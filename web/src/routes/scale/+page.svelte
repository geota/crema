<script lang="ts">
	/**
	 * Scale — the `/scale` route: the calm, focused weighing view, ported from
	 * `ScalePage` in `scale-page.jsx`.
	 *
	 * Header (scale name / status / battery / firmware + Re-pair), the giant
	 * live weight hero (flashes copper on tare), Tare + Reset-peak / Start-timer
	 * buttons, a dose-helper progress track, a Quick-settings block and a
	 * recent-activity log.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the scale is well-supported by the core, so this page is wired
	 * for real. The weight hero, battery, firmware, serial, name and connection
	 * status all read `lib/state`'s `UiSnapshot`. Tare calls `app.tareScale()`;
	 * Re-pair calls `app.connectScale()`. The Quick-settings controls that map
	 * onto real scale capabilities — flow-smoothing, auto-sleep (↔ standby),
	 * volume, anti-mistouch, mode, auto-stop — drive `CremaApp`'s two-way config
	 * setters, gated on `scaleCapabilities`. The recent-activity log is the
	 * shared `UiSnapshot.eventLog`.
	 *
	 * **UI-only** — Reset-peak and Start-timer have no core backing; the design's
	 * dose target and the brew-behaviour toggles (auto-tare / stop-on-weight)
	 * are local UI state. Each is marked with a `// TODO`.
	 */
	import { getCremaAppContext } from '$lib/shell/app-context';
	import { getProfileStore } from '$lib/profiles';

	const ctx = getCremaAppContext();
	const profiles = getProfileStore();

	/** The shared orchestrator, or `null` while the wasm core loads. */
	const app = $derived(ctx().app);
	/** The reactive UI snapshot — the screen renders off this. */
	const snap = $derived(app?.state.current ?? null);

	/** The scale's coarse connection state. */
	const scaleState = $derived(snap?.scaleState ?? 'idle');
	/** Whether a scale link is up (subscribed and streaming). */
	const connected = $derived(scaleState === 'ready');
	/** The connected scale's advertised name, or a placeholder. */
	const scaleName = $derived(snap?.scaleName ?? (connected ? 'Scale' : 'No scale'));
	/** The latest scale weight, grams, or `null` before the first reading. */
	const weightG = $derived(snap?.scaleWeightG ?? null);
	/** The scale's battery charge, %, or `null`. */
	const battery = $derived(snap?.scaleBatteryPercent ?? null);
	/** The scale's firmware version string, or `null`. */
	const firmware = $derived(snap?.scaleFirmware ?? null);
	/** The scale's serial number, or `null`. */
	const serial = $derived(snap?.scaleSerial ?? null);
	/** What the connected scale can do — gates the config controls. */
	const caps = $derived(snap?.scaleCapabilities ?? null);
	/** The shared event log — the recent-activity feed. */
	const eventLog = $derived(snap?.eventLog ?? []);

	/** A human label for the coarse connection state. */
	const statusLabel = $derived(
		(
			{
				idle: 'Not connected',
				connecting: 'Connecting…',
				subscribing: 'Subscribing…',
				ready: 'Connected',
				reconnecting: 'Reconnecting…',
				disconnected: 'Disconnected',
				failed: 'Connection failed'
			} as const
		)[scaleState]
	);

	// ── Tare-flash ───────────────────────────────────────────────────────
	/** When true, the hero readout flashes copper (a 600 ms pulse after a tare). */
	let tarePulse = $state(false);
	let pulseTimer: ReturnType<typeof setTimeout> | undefined;

	/** Tare the connected scale and flash the hero readout. */
	function tare(): void {
		if (!app) return;
		void app.tareScale();
		tarePulse = true;
		clearTimeout(pulseTimer);
		pulseTimer = setTimeout(() => (tarePulse = false), 600);
	}

	// Cancel a pending tare-flash timeout if the component is torn down before
	// it fires — otherwise the callback runs against a destroyed component.
	$effect(() => () => clearTimeout(pulseTimer));

	/** Connect / re-pair a scale — a Web-Bluetooth gesture handler. */
	function repair(): void {
		void app?.connectScale();
	}

	// ── Dose helper ──────────────────────────────────────────────────────
	// The active profile's dose is the natural target. The shell's CremaProfile
	// carries a `dose` (grams); when no profile is active a sensible 18 g stands
	// in. The dose target itself is not a scale capability — it is profile data.
	const activeProfile = $derived(
		profiles.activeId ? profiles.get(profiles.activeId) : undefined
	);
	/** The dose target the helper aims at, grams. */
	const targetDose = $derived(activeProfile?.dose ?? 18.0);
	/** The active profile's name, for the dose-helper caption. */
	const targetProfileName = $derived(activeProfile?.name ?? snap?.activeProfileName ?? null);

	/** Signed difference between the live weight and the dose target, grams. */
	const delta = $derived((weightG ?? 0) - targetDose);
	/** Whether the live weight is within 0.1 g of the target. */
	const within = $derived(weightG != null && Math.abs(delta) < 0.1);
	/** Whether the live weight is wildly off the target. */
	const tooFar = $derived(weightG != null && (delta > 0.5 || delta < -2));
	/** The dose-track fill width, 0–100 %. */
	const fillPct = $derived(
		targetDose > 0 ? Math.min(100, Math.max(0, ((weightG ?? 0) / targetDose) * 100)) : 0
	);

	// ── Quick settings ───────────────────────────────────────────────────
	/** Flow-smoothing — real scale capability, two-way through `CremaApp`. */
	const flowSmoothing = $derived(snap?.scaleFlowSmoothing ?? false);
	/** Anti-mistouch — real scale capability, two-way. */
	const antiMistouch = $derived(snap?.scaleAntiMistouch ?? false);
	/** The scale's auto-standby timeout, minutes — real capability. */
	const standbyMinutes = $derived(snap?.scaleStandbyMinutes ?? 0);
	/** Whether auto-standby is "on" — i.e. a non-zero timeout. */
	const autoSleepOn = $derived(standbyMinutes > 0);

	// TODO: brew-behaviour toggles below have no scale-capability backing — the
	// DE1 owns auto-tare-on-shot-start and stop-on-weight (they live on the
	// profile model). They stay UI-only local state, clearly secondary.
	let autoTareLocal = $state(true);
	let stopOnWeightLocal = $state(true);

	/** Toggle flow-smoothing on the scale (optimistic, then stream-confirmed). */
	function toggleFlowSmoothing(): void {
		if (caps?.flow_smoothing) app?.setScaleFlowSmoothing(!flowSmoothing);
	}

	/** Toggle anti-mistouch on the scale. */
	function toggleAntiMistouch(): void {
		if (caps?.anti_mistouch) app?.setScaleAntiMistouch(!antiMistouch);
	}

	/**
	 * Toggle auto-sleep. With no dedicated on/off flag, this maps to the
	 * standby timeout: off → 0 min, on → the capability's default (5 min,
	 * clamped to the scale's range). Two-way through `CremaApp`.
	 */
	function toggleAutoSleep(): void {
		if (!app || !caps?.standby_minutes) return;
		const range = caps.standby_minutes;
		app.setScaleStandbyMinutes(autoSleepOn ? range.min : Math.max(range.min, Math.min(5, range.max)));
	}

	/** Set the scale's display/behaviour mode by wire id. */
	function setMode(id: number): void {
		app?.setScaleMode(id);
	}

	// ── Reset-peak / Start-timer — UI-only ───────────────────────────────
	// TODO: no core backing — the core exposes no scale peak-reset or
	// built-in-timer start command. Local UI state only.
	let timerRunning = $state(false);
	function resetPeak(): void {
		// TODO: wire to a scale peak-reset command when the core exposes one.
	}
	function toggleTimer(): void {
		// TODO: wire to the scale's built-in timer when the core exposes a
		// start/stop command (the scale already *reports* `device_timer_ms`).
		timerRunning = !timerRunning;
	}

	/** Format the hero readout — one decimal, leading sign for negatives. */
	const heroSign = $derived(weightG != null && weightG < 0 ? '-' : '');
	const heroNum = $derived(weightG != null ? Math.abs(weightG).toFixed(1) : '–');

	// ── Activity log ─────────────────────────────────────────────────────
	/** One parsed activity-log row. */
	interface ActivityRow {
		/** The `HH:MM:SS` timestamp slice. */
		time: string;
		/** The event message. */
		detail: string;
		/** A milestone — shot start/complete, connect — drawn brighter. */
		highlight: boolean;
		/** Routine state-transition noise — drawn dimmer. */
		muted: boolean;
	}

	/**
	 * Parse the shared `eventLog` (`"HH:MM:SS  <message>"`, newest-first) into
	 * rows. The log strings are free-form, so there is no reliable categorical
	 * event keyword to lift into a separate uppercase column — instead each row
	 * is tagged `highlight` (a milestone) or `muted` (routine state noise) so
	 * the feed reads at a glance without inventing structure the data lacks.
	 */
	const activityRows = $derived.by<ActivityRow[]>(() =>
		eventLog.slice(0, 14).map((line) => {
			const sp = line.indexOf('  ');
			const time = sp > 0 ? line.slice(0, sp) : '';
			const detail = sp > 0 ? line.slice(sp + 2) : line;
			const d = detail.toLowerCase();
			const highlight =
				d.includes('shot started') ||
				d.includes('shot complete') ||
				d.includes('auto-stop') ||
				d.includes('steam session');
			const muted =
				d.startsWith('machinestate ->') ||
				d.startsWith('shot phase ->') ||
				d.startsWith('shot frame ->') ||
				d.includes('scale stale');
			return { time, detail, highlight, muted };
		})
	);
