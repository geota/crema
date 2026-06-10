/**
 * Screen Wake Lock — "Keep screen on while brewing".
 *
 * One module-level sentinel; {@link setBrewWakeLock} is idempotent and safe
 * to call from a reactive effect on every snapshot change. The platform
 * auto-releases the lock when the tab hides, so a `visibilitychange`
 * listener re-acquires it if a shot is still pulling when the tab returns.
 *
 * Feature-detected: on browsers without the API (or in insecure contexts,
 * where `navigator.wakeLock` is absent) everything is a silent no-op — the
 * Settings row surfaces support via {@link wakeLockSupported}.
 */

/** Whether this browser exposes the Screen Wake Lock API. */
export const wakeLockSupported =
	typeof navigator !== 'undefined' && 'wakeLock' in navigator;

let sentinel: WakeLockSentinel | null = null;
/** The desired state — drives visibility re-acquire. */
let wantActive = false;

async function acquire(): Promise<void> {
	if (!wakeLockSupported || sentinel !== null) return;
	try {
		sentinel = await navigator.wakeLock.request('screen');
		// The UA can release at any time (tab hidden, battery saver) — clear
		// our handle so the next acquire() isn't a stale no-op.
		sentinel.addEventListener('release', () => {
			sentinel = null;
		});
	} catch {
		// NotAllowedError (battery saver, permissions policy) — degrade to
		// no lock; the shot proceeds, the screen may dim. Not worth a toast.
		sentinel = null;
	}
}

/**
 * Want-the-screen-held? Call with `true` while a shot is in progress (and
 * the setting is on), `false` otherwise.
 */
export function setBrewWakeLock(active: boolean): void {
	wantActive = active;
	if (active) {
		void acquire();
	} else if (sentinel !== null) {
		void sentinel.release();
		sentinel = null;
	}
}

// Re-acquire when the tab becomes visible mid-shot (the UA released the lock
// on hide). The `typeof document` guard keeps adapter-static's build-time
// evaluation off the DOM.
if (typeof document !== 'undefined') {
	document.addEventListener('visibilitychange', () => {
		if (document.visibilityState === 'visible' && wantActive) void acquire();
	});
}
