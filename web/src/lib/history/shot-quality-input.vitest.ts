/**
 * `$lib/history/shot-quality-input.vitest` — vitest suite pinning the
 * {@link qualityInputFromShot} projection: `StoredShot` → the core's
 * `ShotQualityInput` (phase-marker reconstruction, goal series, the
 * ms→s conversion, and the too-few-samples null).
 */

import assert from 'node:assert/strict';
import { describe, it } from 'vitest';
import type { ShotSample, TimedSample } from '$lib/core';
import type { StoredShot } from './model.ts';
import { qualityInputFromShot } from './shot-quality-input.ts';

// ── Fixtures ─────────────────────────────────────────────────────────

function shotSample(over: Partial<ShotSample> = {}): ShotSample {
	return {
		sampleTime: 0,
		groupPressure: 8.5,
		groupFlow: 2.0,
		headTemp: 92.0,
		mixTemp: 90.0,
		setMixTemp: 90.0,
		setHeadTemp: 92.0,
		setGroupPressure: 9.0,
		setGroupFlow: 0.0,
		frameNumber: 0,
		steamTemp: 130.0,
		...over
	};
}

/**
 * A two-frame synthetic pull, 500 ms cadence: frame 0 (samples 0–9,
 * 0–4.5 s) is flow-commanded (preinfusion-shaped), frame 1 (samples
 * 10–19, 5–9.5 s) is pressure-commanded. Scale weight rides on every
 * even sample only, so the weight series must skip the odd ones.
 */
function twoFrameSamples(): TimedSample[] {
	const out: TimedSample[] = [];
	for (let i = 0; i < 20; i++) {
		const preinfusing = i < 10;
		out.push({
			elapsed: i * 500,
			sample: shotSample({
				frameNumber: preinfusing ? 0 : 1,
				groupPressure: preinfusing ? 1.5 : 8.5,
				groupFlow: preinfusing ? 4.0 : 2.0,
				setGroupPressure: preinfusing ? 0.0 : 9.0,
				setGroupFlow: preinfusing ? 4.0 : 0.0
			}),
			...(i % 2 === 0 ? { scaleWeight: i * 1.5 } : {})
		});
	}
	return out;
}

function storedShot(over: Partial<StoredShot> = {}): StoredShot {
	return {
		formatVersion: 3,
		id: 'shot:test',
		completedAt: 1_750_000_000_000,
		profileName: 'Test profile',
		profile: {
			steps: [{ duration_seconds: 20 }, { duration_seconds: 40 }],
			preinfuse_step_count: 1,
			target_weight: 36,
			beverage_type: 'espresso'
		},
		metadata: {},
		record: { duration: 10_000, samples: twoFrameSamples() },
		...over
	};
}

// ── Tests ────────────────────────────────────────────────────────────

describe('qualityInputFromShot', () => {
	it('returns null under 10 samples', () => {
		const short = storedShot({
			record: { duration: 4_000, samples: twoFrameSamples().slice(0, 9) }
		});
		assert.equal(qualityInputFromShot(short), null);
	});

	it('reconstructs Start / Pour / End phase markers from the frame numbers', () => {
		const input = qualityInputFromShot(storedShot());
		assert.ok(input);
		assert.equal(input.phases.length, 3);

		const [start, pour, end] = input.phases;
		// Synthetic Decenza-style start: t=0, frame 0, flow-commanded.
		assert.deepEqual(start, {
			timeS: 0,
			label: 'Start',
			frameNumber: 0,
			isFlowMode: true,
			transitionReason: ''
		});
		// The 0→1 transition lands on sample 10 (elapsed 5000 ms). Frame 1
		// is at/past preinfuse_step_count=1, so it is the Pour, and its
		// median goals (9 bar / 0 ml/s) read as pressure mode.
		assert.deepEqual(pour, {
			timeS: 5,
			label: 'Pour',
			frameNumber: 1,
			isFlowMode: false,
			transitionReason: ''
		});
		// Closing marker at the shot duration, on the last observed frame.
		assert.deepEqual(end, {
			timeS: 10,
			label: 'End',
			frameNumber: 1,
			isFlowMode: false,
			transitionReason: ''
		});
	});

	it('labels the first transition Pour under the no-recipe heuristic', () => {
		const input = qualityInputFromShot(storedShot({ profile: null }));
		assert.ok(input);
		assert.equal(input.phases[1].label, 'Pour');
		// No recipe → the unknown sentinels + untrusted flow goal.
		assert.equal(input.firstFrameConfiguredS, -1);
		assert.equal(input.expectedFrameCount, -1);
		assert.equal(input.profileKbResolved, false);
	});

	it('maps the goal series off the commanded set-points, in seconds', () => {
		const input = qualityInputFromShot(storedShot());
		assert.ok(input);
		// One goal point per sample, t = elapsed / 1000.
		assert.equal(input.pressureGoal.length, 20);
		assert.equal(input.flowGoal.length, 20);
		assert.deepEqual(input.pressureGoal[3], { t: 1.5, v: 0.0 });
		assert.deepEqual(input.flowGoal[3], { t: 1.5, v: 4.0 });
		assert.deepEqual(input.pressureGoal[12], { t: 6, v: 9.0 });
		assert.deepEqual(input.flowGoal[12], { t: 6, v: 0.0 });
		// Measured series convert the same way.
		assert.deepEqual(input.pressure[0], { t: 0, v: 1.5 });
		assert.deepEqual(input.flow[19], { t: 9.5, v: 2.0 });
	});

	it('skips samples without a scale weight and totals off the recipe', () => {
		const input = qualityInputFromShot(storedShot());
		assert.ok(input);
		// Weight rode on the 10 even samples only.
		assert.equal(input.weight.length, 10);
		assert.deepEqual(input.weight[1], { t: 1, v: 3 });
		// Final weight = the last scale reading (sample 18 → 27 g); target
		// falls back to the recipe (no per-shot yieldTarget on the fixture).
		assert.equal(input.finalWeightG, 27);
		assert.equal(input.targetWeightG, 36);
		// Recipe-known scalars.
		assert.equal(input.durationS, 10);
		assert.equal(input.firstFrameConfiguredS, 20);
		assert.equal(input.expectedFrameCount, 2);
		assert.equal(input.beverageType, 'espresso');
		assert.deepEqual(input.analysisFlags, []);
		assert.equal(input.profileKbResolved, true);
	});
});
