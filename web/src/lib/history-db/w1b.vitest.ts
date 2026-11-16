/**
 * #310 W1b: REVIEW-456's fixes, with its probes as regressions.
 * - F1: a write the owner didn't consume is never dropped; it's retried, and
 *   given up after 30 s as a no-op that consumes its seq. A seq the outbox no
 *   longer holds (a pruned tab) is sent as a no-op. No later write wedges (N6).
 * - F2: a leftover journal entry never brings back a shot the user deleted,
 *   nor ends an export's hold on one.
 * - F3: a `retry` during release never opens the file after the lock is gone.
 * - N1: a Worker that keeps failing is restarted with a doubling wait.
 * - N3: a paused pool is `unavailable`, not `lost`.
 * - N4: a stage-1 tab that hears the flip reloads.
 * - N5: an unreadable journal entry gets a diagnostics line, once.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { Env, openTab, STAGE1, STAGE2, row, type FakeFile } from './test-kit';
import type { Engine, EngineDb } from './engine';
import { IDLE_RELEASE_MS, GATED_MS } from './client';
import { OWNER_LOCK, PENDING_KEY_PREFIX, RPC_CHANNEL } from './names';
import { appendPending, pendingKey } from './pending';
import { PROTOCOL_VERSION } from './protocol';

// Fake timers drive minutes of protocol time; under a full parallel run that takes real seconds.
vi.setConfig({ testTimeout: 60_000 });

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = (ms = 200) => vi.advanceTimersByTimeAsync(ms);
const rate = (id: string, r: number) => ({ kind: 'upsert' as const, rows: [{ rowJson: row(id, r) }] });
const ratingOf = (f: FakeFile, id: string) => JSON.parse(f.rows.get(id)!).metadata.rating;

/** The owner's engine throws `code` on `apply` while `when` says so. */
function failingApply(env: Env, when: (seq: number, tab: string) => boolean, code = 'full') {
	const orig = env.disk.engine.bind(env.disk);
	env.disk.engine = (ctx: string): Engine => {
		const e = orig(ctx);
		return {
			...e,
			open: async (o) => {
				const db = await e.open(o);
				const wrapped: EngineDb = {
					close: () => db.close(),
					call: async (m, a) => {
						const x = a as { seq: number; tab: string };
						if (m === 'apply' && when(x.seq, x.tab)) throw { code, message: `${code} (injected)` };
						return db.call(m, a);
					}
				};
				return wrapped;
			}
		};
	};
}

