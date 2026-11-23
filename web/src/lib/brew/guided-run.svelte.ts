/**
 * `#lib/brew/guided-run` — the guided brew as Brew's Manual page drives it
 * (brew modes §3; issue #10's engine).
 *
 * The core's `BrewSessionMonitor` owns the truth and `GuidedBrewStore`
 * mirrors it; this is the view logic on top that the wide and phone Manual
 * pages share: the display clock, the step card's cue flash, the step
 * presentation, the commands and the log prefill.
 *
 * Construct a {@link GuidedRun} during a component's initialisation: it
 * starts the effects that tick the clock and replay the flash.
 */

import type { BrewRecipe, BrewStep } from '#lib/core/crema-core.js';
import { BrewStepKind } from '#lib/core/crema-core.js';
import type { CremaApp } from '#lib/state/index.js';
import { getBeanStore } from '#lib/bean/index.js';
import { guidedLogPrefill, resolveBrewBean } from './bean-pick';
import { primeBrewCues } from './cues';
import type { LogBrewPrefill } from './methods';
import {
	brewClock,
	getRecipeStore,
	hasExpectedDuration,
	recipeEstimateLabel
} from './recipes.svelte';
import { getGuidedBrewStore, type BrewVisualCue } from './session.svelte';

const KIND_LABEL: Record<string, string> = {
	[BrewStepKind.Bloom]: 'Bloom',
	[BrewStepKind.Pour]: 'Pour',
	[BrewStepKind.Wait]: 'Wait',
	[BrewStepKind.Steep]: 'Steep',
	[BrewStepKind.Stir]: 'Stir',
	[BrewStepKind.Press]: 'Press',
	[BrewStepKind.Drawdown]: 'Drawdown',
	[BrewStepKind.Other]: 'Step'
};

/** "Bloom", "Pour", … for the step's kind. */
export function kindLabel(step: BrewStep | undefined): string {
	return step ? (KIND_LABEL[step.kind] ?? 'Step') : '';
}

/** The step's own instruction, else its kind. */
export function stepLabel(step: BrewStep | undefined): string {
	if (!step) return '';
	return step.label?.trim() || kindLabel(step);
}

/** The step's own instruction, when it has one. */
export function stepNote(step: BrewStep | undefined): string | null {
	return step?.label?.trim() || null;
}

/** "to 50 g · 0:45", "about 0:55 · until you tap", "until you tap". */
export function stepSpec(step: BrewStep | undefined): string {
	if (!step) return '';
	const parts: string[] = [];
	if (step.targetWaterG != null) parts.push(`to ${Math.round(step.targetWaterG)} g`);
	// A drawdown's expected time is guidance: "about 0:55 · until you tap".
	if (hasExpectedDuration(step)) parts.push(`about ${brewClock((step.durationS ?? 0) * 1000)}`, 'until you tap');
	else if (step.durationS != null) parts.push(brewClock(step.durationS * 1000));
	if (parts.length === 0) parts.push('until you tap');
	return parts.join(' · ');
}

/** What a {@link GuidedRun} reads from its page. */
export interface GuidedRunInputs {
	app: () => CremaApp | null;
	/** The recipe Brew has selected (the run's recipe once it starts). */
	recipe: () => BrewRecipe | null;
	/** A scale is connected. */
	connected: () => boolean;
	/** Live net weight, grams. */
	weightG: () => number | null;
}

export class GuidedRun {
	readonly #in: GuidedRunInputs;
	readonly session = getGuidedBrewStore();
	/** Wait for the first pour before the clock starts (with a scale). */
	startOnPour = $state(true);
	/** The step card's flash, replayed on every visual cue. */
	flash = $state<BrewVisualCue | null>(null);
	/** The display clock; shell and core share `performance.now()`. */
	nowMs = $state(typeof performance !== 'undefined' ? performance.now() : 0);

