/**
 * W1 in real browsers (#310 PLAN W1, "Done when"): the app's `HistoryDbClient`
 * and history Worker, with real Web Locks, `BroadcastChannel`s and OPFS pools.
 * - Requests answer from either tab, and after a reload.
 * - Closing the owner moves ownership, and requests in flight retry.
 * - The last user action wins across tabs.
 * - An owner that can't answer (frozen; Chromium, over bare CDP) leaves the
 *   other tab read-only on stage 2, with a new shot in the pending journal,
 *   which the thawed owner drains.
 * - Stage 1 makes its index in `crema-index` and never installs `crema-db`.
 * - No path deletes an OPFS file: every file there before is there after.
 */
import type { BrowserContext, Page } from '@playwright/test';
import { launchRaw } from './cdp.ts';
import { BASE, dbDir, expect, indexDir, listFiles, newTruth, test, type Tab } from './fixtures.ts';

const row = (id: string, rating: number) => JSON.stringify({ id, completedAt: 1_790_000_000_000, metadata: { rating } });

/**
 * `history.db` in `crema-db` generation `g`, made through W0's harness, then
 * let go. Tests keep apart by generation, and a generation above 0 is a
 * repair generation, which the owner installs only with a `.repair-done`
 * naming the file (REVIEW-444 notes 2, 3): so it gets one.
 */
async function makeTruth(t: Tab, g: number) {
	await newTruth(t, g, { k: 3 });
	await t.call('close', { h: 'db' });
	await t.call('uninstall', { pool: 'db' });
	await t.call('writeText', { path: `${dbDir(g)}/.repair-done`, text: JSON.stringify({ copied: ['/history.db'] }) });
}

async function w1(ctx: BrowserContext, stage: 1 | 2, g: number): Promise<Page> {
	const page = await ctx.newPage();
	await page.goto(`${BASE}/tests/browser/w1/index.html?stage=${stage}&g=${g}`);
	await page.waitForFunction(() => document.body.dataset.ready === '1');
	return page;
}

const status = (p: Page) => p.evaluate(() => window.h1.status());
const until = (p: Page, want: Record<string, unknown>, ms = 20_000) =>
	p.evaluate(([w, ms]) => window.h1.until(w as never, ms as number), [want, ms] as const);
const read = (p: Page, name: string, arg: unknown = {}) => p.evaluate(([n, a]) => window.h1.read(n, a), [name, arg] as const);
const rate = (p: Page, id: string, r: number) =>
	p.evaluate(([id, r]) => window.h1.write({ kind: 'upsert', rows: [{ rowJson: JSON.stringify({ id, completedAt: 1_790_000_000_000, metadata: { rating: r } }) }] }), [id, r] as const);

test('two tabs and a reload: either tab answers, ownership moves, in-flight requests retry, the last action wins', async ({
	profile,
	tab,
	gen
}) => {
	const g = gen();
	const h = await tab();
	await makeTruth(h, g);
	const before = await listFiles(h, dbDir(g));
	await h.page.close();

	const a = await w1(profile, 2, g);
	await until(a, { health: 'ok', ownerHere: true });
	const b = await w1(profile, 2, g);
	await until(b, { health: 'ok', ownerHere: false });
	expect(await read(b, 'count')).toEqual({ ok: true, value: 3 });
	expect(await rate(b, 'r', 1)).toMatchObject({ ok: true, value: { outcome: 'done' } });
	expect(await read(a, 'count')).toEqual({ ok: true, value: 4 });

	// The last action wins, whichever tab makes it.
	for (let r = 2; r <= 7; r++) expect(await rate(r % 2 ? a : b, 'r', r)).toMatchObject({ ok: true });
	const got = (await read(a, 'get', ['r'])) as { value: { rowJson: string }[] };
	expect(JSON.parse(got.value[0].rowJson).metadata.rating).toBe(7);

	// A reload of the owner: pagehide lets go, B takes over, and A comes back a client.
	await a.reload();
	await a.waitForFunction(() => document.body.dataset.ready === '1');
	await until(b, { health: 'ok', ownerHere: true });
	await until(a, { health: 'ok' });
	expect(await read(a, 'count')).toEqual({ ok: true, value: 4 });

	// Close the owner with a write from A in flight: it retries to the next owner.
	const inFlight = a.evaluate(() => window.h1.write({ kind: 'upsert', rows: [{ rowJson: JSON.stringify({ id: 'late', completedAt: 1, metadata: {} }) }] }));
	await b.close({ runBeforeUnload: false });
	expect(await inFlight).toMatchObject({ ok: true });
	await until(a, { health: 'ok', ownerHere: true });
	expect(await read(a, 'get', ['late'])).toMatchObject({ ok: true, value: [{ id: 'late' }] });
	expect(await read(a, 'count')).toEqual({ ok: true, value: 5 });

	// Nothing went missing.
	await a.evaluate(() => window.h1.stop());
	const h2 = await tab();
	const after = new Set((await listFiles(h2, dbDir(g))).map((e) => e.path));
	for (const e of before) expect(after, e.path).toContain(e.path);
});

