/**
 * `#lib/history/light-rows.js` — rows held without their heavy parts (#310 P3w).
 *
 * From P3w the history keeps every row, but only the rows in view carry their
 * samples, profile and brew series in memory. The others are "light": their
 * `record.samples` is empty although the store holds a curve. A light row
 * read as a row with no telemetry goes wrong quietly (SPEC §4): a new de-dup
 * signature, a measured row that looks manual, a backfill over the shot's own
 * curve. So the store names every light row here, with its summary, and the
 * helpers that look at samples ask first.
 *
 * Keyed by id: an edit makes a new row object, and the row stays light.
 */
import type { ShotSummary } from '#lib/core/crema-core.js';

const light = new Map<string, ShotSummary>();
/** Rows whole in memory with no samples, whose stored summary still has their yield and peaks. */
const curveless = new Map<string, ShotSummary>();

/** `id`'s row is in memory without its heavy parts; `summary` describes them. */
export function markLight(id: string, summary: ShotSummary): void {
	light.set(id, summary);
}

/** `id`'s row carries its heavy parts again, or has left the history. */
export function markLoaded(id: string): void {
	light.delete(id);
}

/** The summary of `id` while its row is light, else undefined. */
export function lightSummary(id: string): ShotSummary | undefined {
	return light.get(id);
}

/** Forget every light row: a new store, or a test. */
export function clearLight(): void {
	light.clear();
	curveless.clear();
}

/**
 * `id` is whole in memory but has no samples, and its summary still holds
 * the yield, peaks and chart it had: a row recovered from the daily copy,
 * whose curve was in the lost store. Read only while it has no samples.
 */
export function markCurveless(id: string, summary: ShotSummary): void {
	curveless.set(id, summary);
}

/** The summary that describes `id`'s yield and peaks while memory has no samples for it. */
export function storedSummary(id: string): ShotSummary | undefined {
	return light.get(id) ?? curveless.get(id);
}

/** How many machine samples `shot` has, in memory or, for a light row, in the store. */
export function sampleCountOf(shot: { id: string; record: { samples: readonly unknown[] } }): number {
	if (shot.record.samples.length > 0) return shot.record.samples.length;
	return light.get(shot.id)?.sampleCount ?? 0;
}

/** Whether `shot` has a profile in the store that memory doesn't hold. */
export function lightHasHeavyParts(id: string): boolean {
	const s = light.get(id);
	return s != null && ((s.sampleCount ?? 0) > 0 || s.brewSampleCount > 0);
}
