/**
 * `$lib/history/telemetry-wire` — bidirectional codec between the
 * chart-friendly {@link TelemetrySample} shape and the wire-shape
 * {@link TimedSample}.
 *
 * The shell persists history in the Rust wire shape (camelCase, nested
 * `sample`, ms `elapsed`) — same bytes the wasm bridge emits and the
 * Visualizer / v2-export consumes. At record time the orchestrator's
 * `ShotCompleted` path calls {@link toWire} to convert the live
 * TelemetrySample buffer; at read time the history chart calls
 * {@link fromWire} (indirectly via `flatSamplesOf`) to project the
 * stored samples back onto the chart shape.
 *
 * The two functions are forward / inverse pairs over every
 * load-bearing field. The `telemetry_wire_round_trip` test pins the
 * contract — adding a sample channel to one direction without the
 * other will fail the test.
 *
 * **Known hazard** (out of scope here, flagged for the Rust side):
 * `ShotSample` declares `setHeadTemp` / `setGroupPressure` /
 * `setGroupFlow` as `number` (not nullable), so {@link toWire} zero-
 * fills any absent value. A truly absent setpoint becomes `0` on
 * round-trip rather than staying absent. Fixing this means relaxing
 * the Rust struct to `Option<f32>` and re-typeshareing; it would
 * cascade into every reader. The codec respects the type contract as
 * declared today.
 */

import type { ShotSample, TimedSample } from '$lib/core';
import type { TelemetrySample } from '$lib/state';

/**
 * Convert a live {@link TelemetrySample} into the persisted wire shape.
 * Called by the orchestrator at `ShotCompleted` to write the buffered
 * series into a `StoredShot.record.samples`.
 *
 * Three nested-sample fields the live buffer doesn't track
 * (`sampleTime`, `setMixTemp`, `frameNumber`) zero-fill — non-load-
 * bearing for chart playback, only present for v2 `.shot.json` cross-
 * app compatibility (where they're optional / zero on the wire).
 */
export function toWire(t: TelemetrySample): TimedSample {
	const sample: ShotSample = {
		sampleTime: 0,
		groupPressure: t.pressure,
		groupFlow: t.flow,
		headTemp: t.temp,
		mixTemp: t.mixTemp,
		setHeadTemp: t.setHeadTemp ?? 0,
		setMixTemp: 0,
		setGroupPressure: t.setGroupPressure ?? 0,
		setGroupFlow: t.setGroupFlow ?? 0,
		frameNumber: 0,
		steamTemp: t.steamTemp
	};
	const out: TimedSample = { elapsed: t.elapsed, sample };
	if (t.weight != null) out.scaleWeight = t.weight;
	if (t.weightFlow != null) out.scaleFlowWeight = t.weightFlow;
	if (t.dispensedVolume != null) out.dispensedVolume = t.dispensedVolume;
	if (t.resistance != null) out.resistance = t.resistance;
	if (t.resistanceWeight != null) out.resistanceWeight = t.resistanceWeight;
	return out;
}

/**
 * Project a persisted {@link TimedSample} back onto the chart-
 * friendly {@link TelemetrySample} shape. Pure inverse of {@link toWire}:
 * the resistance-from-pressure-and-flow fallback that the history
 * chart applies lives in `flatSamplesOf`, NOT here — this is strictly
 * the cell-by-cell projection.
 *
 * Scale fields are returned as `null` when absent (or non-positive,
 * for `weight` / `weightFlow` / `resistance` / `resistanceWeight` —
 * legacy data flows through with `0` as a sentinel for "no signal";
 * the chart treats `null` and `0` the same).
 */
export function fromWire(t: TimedSample): TelemetrySample {
	const sw = t.scaleWeight;
	const sfw = t.scaleFlowWeight;
	const dv = t.dispensedVolume;
	const r = t.resistance;
	const rw = t.resistanceWeight;
	return {
		elapsed: t.elapsed,
		pressure: t.sample.groupPressure,
		flow: t.sample.groupFlow,
		temp: t.sample.headTemp,
		mixTemp: t.sample.mixTemp,
		steamTemp: t.sample.steamTemp,
		weight: sw != null && sw > 0 ? sw : null,
		weightFlow: sfw != null && sfw > 0 ? sfw : null,
		dispensedVolume: dv != null && dv > 0 ? dv : 0,
		resistance: r != null && r > 0 ? r : null,
		resistanceWeight: rw != null && rw > 0 ? rw : null,
		setHeadTemp: t.sample.setHeadTemp,
		setGroupPressure: t.sample.setGroupPressure,
		setGroupFlow: t.sample.setGroupFlow
	};
}
