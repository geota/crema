/**
 * `#lib/pwa/register.js` — register the service worker and offer updates.
 *
 * `vite-plugin-pwa` injects its registration through Vite's HTML hook, which
 * SvelteKit never runs for `app.html`, so the worker it builds (`sw.js`, the
 * precached app shell + wasm core) was never installed. This registers it by
 * hand and drives the `prompt` update strategy:
 *
 * 1. A new build installs in the background and waits (the worker is built
 *    without `skipWaiting`).
 * 2. The shell is asked whether a reload is safe ({@link Deps.canReload}); if
 *    not, the offer is parked until the shell reports a change
 *    ({@link Handle.onStateChange}).
 * 3. When safe, {@link Deps.offer} shows the user a reload action. Applying
 *    it tells the waiting worker to take over and reloads once it has.
 *
 * Nothing here runs under `vite dev` (the plugin is build-only) or on
 * browsers without service workers. DOM access is injected so the lifecycle
 * is unit-testable.
 */

/** The worker's URL and scope: the app is served at the apex, so both are `/`. */
export const SW_URL = '/sw.js';
export const SW_SCOPE = '/';
/** How often an open tab asks the browser to look for a new build. */
export const UPDATE_CHECK_MS = 60 * 60 * 1000;

export interface Deps {
	/** Whether applying an update (a reload) would interrupt nothing right now. */
	readonly canReload: () => boolean;
	/**
	 * Show the user that an update is ready; `apply` performs it. Called at
	 * most once per waiting worker.
	 */
	readonly offer: (apply: () => void) => void;
	/** `navigator.serviceWorker` (injected for tests). */
	readonly container?: ServiceWorkerContainer;
	/** `location.reload` (injected for tests). */
	readonly reload?: () => void;
	/** `document`, for the visibility hook (injected for tests). */
	readonly doc?: Pick<Document, 'addEventListener' | 'removeEventListener' | 'visibilityState'>;
	/** Timer hooks (injected for tests). */
	readonly setInterval?: typeof globalThis.setInterval;
	readonly clearInterval?: typeof globalThis.clearInterval;
}

export interface Handle {
	/** The shell calls this when the machine/brew state changed: a parked offer is retried. */
	readonly onStateChange: () => void;
	/** Remove listeners and timers. */
	readonly dispose: () => void;
}

/**
 * Register `/sw.js` and wire the update flow. Returns a no-op handle where
 * service workers are unavailable. Registration failures are logged, never
 * thrown: the app runs fine without the worker, as it always has.
 */
export function installServiceWorker(deps: Deps): Handle {
	const container = deps.container ?? (typeof navigator !== 'undefined' ? navigator.serviceWorker : undefined);
	if (!container) return { onStateChange: () => {}, dispose: () => {} };
	const reload = deps.reload ?? (() => location.reload());
	const doc = deps.doc ?? document;
	const setI = deps.setInterval ?? globalThis.setInterval.bind(globalThis);
	const clearI = deps.clearInterval ?? globalThis.clearInterval.bind(globalThis);

	let registration: ServiceWorkerRegistration | null = null;
	/** The installed-and-waiting worker the user has not been offered yet. */
	let parked: ServiceWorker | null = null;
	let offered = false;
	let reloading = false;

	const apply = (waiting: ServiceWorker): void => {
		// The new worker takes over, then every open tab's controller changes;
		// reload once on that signal (not before: the old shell would still be
		// served).
		container.addEventListener('controllerchange', () => {
			if (reloading) return;
			reloading = true;
			reload();
		});
		waiting.postMessage({ type: 'SKIP_WAITING' });
	};

	const consider = (waiting: ServiceWorker): void => {
		if (offered) return;
		if (!deps.canReload()) {
			parked = waiting;
			return;
		}
		parked = null;
		offered = true;
		deps.offer(() => apply(waiting));
	};

	const watch = (reg: ServiceWorkerRegistration): void => {
		registration = reg;
		// A build that installed while this tab was closed is already waiting.
		if (reg.waiting && container.controller) consider(reg.waiting);
		reg.addEventListener('updatefound', () => {
			const installing = reg.installing;
			if (!installing) return;
			installing.addEventListener('statechange', () => {
				// `installed` with a controller present = a NEW build waiting
				// behind the one serving this tab. Without a controller it is
				// the very first install, which needs no prompt.
				if (installing.state === 'installed' && container.controller) consider(installing);
			});
		});
	};

	const checkForUpdate = (): void => {
		registration?.update().catch(() => {});
	};
	const onVisibility = (): void => {
		if (doc.visibilityState === 'visible') checkForUpdate();
	};
	doc.addEventListener('visibilitychange', onVisibility);
	const timer = setI(checkForUpdate, UPDATE_CHECK_MS);

	container
		.register(SW_URL, { scope: SW_SCOPE })
		.then(watch)
		.catch((e: unknown) => console.warn('[Crema] service worker registration failed:', e));

	return {
		onStateChange: () => {
			if (parked) consider(parked);
		},
		dispose: () => {
			doc.removeEventListener('visibilitychange', onVisibility);
			clearI(timer);
		}
	};
}
