package coffee.crema.ui

import coffee.crema.core.EventShotSettingsReadInner
import coffee.crema.core.SteamHotWaterSettings

/**
 * Build the steam + hot-water settings packet (cuuid_0B) for a read-modify-write
 * (issue 14). The four QC-modeled fields come from the persisted Quick-Controls
 * values; the rest are preserved from the machine's last-reported settings
 * ([machine], via `Event.ShotSettingsRead`) so a QC steam/water change doesn't
 * reset the machine's timeouts / espresso volume / group temp. Pre-read, the
 * legacy-app defaults stand in. `steamFlags` is always 0 (legacy parity; bits
 * undocumented). Top-level + `internal` so it's pure and unit-testable without a
 * device (the actual BLE write isn't).
 */
internal fun buildSteamHotWaterSettings(
    steamTempC: Float,
    steamTimeoutS: Float,
    hotWaterTempC: Float,
    hotWaterVolumeMl: Float,
    machine: EventShotSettingsReadInner?,
): SteamHotWaterSettings = SteamHotWaterSettings(
    steamFlags = 0u,
    steamTempC = steamTempC,
    steamTimeoutS = steamTimeoutS,
    hotWaterTempC = hotWaterTempC,
    hotWaterVolumeMl = hotWaterVolumeMl,
    hotWaterTimeoutS = machine?.hot_water_timeout ?: 60f,
    espressoVolumeMl = machine?.espresso_volume ?: 200f,
    groupTempC = machine?.group_temp ?: 92f,
)
