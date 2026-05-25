<script lang="ts">
	/**
	 * Calibration section — read + write of the DE1's sensor calibration.
	 *
	 * The DE1 exposes calibration via the `cuuid_12 / Calibration`
	 * characteristic; a read request is *written* to it and the DE1 answers
	 * on the same characteristic, decoded by the core into
	 * `Event::Calibration` which lands on the snapshot's `de1Calibration`
	 * field. A write (the `WriteKey 0xCAFEF00D` packet) tells the DE1 to use
	 * `measured / reported` as a multiplier on the named sensor; a separate
	 * `ResetToFactory` packet restores the factory baseline.
	 *
	 * Flow calibration is a **separate path** — the flow multiplier lives in
	 * the MMR register `CalibrationFlowMultiplier` (`0x80383C`), not on
	 * `cuuid_12`. Path A of docs/43 is wired here: a numeric input bounded
	 * 0.13..=2.00 step 0.01 that reads `de1MachineInfo[CalibrationFlow
	 * Multiplier]` (raw `int(1000 × m)` → divide by 1000), and on Apply +
	 * confirm writes through `app.core.setCalibrationFlowMultiplier(value)`
	 * (core clamps to the same 0.13..=2.0 range). The auto-derive
	 * (dispense → weigh → derive) flow is deferred per docs/43 v2.
	 *
	 * Legacy reference: `gui.tcl:2445-2452` — the legacy calibration screen
	 * reads temperature + pressure current/factory on mount (flow reads are
	 * commented out; flow uses a separate MMR-based multiplier). Crema
	 * matches the read shape and adds an Apply / Reset to factory action on
	 * each row.
	 *
	 * Unit-awareness: the codec works in canonical units (°C / bar / ml·s⁻¹)
	 * — the Apply modal renders its inputs in the user's display unit and
	 * converts back at the I/O boundary, so a °F user types °F. Flow is left
	 * read-only here because its in-use value lives in a separate MMR
	 * register, not on `cuuid_12`.
	 */
	import { onMount } from 'svelte';
	import type { CremaApp } from '$lib/state';
	import { CalTarget, MmrRegister } from '$lib/core';
	import { INITIAL_SNAPSHOT } from '$lib/state';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
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

	/**
	 * On mount, fire reads for temperature + pressure (current + factory)
	 * **and** the flow-calibration MMR multiplier. Flow has its own register
	 * (`CalibrationFlowMultiplier`, MMR `0x80383C`) — separate from the
	 * `cuuid_12` Calibration block.
	 *
	 * Reads are non-fatal: a disconnect mid-mount just leaves the value
	 * fields as `null` and the row reads `—`.
	 */
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

	/** Format a calibration value: 3 decimal places, or a dash if null. */
	function fmt(v: number | null | undefined): string {
		if (v == null) return '—';
		return v.toFixed(3);
	}

	/** Format the "last read" timestamp in HH:MM:SS local time. */
	const lastRefreshedLabel = $derived(
		lastRefreshAt == null ? 'reading…' : new Date(lastRefreshAt).toLocaleTimeString()
	);

	/**
	 * Which dimension a calibration target lives in. Drives the unit label
	 * + the canonical-to-display conversion in the Apply modal. Flow has no
	 * imperial counterpart in the Settings store (no `flowUnit`) so its
	 * input is unitless ml·s⁻¹.
	 */
	function dimensionOf(target: CalTarget): Dimension | null {
		if (target === CalTarget.Temperature) return 'temp';
		if (target === CalTarget.Pressure) return 'pressure';
		return null;
	}

	/** Per-target unit suffix shown next to the inputs (and elsewhere). */
	function unitFor(target: CalTarget): string {
		const dim = dimensionOf(target);
		return dim ? unitLabel(dim, prefs) : 'ml/s';
	}

	/** Per-target friendly name for confirm copy. */
	function nameOf(target: CalTarget): string {
		if (target === CalTarget.Temperature) return 'temperature';
		if (target === CalTarget.Pressure) return 'pressure';
		return 'flow';
	}

	/** Convert a canonical reading to the user's display unit. */
	function toDisplay(target: CalTarget, canonical: number | null | undefined): number | null {
		if (canonical == null || !Number.isFinite(canonical)) return null;
		const dim = dimensionOf(target);
		if (!dim) return canonical;
		return canonicalToDisplay(dim, canonical, prefs);
	}

	/** Inverse of `toDisplay` for committing the modal's inputs to the core. */
	function toCanonical(target: CalTarget, display: number): number {
		const dim = dimensionOf(target);
		if (!dim) return display;
		return displayToCanonical(dim, display, prefs);
	}

	/** Decimals the modal's number inputs should show, per dimension + unit. */
	function decimalsFor(target: CalTarget): number {
		const dim = dimensionOf(target);
		return dim ? displayDecimals(dim, prefs) : 1;
	}

	// ---- Apply / Reset modal -----------------------------------------------
	//
	// One inline modal handles both flows. Apply collects Reported /
	// Measured; Reset has no fields. Both end in a type-to-confirm gate
	// (the user types the sensor name verbatim), mirroring the heater-
	// voltage modal — a mis-applied calibration silently warps every shot
	// until reset, so the "stray tap" cost is high enough to justify the
	// same gate the legacy app uses for hardware-damaging writes.

	type EditState = { target: CalTarget; mode: 'apply' | 'reset' };
	/** The target + mode currently being edited, or `null` when closed. */
	let editing = $state<EditState | null>(null);
	/** Apply-mode form values (display units). */
	let editReported = $state(0);
	let editMeasured = $state(0);
	/** Type-to-confirm input — must equal `nameOf(target)` to enable commit. */
	let typed = $state('');
	/** Submission spinner — disables the Confirm button while in-flight. */
	let applying = $state(false);

	const expectedTyped = $derived(editing ? nameOf(editing.target) : '');
	const typedMatches = $derived(typed.trim().toLowerCase() === expectedTyped);

	function openApply(target: CalTarget): void {
		const current = cal[target]?.current ?? null;
		const displayed = toDisplay(target, current) ?? 0;
		editReported = Number(displayed.toFixed(decimalsFor(target)));
		editMeasured = editReported;
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
				const reported = toCanonical(target, editReported);
				const measured = toCanonical(target, editMeasured);
				await app.writeCalibration(target, reported, measured);
			} else {
				await app.resetCalibrationToFactory(target);
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

	/** Escape closes the modal when one is open. */
	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && editing != null) {
			e.preventDefault();
			closeModal();
		}
		if (e.key === 'Escape' && flowEditing) {
			e.preventDefault();
			closeFlowModal();
		}
	}

	/** Caption explaining the effect of the multiplier. */
	const applyCaption =
		'After saving, the DE1 will use (measured / reported) as a multiplier on this sensor.';

	// ---- Flow-calibration multiplier (MMR 0x80383C) ----------------------
	//
	// Per docs/43 Path A. The core already exposes the read/write path; this
	// adds the UI surface promised by the legacy "Flow" row sub-text.
	//
	// Wire value is `int(1000 × multiplier)`; the snapshot's
	// `de1MachineInfo[CalibrationFlowMultiplier]` holds it raw. Divide by
	// 1000 to get the human multiplier. Range 0.13..=2.0 (reaprime clamp,
	// followed by Crema's core); step 0.01; default 1.000 (the no-correction
	// value).
	//
	// Apply opens a confirm modal (the same shape as the temperature /
	// pressure apply, minus the reported / measured math — flow has no
	// reported / measured workflow here, just "set this value"). Reset
	// writes `1.000` (the firmware has no per-register factory-reset wire
	// packet — see docs/43 §Crema "Reset to factory").

	const FLOW_MIN = 0.13;
	const FLOW_MAX = 2.0;
	const FLOW_STEP = 0.01;
	const FLOW_DEFAULT = 1.0;

	/** Raw value from the MMR read, or `undefined` if not yet read. */
	const flowMultiplierRaw = $derived(
		snapshot.de1MachineInfo[MmrRegister.CalibrationFlowMultiplier]
	);
	/** Human multiplier (raw / 1000), or `null` until the first read lands. */
	const flowMultiplier = $derived<number | null>(
		flowMultiplierRaw != null && Number.isFinite(flowMultiplierRaw)
			? flowMultiplierRaw / 1000
			: null
	);
	/** Current value, formatted to 3 decimals. */
	const flowCurrentLabel = $derived(
		flowMultiplier == null ? '—' : flowMultiplier.toFixed(3)
	);

	/** The value the user is typing in the row's number input. */
	let flowDraft = $state(FLOW_DEFAULT);
	/** Whether the draft has ever been set from the read (so we don't clobber the user's edit). */
	let flowDraftSeeded = $state(false);

	/**
	 * Seed the draft once the first read lands so the input shows the live
	 * value, not the default. Subsequent reads (e.g. after Apply) do *not*
	 * re-seed — the user may still be editing.
	 */
	$effect(() => {
		if (!flowDraftSeeded && flowMultiplier != null) {
			flowDraft = Number(flowMultiplier.toFixed(3));
			flowDraftSeeded = true;
		}
	});

	/**
	 * Clamp + round the draft to the wire's accepted resolution. Mirrors the
	 * core clamp (0.13..=2.0) plus 3-decimal precision (the wire scale is
	 * `× 1000`).
	 */
	function clampFlow(n: number): number {
		if (!Number.isFinite(n)) return FLOW_DEFAULT;
		const clamped = Math.max(FLOW_MIN, Math.min(FLOW_MAX, n));
		return Math.round(clamped * 1000) / 1000;
	}

	/** Whether the draft differs from the live value (Apply has work to do). */
	const flowDirty = $derived.by(() => {
		if (flowMultiplier == null) return false;
		return Math.abs(clampFlow(flowDraft) - flowMultiplier) > 0.0005;
	});

	/**
	 * Whether the "Reset to 1.000" button should be enabled — only if the
	 * live multiplier is not already 1.000 (within the wire's precision).
	 */
	const flowAtDefault = $derived(
		flowMultiplier != null && Math.abs(flowMultiplier - FLOW_DEFAULT) < 0.0005
	);

	type FlowEditMode = 'apply' | 'reset';
	let flowEditing = $state<FlowEditMode | null>(null);
	let flowApplying = $state(false);
	/** Type-to-confirm input for the flow-multiplier modal. */
	let flowTyped = $state('');
	/** The value the modal will commit (clamped draft for apply, default for reset). */
	const flowTargetValue = $derived(
		flowEditing === 'reset' ? FLOW_DEFAULT : clampFlow(flowDraft)
	);
	const flowExpectedTyped = 'flow';
	const flowTypedMatches = $derived(flowTyped.trim().toLowerCase() === flowExpectedTyped);

	function openFlowApply(): void {
		if (!connected || !flowDirty) return;
		flowTyped = '';
		flowEditing = 'apply';
	}

	function openFlowReset(): void {
		if (!connected || flowAtDefault) return;
		flowTyped = '';
		flowEditing = 'reset';
	}

	function closeFlowModal(): void {
		if (flowApplying) return;
		flowEditing = null;
		flowTyped = '';
	}

	async function confirmFlowAction(): Promise<void> {
		if (flowEditing == null || !app || !connected || !flowTypedMatches) return;
		flowApplying = true;
		try {
			await app.setCalibrationFlowMultiplier(flowTargetValue);
			// Read back so the snapshot's `de1MachineInfo` reflects the
			// firmware's accepted value; the row's "Current" label re-renders
			// from the live read, giving the user visible confirmation.
			await app.readMmr(MmrRegister.CalibrationFlowMultiplier);
			// After a reset, snap the draft back to the default so the row
			// no longer reports "dirty".
			if (flowEditing === 'reset') flowDraft = FLOW_DEFAULT;
			flowEditing = null;
			flowTyped = '';
		} catch {
			// The orchestrator logs via the event stream; leave the modal
			// open so a retry / cancel is one tap.
		} finally {
			flowApplying = false;
		}
	}

	function onFlowDraftInput(e: Event): void {
		const target = e.target as HTMLInputElement;
		const n = Number(target.value);
		if (Number.isFinite(n)) flowDraft = n;
	}
