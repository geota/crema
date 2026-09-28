package coffee.crema.ui.brewlog

import coffee.crema.brew.CoreJson
import coffee.crema.brew.customMethodIcon
import coffee.crema.brew.styleIcon
import coffee.crema.core.BrewMethodPreset
import coffee.crema.core.BrewMethodStyle
import coffee.crema.core.CustomBrewMethod
import coffee.crema.core.brewMethodStyleSeedsJson
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The add / edit brewing-method dialog's draft (issue #10 feedback) — held in
 * MainViewModel like the Log-brew and recipe-editor drafts, so a rotation (and
 * the phone-route ↔ tablet-sheet swap at 840dp) keeps what was typed. Pure
 * rules; the core's style seeds come through an injectable FFI call so this
 * stays JVM-testable.
 */

/** Who asked for a method — decides what "create AND select" selects. */
object MethodEditTarget {
    /** The Log-brew form's "+ Add method…" chip. */
    const val LOG = "log"

    /** The recipe editor's method selector. */
    const val RECIPE = "recipe"

    /** Scale → Brew setup's method chips. */
    const val SCALE = "scale"

    /** Profiles → "Your methods" (add / rename / edit defaults). */
    const val MANAGE = "manage"
}

/** The phone's pushed method dialog route (see [coffee.crema.ui.NavRestore]). */
const val METHOD_EDIT_ROUTE = "method-edit"

data class MethodEditDraft(
    /** A per-open token: the phone host pushes its route once per session. */
    val session: Long,
    val target: String,
    /** The tab the dialog belongs to — where the tablet shows it / the phone restores to. */
    val ownerTab: String,
    /** The method being edited, or null for a new one. */
    val base: CustomBrewMethod? = null,
    val label: String = "",
    val style: BrewMethodStyle = BrewMethodStyle.Percolation,
    /**
     * The icon key the dialog highlights and saves — the style's default
     * until the user picks one ([iconPicked]), as the web dialog does.
     */
    val icon: String = styleIcon(BrewMethodStyle.Percolation),
    /** The user picked [icon] (or the method being edited stored one): a style change leaves it alone. */
    val iconPicked: Boolean = false,
    val dose: String = "",
    val water: String = "",
    val temp: String = "",
    /** Seed fields holding the user's own number ([DOSE], [WATER], [TEMP]); the rest follow the style. */
    val explicit: Set<String> = emptySet(),
    /** The last validation message, shown under the name. */
    val error: String? = null,
) {
    val isNew: Boolean get() = base == null
    val heading: String get() = if (isNew) "New brewing method" else "Edit ${base?.label ?: "method"}"

    /** The pushed route the dialog sits on top of on the phone, if any. */
    val parentRoute: String?
        get() = when (target) {
            MethodEditTarget.LOG -> LOG_BREW_ROUTE
            MethodEditTarget.RECIPE -> RECIPE_EDIT_ROUTE
            else -> null
        }

    companion object {
        const val DOSE = "dose"
        const val WATER = "water"
        const val TEMP = "temp"
    }
}

object MethodEditRules {
    /** The style's stand-in seeds — the core's `brew_method_style_seeds`. */
    fun styleSeeds(style: BrewMethodStyle, core: (String) -> String = ::brewMethodStyleSeedsJson): BrewMethodPreset? =
        runCatching { CoreJson.decodeFromString(BrewMethodPreset.serializer(), core(style.string)) }.getOrNull()

    /** "15", "15.5", or "" for none. */
    fun fmt(v: Float?): String = when {
        v == null -> ""
        v == v.roundToInt().toFloat() -> v.roundToInt().toString()
        else -> String.format(Locale.US, "%.1f", v)
    }

