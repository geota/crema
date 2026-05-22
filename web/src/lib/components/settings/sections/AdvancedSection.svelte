<script lang="ts">
	/**
	 * Advanced section — telemetry display toggles, integration stubs, and the
	 * reset actions.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the telemetry-display toggles and the debug-panel toggle are
	 * persisted app preferences in `lib/settings`. "Show debug / event-log
	 * panel" is a genuine BLE-diagnostics preference the shell can honour (the
	 * shared `UiSnapshot.eventLog` already exists). "Reset preferences" is real
	 * — it calls `SettingsStore.reset()`; profiles and history are untouched.
	 *
	 * **UI-only** — Home Assistant, the webhook and API-token actions need a
	 * network layer the shell lacks; factory reset is destructive and gated
	 * behind a confirm. Each is marked with a `// TODO`.
	 *
	 * **Developer** — "Replay a capture" is a real developer/admin tool: it
	 * feeds a recorded BLE capture file back through the core, so an exported
	 * shot plays out in the Brew dashboard and lands in History with no live
	 * machine. See `CremaApp.replayCapture`.
	 */
	import type { CremaApp } from '$lib/state';
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSelect from '../StSelect.svelte';
	import StButton from '../StButton.svelte';
	import { onMount } from 'svelte';

	/** The shared orchestrator, or `null` while the wasm core is still loading. */
	let { app }: { app: CremaApp | null } = $props();

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	// ---- Live line-frequency the core reports ----------------------------
	//
	// When the user has the AC-mains setting on "Auto", the core's
	// auto-detector locks 1+ seconds into the first shot. Poll the core
	// every second to surface the locked value as a hint in the select's
	// description. Polling avoids plumbing a new Event variant for what is
	// a one-time signal per session.
	let detectedHz = $state<number | null>(null);
	onMount(() => {
		if (!app) return;
		const tick = async () => {
			const hz = await app!.lineFrequencyHz();
			detectedHz = hz;
		};
		void tick();
		const id = window.setInterval(tick, 1000);
		return () => window.clearInterval(id);
	});

	/**
	 * Handle the user picking a new mains-frequency option. Stores the
	 * preference and pushes it into the core so the volume integrator
	 * switches its dt source immediately.
	 */
	function onLineFrequencyChange(value: string): void {
		const hz = (value === '50' ? 50 : value === '60' ? 60 : 0) as 0 | 50 | 60;
		settings.set('lineFrequencyHz', hz);
		void app?.setLineFrequencyOverride(hz);
	}

	// ---- Capture replay (developer tool) ---------------------------------

	/** The live replay status from the shared UI state, or `null` if none. */
	const replay = $derived(app?.state.current.replay ?? null);
	/** Whether a replay is currently in progress — gates the picker / Cancel. */
	const replayRunning = $derived(replay?.phase === 'running');

	/**
	 * Handle a chosen capture file: hand it to `app.replayCapture`, then clear
	 * the input so picking the same file again re-fires the `change` event.
	 */
	function onCaptureChosen(event: Event): void {
		const input = event.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (file && app) void app.replayCapture(file);
	}

	// TODO: Home Assistant MQTT bridge needs a network layer; local UI only.
	let homeAssistant = $state(false);

	function resetPreferences(): void {
		if (
			typeof window === 'undefined' ||
			window.confirm(
				'Restore default brew, display, sound and advanced settings? Profiles and shot history are untouched.'
			)
		) {
			settings.reset();
		}
	}

	function factoryReset(): void {
		// TODO: a true factory reset would clear every Crema localStorage key
		// (profiles, history, settings). Gated behind a confirm; left as a stub
		// so it cannot silently wipe data during the port.
		if (
			typeof window !== 'undefined' &&
			window.confirm(
				'Factory reset erases everything on this device — profiles, history and settings. This cannot be undone. Continue?'
			)
		) {
			// Intentionally not wired during the port. See TODO above.
		}
	}

	function configureWebhook(): void {
		// TODO: a shot-end webhook needs a network layer. Stub.
	}
	function newToken(): void {
		// TODO: API tokens need an account / server. Stub.
	}
</script>

<StSectionHead
	eyebrow="Power user"
	title="Advanced"
	sub="Things most people shouldn't touch."
/>

