/**
 * #310 SPEC §9, S3: sync's projection is collected whole before any planner
 * runs. A write between pages starts the walk again; after three moving tries
 * the owner's one-transaction read answers; nothing partial is ever returned.
 * Over the real store in Node (`memory-truth` test bundle).
 */
import { describe, expect, it } from 'vitest';
import { rowOf } from '#lib/history/history-db.js';
import { summarize } from '#lib/history/summary.js';
import type { StoredShot } from '#lib/history/model.js';
import { directPort, freshFile, historyWasmBuilt, makeTruth, openTruth } from '#lib/history-source/sqlite-kit.js';
import type { HistoryDbPort } from '#lib/history-source/sqlite.js';
import { collectSyncProjection, SYNC_TRIES, type SyncRead, type SyncRowOut } from './sync-projection';

const shot = (n: number, extra: Partial<StoredShot> = {}): StoredShot =>
	({
		formatVersion: 3,
		id: `s${n}`,
		completedAt: 1_790_000_000_000 + n * 1000,
		profileName: 'Test',
		profile: null,
		stopReason: null,
		metadata: { dose: 18, yieldOut: 36, notes: `s${n}`, rating: 3 },
		record: { duration: 30_000, samples: [] },
		...extra
	}) as unknown as StoredShot;

const rowJson = (s: StoredShot) => JSON.stringify(rowOf(s, summarize(s)));

async function setup(n: number): Promise<HistoryDbPort> {
	const file = freshFile();
	await makeTruth(file);
	const port = directPort(await openTruth(file));
	await port.write({ kind: 'upsert', rows: Array.from({ length: n }, (_, i) => ({ rowJson: rowJson(shot(i)) })) });
	return port;
}

/** The store's own one-transaction projection, to compare a walk against. */
async function whole(port: HistoryDbPort): Promise<{ rows: SyncRowOut[]; seq: number }> {
	const p = await port.read<{ rows: SyncRowOut[]; seq: number }>('syncProjection');
	return { rows: p.rows, seq: Number(p.seq) };
}

const readOf = (port: HistoryDbPort): SyncRead => (name, arg) => port.read(name, arg);

describe.skipIf(!historyWasmBuilt)('the sync projection, collected whole (S3)', () => {
	it('pages through every row by rid, at one seq', async () => {
		const port = await setup(25);
		const pages: number[] = [];
		const read: SyncRead = async (name, arg) => {
			if (name === 'syncRows') pages.push((arg as { afterRid: number }).afterRid);
			return port.read(name, arg);
		};
		const got = await collectSyncProjection(read, { page: 10 });
		expect(got.via).toBe('pages');
		expect(got.restarts).toBe(0);
		expect(pages.length).toBe(3);
		expect(got).toMatchObject(await whole(port));
		expect(new Set(got.rows.map((r) => r.id)).size).toBe(25);
	});

	it('a write between pages starts the walk again, and the answer holds that write', async () => {
		const port = await setup(25);
		let wrote = false;
		const read: SyncRead = async (name, arg) => {
			const out = await port.read(name, arg);
			// After the first page, another tab records a shot.
			if (name === 'syncRows' && !wrote) {
				wrote = true;
				await port.write({ kind: 'upsert', rows: [{ rowJson: rowJson(shot(99)) }] });
			}
			return out as never;
		};
		const got = await collectSyncProjection(read, { page: 10 });
		expect(got.restarts).toBe(1);
		expect(got.via).toBe('pages');
		expect(got.rows.map((r) => r.id)).toContain('s99');
		expect(got).toMatchObject(await whole(port));
	});

	it('a delete between pages: the row is gone from the answer, not half there', async () => {
		const port = await setup(25);
		let wrote = false;
		const read: SyncRead = async (name, arg) => {
			const out = await port.read(name, arg);
			if (name === 'syncRows' && !wrote) {
				wrote = true;
				await port.write({ kind: 'delete', ids: ['s3'] });
			}
			return out as never;
		};
		const got = await collectSyncProjection(read, { page: 10 });
		expect(got.restarts).toBe(1);
		expect(got.rows.map((r) => r.id)).not.toContain('s3');
		expect(got).toMatchObject(await whole(port));
	});

	it('writes during every walk: after three tries the one-transaction read answers', async () => {
		const port = await setup(25);
		let n = 1_000;
		const calls: string[] = [];
		const read: SyncRead = async (name, arg) => {
			calls.push(name);
			const out = await port.read(name, arg);
			if (name === 'syncRows') await port.write({ kind: 'upsert', rows: [{ rowJson: rowJson(shot(n++)) }] });
			return out as never;
		};
		const got = await collectSyncProjection(read, { page: 10 });
		expect(got.via).toBe('oneTransaction');
		expect(got.restarts).toBe(SYNC_TRIES);
		expect(calls.at(-1)).toBe('syncProjection');
		expect(got).toMatchObject(await whole(port));
	});

	it('the planner never sees a partial set: each answer is the whole set at its seq', async () => {
		const port = await setup(40);
		// A planner that records what it was given.
		const seen: { rows: SyncRowOut[]; seq: number }[] = [];
		const plan = (p: { rows: SyncRowOut[]; seq: number }) => seen.push(p);
		let k = 0;
		const read: SyncRead = async (name, arg) => {
			const out = await port.read(name, arg);
			// A write after every other page, deletes and inserts in turn.
			if (name === 'syncRows' && k++ % 2 === 1) {
				await port.write(
					k % 4 === 0
						? { kind: 'delete', ids: [`s${k}`] }
						: { kind: 'upsert', rows: [{ rowJson: rowJson(shot(500 + k)) }] }
				);
			}
			return out as never;
		};
		for (let i = 0; i < 4; i++) {
			const got = await collectSyncProjection(read, { page: 7 });
			plan(got);
			// Read straight after: what the store held at the answer's seq, if nothing moved since.
			const now = await whole(port);
			if (now.seq === got.seq) expect(got.rows).toEqual(now.rows);
		}
		for (const p of seen) {
			expect(new Set(p.rows.map((r) => r.id)).size).toBe(p.rows.length);
			const rids = p.rows.map((r) => r.rid);
			expect([...rids].sort((a, b) => a - b)).toEqual(rids);
		}
	});

	it('a read that fails mid-walk fails the walk: no partial answer', async () => {
		const port = await setup(25);
		let pages = 0;
		const read: SyncRead = async (name, arg) => {
			if (name === 'syncRows' && ++pages === 2) throw { code: 'noAnswer', message: 'owner gone' };
			return port.read(name, arg);
		};
		await expect(collectSyncProjection(read, { page: 10 })).rejects.toMatchObject({ code: 'noAnswer' });
	});

	it('a row deleted during an export walk comes back held, and a tombstone with its tombstone', async () => {
		const port = await setup(5);
		await port.write({ kind: 'upsert', rows: [{ rowJson: rowJson(shot(7, { deletedAt: 5 } as Partial<StoredShot>)) }] });
		const walk = await port.once<{ token: string }>('exportBegin');
		await port.write({ kind: 'delete', ids: ['s2'] });
		const got = await collectSyncProjection(readOf(port), { page: 2 });
		expect(got.rows.find((r) => r.id === 's2')).toMatchObject({ held: true });
		expect(got.rows.find((r) => r.id === 's7')).toMatchObject({ held: false, deletedAt: 5 });
		await port.once('exportEnd', walk.token);
	});
});
