package coffee.crema.ble

/**
 * How a reconnect session behaves once its fast burst is exhausted — the
 * "lurk" tier, which is where a device that wandered off (or a machine that
 * was switched off) spends most of its absence. Chosen per round by
 * [chooseLurkPolicy]; pinned by `LurkPolicyTest`.
 */
enum class LurkPolicy {
    /** The original tier: every [LURK_INTERVAL_MS], one scan-then-connect attempt. */
    SCAN_INTERVAL,

    /**
     * One long-lived pending connect (Android `autoConnect`): the OS connects
     * the moment the device advertises, with no app scanning at all. A kick
     * (foreground, "Retry now", presence) withdraws it for the fast path; a
     * failure (GATT 133) falls back to scan-then-connect with backoff.
     */
    PENDING_CONNECT,

    /**
     * The companion-device presence callback says the device is NOT nearby:
     * do nothing until it reports it back (a presence kick), re-checking only
     * every [IDLE_SAFETY_MS] with one scan-then-connect in case the presence
     * signal is misbehaving.
     */
    IDLE_UNTIL_KICK,
}

/**
 * @param cdmUsable the companion-device service is available on this device.
 * @param associated the device has a companion association.
 * @param present the last presence report: true / false, or null when none
 *   has arrived yet (unknown is treated as "maybe here").
 * @param pendingSupported the transport can do a pending connect (a real radio,
 *   not the LAN proxy / replay).
 */
fun chooseLurkPolicy(
    cdmUsable: Boolean,
    associated: Boolean,
    present: Boolean?,
    pendingSupported: Boolean,
): LurkPolicy = when {
    cdmUsable && associated && present == false -> LurkPolicy.IDLE_UNTIL_KICK
    pendingSupported -> LurkPolicy.PENDING_CONNECT
    else -> LurkPolicy.SCAN_INTERVAL
}

/** The idle tier's safety re-check (see [LurkPolicy.IDLE_UNTIL_KICK]). */
const val IDLE_SAFETY_MS = 30 * 60_000L
