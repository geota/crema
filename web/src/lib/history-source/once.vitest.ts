/**
 * #310 R1w: `HistoryDbClient.once`, the export walk's and a hold's release's
 * call, on the real store. Sent once and never resent, so a late answer can't
 * run a walk step twice; refused when the owner it went to changes; refused
 * after 30 s without an answer.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Env, openTab } from '#lib/history-db/test-kit.js';
import { GATED_MS } from '#lib/history-db/client.js';
import { RPC_CHANNEL } from '#lib/history-db/names.js';
import type { Expect, Request, Target } from '#lib/history-db/protocol.js';
import { freshFile, historyWasmBuilt, memoryDisk } from './sqlite-kit';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

async function drive<T>(p: Promise<T>, ms = 60_000): Promise<T> {
	let out: { v?: T; e?: unknown; done: boolean } = { done: false };
	p.then(
		(v) => (out = { v, done: true }),
		(e) => (out = { e, done: true })
	);
	for (let t = 0; t < ms && !out.done; t += 50) await vi.advanceTimersByTimeAsync(50);
	if (!out.done) throw new Error('never settled');
	if (out.e !== undefined) throw out.e;
	return out.v as T;
}

async function setup(rows = 6) {
	const env = new Env();
	const disk = memoryDisk();
	(env as unknown as { disk: unknown }).disk = disk;
	const file = freshFile();
	await disk.plant(file);
	const conf: { stage: 2; expect: Expect; target: Target } = {
		stage: 2,
		expect: { role: 'authority', file, generation: 0 },
		target: { pool: 'db', generation: 0, file, role: 'authority', create: false, journal: 'truncate' }
	};
	const a = openTab(env, conf);
	await drive(a.client.read('writeSeq'));
	const b = openTab(env, conf);
	await drive(b.client.read('writeSeq'));
	await drive(
		a.client.write({
			kind: 'upsert',
			rows: Array.from({ length: rows }, (_, i) => ({ rowJson: JSON.stringify({ id: `s${i}`, completedAt: i, record: { duration: 1 } }) }))
		})
	);
	return { env, a, b };
}

/** Requests B sent on the channel, by op and name. */
const sent = (env: Env, from: string, name: string) =>
	env.bus.log.filter((l) => l.name === RPC_CHANNEL && l.from === from && (l.data as Request).t === 'req' && (l.data as { name?: string }).name === name).length;

describe.runIf(historyWasmBuilt)('HistoryDbClient.once (#310 R1w)', () => {
	it('a late answer isn’t resent: each walk step runs once, and the walk yields every row', async () => {
		const { env, b } = await setup();
		const walk = await drive(b.client.once<{ token: string; count: number }>('exportBegin'));
		expect(walk.count).toBe(6);
		// Every answer to B comes 7 s late: past the 5 s that resends a read.
		env.bus.route = (m, _from, to) => ((m as { t?: string }).t === 'reply' && to === b.ctx ? 7_000 : undefined);
		const ids: string[] = [];
		for (;;) {
			const rows = await drive(b.client.once<{ id: string }[]>('exportNext', { token: walk.token, n: 2 }));
			if (rows.length === 0) break;
			ids.push(...rows.map((r) => r.id));
		}
		expect([...ids].sort()).toEqual(['s0', 's1', 's2', 's3', 's4', 's5']);
		expect(sent(env, b.ctx, 'exportNext')).toBe(4);
		env.bus.route = () => undefined;
		await drive(b.client.once('exportEnd', walk.token));
	});

	it('the owner it went to goes before it answers: refused, not sent on', async () => {
		const { env, a, b } = await setup();
		const walk = await drive(b.client.once<{ token: string }>('exportBegin'));
		env.bus.route = (m, _from, to) => ((m as { t?: string }).t === 'reply' && to === b.ctx ? 10_000 : undefined);
		const step = b.client.once('exportNext', { token: walk.token, n: 2 });
		a.close();
		env.bus.route = () => undefined;
		await expect(drive(step)).rejects.toMatchObject({ code: 'ownerChanged' });
		expect(sent(env, b.ctx, 'exportNext')).toBe(1);
		// The next owner never had the walk.
		await drive(b.client.read('writeSeq'));
		await expect(drive(b.client.once('exportNext', { token: walk.token, n: 2 }))).rejects.toMatchObject({ code: 'unknownWalk' });
	});

	it('no answer for 30 s: refused', async () => {
		const { env, a, b } = await setup();
		const walk = await drive(b.client.once<{ token: string }>('exportBegin'));
		a.freeze();
		const step = b.client.once('exportNext', { token: walk.token, n: 2 });
		await expect(drive(step, GATED_MS + 5_000)).rejects.toMatchObject({ code: 'unavailable' });
		expect(sent(env, b.ctx, 'exportNext')).toBe(1);
		a.thaw();
	});

	it('is stage 2’s, and needs an owner that answers', async () => {
		const { b } = await setup(0);
		await drive(b.client.stop());
		await expect(b.client.once('exportBegin')).rejects.toMatchObject({ code: 'stopped' });
	});
});
