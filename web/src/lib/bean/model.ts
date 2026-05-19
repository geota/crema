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
 *
 * The shape mirrors what Visualizer expects in the de1app shot `bean` block:
 * `roaster` → `bean.brand`, `type` → `bean.type`, `roastLevel` → the 1..10
 * `roast_level` scale, `roastedOn` → `roast_date`.
 */

import type { Roast } from '$lib/profiles';

/**
 * The current bean — the bag of coffee in use right now. A snapshot of this
 * shape is also stamped onto each recorded shot (see `lib/history`).
 */
export interface Bean {
	/** The roastery, e.g. `Onyx Coffee Lab` (Visualizer `bean.brand`). */
	roaster: string;
	/** The coffee itself, e.g. `Colombian Geisha` (Visualizer `bean.type`). */
	type: string;
	/** ISO `yyyy-mm-dd` roast date, or `null` when not logged. */
	roastedOn: string | null;
	/**
	 * Roast level on Visualizer's 1..10 `roast_level` scale (1 = lightest),
	 * or `null` when not logged. See {@link roastBand} to classify it.
	 */
	roastLevel: number | null;
}

/** A fresh, empty current bean — the starting point before anything is logged. */
export function blankBean(): Bean {
	return { roaster: '', type: '', roastedOn: null, roastLevel: null };
}

/**
 * Classify a 1..10 roast level into a named band: `1–3 → 'light'`,
 * `4–6 → 'medium'`, `7–10 → 'dark'`. Returns `null` for a `null` level.
 * Values outside 1..10 are clamped into range first.
 */
export function roastBand(level: number | null): Roast | null {
	if (level == null) return null;
	const clamped = Math.max(1, Math.min(10, Math.round(level)));
	if (clamped <= 3) return 'light';
	if (clamped <= 6) return 'medium';
	return 'dark';
}

/**
 * The 1..10 roast-level a quick-set roast pill maps to — each value lands
 * squarely inside its own {@link roastBand}: light → 1, medium → 5, dark → 10.
 */
export const ROAST_PILL_LEVEL: Readonly<Record<Roast, number>> = {
	light: 1,
	medium: 5,
	dark: 10
};

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

/** A bean's rest verdict against the ideal window for its roast band. */
export type Freshness = 'best' | 'ok' | 'bad';

/**
 * Per-band rest windows, in days off roast. `green` is the `[low, high]`
 * best window; `okHigh` is the upper bound of the still-drinkable fading
 * window — past it the bean is stale. Below the green window the bean also
 * only rates `ok` (still too gassy / unstable).
 *
 * The windows differ because degassing tracks bean density: darker beans are
 * porous and degas fast (earliest, shortest window); light roasts are dense,
 * hold CO₂ longest, and need the most rest.
 *
 *  - Dark   — best 4–10, ok 0–3 / 11–14, bad 15+.
 *  - Medium — best 6–14, ok 0–5 / 15–21, bad 22+.
 *  - Light  — best 10–24, ok 0–9 / 25–35, bad 36+.
 */
const REST_WINDOW: Readonly<
	Record<Roast, { green: readonly [number, number]; okHigh: number }>
> = {
	dark: { green: [4, 10], okHigh: 14 },
	medium: { green: [6, 14], okHigh: 21 },
	light: { green: [10, 24], okHigh: 35 }
};

/**
 * Rate how a bean's `days` off roast sits against the ideal rest window for
 * its roast `band` (see {@link REST_WINDOW}) — drives the bean card's status
 * dot. `best` inside the green window, `bad` past `okHigh`, `ok` either side
 * in between. Returns `null` when the band or the day count is unknown.
 */
export function roastFreshness(
	band: Roast | null,
	days: number | null
): Freshness | null {
	if (band == null || days == null) return null;
	const w = REST_WINDOW[band];
	if (days >= w.green[0] && days <= w.green[1]) return 'best';
	if (days > w.okHigh) return 'bad';
	return 'ok';
}

/**
 * Leniently migrate a value persisted under the **old** bean shape
 * (`{ name, roastedOn, roastLevel: 'light'|'medium'|'dark'|null }`) — or any
 * partial / unknown value — into a valid {@link Bean}. Unmappable fields fall
 * back to blank. Never throws: used on load so an old payload cannot crash.
 */
export function migrateBean(raw: unknown): Bean {
	const base = blankBean();
	if (typeof raw !== 'object' || raw === null) return base;
	const obj = raw as Record<string, unknown>;

	// `roaster` is new — accept it if present, else blank.
	if (typeof obj.roaster === 'string') base.roaster = obj.roaster;

	// `type` is new; the old shape had a single `name` → migrate it to `type`.
	if (typeof obj.type === 'string') {
		base.type = obj.type;
	} else if (typeof obj.name === 'string') {
		base.type = obj.name;
	}

	if (typeof obj.roastedOn === 'string') base.roastedOn = obj.roastedOn;

	// `roastLevel` is now a 1..10 number; the old shape stored a band word.
	const level = obj.roastLevel;
	if (typeof level === 'number' && Number.isFinite(level)) {
		base.roastLevel = Math.max(1, Math.min(10, Math.round(level)));
	} else if (level === 'light' || level === 'medium' || level === 'dark') {
		base.roastLevel = ROAST_PILL_LEVEL[level];
	}

	return base;
}
