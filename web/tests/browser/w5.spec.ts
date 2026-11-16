/**
 * W5 in Chrome (#310 PLAN W5): the flip on real OPFS, by the app's boot
 * (`stage.ts`) and history Worker. A `crema-history` of B1 rows becomes
 * `history.db` in `crema-db`: every row equal, deep; the count, a search and
 * the stats as the index gave them before; the done-record at the OPFS root
 * and the marker after it; a reload boots stage 2 from the marker. The tab
 * killed at each step of the flip: the next boot flips, or finds the file the
 * killed one finished, and never loses a row. A second tab open: no flip.
 *
 * Chromium only: the done-record's name is fixed at the OPFS root, and
 * Playwright's WebKit shares one OPFS between profiles (README).
 */
import type { BrowserContext, Page } from '@playwright/test';
import { BASE, expect, test } from './fixtures.ts';

type Boot = { stage: 1 | 2; reason: string; flip: { outcome: string; reason?: string } | null; file: string; ms: number };

declare global {
	interface Window {
		/** `w5/page.ts` (typed here: the page itself is outside this config). */
		h5: {
			seed(n: number): Promise<void>;
			boot(o?: { windows?: number | null; allowFlip?: boolean }): Promise<Boot>;
			open(ms?: number): Promise<string>;
			count(): Promise<unknown>;
			stats(): Promise<unknown>;
			search(text: string): Promise<unknown>;
			compare(): Promise<{ db: number; p3: number; differ: string[] }>;
			marker(): unknown;
			doneRecord(): Promise<string | null>;
			stop(): Promise<void> | undefined;
		};
	}
}

const N = Number(process.env.W5_ROWS ?? 20_000);

async function w5(ctx: BrowserContext, onStep?: (page: Page, step: string) => void): Promise<Page> {
	const page = await ctx.newPage();
	const steps: string[] = [];
	await page.exposeBinding('__w5Step', (_src, step: string) => {
		steps.push(step);
		onStep?.(page, step);
	});
	page.on('console', (m) => {
		if (m.text().startsWith('history-')) console.log(`[page] ${m.text()}`);
	});
	await page.goto(`${BASE}/tests/browser/w5/index.html`);
	await page.waitForFunction(() => document.body.dataset.ready === '1');
	return page;
}

test.beforeEach(({ browserName }) => {
	test.skip(browserName !== 'chromium', 'the flip on OPFS, in Chrome');
});

test(`a ${N.toLocaleString('en')}-row P3 store flips: the same rows, search and stats, then a reload boots stage 2`, async ({ profile }) => {
	test.setTimeout(300_000);
	const page = await w5(profile);
	await page.evaluate((n) => window.h5.seed(n), N);
	// Stage 1 first: what the index answers before the flip.
	expect(await page.evaluate(() => window.h5.boot({ allowFlip: false }))).toMatchObject({ stage: 1, reason: 'noFlip' });
	await page.evaluate(() => window.h5.open(180_000));
	const before = await page.evaluate(async () => ({
		count: await window.h5.count(),
		stats: await window.h5.stats(),
		search: await window.h5.search('kenya')
	}));
	expect(before.count).toBe(N);
	await page.evaluate(() => window.h5.stop());
	await page.close();

	// A fresh boot flips.
	const again = await w5(profile);
	const boot = await again.evaluate(() => window.h5.boot());
	console.log(`W5 flip at ${N} rows: ${Math.round(boot.ms)} ms`);
	expect(boot).toMatchObject({ stage: 2, reason: 'flipped', file: 'history.db', flip: { outcome: 'done' } });
	expect(await again.evaluate(() => window.h5.marker())).toMatchObject({ state: 'done', generation: 0, poolGeneration: 0 });
	expect(JSON.parse((await again.evaluate(() => window.h5.doneRecord())) ?? 'null')).toMatchObject({ state: 'done', generation: 0 });
	expect(await again.evaluate(() => window.h5.open())).toBe('ok');
	const cmp = await again.evaluate(() => window.h5.compare());
	expect(cmp).toEqual({ db: N, p3: N, differ: [] });
	const after = await again.evaluate(async () => ({
		count: await window.h5.count(),
		stats: await window.h5.stats(),
		search: await window.h5.search('kenya')
	}));
	expect(after).toEqual(before);
	await again.evaluate(() => window.h5.stop());
	await again.close();

	// A reload: stage 2 from the marker, no second flip.
	const third = await w5(profile);
	expect(await third.evaluate(() => window.h5.boot())).toMatchObject({ stage: 2, reason: 'marker' });
	expect(await third.evaluate(() => window.h5.open())).toBe('ok');
	expect(await third.evaluate(() => window.h5.count())).toBe(N);
});

for (const step of ['diff', 'copy', 'copied', 'fts', 'verify', 'done', 'doneRecord']) {
	test(`the tab killed at the flip's "${step}" step: the next boot is on history.db with every row`, async ({ profile }) => {
		test.setTimeout(120_000);
		let killed = false;
		const page = await w5(profile, (p, s) => {
			if (s === step && !killed) {
				killed = true;
				void p.close().catch(() => {});
			}
		});
		await page.evaluate(() => window.h5.seed(600));
		await page.evaluate(() => window.h5.boot()).catch(() => null);
		await expect.poll(() => killed).toBe(true);
		const next = await w5(profile);
		const boot = await next.evaluate(() => window.h5.boot());
		expect(boot.stage, JSON.stringify(boot)).toBe(2);
		expect(['flipped', 'found', 'doneRecord', 'doneRecordUnreadable']).toContain(boot.reason);
		expect(await next.evaluate(() => window.h5.open())).toBe('ok');
		expect(await next.evaluate(() => window.h5.compare())).toEqual({ db: 600, p3: 600, differ: [] });
	});
}

test('a second tab open: no flip this boot, and nothing in crema-db', async ({ profile }) => {
	const a = await w5(profile);
	await a.evaluate(() => window.h5.seed(50));
	expect(await a.evaluate(() => window.h5.boot({ allowFlip: false }))).toMatchObject({ stage: 1 });
	await a.evaluate(() => window.h5.open());
	const b = await w5(profile);
	expect(await b.evaluate(() => window.h5.boot())).toMatchObject({ stage: 1, reason: 'waiting:otherTab' });
	expect(await b.evaluate(() => window.h5.marker())).toMatchObject({ state: 'pending', waiting: 'another window is open' });
	// The open tab closes: the next boot flips.
	await a.close();
	await b.close();
	const c = await w5(profile);
	expect(await c.evaluate(() => window.h5.boot())).toMatchObject({ stage: 2, reason: 'flipped' });
});
