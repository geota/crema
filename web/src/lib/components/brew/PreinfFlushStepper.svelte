<script lang="ts">
	/**
	 * `PreinfFlushStepper` — the FLUSH bucket: a `Time | Temp` split.
	 *
	 * Repurposed 2026-05-22 — pre-infuse moved to the BREW bucket
	 * (`BrewTempStepper`) where it pairs naturally with brew-temp; this
	 * card became Flush-only and gained a Temp option (the FlushTemp
	 * MMR setpoint, formerly read-only).
	 *
	 * Component filename kept for import stability; conceptually it's
	 * now "FlushStepper."
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
		prefix="Flush"
		options={[
			{ id: 'time', label: 'time' },
			{ id: 'temp', label: 'temp' }
		]}
		value={p.flushMode}
		onChange={(v) => params.set('flushMode', v as 'time' | 'temp')}
	/>
	{#if p.flushMode === 'time'}
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
	{:else}
		<!-- min/max match reaprime's hot-water range (60..100 °C — flush
		     reuses the hot-water heater path). QStepper's `inc()` +
		     click-to-type `commit()` both clamp to these bounds, so the
		     props are *enforced*, not decorative. docs/40 Category-A
		     MEDIUM #13 — closes the "QC flush stepper has no min/max"
		     row. -->
		<QStepper
			value={p.flushTemp}
			dimension="temp"
			min={60}
			max={100}
			step={0.5}
			onChange={(v) => params.set('flushTemp', v)}
		/>
		<div style="height:6px"></div>
		<QChipRow
			options={[88, 92, 95, 97, 99]}
			value={p.flushTemp}
			dimension="temp"
			onChange={(v) => params.set('flushTemp', v)}
		/>
	{/if}
</div>
