/**
 * `#lib/history/list-filter.js` — the History list's row predicate and sort
 * orders, moved out of the page (#310 P5w) so the derivations that use them
 * can be tested, and the predicate spied on.
 */

import { methodOf, type AnyShot } from './model';
import { COFFEE_FILTER_PREFIX, shotMatchesQuery } from './search';

export type SortField = 'completedAt' | 'rating' | 'profileName' | 'beanName';
export type SortDir = 'asc' | 'desc';

/** The filters, as the predicate reads them. */
export interface ListFilters {
	/** Trimmed and lower-cased; empty for none. */
	readonly query: string;
	/** Rows before this time are out; `null` for no date range. */
	readonly cutoff: number | null;
	/** `all` or a profile name. */
	readonly profile: string;
	readonly pinnedOnly: boolean;
	/** `all`, a bag id, or a coffee key behind {@link COFFEE_FILTER_PREFIX}. */
	readonly bean: string;
	/** `all` or a normalized brew method. */
	readonly method: string;
	/** Every one must be on the row. */
	readonly tags: readonly string[];
}

/** What the predicate looks up beyond the row. */
export interface FilterLookups {
	/** The row's tags, its own or its live bean's. */
	tagsOf(shot: AnyShot): readonly string[];
	/** The row's coffee key, if it has a coffee. */
	coffeeKeyOf(id: string): string | undefined;
}

/** Whether `s` passes the bean filter: one bag, one coffee, or off. */
function passesBean(s: AnyShot, bean: string, lookups: FilterLookups): boolean {
	if (bean === 'all') return true;
	if (bean.startsWith(COFFEE_FILTER_PREFIX)) {
		return lookups.coffeeKeyOf(s.id) === bean.slice(COFFEE_FILTER_PREFIX.length);
	}
	return s.bean?.beanId === bean;
}

/** The row predicate: whether `s` is in the list under `f`. */
export function shotPasses(s: AnyShot, f: ListFilters, lookups: FilterLookups): boolean {
	if (f.cutoff != null && s.completedAt < f.cutoff) return false;
	if (f.profile !== 'all' && s.profileName !== f.profile) return false;
	if (f.pinnedOnly && s.pinned !== true) return false;
	if (!passesBean(s, f.bean, lookups)) return false;
	if (f.method !== 'all' && (methodOf(s) ?? 'espresso') !== f.method) return false;
	// AND semantics: a shot must carry every selected tag, its own or its
	// live bean's, so older shots filter like newer ones.
	if (f.tags.length > 0) {
		const shotTags = lookups.tagsOf(s);
		for (const t of f.tags) {
			if (!shotTags.includes(t)) return false;
		}
	}
	if (f.query === '') return true;
	return shotMatchesQuery(s, f.query) || lookups.tagsOf(s).some((t) => t.toLowerCase().includes(f.query));
}

/**
 * `rows` in the chosen order: the key, then `completedAt`, then the id, all in
 * the chosen direction (#310 SPEC §6, the core's total order). Ties follow the
 * sort, newest first under a descending sort and oldest first under an
 * ascending one, so this in-memory path (`index_fallback`) orders equal keys
 * as the index does (#448 note 6).
 */
export function sortShots<T extends AnyShot>(rows: readonly T[], field: SortField, direction: SortDir): T[] {
	const dir = direction === 'asc' ? 1 : -1;
	const cmpStr = (a: string | null, b: string | null): number => {
		const ax = (a ?? '').toLowerCase();
		const bx = (b ?? '').toLowerCase();
		return ax < bx ? -1 : ax > bx ? 1 : 0;
	};
	const arr = [...rows];
	arr.sort((a, b) => {
		let k = 0;
		switch (field) {
			case 'completedAt':
				k = a.completedAt - b.completedAt;
				break;
			case 'rating':
				k = (a.metadata.rating ?? 0) - (b.metadata.rating ?? 0);
				break;
			case 'profileName':
				k = cmpStr(a.profileName, b.profileName);
				break;
			case 'beanName':
				k = cmpStr(a.bean?.name ?? null, b.bean?.name ?? null);
				break;
		}
		return (k || a.completedAt - b.completedAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) * dir;
	});
	return arr;
}
