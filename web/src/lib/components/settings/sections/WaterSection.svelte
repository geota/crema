<script lang="ts">
	/**
	 * Water & maintenance section — filter life, descaling, backflush, and the
	 * water-chemistry block.
	 *
	 * ## Real vs. stubbed
	 *
	 * **UI-only** — real filter / descale tracking needs the DE1's water-volume
	 * counters, which the web shell does not expose; the maintenance cards show
	 * the design's placeholder figures and "Mark complete" is a stub. The
	 * water-chemistry inputs are faithful UI; they are not app preferences the
	 * rest of the app reads, so they are kept as local state with a `// TODO`.
	 */
	import StSectionHead from '../StSectionHead.svelte';
	import StGroup from '../StGroup.svelte';
	import StRow from '../StRow.svelte';
	import StSegment from '../StSegment.svelte';
	import StValueChip from '../StValueChip.svelte';
	import StMaintenanceCard from '../StMaintenanceCard.svelte';

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
		state="Replace in 12 days"
		stateOk={true}
		metric="74%"
		metricLabel="capacity left"
		detail="3.4 L pulled this week · last replaced 18 May"
	/>
	<StMaintenanceCard
		icon="snowflake"
		title="Descale cycle"
		state="Overdue by 4 days"
		stateOk={false}
		metric="142 L"
		metricLabel="since last descale"
		detail="Threshold: 120 L · last descaled 14 Mar"
	/>
	<StMaintenanceCard
		icon="sparkle"
		title="Backflush"
		state="Due tomorrow"
		stateOk={true}
		metric="48 hr"
		metricLabel="since last cycle"
		detail="Recommended every 48 hr of active shots"
	/>
</div>

<StGroup
	title="Water chemistry"
	sub="Tap or bottled? Crema uses this to estimate scale buildup."
>
	<StRow title="Water source">
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
	>
		{#snippet control()}
			<StValueChip
				value={hardness}
				suffix=" ppm"
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
	>
		{#snippet control()}
			<StValueChip
				value={tds}
				suffix=" ppm"
				step={1}
				min={0}
				max={1000}
				onCommit={(v) => (tds = v)}
			/>
		{/snippet}
	</StRow>
</StGroup>
