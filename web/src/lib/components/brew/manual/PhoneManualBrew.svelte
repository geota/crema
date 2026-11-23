<script lang="ts">
	/**
	 * `PhoneManualBrew` — Brew's Manual page on the phone (brew modes §3,
	 * board PhoneModePourOver): the current-step card (step n of N, the
	 * clock, weight against the target, the note), then the steps over the
	 * scale line. Start brew sits in the foot, in `BrewDashboard`.
	 */
	import type { GuidedRun } from '#lib/brew/guided-run.svelte.js';
	import ManualStepCard from './ManualStepCard.svelte';
	import ManualSteps from './ManualSteps.svelte';
	import ManualScaleLine from './ManualScaleLine.svelte';

	let {
		run,
		connected,
		weightG,
		onTare
	}: {
		run: GuidedRun;
		connected: boolean;
		/** Live net weight, grams. */
		weightG: number | null;
		onTare?: () => void;
	} = $props();
</script>

<div class="pmb">
	<ManualStepCard {run} {connected} {weightG} phone />
	<ManualSteps steps={run.steps} current={run.phase === 'done' ? null : run.stepIndex}>
		{#snippet footer()}
			<ManualScaleLine {run} {connected} {onTare} />
		{/snippet}
	</ManualSteps>
</div>

<style>
	.pmb {
		display: flex;
		flex-direction: column;
		gap: 12px;
		min-height: 0;
	}
</style>
