<script lang="ts">
	/**
	 * `BrewTempStepper` — the BREW bucket: a `Temp | Pre-infuse` split.
	 *
	 * Pre-infuse used to live in the combined `PreinfFlushStepper` bucket
	 * with Flush; that bucket was retired 2026-05-22 — Pre-infuse pairs
	 * with Brew temp (same shot phase, both shape the extraction's start)
	 * and Flush became its own bucket (Time | Temp) on the right side of
	 * the Quick Sheet.
	 *
	 * The component name is kept as `BrewTempStepper` so existing imports
	 * keep working; conceptually it's now "BrewStepper."
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
		prefix="Brew"
		options={[
			{ id: 'temp', label: 'temp' },
			{ id: 'preinf', label: 'pre-infuse' }
		]}
		value={p.brewMode}
		onChange={(v) => params.set('brewMode', v as 'temp' | 'preinf')}
	/>
	{#if p.brewMode === 'temp'}
		<QStepper
			value={p.brewTemp}
			dimension="temp"
			min={80}
			max={100}
			step={0.5}
			onChange={(v) => params.set('brewTemp', v)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[88, 91, 93, 95, 97]}
			value={p.brewTemp}
			dimension="temp"
			onChange={(v) => params.set('brewTemp', v)}
		/>
	{:else}
		<QStepper
			value={p.preinf}
			unit="s"
			min={0}
			max={30}
			step={1}
			onChange={(v) => params.set('preinf', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[0, 4, 8, 12, 16]}
			value={p.preinf}
			unit="s"
			onChange={(v) => params.set('preinf', v)}
		/>
	{/if}
</div>
