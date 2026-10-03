/**
 * `$lib/profiles/library-filter` — the `/profiles` filter rail as two
 * independent axes (geota/crema#124).
 *
 * The rail used to be one single-select value, so picking **Hidden** (the
 * built-ins the user tucked away) dropped the roast / beverage / tag filter,
 * and each chip's count ignored the other selections. Now:
 *
 * - **status** — All / Pinned / Hidden — picks the subset (Hidden draws from
 *   the hidden built-ins, which the store keeps out of the main list);
 * - **facet** — one roast (`light` …), beverage (`b:<type>`) or custom tag
 *   (`t:<tag>`) — narrows whichever status is picked;
 * - the search narrows both.
 *
 * Counts are faceted: each chip shows what the grid would hold if it were
 * picked, given the other axis and the search. Sorting stays on the page.
 * Profiles are not a core type on Android (its `CremaProfile` is a Kotlin
 * model), so this stays a shell helper; Android's `ProfileFilter.kt` follows
 * the same rules.
 */

import type { CremaProfile } from './model';

export type ProfileStatus = 'all' | 'pinned' | 'hidden';

export interface ProfileFilterInput {
	/** The visible library (`store.all`). */
	visible: readonly CremaProfile[];
	/** The hidden built-ins (`store.hiddenBuiltinProfiles`). */
	hidden: readonly CremaProfile[];
	status: ProfileStatus;
	/** `light` / `medium` / `dark`, `b:<beverage>`, `t:<tag>`, or null. */
	facet: string | null;
	query: string;
}

/** Whether `p` carries `facet` (null = no facet = always). */
export function profileMatchesFacet(p: CremaProfile, facet: string | null): boolean {
	if (facet == null) return true;
	if (facet.startsWith('t:')) return p.tags.includes(facet.slice(2));
	if (facet.startsWith('b:')) return p.beverageType === facet.slice(2);
	return p.roast === facet;
}

/** Substring search over name / notes / tags / author (blank = all). */
export function profileMatchesQuery(p: CremaProfile, query: string): boolean {
	const q = query.trim().toLowerCase();
	if (q === '') return true;
	return (
		p.name.toLowerCase().includes(q) ||
		p.notes.toLowerCase().includes(q) ||
		p.tags.some((t) => t.toLowerCase().includes(q)) ||
		p.author.toLowerCase().includes(q)
	);
}

function statusSource(input: ProfileFilterInput, status: ProfileStatus): readonly CremaProfile[] {
	if (status === 'hidden') return input.hidden;
	if (status === 'pinned') return input.visible.filter((p) => p.pinned);
	return input.visible;
}

/** The profiles passing status + facet + search, in source order. */
export function filterProfiles(input: ProfileFilterInput): CremaProfile[] {
	return statusSource(input, input.status).filter(
		(p) => profileMatchesFacet(p, input.facet) && profileMatchesQuery(p, input.query)
	);
}

/** Status chip counts, each given the facet + search. */
export function profileStatusCounts(input: ProfileFilterInput): Record<ProfileStatus, number> {
	const n = (s: ProfileStatus) =>
		statusSource(input, s).filter(
			(p) => profileMatchesFacet(p, input.facet) && profileMatchesQuery(p, input.query)
		).length;
	return { all: n('all'), pinned: n('pinned'), hidden: n('hidden') };
}

/** One facet chip's count, given the status + search. */
export function profileFacetCount(input: ProfileFilterInput, facet: string): number {
	return statusSource(input, input.status).filter(
		(p) => profileMatchesFacet(p, facet) && profileMatchesQuery(p, input.query)
	).length;
}
