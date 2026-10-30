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
	import { quickControlBounds } from '#lib/core/bounds.js';
	import QuickSplitLabel from './QuickSplitLabel.svelte';
	import QuickStepper from './QuickStepper.svelte';
	import QuickChipRow from './QuickChipRow.svelte';

	let { params }: { params: BrewParamState } = $props();
	/** The steppers' ranges, from the core's table (F8). */
	const b = quickControlBounds();

	const p = $derived(params.current);
</script>

<div>
	<QuickSplitLabel
		prefix="Flush"
		options={[
			{ id: 'time', label: 'time' },
			{ id: 'temp', label: 'temp' }
		]}
		value={p.flushMode}
		onChange={(v) => params.set('flushMode', v as 'time' | 'temp')}
	/>
	{#if p.flushMode === 'time'}
		<QuickStepper
			value={p.flushTime}
			name="Flush time"
			unit="s"
			min={b.flushTimeS.min}
			max={b.flushTimeS.max}
			step={b.flushTimeS.step}
			onChange={(v) => params.set('flushTime', v)}
			fmt={(v) => v.toFixed(0)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[2, 4, 6, 8, 10]}
			value={p.flushTime}
			name="Flush time"
			unit="s"
			onChange={(v) => params.set('flushTime', v)}
		/>
	{:else}
		<!-- min/max match reaprime's hot-water range (60..100 °C — flush
		     reuses the hot-water heater path). QuickStepper's `inc()` +
		     click-to-type `commit()` both clamp to these bounds, so the
		     props are *enforced*, not decorative. -->
		<QuickStepper
			value={p.flushTemp}
			name="Flush temperature"
			dimension="temp"
			min={b.flushTempC.min}
			max={b.flushTempC.max}
			step={b.flushTempC.step}
			onChange={(v) => params.set('flushTemp', v)}
		/>
		<div style="height:6px"></div>
		<QuickChipRow
			options={[88, 92, 95, 97, 99]}
			value={p.flushTemp}
			name="Flush temperature"
			dimension="temp"
			onChange={(v) => params.set('flushTemp', v)}
		/>
	{/if}
</div>
