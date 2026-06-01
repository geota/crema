package coffee.crema.ble

import java.util.UUID

/**
 * Bookoo coffee-scale GATT service and characteristic UUIDs.
 *
 * The Bookoo scale exposes a single proprietary service carrying a weight
 * notify characteristic (`ff11`) and a command write characteristic (`ff12`).
 * All UUIDs share the Bluetooth-SIG base UUID
 * `0000xxxx-0000-1000-8000-00805F9B34FB` with a 16-bit value in the `xxxx`
 * slot.
 *
 * The app subscribes to [WEIGHT_NOTIFY] for the live weight stream and writes
 * tare / timer commands to [COMMAND]. No init command is required — weight
 * notifications start as soon as the CCCD is enabled.
 *
 * These are held here as shell-side constants because **the Android subscribe
 * path supports only the Bookoo today**: it subscribes to these exact
 * characteristics rather than the connected scale's. The values mirror the
 * core's `de1_scale::bookoo` constants (manufacturer-fixed, so ~zero drift
 * risk). The multi-scale generalisation is **AND6**: scan generically via the
 * core's `scale_scan_uuids()` (`Scale::scan_uuids`), then subscribe via the
 * connected scale's `scale_uuids()` instead of these — at which point this
 * Bookoo-specific map goes away. (A Bookoo-specific *core* accessor was
 * deliberately avoided — the core's scan surface is generic across all scales.)
 */
object ScaleUuids {

    /** Expand a 16-bit short UUID into the full 128-bit Bluetooth-SIG UUID. */
    private fun short(value: String): UUID =
        UUID.fromString("0000$value-0000-1000-8000-00805F9B34FB")

    /** The Bookoo GATT service (`0ffe`). */
    val SERVICE: UUID = short("0ffe")

    /** `ff11` — weight notify: the live weight notification stream. */
    val WEIGHT_NOTIFY: UUID = short("ff11")

    /** `ff12` — command write: tare / timer commands are written here. */
    val COMMAND: UUID = short("ff12")

    /**
     * Standard Client Characteristic Configuration Descriptor. Writing
     * `ENABLE_NOTIFICATION_VALUE` to it tells the scale to start notifying.
     * Identical to [De1Uuids.CCCD]; restated here for a self-contained map.
     */
    val CCCD: UUID = short("2902")

    /**
     * The Bookoo scale advertises a name beginning "BOOKOO_SC". The app
     * discovers it by an unfiltered scan and matches this prefix; the Rust
     * core then identifies the exact model from the full advertised name.
     */
    const val BOOKOO_NAME_PREFIX: String = "BOOKOO_SC"
}
