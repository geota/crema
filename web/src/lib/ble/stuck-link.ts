/**
 * `#lib/ble/stuck-link.js` — the two ways a DE1 link lies, and what the status
 * line says about each. Shared with Android (`ble/StuckLink.kt`) so the same
 * situation reads the same on every device.
 *
 * **Hollow link**: GATT connected, services discovered, subscriptions in — and
 * then the connect-time StateInfo read times out twice. The DE1 answers that
 * read even while asleep, so silence means the link is dead on arrival (a
 * connect at the edge of range). The manager drops it and lets the reconnect
 * loop try again, instead of reporting `ready` over a machine that isn't there.
 *
 * **Stuck DE1**: after a drop, reconnect attempts keep failing. The DE1 takes
 * one central and advertises only while it has none, so a machine we were just
 * connected to that has gone quiet most likely still believes the old link is
 * up — a drop at the edge of range never delivered a clean disconnect, and only
 * its supervision timeout (or a power cycle) frees it. If this device's own
 * Bluetooth stack is what still holds the link, toggling Bluetooth releases it.
 * (Web Bluetooth cannot ask the stack, so the hint carries both remedies in
 * order; Android asks first via `BleTransport.isLinkHeldByStack`.)
 */

export const HOLLOW_LINK_STATUS = "DE1 link came up but isn't answering — reconnecting…";

/** Failed reconnect attempts in a row (after a connection) before the hint shows. */
export const STUCK_LINK_MISSES = 2;

export const STUCK_LINK_HINT =
	"DE1 not seen advertising — it may still think it's connected. " +
	"Toggle this device's Bluetooth, then power-cycle the DE1 if it still doesn't appear.";

/**
 * The stuck-DE1 hint once a machine we were connected to has stayed out of
 * reach for {@link STUCK_LINK_MISSES} attempts in a row; `null` while a shorter
 * silence is still ordinary (walking back into range) or on a first connect
 * (nothing was held).
 */
export function stuckLinkHint(wasConnected: boolean, consecutiveMisses: number): string | null {
	return wasConnected && consecutiveMisses >= STUCK_LINK_MISSES ? STUCK_LINK_HINT : null;
}
