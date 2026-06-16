/**
 * `useVisualizerConnection` — the shared "connected to Visualizer?" gate (SV1).
 *
 * BeanDeleteSplit, RoasterDeleteSplit and SharingSection each copied the same
 * three-line block: a `connected` signal seeded once on mount from
 * `tokens.isConnected()`, then kept live by `tokens.onConnectionChange` (whose
 * first emission also re-seeds the current state, so a sign-in / sign-out from
 * ANY surface propagates instead of going stale). This wraps that block.
 *
 * Call it once at the top of a component's `<script>` — it uses `getContext`
 * (via {@link getCremaAppContext}), `onMount`, and `$effect`, all of which must
 * run during component initialisation.
 *
 * `connected` is settable so a component that owns a local transition — an
 * explicit Disconnect that calls `clearTokens()` — can reflect it
 * optimistically and synchronously, rather than waiting for the async
 * subscription fiber to echo the same value back a tick later. The subscription
 * then confirms it (a redundant no-op).
 *
 * @example
 * const viz = useVisualizerConnection();
 * // read: viz.connected   ·   optimistic local sign-out: viz.connected = false
 */
import { onMount } from 'svelte';
import { getCremaAppContext } from '$lib/shell/app-context';

export interface VisualizerConnection {
	/** `true` once a Visualizer token set is stored. See module doc for the
	    seed + subscription lifecycle and why this is settable. */
	connected: boolean;
}

export function useVisualizerConnection(): VisualizerConnection {
	const appCtx = getCremaAppContext();
	let connected = $state(false);
	onMount(() => {
		void (async () => {
			connected = (await appCtx().services?.tokens.isConnected()) ?? false;
		})();
	});
	// SV1: a sign-in / sign-out from any surface propagates here (the
	// subscription's first emission also seeds the current state). The returned
	// unsubscribe becomes the $effect cleanup.
	$effect(() => appCtx().services?.tokens.onConnectionChange((c) => (connected = c)));
	return {
		get connected() {
			return connected;
		},
		set connected(v: boolean) {
			connected = v;
		}
	};
}
