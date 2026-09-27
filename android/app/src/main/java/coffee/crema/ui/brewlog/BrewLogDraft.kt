package coffee.crema.ui.brewlog

import coffee.crema.brew.formatClock
import coffee.crema.brew.isEspressoMethod
import coffee.crema.core.BrewLogPrefill
import coffee.crema.core.BrewLogSeedInput
import coffee.crema.core.BrewSeedInput
import coffee.crema.core.BrewSeries
import coffee.crema.core.brewLogSeedsJson
import coffee.crema.history.StoredShot
import coffee.crema.history.methodOf
import kotlinx.serialization.json.Json
import coffee.crema.core.BrewLogSeeds as CoreBrewLogSeeds

/*
 * The Log-brew form's draft (issue #10), held ABOVE the layout — in
 * MainViewModel — so it survives a rotation and the phone↔tablet nav-host
 * swap at the 840dp breakpoint: the phone's pushed `log-brew` route and the
 * tablet's side sheet render the same draft. The seed numbers come from the
 * core; the FFI call is injectable, so this stays JVM unit-testable.
 */

/** The tab a Log-brew form belongs to — where the tablet shows its sheet. */
object BrewLogOwner {
    const val HISTORY = "history"
    const val BEANS = "beans"

    /** A finished guided session's "Save brew…" (issue #10 Phase 2). */
    const val SCALE = "scale"
}

/** The pushed phone route for the form (see [coffee.crema.ui.NavRestore]). */
const val LOG_BREW_ROUTE = "log-brew"

data class BrewLogDraft(
    /** The owning tab ([BrewLogOwner]) — the tablet opens its sheet there. */
    val owner: String,
    /** A preset id, or `"other"` while [customMethod] is being typed. */
    val method: String,
    val customMethod: String = "",
    val beanId: String?,
    val dose: Double,
    /** Water-in for filter methods, yield-out for espresso. */
    val water: Double,
    /** 0 = no grind known. */
    val grind: Double,
    /** 0 = no temperature. */
    val temp: Double,
    /** Brew time as typed ("3:05" or seconds). */
    val timeStr: String = "",
    val minutesAgo: Double = 0.0,
    val rating: Int = 0,
    val notes: String = "",
    val nextPlan: String = "",
    /** A save was attempted — validation messages show from then on. */
    val attempted: Boolean = false,
    /** The journal disclosure (rating / notes / next time) — closed by default. */
    val journalOpen: Boolean = false,
    /** Guided-session hand-off (Phase 2): the recipe name + weight series. */
    val recipeName: String? = null,
    val series: BrewSeries? = null,
    /**
     * Seed fields the user edited this session ([DOSE], [WATER], [GRIND],
     * [TEMP], [TIME]) — a method switch re-seeds only the others (web
     * parity).
     */
    val dirty: Set<String> = emptySet(),
    /** The opening prefill, kept so switching back to its method re-applies it. */
    val prefill: BrewLogPrefill? = null,
) {
    val isCustom: Boolean get() = method == OTHER
    val espresso: Boolean get() = isEspressoMethod(if (isCustom) customMethod else method)

    /** A bag is chosen but the dose is empty — the bag could not be debited. */
    fun doseMissing(beanExists: Boolean): Boolean = beanExists && dose <= 0.0

    /** Mark [field] user-edited — method switches stop re-seeding it. */
    fun edited(field: String): BrewLogDraft = if (field in dirty) this else copy(dirty = dirty + field)

    companion object {
        const val OTHER = "other"
        const val DOSE = "dose"
        const val WATER = "water"
        const val GRIND = "grind"
        const val TEMP = "temp"
        const val TIME = "time"
    }
}

/**
 * The Log-brew form's seeding — a thin shell over the core's
 * `brew_log_seeds` rule (prefill → the last brew of the method, this bag
 * first → the bag's grinder setting → the method preset). The shell only
 * projects history into [BrewSeedInput] rows and keeps the dirty-field
 * tracking. [seedsJson] is the FFI call, injectable so JVM unit tests (no
 * native lib) can stand in for it.
 */
object BrewLogSeeds {
    private val json = Json { ignoreUnknownKeys = true }

    /** A stored row → the core's seeding projection. */
    fun seedRow(s: StoredShot): BrewSeedInput = BrewSeedInput(
        brewMethod = s.brewMethod,
        beanId = s.bean?.beanId,
        doseG = s.doseG,
        waterG = s.waterG,
        yieldG = s.yieldG,
        grinderSetting = s.grindSetting?.let { g ->
            if (g % 1f == 0f) String.format(java.util.Locale.US, "%.0f", g) else String.format(java.util.Locale.US, "%.1f", g)
        },
        tempC = s.brewTempC,
        durationMs = s.durationMs,
    )

