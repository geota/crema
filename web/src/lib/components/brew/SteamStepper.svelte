<script lang="ts">
	/**
	 * `SteamStepper` — the Steam card. "Steam" prefix with a Time | Flow |
	 * Temp three-way toggle (Temp added 2026-05-22 — DE1 firmware target,
	 * mirrors the ShotSettings `steamTemp` field).
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import { quickControlBounds } from '#lib/core/bounds.js';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';

	let { params }: { params: BrewParamState } = $props();
	/** The steppers' ranges, from the core's table (F8). */
	const b = quickControlBounds();

	const p = $derived(params.current);
</script>

<div>
	<QuickSplitLabel
		prefix="Steam"
		options={[
			{ id: 'time', label: 'time' },
			{ id: 'flow', label: 'flow' },
			{ id: 'temp', label: 'temp' }
		]}
		value={p.steamMode}
		onChange={(v) => params.set('steamMode', v as 'time' | 'flow' | 'temp')}
		dot={p.steamMode === 'temp'}
		dotOn={p.steamTemp > 0}
		onDot={() => params.set('steamTemp', p.steamTemp > 0 ? 0 : 148)}
	/>
	{#if p.steamMode === 'time'}
		<QuickStepper
			value={p.steamTime}
			name="Steam time"
			unit="s"
			min={b.steamTimeS.min}
			max={b.steamTimeS.max}
			step={b.steamTimeS.step}
			onChange={(v) => params.set('steamTime', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[5, 10, 15, 20, 30]}
			value={p.steamTime}
			name="Steam time"
			unit="s"
			onChange={(v) => params.set('steamTime', v)}
		/>
	{:else if p.steamMode === 'flow'}
		<QuickStepper
			value={p.steamFlow}
			name="Steam flow"
			unit="ml/s"
			min={b.steamFlowMlS.min}
			max={b.steamFlowMlS.max}
			step={b.steamFlowMlS.step}
			onChange={(v) => params.set('steamFlow', v)}
			fmt={(v) => v.toFixed(1)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[0.6, 0.9, 1.2, 1.6, 2.0]}
			value={p.steamFlow}
			name="Steam flow"
			onChange={(v) => params.set('steamFlow', v)}
			fmt={(v) => v.toFixed(1)}
		/>
	{:else}
		<!-- steamTemp === 0 disables the steam heater (DE1 firmware: a target
		     below 135 °C snaps to 0 = off). The dot on the "Steam" label is the
		     enable toggle; dialling/chipping a temp re-arms it. Floor is 135 so
		     the user can't land in the silent 120–134 snap-to-off band. -->
		<div class="sts-temp" class:is-off={p.steamTemp === 0}>
			<QuickStepper
				value={p.steamTemp}
				name="Steam temperature"
				dimension="temp"
				min={b.steamTempC.min}
				max={b.steamTempC.max}
				step={b.steamTempC.step}
				onChange={(v) => params.set('steamTemp', v)}
			/>
			<div style="height:6px"></div>
			<QuickChipRow
				options={[140, 145, 148, 150, 155]}
				value={p.steamTemp}
				name="Steam temperature"
				dimension="temp"
				onChange={(v) => params.set('steamTemp', v)}
			/>
		</div>
	{/if}
</div>

<style>
	/* Mirror SegmentRow's .pe-seg-cell-input.is-off: dim the temp controls when
	   the steam heater is disabled (steamTemp === 0). */
	.sts-temp {
		transition: opacity var(--dur-1) var(--ease);
	}
	.sts-temp.is-off {
		opacity: 0.4;
	}
</style>