	constructor(inputs: GuidedRunInputs) {
		this.#in = inputs;
		let seenCueSeq = this.session.cueSeq;
		$effect(() => {
			const seq = this.session.cueSeq;
			if (seq === seenCueSeq) return;
			seenCueSeq = seq;
			const kind = this.session.cueKind;
			this.flash = null;
			// Re-add the class on the next frame so the animation replays.
			const raf = requestAnimationFrame(() => (this.flash = kind));
			const t = setTimeout(() => (this.flash = null), 1000);
			return () => {
				cancelAnimationFrame(raf);
				clearTimeout(t);
			};
		});
		$effect(() => {
			const phase = this.session.phase;
			if (phase === 'running' || phase === 'paused' || phase === 'armed') {
				const t = setInterval(() => (this.nowMs = performance.now()), 200);
				return () => clearInterval(t);
			}
		});
	}

	get phase() {
		return this.session.phase;
	}

	/** Idle: the setup shows (the selected recipe, Start brew). */
	get idle(): boolean {
		return this.session.phase === 'idle';
	}

	/** The run's recipe while one runs, else the selected one. */
	readonly recipe: BrewRecipe | null = $derived.by(() => this.session.recipe ?? this.#in.recipe());
	readonly steps: readonly BrewStep[] = $derived(this.recipe?.steps ?? []);
	readonly stepIndex: number = $derived(this.session.phase === 'idle' ? 0 : this.session.stepIndex);
	readonly currentStep: BrewStep | undefined = $derived(this.steps[this.stepIndex]);
	readonly nextStep: BrewStep | undefined = $derived(this.steps[this.stepIndex + 1]);
	readonly elapsedMs: number = $derived(this.session.elapsedMs(this.nowMs));
	readonly stepElapsedMs: number = $derived(this.session.stepElapsedMs(this.nowMs));
	/** "3:00", "2:45+" or null: the recipe's estimated total. */
	readonly estimateLabel: string | null = $derived(this.recipe ? recipeEstimateLabel(this.recipe) : null);
	/** The current step's time is an expectation (a drawdown). */
	readonly expectedStep: boolean = $derived(hasExpectedDuration(this.currentStep));
	/** ms left of an expected step; negative once past it. */
	readonly expectedLeftMs: number = $derived(
		this.expectedStep ? (this.currentStep?.durationS ?? 0) * 1000 - this.stepElapsedMs : 0
	);

	/** The current step's 0..1 progress: weight against target, else time. */
	readonly stepProgress: number | null = $derived.by(() => {
		const s = this.currentStep;
		if (!s || this.session.phase === 'idle') return null;
		const w = this.#in.weightG();
		if (s.targetWaterG != null && this.#in.connected() && w != null) {
			return Math.min(1, Math.max(0, w / s.targetWaterG));
		}
		if (s.durationS != null) return Math.min(1, this.stepElapsedMs / (s.durationS * 1000));
		return null;
	});

	/** The brew's bag: the active one, unless the session picked another. */
	readonly beanId: string | null = $derived(resolveBrewBean(this.session.beanPick, getBeanStore()));

	/** The finished session, pre-shaped for the log form. */
	readonly logPrefill: LogBrewPrefill | undefined = $derived.by(() => {
		const s = this.session.summary;
		if (!s || !this.recipe) return undefined;
		return guidedLogPrefill(s, this.recipe, this.beanId);
	});

	/** Start the selected recipe: armed on the first pour, or now. */
	async start(): Promise<boolean> {
		const app = this.#in.app();
		const recipe = this.#in.recipe();
		if (!app || !recipe) return false;
		primeBrewCues();
		getRecipeStore().touch(recipe);
		const armOnPour = this.startOnPour && this.#in.connected();
		await app.brewSessionArm(recipe, armOnPour);
		if (!armOnPour) await app.brewSessionBegin();
		return true;
	}

	async beginNow(): Promise<void> {
		await this.#in.app()?.brewSessionBegin();
	}

	async cancel(): Promise<void> {
		await this.#in.app()?.brewSessionCancel();
	}

	async togglePause(): Promise<void> {
		if (this.session.phase === 'paused') await this.#in.app()?.brewSessionResume();
		else await this.#in.app()?.brewSessionPause();
	}

	async skip(): Promise<void> {
		await this.#in.app()?.brewSessionSkip();
	}

	async finish(): Promise<void> {
		await this.#in.app()?.brewSessionFinish();
	}

	/** Drop the finished session without saving. */
	discard(): void {
		this.session.reset();
	}
}
