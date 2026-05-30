<script lang="ts">
	/**
	 * The Crema app shell.
	 *
	 * Owns the three things every route depends on:
	 *
	 *  1. The shared `CremaApp` — the orchestrator wrapping one wasm core and
	 *     one BLE connection. Created **once here**, in `onMount`, and published
	 *     via Svelte context (`setCremaAppContext`) so every route shares the
	 *     same core + connection across navigation.
	 *  2. The Web-Bluetooth support gate — Crema talks to the DE1 and scale over
	 *     Web Bluetooth, which is Chromium-only. An unsupported browser gets a
	 *     clear notice instead of dead UI.
	 *  3. The chrome — the fixed `CremaSidebar` rail plus the page content area,
	 *     which is inset by the rail's 72px width.
	 */
	import { onMount, onDestroy } from 'svelte';
	import { Effect } from 'effect';
	import '../app.css';
	import { createCremaApp, type CremaApp } from '$lib/state';
	import { CremaSidebar } from '$lib/components';
	import DebugPanel from '$lib/shell/DebugPanel.svelte';
	import { describeError } from '$lib/utils/error';
	import { setCremaAppContext, type CoreLoadState } from '$lib/shell/app-context';
	import { createAppRuntime, type AppRuntime } from '$lib/effect/runtime';
	import { UploadQueue } from '$lib/services/upload-queue';

	let { children } = $props();

	/** Web Bluetooth is Chromium-only — Firefox / Safari / iOS lack it. */
	const bluetoothSupported =
		typeof navigator !== 'undefined' && 'bluetooth' in navigator;

	let app = $state<CremaApp | null>(null);
	let runtime = $state<AppRuntime | null>(null);
	let loadState = $state<CoreLoadState>('loading');
	let loadError = $state('');

	// Publish the shared-app context — a getter so routes read live values as
	// the core loads and the BLE state changes.
	setCremaAppContext(() => ({ app, runtime, loadState, loadError }));

	// Upload-queue drain triggers, wired to network / visibility regains
	// (Option 3, T-16). `UploadQueue.armLifecycle` (fired in CremaApp) owns the
	// on-launch drain + 5-min foreground tick; these DOM listeners live at the
	// shell because they need the runtime handle and a destroy seam. Kept in
	// scope so `onDestroy` can detach them.
	let onOnline: (() => void) | null = null;
	let onVisibility: (() => void) | null = null;

	onMount(async () => {
		// `createCremaApp()` dynamic-imports and instantiates the wasm core —
		// genuinely async. Skip it entirely on browsers without Web Bluetooth:
		// the app cannot connect anything there.
		if (!bluetoothSupported) {
			loadState = 'failed';
			loadError = 'Web Bluetooth unavailable';
			return;
		}
		// Mount the Effect runtime here, in onMount — never module scope — so
		// adapter-static's build-time evaluation never touches localStorage /
		// navigator / wasm (docs/53 D-03).
		runtime = createAppRuntime();
		// Best-effort drain on reconnect / tab-refocus. `runFork` is detached
		// fire-and-forget; `drain` is offline-guarded + single-flight, so a
		// spurious trigger is cheap.
		const drainNow = (): void => {
			void runtime?.runFork(Effect.flatMap(UploadQueue, (q) => q.drain));
		};
		onOnline = () => drainNow();
		onVisibility = () => {
			if (document.visibilityState === 'visible') drainNow();
		};
		window.addEventListener('online', onOnline);
		document.addEventListener('visibilitychange', onVisibility);
		try {
			// Thread the runtime into the orchestrator so its Visualizer side
			// effects (queue lifecycle, shot-completion upload) run on it.
			app = await createCremaApp(runtime);
			loadState = 'ready';
		} catch (err) {
			loadState = 'failed';
			loadError = describeError(err);
		}
	});

	// Tear the runtime's fibers + finalizers down when the shell unmounts
	// (also fires on HMR), so a dev reload doesn't leak runtimes or listeners.
	onDestroy(() => {
		if (onOnline) window.removeEventListener('online', onOnline);
		if (onVisibility) document.removeEventListener('visibilitychange', onVisibility);
		void runtime?.dispose();
	});
</script>

<svelte:head>
	<title>Crema</title>
</svelte:head>

{#if !bluetoothSupported}
	<!-- Browser-support gate: Web Bluetooth is Chromium-only. The whole app is
	     replaced by this notice on Firefox / Safari / iOS. -->
	<main class="shell-gate">
		<div class="gate-card">
			<h1 class="t-h3">Web Bluetooth is unavailable</h1>
			<p class="t-body-sm">
				Crema talks to the DE1 and the scale over Web Bluetooth, which only
				works in a Chromium-based browser — Chrome, Edge or Opera on desktop or
				Android. This browser (or iOS, where no browser supports it) cannot
				connect.
			</p>
		</div>
	</main>
{:else}
	<CremaSidebar {app} />
	<DebugPanel {app} />
	<div class="shell-content">
		{@render children?.()}
	</div>
{/if}

<style>
	/* The content area clears the fixed side rail (--sidebar-w wide). */
	.shell-content {
		padding-left: var(--sidebar-w);
		min-height: 100vh;
	}

	/* Centred full-screen notice for the unsupported-browser gate. */
	.shell-gate {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 100vh;
		padding: var(--space-6);
	}
	.gate-card {
		max-width: 420px;
		display: flex;
		flex-direction: column;
		gap: var(--space-3);
		background: var(--bg-surface);
		border: 1px solid var(--hairline);
		border-radius: var(--radius-lg);
		box-shadow: var(--shadow-md);
		padding: var(--space-6);
	}
</style>
