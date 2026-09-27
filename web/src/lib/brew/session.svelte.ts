/**
 * `$lib/brew/session` — the guided-brew session's SHELL state
 * (issue #10).
 *
 * The core's `BrewSessionMonitor` owns the truth (step boundaries,
 * cues, the recording); this store holds what the Scale page's Brew
 * segment renders between events: the recipe being run, the phase, the
 * current step, and — because shell and core share the same
 * `performance.now()` timebase — enough timestamps to draw the clock
 * without asking the core every frame.
 *
 * Written by `CremaApp` (commands + event routing); read by the UI.
 */

import type {
	BrewRecipe,
	BrewSample,
	BrewSeries,
	BrewSessionSummary,
	StageMark
} from '$lib/core/crema-core';
import { stageMarkForJson } from '$lib/wasm/de1_wasm';
import type { BeanPick } from './bean-pick';

/** A step at least this long (s) releases the screen wake lock. */
export const LONG_STEP_S = 15 * 60;

/** The live chart's sample cap — ~18 min at the 4 Hz session tick. */
export const LIVE_SAMPLE_CAP = 4500;

/** A visual cue kind — the step card flashes on every one, regardless of
 *  the sound / haptics settings (visual cues are always on). */
export type BrewVisualCue = 'approach' | 'boundary' | 'step';

export type GuidedBrewPhase = 'idle' | 'armed' | 'running' | 'paused' | 'done';

export class GuidedBrewStore {
	/**
	 * The Brew setup's bean pick (issue #10 feedback) — a setup selection,
	 * so it lives here rather than in the panel and survives a remount
	 * (the recipe editor, a layout swap) and a finished session.
	 * `undefined` follows the active bag; see `resolveBrewBean`.
	 * Never reset by {@link reset}, and never written to the active bag.
	 */
	beanPick = $state<BeanPick>(undefined);
	phase = $state<GuidedBrewPhase>('idle');
	recipe = $state<BrewRecipe | null>(null);
	startOnPour = $state(false);
	stepIndex = $state(0);
	/** `performance.now()` the clock started; null before Started. */
	startedAtMs = $state<number | null>(null);
	/** Session-elapsed ms the current step began (from BrewStepChanged). */
	stepStartedAtMs = $state(0);
	/** Accumulated paused time, ms. */
	pausedAccumMs = $state(0);
	/** `performance.now()` the current pause began; null while running. */
	pausedSinceMs = $state<number | null>(null);
	/** The finished session's summary, until saved or discarded. */
	summary = $state<BrewSessionSummary | null>(null);
	/**
	 * The live weight curve for the session chart, sampled on the session
	 * tick while running with a scale connected. Display-only: the
	 * recording that gets saved is the core's (`summary.series`).
	 */
	liveSamples = $state.raw<BrewSample[]>([]);
	/** Live step boundaries (session-elapsed ms), for the chart's bands. */
	liveMarks = $state.raw<StageMark[]>([]);
	/** Bumps on every visual cue so the step card can replay its flash. */
	cueSeq = $state(0);
	/** The latest visual cue's kind. */
	cueKind = $state<BrewVisualCue | null>(null);

	/**
	 * Whether the live session should hold the screen awake. Yes while it
	 * is armed / running / paused — a step timer behind a dark screen is a
	 * missed pour — except during a long step (15 min or more, e.g. the
	 * cold brew's 12-hour steep): nothing needs watching then, the step
	 * waits for a tap, and holding the screen for hours would drain the
	 * battery.
	 */
	get wantsWakeLock(): boolean {
		if (this.phase !== 'armed' && this.phase !== 'running' && this.phase !== 'paused') {
			return false;
		}
		const step = this.recipe?.steps?.[this.stepIndex];
		return !(step?.durationS != null && step.durationS >= LONG_STEP_S);
	}

	/** The live curve as a {@link BrewSeries} for the chart. */
	get liveSeries(): BrewSeries {
		return { samples: this.liveSamples, stageMarks: this.liveMarks };
	}

