<script lang="ts">
	/**
	 * `ManualStepCard` — the current step on Brew's Manual page (brew modes
	 * §3): "Step n of N · Auto", the step and its target, the note and the
	 * next step. While a brew runs it shows the step's progress (weight
	 * against the target, else the time left) with Pause and Skip; armed, the
	 * pour-to-start hint with Cancel; done, the result with Discard.
	 *
	 * It flashes on every visual cue (always on, whatever the sound setting).
	 * The phone variant also carries the brew clock.
	 */
	import PauseIcon from 'phosphor-svelte/lib/PauseIcon';
	import PlayIcon from 'phosphor-svelte/lib/PlayIcon';
	import { StepAdvance } from '#lib/core/crema-core.js';
	import { brewClock } from '#lib/brew/recipes.svelte.js';
	import { kindLabel, stepNote, stepSpec, type GuidedRun } from '#lib/brew/guided-run.svelte.js';

	let {
		run,
		connected,
		weightG,
		phone = false
	}: {
		run: GuidedRun;
		connected: boolean;
		/** Live net weight, grams. */
		weightG: number | null;
		/** The phone's card: the clock on top. */
		phone?: boolean;
	} = $props();

	const session = $derived(run.session);
	const step = $derived(run.currentStep);
	const live = $derived(run.phase === 'running' || run.phase === 'paused');
	const eyebrow = $derived.by(() => {
		if (run.phase === 'armed') return 'Ready · pour to start';
		if (run.phase === 'done') return 'Brew finished';
		if (!step) return 'No steps';
		const adv = step.advance === StepAdvance.Manual ? 'Tap' : 'Auto';
		return `Step ${run.stepIndex + 1} of ${run.steps.length} · ${adv}`;
	});
	/** "Bloom to 50 g"; the step's time shows as its progress, and in the list. */
	const title = $derived(
		step ? (step.targetWaterG != null ? `${kindLabel(step)} to ${Math.round(step.targetWaterG)} g` : kindLabel(step)) : ''
	);
	const sub = $derived.by(() => {
		const parts: string[] = [];
		const note = stepNote(step);
		if (note) parts.push(note);
		if (live && run.expectedStep && run.expectedLeftMs <= 0) parts.unshift("Skip when it's drained");
		const next = run.nextStep;
		parts.push(next ? `next: ${kindLabel(next)} ${stepSpec(next)}` : 'last step');
		return parts.join(' · ');
	});
	/** Weight against the step's target, when a scale reports. */
	const showWeight = $derived(live && step?.targetWaterG != null && connected && weightG != null);
</script>

<div
	class={[
		'msc',
		{
			'is-phone': phone,
			'is-cue-step': run.flash === 'step' || run.flash === 'boundary',
			'is-cue-approach': run.flash === 'approach'
		}
	]}
	data-cue={run.flash ?? undefined}
	aria-live="polite"
