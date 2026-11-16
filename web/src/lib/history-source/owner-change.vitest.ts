/**
 * #310 R1w, M3 and S6 across tabs: the SQLite source over the app's own
 * `HistoryDbClient` and owner, on the real store (W1's test kit, with
 * `memoryDisk` for its pools). A restore whose owner is killed between
 * batches deletes no row of the backup; an export whose owner goes fails and
 * keeps nothing, and the next owner's release finishes its holds.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Env, openTab, type Tab } from '#lib/history-db/test-kit.js';
import type { Expect, Target } from '#lib/history-db/protocol.js';
import { detailsOf, rowOf } from '#lib/history/history-db.js';
import type { StoredShot } from '#lib/history/model.js';
import { summarize } from '#lib/history/summary.js';
import type { ShotPlan } from '#lib/core/crema-core.js';
import { SqliteSource, type HistoryDbPort } from './sqlite';
import { clientPort, type HistoryClientLike } from './port';
import { freshFile, historyWasmBuilt, memoryDetails, memoryDisk, rowsOf } from './sqlite-kit';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

/** Runs `p` to its end, the fake clock moving meanwhile. */
async function drive<T>(p: Promise<T>, ms = 60_000): Promise<T> {
	let out: { v?: T; e?: unknown; done: boolean } = { done: false };
	p.then(
		(v) => (out = { v, done: true }),
		(e) => (out = { e, done: true })
	);
	for (let t = 0; t < ms && !out.done; t += 50) await vi.advanceTimersByTimeAsync(50);
	if (!out.done) throw new Error('never settled');
	if ('e' in out && out.e !== undefined) throw out.e;
	return out.v as T;
}

const shot = (id: string, rating: number, samples = 2): StoredShot =>
	({
		formatVersion: 3,
		id,
		completedAt: 1_790_000_000_000,
		profileName: 'R1w',
		profile: null,
		stopReason: null,
		metadata: { dose: 18, yieldOut: 36, beans: null, grinderSetting: null, notes: '', rating, tds: null, extractionYield: null },
		record: { duration: 30_000, samples: Array.from({ length: samples }, (_, i) => ({ elapsed: i * 100, sample: { groupPressure: 9 } })) }
	}) as unknown as StoredShot;

const plan = (p: Partial<ShotPlan>): ShotPlan => ({
	add: [],
	overwrite: [],
	keep: 0,
	remove: [],
	fill: [],
	zeroDebit: [],
	deletedHere: [],
	revive: [],
	notAdded: 0,
	notRevived: [],
	pinnedRemoved: 0,
	...p
});

async function setup() {
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
	const details = memoryDetails();
	const a = openTab(env, conf);
	await drive(a.client.read('writeSeq'));
	const b = openTab(env, conf);
	await drive(b.client.read('writeSeq'));
	const sourceOf = (t: Tab, wrap: (p: HistoryDbPort) => HistoryDbPort = (p) => p) =>
		new SqliteSource({ db: wrap(clientPort(t.client as unknown as HistoryClientLike)), details, summarize, batch: 2 });
	const seed = async (t: Tab, shots: StoredShot[]) => {
		await details.put(shots.map(detailsOf), 'keep');
		await drive(t.client.write({ kind: 'upsert', rows: shots.map((s) => ({ rowJson: JSON.stringify(rowOf(s, summarize(s))) })) }));
	};
	return { env, conf, details, a, b, sourceOf, seed };
}

const local = () => ['l1', 'l2', 'l3', 'l4', 's1', 's2'].map((id) => shot(id, 1));
const backup = () => [shot('s1', 5), shot('s2', 5), ...['b1', 'b2', 'b3', 'b4', 'b5'].map((id) => shot(id, 5))];
const input = () => ({
	plan: plan({ add: ['b1', 'b2', 'b3', 'b4', 'b5'], overwrite: ['s1', 's2'], remove: ['l1', 'l2', 'l3', 'l4'] }),
	backup: backup(),
	backupIds: new Set(backup().map((s) => s.id)),
	replace: true
});

describe.runIf(historyWasmBuilt)('the SQLite source across an owner change (#310 R1w)', () => {
	it('a restore whose owner is killed between batches deletes no row of the backup (M3)', async () => {
		// Kill the owner after each of the restore's writes in turn.
		for (let k = 1; k <= 6; k++) {
			const { a, b, details, sourceOf, seed } = await setup();
			await seed(a, local());
			let n = 0;
			const source = sourceOf(b, (p) => ({
				...p,
				write: async (w) => {
					const out = await p.write(w);
					if (++n === k) a.close();
					return out;
				}
			}));
			expect(await drive(source.restoreShots(input())), `k=${k}`).toEqual({ stored: true, curvesFailed: 0 });
			expect(b.client.status.ownerHere, `k=${k}`).toBe(true);
			const rows = await drive(rowsOf(b.client as unknown as HistoryClientLike));
			expect([...rows.keys()].sort(), `k=${k}`).toEqual(['b1', 'b2', 'b3', 'b4', 'b5', 's1', 's2']);
			for (const s of backup()) expect((rows.get(s.id) as StoredShot).metadata.rating, `k=${k} ${s.id}`).toBe(5);
			expect([...details.map.keys()].sort(), `k=${k}`).toEqual(['b1', 'b2', 'b3', 'b4', 'b5', 's1', 's2']);
		}
	});

	it('a shot another tab captures during the restore survives the wipe', async () => {
		const { a, b, sourceOf, seed } = await setup();
		await seed(a, local());
		let captured = false;
		const source = sourceOf(b, (p) => ({
			...p,
			write: async (w) => {
				if (w.kind === 'delete' && !captured) {
					captured = true;
					await seed(a, [shot('new1', 3)]);
				}
				return p.write(w);
			}
		}));
		expect((await drive(source.restoreShots(input()))).stored).toBe(true);
		const rows = await drive(rowsOf(b.client as unknown as HistoryClientLike));
		expect([...rows.keys()].sort()).toEqual(['b1', 'b2', 'b3', 'b4', 'b5', 'new1', 's1', 's2']);
	});

	it('an export whose owner goes fails, and the next owner’s release removes what it held, row and details', async () => {
		const { a, b, details, sourceOf, seed } = await setup();
		await seed(a, ['s1', 's2', 's3', 's4', 's5'].map((id) => shot(id, 1)));
		const source = sourceOf(b);
		const ex = await drive(source.exportShots());
		expect(ex.total).toBe(5);
		const it = ex.pass()[Symbol.asyncIterator]();
		expect((await drive(it.next())).value?.id).toBe('s5');
		// The user deletes s2 during the walk: held.
		await drive(a.client.write({ kind: 'delete', ids: ['s2'] }));
		expect(details.map.has('s2')).toBe(true);
		// The owner tab goes: the walk goes with its connection.
		a.close();
		await expect(drive((async () => {
			for (;;) if ((await it.next()).done) break;
		})())).rejects.toBeTruthy();
		await drive(ex.close());
		// B owns now, and its release finished the hold: details, then the row.
		expect(b.client.status.ownerHere).toBe(true);
		const rows = await drive(rowsOf(b.client as unknown as HistoryClientLike));
		expect([...rows.keys()].sort()).toEqual(['s1', 's3', 's4', 's5']);
		expect(details.map.has('s2')).toBe(false);
		expect(await drive(b.client.once('releaseHolds'))).toEqual([]);
	});
});
