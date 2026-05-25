<script lang="ts">
	/**
	 * Calibration section — read + write of the DE1's sensor calibration.
	 *
	 * Three rows, one shared layout: Temperature, Pressure, and Flow. Each
	 * renders through {@link CalibrationRow}, which lays out a 3-column
	 * Current / Factory / New value strip plus Apply + Reset-to-factory
	 * buttons. The visual style is identical across all three so the screen
	 * reads as one block, not three bespoke widgets.
	 *
	 * Temperature + Pressure go through `cuuid_12 / Calibration`: a paired
	 * reported / measured write the DE1 turns into a per-sensor multiplier.
	 * Their "New value" cell is intentionally **disabled** in the row — the
	 * single-number stepper is the wrong surface for that paired workflow.
	 * Apply opens a modal that collects Reported + Measured (in the user's
	 * display unit); the read + factory columns + Reset-to-factory all work
	 * as before. TODO: inline the reported/measured pair into the row so the
	 * stepper can carry its own weight.
	 *
	 * Flow calibration lives in a separate MMR register
	 * (`CalibrationFlowMultiplier`, `0x80383C`): a single number bounded
	 * `0.13..=2.0`, default `1.000`. The row's "New value" stepper is the
	 * commit surface; Apply opens a confirm modal that previews the new
	 * value; Reset writes `1.000` (the firmware has no per-register factory-
	 * reset wire packet for the MMR side — see docs/43 §"Reset to factory").
	 *
	 * Reads on mount fire temperature + pressure (current + factory) **and**
	 * the flow MMR. They're non-fatal: a disconnect mid-mount just leaves the
	 * row's value as `—`.
	 *
	 * Legacy reference: `gui.tcl:2445-2452` — the legacy calibration screen
	 * reads temperature + pressure current/factory on mount (flow reads are
	 * commented out; flow uses a separate MMR-based multiplier).
	 *
	 * Unit-awareness: the codec works in canonical units (°C / bar / ml·s⁻¹)
	 * — the Apply modal renders its inputs in the user's display unit and
	 * converts back at the I/O boundary, so a °F user types °F. Flow is
	 * unitless (a multiplier) so its input is plain.
	 */
	import { onMount } from 'svelte';
	import type { CremaApp } from '$lib/state';
	import { CalTarget, MmrRegister } from '$lib/core';
	import { INITIAL_SNAPSHOT } from '$lib/state';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import CalibrationRow from '../CalibrationRow.svelte';
	import {
		getSettingsStore,
		unitLabel,
		canonicalToDisplay,
		displayToCanonical,
		displayDecimals,
		type Dimension
	} from '$lib/settings';

	const settings = getSettingsStore();
	const prefs = $derived(settings.current);

	let { app }: { app: CremaApp | null } = $props();

	/** The live UI snapshot — supplies the calibration values once read. */
	const snapshot = $derived(app?.state.current ?? INITIAL_SNAPSHOT);
	const cal = $derived(snapshot.de1Calibration);
	const connected = $derived(snapshot.de1State === 'ready');

	let lastRefreshAt = $state<number | null>(null);
	async function refresh(): Promise<void> {
		if (!app || !connected) return;
		try {
			await app.readCalibration(CalTarget.Temperature, false);
			await app.readCalibration(CalTarget.Temperature, true);
			await app.readCalibration(CalTarget.Pressure, false);
			await app.readCalibration(CalTarget.Pressure, true);
			await app.readMmr(MmrRegister.CalibrationFlowMultiplier);
			lastRefreshAt = Date.now();
		} catch {
			// Ignored: the orchestrator already logged via the event stream.
		}
	}

	onMount(() => {
		void refresh();
	});

	/** Format the "last read" timestamp in HH:MM:SS local time. */
	const lastRefreshedLabel = $derived(
		lastRefreshAt == null ? 'reading…' : new Date(lastRefreshAt).toLocaleTimeString()
	);

	/** Which dimension a calibration target lives in — drives unit + decimals. */
	function dimensionOf(target: CalTarget): Dimension | null {
		if (target === CalTarget.Temperature) return 'temp';
		if (target === CalTarget.Pressure) return 'pressure';
		return null;
	}

	function unitFor(target: CalTarget): string {
		const dim = dimensionOf(target);
		return dim ? unitLabel(dim, prefs) : '';
	}

	function nameOf(target: CalTarget): string {
		if (target === CalTarget.Temperature) return 'temperature';
		if (target === CalTarget.Pressure) return 'pressure';
		return 'flow';
	}

	function toDisplay(target: CalTarget, canonical: number | null | undefined): number | null {
		if (canonical == null || !Number.isFinite(canonical)) return null;
		const dim = dimensionOf(target);
		if (!dim) return canonical;
		return canonicalToDisplay(dim, canonical, prefs);
	}

	function toCanonical(target: CalTarget, display: number): number {
		const dim = dimensionOf(target);
		if (!dim) return display;
		return displayToCanonical(dim, display, prefs);
	}

	function decimalsFor(target: CalTarget): number {
		const dim = dimensionOf(target);
		return dim ? displayDecimals(dim, prefs) : 3;
	}

	// ---- Flow-calibration multiplier (MMR 0x80383C) ----------------------

	const FLOW_MIN = 0.13;
	const FLOW_MAX = 2.0;
	const FLOW_STEP = 0.01;
	const FLOW_DEFAULT = 1.0;

	const flowMultiplierRaw = $derived(
		snapshot.de1MachineInfo[MmrRegister.CalibrationFlowMultiplier]
	);
	const flowMultiplier = $derived<number | null>(
		flowMultiplierRaw != null && Number.isFinite(flowMultiplierRaw)
			? flowMultiplierRaw / 1000
			: null
	);

	/** The value the user is typing in Flow's new-value stepper. */
	let flowDraft = $state(FLOW_DEFAULT);
	/** Whether the draft has ever been seeded from the live read. */
	let flowDraftSeeded = $state(false);

	$effect(() => {
		if (!flowDraftSeeded && flowMultiplier != null) {
			flowDraft = Number(flowMultiplier.toFixed(3));
			flowDraftSeeded = true;
		}
	});

	/** Clamp + round to the wire's accepted resolution (`int(1000 × m)`). */
	function clampFlow(n: number): number {
		if (!Number.isFinite(n)) return FLOW_DEFAULT;
		const clamped = Math.max(FLOW_MIN, Math.min(FLOW_MAX, n));
		return Math.round(clamped * 1000) / 1000;
	}

	const flowDirty = $derived.by(() => {
		if (flowMultiplier == null) return false;
		return Math.abs(clampFlow(flowDraft) - flowMultiplier) > 0.0005;
	});

	const flowAtDefault = $derived(
		flowMultiplier != null && Math.abs(flowMultiplier - FLOW_DEFAULT) < 0.0005
	);

	/**
	 * Reset Flow's multiplier to the no-correction default (1.000). The
	 * flow path has no `resetCalibrationToFactory` wire packet — the
	 * baseline IS 1.000, so we just write it. Re-reads the MMR after the
	 * write so the Current column reflects the firmware's accepted value.
	 */
	async function resetFlowToFactory(): Promise<void> {
		if (!app) return;
		await app.setCalibrationFlowMultiplier(FLOW_DEFAULT);
		await app.readMmr(MmrRegister.CalibrationFlowMultiplier);
		flowDraft = FLOW_DEFAULT;
	}

	// ---- Unified Apply / Reset modal -------------------------------------
	//
	// One modal handles all six paths (apply × 3 targets, reset × 3
	// targets). Apply for Temp + Pressure collects Reported / Measured;
	// Apply for Flow shows the "Setting to" preview. Reset has no fields.
	// Every path ends in a type-to-confirm gate (typing `reset` for reset
	// flows, the sensor name for apply flows) — mirrors the heater-voltage
	// modal. A mis-applied calibration silently warps every shot until
	// reset, so the "stray tap" cost is high enough to justify the same
	// gate the legacy app uses for hardware-damaging writes.

	type ApplyState = { target: CalTarget; mode: 'apply' };
	type ResetState = { target: CalTarget; mode: 'reset' };
	type EditState = ApplyState | ResetState;

	let editing = $state<EditState | null>(null);
	let editReported = $state(0);
	let editMeasured = $state(0);
	let typed = $state('');
	let applying = $state(false);

	/**
	 * Expected type-to-confirm token. For Reset we use a single consistent
	 * verb (`reset`) across all three targets — it reads clearly in the
	 * modal copy ("Type `reset` to confirm") and keeps the muscle-memory
	 * the same regardless of which sensor is being restored. For Apply we
	 * use the sensor name (preserving the existing temp/pressure behaviour
	 * + extending it to flow).
	 */
	const expectedTyped = $derived.by(() => {
		if (editing == null) return '';
		if (editing.mode === 'reset') return 'reset';
		return nameOf(editing.target);
	});
	const typedMatches = $derived(typed.trim().toLowerCase() === expectedTyped);

	/** Open the Apply modal for a target — seeds inputs from the live read. */
	function openApply(target: CalTarget): void {
		if (!connected) return;
		if (target === CalTarget.Temperature || target === CalTarget.Pressure) {
			const current = cal[target]?.current ?? null;
			const displayed = toDisplay(target, current) ?? 0;
			editReported = Number(displayed.toFixed(decimalsFor(target)));
			editMeasured = editReported;
		}
		typed = '';
		editing = { target, mode: 'apply' };
	}

	function openReset(target: CalTarget): void {
		if (!connected) return;
		typed = '';
		editing = { target, mode: 'reset' };
	}

	function closeModal(): void {
		if (applying) return;
		editing = null;
		typed = '';
	}

	async function confirmAction(): Promise<void> {
		if (editing == null || !app || !connected || !typedMatches) return;
		const { target, mode } = editing;
		applying = true;
		try {
			if (mode === 'apply') {
				if (target === CalTarget.Temperature || target === CalTarget.Pressure) {
					const reported = toCanonical(target, editReported);
					const measured = toCanonical(target, editMeasured);
					await app.writeCalibration(target, reported, measured);
				} else {
					// Flow apply — single value commit through MMR.
					const value = clampFlow(flowDraft);
					await app.setCalibrationFlowMultiplier(value);
					await app.readMmr(MmrRegister.CalibrationFlowMultiplier);
				}
			} else {
				// Reset
				if (target === CalTarget.Temperature || target === CalTarget.Pressure) {
					await app.resetCalibrationToFactory(target);
				} else {
					await resetFlowToFactory();
				}
			}
			editing = null;
			typed = '';
		} catch {
			// The orchestrator logs failures via the event stream; leave the
			// modal open so the user sees nothing visibly happened.
		} finally {
			applying = false;
		}
	}

	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && editing != null) {
			e.preventDefault();
			closeModal();
		}
	}

	const applyCaption =
		'After saving, the DE1 will use (measured / reported) as a multiplier on this sensor.';

	const tempUnit = $derived(unitFor(CalTarget.Temperature));
	const pressureUnit = $derived(unitFor(CalTarget.Pressure));

	/** Modal title — friendly sensor name, capitalised by CSS. */
	const modalTitle = $derived(editing == null ? '' : nameOf(editing.target));

	/** Whether the reset gate should be disabled for the live target. */
	function resetDisabledFor(target: CalTarget): { disabled: boolean; reason?: string } {
		if (!connected) return { disabled: true, reason: 'Connect DE1 to reset calibration' };
		if (target === CalTarget.Flow && flowAtDefault) {
			return { disabled: true, reason: 'Already at the default (1.000)' };
		}
		return { disabled: false };
	}

	function applyDisabledFor(target: CalTarget): { disabled: boolean; reason?: string } {
		if (!connected) return { disabled: true, reason: 'Connect DE1 to apply calibration' };
		if (target === CalTarget.Flow && !flowDirty) {
			return { disabled: true, reason: 'New value matches the current — nothing to apply' };
		}
		return { disabled: false };
	}

	const tempApplyState = $derived(applyDisabledFor(CalTarget.Temperature));
	const tempResetState = $derived(resetDisabledFor(CalTarget.Temperature));
	const pressureApplyState = $derived(applyDisabledFor(CalTarget.Pressure));
	const pressureResetState = $derived(resetDisabledFor(CalTarget.Pressure));
	const flowApplyState = $derived(applyDisabledFor(CalTarget.Flow));
	const flowResetState = $derived(resetDisabledFor(CalTarget.Flow));