>
	<div class="msc-text">
		<span class="msc-eyebrow">{eyebrow}</span>
		{#if phone && run.phase !== 'done'}
			<span class={['msc-clock', { 'is-paused': run.phase === 'paused' }]}
				>{brewClock(run.elapsedMs)}{#if run.estimateLabel}<span class="msc-of">{` of about ${run.estimateLabel}`}</span>{/if}</span
			>
		{/if}
		{#if run.phase === 'armed'}
			<span class="msc-title">Pour to start</span>
			<span class="msc-sub">The clock begins at the first water.</span>
		{:else if run.phase === 'done' && session.summary}
			<span class="msc-title">{session.summary.recipeName}</span>
			<span class="msc-sub"
				>{brewClock(session.summary.durationMs)}{#if session.summary.finalWeightG != null}
					· {Math.round(session.summary.finalWeightG)} g{/if}
				· {session.summary.series.stageMarks.length} steps</span
			>
		{:else if step}
			<span class="msc-title-row">
				<span class="msc-title">{title}</span>
				{#if showWeight && step.targetWaterG != null && weightG != null}
					<span class="msc-big"
						>{Math.max(0, weightG).toFixed(0)}<span class="msc-of">{` / ${Math.round(step.targetWaterG)} g`}</span></span
					>
				{:else if live && run.expectedStep && run.expectedLeftMs > 0}
					<span class="msc-big"
						><span class="msc-of">about&nbsp;</span>{brewClock(run.expectedLeftMs)}<span class="msc-of">&nbsp;left</span></span
					>
				{:else if live && run.expectedStep}
					<span class="msc-big is-over">+{brewClock(-run.expectedLeftMs)}<span class="msc-of">&nbsp;over</span></span>
				{:else if live && step.durationS != null}
					<span class="msc-big"
						>{brewClock(Math.max(0, step.durationS * 1000 - run.stepElapsedMs))}<span class="msc-of">&nbsp;left</span></span
					>
				{/if}
			</span>
			<span class="msc-sub">{sub}</span>
			{#if live && run.stepProgress != null && !(run.expectedStep && run.expectedLeftMs <= 0)}
				<span class="msc-bar" aria-hidden="true"
					><span class="msc-bar-fill" style:width="{(run.stepProgress * 100).toFixed(1)}%"></span></span
				>
			{/if}
		{/if}
	</div>
	{#if live}
		<div class="msc-actions">
			<button
				type="button"
				class="msc-round"
				aria-label={run.phase === 'paused' ? 'Resume' : 'Pause'}
				onclick={() => void run.togglePause()}
			>
				{#if run.phase === 'paused'}<PlayIcon weight="fill" aria-hidden="true" />{:else}<PauseIcon
						weight="fill"
						aria-hidden="true"
					/>{/if}
			</button>
			<button type="button" class="msc-text-btn" onclick={() => void run.skip()}>Skip ›</button>
		</div>
	{:else if run.phase === 'armed'}
		<div class="msc-actions">
			<button type="button" class="msc-text-btn" onclick={() => void run.cancel()}>Cancel</button>
		</div>
	{:else if run.phase === 'done'}
		<div class="msc-actions">
			<button type="button" class="msc-text-btn" onclick={() => run.discard()}>Discard</button>
		</div>
	{/if}
</div>

<style>
	.msc {
		display: flex;
		align-items: center;
		gap: 16px;
		min-width: 0;
		padding: 14px 18px;
		background: var(--bg-surface);
		border-radius: 16px;
		box-shadow: inset 0 1px 0 rgba(var(--tint-rgb), 0.04);
	}
	.msc-text {
		display: flex;
		flex-direction: column;
		gap: 3px;
		min-width: 0;
		flex: 1;
	}
	.msc-eyebrow {
		font-family: var(--font-sans);
		font-size: 11px;
		font-weight: 600;
		letter-spacing: 0.14em;
		text-transform: uppercase;
		color: var(--fg-accent);
	}
	.msc-title {
		font-family: var(--font-serif);
		font-size: 22px;
		line-height: 1.15;
		color: var(--fg-1);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.msc-sub {
		font-family: var(--font-sans);
		font-size: 13px;
		color: var(--fg-3);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.msc-title-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 16px;
		min-width: 0;
	}
	.msc-big {
		flex: none;
		font-family: var(--font-mono);
		font-size: 22px;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
	}
	.msc-big.is-over {
		color: var(--warning);
	}
	.msc-of {
		font-size: 13px;
		color: var(--fg-muted);
	}
	.msc-clock {
		font-family: var(--font-mono);
		font-size: 40px;
		line-height: 1.1;
		font-variant-numeric: tabular-nums;
		color: var(--fg-1);
	}
	.msc-clock.is-paused {
		color: var(--fg-muted);
	}
	.msc-bar {
		display: block;
		height: 5px;
		border-radius: 999px;
		background: rgba(var(--tint-rgb), 0.08);
		overflow: hidden;
		margin-top: 4px;
	}
	.msc-bar-fill {
		display: block;
		height: 100%;
		background: var(--tel-weight);
		transition: width 180ms linear;
	}
	.msc-actions {
		display: flex;
		align-items: center;
		gap: 10px;
		flex: none;
	}
	.msc-round {
		width: 44px;
		height: 44px;
		border-radius: 50%;
		border: 1px solid rgba(var(--tint-rgb), 0.16);
		background: rgba(var(--tint-rgb), 0.04);
		color: var(--fg-1);
		display: inline-flex;
		align-items: center;
		justify-content: center;
		font-size: 18px;
		cursor: pointer;
	}
	.msc-text-btn {
		min-height: 44px;
		border: 0;
		background: transparent;
		color: var(--fg-2);
		font-family: var(--font-sans);
		font-size: 14px;
		cursor: pointer;
		padding: 0 8px;
	}
	.msc-round:focus-visible,
	.msc-text-btn:focus-visible {
		outline: 2px solid var(--copper-400);
		outline-offset: 2px;
	}
	.msc.is-phone {
		align-items: flex-start;
	}
	/* Visual cues: always on, whatever the sound / haptics settings. */
	.msc.is-cue-step {
		animation: msc-cue-flash 900ms var(--ease) 1;
	}
	.msc.is-cue-approach {
		animation: msc-cue-approach 900ms var(--ease) 1;
	}
	@keyframes msc-cue-flash {
		20% {
			background: color-mix(in srgb, var(--copper-500) 22%, var(--bg-surface));
			box-shadow: 0 0 0 4px color-mix(in srgb, var(--copper-500) 45%, transparent);
		}
	}
	@keyframes msc-cue-approach {
		30% {
			box-shadow: 0 0 0 3px color-mix(in srgb, var(--tel-weight) 55%, transparent);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.msc.is-cue-step,
		.msc.is-cue-approach {
			animation: none;
			outline: 2px solid var(--copper-400);
		}
	}
</style>
