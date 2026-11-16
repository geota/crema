/**
 * #310 W1, S1 and S2, owner question 4, and SPEC §8's failed starts: the
 * owner's life (handover, a crashed or silent Worker, bfcache, hidden and
 * idle), what a tab shows when the owner can't answer, and the health a
 * failed start ends in. Nothing here removes a file.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { Env, openTab, STAGE1, STAGE2, row } from './test-kit';
import { OWNER_LOCK, STAGE_LOCK, TAB_LOCK_PREFIX } from './names';
import { GATED_MS, IDLE_RELEASE_MS, WORKER_DEAD_MS } from './client';
import { INSTALL_BUDGET_MS } from './owner';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = (ms = 200) => vi.advanceTimersByTimeAsync(ms);
const rate = (id: string, r: number) => ({ kind: 'upsert' as const, rows: [{ rowJson: row(id, r) }] });
const owner = (env: Env) => env.locks.holder(OWNER_LOCK);

describe('locks', () => {
	test('each tab holds the stage lock shared and its tab lock; the owner lock is a Worker’s', async () => {
		const env = new Env();
		const a = openTab(env, STAGE1);
		const b = openTab(env, STAGE1);
		await settle();
		const held = env.locks.held.map((h) => `${h.name}@${h.ctx}`);
		expect(held).toEqual(
			expect.arrayContaining([`${STAGE_LOCK}@${a.ctx}`, `${STAGE_LOCK}@${b.ctx}`, `${TAB_LOCK_PREFIX}${a.ctx}@${a.ctx}`, `${TAB_LOCK_PREFIX}${b.ctx}@${b.ctx}`])
		);
		expect(owner(env)).toMatch(new RegExp(`^${a.ctx}/w`));
	});
});

describe('handover', () => {
	test('closing the owner tab moves ownership; a read in flight is answered by the next owner', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		f.rows.set('r', row('r', 1));
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		// A's answers to B are lost; then A goes.
		env.bus.route = (_m, from) => (from.startsWith(`${a.ctx}/`) ? 'drop' : undefined);
		const read = b.client.read('count', {});
		await settle(100);
		a.close();
		env.bus.route = () => undefined;
		await settle(8_000);
		expect(await read).toBe(1);
		expect(b.client.status).toMatchObject({ health: 'ok', ownerHere: true });
		expect(owner(env)).toMatch(new RegExp(`^${b.ctx}/w`));
	});

	test('pagehide closes, pauses and lets go: the other tab takes over at once, with no install retries', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		a.events.fire('pagehide', { persisted: true });
		await settle(500);
		expect(env.disk.pool('db', 0).holder).toMatch(new RegExp(`^${b.ctx}/w`));
		expect(b.client.status.ownerHere).toBe(true);
		// bfcache restore: A queues for the lock again and is a client meanwhile.
		a.events.fire('pageshow', { persisted: true });
		await settle(500);
		expect(a.client.status).toMatchObject({ health: 'ok', ownerHere: false });
		expect(env.locks.queue.some((q) => q.name === OWNER_LOCK && q.ctx.startsWith(`${a.ctx}/`))).toBe(true);
		b.close();
		await settle(5_000);
		expect(a.client.status.ownerHere).toBe(true);
	});

	test('a hard kill leaves the handles held for a while: the next install retries with backoff and wins', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		const pool = env.disk.pool('db', 0);
		const aWorker = pool.holder!;
		// The lock goes with the Worker at once; its handles linger for 2 s.
		env.locks.kill(aWorker);
		for (const w of env.workers) if (w.ctx === aWorker) w.dead = true;
		setTimeout(() => env.disk.kill(aWorker), 2_000);
		await settle(10_000);
		expect(pool.holder).toMatch(new RegExp(`^${b.ctx}/w`));
		expect(b.client.status).toMatchObject({ health: 'ok', ownerHere: true });
	});
});

describe('a Worker that fails (S1)', () => {
	test('a crashed Worker is replaced; the new one queues for the lock and owns', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const first = env.workerOf(a.ctx)!;
		first.crash();
		await settle(1_000);
		const second = env.workerOf(a.ctx)!;
		expect(second.ctx).not.toBe(first.ctx);
		expect(owner(env)).toBe(second.ctx);
		expect(a.client.status).toMatchObject({ health: 'ok', ownerHere: true });
	});

	test('a silent Worker is terminated after 10 s, and its lock and handles go with it', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		const hung = env.workerOf(a.ctx)!;
		hung.hang();
		const w = b.client.write(rate('r', 1));
		await settle(WORKER_DEAD_MS + 8_000);
		expect(hung.dead).toBe(true);
		expect(await w).toMatchObject({ outcome: 'done' });
		expect(env.disk.file('db', 0, 'history.db')!.rows.has('r')).toBe(true);
	});

	test('a frozen tab, thawed, keeps its Worker: the freeze is not a dead Worker', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const w = env.workerOf(a.ctx)!;
		a.freeze();
		await settle(60_000);
		a.thaw();
		await settle(3_000);
		expect(w.dead).toBe(false);
		expect(owner(env)).toBe(w.ctx);
	});
});

describe('the owner hidden and idle (owner question 4)', () => {
	test('hidden for 30 s with nothing in flight gives the database up; visible again queues for it', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		a.events.hide();
		await settle(IDLE_RELEASE_MS - 1_000);
		expect(a.client.status.ownerHere).toBe(true);
		await settle(2_000);
		expect(a.client.status.ownerHere).toBe(false);
		expect(b.client.status.ownerHere).toBe(true);
		a.events.show();
		await settle(500);
		expect(a.client.status).toMatchObject({ health: 'ok', ownerHere: false });
		b.close();
		await settle(5_000);
		expect(a.client.status.ownerHere).toBe(true);
	});

	test('hidden but busy waits until it is idle', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		// A's own write never gets its answer back, so it stays in flight.
		env.bus.route = () => undefined;
		const hold = env.workerOf(a.ctx)!;
		const realPost = hold.postMessage;
		hold.postMessage = (m) => ((m as { t?: string }).t === 'req' ? undefined : realPost(m));
		void a.client.write(rate('r', 1)).catch(() => {});
		a.events.hide();
		await settle(IDLE_RELEASE_MS + 5_000);
		expect(a.client.status.ownerHere).toBe(true);
		expect(b.client.status.ownerHere).toBe(false);
	});
});

describe('an owner that can’t answer (S2)', () => {
	test('stage 1: another tab falls back to its in-memory list; nothing goes read-only', async () => {
		const env = new Env();
		const a = openTab(env, STAGE1);
		await settle();
		const b = openTab(env, STAGE1);
		await settle();
		expect(b.client.status.health).toBe('ok');
		a.freeze();
		void b.client.read('count', {}).catch(() => {});
		await settle(6_000);
		expect(b.client.status).toMatchObject({ health: 'index_fallback', reason: 'noAnswer', message: null });
	});

	test('stage 2: read-only after 5 s; reads reject after 30 s, never as if empty', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		a.freeze();
		const read = b.client.read('count', {});
		read.catch(() => {});
		await settle(4_000);
		expect(b.client.status.health).toBe('ok');
		await settle(2_000);
		expect(b.client.status).toMatchObject({ health: 'unavailable', reason: 'noAnswer', message: 'History is open in another window' });
		await settle(GATED_MS);
		await expect(read).rejects.toMatchObject({ code: 'unavailable' });
	});
});

describe('failed starts (SPEC §8)', () => {
	test('stage 2, the file absent from the pool: lost, and no file is created', async () => {
		const env = new Env();
		const a = openTab(env, STAGE2);
		await settle();
		expect(a.client.status).toMatchObject({ health: 'lost', reason: 'owner:lost:absent' });
		expect(env.disk.pool('db', 0).files.size).toBe(0);
	});

	test('stage 2, corrupt: lost, and the file stays', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		f.corrupt = true;
		const a = openTab(env, STAGE2);
		await settle();
		expect(a.client.status.health).toBe('lost');
		expect(env.disk.file('db', 0, 'history.db')).toBe(f);
	});

	test('stage 2, an invalid slot: unavailable at once; stage 1 falls back', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		env.disk.pool('db', 0).invalid = true;
		env.disk.pool('index', 0).invalid = true;
		const a = openTab(env, STAGE2);
		await settle();
		expect(a.client.status).toMatchObject({ health: 'unavailable', reason: 'owner:unavailable:pool.invalidHeader' });
		const env1 = new Env();
		env1.disk.pool('index', 0).invalid = true;
		const s1 = openTab(env1, STAGE1);
		await settle();
		expect(s1.client.status.health).toBe('index_fallback');
	});

	test('handles held past the install budget: unavailable; resumed, the owner tries again', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		env.disk.pool('db', 0).holder = 'someone-else';
		const a = openTab(env, STAGE2);
		await settle(INSTALL_BUDGET_MS + 6_000);
		expect(a.client.status).toMatchObject({ health: 'unavailable' });
		env.disk.pool('db', 0).holder = null;
		a.events.hide();
		a.events.show();
		await settle(1_000);
		expect(a.client.status.health).toBe('ok');
	});

	test('a repair generation without its done record, or without the file, is never installed', async () => {
		const g = { ...STAGE2, expect: { ...STAGE2.expect, generation: 2 }, target: { ...STAGE2.target, generation: 2 } };
		for (const copied of [null, ['/history.db-journal'], ['/history.db']]) {
			const env = new Env();
			env.disk.plant('db', 2, 'history.db', 'authority');
			if (copied) env.disk.repairDone.set('db:2', copied);
			const a = openTab(env, g);
			await settle();
			expect(a.client.status.health, JSON.stringify(copied)).toBe(copied?.includes('/history.db') ? 'ok' : 'unavailable');
			expect(env.disk.pool('db', 2).holder === null, JSON.stringify(copied)).toBe(!copied?.includes('/history.db'));
		}
	});

	test('stage 1 creates its index; stage 2 never creates history.db', async () => {
		const env = new Env();
		openTab(env, STAGE1);
		await settle();
		expect(env.disk.file('index', 0, 'history-index.db')?.role).toBe('index');
		expect(env.disk.pools.get('db:0')).toBeUndefined();
	});
});

describe('factory reset (S10)', () => {
	test('stop closes, pauses and lets go, before it returns, and never takes the lock again', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const stopping = a.client.stop();
		await settle(100);
		await stopping;
		expect(env.disk.pool('db', 0).holder).toBeNull();
		expect(env.disk.file('db', 0, 'history.db')!.openBy).toBeNull();
		expect(owner(env)).toBeUndefined();
		expect(env.locks.held.filter((h) => h.ctx.startsWith(a.ctx))).toEqual([]);
		a.events.show();
		a.events.fire('pageshow', { persisted: true });
		await settle(5_000);
		expect(owner(env)).toBeUndefined();
		await expect(a.client.read('count', {})).rejects.toMatchObject({ code: 'stopped' });
	});
});
