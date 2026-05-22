<script lang="ts">
	/**
	 * Calibration section — read-only display of the DE1's sensor calibration
	 * values (current + factory). The DE1 exposes calibration via the
	 * `cuuid_12 / Calibration` characteristic; a read request is *written* to
	 * it and the DE1 answers on the same characteristic. Crema's core
	 * decodes the reply into `Event::Calibration` which lands on the
	 * snapshot's `de1Calibration` field.
	 *
	 * Legacy reference: `gui.tcl:2445-2452` — the legacy calibration screen
	 * reads temperature + pressure current/factory on mount (flow reads are
	 * commented out; flow uses a separate MMR-based multiplier). Crema
	 * matches: reads the four values on screen mount, displays them in a
	 * grid.
	 *
	 * Write side (edit / restore factory) is deferred — this first pass
	 * surfaces the data so it's no longer dormant per docs/20 finding #7.
	 */
	import { onMount } from 'svelte';
	import type { CremaApp } from '$lib/state';
	import { CalTarget } from '$lib/core';
	import { INITIAL_SNAPSHOT } from '$lib/state';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import { getSettingsStore, unitLabel } from '$lib/settings';

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
</script>

<StSectionHead
	eyebrow="Diagnostics"
	title="Calibration"
	sub="The sensor calibration values the DE1 is using, and the factory baselines it shipped with. Read-only — editing calibration is not yet wired."
/>

<StGroup title="Sensor calibration">
	<StRow
		title="Temperature"
		sub="The DE1's group-head thermocouple offset, {unitLabel('temp', prefs)}."
	>
		{#snippet control()}
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
		{/snippet}
	</StRow>

	<StRow
		title="Pressure"
		sub="The DE1's group-pressure sensor offset, {unitLabel('pressure', prefs)}."
	>
		{#snippet control()}
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

<style>
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
</style>
