/**
 * `$lib/bean/catalogue` — the Visualizer canonical-catalogue search behind the
 * bean and roaster forms' "Visualizer catalogue" field.
 *
 * The HTTP (`GET /canonical_coffee_bags?q=…`, `GET /canonical_roasters?q=…`)
 * lives in the `BeanSync` service (`searchCatalogue`,
 * `searchCatalogueRoasters`); the response parsing, the pick → bean / roaster
 * autofill rules, the roaster resolution and the clash lists live in the Rust
 * core (`de1_domain::visualizer_catalogue`) so web and Android fill a form
 * identically. This module adds the shell-side pieces:
 *
 *  - wasm wrappers ({@link catalogueClashes}, {@link pickFromCatalogue},
 *    {@link catalogueRoasterClashes}, {@link autofillRoasterFromCatalogue}, …);
 *  - the clash prompt ({@link resolveCatalogueClash}): no clash → fill empty
 *    fields at once; a clash → "Keep mine" / "Use catalogue" / dismiss;
 *  - {@link createCatalogueSearch} — a debounced, last-query-wins search
 *    controller (pure TS, framework-free, so vitest drives it with fake timers).
 */

import {
	catalogueAutofill as wasmCatalogueAutofill,
	catalogueClashes as wasmCatalogueClashes,
	cataloguePick as wasmCataloguePick,
	catalogueRoasterAutofill as wasmCatalogueRoasterAutofill,
	catalogueRoasterClashes as wasmCatalogueRoasterClashes,
	parseCatalogueCoffeeBags as wasmParseCatalogueCoffeeBags,
	parseCatalogueRoasters as wasmParseCatalogueRoasters
} from '$lib/wasm/de1_wasm';
import {
	CatalogueField,
	type CatalogueCoffeeBag,
	type CataloguePage,
	type CatalogueRoaster,
	type CatalogueRoasterPage
} from '$lib/core/crema-core';
import { choiceDialog } from '$lib/components/shared/confirm-dialog.svelte';
import { blankRoaster, type Bean, type Roaster } from './model';

export { CatalogueField };
export type { CatalogueCoffeeBag, CataloguePage, CatalogueRoaster, CatalogueRoasterPage };

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

/** Parse a raw `GET /canonical_roasters` body (never throws on a bad shape). */
export function parseCatalogueRoasterPage(body: unknown): CatalogueRoasterPage {
	return JSON.parse(wasmParseCatalogueRoasters(JSON.stringify(body ?? null))) as CatalogueRoasterPage;
}

// ── Bag pick: clashes + apply (incl. the roaster it's filed under) ──────

/** Inputs shared by {@link catalogueClashes} and {@link pickFromCatalogue}. */
export interface CataloguePickInput {
	/** The form's current bean. */
	bean: Bean;
	/** The picked catalogue bag. */
	entry: CatalogueCoffeeBag;
	/** The roaster input's text. */
	roasterInput: string;
	/** The local roaster directory. */
	roasters: readonly Roaster[];
	/** The bag's catalogue roaster record (website / country), or `null` if
	 *  the lookup failed — then the roaster gets name + link only. */
	fetched: CatalogueRoaster | null;
}

/** The roaster side of a bag pick (mirrors core `CataloguePickRoaster`). */
export interface CataloguePickRoaster {
	/** Roaster name to show in the roaster input. */
	name: string;
	/** `false` = `roaster` is an existing row, updated; `true` = a seed to
	 *  create (blank id). */
	isNew: boolean;
	roaster: Roaster;
	filled: CatalogueField[];
}

/** Result of {@link pickFromCatalogue} (mirrors core `CataloguePick`). */
export interface CataloguePickResult {
	/** The bean with the bag fields + catalogue links applied. */
	bean: Bean;
	/** camelCase names of the bag fields that changed. */
	filled: string[];
	/** The roaster to file the bag under, or `null` = leave it alone. */
	roaster: CataloguePickRoaster | null;
}

/**
 * Every clash a bag pick would raise — the bag's own fields, the roaster
 * input, and the matched local roaster's name / website / country — in form
 * order (core `catalogue_clashes`).
 */
export function catalogueClashes(input: CataloguePickInput): CatalogueField[] {
	return JSON.parse(
		wasmCatalogueClashes(
			JSON.stringify(input.bean),
			JSON.stringify(input.entry),
			input.roasterInput,
			JSON.stringify(input.roasters),
			JSON.stringify(input.fetched)
		)
	) as CatalogueField[];
}

/**
 * Apply a bag pick: the bag fields (empty only unless `replaceAll`) and the
 * roaster it's filed under — an existing row linked to / named like the
 * catalogue roaster, or a seed to create (core `catalogue_pick`).
 */