<StGroup title="Telemetry">
	<StRow
		title="AC mains frequency"
		sub={prefs.lineFrequencyHz === 0
			? `Auto-detect from the DE1's sample-time stream. ${
				detectedHz != null
					? `Currently locked at ${detectedHz} Hz.`
					: 'Locks 1+ seconds into the first shot of a session.'
			}`
			: `Pinned at ${prefs.lineFrequencyHz} Hz. Switch back to Auto to let the detector run.`}
	>
		{#snippet control()}
			<StSelect
				value={String(prefs.lineFrequencyHz)}
				options={[
					{ value: '0', label: 'Auto' },
					{ value: '50', label: '50 Hz' },
					{ value: '60', label: '60 Hz' }
				]}
				onChange={onLineFrequencyChange}
			/>
		{/snippet}
	</StRow>
	<StRow title="Show flow curve in chart">
		{#snippet control()}
			<StToggle
				on={prefs.showFlowCurve}
				onChange={(v) => settings.set('showFlowCurve', v)}
				label="Show flow curve in chart"
			/>
		{/snippet}
	</StRow>
	<StRow title="Show estimated puck resistance">
		{#snippet control()}
			<StToggle
				on={prefs.showPuckResistance}
				onChange={(v) => settings.set('showPuckResistance', v)}
				label="Show estimated puck resistance"
			/>
		{/snippet}
	</StRow>
	<StRow title="Smooth pressure curve">
		{#snippet control()}
			<StToggle
				on={prefs.smoothPressure}
				onChange={(v) => settings.set('smoothPressure', v)}
				label="Smooth pressure curve"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Diagnostics">
	<StRow
		title="Show debug / event-log panel"
		sub="Surfaces the raw BLE event log — connect / notify / decode lines — for diagnosing machine and scale issues."
	>
		{#snippet control()}
			<StToggle
				on={prefs.showDebugPanel}
				onChange={(v) => settings.set('showDebugPanel', v)}
				label="Show debug / event-log panel"
			/>
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Integrations">
	<StRow
		title="Home Assistant"
		sub="Expose shot start/end and temperature as MQTT topics."
	>
		{#snippet control()}
			<StToggle
				on={homeAssistant}
				onChange={(v) => (homeAssistant = v)}
				label="Home Assistant"
			/>
		{/snippet}
	</StRow>
	<StRow title="Webhook on shot end" sub="POST shot JSON to a URL of your choice.">
		{#snippet control()}
			<StButton label="Configure" icon="link" onClick={configureWebhook} />
		{/snippet}
	</StRow>
	<StRow
		title="API access"
		sub="Generate a personal API token for third-party clients."
	>
		{#snippet control()}
			<StButton label="New token" icon="key" onClick={newToken} />
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Developer">
	<StRow
		title="Replay a capture"
		sub="Load a recorded BLE capture (.jsonl) and play it back through the core — the shot fills the Brew dashboard and lands in History with no live machine. After starting, open Brew or History to watch it play out."
	>
		{#snippet control()}
			<div class="rp-control">
				<label class="st-btn st-btn-secondary rp-pick" class:rp-disabled={!app || replayRunning}>
					<i class="ph ph-upload-simple" aria-hidden="true"></i>
					<span>Choose file…</span>
					<input
						type="file"
						accept=".jsonl,.json"
						class="rp-input"
						disabled={!app || replayRunning}
						onchange={onCaptureChosen}
					/>
				</label>
				{#if replayRunning}
					<StButton
						label="Cancel"
						icon="x"
						variant="danger"
						onClick={() => app?.cancelReplay()}
					/>
				{/if}
			</div>
		{/snippet}
		{#snippet hint()}
			{#if replay}
				<span
					class="rp-status"
					class:rp-status-run={replay.phase === 'running'}
					class:rp-status-ok={replay.phase === 'done'}
					class:rp-status-err={replay.phase === 'error'}
				>
					{#if replay.phase === 'running'}
						Replaying {replay.fileName} — {replay.done} / {replay.total}
					{:else if replay.phase === 'done'}
						Done — {replay.total} events from {replay.fileName}. Open Brew or History to watch.
					{:else if replay.phase === 'cancelled'}
						Cancelled — {replay.done} / {replay.total} events.
					{:else}
						{replay.message}
					{/if}
				</span>
			{:else}
				Developer tool — for testing without a DE1.
			{/if}
		{/snippet}
	</StRow>
</StGroup>

<StGroup title="Reset">
	<StRow
		title="Reset preferences"
		sub="Restore default brew/display/sound settings. Profiles and shot history are untouched."
	>
		{#snippet control()}
			<StButton
				label="Reset"
				icon="arrow-counter-clockwise"
				onClick={resetPreferences}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Factory reset"
		sub="Erases everything on this device — profiles, history and settings."
	>
		{#snippet control()}
			<button type="button" class="st-btn st-btn-danger" onclick={factoryReset}>
				<i class="ph ph-warning" aria-hidden="true"></i>Factory reset
			</button>
		{/snippet}
	</StRow>
</StGroup>

<style>
	/* ── Capture-replay developer control ──────────────────────────────────
	   The file picker is a native <input type="file"> hidden inside a <label>
	   styled as an StButton, so it matches the settings kit; the visible
	   chrome is the label. */
	.rp-control {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.rp-pick {
		cursor: pointer;
	}
	.rp-input {
		display: none;
	}
	.rp-disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.rp-status {
		font-variant-numeric: tabular-nums;
	}
	.rp-status-run {
		color: var(--copper-400);
	}
	.rp-status-ok {
		color: var(--success);
	}
	.rp-status-err {
		color: var(--danger);
	}
</style>
