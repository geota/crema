/**
 * `#lib/visualizer/barista-pending.js` — shots whose barista was edited in
 * Crema and has not reached Visualizer yet.
 *
 * Only these shots' edit PATCHes carry `barista` (final review 06 #1), and a
 * pull must not fill a barista into them: a clear whose PATCH failed leaves
 * the local barista blank, and the pull's fill-on-blank rule then wrote the
 * remote name back, so the next edit sent it and the clear was undone on both
 * sides (round-2 verification V3 #6, V2 P3-4). The set is persisted, so a
 * restart or another tab keeps the mark until a PATCH carrying the current
 * value lands. Android keeps the same set in `VisualizerState.baristaPending`.
 *
 * Reads go to storage each time (another tab may have changed it). Only a
 * mark that could not be persisted (storage unavailable or full) is kept in
 * memory instead, so a PATCH that lands in another tab clears a persisted
 * mark here too (round-3 clean check C3 #8).
 */

import { readJson, writeJson } from '../utils/storage.ts';

/** localStorage key (cleared by a factory reset with every other `crema.*` key). */
export const BARISTA_PENDING_KEY = 'crema.visualizer.baristaPending.v1';

const isIdList = (v: unknown): v is string[] =>
	Array.isArray(v) && v.every((x) => typeof x === 'string');

/** The persisted pending set, with a memory fallback for marks storage refused. */
export class BaristaPending {
	/** Marks this tab could not persist. */
	readonly #memory = new Set<string>();

	#stored(): string[] {
		return readJson<string[]>(BARISTA_PENDING_KEY, [], { valid: isIdList });
	}

	/** Whether `id`'s barista edit has not reached Visualizer yet. */
	has(id: string): boolean {
		return this.#memory.has(id) || this.#stored().includes(id);
	}

	/** Mark `id`'s barista as edited here. */
	add(id: string): void {
		const ids = this.#stored();
		if (!ids.includes(id)) writeJson(BARISTA_PENDING_KEY, [...ids, id]);
		if (!this.#stored().includes(id)) this.#memory.add(id);
	}

	/** `id`'s barista reached Visualizer (or the shot is gone). */
	delete(id: string): void {
		this.#memory.delete(id);
		const ids = this.#stored();
		if (ids.includes(id)) writeJson(BARISTA_PENDING_KEY, ids.filter((x) => x !== id));
	}
}
