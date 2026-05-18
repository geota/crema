<script lang="ts">
	/**
	 * `SteamStepper` — the Steam card, ported from the design's `SteamStepper`
	 * in `quick-controls-v2.jsx`. A "Steam" prefix with a Time | Flow toggle.
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
		prefix="Steam"
		options={[
			{ id: 'time', label: 'time' },
			{ id: 'flow', label: 'flow' }
		]}
		value={p.steamMode}
		onChange={(v) => params.set('steamMode', v as 'time' | 'flow')}
	/>
	{#if p.steamMode === 'time'}
		<QStepper
			value={p.steamTime}
			unit="s"
			min={1}
			max={60}
			step={1}
			onChange={(v) => params.set('steamTime', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[5, 10, 15, 20, 30]}
			value={p.steamTime}
			unit="s"
			onChange={(v) => params.set('steamTime', v)}
		/>
	{:else}
		<QStepper
			value={p.steamFlow}
			unit="ml/s"
			min={0.2}
			max={3}
			step={0.1}
			onChange={(v) => params.set('steamFlow', v)}
			fmt={(v) => v.toFixed(1)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[0.6, 0.9, 1.2, 1.6, 2.0]}
			value={p.steamFlow}
			onChange={(v) => params.set('steamFlow', v)}
			fmt={(v) => v.toFixed(1)}
		/>
	{/if}
</div>