</script>

<svelte:head>
	<title>Crema — Scale</title>
</svelte:head>

<div class="qcontain sc-page">
	<!-- Header strip -->
	<div class="sc-head">
		<div>
			<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Connected scale</div>
			<div class="sc-head-name">{scaleName}</div>
			<div class="sc-head-meta">
				<span
					class="sc-dot"
					style="background:{connected ? '#6B8C5F' : 'rgba(244,237,224,0.25)'}"
				></span>
				<span>{statusLabel}</span>
				{#if battery != null}
					<span class="sc-sep">·</span>
					<i class="ph ph-battery-high" aria-hidden="true"></i>
					<span>{Math.round(battery)}%</span>
				{/if}
				{#if firmware}
					<span class="sc-sep">·</span>
					<span>Firmware {firmware}</span>
				{/if}
				{#if serial}
					<span class="sc-sep">·</span>
					<span>SN {serial}</span>
				{/if}
			</div>
		</div>
		<div class="sc-head-r">
			<button class="st-btn st-btn-secondary" onclick={repair} disabled={!app}>
				<i class="ph ph-arrows-clockwise" aria-hidden="true"></i>
				{connected ? 'Re-pair' : 'Connect'}
			</button>
		</div>
	</div>

	<!-- Live readout hero -->
	<div class="sc-hero">
		<div class="sc-readout">
			<div class="sc-readout-num" class:is-tare={tarePulse}>
				<span class="sc-sign">{heroSign}</span>
				<span>{heroNum}</span>
				<span class="sc-unit">g</span>
			</div>
			<div class="sc-readout-sub">
				{connected ? 'live' : 'no scale connected'}
			</div>
		</div>

		<div class="sc-actions">
			<button class="sc-tare" onclick={tare} disabled={!connected}>
				<span class="sc-tare-label">Tare</span>
				<span class="sc-tare-num">0.0<em>g</em></span>
			</button>
			<button class="sc-secondary" onclick={resetPeak}>
				<i class="ph ph-arrow-clockwise" aria-hidden="true"></i>
				<span>Reset peak</span>
			</button>
			<button class="sc-secondary" onclick={toggleTimer}>
				<i class="ph ph-timer" aria-hidden="true"></i>
				<span>{timerRunning ? 'Stop timer' : 'Start timer'}</span>
			</button>
		</div>
	</div>

	<!-- Dose helper -->
	<div class="sc-dose">
		<div class="sc-dose-head">
			<div>
				<div class="t-eyebrow" style="color:rgba(244,237,224,0.55)">Dose helper</div>
				<div class="sc-dose-title">
					{#if targetProfileName}
						Weighing for <strong>{targetProfileName}</strong> · target {targetDose.toFixed(1)} g
					{:else}
						Target {targetDose.toFixed(1)} g
					{/if}
				</div>
			</div>
		</div>
		<div class="sc-dose-track">
			<div class="sc-dose-fill" style="width:{fillPct}%"></div>
			<div class="sc-dose-target"></div>
			<div class="sc-dose-target-label">target</div>
		</div>
		<div class="sc-dose-status">
			{#if weightG == null}
				<span class="sc-dose-warn">Connect a scale to weigh your dose</span>
			{:else if within}
				<span class="sc-dose-ok">
					<i class="ph-fill ph-check-circle" aria-hidden="true"></i>
					On target — {weightG.toFixed(1)} g
				</span>
			{:else if tooFar}
				<span class="sc-dose-bad">
					Way off · {delta > 0 ? `+${delta.toFixed(1)}` : delta.toFixed(1)} g
				</span>
			{:else if delta < 0}
				<span class="sc-dose-warn">Add {Math.abs(delta).toFixed(1)} g</span>
			{:else}
				<span class="sc-dose-warn">Remove {delta.toFixed(1)} g</span>
			{/if}
		</div>
	</div>

	<!-- Settings + Recent activity -->
	<div class="sc-bottom">
		<div class="sc-settings">
			<div class="sc-settings-head">Quick settings</div>

			<!-- Filter strength → flow-smoothing (real capability) -->
			<div class="sc-set-row">
				<div>
					<div class="sc-set-title">Flow smoothing</div>
					<div class="sc-set-sub">
						Smooths the scale's live mass-flow readout. Calmer, slower to settle.
					</div>
				</div>
				<button
					class="qmini-tog"
					class:on={flowSmoothing}
					onclick={toggleFlowSmoothing}
					disabled={!caps?.flow_smoothing}
					aria-label="Flow smoothing"
				></button>
			</div>

			<!-- Anti-mistouch (real capability) -->
			<div class="sc-set-row">
				<div>
					<div class="sc-set-title">Anti-mistouch</div>
					<div class="sc-set-sub">Ignore accidental taps on the scale's buttons.</div>
				</div>
				<button
					class="qmini-tog"
					class:on={antiMistouch}
					onclick={toggleAntiMistouch}
					disabled={!caps?.anti_mistouch}
					aria-label="Anti-mistouch"
				></button>
			</div>

			<!-- Auto-sleep ↔ standby timeout (real capability) -->
			<div class="sc-set-row">
				<div>
					<div class="sc-set-title">Auto-sleep</div>
					<div class="sc-set-sub">
						{#if autoSleepOn}
							Sleeps after {standbyMinutes} min of no activity.
						{:else}
							Put the scale to sleep after a few minutes of no activity.
						{/if}
					</div>
				</div>
				<button
					class="qmini-tog"
					class:on={autoSleepOn}
					onclick={toggleAutoSleep}
					disabled={!caps?.standby_minutes}
					aria-label="Auto-sleep"
				></button>
			</div>

			<!-- Display mode (real capability — when the scale exposes modes) -->
			{#if caps && caps.modes.length > 0}
				<div class="sc-set-row">
					<div>
						<div class="sc-set-title">Display mode</div>
						<div class="sc-set-sub">Switches the scale's on-device display / behaviour.</div>
					</div>
					<div class="st-segment">
						{#each caps.modes as m (m.id)}
							<button
								class:is-active={snap?.scaleActiveMode === m.id}
								onclick={() => setMode(m.id)}>{m.name}</button
							>
						{/each}
					</div>
				</div>
			{/if}

			<!-- Brew-behaviour toggles — UI-only (no scale-capability backing) -->
			<div class="sc-set-row">
				<div>
					<div class="sc-set-title">Auto-tare on shot start</div>
					<div class="sc-set-sub">
						Zero the scale when extraction begins. (App preference — not a scale setting.)
					</div>
				</div>
				<button
					class="qmini-tog"
					class:on={autoTareLocal}
					onclick={() => (autoTareLocal = !autoTareLocal)}
					aria-label="Auto-tare on shot start"
				></button>
			</div>
			<div class="sc-set-row">
				<div>
					<div class="sc-set-title">Stop-on-weight</div>
					<div class="sc-set-sub">
						End the shot at the profile's target yield. (App preference — not a scale setting.)
					</div>
				</div>
				<button
					class="qmini-tog"
					class:on={stopOnWeightLocal}
					onclick={() => (stopOnWeightLocal = !stopOnWeightLocal)}
					aria-label="Stop-on-weight"
				></button>
			</div>
		</div>

		<div class="sc-activity">
			<div class="sc-activity-head">Recent activity</div>
			{#if activityRows.length === 0}
				<div class="sc-activity-empty">No activity yet — connect a scale to begin.</div>
			{:else}
				{#each activityRows as row, i (i)}
					<div class="sc-arow" class:is-hl={row.highlight} class:is-muted={row.muted}>
						<div class="sc-arow-t">{row.time}</div>
						<div class="sc-arow-detail">{row.detail}</div>
					</div>
				{/each}
			{/if}
		</div>
	</div>
</div>

<style>
	.sc-page {
		background: var(--espresso-950);
		color: var(--ink-50);
		display: flex;
		flex-direction: column;
		min-height: 100vh;
		overflow-y: auto;
		/* Shared page-header rhythm — see --page-pad-* in app.css. */
		padding: var(--page-pad-top) var(--page-pad-x) 40px;
		gap: 24px;
	}

	/* Header */
	.sc-head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 20px;
	}
	.sc-head-name {
		font-family: var(--font-serif);
		font-size: 28px;
		letter-spacing: -0.015em;
		color: var(--ink-50);
		margin-top: 2px;
	}
	.sc-head-meta {
		display: flex;
		align-items: center;
		gap: 10px;
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(244, 237, 224, 0.55);
		margin-top: 6px;
	}
	.sc-head-meta i {
		font-size: 14px;
	}
	.sc-sep {
		color: rgba(244, 237, 224, 0.2);
	}
	.sc-head-r {
		display: flex;
		gap: 8px;
	}
	.sc-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		display: inline-block;
		box-shadow: 0 0 0 2.5px rgba(107, 140, 95, 0.2);
	}

	/* st-segment ported from the design's settings kit. The .st-btn family
	   is shared globally — see settings-page.css. */
	.st-segment {
		display: inline-flex;
		background: rgba(244, 237, 224, 0.04);
		border-radius: var(--radius-pill);
		padding: 3px;
	}
	.st-segment > button {
		background: transparent;
		border: 0;
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		color: rgba(244, 237, 224, 0.55);
		padding: 5px 12px;
		border-radius: 999px;
		cursor: pointer;
		transition: all var(--dur-1) var(--ease);
	}
	.st-segment > button.is-active {
		background: var(--copper-500);
		color: #1a120c;
	}

	/* Live readout hero */
	.sc-hero {
		display: grid;
		grid-template-columns: 1fr 320px;
		gap: 28px;
		align-items: stretch;
	}
	.sc-readout {
		display: flex;
		flex-direction: column;
		justify-content: center;
		align-items: flex-start;
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.06);
		border-radius: var(--radius-lg, 14px);
		padding: 36px 44px;
		position: relative;
		overflow: hidden;
	}
	.sc-readout-num {
		display: flex;
		align-items: baseline;
		gap: 8px;
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 132px;
		font-weight: 300;
		color: var(--ink-50);
		letter-spacing: -0.04em;
		line-height: 1;
		transition: color 240ms var(--ease);
	}
	.sc-readout-num.is-tare {
		color: var(--copper-400);
	}
	.sc-sign {
		font-size: 80px;
		opacity: 0.6;
		margin-right: -8px;
	}
	.sc-readout-num .sc-unit {
		font-size: 32px;
		font-weight: 400;
		color: rgba(244, 237, 224, 0.4);
		letter-spacing: 0;
	}
	.sc-readout-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 500;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(244, 237, 224, 0.4);
		margin-top: 18px;
	}

	.sc-actions {
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.sc-tare {
		display: flex;
		flex-direction: column;
		align-items: flex-start;
		gap: 6px;
		flex: 1 1 auto;
		background: var(--copper-500);
		border: 0;
		border-radius: var(--radius-md);
		color: #1a120c;
		cursor: pointer;
		padding: 24px 28px;
		transition:
			background var(--dur-1) var(--ease),
			transform 80ms var(--ease);
		text-align: left;
	}
	.sc-tare:hover:not(:disabled) {
		background: var(--copper-600);
	}
	.sc-tare:active:not(:disabled) {
		transform: scale(0.99);
	}
	.sc-tare:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}
	.sc-tare-label {
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 700;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
	}
	.sc-tare-num {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 44px;
		font-weight: 400;
		line-height: 1;
		margin-top: 4px;
	}
	.sc-tare-num em {
		font-style: normal;
		font-size: 18px;
		opacity: 0.6;
		margin-left: 3px;
	}
	.sc-secondary {
		display: inline-flex;
		align-items: center;
		justify-content: flex-start;
		gap: 8px;
		background: rgba(244, 237, 224, 0.04);
		border: 1px solid rgba(244, 237, 224, 0.08);
		border-radius: var(--radius-sm);
		color: var(--ink-50);
		cursor: pointer;
		padding: 12px 16px;
		font-family: var(--font-sans);
		font-size: 13px;
	}
	.sc-secondary:hover {
		background: rgba(244, 237, 224, 0.08);
	}
	.sc-secondary i {
		font-size: 14px;
	}

	/* Dose helper */
	.sc-dose {
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.06);
		border-radius: var(--radius-lg, 14px);
		padding: 22px 24px 24px;
	}
	.sc-dose-head {
		display: flex;
		align-items: flex-end;
		justify-content: space-between;
		gap: 16px;
		margin-bottom: 16px;
	}
	.sc-dose-title {
		font-family: var(--font-sans);
		font-size: 14px;
		color: var(--ink-50);
		margin-top: 2px;
	}
	.sc-dose-title strong {
		font-weight: 600;
	}
	.sc-dose-track {
		position: relative;
		height: 10px;
		background: rgba(244, 237, 224, 0.05);
		border-radius: 999px;
		overflow: visible;
	}
	.sc-dose-fill {
		position: absolute;
		left: 0;
		top: 0;
		bottom: 0;
		background: var(--copper-500);
		border-radius: 999px;
		transition: width 240ms var(--ease);
	}
	.sc-dose-target {
		position: absolute;
		left: 100%;
		top: -2px;
		bottom: -2px;
		width: 2px;
		background: var(--ink-50);
		transform: translateX(-1px);
	}
	.sc-dose-target-label {
		position: absolute;
		right: 0;
		top: 16px;
		font-family: var(--font-sans);
		font-size: 10px;
		letter-spacing: var(--track-allcaps);
		text-transform: uppercase;
		color: rgba(244, 237, 224, 0.5);
		font-weight: 600;
	}
	.sc-dose-status {
		margin-top: 24px;
		font-family: var(--font-sans);
		font-size: 14px;
	}
	.sc-dose-ok {
		color: #6b8c5f;
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.sc-dose-ok i {
		font-size: 16px;
	}
	.sc-dose-warn {
		color: var(--copper-400);
	}
	.sc-dose-bad {
		color: #d28a4a;
	}

	/* Bottom split */
	.sc-bottom {
		display: grid;
		grid-template-columns: 1.2fr 1fr;
		gap: 18px;
	}
	.sc-settings,
	.sc-activity {
		background: var(--espresso-900);
		border: 1px solid rgba(244, 237, 224, 0.06);
		border-radius: var(--radius-lg, 14px);
		padding: 20px 24px;
	}
	.sc-settings-head,
	.sc-activity-head {
		font-family: var(--font-serif);
		font-size: 18px;
		letter-spacing: -0.01em;
		color: var(--ink-50);
		margin-bottom: 12px;
		padding-bottom: 12px;
		border-bottom: 1px solid rgba(244, 237, 224, 0.05);
	}
	.sc-set-row {
		display: grid;
		grid-template-columns: 1fr auto;
		gap: 18px;
		align-items: center;
		padding: 12px 0;
		border-bottom: 1px solid rgba(244, 237, 224, 0.04);
	}
	.sc-set-row:last-child {
		border-bottom: 0;
	}
	.sc-set-title {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--ink-50);
		font-weight: 500;
	}
	.sc-set-sub {
		font-family: var(--font-sans);
		font-size: 11px;
		line-height: 1.5;
		color: rgba(244, 237, 224, 0.5);
		margin-top: 2px;
	}
	.qmini-tog:disabled {
		opacity: 0.35;
		cursor: not-allowed;
	}

	.sc-arow {
		display: grid;
		grid-template-columns: 64px 1fr;
		gap: 12px;
		align-items: baseline;
		padding: 8px 0 8px 10px;
		border-bottom: 1px solid rgba(244, 237, 224, 0.04);
		/* A 2px rail on the row's leading edge — neutral by default; a
		   milestone row lights it copper, routine noise leaves it transparent. */
		border-left: 2px solid transparent;
		transition: border-color var(--dur-1) var(--ease);
	}
	.sc-arow:last-child {
		border-bottom: 0;
	}
	.sc-arow-t {
		font-family: var(--font-mono);
		font-variant-numeric: tabular-nums;
		font-size: 11px;
		color: rgba(244, 237, 224, 0.4);
	}
	.sc-arow-detail {
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--ink-50);
		word-break: break-word;
	}
	/* Milestone rows — shot start/complete, auto-stop, steam — read brighter
	   with a copper rail so the eye lands on them first. */
	.sc-arow.is-hl {
		border-left-color: var(--copper-500);
	}
	.sc-arow.is-hl .sc-arow-detail {
		color: var(--copper-400);
		font-weight: 500;
	}
	/* Routine state-transition noise recedes — dim text, no rail. */
	.sc-arow.is-muted .sc-arow-detail {
		color: rgba(244, 237, 224, 0.45);
	}
	.sc-arow.is-muted .sc-arow-t {
		color: rgba(244, 237, 224, 0.28);
	}
	.sc-activity-empty {
		padding: 20px 0;
		font-family: var(--font-sans);
		font-size: 12px;
		color: rgba(244, 237, 224, 0.4);
	}

	@media (max-width: 900px) {
		.sc-hero,
		.sc-bottom {
			grid-template-columns: minmax(0, 1fr);
		}
		.sc-readout-num {
			font-size: 96px;
		}
	}
</style>
