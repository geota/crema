/**
 * #310 W1, M11: a tab's writes land once each, in its order, whatever the
 * channel does to them (delays, losses, the resends that follow) and across
 * an owner change; so the last user action wins.
 */
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { Env, openTab, STAGE2, row, type FakeFile } from './test-kit';
import type { Applied, Reply, Request } from './protocol';
import { RPC_CHANNEL } from './names';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = (ms = 200) => vi.advanceTimersByTimeAsync(ms);
const rate = (id: string, r: number) => ({ kind: 'upsert' as const, rows: [{ rowJson: row(id, r) }] });
const ratingOf = (f: FakeFile, id: string) => JSON.parse(f.rows.get(id)!).metadata.rating;
const seqsOf = (f: FakeFile, tab: string) => f.log.filter((l) => l.tab === tab).map((l) => l.seq);

function setup() {
	const env = new Env();
	const f = env.disk.plant('db', 0, 'history.db', 'authority');
	return { env, f };
}

test('lost, delayed and resent writes apply once each, in order', async () => {
	const { env, f } = setup();
	const a = openTab(env, STAGE2);
	await settle();
	const b = openTab(env, STAGE2);
	await settle();
	// The first copy of each of B's writes is lost; later copies arrive late and out of order.
	const seen = new Set<string>();
	let k = 0;
	env.bus.route = (m, from, _to, name) => {
		const req = m as Request;
		if (name !== RPC_CHANNEL || from !== b.ctx || req.t !== 'req' || req.op !== 'write') return undefined;
		if (!seen.has(req.id)) {
			seen.add(req.id);
			return 'drop';
		}
		return 50 + ((k++ * 37) % 400);
	};
	const writes = [1, 2, 3, 4, 5].map((r) => b.client.write(rate('r', r)));
	await settle(30_000);
	const done = await Promise.all(writes);
	expect(done.every((d) => d.outcome === 'done' || d.outcome === 'alreadyApplied')).toBe(true);
	expect(seqsOf(f, b.ctx)).toEqual([1, 2, 3, 4, 5]);
	expect(ratingOf(f, 'r')).toBe(5);
	expect(a.client.status.ownerHere).toBe(true);
});

test('a write that arrives before the one ahead of it waits for it (gap), and both apply in order', async () => {
	const { env, f } = setup();
	openTab(env, STAGE2);
	await settle();
	const b = openTab(env, STAGE2);
	await settle();
	let first = true;
	env.bus.route = (m, from, _to, name) => {
		const req = m as Request;
		if (name === RPC_CHANNEL && from === b.ctx && req.t === 'req' && req.op === 'write' && req.wseq === 1 && first) {
			first = false;
			return 300;
		}
		return undefined;
	};
	const w1 = b.client.write(rate('r', 1));
	const w2 = b.client.write(rate('r', 2));
	await settle(10_000);
	await Promise.all([w1, w2]);
	expect(seqsOf(f, b.ctx)).toEqual([1, 2]);
	expect(ratingOf(f, 'r')).toBe(2);
	expect(f.log.filter((l) => l.tab === b.ctx)).toHaveLength(2);
});

test('across an owner change: what the old owner applied is skipped, the rest applies once', async () => {
	const { env, f } = setup();
	const a = openTab(env, STAGE2);
	await settle();
	const b = openTab(env, STAGE2);
	await settle();
	// A applies B's writes, but B never hears back.
	env.bus.route = (m, from, to, name) =>
		name === RPC_CHANNEL && from.startsWith(`${a.ctx}/`) && to === b.ctx && (m as Reply).t === 'reply' ? 'drop' : undefined;
	const w = [1, 2, 3].map((r) => b.client.write(rate('r', r)));
	await settle(1_000);
	expect(seqsOf(f, b.ctx)).toEqual([1, 2, 3]);
	// The owner tab closes; B's Worker takes the lock, and B resends its whole outbox.
	env.bus.route = () => undefined;
	a.close();
	w.push(b.client.write(rate('r', 4)));
	await settle(10_000);
	const out = (await Promise.all(w)) as Applied[];
	expect(b.client.status.ownerHere).toBe(true);
	expect(out.map((o) => o.outcome)).toEqual(['alreadyApplied', 'alreadyApplied', 'alreadyApplied', 'done']);
	expect(seqsOf(f, b.ctx)).toEqual([1, 2, 3, 4]);
	expect(ratingOf(f, 'r')).toBe(4);
});

test('two tabs editing one row: the last action wins, each write once', async () => {
	const { env, f } = setup();
	const a = openTab(env, STAGE2);
	await settle();
	const b = openTab(env, STAGE2);
	await settle();
	const c = openTab(env, STAGE2);
	await settle();
	// Slow, jittery delivery from every client.
	let k = 0;
	env.bus.route = (m, _from, _to, name) => (name === RPC_CHANNEL && (m as Request).t === 'req' ? (k++ * 53) % 250 : undefined);
	const order: [string, number][] = [];
	for (let r = 1; r <= 9; r++) {
		const t = [a, b, c][r % 3];
		order.push([t.ctx, r]);
		await Promise.all([t.client.write(rate('r', r)), settle(2_000)]);
	}
	expect(ratingOf(f, 'r')).toBe(9);
	for (const t of [a, b, c]) {
		expect(seqsOf(f, t.ctx)).toEqual([1, 2, 3]);
	}
	expect(f.log.map((l) => JSON.parse((l.write as { rows: { rowJson: string }[] }).rows[0].rowJson).metadata.rating)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
});

test('a resend across two owner changes and a crashed Worker still lands once', async () => {
	const { env, f } = setup();
	const a = openTab(env, STAGE2);
	await settle();
	const b = openTab(env, STAGE2);
	await settle();
	const c = openTab(env, STAGE2);
	await settle();
	// C's writes reach no one for a while.
	env.bus.route = (m, from, _to, name) => (name === RPC_CHANNEL && from === c.ctx && (m as Request).t === 'req' ? 'drop' : undefined);
	const w = [1, 2].map((r) => c.client.write(rate('s', r)));
	await settle(500);
	a.close();
	await settle(2_000);
	// B owns now; its Worker crashes; C's own Worker may win the lock next.
	env.workerOf(b.ctx)?.crash();
	env.bus.route = () => undefined;
	await settle(20_000);
	await Promise.all(w);
	expect(seqsOf(f, c.ctx)).toEqual([1, 2]);
	expect(ratingOf(f, 's')).toBe(2);
});
