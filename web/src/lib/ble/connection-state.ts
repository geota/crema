/**
 * `$lib/ble/connection-state` — the coarse lifecycle of a BLE peripheral
 * connection, shared by the DE1 and the scale managers.
 *
 * Both managers run the same connect → subscribe → ready → recover →
 * disconnect lifecycle; both report the same seven coarse states to the UI;
 * both want the same user-facing wording on the status pill. One type and
 * one label helper, so the Machine settings section and the Scale page can
 * never drift on the vocabulary.
 */

/** Coarse state of a BLE peripheral connection — mirrors the Android managers' enum. */
export type BleConnectionState =
	| 'idle' //          no peripheral picked
	| 'connecting' //    GATT connect in flight
	| 'subscribing' //   characteristics resolved, starting notifications
	| 'ready' //         notifications live, ready for I/O
	| 'reconnecting' //  auto-reconnect loop attempting to recover the link
	| 'disconnected' //  link was up and dropped, no recovery in flight
	| 'failed'; //       last connect attempt failed

/** Display label for the user-visible status pill / settings row. */
export function bleStateLabel(state: BleConnectionState): string {
	switch (state) {
		case 'idle':
			return 'Not connected';
		case 'connecting':
			return 'Connecting…';
		case 'subscribing':
			return 'Subscribing…';
		case 'ready':
			return 'Connected';
		case 'reconnecting':
			return 'Reconnecting…';
		case 'disconnected':
			return 'Disconnected';
		case 'failed':
			return 'Connection failed';
	}
}
