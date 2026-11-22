/**
 * `#lib/brew/grind-session.svelte.js` — Brew's grind value (#488, review F9):
 * per-device session state for the next shot, persisted so a reload neither
 * drops an unsaved change nor fakes a saved one. A bag or grinder pick resets
 * it (to the pick order's grind, when there is one); only Save, the bag
 * editor or auto-save write a bag's saved grind.
 *
 * `null` = no grind on Brew yet: the dial shows a placeholder, a shot records
 * no grind, and Save has no grind to save.
 */

import { followOtherTabs, readJson, writeJson } from '#lib/utils/storage.js';
import { grindNumber } from '#lib/bean/grinder.js';

/** The storage key: one value per device. */
export const BREW_GRIND_KEY = 'crema.brew.grind.v1';

const isGrind = (v: unknown): boolean => v === null || (typeof v === 'number' && Number.isFinite(v));

export class BrewGrindSession {
	#value = $state.raw<number | null>(readJson<number | null>(BREW_GRIND_KEY, null, { valid: isGrind }));

	constructor() {
		followOtherTabs(BREW_GRIND_KEY, isGrind, (v) => {
			this.#value = v as number | null;
		});
	}

	/** Brew's grind now, or `null` for none. */
	get value(): number | null {
		return this.#value;
	}

	/** Set Brew's grind (the dial, or a pick's grind); `null` clears it. */
	set(next: number | null): void {
		const v = next !== null && Number.isFinite(next) ? next : null;
		if (v === this.#value) return;
		this.#value = v;
		writeJson(BREW_GRIND_KEY, v);
	}

	/** Take a pick's grind as stored ("14", "480"); one that isn't a number leaves the dial as it is. */
	setText(grind: string | null | undefined): void {
		if (grind == null) {
			this.set(null);
			return;
		}
		const n = grindNumber(grind);
		if (n !== null) this.set(n);
	}
}

let session: BrewGrindSession | null = null;

/** The app's Brew grind. */
export function getBrewGrind(): BrewGrindSession {
	return (session ??= new BrewGrindSession());
}

/** Test seam. */
export function resetBrewGrindForTest(): void {
	session = null;
}
