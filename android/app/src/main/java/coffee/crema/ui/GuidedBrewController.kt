package coffee.crema.ui

import android.app.Application
import android.os.SystemClock
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.history.StoredShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Guided brew sessions (issue #10), the recipe library, the Brew setup's
 * selections, the recipe and custom-method editors, cues and the Brew Log
 * draft — extracted from MainViewModel (breakup step 3).
 *
 * The scale-only session lane: commands round-trip through the core's
 * BrewSessionMonitor; while a session is live a 250 ms tick drives its
 * countdowns (the core's on_tick is timestamp-based, so cadence jitter
 * is harmless). Cues render as a tone + vibration; no DE1 write ever
 * originates here.
 *
 * Like [LibraryController] it keeps no State flow of its own for the rows:
 * the session, recipes, custom methods and the save-method prompt live in
 * [MainUiState], read and written SYNCHRONOUSLY through [uiFlow]/[updateUi].
 * The editor drafts, the setup selections, the live chart and the Brew Log
 * draft are its own flows, which the VM re-exposes. It owns the recipe
 * store and registers the `BrewSession*` / `BrewStepChanged` / `BrewCueDue`
 * handlers on the [CoreLane].
 */
internal class GuidedBrewController(
    private val app: Application,
    private val json: Json,
    private val scope: CoroutineScope,
    private val bridge: CremaBridge,
    /** Where the session commands' output applies; the session handlers register on it. */
    private val lane: CoreLane,
    /** This device's own UI snapshot (the VM's `_ui`). */
    private val uiFlow: StateFlow<MainUiState>,
    /** The live store's current value, read on the caller's thread. */
    private val liveState: () -> LiveTelemetry,
    /** The UI snapshot as the screens show it (spec §7.2). */
    private val effectiveState: () -> MainUiState,
    /** Synchronously update the UI snapshot (the VM's `_ui.update`). */
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    /** Keep the link alive in the background while a session runs (#65). */
    private val setBrewSessionActive: (Boolean) -> Unit,
    /** Persist the app prefs (the last Brew Log method rides on them). */
    private val persistPrefs: () -> Unit,
    /** Record a manual brew; debits the bag ([LibraryController.addManualBrew]). */
    private val addManualBrew: (LibraryController.ManualBrewInput) -> StoredShot,
    /** Re-tag a logged brew with a method id + label ([LibraryController.retagBrewMethod]). */
    private val retagBrewMethod: (shotId: String, method: String, label: String) -> Unit,
    /** Surface a user-facing message (the VM's snackbar channel). */
    private val notify: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    private fun uiState(): MainUiState = uiFlow.value

    private val _guidedSetup = MutableStateFlow(coffee.crema.ui.brewlog.GuidedBrewSetup())
    private val _recipeEdit = MutableStateFlow<coffee.crema.ui.brewlog.RecipeEditDraft?>(null)
    private val _methodEdit = MutableStateFlow<coffee.crema.ui.brewlog.MethodEditDraft?>(null)
    private val _liveBrewSeries = MutableStateFlow(coffee.crema.core.BrewSeries(samples = emptyList(), stageMarks = emptyList()))

    /**
     * The running session's weight curve + stage marks, for the live chart.
     * Sampled on the session tick from the smoothed scale estimate; the core
     * keeps the authoritative recording (it lands in the summary).
     */
    val liveBrewSeries: StateFlow<coffee.crema.core.BrewSeries> = _liveBrewSeries.asStateFlow()

    private val recipeStore = coffee.crema.brew.RecipeFileStore(app, json)
    private var brewTickJob: kotlinx.coroutines.Job? = null

    /** Test seam: the 250 ms session tick is running. */
    @androidx.annotation.VisibleForTesting
    internal val brewTicking: Boolean get() = brewTickJob?.isActive == true

    /**
     * The open Log-brew form's draft, or null. VM-held (not a screen
     * `remember`) so it survives rotation and the phone↔tablet host swap:
     * the phone's pushed `log-brew` route and the tablet's side sheet on the
     * owning tab render this same draft (see [NavRestore.restoreRoute]).
     */
    private val _logBrew = MutableStateFlow<coffee.crema.ui.brewlog.BrewLogDraft?>(null)
    val logBrew: StateFlow<coffee.crema.ui.brewlog.BrewLogDraft?> = _logBrew.asStateFlow()

    // What the core's session events do, registered while the controller is
    // built (before any output applies).
    init {
        lane.on<Event.BrewSessionStarted> { event ->
            // Anchor the shell clock on the SAME monotonic timebase the
            // notifications feed the core (elapsedRealtime).
            updateUi { st ->
                st.copy(
                    guidedBrew = st.guidedBrew.copy(
                        phase = "running",
                        startedAtRealtime = SystemClock.elapsedRealtime(),
                        stepIndex = 0,
                        stepStartedAtMs = 0L,
                        pausedAccumMs = 0L,
                        pausedSinceRealtime = null,
                    ),
                )
            }
            _liveBrewSeries.value = coffee.crema.core.BrewSeries(
                samples = emptyList(),
                stageMarks = listOf(coffee.crema.ui.brewlog.liveStageMark(uiState().guidedBrew.recipe, 0, 0L)),
            )
            log("Guided brew started — ${event.content.recipe_name}")
        }
        lane.on<Event.BrewStepChanged> { event ->
            updateUi { st ->
                st.copy(
                    guidedBrew = st.guidedBrew.copy(
                        stepIndex = event.content.step_index.toInt(),
                        stepStartedAtMs = event.content.at_ms.toLong(),
                    ),
                )
            }
            if (event.content.step_index.toInt() > 0) {
                _liveBrewSeries.update { s ->
                    s.copy(
                        stageMarks = s.stageMarks + coffee.crema.ui.brewlog.liveStageMark(
                            uiState().guidedBrew.recipe,
                            event.content.step_index.toInt(),
                            event.content.at_ms.toLong(),
                        ),
                    )
                }
            }
            // The chime for an auto-advanced boundary rides the step
            // change itself (Boundary cues cover hold-steps).
            if (event.content.step_index.toInt() > 0) playBrewCue("step")
        }
        lane.on<Event.BrewCueDue> { event ->
            playBrewCue(if (event.content.cue == coffee.crema.core.BrewCue.Approach) "approach" else "boundary")
        }
        lane.on<Event.BrewSessionCompleted> { event ->
            stopBrewTick()
            setBrewSessionActive(false)
            updateUi { st ->
                st.copy(guidedBrew = st.guidedBrew.copy(phase = "done", summary = event.content.summary))
            }
            playBrewCue("done")
            log("Guided brew completed — ${event.content.summary.recipeName}")
        }
    }

    /** Seed the recipe library from disk and keep the custom-method label
     *  fallback current. Called from the VM's `init`, where this used to run. */
    fun load() {
        // Seed the recipe library from disk — cheap (a handful of rows).
        scope.launch {
            val loaded = recipeStore.load()
            // One-time clean-up: untouched legacy "… classic" starters go,
            // their default pointers move to the method's built-in.
            val migrated = coffee.crema.brew.RecipeLibraryRules.migrate(loaded)
            if (migrated != null) recipeStore.save(migrated)
            val env = migrated ?: loaded
            coffee.crema.brew.CustomMethods.all = env.customMethods
            updateUi {
                it.copy(
                    brewRecipes = env.recipes,
                    lastRecipeByMethod = env.lastUsedByMethod,
                    hiddenBuiltinRecipes = env.hiddenBuiltins,
                    builtinRecipes = coffee.crema.brew.BUILTIN_BREW_RECIPES,
                    customMethods = env.customMethods,
                )
            }
            resolveGuidedSetup()
        }
        // Rows saved with a custom method snapshot its label; keep the
        // id → label fallback current for methods this device doesn't know.
        scope.launch {
            uiFlow.map { it.history }.distinctUntilChanged().collect { rows ->
                coffee.crema.brew.CustomMethods.snapshotLabels = buildMap {
                    rows.forEach { r ->
                        val id = r.brewMethod?.trim()?.lowercase() ?: return@forEach
                        val label = r.brewMethodLabel ?: return@forEach
                        if (coffee.crema.brew.isCustomMethodId(id) && id !in this) put(id, label)
                    }
                }
            }
        }
    }

    private fun startBrewTick() {
        if (brewTickJob?.isActive == true) return
        brewTickJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(250)
                val now = SystemClock.elapsedRealtime()
                lane.deliver(bridge.onTick(now.toULong()))
                sampleLiveBrew(now)
            }
        }
    }

    /** Append the current scale reading to the live chart while the clock runs. */
    private fun sampleLiveBrew(now: Long) {
        val st = uiState()
        val session = st.guidedBrew
        if (session.phase != "running") return
        val reading = liveState()
        val w = reading.scaleWeightG ?: return
        if (st.scaleState != coffee.crema.ble.ScaleBleManager.State.READY) return
        val at = session.elapsedMs(now)
        _liveBrewSeries.update { s ->
            if (s.samples.size >= 4_500) {
                s
            } else {
                s.copy(samples = s.samples + coffee.crema.core.BrewSample(elapsedMs = at, weightG = w, flowGS = reading.scaleFlowGPerS))
            }
        }
    }

    private fun stopBrewTick() {
        brewTickJob?.cancel()
        brewTickJob = null
    }

    /** Arm a session for [recipe]; [startOnPour] starts it at the first pour. */
    fun brewSessionArm(recipe: coffee.crema.core.BrewRecipe, startOnPour: Boolean) {
        val recipeJson = json.encodeToString(coffee.crema.core.BrewRecipe.serializer(), recipe)
        lane.deliver(bridge.brewSessionArm(recipeJson, startOnPour))
        rememberRecipeUsed(recipe)
        updateUi {
            it.copy(guidedBrew = GuidedBrewUi(phase = "armed", recipe = recipe, startOnPour = startOnPour))
        }
        _liveBrewSeries.value = coffee.crema.core.BrewSeries(samples = emptyList(), stageMarks = emptyList())
        _guidedSetup.update { it.copy(scaleMode = coffee.crema.ui.brewlog.ScaleMode.BREW) }
        startBrewTick()
        setBrewSessionActive(true)
    }

    /** Start an armed session's clock (the Start tap). */
    fun brewSessionBegin() =
        lane.deliver(bridge.brewSessionBegin(SystemClock.elapsedRealtime().toULong()))

    fun brewSessionPause() {
        val now = SystemClock.elapsedRealtime()
        lane.deliver(bridge.brewSessionPause(now.toULong()))
        updateUi { st ->
            st.copy(guidedBrew = st.guidedBrew.copy(phase = "paused", pausedSinceRealtime = now))
        }
    }

    fun brewSessionResume() {
        val now = SystemClock.elapsedRealtime()
        lane.deliver(bridge.brewSessionResume(now.toULong()))
        updateUi { st ->
            val since = st.guidedBrew.pausedSinceRealtime
            st.copy(
                guidedBrew = st.guidedBrew.copy(
                    phase = "running",
                    pausedAccumMs = st.guidedBrew.pausedAccumMs + (since?.let { s -> now - s } ?: 0L),
                    pausedSinceRealtime = null,
                ),
            )
        }
    }

    fun brewSessionSkip() =
        lane.deliver(bridge.brewSessionSkip(SystemClock.elapsedRealtime().toULong()))

    fun brewSessionFinish() =
        lane.deliver(bridge.brewSessionFinish(SystemClock.elapsedRealtime().toULong()))

    /** Drop the session without a summary (backing out of setup / discard). */
    fun brewSessionCancel() {
        lane.deliver(bridge.brewSessionCancel())
        stopBrewTick()
        setBrewSessionActive(false)
        updateUi { it.copy(guidedBrew = GuidedBrewUi()) }
    }

    /** Clear a finished session's summary card (after save or discard). */
    fun brewSessionClearSummary() {
        updateUi { it.copy(guidedBrew = GuidedBrewUi()) }
    }

    // ── Recipe library ───────────────────────────────────────────────

    /** Create-or-replace a recipe (bumps `updatedAt`) and persist. */
    fun upsertBrewRecipe(recipe: coffee.crema.core.BrewRecipe) {
        val next = recipe.copy(updatedAt = System.currentTimeMillis())
        updateUi { st ->
            val list = st.brewRecipes.filter { it.id != recipe.id } + next
            st.copy(brewRecipes = list)
        }
        persistRecipes()
    }

    /**
     * Clone [id] (a user recipe or a built-in) into an editable copy — the
     * core's `duplicate_recipe`: fresh id, "<name> (copy)", credit "Adapted
     * from …" — and persist it. The Profiles Duplicate / "Duplicate to edit"
     * door. Returns the copy so the caller can open the editor.
     */
    fun duplicateBrewRecipe(id: String): coffee.crema.core.BrewRecipe? {
        val st = uiState()
        val base = coffee.crema.brew.RecipeLibraryRules.find(id, st.brewRecipes, st.builtinRecipes) ?: return null
        val copy = runCatching {
            coffee.crema.brew.RecipeLibraryRules.duplicate(base, coffee.crema.core.newRecipeId(), System.currentTimeMillis())
        }.getOrNull() ?: return null
        updateUi { it.copy(brewRecipes = it.brewRecipes + copy) }
        persistRecipes()
        return copy
    }

    /** Whether [id] is a built-in (read-only) recipe. */
    fun isBuiltinRecipe(id: String): Boolean =
        coffee.crema.brew.RecipeLibraryRules.isBuiltin(id, uiState().builtinRecipes)

    /** Tombstone a user recipe and drop any default pointer at it. Built-ins can't be deleted. */
    fun deleteBrewRecipe(id: String) {
        if (isBuiltinRecipe(id)) return
        val now = System.currentTimeMillis()
        updateUi { st ->
            st.copy(
                brewRecipes = st.brewRecipes.map { if (it.id == id) it.copy(deletedAt = now) else it },
                lastRecipeByMethod = st.lastRecipeByMethod.filterValues { it != id },
            )
        }
        persistRecipes()
        resolveGuidedSetup()
    }

    /** Hide (true) or restore (false) a built-in recipe in the library + picker. */
    fun setBuiltinRecipeHidden(id: String, hidden: Boolean) {
        if (!isBuiltinRecipe(id)) return
        updateUi {
            it.copy(hiddenBuiltinRecipes = if (hidden) it.hiddenBuiltinRecipes + id else it.hiddenBuiltinRecipes - id)
        }
        persistRecipes()
    }

    /** Make [recipe] what the Scale screen opens for its method. */
    fun setDefaultBrewRecipe(recipe: coffee.crema.core.BrewRecipe) = rememberRecipeUsed(recipe)

    /** The recipe [method] opens on (pointer, else its default built-in), or null. */
    fun defaultBrewRecipeFor(method: String): coffee.crema.core.BrewRecipe? {
        val st = effectiveState()
        return coffee.crema.brew.RecipeLibraryRules.defaultFor(method, st.brewRecipes, st.builtinRecipes, st.lastRecipeByMethod)
    }

    // ── Setup selections + the recipe editor (VM-held: survive rotation) ──


    /**
     * The Scale screen's Weigh | Brew mode and the Brew setup's method /
     * recipe / start-on-pour picks. VM-held, not a screen `remember`, so a
     * rotation across 840dp (a nav-host swap) keeps them.
     */
    val guidedSetup: StateFlow<coffee.crema.ui.brewlog.GuidedBrewSetup> = _guidedSetup.asStateFlow()

    fun resolveGuidedSetup() {
        val st = effectiveState()
        _guidedSetup.update {
            coffee.crema.ui.brewlog.GuidedSetupRules.resolve(
                it,
                st.brewRecipes + st.builtinRecipes,
                st.lastRecipeByMethod,
                // A deleted custom method has no chip: open on the default instead.
                st.lastBrewLogMethod?.takeUnless { m -> isDeletedCustomMethod(m) },
            ) { m ->
                coffee.crema.brew.RecipeLibraryRules.defaultFor(m, st.brewRecipes, st.builtinRecipes, st.lastRecipeByMethod)
            }
        }
    }

    fun setScaleMode(mode: String) = _guidedSetup.update { it.copy(scaleMode = mode) }

    /** A method chip: the method's default recipe (pointer, else its built-in), or none. */
    fun selectGuidedMethod(method: String) {
        _guidedSetup.update { it.copy(method = method, recipe = null, methodChosen = true) }
        resolveGuidedSetup()
    }

    /** The multi-recipe dropdown. */
    fun selectGuidedRecipe(recipe: coffee.crema.core.BrewRecipe) {
        _guidedSetup.update { it.copy(method = recipe.method, recipe = recipe, methodChosen = true) }
    }

    fun setGuidedStartOnPour(on: Boolean) = _guidedSetup.update { it.copy(startOnPour = on) }

    /**
     * The setup's bean picker: a bag id or [coffee.crema.ui.brewlog.GuidedBeanRules.NO_BEAN].
     * Setup-only — the app's active bag is untouched (the log form's rule too).
     */
    fun selectGuidedBean(key: String) = _guidedSetup.update { it.copy(beanPick = key) }

    /** The bag the guided brew will debit — the setup's pick, else the active bag. */
    fun guidedBeanId(st: MainUiState = effectiveState()): String? =
        coffee.crema.ui.brewlog.GuidedBeanRules.resolve(_guidedSetup.value.beanPick, st.beans, st.activeBeanId)


    /**
     * The open recipe editor's working copy, or null. VM-held so edits
     * survive rotation: the phone pushes the `recipe-edit` route, the tablet
     * shows a side sheet on the owning tab (Scale's Brew setup, or Profiles),
     * both over this one draft (see [NavRestore.restoreRoute]).
     */
    val recipeEdit: StateFlow<coffee.crema.ui.brewlog.RecipeEditDraft?> = _recipeEdit.asStateFlow()

    fun openRecipeEdit(owner: String, recipe: coffee.crema.core.BrewRecipe, isNew: Boolean = false) {
        _recipeEdit.value = coffee.crema.ui.brewlog.RecipeEditDraft.of(owner, recipe, isNew)
    }

    fun updateRecipeEdit(transform: (coffee.crema.ui.brewlog.RecipeEditDraft) -> coffee.crema.ui.brewlog.RecipeEditDraft) {
        _recipeEdit.update { it?.let(transform) }
    }

    /** The editor's method selector (re-seeds the blank plan for a new recipe). */
    fun setRecipeEditMethod(method: String) = updateRecipeEdit { d ->
        d.withMethod(method) { m -> coffee.crema.brew.newRecipeFor(m, System.currentTimeMillis()) }
    }

    fun closeRecipeEdit() {
        _recipeEdit.value = null
    }

    /**
     * Save the editor's recipe and close it. A method's first saved recipe
     * becomes its default; an edit opened from the Scale setup comes back as
     * that setup's selection, so Save returns to the same setup, updated.
     */
    fun saveRecipeEdit() {
        val d = _recipeEdit.value ?: return
        val now = System.currentTimeMillis()
        val edited = d.toRecipe()
        // Built-ins are read-only: an edit handed a built-in saves as a
        // credited copy, never over the bundled id.
        val saved = if (isBuiltinRecipe(edited.id)) {
            coffee.crema.brew.RecipeLibraryRules.duplicate(edited, coffee.crema.core.newRecipeId(), now)
                .copy(name = edited.name.takeIf { it != d.base.name } ?: "${edited.name} (copy)")
        } else {
            edited.copy(updatedAt = now)
        }
        upsertBrewRecipe(saved)
        if (coffee.crema.brew.RecipeLibraryRules.becomesDefaultOnSave(saved.method, uiState().lastRecipeByMethod)) {
            rememberRecipeUsed(saved)
        }
        if (d.owner == coffee.crema.ui.brewlog.RecipeEditOwner.SCALE) {
            _guidedSetup.update { it.copy(method = saved.method, recipe = saved, methodChosen = true) }
        }
        _recipeEdit.value = null
        resolveGuidedSetup()
    }

    // ── Your own brewing methods (issue #10 feedback) ────────────────
    //
    // The add / edit dialog's draft is VM-held like the other editors: the
    // phone pushes the `method-edit` route (PhoneNavHost follows the draft),
    // the tablet shows a side sheet over whatever is open, and a rotation
    // across 840dp keeps what was typed (see [NavRestore.restoreRoute]).

    val methodEdit: StateFlow<coffee.crema.ui.brewlog.MethodEditDraft?> = _methodEdit.asStateFlow()

    /** Whether [method] names a custom method that has been deleted. */
    private fun isDeletedCustomMethod(method: String): Boolean =
        coffee.crema.brew.isCustomMethodId(method) &&
            uiState().customMethods.none { it.id == method.trim().lowercase() && it.deletedAt == null }

    /** The tab a [target]'s dialog belongs to (the opener's owning tab). */
    private fun methodEditOwnerTab(target: String): String = when (target) {
        coffee.crema.ui.brewlog.MethodEditTarget.LOG -> _logBrew.value?.owner ?: "history"
        coffee.crema.ui.brewlog.MethodEditTarget.RECIPE -> _recipeEdit.value?.owner ?: "profiles"
        coffee.crema.ui.brewlog.MethodEditTarget.SCALE -> "scale"
        else -> "profiles"
    }

    /** "+ Add method…" from [target] (a [coffee.crema.ui.brewlog.MethodEditTarget]). */
    fun openNewMethod(target: String, label: String = "") {
        _methodEdit.value = coffee.crema.ui.brewlog.MethodEditRules.open(
            target = target,
            ownerTab = methodEditOwnerTab(target),
            session = System.nanoTime(),
            label = label,
        )
    }

    /** Profiles → Your methods → Rename / Edit defaults. */
    fun openEditMethod(id: String) {
        val m = uiState().customMethods.firstOrNull { it.id == id } ?: return
        _methodEdit.value = coffee.crema.ui.brewlog.MethodEditRules.edit(
            target = coffee.crema.ui.brewlog.MethodEditTarget.MANAGE,
            ownerTab = "profiles",
            session = System.nanoTime(),
            m = m,
        )
    }

    fun updateMethodEdit(transform: (coffee.crema.ui.brewlog.MethodEditDraft) -> coffee.crema.ui.brewlog.MethodEditDraft) {
        _methodEdit.update { it?.let(transform) }
    }

    fun setMethodEditStyle(style: coffee.crema.core.BrewMethodStyle) = updateMethodEdit {
        coffee.crema.ui.brewlog.MethodEditRules.withStyle(it, style)
    }

    fun closeMethodEdit() {
        _methodEdit.value = null
    }

    /**
     * Validate + save the dialog's method, then select it where it was asked
     * for (the log form, the recipe editor, the Brew setup). Returns false —
     * the dialog stays open with the message — on a bad name.
     */
    fun saveMethodEdit(): Boolean {
        val d = _methodEdit.value ?: return false
        val label = coffee.crema.brew.validateCustomMethodLabel(d.label, d.base?.id, uiState().customMethods)
            .getOrElse { e ->
                _methodEdit.value = d.copy(error = e.message)
                return false
            }
        val method = coffee.crema.ui.brewlog.MethodEditRules.toMethod(
            d,
            label,
            coffee.crema.core.newCustomMethodId(),
            System.currentTimeMillis(),
        )
        setCustomMethods(coffee.crema.ui.brewlog.MethodEditRules.upsert(uiState().customMethods, method))
        _methodEdit.value = null
        when (d.target) {
            coffee.crema.ui.brewlog.MethodEditTarget.LOG -> if (d.isNew) reseedLogBrew(method.id)
            coffee.crema.ui.brewlog.MethodEditTarget.RECIPE -> if (d.isNew) setRecipeEditMethod(method.id)
            coffee.crema.ui.brewlog.MethodEditTarget.SCALE -> if (d.isNew) selectGuidedMethod(method.id)
            else -> {}
        }
        if (!d.isNew) resolveGuidedSetup()
        return true
    }

    /**
     * Delete a custom method: a tombstone, so past brews keep their name and
     * "Log again" still seeds. Recipes that use it are KEPT — still runnable
     * from Profiles, labelled with the method's name. A setup or log form
     * sitting on it falls back to the usual opening method.
     */
    fun deleteCustomMethod(id: String) {
        setCustomMethods(coffee.crema.ui.brewlog.MethodEditRules.tombstone(uiState().customMethods, id, System.currentTimeMillis()))
        if (uiState().lastBrewLogMethod == id) {
            updateUi { it.copy(lastBrewLogMethod = null) }
            persistPrefs()
        }
        if (_guidedSetup.value.method == id) {
            _guidedSetup.update { it.copy(method = null, recipe = null, methodChosen = false) }
        }
        resolveGuidedSetup()
    }

    /** Keep the registry + UI state in step and persist (the recipe store's envelope). */
    private fun setCustomMethods(list: List<coffee.crema.core.CustomBrewMethod>) {
        coffee.crema.brew.CustomMethods.all = list
        updateUi { it.copy(customMethods = list) }
        persistRecipes()
    }

    /**
     * "Save 'X' as a method?" → yes: create the method (percolation; editable
     * later in Profiles) and re-tag that brew with its id + label snapshot. A
     * name that clashes with an existing method re-tags to that one instead.
     */
    fun acceptSaveMethodPrompt() {
        val p = uiState().saveMethodPrompt ?: return
        updateUi { it.copy(saveMethodPrompt = null) }
        val existing = uiState().customMethods.firstOrNull {
            it.deletedAt == null && it.label.equals(p.label, ignoreCase = true)
        }
        val method = existing ?: run {
            val label = coffee.crema.brew.validateCustomMethodLabel(p.label, null, uiState().customMethods)
                .getOrElse { e -> notify(e.message ?: "Couldn’t save that method"); return }
            val now = System.currentTimeMillis()
            coffee.crema.core.CustomBrewMethod(
                id = coffee.crema.core.newCustomMethodId(),
                label = label,
                style = coffee.crema.core.BrewMethodStyle.Percolation,
                createdAt = now,
                updatedAt = now,
            ).also { setCustomMethods(uiState().customMethods + it) }
        }
        retagBrewMethod(p.shotId, method.id, method.label)
        if (uiState().lastBrewLogMethod == p.freeText) {
            updateUi { it.copy(lastBrewLogMethod = method.id) }
            persistPrefs()
        }
        notify("Saved “${method.label}” as a method")
    }

    fun dismissSaveMethodPrompt() = updateUi { it.copy(saveMethodPrompt = null) }

    /** Apply a backup's custom methods (MERGE by id, newer wins / WIPE replaces). */
    fun applyRestoredCustomMethods(methods: List<coffee.crema.core.CustomBrewMethod>, wipe: Boolean): Int {
        if (methods.isEmpty() && !wipe) return 0
        val (list, added) = coffee.crema.ui.brewlog.MethodEditRules.mergeRestored(uiState().customMethods, methods, wipe)
        setCustomMethods(list)
        return added
    }

    /** The finished session's "Save brew…": the Log-brew form, pre-filled with measured truth. */
    fun openGuidedLogBrew() {
        val s = effectiveState()
        val session = s.guidedBrew
        val recipe = session.recipe ?: _guidedSetup.value.recipe
        val summary = session.summary
        _logBrew.value = coffee.crema.ui.brewlog.BrewLogSeeds.open(
            owner = coffee.crema.ui.brewlog.BrewLogOwner.SCALE,
            history = s.history,
            activeBeanId = s.activeBeanId,
            guidedMethod = summary?.method ?: recipe?.method,
            guidedDoseG = recipe?.doseG,
            // The scale's final reading (else the plan): the core seeding
            // routes it to espresso's yield slot or a filter's water-in.
            guidedWeightG = summary?.finalWeightG ?: recipe?.waterG,
            guidedTempC = recipe?.tempC,
            guidedDurationMs = summary?.durationMs,
            guidedRecipeName = summary?.recipeName,
            // A scale-less session records stage marks but no weight: the
            // row saves without a series (spec §8), like a manual log.
            guidedSeries = summary?.series?.takeIf { it.samples.isNotEmpty() },
            // The setup's bag, not the active one; still changeable in the form.
            guidedBeanId = guidedBeanId(s),
        )
    }

    /**
     * Remember [recipe] as its method's default (persisting a new user
     * recipe). A built-in only moves the pointer — it's bundled, never stored.
     */
    private fun rememberRecipeUsed(recipe: coffee.crema.core.BrewRecipe) {
        val builtin = isBuiltinRecipe(recipe.id)
        updateUi { st ->
            val list = if (builtin || st.brewRecipes.any { it.id == recipe.id }) st.brewRecipes
            else st.brewRecipes + recipe
            st.copy(
                brewRecipes = list,
                lastRecipeByMethod = st.lastRecipeByMethod + (recipe.method to recipe.id),
            )
        }
        persistRecipes()
    }

    /**
     * Apply a backup's recipes: adopt user recipes by id (MERGE keeps what's
     * here; WIPE replaces the library), skipping any built-in id, then the
     * `recipeMeta` pointers + hidden built-ins. Returns the recipes added.
     */
    fun applyRestoredRecipes(
        recipes: List<coffee.crema.core.BrewRecipe>,
        meta: kotlinx.serialization.json.JsonObject?,
        wipe: Boolean,
    ): Int {
        val st = uiState()
        val builtinIds = st.builtinRecipes.map { it.id }.toHashSet()
        val base = if (wipe) emptyList() else st.brewRecipes
        val have = base.map { it.id }.toHashSet()
        val added = recipes.filter { it.id !in builtinIds && it.id !in have }
        val defaults = (meta?.get("defaults") as? kotlinx.serialization.json.JsonObject)
            ?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { k to it } }
            ?.toMap().orEmpty()
        val hidden = (meta?.get("hiddenBuiltins") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.filter { it in builtinIds }?.toSet().orEmpty()
        updateUi {
            it.copy(
                brewRecipes = base + added,
                // MERGE: this device's pointers win; the bundle fills the gaps.
                lastRecipeByMethod = if (wipe) defaults else defaults + it.lastRecipeByMethod,
                hiddenBuiltinRecipes = if (wipe) hidden else it.hiddenBuiltinRecipes + hidden,
            )
        }
        persistRecipes()
        resolveGuidedSetup()
        return added.size
    }

    /** Erase All Data: the recipe store (recipes, pointers, hidden built-ins, custom methods). */
    suspend fun eraseStoredData() = recipeStore.erase()

    private fun persistRecipes() {
        val st = uiState()
        scope.launch {
            recipeStore.save(
                coffee.crema.brew.RecipeFileStore.Envelope(
                    st.brewRecipes.filterNot { r -> st.builtinRecipes.any { it.id == r.id } },
                    st.lastRecipeByMethod,
                    st.hiddenBuiltinRecipes,
                    st.customMethods,
                ),
            )
        }
    }

    // ── Cues ─────────────────────────────────────────────────────────

    /**
     * Render one guided-brew cue as a short tone + vibration. Best-effort
     * on both legs — a cue is advisory by definition. Tones ride the
     * notification stream (kitchen volume), vibration uses the default
     * amplitude; devices without a vibrator (most tablets) get tone-only.
     */
    private fun playBrewCue(kind: String) {
        val st = uiState()
        // Visual cue: always on (the step card flashes on this stamp).
        if (kind != "done") {
            updateUi { it.copy(guidedBrew = it.guidedBrew.copy(cueAtRealtime = SystemClock.elapsedRealtime(), cueKind = kind)) }
        }
        if (coffee.crema.ui.brewlog.BrewCueDefaults.soundOn(st.brewCueSound)) playBrewTone(kind)
        if (coffee.crema.ui.brewlog.BrewCueDefaults.hapticsOn(st.brewCueHaptics)) playBrewHaptic(kind)
    }

    private fun playBrewTone(kind: String) {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 70)
            when (kind) {
                "approach" -> tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                "boundary" -> tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 220)
                "step" -> tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 180)
                "done" -> tone.startTone(android.media.ToneGenerator.TONE_CDMA_CONFIRM, 400)
            }
            // Release after the tone has played out.
            scope.launch {
                kotlinx.coroutines.delay(600)
                runCatching { tone.release() }
            }
        }
    }

    private fun playBrewHaptic(kind: String) {
        runCatching {
            val vibrator = androidx.core.content.ContextCompat.getSystemService(
                app,
                android.os.Vibrator::class.java,
            )
            val pattern = when (kind) {
                "approach" -> longArrayOf(0, 60)
                "boundary" -> longArrayOf(0, 80, 60, 80)
                "step" -> longArrayOf(0, 120)
                else -> longArrayOf(0, 100, 60, 100, 60, 160)
            }
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        }
    }

    // ── Brew Log (issue #10) ─────────────────────────────────────────
    /** Open the form on [owner]'s tab — seeded from [prefill] ("Log again") or [beanId]'s bag. */
    fun openLogBrew(owner: String, prefill: StoredShot? = null, beanId: String? = null) {
        val s = effectiveState()
        _logBrew.value = coffee.crema.ui.brewlog.BrewLogSeeds.open(
            owner = owner,
            history = s.history,
            activeBeanId = s.activeBeanId,
            prefill = prefill,
            prefillBeanId = beanId,
            lastUsedMethod = s.lastBrewLogMethod,
            grinderOf = ::beanGrinderSetting,
        )
    }

    /** A library bag's own grinder setting — the log form's grind fallback. */
    private fun beanGrinderSetting(beanId: String): String? =
        uiState().beans.firstOrNull { it.id == beanId }?.grinderSetting

    fun updateLogBrew(transform: (coffee.crema.ui.brewlog.BrewLogDraft) -> coffee.crema.ui.brewlog.BrewLogDraft) {
        _logBrew.update { it?.let(transform) }
    }

    /** A method chip: re-template the draft's seeds for [method]. */
    fun reseedLogBrew(method: String) {
        val history = uiState().history
        _logBrew.update { it?.let { d -> coffee.crema.ui.brewlog.BrewLogSeeds.reseed(d, history, method, ::beanGrinderSetting) } }
    }

    fun closeLogBrew() {
        _logBrew.value = null
    }

    /**
     * Validate + save the draft as a manual brew (bag debit included) and
     * close the form. Returns false — the draft stays open, flagged
     * `attempted` — when the method name or a debit-able dose is missing.
     */
    fun saveLogBrew(nowMs: Long = System.currentTimeMillis()): Boolean {
        val d = _logBrew.value ?: return false
        val flagged = d.copy(attempted = true)
        val bean = d.beanId?.let { id -> uiState().beans.firstOrNull { it.id == id } }
        // Never persist an empty method (blank → null → not saved); the
        // presets are already normalized ids.
        val storedMethod = coffee.crema.core.normalizeBrewMethod(if (d.isCustom) d.customMethod else d.method)
        if (storedMethod == null || d.doseMissing(bean != null)) {
            _logBrew.value = flagged
            return false
        }
        // A custom method's label rides on the row, so the brew keeps its
        // name if the method is later deleted (or lost on another device).
        val customLabel = if (coffee.crema.brew.isCustomMethodId(storedMethod)) {
            coffee.crema.brew.CustomMethods.find(storedMethod)?.label
        } else {
            null
        }
        val saved = addManualBrew(
            LibraryController.ManualBrewInput(
                method = storedMethod,
                methodLabel = customLabel,
                completedAtMs = nowMs - (d.minutesAgo * 60_000).toLong(),
                beanId = bean?.id,
                doseG = d.dose.toFloat().takeIf { it > 0f },
                waterG = if (!d.espresso) d.water.toFloat().takeIf { it > 0f } else null,
                yieldG = if (d.espresso) d.water.toFloat().takeIf { it > 0f } else null,
                grindSetting = d.grind.toFloat().takeIf { it > 0f },
                brewTempC = d.temp.toFloat().takeIf { it > 0f },
                durationMs = coffee.crema.ui.brewlog.parseBrewDurationMs(d.timeStr),
                rating = d.rating.takeIf { it > 0 },
                notes = d.notes.ifBlank { null },
                nextPlan = d.nextPlan.ifBlank { null },
                recipeName = d.recipeName,
                brewSeries = d.series,
            ),
        )
        // "Other…" free text → a one-time offer to keep it as a method (not
        // when it already names a preset — the normalized id would match).
        if (d.isCustom && coffee.crema.brew.BREW_METHOD_PRESETS.none { it.id == storedMethod }) {
            updateUi {
                it.copy(saveMethodPrompt = coffee.crema.ui.brewlog.SaveMethodPrompt(saved.id, d.customMethod.trim(), storedMethod))
            }
        }
        // Remember the method so the next log opens on it (web parity).
        if (uiState().lastBrewLogMethod != storedMethod) {
            updateUi { it.copy(lastBrewLogMethod = storedMethod) }
            persistPrefs()
            resolveGuidedSetup()
        }
        _logBrew.value = null
        // A guided session's summary card ends once its brew is saved.
        if (d.owner == coffee.crema.ui.brewlog.BrewLogOwner.SCALE && uiState().guidedBrew.phase == "done") {
            brewSessionClearSummary()
        }
        return true
    }
}
