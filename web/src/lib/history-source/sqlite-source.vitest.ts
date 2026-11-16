/**
 * #310 R1w: the SQLite side of the backup seam over the real store (the
 * `memory-truth` test bundle on `memvfs`). The walk with its token and delete
 * hold, holds released details first (S6), and the restore's merge, then
 * remove, with the wipe from `idsCreatedBefore` (M3).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { unzipSync, strFromU8 } from 'fflate';
import { detailsOf, rowOf, type DetailsRecord } from '#lib/history/history-db.js';
import type { StoredShot } from '#lib/history/model.js';
import { summarize } from '#lib/history/summary.js';
import type { ShotPlan } from '#lib/core/crema-core.js';
import { memorySink, streamBackup } from '#lib/backup/stream.js';
import { DELETE_AFTER_MS, beginMarker, checkMarkerAtBoot, finishMarker } from '#lib/restore/marker.js';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import { RESTORE_LOCK } from '#lib/restore/tab-safety.js';
import { SqliteSource, type HistoryDbPort } from './sqlite';
import { apartFromRestores } from './guards';
import { ShotsMoved } from './types';
import {
	Crash,
	directPort,
	freshFile,
	historyWasmBuilt,
	makeTruth,
	memoryDetails,
	openTruth,
	rowsOf,
	type Fault
} from './sqlite-kit';

const sample = (elapsed: number) =>
	({
		elapsed,
		sample: {
			sampleTime: 0,
			groupPressure: 9,
			groupFlow: 2,
			headTemp: 92,
			mixTemp: 90,
			setMixTemp: 93,
			setHeadTemp: 93,
			setGroupPressure: 9,
			setGroupFlow: 0,
			frameNumber: 1,
			steamTemp: 0
		}
	}) as StoredShot['record']['samples'][number];

export function shot(id: string, extra: Partial<StoredShot> = {}, samples = 3): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_790_000_000_000 + Number(id.replace(/\D/g, '') || 0) * 1000,
		profileName: 'Test',
		profile: null,
		stopReason: null,
		metadata: { dose: 18, yieldOut: 36, beans: null, grinderSetting: null, notes: id, rating: 3, tds: null, extractionYield: null },
		record: { duration: 30_000, samples: Array.from({ length: samples }, (_, i) => sample(i * 100)) },
		...extra
	} as StoredShot;
}

/** Writes `shots` as the store keeps them: details, then rows in this order. */
async function seed(port: HistoryDbPort, details: ReturnType<typeof memoryDetails>, shots: readonly StoredShot[]) {
	await details.put(shots.map(detailsOf), 'keep');
	await port.write({ kind: 'upsert', rows: shots.map((s) => ({ rowJson: JSON.stringify(rowOf(s, summarize(s))) })) });
}

async function setup(fault?: Fault) {
	const file = freshFile();
	await makeTruth(file);
	const db = await openTruth(file);
	const log: string[] = [];
	const logged: Fault = (at, n) => {
		log.push(at);
		return fault?.(at, n) ?? false;
	};
	const port = directPort(db, { fault: logged });
	const details = memoryDetails({ fault: logged });
	const source = new SqliteSource({ db: port, details, summarize, batch: 2 });
	return { file, db, port, details, source, log };
}

async function all(it: AsyncIterable<StoredShot>): Promise<StoredShot[]> {
	const out: StoredShot[] = [];
	for await (const s of it) out.push(s);
	return out;
}

/** The backup's lines: its header and its shot lines. */
function linesOf(zip: Uint8Array): { header: Record<string, number>; shots: StoredShot[] } {
	const jsonl = strFromU8(unzipSync(zip)['backup.jsonl']).split('\n').filter(Boolean).map((l) => JSON.parse(l));
	return { header: jsonl[0], shots: jsonl.filter((l) => l.kind === 'shot') };
}

async function backupOf(source: SqliteSource, during?: (pass: number, at: number) => Promise<void>) {
	const ex = await source.exportShots();
	let passN = 0;
	const shots = {
		total: ex.total,
		async *pass() {
			const p = passN++;
			let at = 0;
			for await (const s of ex.pass()) {
				await during?.(p, at++);
				yield s;
			}
		}
	};
	try {
		const built = await streamBackup(
			{ envelope: '{}', shots, createdAt: 1, appVersion: 't', deviceLabel: 't' },
			{ sink: memorySink(), batch: 2, pause: async () => {} }
		);
		return { built, bytes: new Uint8Array(await built.file.arrayBuffer()) };
	} finally {
		await ex.close();
	}
}

