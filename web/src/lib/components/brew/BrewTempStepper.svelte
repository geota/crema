<script lang="ts">
	/**
	 * `BrewTempStepper` — the Brew temp card, ported from the design's
	 * `BrewTempStepper` in `quick-controls-v2.jsx`.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QStepper from './QStepper.svelte';
	import QChipRow from './QChipRow.svelte';

	let { params }: { params: BrewParamState } = $props();

	const p = $derived(params.current);
</script>

<div>
	<QStepper
		label="Brew temp"
		value={p.brewTemp}
		unit="°C"
		min={80}
		max={100}
		step={0.5}
		onChange={(v) => params.set('brewTemp', v)}
		fmt={(v) => v.toFixed(1)}
	/>
	<div style="height:6px"></div>
	<QChipRow
		options={[88, 91, 93, 95, 97]}
		value={p.brewTemp}
		unit="°"
		onChange={(v) => params.set('brewTemp', v)}
	/>
</div>
