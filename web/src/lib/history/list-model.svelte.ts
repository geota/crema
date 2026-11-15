/**
 * `#lib/history/list-model.svelte.js` — the History list's derivations,
 * keyed on versions rather than on the rows (#310 P5w, SPEC decision 5).
 *
 * - {@link HistoryListModel.shots}, the facets and the per-coffee keys read
 *   {@link ListVersions.list} only.
 * - {@link HistoryListModel.filtered} reads `list`, plus `rating` only when
 *   sorting by rating, and `text` only while a search is active.
 * - {@link HistoryListModel.stats} reads `list` and `rating`, after paint,
 *   debounced by 300 ms.
 * - The search applies 150 ms after the last keystroke.
 *
 * So a star tap runs no row predicate and rebuilds no facet. The rows these
 * hold are as of their last rerun: whatever shows a row reads the current one
 * by id (`HistoryStore.get`).
 *
 * Construct it during a component's init (it owns two effects), or inside
 * `$effect.root` in a test.
 */

import { untrack } from 'svelte';
import type { BrewHistoryStats } from '#lib/core/crema-core.js';
import { methodLabel } from '#lib/brew/methods.js';
import { ListVersions } from './list-versions.svelte';
import { shotPasses, sortShots, type ListFilters, type SortDir, type SortField } from './list-filter';
import { brewStatsOf, methodOf, type ShotRow } from './model';
import { COFFEE_FILTER_PREFIX, shotCoffee } from './search';

/** How long the search waits after the last keystroke. */
export const SEARCH_DEBOUNCE_MS = 150;
/** How long the stats wait after the last change. */
export const STATS_DEBOUNCE_MS = 300;

/** The page's filter state, minus the search, which arrives through {@link ListModelDeps.query}. */
export interface ListInputs {
	readonly cutoff: number | null;
	readonly profile: string;
	readonly pinnedOnly: boolean;
	readonly bean: string;
	readonly method: string;
	readonly tags: readonly string[];
	readonly sortField: SortField;
	readonly sortDir: SortDir;
}

export interface ListModelDeps {
	/** Every live row in view, newest first (`HistoryStore.all`). Reactive. */
	rows(): readonly ShotRow[];
	/** The current row for `id` (`HistoryStore.get`). */
	get(id: string): ShotRow | undefined;
	/** The search box as typed. */
	query(): string;
	filters(): ListInputs;
	/** A row's tags, its own or its live bean's. */
	tagsOf(shot: ShotRow): readonly string[];
	/** A library bag's live name and roaster, for the coffee key. */
	liveCoffee(beanId: string): { name: string; roaster: string | null } | null;
}

/** A facet pill's data: an id, a label and how many rows carry it. */
export interface CoffeeFacet {
	id: string;
	label: string;
	title: string | undefined;
	count: number;
}

export class HistoryListModel {
	readonly #deps: ListModelDeps;
	readonly versions: ListVersions<ShotRow>;
	/** The search as applied: the box's value, {@link SEARCH_DEBOUNCE_MS} after the last keystroke. */
	#query = $state('');
	#stats = $state.raw<BrewHistoryStats | null>(null);
	#statsRan = false;