export function pickFromCatalogue(input: CataloguePickInput, replaceAll: boolean): CataloguePickResult {
	const raw = JSON.parse(
		wasmCataloguePick(
			JSON.stringify(input.bean),
			JSON.stringify(input.entry),
			input.roasterInput,
			JSON.stringify(input.roasters),
			JSON.stringify(input.fetched),
			replaceAll
		)
	) as { bean: Bean; filled: string[]; roaster?: CataloguePickRoaster | null };
	return { bean: raw.bean, filled: raw.filled, roaster: raw.roaster ?? null };
}

/**
 * The roaster row a bag pick files the bag under: the updated existing row,
 * or — for a seed (`isNew`) — a fresh row (new id + timestamps) carrying the
 * catalogue name / website / country and link. The caller upserts it.
 */
export function pickedRoasterRow(picked: CataloguePickRoaster): Roaster {
	if (!picked.isNew) return picked.roaster;
	const seed = picked.roaster;
	return {
		...blankRoaster(seed.name),
		website: seed.website ?? null,
		country: seed.country ?? null,
		catalogueRoasterId: seed.catalogueRoasterId ?? null
	};
}

// ── Roaster form pick ────────────────────────────────────────────────────

/** The roaster form's clashes for a picked catalogue roaster. */
export function catalogueRoasterClashes(roaster: Roaster, entry: CatalogueRoaster): CatalogueField[] {
	return JSON.parse(
		wasmCatalogueRoasterClashes(JSON.stringify(roaster), JSON.stringify(entry))
	) as CatalogueField[];
}

/**
 * Apply a picked catalogue roaster: name / website / country (empty only
 * unless `replaceAll`), and `catalogueRoasterId` always set. The local
 * duplicate-of pointer (`canonicalRoasterId`) is never touched.
 */
export function autofillRoasterFromCatalogue(
	roaster: Roaster,
	entry: CatalogueRoaster,
	replaceAll: boolean
): { roaster: Roaster; filled: CatalogueField[] } {
	return JSON.parse(
		wasmCatalogueRoasterAutofill(JSON.stringify(roaster), JSON.stringify(entry), replaceAll)
	) as { roaster: Roaster; filled: CatalogueField[] };
}

// ── Clash prompt ─────────────────────────────────────────────────────────

/** Human labels for the bag form (roaster-row fields say "Roaster …"). */
export const BAG_FORM_FIELD_LABELS: Record<CatalogueField, string> = {
	[CatalogueField.Name]: 'Name',
	[CatalogueField.Roaster]: 'Roaster',
	[CatalogueField.Country]: 'Country',
	[CatalogueField.Region]: 'Region',
	[CatalogueField.Farmer]: 'Farmer',
	[CatalogueField.Variety]: 'Variety',
	[CatalogueField.Elevation]: 'Elevation',
	[CatalogueField.Processing]: 'Process',
	[CatalogueField.HarvestTime]: 'Harvest time',
	[CatalogueField.RoastLevel]: 'Roast level',
	[CatalogueField.TastingNotes]: 'Tasting notes',
	[CatalogueField.Url]: 'URL',
	[CatalogueField.RoasterName]: 'Roaster name',
	[CatalogueField.RoasterWebsite]: 'Roaster website',
	[CatalogueField.RoasterCountry]: 'Roaster country'
};

/** Human labels for the roaster form (its own fields need no prefix). */
export const ROASTER_FORM_FIELD_LABELS: Record<CatalogueField, string> = {
	...BAG_FORM_FIELD_LABELS,
	[CatalogueField.RoasterName]: 'Name',
	[CatalogueField.RoasterWebsite]: 'Website',
	[CatalogueField.RoasterCountry]: 'Country'
};

/** Beyond this many fields the list ends "… and N more". */
const CLASH_LIST_MAX = 5;

/**
 * Join labels naturally — `"A"`, `"A and B"`, `"A, B and C"`; more than five
 * become the first four + `"and N more"`.
 */
export function joinFieldLabels(labels: readonly string[]): string {
	if (labels.length > CLASH_LIST_MAX) {
		const shown = labels.slice(0, CLASH_LIST_MAX - 1);
		return `${shown.join(', ')} and ${labels.length - shown.length} more`;
	}
	if (labels.length <= 1) return labels[0] ?? '';
	return `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}`;
}

/** The clash dialog's title. */
export const CLASH_TITLE = 'Some fields are already filled in';

/** The clash dialog's body for `fields`. */
export function clashMessage(
	fields: readonly CatalogueField[],
	labels: Record<CatalogueField, string> = BAG_FORM_FIELD_LABELS
): string {
	return `The catalogue has different values for ${joinFieldLabels(fields.map((f) => labels[f]))}.`;
}

