<script lang="ts">
	/**
	 * `PreinfFlushStepper` — the combined Pre-Infuse | Flush card, ported from
	 * the design's `PreinfFlushStepper` in `quick-controls-v2.jsx`.
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
		options={[
			{ id: 'preinf', label: 'Pre-infuse' },
			{ id: 'flush', label: 'Flush' }
		]}
		value={p.timeMode}
		onChange={(v) => params.set('timeMode', v as 'preinf' | 'flush')}
	/>
	{#if p.timeMode === 'preinf'}
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
	{:else}
		<QStepper
			value={p.flushTime}
			unit="s"
			min={1}
			max={20}
			step={1}
			onChange={(v) => params.set('flushTime', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[2, 4, 6, 8, 10]}
			value={p.flushTime}
			unit="s"
			onChange={(v) => params.set('flushTime', v)}
		/>
	{/if}
</div>
