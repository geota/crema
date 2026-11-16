/**
 * W2 in Chrome (#310 PLAN W2, "Done when"): a `crema-history` of 20,000 B1
 * rows, the app's stage-1 client and Worker, and the History list's reads
 * through the Worker within B1's budgets at 20,000 rows: the stage-1 rebuild
 * (the full diff, 6 s), its longest single index call (50 ms), open to the first
 * page (400 ms), `count` and the first page under any sort (60 ms), the next
 * page by keyset (10 ms), an offset jump to the end (80 ms), a search
 * (60 ms), the stats (60 ms) and the facets (80 ms). Then a write's notice
 * reaches the index, and a reload's boot check passes with no diff.
 *
 * Runs in the `bench` project, alone after the others: these are timings.
 * `W2_BENCH_ROWS` picks the size. Every line is gated on a developer's
 * machine; on CI's shared runners, as in `bench.spec.ts`, only the first page
 * and the rebuild are, and the rest is recorded (`W2_BENCH_STRICT=1` gates it
 * all there too): the budgets are the reference device's.
 */
import type { Page } from '@playwright/test';
import { BASE, expect, test } from './fixtures.ts';

declare global {
	interface Window {
		/** `w2/page.ts` (typed here: the page itself is outside this config). */
		h2: {
			seed(n: number): Promise<void>;
			open(ms?: number): Promise<{ readyMs: number; waitMs: number; probes: number; longestCallMs: number }>;
			openToFirstPage(): Promise<number>;
			reads(): Promise<Record<string, number>>;
			count(): Promise<unknown>;
			stop(): Promise<void> | undefined;
		};
	}
}

const N = Number(process.env.W2_BENCH_ROWS ?? 20_000);
/** B1's budgets at 20,000 rows, ms. */
const BUDGET = {
	rebuild: 6_000,
	longest: 50,
	// Not a B1 line: a tab's read during the diff also waits for the Worker's
	// own work beside the index call (reading 500 P3 rows from IndexedDB, as
	// text): twice the transaction budget, to catch a batch grown too big.
	wait: 100,
	open: 400,
	first: 60,
	next: 10,
	jump: 80,
	search: 60,
	stats: 60,
	facets: 80
};

/** What CI gates: the first page and the rebuild. */
const GATED_ON_CI = new Set(['rebuild', 'open', 'first']);

async function w2(page: Page, g: number): Promise<void> {
	await page.goto(`${BASE}/tests/browser/w2/index.html?g=${g}`);
	await page.waitForFunction(() => document.body.dataset.ready === '1');
}

test(`the History list's reads through the index at ${N.toLocaleString('en')} rows meet B1's budgets`, async ({
	profile,
	gen,
	browserName
}, testInfo) => {
	test.skip(browserName !== 'chromium', 'B1 in Chrome');
	test.setTimeout(180_000);
	const g = gen();
	const page = await profile.newPage();
	await w2(page, g);
	await page.evaluate((n) => window.h2.seed(n), N);
	// The first boot: the index is empty, so a full diff builds it.
	const open = await page.evaluate(() => window.h2.open());
	expect(await page.evaluate(() => window.h2.count())).toBe(N);
	const reads = await page.evaluate(() => window.h2.reads());
	await page.evaluate(() => window.h2.stop());
	// A reload: the boot check passes, so the index answers at once.
	await w2(page, g);
	const openMs = await page.evaluate(() => window.h2.openToFirstPage());
	const got = { rebuild: open.readyMs, longest: open.longestCallMs, wait: open.waitMs, open: openMs, ...reads };
	console.log(`W2 at ${N} rows (ms): ${JSON.stringify(got)}; ${open.probes} probes during the diff`);
	await testInfo.attach('w2-bench', { contentType: 'application/json', body: JSON.stringify({ rows: N, got, budget: BUDGET }) });
	const strict = !process.env.CI || process.env.W2_BENCH_STRICT === '1';
	for (const [k, budget] of Object.entries(BUDGET)) {
		if (!strict && !GATED_ON_CI.has(k)) continue;
		expect.soft(got[k as keyof typeof got], `${k} within ${budget} ms`).toBeLessThanOrEqual(budget);
	}
	await page.evaluate(() => window.h2.stop());
});