describe('F1: a seq the owner didn’t consume', () => {
	test('P1: one failed apply is retried, and the tab’s later writes land', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		let thrown = false;
		failingApply(env, (seq) => {
			if (seq === 2 && !thrown) {
				thrown = true;
				return true;
			}
			return false;
		});
		const a = openTab(env, STAGE2);
		await settle();
		expect((await Promise.all([a.client.write(rate('r', 1)), settle(500)]))[0].outcome).toBe('done');
		const w2 = a.client.write(rate('r', 2));
		const w3 = a.client.write(rate('r', 3));
		await settle(15_000);
		expect((await w2).outcome).toBe('done');
		expect((await w3).outcome).toBe('done');
		expect(ratingOf(f, 'r')).toBe(3);
		expect(f.applied.get(a.ctx)).toBe(3);
	});

	test('a write that keeps failing is given up after 30 s as a no-op; the next write lands', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		let failing = true;
		failingApply(env, (seq) => failing && seq === 1);
		const a = openTab(env, STAGE2);
		await settle();
		const w1 = a.client.write(rate('r', 1));
		w1.catch(() => {});
		const w2 = a.client.write(rate('r', 2));
		await settle(GATED_MS + 2_000);
		await expect(w1).rejects.toMatchObject({ code: 'full' });
		failing = false;
		await settle(10_000);
		expect((await w2).outcome).toBe('done');
		expect(ratingOf(f, 'r')).toBe(2);
		// Seq 1 went as an upsert of nothing.
		expect(f.log.map((l) => [l.seq, (l.write as { rows: unknown[] }).rows.length])).toEqual([
			[1, 0],
			[2, 1]
		]);
	});

	test('a write sent while loading to an owner that is unavailable lands once it recovers', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const pool = env.disk.pool('db', 0);
		pool.holder = 'ghost';
		const a = openTab(env, STAGE2);
		// Loading: the write is taken.
		const w = a.client.write(rate('r', 7));
		const heard = w.then(
			() => 'landed',
			(e: { code: string }) => e.code
		);
		await settle(35_000);
		expect(a.client.status.health).toBe('unavailable');
		// It was given up at 30 s; the handles come back, the owner retries on resume.
		pool.holder = null;
		a.events.hide();
		a.events.show();
		await settle(10_000);
		// Given up with the owner's last answer (its handles were held).
		expect(['unavailable', 'pool.opfs']).toContain(await heard);
		const w2 = a.client.write(rate('r', 8));
		await settle(2_000);
		expect((await w2).outcome).toBe('done');
		expect(ratingOf(f, 'r')).toBe(8);
	});

	test('a pruned tab entry: the missing seqs go as no-ops and the write lands (C2a note 7)', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		for (let r = 1; r <= 3; r++) await Promise.all([a.client.write(rate('r', r)), settle(500)]);
		f.applied.delete(a.ctx);
		const w = a.client.write(rate('r', 4));
		await settle(3_000);
		expect((await w).outcome).toBe('done');
		expect(ratingOf(f, 'r')).toBe(4);
		expect(f.applied.get(a.ctx)).toBe(4);
	});

	test('N6: after a given-up write, the tab hidden and idle still releases', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		let failing = true;
		failingApply(env, () => failing);
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		void a.client.write(rate('r', 1)).catch(() => {});
		await settle(GATED_MS + 2_000);
		failing = false;
		await settle(6_000);
		a.events.hide();
		await settle(IDLE_RELEASE_MS + 2_000);
		expect(a.client.status.ownerHere).toBe(false);
		expect(b.client.status.ownerHere).toBe(true);
	});
});

