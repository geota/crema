package coffee.crema.ui

import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.DEFAULT_GRIND_SCALE
import coffee.crema.beans.activeGrinder
import coffee.crema.beans.formatGrind
import coffee.crema.beans.isLive
import coffee.crema.beans.savedGrind
import coffee.crema.core.BagGrind
import coffee.crema.core.Bean
import coffee.crema.core.BrewGrindView
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder

/*
 * What Brew reads of the grinders and the bag grinds (#488): the active
 * grinder and its scale, the bag's saved grind for it, and the unsaved-grind
 * marker (DESIGN.md "Unsaved grind marker"). Pure reads of the state, so the
 * screens and the Save plan agree.
 */

/** The library file this device keeps, from this state: every row, grinders included, and both pointers. */
internal fun MainUiState.libraryFile(): BeanLibrary = BeanLibrary(beans, roasters, activeBeanId, grinders, activeGrinderId)

/** The grinders the pickers list: owned, not archived. */
val MainUiState.liveGrinders: List<Grinder> get() = grinders.filter { it.isLive }

/** Archived grinders (Devices → Grinder "Archived"), not tombstones. */
val MainUiState.archivedGrinders: List<Grinder> get() = grinders.filter { it.archivedAt != null && it.deletedAt == null }

/**
 * The active grinder as Brew shows it: on a remote the hub's, from its
 * snapshot (a remote's library is its own, review F3), else this device's
 * live record. Null = no grinder: the default scale.
 */
fun MainUiState.activeGrinder(): Grinder? =
    mirroredGrinder?.takeIf { it.id == activeGrinderId } ?: grinders.activeGrinder(activeGrinderId)

/**
 * Whether this record is the grinder [id] named [name] (#488 follow-ups 19,
 * 39): the same id and the same name (trimmed, any case). Ids are slugs of
 * the first name, so a grinder renamed since, or another device's grinder
 * of that name, can share an id with a different record. A null [name]
 * (not said) is never the same.
 */
fun Grinder.sameRecord(id: String, name: String?): Boolean =
    this.id == id && name != null && this.name.trim().equals(name.trim(), ignoreCase = true)

/**
 * The grinder this device's own records may name for what Brew shows (#488
 * follow-up 39): on a remote, the hub's active grinder only where this
 * device's library has that same live record, else none (never a dangling
 * hub id); elsewhere the active grinder.
 */
fun MainUiState.ownActiveGrinder(): Grinder? {
    val shown = activeGrinder() ?: return null
    if (mirror == null) return shown
    return grinders.firstOrNull { it.isLive && it.sameRecord(shown.id, shown.name) }
}

/** The scale Brew's grind uses: the active grinder's, else the default 0–20 in 0.1. */
fun MainUiState.activeGrindScale(): GrindScale = activeGrinder()?.scale ?: DEFAULT_GRIND_SCALE

/** The bag on Brew. */
fun MainUiState.activeBag(): Bean? = beans.firstOrNull { it.id == activeBeanId }

/**
 * The bag Brew's grind compares with (#488 S8): on a remote the hub's, from
 * its snapshot ([MainUiState.mirroredBag]: the hub's saved grind for the
 * hub's grinder), never this device's own copy, which isn't synced; none
 * until the hub's snapshot names this bag. Elsewhere [activeBag].
 */
fun MainUiState.grindBag(): Bean? =
    if (mirror != null) mirroredBag?.takeIf { it.id == activeBeanId } else activeBag()

/** The bag's saved grind for the active grinder (the "no grinder" slot with none). */
fun MainUiState.savedGrindForBrew(): BagGrind? = grindBag()?.savedGrind(activeGrinder()?.id)

/** The last [brewGrindView] answer and what it was asked: the screens read it on every recomposition. */
private class GrindViewMemo(val bag: Bean?, val grinder: Grinder?, val grind: String?, val view: BrewGrindView)

@Volatile private var grindViewMemo: GrindViewMemo? = null

/**
 * What Brew's grind is, from the core (`brew_grind_view`, DESIGN "No grind
 * set is its own state"): [BrewGrindView.value] is what the header and strip
 * show and what the shot records (null = not set: "Grind —", no marker, and
 * the first + or − goes to [BrewGrindView.startFromBlank]); the marker and
 * Save's grind part come with it. On a remote, the hub's grinder and grind.
 */
fun MainUiState.brewGrindView(): BrewGrindView {
    val bag = grindBag()
    val grinder = activeGrinder()
    grindViewMemo?.takeIf { it.bag == bag && it.grinder == grinder && it.grind == qcGrind }?.let { return it.view }
    val view = runCatching { coffee.crema.beans.brewGrindView(bag, grinder, qcGrind) }.getOrElse {
        // Never expected (the input is this app's own types); keep Brew drawable.
        val scale = grinder?.scale ?: DEFAULT_GRIND_SCALE
        val value = qcGrind?.trim()?.takeIf { g -> g.isNotEmpty() }
        BrewGrindView(scale = scale, value = value, display = value ?: "—", startFromBlank = minOf(scale.min, scale.max).toDouble())
    }
    grindViewMemo = GrindViewMemo(bag, grinder, qcGrind, view)
    return view
}

/** "Grind 480 μm" or "Grind —": Brew's grind in the header and the phone strip. */
fun BrewGrindView.headerText(): String = "Grind $display"

/** The stepper's blank state ([coffee.crema.ui.components.StepperBlank]): the grind isn't set ("—") or isn't a plain number ("2.1.3"). */
fun BrewGrindView.stepperBlank(): coffee.crema.ui.components.StepperBlank? =
    if (dial != null) null else coffee.crema.ui.components.StepperBlank(value ?: "—", startFromBlank)