	/** Record one live weight sample (no-op unless running). */
	sample(nowMs: number, weightG: number | null, flowGs: number | null): void {
		if (this.phase !== 'running' || weightG == null || !Number.isFinite(weightG)) return;
		if (this.liveSamples.length >= LIVE_SAMPLE_CAP) return;
		const s: BrewSample = { elapsedMs: Math.round(this.elapsedMs(nowMs)), weightG };
		if (flowGs != null && Number.isFinite(flowGs)) s.flowGS = flowGs;
		this.liveSamples = [...this.liveSamples, s];
	}

	/** Flash the step card (visual cue — always on). */
	cue(kind: BrewVisualCue): void {
		this.cueKind = kind;
		this.cueSeq += 1;
	}

	/** Session time at wall-clock `nowMs`, pauses excluded. */
	elapsedMs(nowMs: number): number {
		if (this.startedAtMs == null) return 0;
		const effective = this.pausedSinceMs ?? nowMs;
		return Math.max(0, effective - this.startedAtMs - this.pausedAccumMs);
	}

	/** Time inside the current step at `nowMs`. */
	stepElapsedMs(nowMs: number): number {
		return Math.max(0, this.elapsedMs(nowMs) - this.stepStartedAtMs);
	}

	// ── Transitions (driven by CremaApp) ─────────────────────────

	armed(recipe: BrewRecipe, startOnPour: boolean): void {
		this.phase = 'armed';
		this.recipe = recipe;
		this.startOnPour = startOnPour;
		this.stepIndex = 0;
		this.startedAtMs = null;
		this.stepStartedAtMs = 0;
		this.pausedAccumMs = 0;
		this.pausedSinceMs = null;
		this.summary = null;
		this.clearLive();
	}

	started(nowMs: number): void {
		this.phase = 'running';
		this.startedAtMs = nowMs;
		this.stepStartedAtMs = 0;
		this.liveMarks = [this.markAt(0, 0)];
	}

	stepChanged(stepIndex: number, atMs: number): void {
		this.stepIndex = stepIndex;
		this.stepStartedAtMs = atMs;
		if (!this.liveMarks.some((m) => m.stepIndex === stepIndex)) {
			this.liveMarks = [...this.liveMarks, this.markAt(stepIndex, atMs)];
		}
	}

	/**
	 * A live stage mark carrying the step's planned water target — the
	 * core's `stage_mark_for`, the same rule the session engine stamps on
	 * the saved record, so the live chart's planned staircase matches the
	 * saved one.
	 */
	private markAt(stepIndex: number, atMs: number): StageMark {
		const r = this.recipe;
		if (r != null) {
			try {
				return JSON.parse(stageMarkForJson(JSON.stringify(r), stepIndex, atMs)) as StageMark;
			} catch {
				// A malformed recipe only loses the overlay, never the session.
			}
		}
		return { elapsedMs: atMs, stepIndex };
	}

	private clearLive(): void {
		this.liveSamples = [];
		this.liveMarks = [];
		this.cueKind = null;
	}

	paused(nowMs: number): void {
		if (this.phase === 'running') {
			this.phase = 'paused';
			this.pausedSinceMs = nowMs;
		}
	}

	resumed(nowMs: number): void {
		if (this.phase === 'paused' && this.pausedSinceMs != null) {
			this.pausedAccumMs += nowMs - this.pausedSinceMs;
			this.pausedSinceMs = null;
			this.phase = 'running';
		}
	}

	completed(summary: BrewSessionSummary): void {
		this.phase = 'done';
		this.summary = summary;
	}

	/** Choose the setup's bag (`null` = No bean). */
	pickBean(id: string | null): void {
		this.beanPick = id;
	}

	reset(): void {
		this.phase = 'idle';
		this.recipe = null;
		this.startOnPour = false;
		this.stepIndex = 0;
		this.startedAtMs = null;
		this.stepStartedAtMs = 0;
		this.pausedAccumMs = 0;
		this.pausedSinceMs = null;
		this.summary = null;
		this.clearLive();
	}
}

let store: GuidedBrewStore | null = null;
export function getGuidedBrewStore(): GuidedBrewStore {
	store ??= new GuidedBrewStore();
	return store;
}
