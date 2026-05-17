package coffee.crema.ble

import java.util.UUID

/**
 * DE1 GATT service and characteristic UUIDs.
 *
 * Source: `docs/02-ble-protocol.md` §1. All DE1 characteristics share the
 * Bluetooth-SIG base UUID `0000xxxx-0000-1000-8000-00805F9B34FB` with a 16-bit
 * value in the `xxxx` slot (`A000`..`A012`).
 *
 * The app currently uses the two notify characteristics — `StateInfo` and
 * `ShotSample` — plus `RequestedState` for the (optional) machine-control
 * write path. The rest are listed for completeness / future use.
 */
object De1Uuids {

    /** Expand a 16-bit short UUID into the full 128-bit Bluetooth-SIG UUID. */
    private fun short(value: String): UUID =
        UUID.fromString("0000$value-0000-1000-8000-00805F9B34FB")

    /** The DE1 GATT service (`suuid`, `A000`). Scans filter on this. */
    val SERVICE: UUID = short("A000")

    /** `cuuid_02` / `A002` — RequestedState. Write 1 byte = desired MachineState. */
    val REQUESTED_STATE: UUID = short("A002")

    /** `cuuid_0B` / `A00B` — ShotSettings (steam / hot-water / group temp). */
    val SHOT_SETTINGS: UUID = short("A00B")

    /** `cuuid_0D` / `A00D` — ShotSample: the ~4-10 Hz telemetry notify stream. */
    val SHOT_SAMPLE: UUID = short("A00D")

    /** `cuuid_0E` / `A00E` — StateInfo: 2-byte machine state + substate notify. */
    val STATE_INFO: UUID = short("A00E")

    /** `cuuid_11` / `A011` — WaterLevels: 4-byte tank level notify. */
    val WATER_LEVELS: UUID = short("A011")

    /**
     * Standard Client Characteristic Configuration Descriptor. Writing
     * `ENABLE_NOTIFICATION_VALUE` to it tells the DE1 to start notifying.
     */
    val CCCD: UUID = short("2902")

    /**
     * The DE1 advertises with a name beginning "DE1". The official app
     * discovers it by the [SERVICE] UUID; the name prefix is a secondary
     * sanity filter.
     */
    const val ADVERTISED_NAME_PREFIX: String = "DE1"
}
