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
 * Reads go to storage each time (another tab may have changed it); a memory
 * copy keeps the session working where storage is unavailable.
 */

import { readJson, writeJson } from '../utils/storage.ts';

/** localStorage key (cleared by a factory reset with every other `crema.*` key). */
export const BARISTA_PENDING_KEY = 'crema.visualizer.baristaPending.v1';

const isIdList = (v: unknown): v is string[] =>
	Array.isArray(v) && v.every((x) => typeof x === 'string');

/** The persisted pending set, with a per-session memory copy. */
export class BaristaPending {
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
		this.#memory.add(id);
		const ids = this.#stored();
		if (!ids.includes(id)) writeJson(BARISTA_PENDING_KEY, [...ids, id]);
	}

	/** `id`'s barista reached Visualizer (or the shot is gone). */
	delete(id: string): void {
		this.#memory.delete(id);
		const ids = this.#stored();
		if (ids.includes(id)) writeJson(BARISTA_PENDING_KEY, ids.filter((x) => x !== id));
	}
}
