package coffee.crema.ble

import coffee.crema.core.WriteTarget
import coffee.crema.core.de1Uuids
import coffee.crema.core.de1WriteTargetUuid
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * DE1 GATT service + characteristic UUIDs.
 *
 * Sourced from the Rust core ([de1Uuids]), **not** hardcoded — so the web and
 * Android shells share ONE map for the DE1's single fixed GATT layout (no
 * cross-shell drift). The core stays **sans-IO**: it only *describes* the
 * layout as data; this object is the thin shell accessor that parses the core's
 * JSON once and hands `java.util.UUID`s to the BLE layer. Writes are addressed
 * via [forWriteTarget], which delegates to the core's single `WriteTarget` →
 * UUID map.
 *
 * (Scales are different — multi-vendor, so the core reports the *connected*
 * scale's UUIDs after identifying it. The DE1 is one fixed device, so its map
 * is static; it lives in the core purely to avoid duplicating it per shell.)
 */
object De1Uuids {
    private val map: coffee.crema.core.De1Uuids =
        Json.decodeFromString(coffee.crema.core.De1Uuids.serializer(), de1Uuids())

    private fun uuid(value: String): UUID = UUID.fromString(value)

    /** The DE1 GATT service (`A000`). Scans filter on this. */
    val SERVICE: UUID = uuid(map.service)

    /** `A00E` StateInfo — 2-byte machine state + substate notify. */
    val STATE_INFO: UUID = uuid(map.stateInfo)

    /** `A00D` ShotSample — the ~4–10 Hz telemetry notify stream. */
    val SHOT_SAMPLE: UUID = uuid(map.shotSample)

    /** `A011` WaterLevels — tank-level notify. */
    val WATER_LEVELS: UUID = uuid(map.waterLevels)

    /** `A00B` ShotSettings — steam / hot-water settings notify. */
    val SHOT_SETTINGS: UUID = uuid(map.shotSettings)

    /** `A005` ReadFromMMR — MMR read-reply notify. */
    val MMR_READ: UUID = uuid(map.mmrRead)

    /**
     * The DE1 advertises with a name beginning "DE1"; some units advertise
     * "BENGLE". The service-UUID scan already does the real narrowing.
     */
    const val ADVERTISED_NAME_PREFIX: String = "DE1"

    /**
     * The DE1 characteristic to write a core [WriteTarget]'s bytes to —
     * delegates to the core's single `WriteTarget` → UUID map. The shell holds
     * no DE1 UUID literals and no per-target switch of its own.
     */
    fun forWriteTarget(target: WriteTarget): UUID =
        uuid(
            de1WriteTargetUuid(target.string)
                ?: error("No DE1 UUID for write target ${target.string}"),
        )
}
