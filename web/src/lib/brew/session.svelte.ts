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

import type { BrewRecipe, BrewSessionSummary } from '$lib/core/crema-core';

export type GuidedBrewPhase = 'idle' | 'armed' | 'running' | 'paused' | 'done';

export class GuidedBrewStore {
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
	}

	started(nowMs: number): void {
		this.phase = 'running';
		this.startedAtMs = nowMs;
		this.stepStartedAtMs = 0;
	}

	stepChanged(stepIndex: number, atMs: number): void {
		this.stepIndex = stepIndex;
		this.stepStartedAtMs = atMs;
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
	}
}

let store: GuidedBrewStore | null = null;
export function getGuidedBrewStore(): GuidedBrewStore {
	store ??= new GuidedBrewStore();
	return store;
}
