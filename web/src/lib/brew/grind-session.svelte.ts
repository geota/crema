/**
 * `#lib/brew/grind-session.svelte.js` — Brew's grind value (#488, review F9):
 * per-device session state for the next shot, persisted so a reload neither
 * drops an unsaved change nor fakes a saved one. A bag or grinder pick resets
 * it (to the pick order's grind, when there is one); only Save, the bag
 * editor or auto-save write a bag's saved grind.
 *
 * Brew's grind is text, as the core reads it (`de1_domain::brew_grind_view`):
 * a dial value on the scale's step ("2.25", "480") or a grind that isn't a
 * plain number ("2.1.3"), picked or typed. `null` = not set (DESIGN "No grind
 * set is its own state"): Brew shows it blank, a shot records no grind, and
 * Save has no grind to save. Never a placeholder number.
 */

import { followOtherTabs, readJson, writeJson } from '#lib/utils/storage.js';
import { DEFAULT_GRIND_SCALE, grindText, type GrindScale } from '#lib/bean/grinder.js';

/** The storage key: one value per device. */
export const BREW_GRIND_KEY = 'crema.brew.grind.v1';

/** A stored value: text, `null`, or a number written before grinds were text. */
const isStored = (v: unknown): boolean =>
	v === null || typeof v === 'string' || (typeof v === 'number' && Number.isFinite(v));

/**
 * A stored value as Brew's grind: blank is none; an older number reads as
 * the core writes a dial (`grind_text` on the default scale: whole numbers
 * bare, off-step values to four decimals), so float noise such as
 * 0.30000000000000004 never becomes the grind (REVIEW-503, follow-up 27).
 */
function fromStored(v: unknown): string | null {
	const text = typeof v === 'number' ? grindText(v, DEFAULT_GRIND_SCALE) : typeof v === 'string' ? v.trim() : '';
	return text === '' ? null : text;
}

export class BrewGrindSession {
	#value = $state.raw<string | null>(fromStored(readJson<unknown>(BREW_GRIND_KEY, null, { valid: isStored })));

	constructor() {
		followOtherTabs(BREW_GRIND_KEY, isStored, (v) => {
			this.#value = fromStored(v);
		});
	}

	/** Brew's grind now, or `null` when not set. */
	get value(): string | null {
		return this.#value;
	}

	/** Set Brew's grind as text (typed, or a pick's grind as stored); blank or `null` clears it. */
	set(next: string | null | undefined): void {
		const v = fromStored(next ?? null);
		if (v === this.#value) return;
		this.#value = v;
		writeJson(BREW_GRIND_KEY, v);
	}

	/** Set Brew's grind from the stepper or a chip: the dial on `scale`, as the core writes it. */
	setDial(dial: number, scale: GrindScale | null): void {
		if (Number.isFinite(dial)) this.set(grindText(dial, scale));
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
