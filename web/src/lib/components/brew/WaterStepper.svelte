<script lang="ts">
	/**
	 * `WaterStepper` — the Hot Water card, ported from the design's
	 * `WaterStepper` in `quick-controls-v2.jsx`. A "Hot water" prefix with a
	 * Temp | Volume toggle.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';

	let { params }: { params: BrewParamState } = $props();

	const p = $derived(params.current);
</script>

<div>
	<QuickSplitLabel
		prefix="Hot water"
		options={[
			{ id: 'temp', label: 'temp' },
			{ id: 'volume', label: 'volume' }
		]}
		value={p.waterMode}
		onChange={(v) => params.set('waterMode', v as 'temp' | 'volume')}
	/>
	{#if p.waterMode === 'temp'}
		<QuickStepper
			value={p.waterTemp}
			dimension="temp"
			min={40}
			max={98}
			step={1}
			onChange={(v) => params.set('waterTemp', v)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[60, 75, 85, 92, 96]}
			value={p.waterTemp}
			dimension="temp"
			onChange={(v) => params.set('waterTemp', v)}
		/>
	{:else}
		<QuickStepper
			value={p.waterVolume}
			dimension="volume"
			min={20}
			max={500}
			step={10}
			onChange={(v) => params.set('waterVolume', v)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[60, 120, 180, 250, 350]}
			value={p.waterVolume}
			dimension="volume"
			onChange={(v) => params.set('waterVolume', v)}
		/>
	{/if}
</div>
