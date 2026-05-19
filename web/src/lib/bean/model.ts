/**
 * `$lib/bean/model` — the **current bean** model.
 *
 * A *bean* (a bag of coffee) and a *profile* (an extraction recipe) have
 * different lifecycles, so bean identity does **not** live on the profile —
 * this matches the upstream de1app, where `profile_vars` carries no bean
 * fields and bean info lives in app settings plus per-shot metadata.
 *
 * The shell therefore keeps a single **current bean** — the bag you are
 * pulling shots from right now — in its own localStorage-backed store (see
 * `store.svelte.ts`). It is independent of any profile.
 */

import type { Roast } from '$lib/profiles';

/**
 * The current bean — the bag of coffee in use right now. A snapshot of this
 * shape is also stamped onto each recorded shot (see `lib/history`).
 */
export interface Bean {
	/** Bean name / origin, e.g. `Onyx Monarch`. */
	name: string;
	/** ISO `yyyy-mm-dd` roast date, or `null` when not logged. */
	roastedOn: string | null;
	/** Roast level, or `null` when not logged. */
	roastLevel: Roast | null;
}

/** A fresh, empty current bean — the starting point before anything is logged. */
export function blankBean(): Bean {
	return { name: '', roastedOn: null, roastLevel: null };
}

/**
 * Whole days between a `yyyy-mm-dd` roast date and `asOf` (default: now) —
 * the bean's "days off roast". Returns `null` when no roast date is set, and
 * is clamped at `0` so a future-dated roast never reads negative.
 *
 * The arithmetic is done on the calendar date only (UTC midnight of each
 * day), so a shot pulled at any time of day reports a stable integer.
 */
export function daysOffRoast(
	roastedOn: string | null,
	asOf: number | Date = Date.now()
): number | null {
	if (!roastedOn) return null;
	const roast = Date.parse(`${roastedOn}T00:00:00Z`);
	if (Number.isNaN(roast)) return null;
	const now = asOf instanceof Date ? asOf : new Date(asOf);
	const today = Date.UTC(
		now.getUTCFullYear(),
		now.getUTCMonth(),
		now.getUTCDate()
	);
	const days = Math.floor((today - roast) / 86_400_000);
	return Math.max(0, days);
}
