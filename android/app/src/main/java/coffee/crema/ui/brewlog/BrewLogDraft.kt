package coffee.crema.ui.brewlog

import coffee.crema.brew.formatClock
import coffee.crema.brew.isEspressoMethod
import coffee.crema.brew.presetFor
import coffee.crema.core.BrewSeries
import coffee.crema.history.StoredShot
import coffee.crema.history.methodOf

/*
 * The Log-brew form's draft (issue #10), held ABOVE the layout — in
 * MainViewModel — so it survives a rotation and the phone↔tablet nav-host
 * swap at the 840dp breakpoint: the phone's pushed `log-brew` route and the
 * tablet's side sheet render the same draft. Pure Kotlin (no Android, no FFI):
 * unit-tested.
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
    val grind: Double,
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
) {
    val isCustom: Boolean get() = method == OTHER
    val espresso: Boolean get() = isEspressoMethod(if (isCustom) customMethod else method)

    /** A bag is chosen but the dose is empty — the bag could not be debited. */
    fun doseMissing(beanExists: Boolean): Boolean = beanExists && dose <= 0.0

    companion object {
        const val OTHER = "other"
    }
}

object BrewLogSeeds {
    /** The newest brew of [method], on [beanId]'s bag first, else any bag. */
    fun lastBrewOf(history: List<StoredShot>, method: String, beanId: String?): StoredShot? {
        val matches: (StoredShot) -> Boolean = { (it.methodOf ?: "espresso") == method }
        return beanId?.let { bid -> history.firstOrNull { matches(it) && it.bean?.beanId == bid } }
            ?: history.firstOrNull(matches)
    }

    /**
     * A fresh draft: a "Log again" [prefill] re-fills from that brew; else
     * the bag's (or any bag's) last brew of the method; else the preset
     * seeds. [prefillBeanId] (the bean-detail door) wins over the active bag.
     */
    fun open(
        owner: String,
        history: List<StoredShot>,
        activeBeanId: String?,
        prefill: StoredShot? = null,
        prefillBeanId: String? = null,
        guidedMethod: String? = null,
        guidedDoseG: Float? = null,
        guidedWaterG: Float? = null,
        guidedTempC: Float? = null,
        guidedDurationMs: Long? = null,
        guidedRecipeName: String? = null,
        guidedSeries: BrewSeries? = null,
    ): BrewLogDraft {
        val method = guidedMethod ?: prefill?.methodOf ?: "pourover"
        val beanId = prefillBeanId ?: prefill?.bean?.beanId ?: activeBeanId
        val last = lastBrewOf(history, method, beanId)
        val preset = presetFor(method)
        return BrewLogDraft(
            owner = owner,
            method = method,
            beanId = beanId,
            dose = (guidedDoseG ?: prefill?.doseG ?: last?.doseG ?: preset?.seedDose ?: 15f).toDouble(),
            water = (
                guidedWaterG ?: prefill?.let { it.waterG ?: it.yieldG }
                    ?: last?.let { it.waterG ?: it.yieldG }
                    ?: preset?.seedWater ?: preset?.seedYield ?: 250f
                ).toDouble(),
            grind = (prefill?.grindSetting ?: last?.grindSetting ?: 0f).toDouble(),
            temp = (guidedTempC ?: prefill?.brewTempC ?: last?.brewTempC ?: preset?.seedTemp ?: 0f).toDouble(),
            timeStr = (guidedDurationMs ?: prefill?.durationMs?.takeIf { it > 0 })?.let { formatClock(it) } ?: "",
            recipeName = guidedRecipeName,
            series = guidedSeries,
        )
    }

    /** A method chip tap: re-template the numeric seeds for [newMethod]. */
    fun reseed(draft: BrewLogDraft, history: List<StoredShot>, newMethod: String): BrewLogDraft {
        if (newMethod == BrewLogDraft.OTHER) return draft.copy(method = newMethod)
        val preset = presetFor(newMethod)
        val last = lastBrewOf(history, newMethod, draft.beanId)
        val esp = isEspressoMethod(newMethod)
        return draft.copy(
            method = newMethod,
            dose = (last?.doseG ?: preset?.seedDose ?: 15f).toDouble(),
            water = (
                (if (esp) last?.yieldG else last?.waterG ?: last?.yieldG)
                    ?: (if (esp) preset?.seedYield ?: 36f else preset?.seedWater ?: 250f)
                ).toDouble(),
            temp = (last?.brewTempC ?: preset?.seedTemp ?: 0f).toDouble(),
            grind = (last?.grindSetting ?: 0f).toDouble(),
        )
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
