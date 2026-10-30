package coffee.crema.ui

import coffee.crema.core.CremaBridge
import coffee.crema.core.cremaProfileFromWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val UploadJson = Json { ignoreUnknownKeys = true }

/**
 * Upload a wire `Profile` fixture through the shell's only upload entry point,
 * `uploadCremaProfile` (no Quick-Controls overrides): the core adapts it to a
 * library `CremaProfile` first, as the app's library does. Returns the
 * upload's `CoreOutput` JSON, the shape the old raw upload returned.
 */
internal fun CremaBridge.uploadWire(wireJson: String, nowMs: ULong): String {
    val upload = uploadCremaProfile(cremaProfileFromWire(wireJson), null, nowMs)
    return UploadJson.parseToJsonElement(upload).jsonObject.getValue("output").toString()
}
