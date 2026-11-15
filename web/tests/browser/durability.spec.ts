/**
 * W0 durability (#310 PLAN W0, SPEC §8 "Journal mode", "Durability"): kill the
 * tab inside a write transaction, reopen, and find the whole transaction or
 * none of it, `W0_KILLS` times (200 by default), for `journal_mode=TRUNCATE`
 * and for `locking_mode=EXCLUSIVE` with WAL, both with `synchronous=FULL`.
 *
 * The file is a real truth file, `history.db` in `crema-db`, made the one way
 * besides a copy that one begins (a recovery file) and reopened with
 * `create: false`. Each transaction is one `upsert` that rewrites the same
 * `K` rows at a new version: every row, its derived columns and its search
 * entry change, so the transaction is long and the file doesn't grow. After
 * every reopen:
 * - all `K` rows hold one version (atomic: no mix of two transactions);
 * - that version is at least the last one whose commit the Worker reported
 *   before the kill (durable against a tab kill; power loss is for the real
 *   tablet), and at most the last one it began;
 * - `count` is `K` (no stray rows) and the search finds the version's text.
 *
 * The kill:
 * - Chromium: CDP `Page.crash`, which kills the renderer, Worker and all, as an
 *   OOM kill or a crash does. The next tab's install waits out the dead tab's
 *   handles with SPEC §8's backoff.
 * - WebKit (no CDP): `Worker.terminate()` from the page in the middle of the
 *   transaction, then the page closed. The Worker's thread stops between two
 *   of SQLite's writes, as in a process kill; the OS keeps what was written
 *   either way.
 *
 * The test writes a few versions, each acknowledged only when its call
 * returns, then sends one more and kills the tab a random time into it, so
 * it knows exactly which transaction was in flight however slow the CDP link
 * is (Android). Each run records how many kills rolled that transaction back
 * (the reopened version is the one before it), how many reopens found a hot
 * journal, and the mean commit time, which is the "faster" W0 compares.
 */
import { dbDir, expect, isAndroid, sleep, test, type Tab } from './fixtures.ts';

const KILLS = Number(process.env.W0_KILLS ?? 200);
const K = 400;
const FILE = 'history.db';

type Journal = 'truncate' | 'exclusiveWal';

