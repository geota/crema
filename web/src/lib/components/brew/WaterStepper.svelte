<script lang="ts">
	/**
	 * `WaterStepper` — the Hot Water card, ported from the design's
	 * `WaterStepper` in `quick-controls-v2.jsx`. A "Hot water" prefix with a
	 * Temp | Volume toggle.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QSplitLabel from './QSplitLabel.svelte';
	import QStepper from './QStepper.svelte';
	import QChipRow from './QChipRow.svelte';

	let { params }: { params: BrewParamState } = $props();

	const p = $derived(params.current);
</script>

<div>
	<QSplitLabel
		prefix="Hot water"
		options={[
			{ id: 'temp', label: 'temp' },
			{ id: 'volume', label: 'volume' }
		]}
		value={p.waterMode}
		onChange={(v) => params.set('waterMode', v as 'temp' | 'volume')}
	/>
	{#if p.waterMode === 'temp'}
		<QStepper
			value={p.waterTemp}
			unit="°C"
			min={40}
			max={98}
			step={1}
			onChange={(v) => params.set('waterTemp', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[60, 75, 85, 92, 96]}
			value={p.waterTemp}
			unit="°"
			onChange={(v) => params.set('waterTemp', v)}
		/>
	{:else}
		<QStepper
			value={p.waterVolume}
			unit="ml"
			min={20}
			max={500}
			step={10}
			onChange={(v) => params.set('waterVolume', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[60, 120, 180, 250, 350]}
			value={p.waterVolume}
			onChange={(v) => params.set('waterVolume', v)}
		/>
	{/if}
</div>
