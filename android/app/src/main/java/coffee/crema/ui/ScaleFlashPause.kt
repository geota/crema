package coffee.crema.ui

/**
 * Scale Bluetooth stands down while a DE1 firmware flash runs (port of
 * Decenza 270b89df, `BLEManager::setFirmwareUpdateActive`,
 * `blemanager.cpp:185-213`). The flash is 15–25 minutes of acknowledged
 * writes, one at a time, and the connection interval is its speed limit; a
 * scale streaming weight at ~10 Hz, its scans and its reconnect attempts share
 * that radio. So while the flash runs:
 *  - the scale scan is cancelled and the scale link dropped without
 *    reconnecting (the remembered scale is kept);
 *  - every scale connect (a tap, the launch auto-connect, a foreground or
 *    presence kick) is held, not made.
 * When the flash ends — succeeded, failed or cancelled — the scale comes back
 * if it was linked, connecting, remembered, or asked for during the flash.
 *
 * "Runs" is the core's flash state as the driver reports it
 * ([coffee.crema.firmware.FirmwareFlashDriver]'s keep-alive: preparing,
 * erasing, uploading, verifying), so a flash restored after an app restart
 * (an erase or verify window) pauses the scale too, and lets go when it ends.
 * Nothing is persisted: a restart outside a running flash starts unpaused.
 * Main thread only, like the rest of [ConnectionController].
 */
internal class ScaleFlashPause(
    private val port: Port,
    /** Which device the log lines name (the grinder shares this pause). */
    private val label: String = "Scale",
    /** Say nothing about a device that isn't wanted (no grinder linked or
     *  remembered); the scale keeps its line either way. */
    private val quietWhenUnwanted: Boolean = false,
) {
    interface Port {
        /** A scale is linked, connecting, reconnecting or remembered, and not ended by the user. */
        fun scaleWanted(): Boolean

        /** Cancel the scale scan and drop the link, with no reconnect; keep the remembered scale. */
        fun standDown()

        /** Connect the scale again (the remembered one directly, else a scan). */
        fun bringBack()

        fun log(line: String)
    }

    /** A flash is running: scale connects are held. */
    var paused: Boolean = false
        private set

    /** Bring the scale back when the flash ends. */
    private var bringBack = false

    /** The flash started ([active]) or ended; repeats are ignored. */
    fun onFlashActive(active: Boolean) {
        if (active == paused) return
        paused = active
        if (active) {
            bringBack = port.scaleWanted()
            port.standDown()
            if (bringBack || !quietWhenUnwanted) port.log("$label: Bluetooth paused for the firmware update")
        } else {
            val restore = bringBack
            bringBack = false
            if (restore) {
                port.log("$label: firmware update over — reconnecting")
                port.bringBack()
            }
        }
    }

    /**
     * A scale connect was asked for. While paused it is held for the end of
     * the flash and this answers true (the caller does nothing now).
     */
    fun holdConnect(): Boolean {
        if (!paused) return false
        if (!bringBack) port.log("$label: connect held until the firmware update ends")
        bringBack = true
        return true
    }
}
