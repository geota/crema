package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BrewGrindInput
import coffee.crema.core.BrewGrindView
import coffee.crema.core.GrindChip
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.ScaleRefusal
import coffee.crema.core.brewGrindViewJson
import coffee.crema.core.countsInThirdsJson
import coffee.crema.core.formatGrindJson
import coffee.crema.core.grindChipsJson
import coffee.crema.core.grindScaleRefusalJson
import coffee.crema.core.grindTextJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * One grind value (#488 round 2, REVIEW-CORE H1/H2/L1, REVIEW-FINAL M1/M2):
 * a grind is text, as the core, the web and the backups keep it. The core's
 * `grind_value` module owns every rule: a dial number as text on its scale,
 * the comparison, the display with the unit, the scale refusal, what Brew's
 * grind is (header, shot, marker, Save part, start from blank), the chips,
 * the parse of a typed or stored grind ([grindNumber]) and thirds (the
 * Fellow Ode: 4, 4⅓, 4⅔). These are the typed calls onto it.
 */

private val grindJson = Json { ignoreUnknownKeys = true }

private fun scaleJson(scale: GrindScale): String = grindJson.encodeToString(GrindScale.serializer(), scale)

/** [dial] as Brew's grind text on [scale] (core `grind_text`): "15", "4.2", "2.25", "4⅓" on a thirds scale, no float noise, never one-decimal rounding. */
fun grindText(dial: Double, scale: GrindScale = DEFAULT_GRIND_SCALE): String = grindTextJson(dial, scaleJson(scale))

/** Whether [a] and [b] are the same grind (core `same_grind`): as numbers when both are ("15" = "15.0"), else as trimmed text. */
fun sameGrind(a: String, b: String): Boolean = coffee.crema.core.sameGrind(a, b)

/** [grind] as Brew shows it on [scale] (core `format_grind`): "480 μm", "2.1.3" (no unit after text), "—" when blank. */
fun formatGrind(grind: String, scale: GrindScale?): String = formatGrindJson(grind, scaleJson(scale ?: DEFAULT_GRIND_SCALE))

/**
 * Why a typed scale can't be stored (core `grind_scale_refusal`), as Devices
 * says it, or null. A field that didn't parse is null here.
 */
fun scaleRefusal(min: Float?, max: Float?, step: Float?, unit: String = ""): String? {
    fun num(v: Float?) = v?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: JsonNull
    val payload = buildJsonObject {
        put("min", num(min))
        put("max", num(max))
        put("step", num(step))
        put("unit", JsonPrimitive(unit))
    }
    val out = grindScaleRefusalJson(payload.toString()).trim()
    if (out == "null") return null
    return when (grindJson.decodeFromString(ScaleRefusal.serializer(), out)) {
        ScaleRefusal.NotANumber -> "Enter a number in each field"
        ScaleRefusal.LowestNotBelowHighest -> "Lowest must be below Highest"
        ScaleRefusal.StepNotPositive -> "Step must be more than 0"
        ScaleRefusal.StepTooLarge -> "Step must fit between Lowest and Highest"
    }
}

/**
 * What Brew's grind is (core `brew_grind_view`, DESIGN "No grind set is its
 * own state"): the header's and the shot's value (null = not set), the
 * display, the stepper's number, where the first step from blank goes, the
 * bag's saved grind, the marker and Save's grind part.
 */
fun brewGrindView(
    bag: Bean?,
    grinder: Grinder?,
    brewGrind: String?,
    core: (String) -> String = ::brewGrindViewJson,
): BrewGrindView = grindJson.decodeFromString(
    BrewGrindView.serializer(),
    core(grindJson.encodeToString(BrewGrindInput.serializer(), BrewGrindInput(bag = bag, grinder = grinder, brewGrind = brewGrind))),
)

/** Where a stepper on [scale] goes from blank: the middle of the scale on its step (core `grind_start_from_blank`). */
fun grindStartFromBlank(scale: GrindScale): Double =
    brewGrindView(null, Grinder(id = "", name = "", scale = scale, updatedAt = 0L), null).startFromBlank

/** Quick Controls' grind chips around [around] on [scale] (core `grind_chips`): none when it's unset or not a plain number. */
fun grindChips(scale: GrindScale, around: String?, count: Int = 5): List<GrindChip> =
    grindJson.decodeFromString(ListSerializer(GrindChip.serializer()), grindChipsJson(scaleJson(scale), around, count.toUInt()))

/**
 * A stored or typed grind as the stepper's number (core `grind_number`), or
 * null when it isn't one ("2.5.3" stays text): plain decimals ("2,5" too)
 * and thirds ("4⅓", "4 1/3"); no exponent, nothing past 1e6.
 */
fun grindNumber(text: String?): Double? = text?.let { coffee.crema.core.grindNumber(it) }

/** Whether [scale] counts in thirds (core `counts_in_thirds`, the Fellow Ode). */
fun countsInThirds(scale: GrindScale): Boolean = countsInThirdsJson(scaleJson(scale))

/**
 * Whether a grind field on [scale] opens the full keyboard: only on a thirds
 * scale, where "4 1/3" needs a space and a slash; every other scale keeps the
 * decimal pad.
 */
fun grindFullKeyboard(scale: GrindScale): Boolean = countsInThirds(scale)

/** [scale]'s step as Devices shows it: "⅓" on a thirds scale, "0.05", "1" (core `grind_text` of the step). */
fun grindStepText(scale: GrindScale): String = grindText(scale.step.toDouble(), scale)

/**
 * A grind read from JSON: a string as stored (blank = none), a number from an
 * older file (Android stored a shot's grind and Brew's dial as a Float) as
 * its plain digits, 15.0 as "15". Never rounded.
 */
fun grindTextOf(p: JsonPrimitive?): String? = when {
    p == null || p is JsonNull -> null
    p.isString -> p.content.takeIf { it.isNotBlank() }
    // Only a number a Float could have held is spelled out: a crafted
    // "1e999999999" would expand into a gigantic string, so anything outside
    // a grind's range stays as its own (input-sized) literal.
    else -> p.content.toBigDecimalOrNull()?.stripTrailingZeros()
        ?.takeIf { it.scale() in -MAX_GRIND_EXPONENT..MAX_GRIND_EXPONENT && it.precision() <= MAX_GRIND_DIGITS }
        ?.toPlainString()
        ?: p.content.takeIf { it.isNotBlank() }
}

/** The widest number [grindTextOf] spells out: digits after or zeros before the point, and digits in all. */
private const val MAX_GRIND_EXPONENT = 20
private const val MAX_GRIND_DIGITS = 30

/**
 * A grind as text in JSON (REVIEW-FINAL M1): written as a string, and read
 * from a string or from the number an older Android file holds.
 */
object GrindTextSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("coffee.crema.GrindText", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        val p = decoder.decodeJsonElement() as? JsonPrimitive
        return grindTextOf(p).orEmpty()
    }
}
