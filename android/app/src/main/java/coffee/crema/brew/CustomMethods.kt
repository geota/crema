package coffee.crema.brew

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coffee.crema.core.BrewMethodStyle
import coffee.crema.core.CustomBrewMethod
import coffee.crema.core.CustomMethodLabelCheck
import coffee.crema.core.CustomMethodLabelError
import coffee.crema.core.CustomMethodLabelInput
import coffee.crema.core.validateCustomMethodLabelJson
import kotlinx.serialization.builtins.ListSerializer

/*
 * User-defined brewing methods (issue #10 feedback: "can I add my ORB?").
 * The core owns the shape and the rules (`de1_domain::brew_custom`); the
 * shell owns persistence (the recipe store's envelope), the icon set and the
 * copy. The live list sits in [CustomMethods] — a snapshot-state registry the
 * VM keeps current — so every `methodLabel` / `methodIcon` call site, from a
 * History row to a recipe card, resolves a `custom:<uuid>` id without
 * threading the list through each composable.
 */

/** The id namespace of user methods — the core's `CUSTOM_METHOD_ID_PREFIX`. */
const val CUSTOM_METHOD_PREFIX = "custom:"

/** Whether [method] is a user-defined method id (any case / padding). */
fun isCustomMethodId(method: String?): Boolean =
    method?.trim()?.startsWith(CUSTOM_METHOD_PREFIX, ignoreCase = true) == true

/**
 * The small shared icon set a custom method picks from — Phosphor names, the
 * same keys the web stores, so a backup renders the same mark on both shells.
 */
val CUSTOM_METHOD_ICONS: List<String> = listOf("drop", "funnel", "coffee", "flask", "cylinder", "snowflake", "fire", "leaf")

/** The four styles in display order, with their copy. */
val BREW_METHOD_STYLES: List<BrewMethodStyle> =
    listOf(BrewMethodStyle.Percolation, BrewMethodStyle.Immersion, BrewMethodStyle.Pressure, BrewMethodStyle.Cold)

fun styleLabel(style: BrewMethodStyle?): String = when (style ?: BrewMethodStyle.Percolation) {
    BrewMethodStyle.Percolation -> "Percolation"
    BrewMethodStyle.Immersion -> "Immersion"
    BrewMethodStyle.Pressure -> "Pressure"
    BrewMethodStyle.Cold -> "Cold"
}

fun styleHint(style: BrewMethodStyle?): String = when (style ?: BrewMethodStyle.Percolation) {
    BrewMethodStyle.Percolation -> "Water poured through a bed — like a V60"
    BrewMethodStyle.Immersion -> "Steeps, then separates — like a French press"
    BrewMethodStyle.Pressure -> "Steep, then press — like an AeroPress"
    BrewMethodStyle.Cold -> "A long cold steep — like cold brew"
}

/** Each style's default mark (the user may pick another from [CUSTOM_METHOD_ICONS]). */
fun styleIcon(style: BrewMethodStyle?): String = when (style ?: BrewMethodStyle.Percolation) {
    BrewMethodStyle.Percolation -> "funnel"
    BrewMethodStyle.Immersion -> "coffee"
    BrewMethodStyle.Pressure -> "cylinder"
    BrewMethodStyle.Cold -> "snowflake"
}

/** A custom method's mark: its pick, else its style's default. */
fun customMethodIcon(m: CustomBrewMethod): String = m.icon?.takeIf { it in CUSTOM_METHOD_ICONS } ?: styleIcon(m.style)

/**
 * The live registry of the user's methods, tombstones included (old rows
 * still resolve their label and seeds). Snapshot state: composables that read
 * a label recompose on a rename. [snapshotLabels] maps a custom id to the
 * label a History row snapshotted — the fallback when the method itself is
 * gone (a restore elsewhere, a lost list).
 */
object CustomMethods {
    var all: List<CustomBrewMethod> by mutableStateOf(emptyList())
    var snapshotLabels: Map<String, String> by mutableStateOf(emptyMap())

    /** Picker entries — live methods only, oldest first. */
    val live: List<CustomBrewMethod> get() = all.filter { it.deletedAt == null }.sortedBy { it.createdAt }

    fun find(id: String?): CustomBrewMethod? {
        val key = id?.trim()?.lowercase() ?: return null
        return all.firstOrNull { it.id.lowercase() == key }
    }
}

/** A custom method's label: registry (live or tombstoned) → row snapshot → "Custom method". */
internal fun customMethodLabel(id: String): String =
    CustomMethods.find(id)?.label
        ?: CustomMethods.snapshotLabels[id.trim().lowercase()]
        ?: "Custom method"

/**
 * The presets and the live custom methods, as picker chips — the core's
 * `brew_method_presets_with_custom` (its seeds for each custom entry), with
 * the UI face attached. Cached on the registry list; native, UI code only.
 */
fun brewMethodPickerPresets(): List<BrewMethodPreset> {
    val live = CustomMethods.live
    pickerCache?.let { (key, value) -> if (key == live) return value }
    val customs = if (live.isEmpty()) {
        emptyList()
    } else {
        val seeds = runCatching {
            CoreJson.decodeFromString(
                ListSerializer(coffee.crema.core.BrewMethodPreset.serializer()),
                coffee.crema.core.brewMethodPresetsWithCustomJson(
                    CoreJson.encodeToString(ListSerializer(CustomBrewMethod.serializer()), live),
                ),
            ).associateBy { it.id }
        }.getOrDefault(emptyMap())
        live.mapNotNull { m ->
            val s = seeds[m.id] ?: return@mapNotNull null
            BrewMethodPreset(m.id, m.label, customMethodIcon(m), s)
        }
    }
    val out = BREW_METHOD_PRESETS + customs
    pickerCache = live to out
    return out
}

private var pickerCache: Pair<List<CustomBrewMethod>, List<BrewMethodPreset>>? = null

/** The display labels of the curated presets — the validator checks them too. */
val PRESET_LABELS: List<String> get() = METHOD_FACE_LABELS

/**
 * Validate a name through the core's `validate_custom_method_label`: trimmed,
 * 1–40 characters, unique against the presets and the live methods (but the
 * one being renamed). Returns the trimmed label or the user-facing error.
 * [core] is the FFI call, injectable for JVM tests.
 */
fun validateCustomMethodLabel(
    raw: String,
    editingId: String?,
    methods: List<CustomBrewMethod> = CustomMethods.all,
    core: (String) -> String = ::validateCustomMethodLabelJson,
): Result<String> {
    val input = CustomMethodLabelInput(label = raw, editingId = editingId, customMethods = methods, presetLabels = PRESET_LABELS)
    val check = runCatching {
        CoreJson.decodeFromString(
            CustomMethodLabelCheck.serializer(),
            core(CoreJson.encodeToString(CustomMethodLabelInput.serializer(), input)),
        )
    }.getOrElse { return Result.failure(IllegalStateException("Couldn’t check that name")) }
    return when (check.error) {
        null -> Result.success(check.label)
        CustomMethodLabelError.Empty -> Result.failure(IllegalArgumentException("Name the method."))
        CustomMethodLabelError.TooLong -> Result.failure(IllegalArgumentException("Keep it to 40 characters."))
        CustomMethodLabelError.Duplicate -> Result.failure(IllegalArgumentException("You already have a method called “${check.label}”."))
    }
}