for (const journal of ['truncate', 'exclusiveWal'] as Journal[]) {
	test(`a kill inside a transaction leaves all of it or none: ${journal}, ${KILLS} kills`, async ({
		tab,
		gen,
		browserName
	}, testInfo) => {
		test.setTimeout(KILLS * 5_000 + 60_000);
		const g = gen();
		const dir = dbDir(g);
		const crash = browserName === 'chromium';
		// The in-flight transaction's notes, per row: longer over adb, whose
		// round trips are slower than a whole transaction on desktop.
		const HEAVY = isAndroid(testInfo.project.name) ? 16_000 : 4_000;
		let lastAcked = 0;
		let lastBegun = 0;
		let rolledBack = 0;
		let hot = 0;
		let installWaits = 0;
		let maxInstallMs = 0;
		const commitMs: number[] = [];
		const reopenMs: number[] = [];
		let heavyMs = 0;
		// Where in the in-flight transaction the kill aims, as a share of
		// `heavyMs`: it backs off after a kill that came too late, since a
		// loaded runner's round trips vary.
		let aim = 0.8;

		for (let i = 0; i <= KILLS; i++) {
			const t: Tab = await tab();
			// What the kill left, read straight from OPFS before anything opens it.
			if (i > 0) {
				const slots = await t.try<{ name: string; size: number }[]>('slots', { dir });
				const journalBytes = (slots.value ?? [])
					.filter((s) => s.name === `/${FILE}-journal`)
					.reduce((n, s) => n + s.size - 4096, 0);
				if (journalBytes > 0) hot++;
			}
			const inst = await t.call<{ ok: boolean; attempts: number; elapsedMs: number }>(
				'installRetry',
				{ pool: 'db', generation: g, budgetMs: 30_000 }
			);
			expect(inst.ok, JSON.stringify(inst)).toBe(true);
			if (inst.attempts > 1) installWaits++;
			maxInstallMs = Math.max(maxInstallMs, inst.elapsedMs);
			const t0 = Date.now();
			if (i === 0) {
				await t.call('recover', { h: 'db', opts: { place: 'db', file: FILE, generation: 1, journal } });
				// The in-flight transaction's size, timed once: the kill aims inside it.
				heavyMs = (await t.call<{ ms: number }>('writeVersion', { h: 'db', v: 0, k: K, pad: HEAVY })).ms;
				await t.call('writeVersion', { h: 'db', v: 0, k: K });
			} else {
				await t.call('open', { h: 'db', opts: { place: 'db', file: FILE, role: 'authority', journal } });
			}
			reopenMs.push(Date.now() - t0);

			const { byVersion, missing } = await t.call<{ byVersion: Record<string, number>; missing: number }>(
				'versions',
				{ h: 'db', k: K }
			);
			const held = Object.keys(byVersion);
			expect(missing, `rows missing after kill ${i}`).toBe(0);
			expect(held, `versions mixed after kill ${i}: ${JSON.stringify(byVersion)}`).toHaveLength(1);
			const v = Number(held[0]);
			expect(v, `kill ${i}: acknowledged version ${lastAcked} lost`).toBeGreaterThanOrEqual(lastAcked);
			expect(v, `kill ${i}: a version nobody wrote`).toBeLessThanOrEqual(lastBegun);
			if (i > 0 && v < lastBegun) {
				rolledBack++;
				aim = Math.min(aim * 1.05, 0.8);
			} else if (i > 0) {
				aim = Math.max(aim * 0.7, 0.05);
			}
			expect(await t.call<number>('call', { h: 'db', m: 'count', a: {} })).toBe(K);
			expect(await t.call<number>('call', { h: 'db', m: 'count', a: { text: `v${v} y` } })).toBe(K);
			if (i === KILLS) break;

			// A few acknowledged versions, then one more, killed in flight.
			const from = v + 1;
			const acked = 1 + Math.floor(Math.random() * 3);
			for (let w = from; w < from + acked; w++) {
				const { ms } = await t.call<{ ms: number }>('writeVersion', { h: 'db', v: w, k: K });
				commitMs.push(ms);
				lastAcked = w;
			}
			lastBegun = from + acked;
			// The one in flight carries ten times the text, so the kill, whose
			// CDP round trip is slow over adb, still lands inside it.
			void t.call('writeVersion', { h: 'db', v: lastBegun, k: K, pad: HEAVY }).catch(() => {});
			await sleep(Math.random() * heavyMs * aim);
			if (crash) await t.crash();
			else await t.page.evaluate(() => window.w0.terminate()).catch(() => {});
			await t.page.close().catch(() => {});
		}

		const mean = (xs: number[]) => xs.reduce((s, x) => s + x, 0) / Math.max(xs.length, 1);
		const summary = {
			browser: testInfo.project.name,
			journal,
			kills: KILLS,
			kill: crash ? 'Page.crash' : 'Worker.terminate',
			rowsPerTransaction: K,
			rolledBack,
			hotJournalAtReopen: journal === 'truncate' ? hot : 'n/a (WAL)',
			installRetried: installWaits,
			longestInstallMs: Math.round(maxInstallMs),
			transactionsCommitted: commitMs.length,
			meanCommitMs: Number(mean(commitMs).toFixed(2)),
			inFlightTransactionMs: Math.round(heavyMs),
			finalAim: Number(aim.toFixed(2)),
			meanReopenMs: Number(mean(reopenMs).toFixed(1))
		};
		console.log(`W0 durability ${JSON.stringify(summary)}`);
		await testInfo.attach('durability', { body: JSON.stringify(summary, null, 2), contentType: 'application/json' });
		// Most kills must cut a transaction off, or the test proves little.
		expect(rolledBack, JSON.stringify(summary)).toBeGreaterThan(KILLS * 0.5);
	});
}