/** Rows by `rid`, held ones marked, from the store itself. */
async function projection(port: HistoryDbPort) {
	const page = await port.read<{ rows: { id: string; held: boolean; deletedAt: number | null }[] }>('syncProjection');
	return page.rows;
}

/** The restore marker's boot, an hour after a restore that owes `ids`, over `source`. */
async function owedDeletes(source: SqliteSource, ids: string[]) {
	localStorage.clear();
	beginMarker('replace', 'b', { shots: ids, photos: [] }, 0, [], 'b');
	finishMarker(1);
	let listed: ReadonlySet<string> = new Set();
	const found = await checkMarkerAtBoot({
		now: 1 + DELETE_AFTER_MS,
		restoreRunning: async () => {
			listed = await source.listedIds();
			return false;
		},
		listedShots: () => listed,
		listedBeans: () => new Set(),
		shotsHealth: () => 'ok',
		beansHealth: () => 'ok',
		deleteSeries: (id) => source.deleteOwed(id),
		deleteCapture: async () => {},
		deletePhoto: async () => {}
	});
	expect(found.kind).toBe('deleted');
}

describe.runIf(historyWasmBuilt)('the SQLite source (#310 R1w)', () => {
	// A restore that stops logs why; these stop it on purpose.
	beforeEach(() => void vi.spyOn(console, 'error').mockImplementation(() => {}));
	it('exports every live shot whole, newest first, the same in both passes', async () => {
		const { port, details, source } = await setup();
		const shots = ['s1', 's2', 's3', 's4', 's5'].map((id) => shot(id));
		await seed(port, details, [...shots, shot('s6', { deletedAt: 5 } as Partial<StoredShot>)]);
		expect(await source.countLive()).toBe(5);
		expect(source.liveCount()).toBe(5);
		const ex = await source.exportShots();
		expect(ex.total).toBe(5);
		const one = await all(ex.pass());
		const two = await all(ex.pass());
		await ex.close();
		expect(one.map((s) => s.id)).toEqual(['s5', 's4', 's3', 's2', 's1']);
		expect(two).toEqual(one);
		// Whole: the details are back on each row.
		expect(one[2]).toEqual({ ...shots[2], profile: null });
		expect(one[2].record.samples).toHaveLength(3);
	});

	it('a delete during the export is held: hidden, still written, then removed details first', async () => {
		const { port, details, source, log } = await setup();
		await seed(port, details, ['s1', 's2', 's3', 's4', 's5'].map((id) => shot(id)));
		const { bytes } = await backupOf(source, async (pass, at) => {
			if (pass !== 0 || at !== 1) return;
			// The user deletes s2, which this walk hasn't reached.
			await port.write({ kind: 'delete', ids: ['s2'] });
			expect(await port.read('get', ['s2'])).toEqual([]);
			expect((await projection(port)).find((r) => r.id === 's2')?.held).toBe(true);
			expect(details.map.has('s2')).toBe(true);
		});
		const { header, shots } = linesOf(bytes);
		expect(shots.map((s) => s.id)).toEqual(['s5', 's4', 's3', 's2', 's1']);
		expect(header.shotCount).toBe(5);
		expect(shots.find((s) => s.id === 's2')!.record.samples).toHaveLength(3);
		// After the walk: gone, details first, then the row.
		expect((await projection(port)).map((r) => r.id)).toEqual(['s1', 's3', 's4', 's5']);
		expect(details.map.has('s2')).toBe(false);
		const release = log.lastIndexOf('details:delete');
		expect(release).toBeGreaterThan(-1);
		expect(log.indexOf('write:delete', release)).toBeGreaterThan(release);
	});

	it('the header count equals the lines, whatever is written during the walk', async () => {
		const { port, details, source } = await setup();
		await seed(port, details, ['s1', 's2', 's3', 's4', 's5', 's6'].map((id) => shot(id)));
		const { built, bytes } = await backupOf(source, async (pass, at) => {
			if (pass === 0 && at === 0) {
				await port.write({ kind: 'delete', ids: ['s1', 's6'] });
				await seed(port, details, [shot('s7')]);
				await port.write({ kind: 'upsert', rows: [{ rowJson: JSON.stringify(rowOf(shot('s3', { metadata: { ...shot('s3').metadata, rating: 5 } }), summarize(shot('s3')))) }] });
			}
			if (pass === 1 && at === 2) await port.write({ kind: 'delete', ids: ['s2'] });
		});
		const { header, shots } = linesOf(bytes);
		expect(header.shotCount).toBe(shots.length);
		expect(built.shotsWritten).toBe(shots.length);
		// The set fixed at the start; the edit is in its line; the new shot waits for the next backup.
		expect(shots.map((s) => s.id)).toEqual(['s6', 's5', 's4', 's3', 's2', 's1']);
		expect(shots.find((s) => s.id === 's3')!.metadata.rating).toBe(5);
		expect((await projection(port)).map((r) => r.id)).toEqual(['s3', 's4', 's5', 's7']);
	});

	it('a walk step lost or run twice fails the backup rather than skipping shots', async () => {
		const { db, details, port: p0 } = await setup();
		await seed(p0, details, ['s1', 's2', 's3', 's4', 's5'].map((id) => shot(id)));
		// A port whose third `exportNext` runs twice on the owner: its first answer is the one kept.
		const port = directPort(db);
		let n = 0;
		const twice: HistoryDbPort = {
			...port,
			once: async (name, arg) => {
				const v = await port.once(name, arg);
				if (name === 'exportNext' && ++n === 2) await port.once(name, arg);
				return v as never;
			}
		};
		const source = new SqliteSource({ db: twice, details, summarize, batch: 2 });
		await expect(backupOf(source)).rejects.toBeInstanceOf(ShotsMoved);
	});

	it('a crash between a hold’s details and its row delete is finished at the next open, with no details left behind', async () => {
		for (const crashAt of ['details:delete', 'write:delete'] as const) {
			let armed = false;
			const { file, db, port, details, source } = await setup((at) => armed && at === crashAt);
			await seed(port, details, ['s1', 's2', 's3'].map((id) => shot(id)));
			const ex = await source.exportShots();
			await all(ex.pass());
			await port.write({ kind: 'delete', ids: ['s2'] });
			await all(ex.pass());
			armed = true;
			await ex.close(); // the release crashes
			armed = false;
			const after = await projection(port);
			// Never details without a row: either both stay, or the row stays held and hidden.
			expect(after.find((r) => r.id === 's2')?.held).toBe(true);
			expect(await port.read('get', ['s2'])).toEqual([]);
			if (crashAt === 'details:delete') expect(details.map.has('s2')).toBe(true);
			else expect(details.map.has('s2')).toBe(false);
			// The next open (another owner): its first release finishes it.
			db.close();
			db.free();
			const again = await openTruth(file);
			const next = new SqliteSource({ db: directPort(again), details, summarize, batch: 2 });
			expect(await next.settleHolds()).toBe(1);
			expect((await projection(directPort(again))).map((r) => r.id)).toEqual(['s1', 's3']);
			expect(details.map.has('s2')).toBe(false);
			again.close();
			again.free();
		}
	});

	it('a release deletes only rows still held: one an upsert brought back since stays', async () => {
		const { db, port, details } = await setup();
		await seed(port, details, ['s1', 's2', 's3'].map((id) => shot(id)));
		const other = directPort(db);
		let armed = false;
		const racing: HistoryDbPort = {
			...port,
			once: async (name, arg) => {
				const v = await port.once(name, arg);
				// An upsert of s2 (a restore's, were it not kept apart) lands between the release's reads.
				if (name === 'releaseHolds' && armed) {
					armed = false;
					await seed(other, details, [shot('s2', { metadata: { ...shot('s2').metadata, rating: 5 } })]);
				}
				return v as never;
			}
		};
		const source = new SqliteSource({ db: racing, details, summarize, batch: 2 });
		const ex = await source.exportShots();
		await port.write({ kind: 'delete', ids: ['s2'] });
		await all(ex.pass());
		await all(ex.pass());
		armed = true;
		await ex.close();
		expect((await rowsOf(port)).get('s2')).toMatchObject({ metadata: { rating: 5 } });
		expect((await projection(port)).find((r) => r.id === 's2')?.held).toBe(false);
	});

	it('a release waits out a restore: under `crema-restore`, or not at all', async () => {
		const { port, details } = await setup();
		await seed(port, details, ['s1', 's2'].map((id) => shot(id)));
		const locks = new FakeLocks();
		const source = new SqliteSource({ db: port, details, summarize, apartFromRestores: apartFromRestores(locks) });
		const ex = await source.exportShots();
		await port.write({ kind: 'delete', ids: ['s2'] });
		const unlock = await locks.hold(RESTORE_LOCK);
		await ex.close();
		// A restore runs: the hold stays for later.
		expect((await projection(port)).find((r) => r.id === 's2')?.held).toBe(true);
		expect(details.map.has('s2')).toBe(true);
		unlock();
		await new Promise((r) => setTimeout(r, 0));
		expect(await source.settleHolds()).toBe(1);
		expect((await projection(port)).map((r) => r.id)).toEqual(['s1']);
		expect(details.map.has('s2')).toBe(false);
	});

	describe('restore: merge, then remove (M3)', () => {
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
		/** Here: l1–l4 and shared s1, s2; the backup: s1, s2 (newer), b1–b3. Replace removes l1–l4. */
		const local = () => ['l1', 'l2', 'l3', 'l4', 's1', 's2'].map((id) => shot(id));
		const backup = () => [shot('s1', { metadata: { ...shot('s1').metadata, rating: 5 } }), shot('s2', { metadata: { ...shot('s2').metadata, rating: 5 } }), shot('b1'), shot('b2'), shot('b3')];
		const replacePlan = () => plan({ add: ['b1', 'b2', 'b3'], overwrite: ['s1', 's2'], remove: ['l1', 'l2', 'l3', 'l4'] });
		const input = () => ({ plan: replacePlan(), backup: backup(), backupIds: new Set(backup().map((s) => s.id)), replace: true });

		it('replaces: backup rows win, the plan’s removes go, details with them', async () => {
			const { port, details, source } = await setup();
			await seed(port, details, local());
			expect(await source.restoreShots(input())).toEqual({ stored: true, curvesFailed: 0 });
			const rows = await rowsOf(port);
			expect([...rows.keys()].sort()).toEqual(['b1', 'b2', 'b3', 's1', 's2']);
			expect((rows.get('s1') as StoredShot).metadata.rating).toBe(5);
			expect([...details.map.keys()].sort()).toEqual(['b1', 'b2', 'b3', 's1', 's2']);
		});

		it('a crash at every batch leaves the union, and running it again finishes it', async () => {
			const steps = ['restoreBegin', 'get', 'details:put', 'write:upsert', 'idsCreatedBefore', 'write:delete', 'details:delete'];
			for (const at of steps) {
				for (let k = 1; k <= 3; k++) {
					const ctl = { armed: false, seen: 0 };
					const { port, details, source, log } = await setup((a) => ctl.armed && a === at && ++ctl.seen === k);
					await seed(port, details, local());
					const from = log.length;
					ctl.armed = true;
					const first = await source.restoreShots(input());
					ctl.armed = false;
					const ran = log.slice(from);
					const rows = await rowsOf(port);
					const what = `${at}#${k}`;
					// No row the plan doesn't remove is ever gone, and a row is never without its details.
					for (const id of ['s1', 's2']) expect(rows.has(id), `${what} ${id}`).toBe(true);
					for (const id of rows.keys()) expect(details.map.has(id), `${what} details of ${id}`).toBe(true);
					// Stopped before any of the wipe landed: the union.
					const wiped = ran.filter((a) => a === 'write:delete').length - (at === 'write:delete' && !first.stored ? 1 : 0);
					if (wiped === 0) for (const id of ['l1', 'l2', 'l3', 'l4']) expect(rows.has(id), `${what} ${id}`).toBe(true);
					expect(await source.restoreShots(input())).toEqual({ stored: true, curvesFailed: 0 });
					expect([...(await rowsOf(port)).keys()].sort(), what).toEqual(['b1', 'b2', 'b3', 's1', 's2']);
					expect((await rowsOf(port)).get('s1')).toMatchObject({ metadata: { rating: 5 } });
					// Details a crash left after a row's delete are the marker's to remove,
					// an hour on, once no row lists them (restore/marker.ts).
					const left = [...details.map.keys()].filter((id) => !['b1', 'b2', 'b3', 's1', 's2'].includes(id));
					for (const id of left) expect(['l1', 'l2', 'l3', 'l4'], what).toContain(id);
					await owedDeletes(source, ['l1', 'l2', 'l3', 'l4']);
					expect([...details.map.keys()].sort(), what).toEqual(['b1', 'b2', 'b3', 's1', 's2']);
				}
			}
		});

		it('a shot captured during the restore survives the wipe', async () => {
			const { db, port, details } = await setup();
			await seed(port, details, local());
			const other = directPort(db);
			let captured = false;
			const during: HistoryDbPort = {
				...port,
				write: async (w) => {
					// Another tab's capture lands between the merge and the wipe.
					if (w.kind === 'delete' && !captured) {
						captured = true;
						await seed(other, details, [shot('new1')]);
					}
					return port.write(w);
				}
			};
			const source = new SqliteSource({ db: during, details, summarize, batch: 2 });
			expect((await source.restoreShots(input())).stored).toBe(true);
			expect(captured).toBe(true);
			expect([...(await rowsOf(port)).keys()].sort()).toEqual(['b1', 'b2', 'b3', 'new1', 's1', 's2']);
			expect(details.map.has('new1')).toBe(true);
		});

		it('never removes a backup row, even one the plan names, nor a row it doesn’t name', async () => {
			const { port, details, source } = await setup();
			await seed(port, details, local());
			const p = replacePlan();
			p.remove = ['l1', 'l2', 's1'];
			expect((await source.restoreShots({ ...input(), plan: p })).stored).toBe(true);
			expect([...(await rowsOf(port)).keys()].sort()).toEqual(['b1', 'b2', 'b3', 'l3', 'l4', 's1', 's2']);
		});

		it('merges: fills a row with no curve from the backup, keeps the row’s own fields', async () => {
			const { port, details, source } = await setup();
			const flat = shot('s1', { metadata: { ...shot('s1').metadata, notes: 'mine' } }, 0);
			await seed(port, details, [flat]);
			const b = shot('s1', { metadata: { ...shot('s1').metadata, notes: 'theirs' } });
			const r = await source.restoreShots({ plan: plan({ fill: ['s1'], keep: 1 }), backup: [b], backupIds: new Set(['s1']), replace: false });
			expect(r.stored).toBe(true);
			const row = (await rowsOf(port)).get('s1') as StoredShot & { summary: { sampleCount: number } };
			expect(row.metadata.notes).toBe('mine');
			expect(row.summary.sampleCount).toBe(3);
			expect((details.map.get('s1') as DetailsRecord).samples).toHaveLength(3);
		});

		it('a restore whose writes fail says so, and leaves what was there', async () => {
			const { port, details } = await setup();
			await seed(port, details, local());
			const failing = new SqliteSource({ db: { ...port, write: async () => Promise.reject(new Crash('full')) }, details, summarize });
			expect(await failing.restoreShots(input())).toEqual({ stored: false, curvesFailed: 0 });
			expect([...(await rowsOf(port)).keys()].sort()).toEqual(['l1', 'l2', 'l3', 'l4', 's1', 's2']);
		});
	});

	it('the planner’s rows: tombstones and held rows deleted, pins and unknown counts as stored', async () => {
		const { port, details, source } = await setup();
		await seed(port, details, [shot('a', { pinned: true }), shot('b', {}, 0), shot('c', { deletedAt: 9 } as Partial<StoredShot>), shot('d')]);
		const ex = await source.exportShots();
		await port.write({ kind: 'delete', ids: ['d'] });
		const local = await source.localShots();
		await ex.close();
		expect(local).toEqual([
			{ id: 'a', hasSamples: true, pinned: true, deleted: false },
			{ id: 'b', hasSamples: false, pinned: false, deleted: false },
			{ id: 'c', hasSamples: true, pinned: false, deleted: true },
			{ id: 'd', hasSamples: true, pinned: false, deleted: true }
		]);
	});
});