</script>

<svelte:window onkeydown={onKeydown} />

<StSectionHead
	eyebrow="Diagnostics"
	title="Calibration"
	sub="The sensor calibration values the DE1 is using, and the factory baselines it shipped with. Apply a new calibration when an external instrument shows a sensor is off; Reset to factory restores the original."
/>

<StGroup title="Sensor calibration">
	<StRow
		title="Temperature"
		needsConnection={!connected}
		sub="The DE1's group-head thermocouple offset, {unitLabel('temp', prefs)}."
	>
		{#snippet control()}
			<div class="cal-control">
				<div class="cal-pair">
					<div>
						<div class="t-eyebrow">Current</div>
						<div class="cal-val">{fmt(cal[CalTarget.Temperature]?.current)}</div>
					</div>
					<div>
						<div class="t-eyebrow">Factory</div>
						<div class="cal-val">{fmt(cal[CalTarget.Temperature]?.factory)}</div>
					</div>
				</div>
				<div class="cal-actions">
					<button
						type="button"
						class="st-btn st-btn-secondary"
						onclick={() => openApply(CalTarget.Temperature)}
						disabled={!connected}
					>
						Apply
					</button>
					<button
						type="button"
						class="st-btn st-btn-danger"
						onclick={() => openReset(CalTarget.Temperature)}
						disabled={!connected}
					>
						Reset to factory
					</button>
				</div>
			</div>
		{/snippet}
	</StRow>

	<StRow
		title="Pressure"
		needsConnection={!connected}
		sub="The DE1's group-pressure sensor offset, {unitLabel('pressure', prefs)}."
	>
		{#snippet control()}
			<div class="cal-control">
				<div class="cal-pair">
					<div>
						<div class="t-eyebrow">Current</div>
						<div class="cal-val">{fmt(cal[CalTarget.Pressure]?.current)}</div>
					</div>
					<div>
						<div class="t-eyebrow">Factory</div>
						<div class="cal-val">{fmt(cal[CalTarget.Pressure]?.factory)}</div>
					</div>
				</div>
				<div class="cal-actions">
					<button
						type="button"
						class="st-btn st-btn-secondary"
						onclick={() => openApply(CalTarget.Pressure)}
						disabled={!connected}
					>
						Apply
					</button>
					<button
						type="button"
						class="st-btn st-btn-danger"
						onclick={() => openReset(CalTarget.Pressure)}
						disabled={!connected}
					>
						Reset to factory
					</button>
				</div>
			</div>
		{/snippet}
	</StRow>

	<StRow
		title="Flow calibration multiplier"
		needsConnection={!connected}
		sub="Per-machine flow correction (MMR 0x80383C). Default 1.000 (no correction); range 0.13–2.0. Drag-grade flow drift skews weight-stop accuracy; calibrate when an external scale disagrees with the DE1's estimate."
	>
		{#snippet control()}
			<div class="cal-control">
				<div class="cal-pair">
					<div>
						<div class="t-eyebrow">Current</div>
						<div class="cal-val">{flowCurrentLabel}</div>
					</div>
					<div>
						<div class="t-eyebrow">New value</div>
						<div class="cal-flow-input">
							<input
								type="number"
								min={FLOW_MIN}
								max={FLOW_MAX}
								step={FLOW_STEP}
								value={Number(flowDraft.toFixed(3))}
								oninput={onFlowDraftInput}
								disabled={!connected}
								aria-label="Flow calibration multiplier"
							/>
						</div>
					</div>
				</div>
				<div class="cal-actions">
					<button
						type="button"
						class="st-btn st-btn-secondary"
						onclick={openFlowApply}
						disabled={!connected || !flowDirty}
						title={!connected ? 'Connect DE1 to apply calibration' : undefined}
					>
						Apply
					</button>
					<button
						type="button"
						class="cal-flow-reset"
						onclick={openFlowReset}
						disabled={!connected || flowAtDefault}
						title={flowAtDefault ? 'Already at the default (1.000)' : undefined}
					>
						Reset to 1.000
					</button>
				</div>
			</div>
		{/snippet}
	</StRow>

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
	<!--
		Inline modal — scrim + centred panel. Click the scrim or press Escape
		to close. Apply mode collects Reported / Measured; Reset mode shows
		no fields. Both gates require typing the sensor name to commit, so a
		stray tap can't warp the user's shots until they next reset.
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
				<h2 id="cal-modal-title">{nameOf(target)} sensor</h2>
			</div>

			<div class="cal-modal-warn" role="alert">
				<i class="ph ph-warning-octagon" aria-hidden="true"></i>
				<span>
					{#if mode === 'apply'}
						Calibration changes how the DE1 interprets {nameOf(target)}
						readings on every shot. Verify both values come from a trusted
						external instrument before proceeding.
					{:else}
						This discards your current {nameOf(target)} calibration and
						restores the factory baseline. The DE1 will use the as-shipped
						offsets immediately.
					{/if}
				</span>
			</div>

			<div class="cal-modal-body">
				{#if mode === 'apply'}
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
							<span class="cal-field-unit">{unitFor(target)}</span>
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
							<span class="cal-field-unit">{unitFor(target)}</span>
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
							aria-label="Type sensor name to confirm"
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
					{:else}
						Reset to factory
					{/if}
				</button>
			</div>
		</div>
	</div>
{/if}

{#if flowEditing != null}
	<!--
		Flow-calibration confirm modal — same scrim + panel shape as the
		sensor-calibration modal above. Asks the user to type `flow` before
		committing. The wire write is `set_calibration_flow_multiplier`
		(MMR `0x80383C`, scaled `int(1000 × m)`); the core also clamps
		0.13..=2.0 as a defence-in-depth.
	-->
	<div
		class="cal-modal-scrim"
		role="presentation"
		onclick={closeFlowModal}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') closeFlowModal();
		}}
	>
		<div
			class="cal-modal"
			role="dialog"
			aria-modal="true"
			aria-labelledby="cal-flow-modal-title"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.stopPropagation()}
		>
			<div class="cal-modal-head">
				<div class="t-eyebrow">
					{flowEditing === 'apply' ? 'Apply calibration' : 'Reset calibration'}
				</div>
				<h2 id="cal-flow-modal-title">flow multiplier</h2>
			</div>

			<div class="cal-modal-warn" role="alert">
				<i class="ph ph-warning-octagon" aria-hidden="true"></i>
				<span>
					{#if flowEditing === 'apply'}
						This changes how the DE1 estimates dispensed mass from its
						flow-meter on every shot. Verify the new value matches what
						an external scale shows before proceeding.
					{:else}
						This restores the DE1's flow-calibration multiplier to the
						no-correction default (1.000). Any prior calibration is
						overwritten.
					{/if}
				</span>
			</div>

			<div class="cal-modal-body">
				<div class="cal-flow-rows">
					<div class="cal-flow-row">
						<span class="cal-flow-row-label">Currently set</span>
						<span class="cal-flow-row-value">{flowCurrentLabel}</span>
					</div>
					<div class="cal-flow-row">
						<span class="cal-flow-row-label">Setting to</span>
						<span class="cal-flow-row-value cal-flow-row-value-chosen">
							{flowTargetValue.toFixed(3)}
						</span>
					</div>
				</div>

				<label class="cal-field">
					<span class="cal-field-label">
						Type <code>{flowExpectedTyped}</code> to confirm
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
							bind:value={flowTyped}
							disabled={flowApplying}
							onkeydown={(e) => {
								if (e.key === 'Enter' && flowTypedMatches) {
									e.preventDefault();
									void confirmFlowAction();
								}
							}}
							placeholder={flowExpectedTyped}
							aria-label="Type flow to confirm"
						/>
					</div>
				</label>
			</div>

			<div class="cal-modal-actions">
				<button
					type="button"
					class="st-btn st-btn-secondary"
					onclick={closeFlowModal}
					disabled={flowApplying}
				>
					Cancel
				</button>
				<button
					type="button"
					class={['st-btn', flowEditing === 'reset' ? 'st-btn-danger' : 'st-btn-primary']}
					onclick={confirmFlowAction}
					disabled={flowApplying || !connected || !flowTypedMatches}
				>
					{#if flowApplying}
						{flowEditing === 'apply' ? 'Applying…' : 'Resetting…'}
					{:else if flowEditing === 'apply'}
						Apply calibration
					{:else}
						Reset to default
					{/if}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.cal-control {
		display: flex;
		align-items: center;
		gap: 24px;
	}
	.cal-pair {
		display: flex;
		gap: 28px;
	}
	.cal-val {
		font-family: var(--font-mono);
		font-size: 13px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
	}
	.cal-actions {
		display: flex;
		gap: 8px;
		align-items: center;
	}
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

	/* ---- Flow-multiplier row -------------------------------------------- */

	.cal-flow-input {
		display: inline-flex;
		align-items: center;
		min-width: 88px;
	}
	.cal-flow-input input {
		width: 6em;
		border: 1px solid rgba(var(--tint-rgb), 0.18);
		border-radius: 6px;
		background: rgba(var(--tint-rgb), 0.04);
		padding: 4px 8px;
		font-family: var(--font-mono);
		font-size: 13px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
		outline: none;
	}
	.cal-flow-input input:focus-visible {
		border-color: rgba(var(--copper-rgb), 0.5);
		background: rgba(var(--tint-rgb), 0.06);
	}
	.cal-flow-input input:disabled {
		opacity: 0.55;
		cursor: not-allowed;
	}
	.cal-flow-reset {
		background: transparent;
		border: 0;
		color: var(--copper-400);
		font-family: var(--font-sans);
		font-size: 12px;
		padding: 4px 6px;
		cursor: pointer;
		text-decoration: underline;
		text-underline-offset: 2px;
	}
	.cal-flow-reset:hover:not(:disabled) {
		color: var(--fg-1);
	}
	.cal-flow-reset:disabled {
		opacity: 0.4;
		cursor: not-allowed;
		text-decoration: none;
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
