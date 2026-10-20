/**
 * `$lib/history/search` — the History list's text search and the
 * "same coffee" grouping behind its bean filter. Pure over a
 * {@link StoredShot}; mirrored by Android's `ShotFilter.kt` so both shells
 * match the same shots.
 */

import { methodLabel } from '$lib/brew/methods';
import { methodOf, type StoredShot } from './model';

/**
 * True when the shot's text fields contain `query` (case-insensitive). The
 * fields: profile, brew method, recipe, notes, next-time plan, the bean
 * snapshot's name / roaster / grinder (plus the legacy flat bean label),
 * the shot's grinder model, barista and drinker. Tags are matched by the
 * caller, which resolves them through the live bean. An empty query
 * matches everything.
 */
export function shotMatchesQuery(shot: StoredShot, query: string): boolean {
	const q = query.trim().toLowerCase();
	if (q === '') return true;
	const fields = [
		shot.profileName,
		methodLabel(methodOf(shot), shot.brewMethodLabel),
		shot.recipeName,
		shot.metadata?.notes,
		shot.metadata?.nextPlan,
		shot.metadata?.beans,
		shot.metadata?.barista,
		shot.metadata?.drinker,
		shot.bean?.name,
		shot.bean?.roasterName,
		shot.bean?.grinder,
		shot.grinderModel
	];
	return fields.some((f) => typeof f === 'string' && f.toLowerCase().includes(q));
}

/** Prefix that marks a bean-filter value as a coffee key, not a bag id. */
export const COFFEE_FILTER_PREFIX = 'coffee:';

/**
 * The grouping key for "the same coffee": roaster + coffee name, trimmed,
 * case-folded and whitespace-collapsed — the same name rule the roaster
 * duplicate merge uses. `null` when there is no coffee name.
 */
export function coffeeKey(name: string | null | undefined, roaster: string | null | undefined): string | null {
	const norm = (s: string | null | undefined) => (s ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
	const n = norm(name);
	if (n === '') return null;
	return `${norm(roaster)}|${n}`;
}

/** The coffee a shot was pulled with: name, roaster and grouping key. */
export interface ShotCoffee {
	readonly key: string;
	readonly name: string;
	readonly roaster: string | null;
}

/**
 * Resolve the coffee a shot was pulled with. A bag still in the library
 * answers with its live name and roaster (so a rename keeps every bag's
 * shots together, and a merged duplicate roaster resolves to the one it
 * was merged into); otherwise the shot's frozen snapshot answers.
 */
export function shotCoffee(
	shot: StoredShot,
	live: (beanId: string) => { name: string; roaster: string | null } | null
): ShotCoffee | null {
	const bean = shot.bean;
	if (!bean) return null;
	const fromLive = bean.beanId ? live(bean.beanId) : null;
	const name = (fromLive?.name ?? bean.name ?? '').trim();
	const roaster = (fromLive ? fromLive.roaster : bean.roasterName)?.trim() || null;
	const key = coffeeKey(name, roaster);
	return key ? { key, name, roaster } : null;
}

/**
 * Names already used in a person field, most recent first, de-duplicated
 * case-insensitively (the newest spelling wins). Feeds the shot editor's
 * barista / drinker suggestions.
 */
export function recentNames(shots: readonly StoredShot[], field: 'barista' | 'drinker'): string[] {
	const seen = new Set<string>();
	const out: string[] = [];
	const newestFirst = [...shots].sort((a, b) => b.completedAt - a.completedAt);
	for (const s of newestFirst) {
		const name = s.metadata?.[field]?.trim();
		if (!name) continue;
		const k = name.toLowerCase();
		if (seen.has(k)) continue;
		seen.add(k);
		out.push(name);
	}
	return out;
}
