/**
 * `#lib/visualizer/poison.js` — rows that keep failing to pull (core
 * `PoisonEntry`): counted at most once a day, and only when another detail
 * succeeded in the run, so an outage poisons nothing. At three a row stops
 * holding the cursor and is tried once a day, alone.
 */

import type { PoisonEntry } from '#lib/core/crema-core.js';
import { readJson, writeJson } from '../utils/storage.ts';

/** localStorage key (cleared by a factory reset with every other `crema.*` key). */
export const POISON_KEY = 'crema.visualizer.rowFailures.v1';

const isEntries = (v: unknown): v is PoisonEntry[] =>
	Array.isArray(v) && v.every((e) => typeof (e as PoisonEntry)?.id === 'string');

export function readPoison(): PoisonEntry[] {
	return readJson<PoisonEntry[]>(POISON_KEY, [], { valid: isEntries });
}

export function writePoison(entries: PoisonEntry[]): void {
	writeJson(POISON_KEY, entries);
}
