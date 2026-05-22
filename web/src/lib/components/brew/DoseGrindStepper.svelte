<script lang="ts">
	/**
	 * `DoseGrindStepper` — the combined Dose | Grind card, ported from the
	 * design's `DoseGrindStepper` in `quick-controls-v2.jsx`. The split-pill
	 * label is the toggle; one stepper edits whichever side is active.
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
			{ id: 'dose', label: 'Dose' },
			{ id: 'grind', label: 'Grind' }
		]}
		value={p.doseGrindMode}
		onChange={(v) => params.set('doseGrindMode', v as 'dose' | 'grind')}
	/>
	{#if p.doseGrindMode === 'dose'}
		<QStepper
			value={p.dose}
			dimension="weight"
			min={5}
			max={30}
			step={0.1}
			onChange={(v) => params.set('dose', v)}
		/>
		<div style="height:8px"></div>
		<QChipRow
			options={[16, 17, 18, 19, 20]}
			value={p.dose}
			dimension="weight"
			onChange={(v) => params.set('dose', v)}
		/>
	{:else}
		<QStepper
			value={p.grind}
			min={0}
			max={20}
			step={0.1}
			onChange={(v) => params.set('grind', v)}
			fmt={(v) => v.toFixed(1)}
		/>
		<div style="height:8px"></div>
		<QChipRow
			options={[3.8, 4.0, 4.2, 4.4, 4.6]}
			value={p.grind}
			onChange={(v) => params.set('grind', v)}
			fmt={(v) => v.toFixed(1)}
		/>
	{/if}
</div>
