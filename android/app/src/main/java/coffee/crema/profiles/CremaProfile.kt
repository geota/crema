package coffee.crema.profiles

import kotlinx.serialization.Serializable

/*
 * The shell-facing editable profile model — a thin Kotlin interface that mirrors
 * the JSON shape the core's `CremaProfile` serializes to (de1_domain::crema_profile,
 * serde `rename_all = "camelCase"`). The adapter LOGIC (segment↔step, built-ins,
 * defaults) lives once in the Rust core and crosses the FFI as JSON strings
 * (`builtinCremaProfiles()`, `blankCremaProfile()`, `cremaProfileToWire()`…);
 * each shell keeps only this matching data shape, exactly as the web shell does
 * in `$lib/profiles/model.ts`. See core/de1-domain/src/crema_profile.rs.
 *
 * Only the fields the Brew dashboard reads are declared here, and every field is
 * defaulted so a partial payload degrades gracefully — `Json { ignoreUnknownKeys
 * = true }` (the VM's decoder) drops the many fields we don't model yet (notes,
 * limiter, tempSensor, per-segment volume limits, …). The editor (M3) will grow
 * this model to the full round-trippable shape.
 */
@Serializable
data class CremaProfile(
    /** Stable UUID v7 (built-ins carry pre-generated ids). */
    val id: String = "",
    /** `"builtin"` (read-only) or `"custom"`. */
    val source: String = "builtin",
    /** Display name (the wire `Profile::title`). */
    val name: String = "",
    /** Roast level the recipe is tuned for — `"light"|"medium"|"dark"` or null. */
    val roast: String? = null,
    /** Custom user tags (plus a synthesised `"Built-in"` on import). */
    val tags: List<String> = emptyList(),
    /** Pinned to the Quick Controls favorites strip. */
    val pinned: Boolean = false,
    /** Dose target, grams. */
    val dose: Float = 18f,
    /** Yield target, grams (round-trips as the wire `target_weight`). */
    val yieldOut: Float = 36f,
    /** Brew temperature, °C — a display default; per-segment temps are the real control. */
    val brewTemp: Float = 93f,
    /** How many leading segments count as preinfusion. */
    val preinfuseStepCount: Int = 0,
    /** Whole-shot dispensed-volume limit, ml, 0 = no limit. */
    val maxTotalVolumeMl: Int = 0,
    /** Profile author — free text. */
    val author: String = "",
    /** Beverage kind — wire `beverage_type` (e.g. `"espresso"`); display-only here. */
    val beverageType: String? = null,
    /** The ordered pressure / flow segments (the phase track). */
    val segments: List<ProfileSegment> = emptyList(),
) {
    /** Live yield-to-dose ratio target, the x in `1:x` (0 when dose is unset). */
    val ratio: Float get() = if (dose > 0f) yieldOut / dose else 0f

    /** Pre-infusion seconds — the first segment's duration, rounded (web parity). */
    val preinfuseSeconds: Int get() = segments.firstOrNull()?.time?.let { Math.round(it) } ?: 0
}

/*
 * One editable segment — the editor's segment shape. Only the fields the Brew
 * phase track reads are modelled (name, duration, early-exit metric); the wire
 * round-trip fields (mode/target/ramp/temp/limiter/…) are ignored for now.
 */
@Serializable
data class ProfileSegment(
    /** Stable id, unique within the profile (`s1`, `s2`, …). */
    val id: String = "",
    /** Human-readable segment name. */
    val name: String = "",
    /** Segment duration, seconds (float — the DE1 carries 0.1 s frames). */
    val time: Float = 0f,
    /** Structured early-exit condition, or null. */
    val exit: SegmentExit? = null,
)

/*
 * The early-exit condition's metric — all the phase track needs to draw the
 * exit notch + pick its channel icon/colour. The rest of the wire
 * `ExitCondition` (compare/value/…) is dropped by ignoreUnknownKeys.
 */
@Serializable
data class SegmentExit(
    /** `"pressure" | "flow" | "time" | "volume" | "weight"`, or null. */
    val metric: String? = null,
)
