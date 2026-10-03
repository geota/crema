/**
 * `$lib/bean/filter` — the bean library's facet filter + chip counts,
 * delegated to the core (`de1_domain::bean_filter`, geota/crema#124).
 *
 * Status (where "archived" is one more status), "include archived", roast
 * band, tags, the roaster scope and the search's matched ids are independent
 * axes that compose; every chip count is "how many bags if I picked this,
 * given everything else". Android's phone and tablet shells call the same
 * function through the FFI, so the three lists and their badges agree.
 *
 * Sorting stays on the page — the core hands back ids in library order.
 */

import { filterBeans as wasmFilterBeans } from '$lib/wasm/de1_wasm';
import {
	BeanStatusFilter,
	type BeanFilterQuery,
	type BeanFilterResult
} from '$lib/core/crema-core';
import type { Bean } from './model';
import { libraryBeansJson, type SearchResults } from './search';

export { BeanStatusFilter };
export type { BeanFilterQuery, BeanFilterResult };

/** The page-side selections, before the search hits are folded in. */
export interface BeanFacets {
	status: BeanStatusFilter;
	includeArchived: boolean;
	roast: 'light' | 'medium' | 'dark' | null;
	tags: readonly string[];
	roasterId: string | null;
}

/** The result for an empty library, or when the core call fails. */
function emptyResult(): BeanFilterResult {
	return {
		ids: [],
		statusCounts: { all: 0, active: 0, frozen: 0, favourite: 0, archived: 0 },
		roastCounts: { light: 0, medium: 0, dark: 0 },
		tagCounts: [],
		archivedHidden: 0,
		showingArchived: false
	};
}

/** The core query for `facets` + the current search. */
export function toQuery(facets: BeanFacets, hits: SearchResults): BeanFilterQuery {
	return {
		status: facets.status,
		includeArchived: facets.includeArchived,
		roast: facets.roast ?? undefined,
		tags: [...facets.tags],
		roasterId: facets.roasterId ?? undefined,
		matchIds: hits.active ? [...hits.byId.keys()] : undefined
	};
}

/**
 * Filter `beans` and count every chip. A failing core call logs and falls
 * back to the unfiltered library (with zero counts) rather than an empty
 * page — the same "never hide the library" rule the search follows.
 */
export function filterBeans(
	beans: readonly Bean[],
	facets: BeanFacets,
	hits: SearchResults
): BeanFilterResult {
	if (beans.length === 0) return emptyResult();
	try {
		return JSON.parse(
			wasmFilterBeans(libraryBeansJson(beans), JSON.stringify(toQuery(facets, hits)))
		) as BeanFilterResult;
	} catch (err) {
		console.error('Bean filter failed', err);
		return { ...emptyResult(), ids: beans.map((b) => b.id) };
	}
}
