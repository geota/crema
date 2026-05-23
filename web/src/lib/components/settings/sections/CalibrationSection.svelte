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
	import { CalTarget } from '$lib/core';
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
	 * On mount, fire reads for temperature + pressure (current + factory).
	 * Flow is skipped to match legacy — the flow calibration is read via
	 * `MmrRegister::CalibrationFlowMultiplier` (a separate path), not the
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

	// ---- Apply modal --------------------------------------------------------
	//
	// Inline modal — the codebase has no existing dialog component, so this
	// section owns its own overlay. Keep it small + dismissable: scrim click,
	// Escape, Cancel button. Two number inputs default to the displayed
	// current value so the user only edits one side (typically Measured).

	/** The target currently being edited, or `null` when the modal is closed. */
	let editing = $state<CalTarget | null>(null);
	/** Live form values (display units). */
	let editReported = $state(0);
	let editMeasured = $state(0);
	/** Submission spinner — disables the Confirm button while in-flight. */
	let applying = $state(false);

	function openApply(target: CalTarget): void {
		const current = cal[target]?.current ?? null;
		const displayed = toDisplay(target, current) ?? 0;
		editReported = Number(displayed.toFixed(decimalsFor(target)));
		editMeasured = editReported;
		editing = target;
	}

	function closeApply(): void {
		if (applying) return;
		editing = null;
	}

	async function confirmApply(): Promise<void> {
		if (editing == null || !app || !connected) return;
		const target = editing;
		const reported = toCanonical(target, editReported);
		const measured = toCanonical(target, editMeasured);
		applying = true;
		try {
			await app.writeCalibration(target, reported, measured);
			editing = null;
		} catch {
			// The orchestrator logs failures via the event stream; leave the
			// modal open so the user sees nothing visibly happened.
		} finally {
			applying = false;
		}
	}

	async function resetToFactory(target: CalTarget): Promise<void> {
		if (!app || !connected) return;
		if (typeof window === 'undefined') return;
		if (!window.confirm(`Reset ${nameOf(target)} calibration to factory defaults?`)) return;
		try {
			await app.resetCalibrationToFactory(target);
		} catch {
			// Same as above — event stream carries the failure.
		}
	}

	/** Escape closes the modal when one is open. */
	function onKeydown(e: KeyboardEvent): void {
		if (e.key === 'Escape' && editing != null) {
			e.preventDefault();
			closeApply();
		}
	}

	/** Caption explaining the effect of the multiplier. */
	const applyCaption =
		'After saving, the DE1 will use (measured / reported) as a multiplier on this sensor.';
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
						onclick={() => resetToFactory(CalTarget.Temperature)}
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
						onclick={() => resetToFactory(CalTarget.Pressure)}
						disabled={!connected}
					>
						Reset to factory
					</button>
				</div>
			</div>
		{/snippet}
	</StRow>

	<StRow
		title="Flow"
		sub="Flow calibration uses a separate multiplier register (MMR 0x80383C); read it on the Brew settings screen when that surface lands."
	>
		{#snippet control()}
			<div class="cal-pair">
				<div>
					<div class="t-eyebrow">Current</div>
					<div class="cal-val">—</div>
				</div>
				<div>
					<div class="t-eyebrow">Factory</div>
					<div class="cal-val">—</div>
				</div>
			</div>
		{/snippet}
	</StRow>

	<StRow title="Last read" sub="When the calibration values above were last refreshed from the DE1.">
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
	{@const target = editing}
	<!--
		Inline modal — scrim + centred panel. Click the scrim or press Escape
		to close (mirrors the legacy gui.tcl confirm dance, but with a real
		two-field form instead of a Tk popup).
	-->
	<div
		class="cal-modal-scrim"
		role="presentation"
		onclick={closeApply}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') closeApply();
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
				<div class="t-eyebrow">Apply calibration</div>
				<h2 id="cal-modal-title">{nameOf(target)} sensor</h2>
			</div>

			<div class="cal-modal-body">
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
			</div>

			<div class="cal-modal-actions">
				<button
					type="button"
					class="st-btn st-btn-secondary"
					onclick={closeApply}
					disabled={applying}
				>
					Cancel
				</button>
				<button
					type="button"
					class="st-btn st-btn-primary"
					onclick={confirmApply}
					disabled={applying || !connected}
				>
					{applying ? 'Applying…' : 'Confirm + Apply'}
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
		background: var(--bg-1, #fff);
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
</style>
