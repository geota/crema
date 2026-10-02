/**
 * `$lib/bean/catalogue` — the Visualizer canonical-catalogue search behind the
 * bean form's "Search Visualizer catalogue" field.
 *
 * The HTTP (`GET /canonical_coffee_bags?q=…`) lives in the `BeanSync` service
 * (`searchCatalogue`); the response parsing and the pick → bean autofill rule
 * live in the Rust core (`de1_domain::visualizer_catalogue`) so web and Android
 * fill a form identically. This module adds the shell-side pieces:
 *
 *  - {@link autofillFromCatalogue} — the wasm `catalogueAutofill` wrapper.
 *  - {@link createCatalogueSearch} — a debounced, last-query-wins search
 *    controller (pure TS, framework-free, so vitest drives it with fake timers).
 */

import {
	catalogueAutofill as wasmCatalogueAutofill,
	parseCatalogueCoffeeBags as wasmParseCatalogueCoffeeBags
} from '$lib/wasm/de1_wasm';
import type { CatalogueCoffeeBag, CataloguePage } from '$lib/core/crema-core';
import type { Bean } from './model';

export type { CatalogueCoffeeBag, CataloguePage };

/** Result of {@link autofillFromCatalogue} (mirrors core `CatalogueAutofill`). */
export interface CatalogueAutofillResult {
	/** The bean with the pick applied (catalogue links always set). */
	bean: Bean;
	/** Catalogue roaster name to put in the roaster field, or `null` = leave it. */
	roasterName: string | null;
	/** camelCase names of the fields that changed (excludes the link ids). */
	filled: string[];
}

/** Parse a raw `GET /canonical_coffee_bags` body (never throws on a bad shape). */
export function parseCataloguePage(body: unknown): CataloguePage {
	return JSON.parse(wasmParseCatalogueCoffeeBags(JSON.stringify(body ?? null))) as CataloguePage;
}

/**
 * Apply a picked catalogue row onto `bean`. Default: fill only EMPTY fields
 * (`roasterSet` = the form's roaster input already holds a value). With
 * `replaceAll`, every field the catalogue has a value for overwrites the
 * bean's; catalogue blanks never erase user values.
 */
export function autofillFromCatalogue(
	bean: Bean,
	entry: CatalogueCoffeeBag,
	opts: { roasterSet: boolean; replaceAll?: boolean }
): CatalogueAutofillResult {
	const raw = JSON.parse(
		wasmCatalogueAutofill(
			JSON.stringify(bean),
			JSON.stringify(entry),
			opts.roasterSet,
			opts.replaceAll ?? false
		)
	) as { bean: Bean; roasterName?: string | null; filled: string[] };
	return { bean: raw.bean, roasterName: raw.roasterName ?? null, filled: raw.filled };
}

/** Debounce before a typed query hits the network. */
export const CATALOGUE_DEBOUNCE_MS = 300;
/** Shortest query worth sending (Visualizer returns nothing for blank). */
export const CATALOGUE_MIN_CHARS = 2;
/** Rows per search. */
export const CATALOGUE_PAGE_SIZE = 10;

/** The search controller's observable state. */
export interface CatalogueSearchState {
	/** The trimmed query the current `results` / `error` belong to. */
	query: string;
	/** A request is in flight (or debouncing) for the latest query. */
	loading: boolean;
	results: CatalogueCoffeeBag[];
	/** Human-readable failure for the latest query, or `null`. */
	error: string | null;
}

export interface CatalogueSearchOptions {
	/** Run one search (the `BeanSync.searchCatalogue` bridge in production). */
	search: (query: string) => Promise<CataloguePage>;
	/** Fires on every state change. */
	onState: (state: CatalogueSearchState) => void;
	debounceMs?: number;
	minChars?: number;
}

export interface CatalogueSearch {
	/** Feed the latest input value (debounced). */
	setQuery(raw: string): void;
	/** Drop any pending / in-flight search and reset to idle. */
	clear(): void;
	/** Cancel timers (component teardown). */
	dispose(): void;
}

/**
 * A debounced catalogue search: each `setQuery` restarts a `debounceMs` timer;
 * only a query of at least `minChars` (trimmed) is sent; a response for a
 * query the user has since changed is dropped (last query wins), so a slow
 * early request can never overwrite newer results.
 */
export function createCatalogueSearch(opts: CatalogueSearchOptions): CatalogueSearch {
	const debounceMs = opts.debounceMs ?? CATALOGUE_DEBOUNCE_MS;
	const minChars = opts.minChars ?? CATALOGUE_MIN_CHARS;
	let timer: ReturnType<typeof setTimeout> | null = null;
	/** Bumped on every query change — a response tagged with an older value is stale. */
	let generation = 0;
	let state: CatalogueSearchState = { query: '', loading: false, results: [], error: null };

	const emit = (next: Partial<CatalogueSearchState>) => {
		state = { ...state, ...next };
		opts.onState(state);
	};
	const cancelTimer = () => {
		if (timer !== null) clearTimeout(timer);
		timer = null;
	};

	return {
		setQuery(raw) {
			const query = raw.trim();
			if (query === state.query && (state.loading || state.results.length || state.error)) return;
			cancelTimer();
			const gen = ++generation;
			if (query.length < minChars) {
				emit({ query, loading: false, results: [], error: null });
				return;
			}
			emit({ query, loading: true, error: null });
			timer = setTimeout(() => {
				timer = null;
				opts.search(query).then(
					(page) => {
						if (gen !== generation) return;
						emit({ loading: false, results: page.entries, error: null });
					},
					(e: unknown) => {
						if (gen !== generation) return;
						emit({
							loading: false,
							results: [],
							error: e instanceof Error && e.message ? e.message : 'Search failed.'
						});
					}
				);
			}, debounceMs);
		},
		clear() {
			cancelTimer();
			generation += 1;
			emit({ query: '', loading: false, results: [], error: null });
		},
		dispose() {
			cancelTimer();
			generation += 1;
		}
	};
}
