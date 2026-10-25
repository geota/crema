/**
 * `$lib/ble/connect-blocker` — why a Connect tap can't open the Bluetooth
 * chooser in this browser right now, in words the user can act on.
 *
 * Synchronous on purpose: it runs inside the click handler, before
 * `requestDevice()`, which needs the click's user activation.
 */

import { bluetoothAvailable } from './availability';
import { isAppleMobile, isIOSHomeScreenApp, openInSafariUrl } from './ios-bridge';
import { isWebBluetoothSupported } from './transport';

export interface ConnectBlocker {
	readonly message: string;
	/** An `x-safari-https://…` link that reopens Crema in Safari, when that's the fix. */
	readonly openInSafari?: string;
}

/** Why connecting can't work here, or null when it can. */
export function connectBlocker(): ConnectBlocker | null {
	if (!isWebBluetoothSupported()) {
		if (isIOSHomeScreenApp()) {
			const href = openInSafariUrl(window.location.href);
			return {
				message:
					"Bluetooth doesn't work in the Home Screen app on iPhone or iPad — open Crema in Safari, with the Beacio extension turned on.",
				...(href !== null ? { openInSafari: href } : {})
			};
		}
		if (isAppleMobile()) {
			return {
				message:
					'Safari needs the Beacio extension for Bluetooth — install it from the App Store, then turn it on for this site in Settings → Apps → Safari → Extensions. It is off in Private Browsing.'
			};
		}
		return {
			message:
				"This browser can't connect over Bluetooth — use Chrome, Edge or Opera on desktop or Android."
		};
	}
	if (bluetoothAvailable() === false) {
		return {
			message: isAppleMobile()
				? "Bluetooth is unavailable — check it's on, and that the Beacio extension is allowed on this site."
				: 'Bluetooth is off or unavailable — turn it on and try again.'
		};
	}
	return null;
}