    /** Ask the core for the seeds of [method] (`null` = the opening method). */
    fun seeds(
        history: List<StoredShot>,
        method: String?,
        lastUsedMethod: String?,
        beanId: String?,
        beanGrinderSetting: String?,
        prefill: BrewLogPrefill?,
        seedsJson: (String) -> String = ::brewLogSeedsJson,
    ): CoreBrewLogSeeds {
        val input = BrewLogSeedInput(
            method = method,
            lastUsedMethod = lastUsedMethod,
            beanId = beanId,
            beanGrinderSetting = beanGrinderSetting,
            prefill = prefill,
            rows = history.map(::seedRow),
        )
        return json.decodeFromString(CoreBrewLogSeeds.serializer(), seedsJson(json.encodeToString(BrewLogSeedInput.serializer(), input)))
    }

    /** Apply [s] to every seed field the user hasn't edited. */
    fun apply(d: BrewLogDraft, s: CoreBrewLogSeeds): BrewLogDraft = d.copy(
        dose = if (BrewLogDraft.DOSE in d.dirty) d.dose else s.dose.toDouble(),
        water = if (BrewLogDraft.WATER in d.dirty) d.water else s.water.toDouble(),
        grind = if (BrewLogDraft.GRIND in d.dirty) d.grind else (s.grind ?: 0f).toDouble(),
        temp = if (BrewLogDraft.TEMP in d.dirty) d.temp else (s.tempC ?: 0f).toDouble(),
        timeStr = if (BrewLogDraft.TIME in d.dirty) d.timeStr else s.durationMs?.takeIf { it > 0 }?.let(::formatClock).orEmpty(),
    )

    /**
     * A fresh draft. The opening method is the core's call: a "Log again"
     * [prefill]'s / guided session's method, else [lastUsedMethod], else
     * pourover. [prefillBeanId] (the bean-detail door) wins over the active
     * bag. [grinderOf] resolves a bag's own grinder setting. A guided
     * session passes the setup's bag as [guidedBeanId] — explicitly, so its
     * "No bean" (null) doesn't fall back to the active bag.
     */
    fun open(
        owner: String,
        history: List<StoredShot>,
        activeBeanId: String?,
        prefill: StoredShot? = null,
        prefillBeanId: String? = null,
        lastUsedMethod: String? = null,
        grinderOf: (String) -> String? = { null },
        guidedMethod: String? = null,
        guidedDoseG: Float? = null,
        guidedWeightG: Float? = null,
        guidedTempC: Float? = null,
        guidedDurationMs: Long? = null,
        guidedRecipeName: String? = null,
        guidedSeries: BrewSeries? = null,
        guidedBeanId: String? = activeBeanId,
        seedsJson: (String) -> String = ::brewLogSeedsJson,
    ): BrewLogDraft {
        val beanId = if (guidedMethod != null) guidedBeanId else prefillBeanId ?: prefill?.bean?.beanId ?: activeBeanId
        val corePrefill = when {
            guidedMethod != null -> BrewLogPrefill(
                method = guidedMethod,
                doseG = guidedDoseG,
                // The scale weighs the vessel: beverage-out (yield) for the
                // espresso family — the slot the core seeds espresso from —
                // water-in for filter methods (drift bug 9).
                waterG = guidedWeightG.takeUnless { coffee.crema.brew.isEspressoMethod(guidedMethod) },
                yieldG = guidedWeightG.takeIf { coffee.crema.brew.isEspressoMethod(guidedMethod) },
                tempC = guidedTempC,
                durationMs = guidedDurationMs,
            )
            prefill != null -> BrewLogPrefill(
                method = prefill.methodOf ?: "espresso",
                doseG = prefill.doseG,
                waterG = prefill.waterG,
                yieldG = prefill.yieldG,
                grinderSetting = seedRow(prefill).grinderSetting,
                tempC = prefill.brewTempC,
                durationMs = prefill.durationMs.takeIf { it > 0 },
            )
            else -> null
        }
        val s = seeds(history, null, lastUsedMethod, beanId, beanId?.let(grinderOf), corePrefill, seedsJson)
        val blank = BrewLogDraft(
            owner = owner,
            method = s.method,
            beanId = beanId,
            dose = 0.0,
            water = 0.0,
            grind = 0.0,
            temp = 0.0,
            recipeName = guidedRecipeName,
            series = guidedSeries,
            prefill = corePrefill,
        )
        return apply(blank, s)
    }

    /**
     * A method chip tap: re-seed [newMethod]'s numbers into the fields the
     * user hasn't edited. "Other…" has no seeds until a name is typed, so it
     * keeps what's there.
     */
    fun reseed(
        draft: BrewLogDraft,
        history: List<StoredShot>,
        newMethod: String,
        grinderOf: (String) -> String? = { null },
        seedsJson: (String) -> String = ::brewLogSeedsJson,
    ): BrewLogDraft {
        if (newMethod == BrewLogDraft.OTHER) return draft.copy(method = newMethod)
        val s = seeds(history, newMethod, null, draft.beanId, draft.beanId?.let(grinderOf), draft.prefill, seedsJson)
        return apply(draft.copy(method = newMethod), s)
    }
}

/** "3:05" or "185" (seconds) → ms; blank or unparseable → null. */
fun parseBrewDurationMs(raw: String): Long? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    Regex("^(\\d+):([0-5]?\\d)$").find(t)?.let { m ->
        return (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000
    }
    return t.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
}
