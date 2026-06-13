package coffee.crema.profiles

import coffee.crema.core.defaultBrewDefaultsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The out-of-box brew defaults — dose / ratio / brew temp / pre-infusion —
 * parsed once from the core's `defaultBrewDefaultsJson()` so the shell seeds new
 * profiles and Quick-Controls fallbacks from the one source instead of
 * hardcoding 18 / 2.0 / 93 / 8. The web shell reads the same core fn (its
 * settings store's DEFAULT_SETTINGS), so a tweak in core reaches both shells.
 *
 * Keys are camelCase (the core `BrewDefaults` struct's serde rename), mapping
 * 1:1 to these fields. Parsed lazily on first access — the uniffi layer loads
 * the native lib on that first call. See core/de1-domain/src/crema_profile.rs.
 */
@Serializable
data class BrewDefaults(
    val doseG: Float,
    val ratio: Float,
    val brewTempC: Float,
    val preinfusionS: Float,
) {
    companion object {
        /** The core's seed defaults, parsed once. */
        val INSTANCE: BrewDefaults by lazy {
            Json { ignoreUnknownKeys = true }.decodeFromString(defaultBrewDefaultsJson())
        }
    }
}