/** A scale bound or step as Devices and the pickers show it: "999", "0.1". */
fun scaleNumber(v: Float): String = if (v % 1f == 0f) String.format(java.util.Locale.ROOT, "%.0f", v) else v.toString()

/** The Step field's text: "⅓" on a thirds scale (core `counts_in_thirds`, the Fellow Ode), not 0.33333334; else [scaleNumber]. */
fun scaleStepField(scale: GrindScale): String = if (coffee.crema.beans.countsInThirds(scale)) "⅓" else scaleNumber(scale.step)

/** A typed scale field as a number: "0,5" reads 0.5, "⅓" and "1/3" a third (core `grind_number`); null when it isn't one. */
fun scaleFieldNumber(text: String): Float? =
    coffee.crema.beans.grindNumber(text)?.toFloat() ?: text.trim().replace(',', '.').toFloatOrNull()

/**
 * What Devices says about a catalogue preset's scale (#501): "From the X
 * preset" for a verified range, "Typical range — check your dial" for a
 * typical one.
 */
fun presetLabel(entry: coffee.crema.core.CatalogueGrinderInfo): String =
    if (entry.tier == coffee.crema.core.ScaleTier.Typical) "Typical range — check your dial" else "From the ${entry.name} preset"

/** "0–999 μm", or "0–20" with no unit. */
fun scaleRangeText(scale: GrindScale): String =
    "${scaleNumber(scale.min)}–${scaleNumber(scale.max)}" + scale.unit.trim().takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

/** The newest shot grind on [lastGrinds] for [grinder]: stamped with its id, or an older row by its name. */
fun newestShotGrind(lastGrinds: List<coffee.crema.core.LastGrind>, grinder: Grinder): coffee.crema.core.LastGrind? =
    lastGrinds.filter { it.grind.isNotBlank() && (it.grinderId == grinder.id || (it.grinderId == null && it.grinder.trim().equals(grinder.name.trim(), ignoreCase = true))) }
        .maxByOrNull { it.completedAt }

/** The scale a stored shot's grind is on: its grinder's by id, an older row's by name, else the default. */
fun shotGrindScale(shot: coffee.crema.history.ShotMeta, grinders: List<Grinder>): GrindScale =
    grinders.firstOrNull { g -> !shot.grinderId.isNullOrEmpty() && g.id == shot.grinderId }?.scale
        ?: shot.grinderModel?.trim()?.takeIf { shot.grinderId.isNullOrEmpty() && it.isNotEmpty() }
            ?.let { name -> grinders.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.scale }
        ?: DEFAULT_GRIND_SCALE

/** One grinder row in the Brew pickers (wide header, phone swap section). */
data class GrinderPickRow(val grinder: Grinder, val starred: Boolean, val meta: String)

/**
 * The pickers' rows for [grinders] (the live ones) with bag [bag] selected:
 * "This bag's grinder · saved 14" on the starred one, "Saved 480 μm", or
 * "Nothing saved · last shot 32"; with no bag, the grinder's range.
 */
fun grinderPickRows(grinders: List<Grinder>, bag: Bean?, lastGrinds: List<coffee.crema.core.LastGrind>): List<GrinderPickRow> =
    grinders.map { g ->
        val saved = bag?.savedGrind(g.id)
        val starred = saved?.starred == true
        val meta = when {
            bag == null -> scaleRangeText(g.scale)
            saved != null && starred -> "This bag's grinder · saved ${formatGrind(saved.grind, g.scale)}"
            saved != null -> "Saved ${formatGrind(saved.grind, g.scale)}"
            else -> newestShotGrind(lastGrinds, g)?.let { "Nothing saved · last shot ${formatGrind(it.grind, g.scale)}" } ?: "Nothing saved"
        }
        GrinderPickRow(g, starred, meta)
    }

/** The explanation behind the unsaved-grind marker (hover / long-press). */
fun MainUiState.unsavedGrindExplanation(): String? {
    val view = brewGrindView()
    val marker = view.marker ?: return null
    val bag = grindBag() ?: return null
    val grinder = activeGrinder()
    val forGrinder = grinder?.name?.let { " for $it" }.orEmpty()
    val has = marker.saved?.let { "${bag.name} has ${formatGrind(it, view.scale)} saved$forGrinder." } ?: "${bag.name} has no grind saved$forGrinder yet."
    val now = view.display
    return "$has Brew is at $now for the next shot. Save in Quick Controls saves it."
}

/** What the type-or-pick box says under catalogue entry [id] (review 499, #501):
 *  its scale comes with it, a typical range says to check the dial, and one
 *  without a range gets the default scale, set on Devices. */
fun catalogueOfferLine(id: String): String {
    val entry = coffee.crema.beans.grinderCatalogue.firstOrNull { it.id == id }
    return when {
        entry?.scale == null -> "Adds it to your grinders · set its range on Devices"
        entry.tier == coffee.crema.core.ScaleTier.Typical -> "Adds it to your grinders · Typical range — check your dial"
        else -> "Adds it to your grinders, with its scale"
    }
}

/** What the type-or-pick box picked (#488, "Add a grinder: type or pick"). */
sealed interface GrinderPick {
    /** One of the user's grinders. */
    data class Owned(val id: String) : GrinderPick

    /** A catalogue entry: added to the user's grinders with its scale. */
    data class Catalogue(val id: String, val name: String) : GrinderPick

    /** An archived grinder: restored, same id, its bag grinds come back. */
    data class Archived(val id: String) : GrinderPick

    /** "+ Create “text”": a new grinder with the default scale. */
    data class Create(val name: String) : GrinderPick
}