describe('F2: a leftover journal entry and the user’s delete', () => {
	/** A owns; its page never hears the reply to its capture (it dies first). */
	async function orphanedCapture(env: Env, f: FakeFile) {
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		const w = env.workerOf(a.ctx)!;
		const h = w.onmessage!;
		w.onmessage = (e) => {
			if ((e.data as { t: string }).t === 'reply') return;
			h(e);
		};
		void a.client.capture(row('shot1', 0)).catch(() => {});
		await settle(100);
		expect(f.rows.has('shot1')).toBe(true);
		expect(env.storage.getItem(PENDING_KEY_PREFIX + 'shot1')).not.toBeNull();
		return { a, b };
	}

	test('P2: a deleted shot stays deleted when its leftover entry is drained', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const { a, b } = await orphanedCapture(env, f);
		const del = b.client.write({ kind: 'delete', ids: ['shot1'] });
		await settle(100);
		await del;
		a.close();
		await settle(10_000);
		expect(b.client.status.ownerHere).toBe(true);
		expect(f.rows.has('shot1')).toBe(false);
		expect(env.storage.keys()).toEqual([]);
	});

	test('P3: a shot deleted during an export stays held, hidden, and its row unchanged', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const { a, b } = await orphanedCapture(env, f);
		const edit = b.client.write(rate('shot1', 4));
		await settle(100);
		await edit;
		f.walking = true;
		const del = b.client.write({ kind: 'delete', ids: ['shot1'] });
		await settle(100);
		await del;
		expect(f.held.has('shot1')).toBe(true);
		a.close();
		await settle(10_000);
		expect(f.held.has('shot1')).toBe(true);
		expect(ratingOf(f, 'shot1')).toBe(4);
		expect(env.storage.keys()).toEqual([]);
	});

	test('a drained capture is applied under its own seq: the tab’s late resend can’t undo a later edit', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		// B's capture write is held back; the owner drains its entry first.
		let hold = true;
		env.bus.route = (m, from, _to, name) =>
			hold && name === RPC_CHANNEL && from === b.ctx && (m as { op?: string }).op === 'write' ? 'drop' : undefined;
		const c = b.client.capture(row('shot2', 0));
		await settle(500);
		expect(f.rows.has('shot2')).toBe(true);
		await Promise.all([a.client.write(rate('shot2', 5)), settle(500)]);
		hold = false;
		await settle(8_000);
		expect(await c).toBe('written');
		expect(ratingOf(f, 'shot2')).toBe(5);
		expect(f.log.filter((l) => l.tab === b.ctx)).toHaveLength(1);
	});

	test('a capture given up after 30 s keeps its entry, as a plain one, and the drain inserts it', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		let failing = true;
		failingApply(env, (_s, tab) => failing && !tab.startsWith('journal:'));
		const a = openTab(env, STAGE2);
		await settle();
		const c = a.client.capture(row('shot3', 0));
		await settle(GATED_MS + 2_000);
		expect(await c).toBe('journaled');
		failing = false;
		// The next drain (a hello) takes it, by id: the given-up seq is a no-op.
		openTab(env, STAGE2);
		await settle(3_000);
		expect(f.rows.has('shot3')).toBe(true);
		expect(env.storage.keys()).toEqual([]);
	});

	test('an entry whose tab is alive and whose write hasn’t landed waits; once the tab is gone it goes in by id', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		// B's seq 1 never arrives; its seq 2 is a capture, journaled.
		env.bus.route = (m, from) => (from === b.ctx && (m as { op?: string }).op === 'write' ? 'drop' : undefined);
		void b.client.write(rate('x', 1)).catch(() => {});
		void b.client.capture(row('shot4', 0)).catch(() => {});
		await settle(2_000);
		expect(f.rows.has('shot4')).toBe(false);
		expect(env.storage.keys()).toEqual([pendingKey('shot4')]);
		b.close();
		env.bus.route = () => undefined;
		openTab(env, STAGE2);
		await settle(3_000);
		expect(f.rows.has('shot4')).toBe(true);
		expect(env.storage.keys()).toEqual([]);
	});

	test('a plain entry drained twice (a crash before its removal) can’t bring a deleted shot back', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const storage = env.storage;
		appendPending(storage, row('shot5', 0));
		const kept = storage.getItem(pendingKey('shot5'))!;
		const a = openTab(env, STAGE2);
		await settle(1_000);
		expect(f.rows.has('shot5')).toBe(true);
		await Promise.all([a.client.write({ kind: 'delete', ids: ['shot5'] }), settle(500)]);
		// The entry is back, as if the first drain's removal never happened.
		storage.setItem(pendingKey('shot5'), kept);
		openTab(env, STAGE2);
		await settle(2_000);
		expect(f.rows.has('shot5')).toBe(false);
		expect(storage.keys()).toEqual([]);
	});
});

describe('F3: a retry during release', () => {
	test('P4: never opens the file after the lock is gone, and the tab queues for the lock again', async () => {
		const env = new Env();
		const pool = env.disk.pool('index', 0);
		pool.holder = 'ghost';
		const a = openTab(env, STAGE1);
		await settle(31_000);
		expect(a.client.status.health).toBe('index_fallback');
		a.events.show();
		await settle(150);
		a.events.fire('pagehide');
		await settle(10);
		a.events.fire('pageshow', { persisted: true });
		await settle(10);
		pool.holder = null;
		// At every moment, whatever holds the pool's handles or the file holds the lock.
		const breaches: string[] = [];
		const watch = setInterval(() => {
			const lock = env.locks.holder(OWNER_LOCK);
			const f = env.disk.file('index', 0, 'history-index.db');
			if (f?.openBy && f.openBy !== lock) breaches.push(`file open by ${f.openBy}, lock ${lock}`);
			if (pool.holder && pool.holder !== lock) breaches.push(`handles of ${pool.holder}, lock ${lock}`);
		}, 1);
		await settle(10_000);
		clearInterval(watch);
		expect(breaches).toEqual([]);
		const w = env.workerOf(a.ctx)!;
		const lock = env.locks.holder(OWNER_LOCK);
		// And pageshow's acquire wasn't lost: the Worker owns again.
		expect(lock).toBe(w.ctx);
		expect(a.client.status).toMatchObject({ health: 'ok', ownerHere: true });
	});
});

