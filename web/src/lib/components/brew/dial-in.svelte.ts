/**
 * `dial-in` — the cross-route "start from this shot" hand-off.
 *
 * The Quick Sheet's `BrewParamState` lives inside `BrewDashboard`, so a
 * shot picked on `/history` cannot prefill it directly. The history
 * detail records the chosen shot here and navigates to Brew; the
 * dashboard consumes it on mount and applies profile + bean + dial
 * params in one place (`applyDialIn`). A one-slot mailbox, not a store:
 * `take` clears it so a later plain visit to Brew doesn't replay a
 * stale request.
 */

import type { AnyShot } from '#lib/history/index.js';

let pending = $state<AnyShot | null>(null);

/** Record the shot the brew screen should dial itself from. */
export function requestDialIn(shot: AnyShot): void {
	pending = shot;
}

/** Consume (and clear) the pending dial-in request, if any. */
export function takePendingDialIn(): AnyShot | null {
	const shot = pending;
	pending = null;
	return shot;
}
