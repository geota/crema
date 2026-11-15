/**
 * `#lib/history/light-rows.js` — the store's rows held without their heavy
 * parts (#310 P3w, P4w).
 *
 * The store keeps every row in memory, but from P4w a row carries its
 * samples, profile and brew series only while they aren't written yet: a new
 * shot, a curve a write couldn't take. Every other row is "light": its
 * `record.samples` is empty although the store holds a curve. Inside the
 * store a light row read as a row with no telemetry would go wrong quietly
 * (SPEC §4: a new de-dup signature, a measured row that looks manual, a
 * backfill over the shot's own curve), so the store names every light row
 * here, with its summary.
 *
 * Nothing outside the store reads this: the store hands out `ShotRow`s,
 * which carry their summary and no samples at all (`model.ts`).
 *
 * Keyed by id: an edit makes a new row object, and the row stays light.
 */
import type { ShotSummary } from '#lib/core/crema-core.js';

const light = new Map<string, ShotSummary>();

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
}

/**
 * How many machine samples the store's own row `shot` has, in memory or, for
 * a light row, in the store. `null` when that's unknown.
 */
export function heldSampleCount(shot: { id: string; record: { samples: readonly unknown[] } }): number | null {
	if (shot.record.samples.length > 0) return shot.record.samples.length;
	const s = light.get(shot.id);
	return s ? (s.sampleCount ?? null) : 0;
}
