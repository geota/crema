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
import {
	roast_band as wasmRoastBand,
	days_off_roast as wasmDaysOffRoast,
	roast_freshness as wasmRoastFreshness
} from '$lib/wasm/de1_wasm';

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
	/**
	 * The grinder this bag is dialled in on, e.g. `Niche Zero` — free text,
	 * empty when not logged. Bean-scoped because a grind setting only means
	 * something paired with the grinder it was measured on.
	 */
	grinder: string;
}

/** A fresh, empty current bean — the starting point before anything is logged. */
export function blankBean(): Bean {
	return { roaster: '', type: '', roastedOn: null, roastLevel: null, grinder: '' };
}

/**
 * Classify a 1..10 roast level into a named band: `1–3 → 'light'`,
 * `4–6 → 'medium'`, `7–10 → 'dark'`. Returns `null` for a `null` level.
 * Values outside 1..10 are clamped into range first.
 *
 * Delegates to `de1_domain::roast_band` via the wasm bridge so every
 * shell consumes the same classification (audit #5). The shell rounds
 * a fractional level before crossing the bridge — the wasm helper
 * takes an integer level.
 */
export function roastBand(level: number | null): Roast | null {
	if (level == null) return null;
	const rounded = Math.round(level);
	if (!Number.isFinite(rounded)) return null;
	return (wasmRoastBand(rounded) ?? null) as Roast | null;
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
 *
 * Delegates to `de1_domain::days_off_roast` via the wasm bridge — the
 * core is sans-IO so the shell passes `Date.now()` (or `asOf`) at the
 * call site (audit #5/#9).
 */
export function daysOffRoast(
	roastedOn: string | null,
	asOf: number | Date = Date.now()
): number | null {
	if (!roastedOn) return null;
	const nowMs = asOf instanceof Date ? asOf.getTime() : asOf;
	const days = wasmDaysOffRoast(roastedOn, nowMs);
	return days == null ? null : days;
}

/** A bean's rest verdict against the ideal window for its roast band. */
export type Freshness = 'best' | 'ok' | 'bad';

/**
 * Rate how a bean's `days` off roast sits against the ideal rest window for
 * its roast `band` — drives the bean card's status dot. `best` inside the
 * green window, `bad` past the band's `ok` upper bound, `ok` either side in
 * between. Returns `null` when the band or the day count is unknown.
 *
 * The per-band windows (dark / medium / light) and the verdict thresholds
 * live in `de1_domain::roast_freshness` — this delegates via the wasm
 * bridge so every shell rates a bean identically (audit #5).
 */
export function roastFreshness(
	band: Roast | null,
	days: number | null
): Freshness | null {
	if (band == null || days == null) return null;
	return (wasmRoastFreshness(band, days) ?? null) as Freshness | null;
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

	// `grinder` is new — accept it if present, else blank.
	if (typeof obj.grinder === 'string') base.grinder = obj.grinder;

	// `roastLevel` is now a 1..10 number; the old shape stored a band word.
	const level = obj.roastLevel;
	if (typeof level === 'number' && Number.isFinite(level)) {
		base.roastLevel = Math.max(1, Math.min(10, Math.round(level)));
	} else if (level === 'light' || level === 'medium' || level === 'dark') {
		base.roastLevel = ROAST_PILL_LEVEL[level];
	}

	return base;
}
