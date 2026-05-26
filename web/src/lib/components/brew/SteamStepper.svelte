<script lang="ts">
	/**
	 * `SteamStepper` — the Steam card. "Steam" prefix with a Time | Flow |
	 * Temp three-way toggle (Temp added 2026-05-22 — DE1 firmware target,
	 * mirrors the ShotSettings `steamTemp` field).
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
		prefix="Steam"
		options={[
			{ id: 'time', label: 'time' },
			{ id: 'flow', label: 'flow' },
			{ id: 'temp', label: 'temp' }
		]}
		value={p.steamMode}
		onChange={(v) => params.set('steamMode', v as 'time' | 'flow' | 'temp')}
	/>
	{#if p.steamMode === 'time'}
		<QuickStepper
			value={p.steamTime}
			unit="s"
			min={1}
			max={60}
			step={1}
			onChange={(v) => params.set('steamTime', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[5, 10, 15, 20, 30]}
			value={p.steamTime}
			unit="s"
			onChange={(v) => params.set('steamTime', v)}
		/>
	{:else if p.steamMode === 'flow'}
		<QuickStepper
			value={p.steamFlow}
			unit="ml/s"
			min={0.2}
			max={3}
			step={0.1}
			onChange={(v) => params.set('steamFlow', v)}
			fmt={(v) => v.toFixed(1)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[0.6, 0.9, 1.2, 1.6, 2.0]}
			value={p.steamFlow}
			onChange={(v) => params.set('steamFlow', v)}
			fmt={(v) => v.toFixed(1)}
		/>
	{:else}
		<QuickStepper
			value={p.steamTemp}
			dimension="temp"
			min={120}
			max={170}
			step={0.5}
			onChange={(v) => params.set('steamTemp', v)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[140, 145, 148, 150, 155]}
			value={p.steamTemp}
			dimension="temp"
			onChange={(v) => params.set('steamTemp', v)}
		/>
	{/if}
</div>
