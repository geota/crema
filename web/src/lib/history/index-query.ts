/**
 * `#lib/history/index-query.js` — the History page's filters as the core's
 * `HistoryQuery` (#310 W2, SPEC §6), and the day groups' edges (§7).
 */
import { maySearch, type HistoryQuery, type SortKey } from '#lib/history-db/window.svelte.js';
import type { Live } from '#lib/history-db/protocol.js';
import type { SortDir, SortField } from './list-filter';
import { COFFEE_FILTER_PREFIX } from './search';

const KEYS: Record<SortField, SortKey> = {
	completedAt: 'date',
	rating: 'rating',
	profileName: 'profile',
	beanName: 'bean'
};

/** The page's filter state, as `HistoryListModel` takes it, with the search. */
export interface PageFilters {
	/** The search as typed (debounced): under 3 characters it's no search (owner question 3). */
	readonly text: string;
	readonly cutoff: number | null;
	/** `all` or a profile name. */
	readonly profile: string;
	readonly pinnedOnly: boolean;
	/** `all`, a bag id, or a coffee key behind {@link COFFEE_FILTER_PREFIX}. */
	readonly bean: string;
	/** `all` or a normalized method. */
	readonly method: string;
	readonly tags: readonly string[];
	readonly sortField: SortField;
	readonly sortDir: SortDir;
}

export function historyQuery(f: PageFilters): HistoryQuery {
	return {
		text: maySearch(f.text) ? f.text.trim() : '',
		sinceMs: f.cutoff,
		profile: f.profile === 'all' ? null : f.profile,
		bean:
			f.bean === 'all'
				? null
				: f.bean.startsWith(COFFEE_FILTER_PREFIX)
					? { coffee: f.bean.slice(COFFEE_FILTER_PREFIX.length) }
					: { bag: f.bean },
		method: f.method === 'all' ? null : f.method,
		tagsAll: [...f.tags],
		pinnedOnly: f.pinnedOnly,
		sort: { key: KEYS[f.sortField], dir: f.sortDir }
	};
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** The phone's day groups' edges, newest first: Today, Yesterday, This week (`phone-list.ts`). */
export function dayEdges(now: Date): number[] {
	const start = new Date(now);
	start.setHours(0, 0, 0, 0);
	const today = start.getTime();
	return [today, today - DAY_MS, today - 6 * DAY_MS];
}

/** The day groups' labels, in `group_counts` order. */
export const DAY_LABELS = ['Today', 'Yesterday', 'This week', 'Earlier'] as const;

/** What the live libraries are read from: the bean store and the custom methods. */
export interface LiveSources {
	beans: readonly { id: string; name: string; roasterId?: string | null; tags?: readonly string[] }[];
	roaster(id: string): { name: string; canonicalRoasterId?: string | null } | null;
	methods: readonly { id: string; label: string }[];
}

/**
 * The live libraries for the index's queries (SPEC §5, §6): each bag's current
 * name and roaster, a roaster merged into another answering as that one (as
 * the page's `liveCoffee` does), its tags, and every custom method's label.
 */
export function historyLive(src: LiveSources): Live {
	return {
		beans: src.beans.map((b) => {
			let roaster = b.roasterId ? src.roaster(b.roasterId) : null;
			if (roaster?.canonicalRoasterId) roaster = src.roaster(roaster.canonicalRoasterId) ?? roaster;
			return { beanId: b.id, name: b.name, roaster: roaster?.name ?? null, tags: [...(b.tags ?? [])] };
		}),
		methods: src.methods.map((m) => ({ method: m.id, label: m.label }))
	};
}
