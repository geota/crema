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
	 * **Factory reset** — destructive; wipes every `crema.*` localStorage
	 * key and reloads. Gated behind a confirm. DE1 firmware is untouched.
	 *
	 * **Webhooks** — implemented: outbound POSTs from the browser to a
	 * user-supplied URL when chosen events fire (subject to CORS). The
	 * live fire path lives in `CremaApp.fireWebhook`; the test button
	 * uses `CremaApp.sendTestWebhook`. Five event toggles + URL + master
	 * switch all persist in `settings`.
	 *
	 * **Developer** — "Replay a capture" is a real developer/admin tool: it
	 * feeds a recorded BLE capture file back through the core, so an exported
	 * shot plays out in the Brew dashboard and lands in History with no live
	 * machine. See `CremaApp.replayCapture`.
	 */
	import { getMachineReadout, type CremaApp, type UiSnapshot } from '$lib/state';
	import { getSettingsStore } from '$lib/settings';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StToggle from '../StToggle.svelte';
	import StSelect from '../StSelect.svelte';
	import StButton from '../StButton.svelte';
	import MainsConfirmModal from '../MainsConfirmModal.svelte';
	import { onMount } from 'svelte';
	import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';

	/** The shared orchestrator, or `null` while the wasm core is still loading. */
	let { app, snapshot }: { app: CremaApp | null; snapshot: UiSnapshot } = $props();

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
	 * Handle the user picking a new mains-frequency option.
	 *
	 * "Auto" (`0`) commits immediately — the auto-detector takes over, no
	 * mis-calibration possible.
	 *
	 * `50` / `60` open the {@link MainsConfirmModal} first; only after the
	 * user types the value verbatim do we persist the preference and push
	 * it into the core. Hz mis-calibration is non-damaging (just ~5%
	 * volume drift) but we still gate it for UX symmetry with the
	 * heater-voltage control.
	 */
	function onLineFrequencyChange(value: string): void {
		const hz = (value === '50' ? 50 : value === '60' ? 60 : 0) as 0 | 50 | 60;
		if (hz === 0) {
			settings.set('lineFrequencyHz', 0);
			void app?.setLineFrequencyOverride(0);
			return;
		}
		// Open the type-to-confirm modal; commit lands in `onHzConfirm`.
		pendingHz = hz;
	}

	// ---- Mains heater voltage (MMR 0x803834) ----------------------------
	//
	// The current voltage is read at connect time and folded into the
	// shared snapshot as `de1MachineInfo[HeaterVoltage]`. The firmware
	// stamps `+1000` on user-committed values, so a raw read of `1120` /
	// `1230` decodes to `120` / `230`. A raw `0` means the firmware has
	// not yet been told (legacy: "detect from heater dB").
	const machine = getMachineReadout();
	const heaterVoltageRaw = $derived(machine.heaterVoltage);
	const heaterVoltage = $derived.by(() => {
		const v = heaterVoltageRaw;
		if (v === null || v === 0) return 0;
		return v >= 1000 ? v - 1000 : v;
	});
	const heaterVoltageLabel = $derived(
		heaterVoltageRaw === null
			? '—'
			: heaterVoltage === 0
				? 'Not set (auto-detect)'
				: `${heaterVoltage} V`
	);

	// ---- Modal controllers ----------------------------------------------
	//
	// One pending value per kind. `pendingVoltage` / `pendingHz` doubles
	// as both "is the modal open?" and "what value did the user pick?".
	let pendingVoltage = $state<120 | 230 | null>(null);
	let pendingHz = $state<50 | 60 | null>(null);

	function openVoltageModal(chosen: 120 | 230): void {
		pendingVoltage = chosen;
	}

	async function onVoltageConfirm(): Promise<void> {
		const chosen = pendingVoltage;
		pendingVoltage = null;
		if (chosen == null || !app) return;
		try {
			await app.setHeaterVoltage(chosen);
		} catch (err) {
			// The core's last-line clamp rejected the value. The modal
			// already pre-validated, so this is defence-in-depth — surface
			// it as a console warning. (A future error-toast layer would
			// catch this too.)
			console.warn('setHeaterVoltage rejected by core:', err);
		}
	}

	function onVoltageCancel(): void {
		pendingVoltage = null;
	}

	function onHzConfirm(): void {
		const hz = pendingHz;
		pendingHz = null;
		if (hz == null) return;
		settings.set('lineFrequencyHz', hz);
		void app?.setLineFrequencyOverride(hz);
	}

	function onHzCancel(): void {
		pendingHz = null;
	}

	// ---- Reset machine settings to factory (MMR baseline) ---------------
	//
	// Mirrors reaprime's DELETE /api/v1/machine/settings/reset — re-applies
	// 8 documented MMR baselines (fan threshold, hot-water idle temp,
	// heater phase 1/2 flows, espresso warmup timeout, refill kit auto
	// mode, flow-calibration multiplier, steam purge mode). Less
	// aggressive UX gate than the heater-voltage modal: blast radius is
	// "user retunes their settings", not "burned heater", so we use a
	// plain `window.confirm` instead of type-to-confirm.

	/** Most-recent inline feedback for the reset action; cleared after a few seconds. */
	let resetFeedback = $state<string | null>(null);
	/** Whether the reset is mid-flight (prevents double-clicks). */
	let resetBusy = $state(false);

	/** Whether the DE1 is connected — disables the reset button otherwise. */
	const connected = $derived(snapshot.de1State === 'ready');

	async function resetMachineSettings(): Promise<void> {
		if (!app || resetBusy) return;
		if (
			!(await confirmDialog({
				message: 'Reset the 8 DE1 machine settings to factory defaults?',
				confirmLabel: 'Reset',
				danger: true
			}))
		) {
			return;
		}
		resetBusy = true;
		resetFeedback = null;
		try {
			await app.resetMachineDefaults();
			resetFeedback = 'Machine settings reset to defaults';
		} catch (err) {
			resetFeedback = `Reset failed: ${err instanceof Error ? err.message : String(err)}`;
			console.warn('resetMachineDefaults failed:', err);
		} finally {
			resetBusy = false;
			// Clear the inline message after ~4 s so it doesn't linger.
			window.setTimeout(() => {
				resetFeedback = null;
			}, 4000);
		}
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

	async function resetPreferences(): Promise<void> {
		if (
			await confirmDialog({
				message:
					'Restore default brew, display, sound and advanced settings? Profiles and shot history are untouched.',
				confirmLabel: 'Restore'
			})
		) {
			settings.reset();
		}
	}

	/**
	 * Factory reset for the **Crema app** — wipes every `crema.*` key from
	 * `localStorage` and reloads. Does NOT touch the DE1: profiles
	 * uploaded to the machine, calibration, etc. stay on the firmware.
	 * Gated behind a `window.confirm` so a stray tap doesn't nuke user
	 * data. Documented in the row's sub-copy.
	 */
	async function factoryReset(): Promise<void> {
		if (typeof window === 'undefined') return;
		const ok = await confirmDialog({
			title: 'Reset the Crema app on this device?',
			message:
				'This permanently erases:\n' +
				'  • custom profiles + library overrides\n' +
				'  • shot history + per-shot captures\n' +
				'  • bean library + roasters\n' +
				'  • all app preferences\n\n' +
				'Your DE1 is NOT affected — uploaded profiles, calibration, and\n' +
				'machine settings stay on the firmware. The page will reload.',
			confirmLabel: 'Reset app',
			danger: true
		});
		if (!ok) return;
		const keys: string[] = [];
		for (let i = 0; i < localStorage.length; i++) {
			const k = localStorage.key(i);
			if (k && k.startsWith('crema.')) keys.push(k);
		}
		for (const k of keys) localStorage.removeItem(k);
		// Hard reload so every $state / module-singleton re-initialises
		// from the now-empty store. `location.reload()` is enough — the
		// PWA service worker will still serve cached assets, which is
		// fine; the data layer is what's being reset.
		window.location.reload();
	}

	// ---- Webhooks (Advanced → Webhooks card) ----------------------------
	//
	// MVP: a single user-supplied URL, five event toggles, and a "Send
	// test" button. The live fire path lives in `CremaApp.fireWebhook`;
	// the test path lives in `CremaApp.sendTestWebhook` (the only path
	// that surfaces errors to the user — live fires are silent and log
	// to the BLE event panel).

	/** True for `https://…` and for `http://localhost…` (dev convenience). */
	const webhookUrlValid = $derived.by(() => {
		const url = prefs.webhookUrl.trim();
		if (url.length === 0) return false;
		if (url.startsWith('https://')) return true;
		// Allow http://localhost / http://127.0.0.1 / http://[::1] for dev.
		if (
			url.startsWith('http://localhost') ||
			url.startsWith('http://127.0.0.1') ||
			url.startsWith('http://[::1]')
		) {
			return true;
		}
		return false;
	});

	/** Inline status for the most-recent "Send test" press; cleared after ~4 s. */
	let webhookTestFeedback = $state<{ ok: boolean; message: string } | null>(null);
	/** Whether a test send is currently in flight (button shows "Sending…"). */
	let webhookTestBusy = $state(false);

	function setWebhookEvent(
		key: keyof typeof prefs.webhookEvents,
		value: boolean
	): void {
		settings.set('webhookEvents', { ...prefs.webhookEvents, [key]: value });
	}

	async function sendTestWebhook(): Promise<void> {
		if (!app || webhookTestBusy) return;
		webhookTestBusy = true;
		webhookTestFeedback = null;
		try {
			webhookTestFeedback = await app.sendTestWebhook();
		} finally {
			webhookTestBusy = false;
			window.setTimeout(() => {
				webhookTestFeedback = null;
			}, 4000);
		}
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
	<!--
		The per-line "show pressure / resistance / flow / volume / head temp
		/ mix temp / weight / weight flow" toggles moved to the Brew page's
		Quick Sheet → Chart channels group on 2026-05-22. Same fields on
		the persisted Settings bundle (`showPressure` etc.), just a more
		discoverable home next to the chart they control.
	-->
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

<StGroup
	title="Webhooks"
	sub="Outbound POSTs to your URL when these events fire. Requires CORS to be configured on your endpoint."
>
	<StRow title="Enable webhooks" sub="Master switch. Off by default.">
		{#snippet control()}
			<StToggle
				on={prefs.webhookEnabled}
				onChange={(v) => settings.set('webhookEnabled', v)}
				label="Enable webhooks"
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Destination URL"
		sub="POST target. Must be https:// (http://localhost is allowed for development testing)."
	>
		{#snippet control()}
			<input
				type="url"
				class="wh-url"
				class:wh-url-invalid={prefs.webhookUrl.trim().length > 0 && !webhookUrlValid}
				placeholder="https://example.com/crema-hook"
				value={prefs.webhookUrl}
				disabled={!prefs.webhookEnabled}
				oninput={(e) =>
					settings.set('webhookUrl', (e.currentTarget as HTMLInputElement).value)}
			/>
		{/snippet}
		{#snippet hint()}
			{#if prefs.webhookUrl.trim().length > 0 && !webhookUrlValid}
				<span class="wh-hint-err">URL must start with https:// (or http://localhost for dev).</span>
			{/if}
		{/snippet}
	</StRow>
	<StRow title="Shot completed" sub="Fires after each shot ends.">
		{#snippet control()}
			<StToggle
				on={prefs.webhookEvents.shotCompleted}
				onChange={(v) => setWebhookEvent('shotCompleted', v)}
				disabled={!prefs.webhookEnabled}
				label="Shot completed"
			/>
		{/snippet}
	</StRow>
	<StRow title="Machine error" sub="Fires when the DE1 enters an error state.">
		{#snippet control()}
			<StToggle
				on={prefs.webhookEvents.machineError}
				onChange={(v) => setWebhookEvent('machineError', v)}
				disabled={!prefs.webhookEnabled}
				label="Machine error"
			/>
		{/snippet}
	</StRow>
	<StRow title="Profile uploaded" sub="Fires after a profile upload completes successfully.">
		{#snippet control()}
			<StToggle
				on={prefs.webhookEvents.profileUploaded}
				onChange={(v) => setWebhookEvent('profileUploaded', v)}
				disabled={!prefs.webhookEnabled}
				label="Profile uploaded"
			/>
		{/snippet}
	</StRow>
	<StRow title="Scale connected" sub="Fires the first time the scale reaches ready.">
		{#snippet control()}
			<StToggle
				on={prefs.webhookEvents.scaleConnected}
				onChange={(v) => setWebhookEvent('scaleConnected', v)}
				disabled={!prefs.webhookEnabled}
				label="Scale connected"
			/>
		{/snippet}
	</StRow>
	<StRow title="DE1 connected" sub="Fires the first time the machine reaches ready.">
		{#snippet control()}
			<StToggle
				on={prefs.webhookEvents.de1Connected}
				onChange={(v) => setWebhookEvent('de1Connected', v)}
				disabled={!prefs.webhookEnabled}
				label="DE1 connected"
			/>
		{/snippet}
	</StRow>
	<StRow title="Send test" sub="POST a test payload to the URL above and surface the result.">
		{#snippet control()}
			<StButton
				label={webhookTestBusy ? 'Sending…' : 'Send test'}
				icon="paper-plane-tilt"
				disabled={!app || !prefs.webhookEnabled || !webhookUrlValid || webhookTestBusy}
				onClick={sendTestWebhook}
			/>
		{/snippet}
		{#snippet hint()}
			{#if webhookTestFeedback}
				<span
					class="wh-test-feedback"
					class:wh-test-ok={webhookTestFeedback.ok}
					class:wh-test-err={!webhookTestFeedback.ok}
				>
					{webhookTestFeedback.ok ? '✓' : '✗'}
					{webhookTestFeedback.ok ? 'Sent' : 'Failed'}: {webhookTestFeedback.message}
				</span>
			{/if}
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
			{/if}
		{/snippet}
	</StRow>
</StGroup>

<!--
	Service-grade settings — destructive or hardware-affecting controls
	that need a type-to-confirm gate. Visually quieter than the everyday
	settings above, but the controls themselves are *not* placeholders.
-->
<StGroup title="Service-grade settings — change only if you know what you're doing">
	<StRow
		title="Mains heater voltage"
		needsConnection={!connected}
		sub="Tells the DE1 whether you have 120 V or 230 V wiring. Wrong setting on the wrong outlet can permanently damage the heater — confirmation is required."
	>
		{#snippet control()}
			<div class="adv-voltage-control">
				<span class="adv-voltage-current">{heaterVoltageLabel}</span>
				<StButton
					label="Set to 120 V"
					icon="lightning"
					disabled={!connected || heaterVoltage === 120}
					onClick={() => openVoltageModal(120)}
				/>
				<StButton
					label="Set to 230 V"
					icon="lightning"
					disabled={!connected || heaterVoltage === 230}
					onClick={() => openVoltageModal(230)}
				/>
			</div>
		{/snippet}
	</StRow>
	<StRow
		title="Reset machine settings to factory"
		needsConnection={!connected}
		sub="Re-applies fan threshold, idle temp, heater flows, refill kit auto mode, flow estimate, steam purge. Does NOT touch profiles, history, or app preferences."
	>
		{#snippet control()}
			<StButton
				label={resetBusy ? 'Resetting…' : 'Reset…'}
				icon="arrow-counter-clockwise"
				disabled={!connected || resetBusy}
				onClick={resetMachineSettings}
			/>
		{/snippet}
		{#snippet hint()}
			{#if resetFeedback}
				<span class="adv-reset-feedback">{resetFeedback}</span>
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
		sub="Resets the Crema app on this device — profiles, history, settings, beans. Does NOT change DE1 machine settings (those stay on the firmware)."
	>
		{#snippet control()}
			<button type="button" class="st-btn st-btn-danger" onclick={factoryReset}>
				<i class="ph ph-warning" aria-hidden="true"></i>Factory reset
			</button>
		{/snippet}
	</StRow>
</StGroup>

{#if pendingVoltage != null}
	<MainsConfirmModal
		kind="voltage"
		chosen={pendingVoltage}
		current={heaterVoltage}
		onConfirm={onVoltageConfirm}
		onCancel={onVoltageCancel}
	/>
{/if}

{#if pendingHz != null}
	<MainsConfirmModal
		kind="hz"
		chosen={pendingHz}
		current={prefs.lineFrequencyHz}
		onConfirm={onHzConfirm}
		onCancel={onHzCancel}
	/>
{/if}

<style>
	/* ── Capture-replay developer control ──────────────────────────────────
	   The file picker is a native <input type="file"> hidden inside a <label>
	   styled as an StButton, so it matches the settings kit; the visible
	   chrome is the label. */
	.rp-control {
		display: flex;
		align-items: center;
		justify-content: flex-end;
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

	/* ── Service-grade voltage control ────────────────────────────────────
	   Shows the current voltage label inline with the two "Set to N V"
	   buttons. The buttons are not separated by a divider; the disabled
	   state (when the firmware already holds the value) acts as the
	   visual "current" indicator alongside the mono label. */
	.adv-voltage-control {
		display: flex;
		align-items: center;
		gap: 12px;
		flex-wrap: wrap;
		justify-content: flex-end;
	}
	.adv-voltage-current {
		font-family: var(--font-mono);
		font-size: 13px;
		font-variant-numeric: tabular-nums;
		color: rgba(var(--tint-rgb), 0.7);
	}

	/* ── Reset-feedback inline note ───────────────────────────────────────
	   Ephemeral status line shown under "Reset machine settings to factory"
	   for a few seconds after the action — green for success, the same
	   muted tone as the rest of the row otherwise. */
	.adv-reset-feedback {
		color: var(--success);
		font-variant-numeric: tabular-nums;
	}

	/* ── Webhooks URL input + test feedback ───────────────────────────────
	   Plain `<input type="url">` styled to match the rest of the settings
	   kit (the kit has no text-input precedent yet). Sits in the StRow's
	   right slot, sized for a comfortable URL paste. */
	.wh-url {
		width: 280px;
		max-width: 100%;
		padding: 6px 10px;
		font-family: var(--font-mono);
		font-size: 12px;
		color: var(--fg-1);
		background: rgba(var(--tint-rgb), 0.06);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 6px;
		outline: none;
	}
	.wh-url:focus {
		border-color: rgba(var(--tint-rgb), 0.4);
	}
	.wh-url:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
	.wh-url-invalid {
		border-color: var(--danger);
	}
	.wh-hint-err {
		color: var(--danger);
	}
	.wh-test-feedback {
		font-variant-numeric: tabular-nums;
	}
	.wh-test-ok {
		color: var(--success);
	}
	.wh-test-err {
		color: var(--danger);
	}
</style>
