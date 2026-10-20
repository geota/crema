/**
 * `$lib/ble/availability` — whether the browser says Bluetooth is usable
 * right now: `navigator.bluetooth.getAvailability()` plus the
 * `availabilitychanged` event.
 *
 * Chrome reports adapter presence (on most platforms, also whether it's
 * switched on); Beacio on iOS also reports whether the extension is
 * reachable. Read synchronously by the Connect handlers, which must not
 * `await` before `requestDevice()` — the device chooser needs the click's
 * user activation, and Safari's is short.
 *
 * `null` means unknown (not asked yet, or no API): treat it as available, so a
 * browser that never answers can still open the chooser.
 */

let available: boolean | null = null;

/** The last answer: `false` = Bluetooth is off or unusable, `null` = unknown. */
export function bluetoothAvailable(): boolean | null {
	return available;
}

/**
 * Track availability and call `onChange` with each new value. Returns a stop
 * function. Safe to call again after the iOS bridge installs: it starts over
 * on the current `navigator.bluetooth`.
 */
export function watchBluetoothAvailability(
	onChange: (available: boolean) => void,
	bluetooth: Bluetooth | undefined = typeof navigator !== 'undefined'
		? navigator.bluetooth
		: undefined
): () => void {
	if (bluetooth === undefined || typeof bluetooth.getAvailability !== 'function') {
		return () => undefined;
	}
	let stopped = false;
	const set = (value: boolean): void => {
		if (stopped || value === available) return;
		available = value;
		onChange(value);
	};
	const onEvent = (event: Event): void => {
		const value = (event as Event & { value?: unknown }).value;
		if (typeof value === 'boolean') set(value);
	};
	bluetooth.addEventListener?.('availabilitychanged', onEvent);
	bluetooth.getAvailability().then(set, () => undefined);
	return () => {
		stopped = true;
		bluetooth.removeEventListener?.('availabilitychanged', onEvent);
	};
}

/** Test seam: forget the last answer. */
export function resetBluetoothAvailabilityForTests(): void {
	available = null;
}
