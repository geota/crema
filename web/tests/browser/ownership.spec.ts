/**
 * W0 ownership and handover (#310 PLAN W0, SPEC §8 "Owner", "Handover", S1):
 * two tabs of one profile, each with its own history Worker.
 * - The second tab can't take the lock or install the pool while the first
 *   holds them. The lock, held by the Worker, moves when the first lets go.
 * - `pause` with a connection open fails with `FilesInUse`; after every
 *   connection closes, `pause` frees the handles and the other tab installs.
 * - After an unplanned close (the owner tab killed), the next owner's
 *   install, retried with SPEC §8's backoff, succeeds and finds every row.
 */
import { dbDir, expect, isAndroid, listFiles, newTruth, test } from './fixtures.ts';

const LOCK = 'crema-history-db';

test('a second tab can neither lock nor install; pause frees the handles and the lock moves', async ({
	tab,
	gen
}, testInfo) => {
	const g = gen();
	const a = await tab();
	const b = await tab();
	expect(await a.call('lock', { name: LOCK })).toEqual({ held: true });
	await newTruth(a, g);

	// B: the lock is taken, and the pool's handles are A's.
	expect(await b.call('lock', { name: LOCK, ifAvailable: true })).toEqual({ held: false });
	expect((await b.call<{ held: string[] }>('locks')).held).toContain(LOCK);
	const refused = await b.try('install', { pool: 'db', generation: g });
	expect(refused.ok).toBe(false);
	const refusedCode = String(refused.error?.code);
	expect((await b.try('poolInfo', { pool: 'db' })).error?.code).toBe('pool.notInstalled');

	// A: `pause` refuses while a connection is open (S1).
	const busy = await a.try('pause', { pool: 'db' });
	expect(busy.error?.code).toBe('pool.filesInUse');
	// Close every connection, then pause: the handles go.
	await a.call('close', { h: 'db' });
	await a.call('pause', { pool: 'db' });
	expect((await a.call<{ paused: boolean }>('poolInfo', { pool: 'db' })).paused).toBe(true);

	// B queues for the lock; A releases it; B gets it and installs.
	const since = b.events.length;
	await b.call('queueLock', { name: LOCK });
	expect(await a.call('unlock', { name: LOCK })).toBe(true);
	await b.waitEvent((e) => e.event === 'lockGranted', 10_000, since);
	const inst = await b.call<{ ok: boolean; attempts: number; elapsedMs: number }>('installRetry', {
		pool: 'db',
		generation: g,
		budgetMs: 30_000
	});
	expect(inst.ok, JSON.stringify(inst)).toBe(true);
	await b.call('open', { h: 'db', opts: { place: 'db', file: 'history.db', role: 'authority', journal: 'truncate' } });
	expect(await b.call('versions', { h: 'db', k: 20 })).toEqual({ byVersion: { 0: 20 }, missing: 0 });
	await b.call('writeVersion', { h: 'db', v: 1, k: 20 });

	// A can't take its handles back while B holds them.
	const resumed = await a.try('resume', { pool: 'db' });
	expect(resumed.ok).toBe(false);

	await testInfo.attach('ownership', {
		contentType: 'application/json',
		body: JSON.stringify(
			{
				secondInstallRefusedWith: refusedCode,
				refusedMessage: refused.error?.message,
				pauseWithOpenConnection: busy.error?.code,
				handoverInstallAttempts: inst.attempts,
				handoverInstallMs: Math.round(inst.elapsedMs),
				resumeWhileOtherHolds: resumed.error?.code
			},
			null,
			2
		)
	});
	console.log(`W0 ownership ${testInfo.project.name} second install refused with ${refusedCode}; resume while held: ${String(resumed.error?.code)}`);
});

test('after an unplanned close the next owner installs with backoff and finds every row', async ({
	tab,
	gen,
	browserName
}, testInfo) => {
	const g = gen();
	const a = await tab();
	const b = await tab();
	await a.call('lock', { name: LOCK });
	await newTruth(a, g, { k: 50 });
	await a.call('writeVersion', { h: 'db', v: 1, k: 50 });
	const before = await listFiles(b, dbDir(g));

	const since = b.events.length;
	await b.call('queueLock', { name: LOCK });
	// B's install starts while A still holds the handles, so it has to retry.
	const installing = b.call<{ ok: boolean; attempts: number; elapsedMs: number; codes: string[] }>(
		'installRetry',
		{ pool: 'db', generation: g, budgetMs: 30_000 }
	);
	await new Promise((r) => setTimeout(r, 300));
	// Unplanned: no close, no pause, no unlock. The Worker dies holding all three.
	// Chromium crashes the renderer. Android Chrome ignores `Page.crash` for a
	// background tab, so there, and in WebKit, the tab is closed instead: the
	// harness has no `pagehide` handler, so its Worker goes holding everything.
	const crash = browserName === 'chromium' && !isAndroid(testInfo.project.name);
	if (crash) await a.crash();
	await a.page.close({ runBeforeUnload: false });
	const granted = await b.waitEvent((e) => e.event === 'lockGranted', 15_000, since);
	const inst = await installing;
	expect(inst.ok, JSON.stringify(inst)).toBe(true);
	expect(inst.attempts, 'the install was refused while A lived, then retried').toBeGreaterThan(1);
	await b.call('open', { h: 'db', opts: { place: 'db', file: 'history.db', role: 'authority', journal: 'truncate' } });
	expect(await b.call('versions', { h: 'db', k: 50 })).toEqual({ byVersion: { 1: 50 }, missing: 0 });
	// Nothing went missing: every file the dead owner had is still there.
	const after = new Set((await listFiles(b, dbDir(g))).map((e) => e.path));
	for (const e of before) expect(after, e.path).toContain(e.path);
	console.log(`W0 handover ${testInfo.project.name} ${JSON.stringify({ attempts: inst.attempts, ms: Math.round(inst.elapsedMs), codes: inst.codes })}`);
	await testInfo.attach('unplanned-handover', {
		contentType: 'application/json',
		body: JSON.stringify({ kill: crash ? 'Page.crash' : 'page.close', lockGrantedAt: granted.t, ...inst }, null, 2)
	});
});

test('pause then resume in one Worker gives the same file back', async ({ tab, gen }) => {
	const g = gen();
	const a = await tab();
	await newTruth(a, g);
	await a.call('close', { h: 'db' });
	await a.call('pause', { pool: 'db' });
	const paused = await a.call<{ paused: boolean; files: string[] }>('poolInfo', { pool: 'db' });
	expect(paused).toMatchObject({ paused: true, files: [] });
	await a.call('resume', { pool: 'db' });
	const resumed = await a.call<{ paused: boolean; files: string[] }>('poolInfo', { pool: 'db' });
	expect(resumed.paused).toBe(false);
	expect(resumed.files).toContain('/history.db');
	await a.call('open', { h: 'db', opts: { place: 'db', file: 'history.db', role: 'authority', journal: 'truncate' } });
	expect(await a.call('versions', { h: 'db', k: 20 })).toEqual({ byVersion: { 0: 20 }, missing: 0 });
});
