/**
 * `$lib/history/shot-quality-input` — project a persisted {@link StoredShot}
 * onto the core's `ShotQualityInput`, the argument to the wasm bridge's
 * `analyze_shot_quality` (`de1_domain::shot_quality::analyze_shot`).
 *
 * Pure projection — no wasm, no stores — so the History detail can build the
 * input synchronously and the mapping stays unit-testable without the bridge.
 * All time axes convert from the stored millisecond `elapsed` / `duration`
 * to the analysis's seconds-from-extraction-start.
 *
 * ## Phase markers
 *
 * The core wants Decenza-style `PhaseMarker`s, which Crema never stored —
 * shots only carry the per-sample `frame_number`. This module reconstructs
 * them: a synthetic `Start` marker at t=0 (frame 0, mirroring Decenza's
 * `shotanalysis.cpp:667-672`), one marker per observed frame transition, and
 * a closing `End` marker at the shot duration.
 *
 * Labels come from the recipe when the shot snapshotted one (frames below
 * `preinfuse_step_count` → `Preinfusion`, the first frame at/past it →
 * `Pour`, later frames → `Frame N`). **No-recipe heuristic:** without a
 * recipe (older shots, imports without profile JSON) the preinfusion
 * boundary is unknowable, so the first transition to a frame ≥ 1 is labeled
 * `Pour` as a best-effort pour anchor — frame 0 is overwhelmingly the
 * preinfusion-ish opening step in practice — and later transitions fall
 * back to `Frame N`.
 *
 * Per-frame `isFlowMode` is inferred from the commanded goals over that
 * frame's samples: median `set_group_flow` > 0.2 with median
 * `set_group_pressure` < 0.2 reads as a flow-driven frame. `transitionReason`
 * is emitted as `""` — reconstructed markers can't know it — and the core
 * infers it from `frameExits` (the recipe's per-step exit specs) so the
 * confirmed-exit suppression in skip-first-frame detection works
 * (Decenza #1421).
 */

import type { PhaseMarker, Profile, SeriesPoint, ShotQualityInput } from '$lib/core';
import type { StoredShot } from './model';

/**
 * Fewest stored samples worth analyzing. Below this the report would be
 * the insufficient-data early return anyway, so the caller can skip the
 * bridge round-trip (and hide the card) outright.
 */
const MIN_SAMPLES = 10;

/**
 * Median goal thresholds for the flow-vs-pressure frame-mode call — a
 * frame is flow-driven when the machine was commanding meaningful flow
 * (> 0.2 ml/s) and essentially no pressure (< 0.2 bar).
 */
const FLOW_MODE_FLOW_FLOOR = 0.2;
const FLOW_MODE_PRESSURE_CEIL = 0.2;

