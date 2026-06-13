package coffee.crema.profiles

import coffee.crema.core.profileBoundsJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Hard wire-protocol bounds for DE1 profile fields, parsed once from the core's
 * `profileBoundsJson()` snapshot so the profile editors' steppers reject the same
 * firmware-invalid values the core (and the web shell) do — no per-shell drift.
 * These are constants, so the singleton parses lazily on first access and is
 * never refetched, exactly as the web shell does in `$lib/settings/profile_bounds.ts`.
 *
 * This is the accept/reject truth; editorial "warn above X" ranges (e.g. a 20 °C
 * brew-temp floor, a 95 °C tank-temp ceiling) stay in the editor as plain literals.
 * Keys are snake_case here (the core fn hand-rolls them); the camelCase fields carry
 * `@SerialName`. See core/de1-domain/src/profile_bounds.rs for the canonical caps.
 */
@Serializable
data class ProfileBounds(
    @SerialName("max_profile_steps") val maxProfileSteps: Int,
    @SerialName("max_total_volume_ml") val maxTotalVolumeMl: Int,
    @SerialName("min_total_volume_ml") val minTotalVolumeMl: Int,
    @SerialName("min_pressure_bar") val minPressureBar: Float,
    @SerialName("max_pressure_bar") val maxPressureBar: Float,
    @SerialName("min_flow_ml_per_s") val minFlowMlPerS: Float,
    @SerialName("max_flow_ml_per_s") val maxFlowMlPerS: Float,
    @SerialName("min_temperature_c") val minTemperatureC: Float,
    @SerialName("max_temperature_c") val maxTemperatureC: Float,
    @SerialName("max_steam_temperature_c") val maxSteamTemperatureC: Float,
    @SerialName("min_frame_seconds") val minFrameSeconds: Float,
    @SerialName("max_frame_seconds") val maxFrameSeconds: Float,
    @SerialName("max_preinfuse_steps") val maxPreinfuseSteps: Int,
) {
    companion object {
        /** The firmware caps, parsed once from the core JSON snapshot. */
        val INSTANCE: ProfileBounds by lazy {
            Json { ignoreUnknownKeys = true }.decodeFromString(profileBoundsJson())
        }
    }
}