/** The clash answer: fill empty fields only, or replace the clashing ones too. */
export type ClashChoice = 'keep' | 'replace';

/** Ask "Keep mine" (primary, safe) / "Use catalogue"; `null` = dismissed. */
export async function askCatalogueClash(
	fields: readonly CatalogueField[],
	labels: Record<CatalogueField, string> = BAG_FORM_FIELD_LABELS
): Promise<ClashChoice | null> {
	const answer = await choiceDialog({
		title: CLASH_TITLE,
		message: clashMessage(fields, labels),
		primaryLabel: 'Keep mine',
		secondaryLabel: 'Use catalogue'
	});
	return answer === 'primary' ? 'keep' : answer === 'secondary' ? 'replace' : null;
}

/**
 * How to apply a pick: no clash → `'keep'` at once (fill empty fields, no
 * prompt); a clash → the user's answer, or `null` when they dismiss the
 * dialog (apply nothing — the form and links stay as they were).
 */
export function resolveCatalogueClash(
	fields: readonly CatalogueField[],
	labels: Record<CatalogueField, string> = BAG_FORM_FIELD_LABELS,
	ask: typeof askCatalogueClash = askCatalogueClash
): Promise<ClashChoice | null> {
	return fields.length === 0 ? Promise.resolve('keep') : ask(fields, labels);
}

/**
 * A whole bag pick: clash check → (prompt) → apply. `input` is read twice —
 * before the prompt for the clash list and after it for the apply — so it
 * should return the form's current state. Resolves `null` when the user
 * dismissed the prompt (apply nothing).
 */
export async function runCataloguePick(
	input: () => CataloguePickInput,
	ask: typeof askCatalogueClash = askCatalogueClash
): Promise<CataloguePickResult | null> {
	const choice = await resolveCatalogueClash(catalogueClashes(input()), BAG_FORM_FIELD_LABELS, ask);
	return choice === null ? null : pickFromCatalogue(input(), choice === 'replace');
}

/**
 * A whole roaster-form pick: clash check → (prompt) → apply. Resolves `null`
 * when the user dismissed the prompt.
 */
export async function runCatalogueRoasterPick(
	roaster: () => Roaster,
	entry: CatalogueRoaster,
	ask: typeof askCatalogueClash = askCatalogueClash
): Promise<{ roaster: Roaster; filled: CatalogueField[] } | null> {
	const choice = await resolveCatalogueClash(
		catalogueRoasterClashes(roaster(), entry),
		ROASTER_FORM_FIELD_LABELS,
		ask
	);
	return choice === null ? null : autofillRoasterFromCatalogue(roaster(), entry, choice === 'replace');
}

/** The one-line outcome under the search field after a pick. */
export function catalogueFillStatus(filledCount: number): string {
	return filledCount === 0
		? 'Linked — every field was already filled.'
		: `Filled ${filledCount} field${filledCount === 1 ? '' : 's'} from the catalogue.`;
}

// ── Search controller ────────────────────────────────────────────────────

/** Debounce before a typed query hits the network. */
export const CATALOGUE_DEBOUNCE_MS = 300;
/** Shortest query worth sending (Visualizer returns nothing for blank). */
export const CATALOGUE_MIN_CHARS = 2;
/** Rows per search. */
export const CATALOGUE_PAGE_SIZE = 10;

/** The search controller's observable state. */
export interface CatalogueSearchState<T = CatalogueCoffeeBag> {
	/** The trimmed query the current `results` / `error` belong to. */
	query: string;
	/** A request is in flight (or debouncing) for the latest query. */
	loading: boolean;
	results: T[];
	/** Human-readable failure for the latest query, or `null`. */
	error: string | null;
}

export interface CatalogueSearchOptions<T = CatalogueCoffeeBag> {
	/** Run one search (the `BeanSync.searchCatalogue` /
	 *  `searchCatalogueRoasters` bridge in production). */
	search: (query: string) => Promise<{ entries: T[] }>;
	/** Fires on every state change. */
	onState: (state: CatalogueSearchState<T>) => void;
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
export function createCatalogueSearch<T = CatalogueCoffeeBag>(
	opts: CatalogueSearchOptions<T>
): CatalogueSearch {
	const debounceMs = opts.debounceMs ?? CATALOGUE_DEBOUNCE_MS;
	const minChars = opts.minChars ?? CATALOGUE_MIN_CHARS;
	let timer: ReturnType<typeof setTimeout> | null = null;
	/** Bumped on every query change — a response tagged with an older value is stale. */
	let generation = 0;
	let state: CatalogueSearchState<T> = { query: '', loading: false, results: [], error: null };

	const emit = (next: Partial<CatalogueSearchState<T>>) => {
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