/** Median of a non-empty array (mean of the two middles on even length). */
function median(values: number[]): number {
	const sorted = [...values].sort((a, b) => a - b);
	const mid = Math.floor(sorted.length / 2);
	return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/**
 * The shot's snapshotted recipe as a core-shape {@link Profile}, or `null`.
 * `StoredShot.profile` is `unknown` on the shell model (it round-trips the
 * core Profile JSON written at completion time), so validate the one field
 * the mapping leans on — a non-empty `steps` array — before trusting it.
 */
function recipeOf(shot: StoredShot): Profile | null {
	const p = shot.profile;
	if (!p || typeof p !== 'object') return null;
	const candidate = p as Partial<Profile>;
	if (!Array.isArray(candidate.steps) || candidate.steps.length === 0) return null;
	return candidate as Profile;
}

/**
 * Build the `ShotQualityInput` for a stored shot, or `null` when the shot
 * has fewer than {@link MIN_SAMPLES} samples (nothing worth analyzing —
 * callers hide the quality card).
 */
export function qualityInputFromShot(shot: StoredShot): ShotQualityInput | null {
	const samples = shot.record.samples;
	if (samples.length < MIN_SAMPLES) return null;

	const recipe = recipeOf(shot);
	// `preinfuse_step_count` may be absent on tolerantly-decoded older
	// snapshots; treat "recipe without a count" like "no recipe" for the
	// label heuristic only (the other recipe fields still apply).
	const preinfuseCount =
		recipe && typeof recipe.preinfuse_step_count === 'number'
			? recipe.preinfuse_step_count
			: null;

	const pressure: SeriesPoint[] = [];
	const flow: SeriesPoint[] = [];
	const weight: SeriesPoint[] = [];
	const pressureGoal: SeriesPoint[] = [];
	const flowGoal: SeriesPoint[] = [];

	// Per-frame commanded-goal accumulators for the frame-mode call, plus
	// the frame transitions observed in the telemetry (time-ordered).
	const frameFlowGoals = new Map<number, number[]>();
	const framePressureGoals = new Map<number, number[]>();
	const transitions: Array<{ timeS: number; frameNumber: number }> = [];
	let currentFrame: number | null = null;

	for (const s of samples) {
		const t = s.elapsed / 1000;
		pressure.push({ t, v: s.sample.groupPressure });
		flow.push({ t, v: s.sample.groupFlow });
		// Scale overlay is optional on the wire — a shot pulled without a
		// paired scale carries no `scaleWeight`; skip those points so the
		// weight series stays empty (the analysis tolerates that).
		if (s.scaleWeight != null) weight.push({ t, v: s.scaleWeight });
		pressureGoal.push({ t, v: s.sample.setGroupPressure });
		flowGoal.push({ t, v: s.sample.setGroupFlow });

		const frame = s.sample.frameNumber;
		let flows = frameFlowGoals.get(frame);
		let pressures = framePressureGoals.get(frame);
		if (!flows || !pressures) {
			flows = [];
			pressures = [];
			frameFlowGoals.set(frame, flows);
			framePressureGoals.set(frame, pressures);
		}
		flows.push(s.sample.setGroupFlow);
		pressures.push(s.sample.setGroupPressure);

		if (currentFrame === null) {
			currentFrame = frame;
		} else if (frame !== currentFrame) {
			transitions.push({ timeS: t, frameNumber: frame });
			currentFrame = frame;
		}
	}

	/** Whether `frame` was flow-driven, from the median commanded goals. */
	const isFlowMode = (frame: number): boolean => {
		const flows = frameFlowGoals.get(frame);
		const pressures = framePressureGoals.get(frame);
		if (!flows || flows.length === 0 || !pressures || pressures.length === 0) return false;
		return (
			median(flows) > FLOW_MODE_FLOW_FLOOR && median(pressures) < FLOW_MODE_PRESSURE_CEIL
		);
	};

	// Label the transition markers (the synthetic Start/End are fixed).
	// With a recipe: Preinfusion below the preinfuse count, Pour on the
	// first frame at/past it, Frame N after. Without: the no-recipe
	// heuristic documented in the module header.
	let pourSeen = false;
	const labelFor = (frameNumber: number): string => {
		if (preinfuseCount !== null) {
			if (frameNumber < preinfuseCount) return 'Preinfusion';
			if (!pourSeen) {
				pourSeen = true;
				return 'Pour';
			}
			return `Frame ${frameNumber}`;
		}
		if (!pourSeen && frameNumber >= 1) {
			pourSeen = true;
			return 'Pour';
		}
		return `Frame ${frameNumber}`;
	};

	const durationS = shot.record.duration / 1000;
	const lastFrame = samples[samples.length - 1].sample.frameNumber;
	const phases: PhaseMarker[] = [
		// Decenza's synthetic extraction-start marker: always "Start",
		// always frame 0 (shotanalysis.cpp:667-672). Mode falls back to
		// the first *observed* frame when frame 0 never sampled.
		{
			timeS: 0,
			label: 'Start',
			frameNumber: 0,
			isFlowMode: isFlowMode(
				frameFlowGoals.has(0) ? 0 : samples[0].sample.frameNumber
			),
			transitionReason: ''
		},
		...transitions.map((tr) => ({
			timeS: tr.timeS,
			label: labelFor(tr.frameNumber),
			frameNumber: tr.frameNumber,
			isFlowMode: isFlowMode(tr.frameNumber),
			transitionReason: ''
		})),
		{
			timeS: durationS,
			label: 'End',
			frameNumber: lastFrame,
			isFlowMode: isFlowMode(lastFrame),
			transitionReason: ''
		}
	];

	// Final in-cup weight: the last scale reading when one exists, else the
	// user-journaled yield, else 0 (which disables the yield arms). Target:
	// the stop-on-weight target the user dialed, falling back to the
	// recipe's own target weight.
	const finalWeightG =
		weight.length > 0 ? weight[weight.length - 1].v : (shot.metadata.yieldOut ?? 0);
	const targetWeightG = shot.yieldTarget ?? recipe?.target_weight ?? 0;

	return {
		pressure,
		flow,
		weight,
		pressureGoal,
		flowGoal,
		phases,
		// The recipe knows what it brews (filter / cleaning shots skip the
		// puck-integrity detectors); default to espresso when unknown.
		beverageType: recipe?.beverage_type ?? 'espresso',
		durationS,
		// Tolerant of older snapshots whose steps decoded without a
		// duration — `-1` is the analysis's "unknown" sentinel.
		firstFrameConfiguredS:
			typeof recipe?.steps[0]?.duration_seconds === 'number'
				? recipe.steps[0].duration_seconds
				: -1,
		targetWeightG,
		finalWeightG,
		expectedFrameCount: recipe ? recipe.steps.length : -1,
		// Per-profile analysis flags (grind_check_skip, …) are a Decenza
		// knowledge-base concept Crema profiles don't carry; none apply.
		analysisFlags: [],
		// A snapshotted recipe with steps is Crema's equivalent of a
		// resolved profile shape — grind Arm 1 may trust the flow goal.
		profileKbResolved: recipe !== null,
		// Per-frame exit specs so the core can infer the transitionReason
		// these reconstructed markers can't carry — without them a fill
		// frame exiting early on its pressure target reads as "First step
		// skipped" every shot (Decenza #1421).
		frameExits: recipe
			? recipe.steps.map((step) => ({
					metric: step.exit?.metric ?? '',
					exitOver: step.exit?.compare === 'over',
					threshold: step.exit?.threshold ?? 0,
					maxDurationS:
						typeof step.duration_seconds === 'number' ? step.duration_seconds : -1
				}))
			: []
	};
}