	constructor(deps: ListModelDeps) {
		this.#deps = deps;
		this.versions = new ListVersions(() => deps.rows());
		this.#query = untrack(() => deps.query());
		$effect(() => {
			const typed = deps.query();
			if (typed === untrack(() => this.#query)) return;
			const timer = setTimeout(() => (this.#query = typed), SEARCH_DEBOUNCE_MS);
			return () => clearTimeout(timer);
		});
		$effect(() => {
			const rows = this.filtered;
			void this.versions.rating;
			// The rows `filtered` holds are as of the last list change: a rating
			// counts as it is now.
			const run = (): void => {
				this.#statsRan = true;
				this.#stats = brewStatsOf(rows.map((s) => deps.get(s.id) ?? s));
			};
			const timer = setTimeout(run, this.#statsRan ? STATS_DEBOUNCE_MS : 0);
			return () => clearTimeout(timer);
		});
	}

	/** Every row, newest first, as of the last {@link ListVersions.list} move. */
	readonly shots = $derived.by((): readonly ShotRow[] => {
		void this.versions.list;
		return untrack(() => this.#deps.rows());
	});

	/** The search as applied, trimmed and lower-cased. */
	get query(): string {
		return this.#query.trim().toLowerCase();
	}

	/** Distinct profile names, in row order, for the filter chips. */
	readonly profilesInUse = $derived([
		...new Set(this.shots.map((s) => s.profileName).filter((n): n is string => !!n))
	]);

	/** Rows per profile name. */
	readonly profileCounts = $derived.by(() => {
		const m = new Map<string, number>();
		for (const s of this.shots) if (s.profileName) m.set(s.profileName, (m.get(s.profileName) ?? 0) + 1);
		return m;
	});

	/** Pinned rows (#311). */
	readonly pinnedCount = $derived(this.shots.filter((s) => s.pinned === true).length);

	/** Distinct brew methods, counted; machine shots fold into `espresso`. */
	readonly methodsInUse = $derived.by(() => {
		const m = new Map<string, number>();
		// A custom method's row snapshot: the facet's label of last resort.
		const snapshots = new Map<string, string>();
		for (const s of this.shots) {
			const key = methodOf(s) ?? 'espresso';
			m.set(key, (m.get(key) ?? 0) + 1);
			if (s.brewMethodLabel && !snapshots.has(key)) snapshots.set(key, s.brewMethodLabel);
		}
		return [...m.entries()]
			.map(([id, count]) => ({ id, count, label: methodLabel(id, snapshots.get(id)) }))
			.sort((a, b) => b.count - a.count || a.id.localeCompare(b.id));
	});

	/** Each row's coffee key. */
	readonly coffeeKeyById = $derived.by(() => {
		const m = new Map<string, string>();
		for (const s of this.shots) {
			const c = shotCoffee(s, (id) => this.#deps.liveCoffee(id));
			if (c) m.set(s.id, c.key);
		}
		return m;
	});

	/**
	 * Distinct coffees, counted, for the bean pills. A coffee is roaster +
	 * name, so every bag of it is one pill. A name two roasters share gets the
	 * roaster appended.
	 */
	readonly beansInUse = $derived.by((): CoffeeFacet[] => {
		const m = new Map<string, { name: string; roaster: string | null; count: number; bags: Set<string> }>();
		for (const s of this.shots) {
			const c = shotCoffee(s, (id) => this.#deps.liveCoffee(id));
			if (!c) continue;
			const entry = m.get(c.key) ?? { name: c.name, roaster: c.roaster, count: 0, bags: new Set<string>() };
			entry.count += 1;
			if (s.bean?.beanId) entry.bags.add(s.bean.beanId);
			m.set(c.key, entry);
		}
		const nameCounts = new Map<string, number>();
		for (const v of m.values()) {
			const n = v.name.toLowerCase();
			nameCounts.set(n, (nameCounts.get(n) ?? 0) + 1);
		}
		return [...m.entries()]
			.map(([key, v]) => ({
				id: `${COFFEE_FILTER_PREFIX}${key}`,
				label: (nameCounts.get(v.name.toLowerCase()) ?? 0) > 1 && v.roaster ? `${v.name} · ${v.roaster}` : v.name,
				title: [v.roaster, v.bags.size > 1 ? `${v.bags.size} bags` : null].filter(Boolean).join(' · ') || undefined,
				count: v.count
			}))
			.sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
	});

	/** Distinct tags, counted, by frequency then name. */
	readonly tagFacets = $derived.by(() => {
		const m = new Map<string, number>();
		for (const s of this.shots) {
			for (const t of this.#deps.tagsOf(s)) m.set(t, (m.get(t) ?? 0) + 1);
		}
		return [...m.entries()]
			.map(([tag, count]) => ({ tag, count }))
			.sort((a, b) => b.count - a.count || a.tag.localeCompare(b.tag));
	});

	/** The rows the list shows, filtered and sorted. */
	readonly filtered = $derived.by((): readonly ShotRow[] => {
		const f = this.#deps.filters();
		const query = this.query;
		void this.versions.list;
		if (f.sortField === 'rating') void this.versions.rating;
		if (query !== '') void this.versions.text;
		const rows = untrack(() => this.#deps.rows());
		const filters: ListFilters = {
			query,
			cutoff: f.cutoff,
			profile: f.profile,
			pinnedOnly: f.pinnedOnly,
			bean: f.bean,
			method: f.method,
			tags: f.tags
		};
		const lookups = {
			tagsOf: (s: ShotRow) => this.#deps.tagsOf(s),
			coffeeKeyOf: (id: string) => this.coffeeKeyById.get(id)
		};
		return sortShots(
			rows.filter((s) => shotPasses(s, filters, lookups)),
			f.sortField,
			f.sortDir
		);
	});

	/** The filtered rows' stats, as of {@link STATS_DEBOUNCE_MS} after the last change; `null` until the first run. */
	get stats(): BrewHistoryStats | null {
		return this.#stats;
	}
}