describe('notes', () => {
	test('N1: a Worker that keeps crashing is restarted after 1, 2, 4, 8 … s', async () => {
		const env = new Env();
		const make = env.makeWorker.bind(env);
		const started: number[] = [];
		env.makeWorker = (page: string) => {
			const w = make(page);
			started.push(Date.now());
			setTimeout(() => w.crash(), 0);
			return w;
		};
		openTab(env, STAGE1);
		await settle(40_000);
		const gaps = started.slice(1).map((t, i) => Math.round((t - started[i]) / 1000));
		expect(gaps.slice(0, 5)).toEqual([1, 2, 4, 8, 16]);
	});

	test('N3: a pool still paused after the install is unavailable, not lost', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const orig = env.disk.engine.bind(env.disk);
		env.disk.engine = (ctx) => {
			const e = orig(ctx);
			return {
				...e,
				installPool: async (p, g) => ({ ...(await e.installPool(p, g)), paused: true }),
				resumePool: async () => {},
				poolInfo: (p) => ({ ...e.poolInfo(p), paused: true, files: [] })
			};
		};
		const a = openTab(env, STAGE2);
		await settle();
		expect(a.client.status).toMatchObject({ health: 'unavailable', reason: 'owner:unavailable:paused' });
	});

	test('N4: a stage-1 tab that hears the flip reloads, once; a stage-2 tab doesn’t', async () => {
		const env = new Env();
		let reloads = 0;
		openTab(env, STAGE1, { onFlipped: () => reloads++ });
		const env2 = new Env();
		let reloads2 = 0;
		openTab(env2, STAGE2, { onFlipped: () => reloads2++ });
		await settle();
		for (const e of [env, env2]) {
			const ch = e.bus.channel(RPC_CHANNEL, 'flipper');
			ch.postMessage({ t: 'flipped', v: PROTOCOL_VERSION, build: 'new', generation: 0 });
			ch.postMessage({ t: 'flipped', v: PROTOCOL_VERSION, build: 'new', generation: 0 });
		}
		await settle();
		expect(reloads).toBe(1);
		expect(reloads2).toBe(0);
	});

	test('N5: an entry that isn’t JSON stays, with one diagnostics line', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		env.storage.setItem(pendingKey('junk'), '{not json');
		const a = openTab(env, STAGE2);
		await settle(1_000);
		env.storage.as('elsewhere', () => appendPending(env.storage, row('shot6', 0)));
		await settle(1_000);
		expect(env.storage.keys()).toEqual([pendingKey('junk')]);
		expect(a.client.diagnostics.filter((d) => d.includes(pendingKey('junk')))).toHaveLength(1);
	});
});

describe('N4: reloading for the flip', () => {
	test('waits for a moment the shell allows, then reloads once', async () => {
		const { reloadWhenIdle } = await import('./start');
		let allowed = false;
		let reloads = 0;
		reloadWhenIdle(() => allowed, () => reloads++);
		await settle(12_000);
		expect(reloads).toBe(0);
		allowed = true;
		await settle(6_000);
		expect(reloads).toBe(1);
		await settle(20_000);
		expect(reloads).toBe(1);
	});
});
