/**
 * `$lib/shell/app-context` — the shell-level `CremaApp` singleton.
 *
 * The orchestrator (`CremaApp`) owns one wasm core and one BLE connection. It
 * must be created **once**, at the app shell, and shared by every route, so a
 * DE1 / scale connection survives navigation between Brew, Profiles, History,
 * Scale and Settings.
 *
 * The shell calls {@link setCremaAppContext} with a reactive holder; each
 * route reads it with {@link getCremaAppContext}. Svelte context is set during
 * the shell component's init and inherited by every child route — exactly the
 * lifetime we want.
 */

import { getContext, setContext } from 'svelte';
import type { CremaApp } from '$lib/state';

/** How the wasm core load is progressing — drives the shell's gate UI. */
export type CoreLoadState = 'loading' | 'ready' | 'failed';

/**
 * The reactive handle the shell publishes to routes. `app` is `null` until the
 * wasm core finishes loading; `loadState` / `loadError` let a route show a
 * loading or error state instead of dereferencing a null app.
 */
export interface CremaAppContext {
	/** The shared orchestrator, or `null` while the core is still loading. */
	readonly app: CremaApp | null;
	/** Lifecycle of the one-time core load. */
	readonly loadState: CoreLoadState;
	/** The failure message when `loadState === 'failed'`. */
	readonly loadError: string;
}

/** Context key — a symbol so it cannot collide with anything else. */
const CREMA_APP_KEY = Symbol('crema-app');

/**
 * Publish the shared-app context. Call once, in the shell component's script
 * top-level, passing a getter that reads the shell's reactive `$state`.
 */
export function setCremaAppContext(get: () => CremaAppContext): void {
	setContext(CREMA_APP_KEY, get);
}

/**
 * Read the shared-app context from any route. Returns a getter; call it inside
 * a `$derived` so the route stays reactive as the core loads and the BLE state
 * changes.
 */
export function getCremaAppContext(): () => CremaAppContext {
	return getContext<() => CremaAppContext>(CREMA_APP_KEY);
}