</script>

<svelte:window onkeydown={onKeydown} />

<StSectionHead
	eyebrow="Diagnostics"
	title="Calibration"
	sub="The sensor calibration values the DE1 is using, and the factory baselines it shipped with. Apply a new calibration when an external instrument shows a sensor is off; Reset to factory restores the original."
/>

<StGroup title="Sensor calibration">
	<CalibrationRow
		title="Temperature"
		unit={tempUnit}
		currentValue={cal[CalTarget.Temperature]?.current ?? null}
		factoryValue={cal[CalTarget.Temperature]?.factory ?? null}
		newValueInput={{ type: 'disabled' }}
		onApply={() => openApply(CalTarget.Temperature)}
		onResetToFactory={() => openReset(CalTarget.Temperature)}
		applyDisabled={tempApplyState.disabled}
		applyDisabledReason={tempApplyState.reason}
		resetDisabled={tempResetState.disabled}
		resetDisabledReason={tempResetState.reason}
		needsConnection={!connected}
		decimals={3}
	/>

	<CalibrationRow
		title="Pressure"
		unit={pressureUnit}
		currentValue={cal[CalTarget.Pressure]?.current ?? null}
		factoryValue={cal[CalTarget.Pressure]?.factory ?? null}
		newValueInput={{ type: 'disabled' }}
		onApply={() => openApply(CalTarget.Pressure)}
		onResetToFactory={() => openReset(CalTarget.Pressure)}
		applyDisabled={pressureApplyState.disabled}
		applyDisabledReason={pressureApplyState.reason}
		resetDisabled={pressureResetState.disabled}
		resetDisabledReason={pressureResetState.reason}
		needsConnection={!connected}
		decimals={3}
	/>

	<CalibrationRow
		title="Flow"
		unit=""
		currentValue={flowMultiplier}
		factoryValue={FLOW_DEFAULT}
		newValueInput={{
			type: 'stepper',
			min: FLOW_MIN,
			max: FLOW_MAX,
			step: FLOW_STEP,
			value: flowDraft
		}}
		onNewValueChange={(next) => (flowDraft = next)}
		onApply={() => openApply(CalTarget.Flow)}
		onResetToFactory={() => openReset(CalTarget.Flow)}
		applyDisabled={flowApplyState.disabled}
		applyDisabledReason={flowApplyState.reason}
		resetDisabled={flowResetState.disabled}
		resetDisabledReason={flowResetState.reason}
		description="Calibrate when an external scale disagrees with the DE1's estimate."
		needsConnection={!connected}
		decimals={3}
	/>

	<StRow
		title="Last read"
		needsConnection={!connected}
		sub="When the calibration values above were last refreshed from the DE1."
	>
		{#snippet control()}
			<div class="cal-refresh">
				<span class="cal-refresh-label">{lastRefreshedLabel}</span>
				<button
					type="button"
					class="st-btn st-btn-secondary"
					onclick={refresh}
					disabled={!connected}
				>
					Refresh
				</button>
			</div>
		{/snippet}
	</StRow>
</StGroup>

{#if editing != null}
	{@const target = editing.target}
	{@const mode = editing.mode}
	{@const isFlow = target === CalTarget.Flow}
	{@const sensorUnit = unitFor(target)}
	<!--
		One inline modal — scrim + centred panel. Click the scrim or press
		Escape to close. Apply mode for Temp/Pressure collects Reported /
		Measured; Apply mode for Flow shows the "Setting to" preview. Reset
		mode shows no fields. Every path requires the type-to-confirm token
		(sensor name for Apply; `reset` for Reset) before the commit button
		enables.
	-->
	<div
		class="cal-modal-scrim"
		role="presentation"
		onclick={closeModal}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') closeModal();
		}}
	>
		<div
			class="cal-modal"
			role="dialog"
			aria-modal="true"
			aria-labelledby="cal-modal-title"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			<div class="cal-modal-head">
				<div class="t-eyebrow">
					{mode === 'apply' ? 'Apply calibration' : 'Reset calibration'}
				</div>
				<h2 id="cal-modal-title">{modalTitle}{isFlow ? ' multiplier' : ' sensor'}</h2>
			</div>

			<div class="cal-modal-warn" role="alert">
				<i class="ph ph-warning-octagon" aria-hidden="true"></i>
				<span>
					{#if mode === 'apply' && isFlow}
						This changes how the DE1 estimates dispensed mass from its
						flow-meter on every shot. Verify the new value matches what
						an external scale shows before proceeding.
					{:else if mode === 'apply'}
						Calibration changes how the DE1 interprets {nameOf(target)}
						readings on every shot. Verify both values come from a trusted
						external instrument before proceeding.
					{:else if isFlow}
						This restores the DE1's flow-calibration multiplier to the
						no-correction default (1.000). Any prior calibration is
						overwritten.
					{:else}
						This discards your current {nameOf(target)} calibration and
						restores the factory baseline. The DE1 will use the as-shipped
						offsets immediately.
					{/if}
				</span>
			</div>

			<div class="cal-modal-body">
				{#if mode === 'apply' && isFlow}
					<div class="cal-flow-rows">
						<div class="cal-flow-row">
							<span class="cal-flow-row-label">Currently set</span>
							<span class="cal-flow-row-value">
								{flowMultiplier == null ? '—' : flowMultiplier.toFixed(3)}
							</span>
						</div>
						<div class="cal-flow-row">
							<span class="cal-flow-row-label">Setting to</span>
							<span class="cal-flow-row-value cal-flow-row-value-chosen">
								{clampFlow(flowDraft).toFixed(3)}
							</span>
						</div>
					</div>
				{:else if mode === 'apply'}
					<label class="cal-field">
						<span class="cal-field-label">Reported</span>
						<span class="cal-field-sub">The value the DE1 reported.</span>
						<div class="cal-field-input">
							<input
								type="number"
								step="any"
								bind:value={editReported}
								disabled={applying}
							/>
							<span class="cal-field-unit">{sensorUnit}</span>
						</div>
					</label>

					<label class="cal-field">
						<span class="cal-field-label">Measured</span>
						<span class="cal-field-sub">The value an external instrument measured.</span>
						<div class="cal-field-input">
							<input
								type="number"
								step="any"
								bind:value={editMeasured}
								disabled={applying}
							/>
							<span class="cal-field-unit">{sensorUnit}</span>
						</div>
					</label>

					<p class="cal-modal-caption">{applyCaption}</p>
				{/if}

				<label class="cal-field">
					<span class="cal-field-label">
						Type <code>{expectedTyped}</code> to confirm
					</span>
					<span class="cal-field-sub">
						This prevents a stray tap from committing the change.
					</span>
					<div class="cal-field-input">
						<input
							type="text"
							autocomplete="off"
							autocorrect="off"
							autocapitalize="off"
							spellcheck="false"
							bind:value={typed}
							disabled={applying}
							onkeydown={(e) => {
								if (e.key === 'Enter' && typedMatches) {
									e.preventDefault();
									void confirmAction();
								}
							}}
							placeholder={expectedTyped}
							aria-label="Type confirmation token"
						/>
					</div>
				</label>
			</div>

			<div class="cal-modal-actions">
				<button
					type="button"
					class="st-btn st-btn-secondary"
					onclick={closeModal}
					disabled={applying}
				>
					Cancel
				</button>
				<button
					type="button"
					class={['st-btn', mode === 'reset' ? 'st-btn-danger' : 'st-btn-primary']}
					onclick={confirmAction}
					disabled={applying || !connected || !typedMatches}
				>
					{#if applying}
						{mode === 'apply' ? 'Applying…' : 'Resetting…'}
					{:else if mode === 'apply'}
						Apply calibration
					{:else if isFlow}
						Reset to default
					{:else}
						Reset to factory
					{/if}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.cal-refresh {
		display: flex;
		align-items: center;
		gap: 12px;
	}
	.cal-refresh-label {
		font-family: var(--font-mono);
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.55);
	}

	/* ---- Modal ---------------------------------------------------------- */

	.cal-modal-scrim {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.55);
		backdrop-filter: blur(4px);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
		padding: 24px;
	}
	.cal-modal {
		background: var(--bg-surface);
		color: var(--fg-1);
		border: 1px solid rgba(var(--tint-rgb), 0.12);
		border-radius: 12px;
		box-shadow:
			0 24px 48px rgba(0, 0, 0, 0.35),
			0 4px 12px rgba(0, 0, 0, 0.2);
		max-width: 440px;
		width: 100%;
		padding: 24px;
		display: flex;
		flex-direction: column;
		gap: 20px;
		outline: none;
	}
	.cal-modal-head h2 {
		margin: 4px 0 0 0;
		font-size: 18px;
		font-weight: 600;
		text-transform: capitalize;
	}
	.cal-modal-warn {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 12px 14px;
		border-radius: 8px;
		font-size: 13px;
		line-height: 1.4;
		background: rgba(220, 80, 0, 0.12);
		border: 1px solid rgba(220, 80, 0, 0.35);
		color: #c64500;
	}
	.cal-modal-warn i {
		flex-shrink: 0;
		font-size: 18px;
		margin-top: 1px;
	}
	.cal-field-label code {
		font-family: var(--font-mono);
		font-size: 13px;
		font-weight: 700;
		padding: 1px 6px;
		background: rgba(var(--tint-rgb), 0.1);
		border-radius: 4px;
	}
	.cal-modal-body {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}
	.cal-field {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.cal-field-label {
		font-size: 13px;
		font-weight: 600;
		color: var(--fg-1);
	}
	.cal-field-sub {
		font-size: 11px;
		color: rgba(var(--tint-rgb), 0.6);
	}
	.cal-field-input {
		margin-top: 6px;
		display: flex;
		align-items: center;
		gap: 8px;
		border: 1px solid rgba(var(--tint-rgb), 0.18);
		border-radius: 8px;
		padding: 6px 10px;
		background: rgba(var(--tint-rgb), 0.04);
	}
	.cal-field-input input {
		flex: 1;
		border: 0;
		outline: 0;
		background: transparent;
		font-family: var(--font-mono);
		font-size: 14px;
		color: var(--fg-1);
		font-variant-numeric: tabular-nums;
	}
	.cal-field-unit {
		font-family: var(--font-mono);
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.55);
	}
	.cal-modal-caption {
		margin: 0;
		font-size: 12px;
		line-height: 1.4;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.cal-modal-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
	}

	/* ---- Flow modal "current vs setting-to" block ----------------------- */

	.cal-flow-rows {
		display: flex;
		flex-direction: column;
		gap: 6px;
		padding: 12px 14px;
		background: rgba(var(--tint-rgb), 0.04);
		border: 1px solid rgba(var(--tint-rgb), 0.1);
		border-radius: 8px;
	}
	.cal-flow-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
	}
	.cal-flow-row-label {
		font-size: 12px;
		color: rgba(var(--tint-rgb), 0.65);
	}
	.cal-flow-row-value {
		font-family: var(--font-mono);
		font-size: 14px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
	}
	.cal-flow-row-value-chosen {
		font-weight: 700;
		font-size: 16px;
	}
</style>
