<script lang="ts">
	/**
	 * Water & maintenance section — filter life, descaling, backflush, and the
	 * water-chemistry block.
	 *
	 * ## Real vs. stubbed
	 *
	 * **Real** — the maintenance cards are driven by `$lib/maintenance`: the
	 * DE1 has no water-volume counter, so the shell integrates group flow over
	 * time (the de1app's approach) into a persisted litre total. The filter
	 * capacity, litres-since-descale and hours-since-backflush all derive from
	 * that counter plus the stored intervals; "Mark complete" rebaselines the
	 * relevant counter.
	 *
	 * **UI-only** — the water-chemistry inputs are faithful UI; they are not
	 * app preferences the rest of the app reads, so they are kept as local
	 * state with a `// TODO`.
	 */
	import { getMaintenanceStore } from '$lib/maintenance';
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSegment from '../StSegment.svelte';
	import StStepper from '../StStepper.svelte';
	import StMaintenanceCard from '../StMaintenanceCard.svelte';

	const maintenance = getMaintenanceStore();
	/** The derived filter / descale / backflush readouts — reactive. */
	const readout = $derived(maintenance.readout);
	/** The persisted maintenance state — for the intervals and last-done dates. */
	const m = $derived(maintenance.current);

	/** Format a Unix-ms timestamp as a short `18 May` date. */
	function shortDate(ms: number): string {
		return new Date(ms).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });
	}

	// TODO: water chemistry is not yet wired into a store the app reads; local
	// state only, so the segment / chips feel real while previewing the IA.
	let waterSource = $state('bottled');
	let hardness = $state(38);
	let tds = $state(148);
</script>

<StSectionHead
	eyebrow="Health"
	title="Water & maintenance"
	sub="Track filter life, descaling, and water chemistry. Crema reminds you before things start affecting taste."
/>

<div class="st-maint-grid">
	<StMaintenanceCard
		icon="funnel"
		title="Water filter"
		state={readout.filterOk
			? `${Math.round(readout.filterPercent)}% capacity left`
			: 'Replace now'}
		stateOk={readout.filterOk}
		metric={`${Math.round(readout.filterPercent)}%`}
		metricLabel="capacity left"
		detail={`${readout.filterUsedLitres.toFixed(1)} L of ${m.filterCapacityLitres} L used · last replaced ${shortDate(m.filterAtMs)}`}
		onComplete={() => maintenance.markFilterReplaced()}
	/>
	<StMaintenanceCard
		icon="snowflake"
		title="Descale cycle"
		state={readout.descaleOk ? 'On schedule' : 'Descale due'}
		stateOk={readout.descaleOk}
		metric={`${readout.descaleSinceLitres.toFixed(0)} L`}
		metricLabel="since last descale"
		detail={`Threshold: ${m.descaleIntervalLitres} L · last descaled ${shortDate(m.descaleAtMs)}`}
		onComplete={() => maintenance.markDescaled()}
	/>
	<StMaintenanceCard
		icon="sparkle"
		title="Backflush"
		state={readout.backflushOk ? 'On schedule' : 'Backflush due'}
		stateOk={readout.backflushOk}
		metric={`${readout.backflushSinceHours} hr`}
		metricLabel="since last cycle"
		detail={`Recommended every ${m.backflushIntervalHours} hr · last done ${shortDate(m.backflushAtMs)}`}
		onComplete={() => maintenance.markBackflushed()}
	/>
</div>

<StGroup
	title="Water chemistry"
	sub="Tap or bottled? Crema uses this to estimate scale buildup."
>
	<StRow title="Water source" notImplemented>
		{#snippet control()}
			<StSegment
				value={waterSource}
				options={[
					{ value: 'tap', label: 'Tap' },
					{ value: 'filtered', label: 'Filtered' },
					{ value: 'bottled', label: 'Bottled' },
					{ value: 'rpavlis', label: 'Custom (RPavlis)' }
				]}
				onChange={(v) => (waterSource = v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Hardness (ppm CaCO₃)"
		sub="Helps schedule descaling cycles accurately."
		notImplemented
	>
		{#snippet control()}
			<StStepper
				value={hardness}
				unit="ppm"
				step={1}
				min={0}
				max={500}
				onCommit={(v) => (hardness = v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Total dissolved solids"
		sub="Affects extraction; optional but useful for tuning."
		notImplemented
	>
		{#snippet control()}
			<StStepper
				value={tds}
				unit="ppm"
				step={1}
				min={0}
				max={1000}
				onCommit={(v) => (tds = v)}
			/>
		{/snippet}
	</StRow>
</StGroup>

<!--
	Maintenance intervals — the three thresholds the maintenance cards above
	compare their counters against. Persisted via the maintenance store; each
	stepper writes through its setter so a change takes effect immediately.
	The litre values stay in canonical L (the maintenance store's native
	unit); a future unit-aware variant of the `volume` dimension would let
	these display as fl-oz for users on imperial units.
-->
<StGroup
	title="Maintenance intervals"
	sub="When the maintenance cards above flip to 'due'. Adjust to your filter spec, water source, and usage."
>
	<StRow
		title="Filter capacity"
		sub="Replace the inline filter after this many litres pass through it."
	>
		{#snippet control()}
			<StStepper
				value={m.filterCapacityLitres}
				unit="L"
				step={5}
				min={5}
				max={500}
				onCommit={(v) => maintenance.setFilterCapacity(v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Descale interval"
		sub="Run a descale after this many litres dispensed."
	>
		{#snippet control()}
			<StStepper
				value={m.descaleIntervalLitres}
				unit="L"
				step={10}
				min={10}
				max={1000}
				onCommit={(v) => maintenance.setDescaleInterval(v)}
			/>
		{/snippet}
	</StRow>
	<StRow
		title="Backflush interval"
		sub="Run a backflush after this many hours of use."
	>
		{#snippet control()}
			<StStepper
				value={m.backflushIntervalHours}
				unit="hr"
				step={1}
				min={1}
				max={500}
				onCommit={(v) => maintenance.setBackflushInterval(v)}
			/>
		{/snippet}
	</StRow>
</StGroup>
