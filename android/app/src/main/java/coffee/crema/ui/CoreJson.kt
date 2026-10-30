package coffee.crema.ui

import coffee.crema.core.CalibrationRefusal
import coffee.crema.core.MachineRequestRefusal
import coffee.crema.core.StartEnd
import coffee.crema.core.StartRefusal
import coffee.crema.core.ProfileUploadFailure
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule

// Declared before [coreJson]: top-level properties initialise in source order.
/** Decoders for the core enums tagged `kind` (see [coreJson]). */
internal val kindTaggedCoreEnums: SerializersModule = SerializersModule {
    polymorphicDefaultDeserializer(ProfileUploadFailure::class) { KindTagged(ProfileUploadFailure.serializer()) }
    polymorphicDefaultDeserializer(CalibrationRefusal::class) { KindTagged(CalibrationRefusal.serializer()) }
    polymorphicDefaultDeserializer(MachineRequestRefusal::class) { KindTagged(MachineRequestRefusal.serializer()) }
    polymorphicDefaultDeserializer(StartRefusal::class) { KindTagged(StartRefusal.serializer()) }
    polymorphicDefaultDeserializer(StartEnd::class) { KindTagged(StartEnd.serializer()) }
}

/**
 * The JSON configuration the VM decodes every `CoreOutput` (and its other
 * payloads) with.
 *
 * coerceInputValues: an unknown enum string (e.g. an out-of-contract profile
 * `mode`/`metric` or a version-skewed peer frame) coerces to the property
 * default (null) rather than throwing — preserves the lenient profile decode
 * after the mode/ramp/metric/compare fields became typed enums.
 *
 * [kindTaggedCoreEnums]: some core enums are adjacently tagged with `kind` /
 * `details` (serde `tag = "kind"`), but the typeshare Kotlin for them is a
 * plain sealed class, which kotlinx decodes by a `type` discriminator. Every
 * output carrying `ProfileUploadFailed` or `CalibrationRefused` failed to
 * decode ("Class discriminator was missing") and was dropped whole, with the
 * writes it carried — a superseding upload's frames, the re-upload request,
 * the cold-maintenance outcome (found by the round-3 write-path work).
 */
internal val coreJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    serializersModule = kindTaggedCoreEnums
}

/**
 * Reached when a [sealed] value arrives without the `type` discriminator:
 * re-tags its `kind` as `type` and decodes it with the generated serializer
 * (the variant payload stays under `details`, the generated field name).
 */
private class KindTagged<T : Any>(private val sealed: KSerializer<T>) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("KindTagged(${sealed.descriptor.serialName})")

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("${sealed.descriptor.serialName} decodes from JSON only")
        val tagged = input.decodeJsonElement().jsonObject
        val kind = tagged["kind"]
            ?: throw SerializationException("${sealed.descriptor.serialName} has neither `type` nor `kind`")
        return input.json.decodeFromJsonElement(sealed, JsonObject(tagged - "kind" + ("type" to kind)))
    }
}

/** A readable name for an upload failure in the event log (`Aborted`,
 *  `AckTimeout(awaiting=2)`, `Internal: …`) — the generated objects have no
 *  `toString`. */
internal fun uploadFailureLabel(reason: ProfileUploadFailure): String = when (reason) {
    is ProfileUploadFailure.Internal -> "Internal: ${reason.details.message}"
    else -> reason::class.simpleName ?: reason.toString()
}
