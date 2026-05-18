<script lang="ts">
	import { onMount } from 'svelte';
	import { createCremaApp, type CremaApp } from '$lib/state';
	import type { De1State } from '$lib/ble/de1';
	import { ScaleCard, ReadoutCard } from '$lib/components';

	/**
	 * The POC screen — a faithful port of the Android shell's `MainScreen`
	 * (`MainActivity.kt`): a header, a DE1 connect block, the scale card, the
	 * DE1 readout card, and the decoded-event log, in a single scrollable
	 * column.
	 *
	 * Two browser realities the Android shell does not face are handled up
	 * front: Web Bluetooth is Chromium-only, so an unsupported browser gets a
	 * clear notice instead of dead buttons; and the wasm core loads
	 * asynchronously, so the screen shows a loading state until it is ready.
	 */

	/** Web Bluetooth is Chromium-only — Firefox / Safari / iOS lack it. */
	const bluetoothSupported = typeof navigator !== 'undefined' && 'bluetooth' in navigator;

	type LoadState = 'loading' | 'ready' | 'failed';
	let loadState = $state<LoadState>('loading');
	let loadError = $state('');
	let app = $state<CremaApp | null>(null);

	onMount(async () => {
		// `createCremaApp()` dynamic-imports and instantiates the wasm core —
		// genuinely async, hence the loading state above.
		try {
			app = await createCremaApp();
			loadState = 'ready';
		} catch (err) {
			loadState = 'failed';
			loadError = err instanceof Error ? err.message : String(err);
		}
	});

	/** The live snapshot, only once the orchestrator exists. */
	const ui = $derived(app?.state.current ?? null);

	/**
	 * True while a DE1 is connected or mid-handshake — the Android shell's
	 * `connected` rule: Connect disables, Disconnect enables.
	 */
	const de1Connected = $derived(
		ui !== null && (['connecting', 'subscribing', 'ready'] as De1State[]).includes(ui.de1State)
	);
</script>

<svelte:head>
	<title>Crema</title>
</svelte:head>

<main class="bg-base-200 min-h-screen">
	<div class="mx-auto flex max-w-md flex-col gap-4 p-4">
		<header class="flex flex-col gap-1">
			<h1 class="text-3xl font-bold tracking-tight">Crema</h1>
			<p class="text-base-content/60 text-sm">
				A web shell for the DE1 — live BLE readout over the shared Rust core.
			</p>
		</header>

		{#if !bluetoothSupported}
			<!-- Browser-support gate: Web Bluetooth is Chromium-only. Rather than
			     show dead connect buttons, the whole interactive UI is replaced
			     by this notice on Firefox / Safari / iOS. -->
			<div class="alert alert-warning">
				<div class="flex flex-col gap-1">
					<span class="font-semibold">Web Bluetooth is unavailable</span>
					<span class="text-sm">
						Crema talks to the DE1 and the scale over Web Bluetooth, which only
						works in a Chromium-based browser — Chrome, Edge or Opera on desktop
						or Android. This browser (or iOS, where no browser supports it)
						cannot connect.
					</span>
				</div>
			</div>
		{:else if loadState === 'loading'}
			<div class="alert">
				<span class="loading loading-spinner loading-sm"></span>
				<span>Loading core…</span>
			</div>
		{:else if loadState === 'failed'}
			<div class="alert alert-error">
				<div class="flex flex-col gap-1">
					<span class="font-semibold">Core failed to load</span>
					<span class="font-mono text-xs break-all">{loadError}</span>
				</div>
			</div>
		{:else if app && ui}
			<!-- DE1 connect block — Connect disables while connected, Disconnect
			     enables only then, exactly the Android shell's enabled rules. -->
			<div class="flex flex-col gap-2">
				<button
					type="button"
					class="btn btn-primary"
					disabled={de1Connected}
					onclick={() => app?.connectDe1()}
				>
					Connect to DE1
				</button>
				<button
					type="button"
					class="btn btn-outline"
					disabled={!de1Connected}
					onclick={() => app?.disconnectDe1()}
				>
					Disconnect
				</button>
			</div>

			<ScaleCard {app} />

			<ReadoutCard {ui} />

			<section class="flex flex-col gap-2">
				<h2 class="text-lg font-semibold">Decoded events</h2>
				{#if ui.eventLog.length === 0}
					<p class="text-base-content/50 text-sm">No events yet.</p>
				{:else}
					<!-- Newest-first, monospace, capped at 200 lines by the state
					     layer; each line already carries an HH:mm:ss prefix. -->
					<ul class="bg-base-100 flex flex-col gap-0.5 rounded-box p-3 shadow-inner">
						{#each ui.eventLog as line, i (i)}
							<li class="font-mono text-xs break-all">{line}</li>
						{/each}
					</ul>
				{/if}
			</section>
		{/if}
	</div>
</main>
