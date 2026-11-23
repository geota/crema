<script lang="ts">
	/**
	 * `ManualBrew` — Brew's Manual page on wide screens (brew modes §3,
	 * board WideModePourOver): the guided brew in the dashboard's frame.
	 *
	 * - Left column: today's timer card, then the recipe's steps (the
	 *   current one highlighted) over the scale line.
	 * - Tiles: Weight / Flow, then the current step.
	 * - Chart: weight and flow from the scale against the steps' targets.
	 *
	 * The header, the foot (Hot water, Start brew) and the log dialog stay in
	 * `BrewDashboard`, which owns the shared {@link GuidedRun}.
	 */
	import BrewSessionChart from '#lib/components/history/BrewSessionChart.svelte';
	import { kindLabel, type GuidedRun } from '#lib/brew/guided-run.svelte.js';
	import { convertWeight, getSettingsStore } from '#lib/settings/index.js';
	import ExtractionTimer from '../ExtractionTimer.svelte';
	import ChannelReadout from '../ChannelReadout.svelte';
	import ManualStepCard from './ManualStepCard.svelte';
	import ManualSteps from './ManualSteps.svelte';
	import ManualScaleLine from './ManualScaleLine.svelte';

	let {
		run,
		connected,
		weightG,
		flowGs,
		onTare,
		tareFlash = false
	}: {
		run: GuidedRun;
		connected: boolean;
		/** Live net weight, grams. */
		weightG: number | null;
		/** Live scale flow, g/s. */
		flowGs: number | null;
		/** Tap the weight tile to tare (no brew running). */
		onTare?: () => void;
		tareFlash?: boolean;
	} = $props();

	const prefs = $derived(getSettingsStore().current);
	const weightM = $derived(convertWeight(connected ? weightG : null, prefs.weightUnit));
	const targetM = $derived(convertWeight(run.currentStep?.targetWaterG ?? null, prefs.weightUnit));

	const timerCaption = $derived.by(() => {
		if (run.phase === 'idle') return 'Ready';
		if (run.phase === 'armed') return 'Pour to start';
		if (run.phase === 'paused') return 'Paused';
		if (run.phase === 'done') return 'Finished';
		return kindLabel(run.currentStep) || 'Brewing';
	});
	const seconds = $derived(
		run.phase === 'done' && run.session.summary ? run.session.summary.durationMs / 1000 : run.elapsedMs / 1000
	);
	const flowVal = $derived(connected && flowGs != null && Number.isFinite(flowGs) ? flowGs.toFixed(1) : '—');
</script>

<div class="crema-dash-main mb">
	<div class="crema-dash-timercol">
		<ExtractionTimer {seconds} step={timerCaption} />
		<ManualSteps steps={run.steps} current={run.phase === 'done' ? null : run.stepIndex}>
			{#snippet footer()}
				<ManualScaleLine {run} {connected} {onTare} />
			{/snippet}
		</ManualSteps>
	</div>
	<div class="crema-dash-chartcol">
		<div class="crema-readouts mb-tiles">
			<ChannelReadout
				icon="scales"
				label="WEIGHT"
				value={weightM.value}
				unit={weightM.unit}
				color="var(--tel-weight)"
				target={run.currentStep?.targetWaterG != null && run.phase !== 'done' ? targetM.value : undefined}
				secondaryLabel="FLOW"
				secondaryValue={flowVal}
				secondaryUnit="g/s"
				secondaryColor="var(--tel-weight-2)"
				onclick={connected && (run.idle || run.phase === 'armed') ? onTare : undefined}
				flash={tareFlash}
			/>
			<div class="mb-step"><ManualStepCard {run} {connected} {weightG} /></div>
		</div>
		<div class="crema-chart mb-chart">
			<BrewSessionChart series={run.session.liveSeries} extentMs={run.idle ? 0 : run.elapsedMs} height={300} />
			{#if run.idle}
				<span class="mb-chart-note">Weight and flow from the scale, against each step's target</span>
			{/if}
		</div>
	</div>
</div>

<style>
	.mb-step {
		grid-column: 2 / -1;
		display: flex;
		min-width: 0;
	}
	.mb-step > :global(.msc) {
		flex: 1;
	}
	@container brew-chartcol (max-width: 440px) {
		.mb-step {
			grid-column: 1 / -1;
		}
	}
	/* The steps scroll inside their card, so Manual keeps Espresso's height. */
	@media (min-width: 821px) {
		.mb {
			flex: 1 1 0;
			grid-template-rows: minmax(0, 1fr);
		}
	}
	.mb .crema-dash-timercol {
		min-height: 0;
	}
	.mb :global(.ms) {
		min-height: 160px;
	}
	.mb-chart {
		position: relative;
		display: flex;
		align-items: center;
	}
	.mb-chart > :global(svg) {
		width: 100%;
	}
	.mb-chart-note {
		position: absolute;
		left: 50%;
		top: 50%;
		transform: translate(-50%, -50%);
		padding: 4px 12px;
		border-radius: 999px;
		background: var(--bg-page);
		font-family: var(--font-sans);
		font-size: 12px;
		color: var(--fg-3);
		pointer-events: none;
		max-width: 80%;
		text-align: center;
	}
</style>
