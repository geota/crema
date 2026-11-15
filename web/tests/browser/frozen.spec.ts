/**
 * W0 frozen owner (#310 PLAN W0, SPEC §8 "Owner can't answer", owner
 * question 4): the owner tab frozen through CDP `Page.setWebLifecycleState`,
 * as Chrome freezes a background tab. Records whether a frozen owner keeps
 * its Web Lock and its pool handles, and whether its page or its Worker still
 * answers on `crema-history-rpc`. Then a stage-2 client in the other tab goes
 * read-only after 5 s without an answer and puts a new shot in the pending
 * journal (one `localStorage` key per entry, M5), and the owner, thawed,
 * drains it into the store.
 *
 * Chrome only. It runs on a bare CDP connection (`cdp.ts`): a page Playwright
 * is attached to never freezes. WebKit has no lifecycle freeze to drive.
 */
import { launchRaw, rawBrowser, type RawTab } from './cdp.ts';
import { BASE, expect, HARNESS, isAndroid, test } from './fixtures.ts';

const LOCK = 'crema-history-db';

const call = <T = unknown>(t: RawTab, op: string, args: Record<string, unknown> = {}) =>
	t.eval<T>(`window.w0.call(${JSON.stringify(op)}, ${JSON.stringify(args)})`);
const tryCall = <T = unknown>(t: RawTab, op: string, args: Record<string, unknown> = {}) =>
	t.eval<{ ok: boolean; value?: T; error?: { code: string } }>(
		`window.w0.try(${JSON.stringify(op)}, ${JSON.stringify(args)})`
	);
const ask = (t: RawTab, ms: number) => t.eval<string[]>(`window.w0.ask(${ms})`).then((a) => [...a].sort());

test('a frozen owner keeps its lock and handles; the other tab goes read-only and journals', async ({
	browserName,
	gen
}, testInfo) => {
	test.skip(browserName !== 'chromium', 'needs CDP Page.setWebLifecycleState');
	const android = isAndroid(testInfo.project.name);
	const browser = android
		? rawBrowser(process.env.W0_ANDROID_CDP ?? 'http://127.0.0.1:9222')
		: await launchRaw();
	try {
		const url = BASE + HARNESS;
		const owner = await browser.open(url);
		const other = await browser.open(url);
		await owner.eval('window.w0.start()');
		await other.eval('window.w0.start()');
		const g = gen();
		await call(owner, 'lock', { name: LOCK });
		await call(owner, 'install', { pool: 'db', generation: g });
		await call(owner, 'recover', { h: 'db', opts: { place: 'db', file: 'history.db', generation: 1, journal: 'truncate' } });
		await call(owner, 'writeVersion', { h: 'db', v: 0, k: 20 });
		await owner.eval(`window.w0.serve('owner')`);
		expect(await ask(other, 1_000)).toEqual(['page', 'worker']);

		// The page's own view of the freeze: its `freeze` event, and a timer that stops.
		await owner.eval(
			`window.__froze = 0; window.__ticks = 0; document.addEventListener('freeze', () => window.__froze++); setInterval(() => window.__ticks++, 100); 1`
		);
		await owner.send('Page.setWebLifecycleState', { state: 'frozen' });
		// The page freezes at once; its Worker a moment later. Ask after that.
		const settle = Date.now();
		let workerFrozeAfterMs = -1;
		while (Date.now() - settle < 5_000) {
			if (!(await ask(other, 250)).includes('worker')) {
				workerFrozeAfterMs = Date.now() - settle;
				break;
			}
		}
		const t0 = Date.now();
		const state = await other.eval<string>('window.w0.checkOwner(5000)');
		const waited = Date.now() - t0;
		const answeredWhileFrozen = await ask(other, 1_000);
		// What the frozen owner kept.
		const lockFree = await call<{ held: boolean }>(other, 'lock', { name: LOCK, ifAvailable: true });
		const locks = await call<{ held: string[] }>(other, 'locks');
		const install = await tryCall(other, 'install', { pool: 'db', generation: g });

		const result = {
			browser: testInfo.project.name,
			frozenOwnerKeptLock: !lockFree.held,
			frozenOwnerKeptHandles: !install.ok,
			otherTabInstallError: install.error?.code,
			pageAnsweredWhileFrozen: answeredWhileFrozen.includes('page'),
			workerAnsweredWhileFrozen: answeredWhileFrozen.includes('worker'),
			workerFrozeWithinMs: workerFrozeAfterMs,
			otherTabState: state,
			readOnlyAfterMs: waited
		};
		console.log(`W0 frozen ${JSON.stringify(result)}`);
		await testInfo.attach('frozen-owner', { contentType: 'application/json', body: JSON.stringify(result, null, 2) });

		expect(answeredWhileFrozen, 'a frozen owner answers nothing').not.toContain('worker');
		expect(state).toBe('readOnly');
		expect(waited).toBeGreaterThanOrEqual(5_000);
		expect(lockFree.held, 'the frozen owner kept the lock').toBe(false);
		expect(locks.held).toContain(LOCK);
		expect(install.ok, 'the frozen owner kept the pool handles').toBe(false);

		// A shot while read-only goes to the pending journal.
		const shot = {
			id: 'shot:0190a0c1-0000-7000-8000-00000000f0f0',
			completedAt: 1_790_000_000_000,
			profileName: 'Frozen',
			metadata: { dose: 18, notes: 'captured while the owner was frozen' },
			record: { duration: 30000 },
			summary: { v: 1, sampleCount: 120 }
		};
		expect(await other.eval(`window.w0.capture(${JSON.stringify(shot)})`)).toBe('journaled');
		expect(await other.eval('window.w0.pending()')).toEqual([`crema.history.sqlite.pending.${shot.id}`]);

		// Thawed, the owner answers again and drains the journal into the store.
		const ticksAtThaw = await owner.eval<number>('window.__ticks');
		await owner.send('Page.setWebLifecycleState', { state: 'active' });
		const pageSaw = await owner.eval<{ froze: number; ticks: number }>('({ froze: window.__froze, ticks: window.__ticks })');
		console.log(`W0 frozen page ${JSON.stringify({ ...pageSaw, ticksAtThaw })}`);
		expect(await ask(other, 1_000)).toEqual(['page', 'worker']);
		expect(await owner.eval(`window.w0.drain('db')`)).toEqual([shot.id]);
		expect(await other.eval('window.w0.pending()')).toEqual([]);
		expect(await call<unknown[]>(owner, 'call', { h: 'db', m: 'get', a: [shot.id] })).toHaveLength(1);
		// A second drain finds nothing (insert-if-absent, removed after commit).
		expect(await owner.eval(`window.w0.drain('db')`)).toEqual([]);
		await call(owner, 'closeAll');
	} finally {
		await browser.close();
	}
});
