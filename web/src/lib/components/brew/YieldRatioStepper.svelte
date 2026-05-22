<script lang="ts">
	/**
	 * `YieldRatioStepper` — the Yield card, ported from the design's
	 * `YieldRatioStepper` in `quick-controls-v2.jsx`. A short label with the
	 * live brew ratio as a top-right superscript.
	 */
	import type { BrewParamState } from './brew-params.svelte';
	import QStepper from './QStepper.svelte';
	import QChipRow from './QChipRow.svelte';

	let { params }: { params: BrewParamState } = $props();

	const p = $derived(params.current);
	/** Live yield-to-dose ratio. */
	const ratio = $derived((p.yield / p.dose).toFixed(2));
</script>

<div>
	<div class="qcs-label-row">
		<span class="qcs-label">Yield</span>
		<span class="qcs-sup">1:{ratio}</span>
	</div>
	<QStepper
		value={p.yield}
		dimension="weight"
		min={10}
		max={80}
		step={0.5}
		onChange={(v) => params.set('yield', v)}
	/>
	<div style="height:6px"></div>
	<QChipRow
		options={[28, 32, 36, 40, 45]}
		value={p.yield}
		dimension="weight"
		onChange={(v) => params.set('yield', v)}
	/>
</div>
