/**
 * `#lib/history/summary.js` — a row's summary, from the core (#310 P1's
 * `shot_summary`): sample counts, peaks and the spark, worked out from the
 * whole shot when it's saved, migrated or restored.
 */
import type { ShotSummary } from '#lib/core/crema-core.js';
import { shotSummary } from '#lib/wasm/de1_wasm.js';
import type { StoredShot } from './model';

/**
 * The summary of a whole shot. A shot the core can't read still gets one, with
 * its counts and no peaks, so one odd row can't stop a save or a migration.
 */
export function summarize(shot: StoredShot): ShotSummary {
	try {
		return JSON.parse(shotSummary(JSON.stringify(shot))) as ShotSummary;
	} catch {
		return {
			v: 1,
			sampleCount: shot.record.samples.length,
			brewSampleCount: shot.brewSeries?.samples.length ?? 0,
			peakPressure: 0,
			peakTemp: 0
		};
	}
}