    /** A typed seed → grams / °C; blank, unparseable or non-positive → null (the style default). */
    fun parse(raw: String): Float? = raw.trim().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }

    /** A fresh draft — fields prefilled from [style]'s seeds, [label] from an "Other…" name. */
    fun open(
        target: String,
        ownerTab: String,
        session: Long,
        label: String = "",
        style: BrewMethodStyle = BrewMethodStyle.Percolation,
        core: (String) -> String = ::brewMethodStyleSeedsJson,
    ): MethodEditDraft = prefill(
        MethodEditDraft(session = session, target = target, ownerTab = ownerTab, label = label, style = style, icon = styleIcon(style)),
        core,
    )

    /** Edit an existing method: its own seeds where set (explicit), the style's where blank. */
    fun edit(
        target: String,
        ownerTab: String,
        session: Long,
        m: CustomBrewMethod,
        core: (String) -> String = ::brewMethodStyleSeedsJson,
    ): MethodEditDraft {
        val explicit = buildSet {
            if (m.seedDoseG != null) add(MethodEditDraft.DOSE)
            if (m.seedWaterG != null) add(MethodEditDraft.WATER)
            if (m.seedTempC != null) add(MethodEditDraft.TEMP)
        }
        val d = MethodEditDraft(
            session = session,
            target = target,
            ownerTab = ownerTab,
            base = m,
            label = m.label,
            style = m.style ?: BrewMethodStyle.Percolation,
            icon = customMethodIcon(m),
            iconPicked = m.icon != null,
            dose = fmt(m.seedDoseG),
            water = fmt(m.seedWaterG),
            temp = fmt(m.seedTempC),
            explicit = explicit,
        )
        return prefill(d, core)
    }

    /** Re-fill every non-explicit seed field from the draft's style. */
    fun prefill(d: MethodEditDraft, core: (String) -> String = ::brewMethodStyleSeedsJson): MethodEditDraft {
        val s = styleSeeds(d.style, core) ?: return d
        return d.copy(
            dose = if (MethodEditDraft.DOSE in d.explicit) d.dose else fmt(s.seedDoseG),
            water = if (MethodEditDraft.WATER in d.explicit) d.water else fmt(s.seedWaterG),
            temp = if (MethodEditDraft.TEMP in d.explicit) d.temp else fmt(s.seedTempC),
        )
    }

    /** A style pick: the untouched seed fields — and the icon, until picked — follow it. */
    fun withStyle(d: MethodEditDraft, style: BrewMethodStyle, core: (String) -> String = ::brewMethodStyleSeedsJson): MethodEditDraft =
        prefill(d.copy(style = style, icon = if (d.iconPicked) d.icon else styleIcon(style)), core)

    /** The user picked an icon: it stays through style changes. */
    fun withIcon(d: MethodEditDraft, icon: String): MethodEditDraft = d.copy(icon = icon, iconPicked = true)

    /** The user typed a seed: it's theirs now (blank → back to the style's default on save). */
    fun withField(d: MethodEditDraft, field: String, value: String): MethodEditDraft {
        val next = when (field) {
            MethodEditDraft.DOSE -> d.copy(dose = value)
            MethodEditDraft.WATER -> d.copy(water = value)
            else -> d.copy(temp = value)
        }
        return next.copy(explicit = d.explicit + field)
    }

    /**
     * The method the draft saves as ([label] already validated). A new method
     * gets [newId] and [nowMs] timestamps; an edit keeps its id, created-at
     * and tombstone. Explicit fields store their number (blank → null);
     * the rest store null, so they keep following the style.
     */
    fun toMethod(d: MethodEditDraft, label: String, newId: String, nowMs: Long): CustomBrewMethod {
        fun seed(field: String, raw: String): Float? = if (field in d.explicit) parse(raw) else null
        val base = d.base
        return CustomBrewMethod(
            id = base?.id ?: newId,
            label = label,
            style = d.style,
            icon = d.icon,
            seedDoseG = seed(MethodEditDraft.DOSE, d.dose),
            seedWaterG = seed(MethodEditDraft.WATER, d.water),
            seedTempC = seed(MethodEditDraft.TEMP, d.temp),
            createdAt = base?.createdAt ?: nowMs,
            updatedAt = nowMs,
            deletedAt = base?.deletedAt,
        )
    }

    /** Create-or-replace [m] in [list] by id. */
    fun upsert(list: List<CustomBrewMethod>, m: CustomBrewMethod): List<CustomBrewMethod> =
        if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m

    /** Tombstone [id] (past brews keep resolving its label; recipes that use it stay runnable). */
    fun tombstone(list: List<CustomBrewMethod>, id: String, nowMs: Long): List<CustomBrewMethod> =
        list.map { if (it.id == id && it.deletedAt == null) it.copy(deletedAt = nowMs, updatedAt = nowMs) else it }

    /**
     * Merge a backup's methods: WIPE replaces the list; MERGE adopts unknown
     * ids and, for a shared id, keeps the more recently updated copy (a
     * rename or delete made on the other device wins only if it is newer).
     * Returns the list and how many methods were new.
     */
    fun mergeRestored(local: List<CustomBrewMethod>, restored: List<CustomBrewMethod>, wipe: Boolean): Pair<List<CustomBrewMethod>, Int> {
        if (wipe) return restored to restored.size
        val byId = local.associateBy { it.id }.toMutableMap()
        var added = 0
        restored.forEach { r ->
            val have = byId[r.id]
            if (have == null) {
                byId[r.id] = r
                added++
            } else if (r.updatedAt > have.updatedAt) {
                byId[r.id] = r
            }
        }
        return byId.values.sortedBy { it.createdAt } to added
    }
}

/**
 * The "Save 'X' as a method?" offer after a brew logged with a free-text
 * "Other…" name — one time, for that brew ([shotId]).
 */
data class SaveMethodPrompt(val shotId: String, val label: String, val freeText: String)
