/**
 * `$lib/history/telemetry-wire.test` — node:test suite pinning the
 * {@link toWire} / {@link fromWire} pair as a forward/inverse codec.
 *
 * Same runner style as `shot-sync.test.ts`. Invoked with:
 *
 * ```
 * cd web && node \
 *   --experimental-strip-types \
 *   --experimental-detect-module \
 *   --test src/lib/history/telemetry-wire.test.ts
 * ```
 *
 * The contract under test: for any `TelemetrySample` whose fields
 * conform to the published shape, `fromWire(toWire(t))` round-trips
 * every load-bearing field. Adding a sample channel to one direction
 * without the other fails the round-trip assertion.
 */

import assert from 'node:assert/strict';
import { describe, it } from 'vitest';
import type { TelemetrySample } from '../state/ui-state.svelte.ts';
import { fromWire, toWire } from './telemetry-wire.ts';

// ── Fixtures ─────────────────────────────────────────────────────────

function sample(over: Partial<TelemetrySample> = {}): TelemetrySample {
	return {
		elapsed: 1234,
		pressure: 9.0,
		flow: 1.5,
		temp: 92.5,
		mixTemp: 90.0,
		steamTemp: 135.0,
		weight: 12.4,
		weightFlow: 0.8,
		dispensedVolume: 12.0,
		resistance: 4.0,
		resistanceWeight: 14.0625,
		setHeadTemp: 92.0,
		setGroupPressure: 9.0,
		setGroupFlow: 2.0,
		...over
	};
}

// ── Tests ────────────────────────────────────────────────────────────

describe('toWire / fromWire round-trip', () => {
	it('preserves every load-bearing field on a complete sample', () => {
		const t = sample();
		assert.deepEqual(fromWire(toWire(t)), t);
	});

	it('preserves null scale fields when the live sample lacks a scale', () => {
		const t = sample({
			weight: null,
			weightFlow: null,
			resistance: null,
			resistanceWeight: null
		});
		// `weightFlow` is `number | null | undefined`; `fromWire` returns
		// `null` for absent/zero, so the round-trip pins `null`.
		const back = fromWire(toWire(t));
		assert.equal(back.weight, null);
		assert.equal(back.weightFlow, null);
		assert.equal(back.resistance, null);
		assert.equal(back.resistanceWeight, null);
	});

	it('round-trips dispensedVolume of zero as zero (no signal sentinel)', () => {
		const t = sample({ dispensedVolume: 0 });
		const back = fromWire(toWire(t));
		assert.equal(back.dispensedVolume, 0);
	});

	it('round-trips dispensedVolume of a positive value verbatim', () => {
		const t = sample({ dispensedVolume: 12.5 });
		const back = fromWire(toWire(t));
		assert.equal(back.dispensedVolume, 12.5);
	});

	it('drops the wire scale fields that the live sample omits', () => {
		// A pre-scale-channel TelemetrySample has `weight: null`; the wire
		// shape must omit the optional fields (not zero-fill them).
		const t = sample({
			weight: null,
			weightFlow: null,
			dispensedVolume: undefined,
			resistance: null,
			resistanceWeight: null
		});
		const wire = toWire(t);
		assert.equal(wire.scaleWeight, undefined);
		assert.equal(wire.scaleFlowWeight, undefined);
		assert.equal(wire.dispensedVolume, undefined);
		assert.equal(wire.resistance, undefined);
		assert.equal(wire.resistanceWeight, undefined);
	});

	it('zero-fills the three unused-by-live nested fields on the wire', () => {
		// `sampleTime` / `setMixTemp` / `frameNumber` are present in the
		// Rust struct but absent from the live `TelemetrySample`. They
		// must zero-fill (matching the v2 .shot.json convention).
		const wire = toWire(sample());
		assert.equal(wire.sample.sampleTime, 0);
		assert.equal(wire.sample.setMixTemp, 0);
		assert.equal(wire.sample.frameNumber, 0);
	});

	it('round-trips the setpoint fields (load-bearing for the goal-line overlay)', () => {
		const t = sample({ setHeadTemp: 95.0, setGroupPressure: 8.5, setGroupFlow: 1.7 });
		const back = fromWire(toWire(t));
		assert.equal(back.setHeadTemp, 95.0);
		assert.equal(back.setGroupPressure, 8.5);
		assert.equal(back.setGroupFlow, 1.7);
	});

	it('elapsed is carried through unchanged', () => {
		const t = sample({ elapsed: 25_000 });
		const back = fromWire(toWire(t));
		assert.equal(back.elapsed, 25_000);
	});
});

describe('fromWire scale-field zero-as-sentinel', () => {
	it('treats a zero scaleWeight as null (legacy zero-fill data)', () => {
		const back = fromWire({
			elapsed: 100,
			sample: {
				sampleTime: 0,
				groupPressure: 9,
				groupFlow: 1,
				headTemp: 92,
				mixTemp: 90,
				setHeadTemp: 92,
				setMixTemp: 0,
				setGroupPressure: 9,
				setGroupFlow: 2,
				frameNumber: 0,
				steamTemp: 135
			},
			scaleWeight: 0,
			scaleFlowWeight: 0
		});
		assert.equal(back.weight, null);
		assert.equal(back.weightFlow, null);
	});
});
