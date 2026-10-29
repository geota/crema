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
	 *  2. The Web-Bluetooth notice — Crema talks to the DE1 and scale over Web
	 *     Bluetooth, which is Chromium-only. An unsupported browser (Firefox /
	 *     Safari / iOS) still loads the whole app — everything that doesn't need a
	 *     machine works — and just gets a toast that connecting won't work here.
	 *  3. The chrome — the fixed `CremaSidebar` rail plus the page content area,
	 *     which is inset by the rail's 72px width.
	 */
	import { onMount, onDestroy } from 'svelte';
	import '../app.css';
	import { createCremaApp, type CremaApp } from '#lib/state/index.js';
	import { CremaSidebar } from '#lib/components/index.js';
	import DebugPanel from '#lib/shell/DebugPanel.svelte';
	import ReconnectStrip from '#lib/shell/ReconnectStrip.svelte';
	import ToastHost from '#lib/components/shared/ToastHost.svelte';
	import { dismissToast, toast } from '#lib/components/shared/toast.svelte.js';
	import ConfirmDialog from '#lib/components/shared/ConfirmDialog.svelte';
	import { describeError } from '#lib/utils/error.js';
	import { installStorageNotices } from '#lib/shell/storage-notices.js';
	import { setCremaAppContext, type CoreLoadState } from '#lib/shell/app-context.js';
	import { createAppRuntime, type AppRuntime } from '#lib/effect/runtime.js';
	import { createCremaServices, type CremaServices } from '#lib/effect/crema-services.js';
	import { setBrewWakeLock } from '#lib/shell/wake-lock.js';
	import FirmwareUpdateHost from '#lib/components/settings/FirmwareUpdateHost.svelte';
	import { retryPendingDecentUploads } from '#lib/decent/upload.js';
	import { getGuidedBrewStore } from '#lib/brew/session.svelte.js';
	import { getSettingsStore } from '#lib/settings/index.js';
	import { installServiceWorker, type Handle as ServiceWorkerHandle } from '#lib/pwa/register.js';
	import { canReloadNow, reloadLinkOf, type ReloadInputs } from '#lib/pwa/update-gate.js';

	let { children } = $props();

	/** Web Bluetooth is Chromium-only — Firefox / Safari / iOS lack it. */
	const bluetoothSupported =
		typeof navigator !== 'undefined' && 'bluetooth' in navigator;

	let app = $state<CremaApp | null>(null);
	let runtime = $state<AppRuntime | null>(null);
	let services = $state<CremaServices | null>(null);
	let loadState = $state<CoreLoadState>('loading');
	let loadError = $state('');

	// Publish the shared-app context — a getter so routes read live values as
	// the core loads and the BLE state changes.
	setCremaAppContext(() => ({ app, runtime, services, loadState, loadError }));

	// Upload-queue drain triggers, wired to network / visibility regains
	// (Option 3, T-16). `UploadQueue.armLifecycle` (fired in CremaApp) owns the
	// on-launch drain + 5-min foreground tick; these DOM listeners live at the
	// shell because they need the runtime handle and a destroy seam. Kept in
	// scope so `onDestroy` can detach them.
	let onOnline: (() => void) | null = null;
	let onVisibility: (() => void) | null = null;
	let onPageShow: ((e: PageTransitionEvent) => void) | null = null;
	// Unreadable stores kept aside / failed saves -> toasts (see storage-notices).
	let stopStorageNotices: (() => void) | null = null;
	// The service worker (offline app shell + wasm precache) and its update
	// offer. Production only: the PWA plugin is build-only, so `vite dev` has
	// no sw.js to register.
	let serviceWorker: ServiceWorkerHandle | null = null;

	/**
	 * What a reload would interrupt right now — a pulling shot, a guided brew
	 * mid-session, a firmware transfer, or a DE1 that is doing anything but
	 * sitting idle. Read inside an `$effect` so every change re-evaluates a
	 * parked update offer.
	 */
	function reloadInputs(): ReloadInputs {
		const snapshot = app?.state.current ?? null;
		const brewPhase = getGuidedBrewStore().phase;
		return {
			link: reloadLinkOf(snapshot?.de1State),
			machineStateName: snapshot?.machineStateName ?? null,
			shotInProgress: snapshot?.shotInProgress ?? false,
			guidedBrewLive: brewPhase === 'armed' || brewPhase === 'running' || brewPhase === 'paused',
			firmwareBusy: app?.firmware.running ?? false
		};
	}

	onMount(async () => {
		stopStorageNotices = installStorageNotices();
		if (import.meta.env.PROD) {
			serviceWorker = installServiceWorker({
				canReload: () => canReloadNow(reloadInputs()),
				// Pinned until acted on: a new build is never applied behind the
				// user's back, and the next launch picks it up regardless. A newer
				// build withdraws this toast and offers itself.
				offer: (apply) => {
					const id = toast.action(
						'Crema has been updated. Reload when you are ready.',
						'Reload',
						apply,
						0
					);
					return () => dismissToast(id);
				},
				// Tapped mid-shot (or mid-steam): not now; offered again once idle.
				onDeferred: () =>
					toast.info('The machine is busy — the update will be offered again once it is idle.', 6000)
			});
		}
		// Web Bluetooth is Chromium-only. The app still loads on Firefox / Safari /
		// iOS — history, beans, profiles and Visualizer sync all work without a
		// machine — but connecting to a DE1 or scale won't, so warn once on launch.
		if (!bluetoothSupported) {
			toast.error(
				"This browser doesn't support Web Bluetooth, so Crema can't connect to a " +
					'machine or scale. Use Chrome, Edge or Opera on desktop or Android.',
				12000
			);
		}
		// Mount the Effect runtime here, in onMount — never module scope — so
		// adapter-static's build-time evaluation never touches localStorage /
		// navigator / wasm (docs/53 D-03).
		runtime = createAppRuntime();
		// Bind the Promise-shaped service facade to the runtime here at the shell
		// and publish it via context (D-04): components call `services.*` instead
		// of crossing the Effect boundary by hand.
		services = createCremaServices(runtime);
		// Keep the cached Visualizer Premium flag (it gates the edit sync's
		// Premium-only PATCH fields) fresh: re-probe at most once per 24 h.
		// Fire-and-forget; a no-op when signed out, and it never rejects.
		void services.beans.refreshPremiumIfStale();
		// Best-effort drain on reconnect / tab-refocus. Fire-and-forget (not
		// awaited); `drain` is offline-guarded + single-flight, so a spurious
		// trigger is cheap.
		const drainNow = (): void => {
			void services?.queue.drain();
		};
		// Decent auto-uploads that failed on the network (`retryShotIds`) are
		// retried on reconnect and once at launch — only once the core (wasm)
		// is up, since the upload builds its payload there. The catch keeps a
		// surprise from becoming an unhandled rejection.
		const retryDecent = (): void => {
			if (loadState !== 'ready') return;
			retryPendingDecentUploads().catch((e: unknown) =>
				console.warn('[Crema] Decent retry failed:', e)
			);
		};
		onOnline = () => {
			drainNow();
			retryDecent();
		};
		onVisibility = () => {
			if (document.visibilityState !== 'visible') return;
			drainNow();
			// Back in view: a DE1 / scale whose auto-reconnect is sitting out a
			// backoff or the 60 s lurk (timers Chrome throttles in a hidden tab)
			// tries again now. Debounced per device; a no-op when connected.
			app?.kickReconnect('foreground');
		};
		// Restored from the back/forward cache: no visibilitychange fires.
		onPageShow = (e) => {
			if (e.persisted) app?.kickReconnect('foreground');
		};
		window.addEventListener('online', onOnline);
		document.addEventListener('visibilitychange', onVisibility);
		window.addEventListener('pageshow', onPageShow);
		try {
			// Thread the runtime into the orchestrator so its Visualizer side
			// effects (queue lifecycle, shot-completion upload) run on it.
			app = await createCremaApp(runtime);
			loadState = 'ready';
			retryDecent();
		} catch (err) {
			loadState = 'failed';
			loadError = describeError(err);
		}
	});

	// "Keep screen on while brewing" — hold a Screen Wake Lock exactly while a
	// shot is pulling and the Display setting is on. Reactive over both the
	// snapshot and the setting; setBrewWakeLock is idempotent and a no-op on
	// browsers without the API. Lives here at the shell because the lock is
	// app-wide, not page-scoped (the user may navigate mid-shot).
	$effect(() => {
		const a = app;
		// A live guided-brew session (issue #10) holds the screen
		// regardless of the setting — a step timer behind a dark screen is
		// a missed pour — except during a long step (the cold brew's 12 h
		// steep), see `wantsWakeLock`. Shots keep honoring the setting.
		const on =
			(a !== null &&
				getSettingsStore().current.keepScreenOnBrew &&
				a.state.current.shotInProgress) ||
			getGuidedBrewStore().wantsWakeLock;
		setBrewWakeLock(on);
	});

	// A parked update offer (the machine was busy when the build arrived) is
	// retried whenever the inputs change — reading them here is what subscribes.
	$effect(() => {
		reloadInputs();
		serviceWorker?.onStateChange();
	});

	// Tear the runtime's fibers + finalizers down when the shell unmounts
	// (also fires on HMR), so a dev reload doesn't leak runtimes or listeners.
	onDestroy(() => {
		stopStorageNotices?.();
		serviceWorker?.dispose();
		if (onOnline) window.removeEventListener('online', onOnline);
		if (onVisibility) document.removeEventListener('visibilitychange', onVisibility);
		if (onPageShow) window.removeEventListener('pageshow', onPageShow);
		void runtime?.dispose();
	});
</script>

<svelte:head>
	<title>Crema</title>
</svelte:head>

<!-- The app loads on every browser; the Web-Bluetooth notice is a toast (see
     onMount), and the connect actions guard themselves on unsupported browsers. -->
<CremaSidebar {app} />
<DebugPanel {app} />
<div class="shell-content">
	<ReconnectStrip {app} />
	{@render children?.()}
</div>
<!-- App-wide in-app dialog + toast hosts (FD4) — replace native
     confirm/alert/prompt. Single instances, driven by the shared stores. -->
<ConfirmDialog />
<ToastHost />
<!-- The firmware-update flow (alpha) — app-wide so a running flash or its
     power-cycle step stays visible on every page. -->
<FirmwareUpdateHost {app} />

<style>
	/* The content area clears the fixed side rail (--sidebar-w wide). */
	.shell-content {
		padding-left: var(--sidebar-w);
		min-height: 100vh;
	}
</style>
