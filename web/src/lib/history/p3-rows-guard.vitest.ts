/**
 * #310 W3 (N1): nothing outside the store's own seams reads the whole list
 * of rows. The store has no `all`, `rawAll` or `allLive`, so a reader, a
 * destructuring or an alias of one fails to type-check; and only the files
 * named here import `p3-rows.ts`, the one way to the rows.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { HistoryStore } from './store.svelte';

const SRC = resolve(process.cwd(), 'src');

/** Who may read P3w's whole list on stage 1, and why. */
const ALLOWED: Record<string, string> = {
	'lib/history/store.svelte.ts': 'the store itself',
	'lib/history/reads.svelte.ts': "the queries' P3w answers and the dev comparison",
	'lib/history/sync-view.ts': "sync's planners and the upload backlogs (W3's decision: P3w until W5)",
	'lib/history-source/p3.ts': 'the P3 backup source: backups and restore read the truth (W5 switches the seam)',
	'routes/history/+page.svelte': "`index_fallback`'s in-memory list and the W2 dev oracle",
	'lib/history-source/cross-kit.ts': 'a test kit'
};

function files(dir: string, out: string[] = []): string[] {
	for (const name of readdirSync(dir)) {
		const path = join(dir, name);
		if (statSync(path).isDirectory()) {
			if (name === 'wasm' || name === 'node_modules') continue;
			files(path, out);
		} else if (/\.(ts|svelte)$/.test(name) && !/\.(vitest|test|spec)\.ts$/.test(name) && !name.endsWith('.d.ts')) {
			out.push(path);
		}
	}
	return out;
}

describe('the whole list stays behind the store (N1)', () => {
	it('the store has no `all`, `rawAll` or `allLive`', () => {
		for (const name of ['all', 'rawAll', 'allLive']) {
			expect(name in HistoryStore.prototype, name).toBe(false);
		}
	});

	it('only the store’s seams import `p3-rows`', () => {
		const importers = files(SRC)
			.filter((f) => !f.endsWith(join('lib', 'history', 'p3-rows.ts')))
			.filter((f) => /from ['"][^'"]*\/p3-rows(\.js)?['"]|from ['"]\.\/p3-rows['"]/.test(readFileSync(f, 'utf8')))
			.map((f) => relative(SRC, f).split('\\').join('/'))
			.sort();
		expect(importers).toEqual(Object.keys(ALLOWED).sort());
	});

	it('no reader names a store’s whole list', () => {
		const offenders: string[] = [];
		for (const f of files(SRC)) {
			const text = readFileSync(f, 'utf8');
			const rel = relative(SRC, f).split('\\').join('/');
			for (const [i, line] of text.split('\n').entries()) {
				if (/\b(history|historyStore|getHistoryStore\(\))\??\.(all|rawAll|allLive)\b/.test(line)) offenders.push(`${rel}:${i + 1}`);
			}
		}
		expect(offenders).toEqual([]);
	});
});
