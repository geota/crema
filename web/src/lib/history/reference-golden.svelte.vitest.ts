/**
 * #310 C1: the History list's filter and sort as the web does them today,
 * pinned as a golden the core's reference rules are checked against
 * (`core/de1-history/tests/shells.rs`). The fixture is 300 real-shaped rows
 * (`core/de1-history/tests/fixtures/`, written by its `gen.mjs`); each query
 * runs through the page's own `HistoryListModel.filtered`, with the page's
 * effective tags and live coffee lookups over the fixture's bean library.
 *
 * Regenerate with `CREMA_REGEN_HISTORY_GOLDEN=1 pnpm vitest run
 * src/lib/history/reference-golden.svelte.vitest.ts`.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushSync } from 'svelte';
import { HistoryListModel, type ListInputs } from './list-model.svelte';
import type { SortField } from './list-filter';
import type { ShotRow } from './model';
import { rangeCutoff } from './range';

const FIXTURES = resolve(process.cwd(), '../core/de1-history/tests/fixtures');
const read = (name: string) => readFileSync(resolve(FIXTURES, name), 'utf8');

interface LiveBean {
	beanId: string;
	name: string;
	roaster: string | null;
	tags: string[];
}

interface FixtureQuery {
	name: string;
	range: 'all' | '7d' | '30d';
	text: string;
	profile: string | null;
	bean: string | null;
	method: string | null;
	tags: string[];
	pinnedOnly: boolean;
	sort: { key: 'date' | 'rating' | 'profile' | 'bean' | 'yield' | 'time'; dir: 'asc' | 'desc' };
}

/** The web's sorts; yield and time it doesn't offer. */
const SORT_FIELDS: Partial<Record<FixtureQuery['sort']['key'], SortField>> = {
	date: 'completedAt',
	rating: 'rating',
	profile: 'profileName',
	bean: 'beanName'
};

const rows: ShotRow[] = read('rows.web.jsonl')
	.trim()
	.split('\n')
	.map((l) => JSON.parse(l) as ShotRow);
const live = JSON.parse(read('live.json')) as { beans: LiveBean[] };
const { now, queries } = JSON.parse(read('queries.json')) as { now: number; queries: FixtureQuery[] };
const beans = new Map(live.beans.map((b) => [b.beanId, b]));
const indexOf = new Map(rows.map((r, i) => [r.id, i]));

/** The page's `effectiveShotTags`: the row's own tags, else its live bag's. */
function tagsOf(s: ShotRow): readonly string[] {
	if (s.tags && s.tags.length > 0) return s.tags;
	const beanId = s.bean?.beanId ?? null;
	return beanId ? (beans.get(beanId)?.tags ?? []) : [];
}

/** The rows `q` lists on web today, as fixture indexes. */
function listed(q: FixtureQuery, sortField: SortField): number[] {
	const filters: ListInputs = {
		cutoff: rangeCutoff(q.range, now),
		profile: q.profile ?? 'all',
		pinnedOnly: q.pinnedOnly,
		bean: q.bean ?? 'all',
		method: q.method ?? 'all',
		tags: q.tags,
		sortField,
		sortDir: q.sort.dir
	};
	let model!: HistoryListModel;
	const stop = $effect.root(() => {
		model = new HistoryListModel({
			rows: () => rows,
			get: (id) => rows[indexOf.get(id) ?? -1],
			query: () => q.text,
			filters: () => filters,
			tagsOf,
			liveCoffee: (beanId) => {
				const b = beans.get(beanId);
				return b ? { name: b.name, roaster: b.roaster } : null;
			}
		});
	});
	flushSync();
	const out = model.filtered.map((r) => indexOf.get(r.id) ?? -1);
	stop();
	return out;
}

describe('the web History filter, pinned for the core reference (#310 C1)', () => {
	it('matches the golden on every query the web offers', () => {
		const golden: Record<string, number[]> = {};
		for (const q of queries) {
			const field = SORT_FIELDS[q.sort.key];
			if (!field) continue;
			golden[q.name] = listed(q, field);
		}
		expect(Object.keys(golden).length).toBeGreaterThan(30);
		const file = resolve(FIXTURES, 'golden.web.json');
		if (process.env.CREMA_REGEN_HISTORY_GOLDEN === '1') {
			writeFileSync(file, JSON.stringify(golden) + '\n');
		}
		expect(JSON.parse(readFileSync(file, 'utf8'))).toEqual(golden);
	});
});