test('stage 1 makes its index in crema-index and never installs crema-db', async ({ profile, tab, gen }) => {
	const g = gen();
	const a = await w1(profile, 1, g);
	await until(a, { health: 'ok', ownerHere: true });
	const b = await w1(profile, 1, g);
	await until(b, { health: 'ok', ownerHere: false });
	expect(await read(b, 'count')).toEqual({ ok: true, value: 0 });
	// Stage 1 writes the P3 store; no tab writes the index.
	expect(await rate(b, 'x', 1)).toMatchObject({ ok: false, code: 'stage1' });
	await a.evaluate(() => window.h1.stop());
	await b.evaluate(() => window.h1.stop());
	const h = await tab();
	const files = await listFiles(h, indexDir(g), dbDir(g));
	expect(files.some((f) => f.path.startsWith(`${indexDir(g)}/`))).toBe(true);
	expect(files.some((f) => f.path.startsWith(`${dbDir(g)}/`))).toBe(false);
});

test('stop (factory reset) closes, pauses and lets go: the pool can be installed elsewhere at once', async ({ profile, tab, gen }) => {
	const g = gen();
	const h = await tab();
	await makeTruth(h, g);
	await h.page.close();
	const a = await w1(profile, 2, g);
	await until(a, { health: 'ok', ownerHere: true });
	await a.evaluate(() => window.h1.stop());
	const h2 = await tab();
	expect(await h2.try('install', { pool: 'db', generation: g })).toMatchObject({ ok: true });
	expect(await h2.call('locks')).toMatchObject({ held: expect.not.arrayContaining(['crema-history-db']) });
});

test('a frozen owner: the other tab goes read-only and journals a shot; thawed, the owner drains it', async ({
	browserName,
	tab,
	gen,
	profile
}, testInfo) => {
	test.skip(browserName !== 'chromium' || testInfo.project.name === 'android-chrome', 'needs a freeze through bare CDP');
	const g = gen();
	// The truth file, in this test's own profile... but the frozen tabs run in a
	// Chrome of their own (cdp.ts), so make it there instead.
	void profile;
	void tab;
	const browser = await launchRaw();
	try {
		const harness = await browser.open(`${BASE}/tests/browser/harness/index.html`);
		await harness.eval('window.w0.start()');
		const call = (expr: string) => harness.eval(expr);
		await call(`window.w0.call('install', { pool: 'db', generation: ${g} })`);
		await call(`window.w0.call('recover', { h: 'db', opts: { place: 'db', file: 'history.db', generation: 1, journal: 'truncate' } })`);
		await call(`window.w0.call('close', { h: 'db' })`);
		await call(`window.w0.call('uninstall', { pool: 'db' })`);
		await call(`window.w0.call('writeText', { path: '.crema-history-db-${g}/.repair-done', text: ${JSON.stringify(JSON.stringify({ copied: ['/history.db'] }))} })`);
		await harness.close();

		const owner = await browser.open(`${BASE}/tests/browser/w1/index.html?stage=2&g=${g}`);
		await owner.eval(`window.h1.until({ health: 'ok', ownerHere: true })`);
		const other = await browser.open(`${BASE}/tests/browser/w1/index.html?stage=2&g=${g}`);
		await other.eval(`window.h1.until({ health: 'ok', ownerHere: false })`);

		await owner.send('Page.setWebLifecycleState', { state: 'frozen' });
		void other.eval(`window.h1.read('count', {})`);
		const s = await other.eval<{ health: string; reason: string; message: string }>(
			`window.h1.until({ health: 'unavailable', reason: 'noAnswer' }, 15000)`
		);
		expect(s.message).toBe('History is open in another window');
		expect(await other.eval(`window.h1.write({ kind: 'delete', ids: ['x'] })`)).toMatchObject({ ok: false, code: 'readOnly' });
		expect(await other.eval(`window.h1.capture(${JSON.stringify(row('shot:frozen', 0))})`)).toEqual({ ok: true, value: 'journaled' });
		expect(await other.eval('window.h1.pending()')).toEqual(['crema.history.sqlite.pending.shot:frozen']);

		await owner.send('Page.setWebLifecycleState', { state: 'active' });
		await other.eval(`window.h1.until({ health: 'ok' }, 20000)`);
		await owner.eval(`new Promise((r) => setTimeout(r, 1500))`);
		expect(await owner.eval(`window.h1.read('get', ['shot:frozen']).then((r) => r.value.length)`)).toBe(1);
		expect(await other.eval('window.h1.pending()')).toEqual([]);
	} finally {
		await browser.close();
	}
});
