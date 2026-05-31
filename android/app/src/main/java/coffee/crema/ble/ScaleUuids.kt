package coffee.crema.ble

import coffee.crema.core.bookooGattUuids
import java.util.UUID

/**
 * Bookoo coffee-scale GATT service and characteristic UUIDs.
 *
 * The service (`0ffe`), weight-notify (`ff11`), and command (`ff12`) UUIDs are
 * sourced from the Rust core via `bookooGattUuids()` (AND2) — the core owns the
 * Bookoo protocol, so it owns the canonical UUIDs, and the shell no longer
 * keeps hardcoded duplicates that could drift from `de1_scale::bookoo`. The
 * universal `2902` CCCD and the `BOOKOO_SC` advertised-name prefix stay here: a
 * CCCD is not Bookoo-specific, and the name prefix is a scan-discovery detail
 * the core does not model.
 *
 * The app subscribes to [WEIGHT_NOTIFY] for the live weight stream and writes
 * tare / timer commands to [COMMAND]. No init command is required — weight
 * notifications start as soon as the CCCD is enabled.
 */
object ScaleUuids {

    /** Expand a 16-bit short UUID into the full 128-bit Bluetooth-SIG UUID. */
    private fun short(value: String): UUID =
        UUID.fromString("0000$value-0000-1000-8000-00805F9B34FB")

    /**
     * The canonical Bookoo GATT UUIDs from the Rust core (`de1_scale::bookoo`).
     * The core constants are full 128-bit strings, so they expand directly.
     */
    private val gatt = bookooGattUuids()

    /** The Bookoo GATT service (`0ffe`) — core-sourced. */
    val SERVICE: UUID = UUID.fromString(gatt.service)

    /** `ff11` — weight notify: the live weight notification stream (core-sourced). */
    val WEIGHT_NOTIFY: UUID = UUID.fromString(gatt.notify)

    /** `ff12` — command write: tare / timer commands are written here (core-sourced). */
    val COMMAND: UUID = UUID.fromString(gatt.command)

    /**
     * Standard Client Characteristic Configuration Descriptor. Writing
     * `ENABLE_NOTIFICATION_VALUE` to it tells the scale to start notifying.
     * Identical to [De1Uuids.CCCD]; restated here for a self-contained map.
     * Universal (not Bookoo-specific), so it stays shell-side.
     */
    val CCCD: UUID = short("2902")

    /**
     * The Bookoo scale advertises a name beginning "BOOKOO_SC". The app
     * discovers it by an unfiltered scan and matches this prefix; the Rust
     * core then identifies the exact model from the full advertised name. A
     * scan-discovery detail the core does not model, so it stays shell-side.
     */
    const val BOOKOO_NAME_PREFIX: String = "BOOKOO_SC"
}
