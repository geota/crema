/**
 * #420 S1, the owner's decision: a store lost under the app gives back curves
 * too, from the curve archive, one gzipped file per shot in the private file
 * system, written once when the curve first lands in the store.
 *
 * - Every recovery fills curves from the archive for the rows the recovered
 *   store lacks one for: all shots, not the 16 memory keeps.
 * - A deleted shot's curve doesn't come back.
 * - A write that fails for quota never holds up a save, and goes again.
 * - A corrupt file is skipped and logged, never used.
 * - Shots from before the archive go in later, in the background.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { gzipSync, strToU8 } from 'fflate';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION, type MigrationRecord } from './history-db';
import type { StoredShot } from './model';
import { writeDoneMarker } from './store-marker';
import { summarize } from './summary';
import { archiveNameOf, CurveArchive } from './curve-archive';
import { dayOf } from './rows-snapshot';
import { boot, freshStorage, stored, writeOldCopy, LEGACY_KEY } from './idb-test-kit';
import { memoryFs } from './archive-test-kit';
import type { ChannelLike } from './store.svelte';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
let idb: IDBFactory;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	idb = freshStorage();
});

const small = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));

async function seedStore(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, {
		state: 'done',
		sourceHash: '0:0',
		sourceIds: shots.map((s) => s.id),
		doneAt: 1
	} satisfies MigrationRecord);
	db.close();
	writeDoneMarker(1, shots.length);
	localStorage.removeItem(LEGACY_KEY);
}

function deleteHistoryDb(): Promise<void> {
	return new Promise<void>((resolve) => {
		const req = idb.deleteDatabase('crema-history');
		req.onsuccess = () => resolve();
		req.onblocked = () => resolve();
	});
}

const until = async (ok: () => boolean, ms = 2_000) => {
	const end = Date.now() + ms;
	while (!ok()) {
		if (Date.now() > end) throw new Error('timed out');
		await new Promise((r) => setTimeout(r, 2));
	}
};

/** Lose the store under `store`, and see it go `lost`. */
async function lose(store: Awaited<ReturnType<typeof boot>>, touch: string): Promise<void> {
	await deleteHistoryDb();
	store.setRating(touch, 4);
	await store.flushed();
	expect(store.health).toBe('lost');
}

describe('a recovery gives back every curve, not 16', () => {
	it('Start empty: 20 shots made after the move all keep their curves', async () => {
		const fs = memoryFs();
		await writeOldCopy(small(5));
		const a = await boot(fs.opts);
		const made: string[] = [];
		for (let i = 0; i < 20; i++) {
			made.push(a.recordCaptured({ ...realShot(100 + i, realSamples(4, i)), visualizerId: null } as unknown as Record<string, unknown>)!.id);
			await a.flushed();
		}
		await a.archiveSettled();
		expect(fs.curves().size).toBe(20);
		await lose(a, made[0]);
		expect(await a.startEmpty()).toBe(true);
		const { rows, details } = await stored();
		for (const id of made) {
			expect(details.get(id)?.samples, id).toHaveLength(4);
			expect(rows.get(id)!.summary.sampleCount, id).toBe(4);
			expect(a.get(id)!.summary.sampleCount, id).toBe(4);
		}
	});

	it('from the daily copy, in the same tab or a new one, every row takes its curve back', async () => {
		for (const sameTab of [true, false]) {
			idb = freshStorage();
			const fs = memoryFs();
			const shots = small(30);
			await seedStore(shots);
			const a = await boot(fs.opts);
			await a.archiveCatchUpNow();
			expect(fs.curves().size).toBe(30);
			a.setRating(shots[0].id, 3);
			await a.flushed();
			await until(() => fs.copies() === 1);
			await lose(a, shots[1].id);
			const who = sameTab ? a : await boot(fs.opts);
			expect(await who.recoverSnapshot()).toBe(true);
			const { rows, details } = await stored();
			for (const s of shots) {
				expect(details.get(s.id)?.samples, `${sameTab} ${s.id}`).toEqual(s.record.samples);
				expect(rows.get(s.id)!.summary.sampleCount).toBe(4);
			}
		}
	});

	it('the old copy: shots made after the move take their curves from the archive', async () => {
		const fs = memoryFs();
		const old = small(20);
		await writeOldCopy(old);
		const a = await boot(fs.opts);
		const made = a.recordCaptured({ ...realShot(70, realSamples(9)), visualizerId: null } as unknown as Record<string, unknown>)!;
		await a.flushed();
		await a.archiveSettled();
		// The made shot was written and went light: memory keeps it only among
		// the 16 kept, so push it out to see the archive bring it back.
		for (const s of old) await a.loadShot(s.id);
		await lose(a, old[0].id);
		const b = await boot(fs.opts);
		expect(await b.recoverOldCopy()).toBe(true);
		// A, still lost, finds B's recovery when it hears of it or comes back.
		expect(await a.startEmpty()).toBe('reopened');
		await a.flushed();
		const { details, rows } = await stored();
		expect(details.get(made.id)?.samples).toHaveLength(9);
		expect(rows.get(made.id)!.summary.sampleCount).toBe(9);
	});
});

describe('a deleted shot’s curve doesn’t come back', () => {
	it('the file goes once the store’s delete lands, and a daily copy that still has the row brings no curve', async () => {
		const fs = memoryFs();
		const shots = small(4).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		a.setRating(shots[0].id, 2);
		await a.flushed();
		// The daily copy is written with every row, the doomed one too.
		await until(() => fs.copies() === 1);
		const doomed = shots[2].id;
		expect(fs.curves().has(archiveNameOf(doomed))).toBe(true);
		a.delete(doomed);
		await a.flushed();
		await until(() => !fs.curves().has(archiveNameOf(doomed)));
		await lose(a, shots[1].id);
		expect(await a.recoverSnapshot()).toBe(true);
		const { rows, details } = await stored();
		expect(details.get(doomed)?.samples ?? []).toEqual([]);
		if (rows.has(doomed)) expect(rows.get(doomed)!.summary.sampleCount).toBe(0);
		expect(details.get(shots[3].id)!.samples).toHaveLength(4);
	});
});

describe('failures', () => {
	it('quota: the save lands without the archive, and the archive write goes again with a later save', async () => {
		const fs = memoryFs();
		await seedStore(small(2));
		const a = await boot(fs.opts);
		fs.control.quota = true;
		const made = a.recordCaptured({ ...realShot(50, realSamples(6)), visualizerId: null } as unknown as Record<string, unknown>)!;
		await a.flushed();
		await a.archiveSettled();
		// The save is whole and nothing waits on the archive.
		expect((await stored()).details.get(made.id)!.samples).toHaveLength(6);
		expect(a.hasUnsaved).toBe(false);
		expect(fs.curves().get(archiveNameOf(made.id))?.length ?? 0).toBe(0);
		fs.control.quota = false;
		a.setRating(made.id, 5);
		await a.flushed();
		await a.archiveSettled();
		expect(fs.curves().get(archiveNameOf(made.id))!.length).toBeGreaterThan(0);
		expect((await new CurveArchive(fs.fsRoot).read(made.id)).kind).toBe('curve');
	});

	it('a corrupt file is skipped and logged, never used; the others still fill', async () => {
		const fs = memoryFs();
		const shots = small(3);
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		// One file whose gzip reads, but whose curve was changed: only its hash says so.
		const bad = shots[1];
		const forged = { v: 1, id: bad.id, sampleCount: 4, brewSampleCount: 0, sum: 'x', samples: realSamples(4, 50), brewSeries: null };
		const archive = new CurveArchive(fs.fsRoot);
		const right = await archive.read(bad.id);
		expect(right.kind).toBe('curve');
		const sum = (JSON.parse(new TextDecoder().decode((await import('fflate')).gunzipSync(fs.curves().get(archiveNameOf(bad.id))!))) as { sum: string }).sum;
		fs.curves().set(archiveNameOf(bad.id), gzipSync(strToU8(JSON.stringify({ ...forged, sum }))));
		// And one that isn't gzip at all.
		const junk = shots[2];
		fs.curves().set(archiveNameOf(junk.id), new Uint8Array([1, 2, 3]));
		await lose(a, shots[0].id);
		expect(await a.startEmpty()).toBe(true);
		const { details } = await stored();
		expect(details.get(shots[0].id)!.samples).toEqual(shots[0].record.samples);
		expect(details.get(bad.id)?.samples ?? []).toEqual([]);
		expect(details.get(junk.id)?.samples ?? []).toEqual([]);
		expect([...a.archiveSkipped.keys()].sort()).toEqual([bad.id, junk.id].sort());
		// Said, with a count: the recovery's result, and Settings (#420 FC-N5).
		expect(a.lastRecovery).toEqual({ restored: 1, notRestored: 2 });
		await a.countArchiveNow();
		expect(a.archiveStatus!.notRestored).toBe(2);
		// A curve that comes back another way no longer counts.
		a.backfillTelemetry(bad.id, realSamples(4, 3), 30_000);
		await a.flushed();
		expect(a.archiveStatus!.notRestored).toBe(1);
	});
});

describe('the archive catches up on shots from before it', () => {
	it('in the background, after the load: every stored curve goes in once, a manual log has none', async () => {
		const fs = memoryFs();
		const { profile: _p, ...plain } = realShot(90, []);
		const manual = { ...plain, id: 'shot:manual-90', brewMethod: 'aeropress', profile: null } as StoredShot;
		const shots = [...small(25), manual];
		await seedStore(shots);
		const a = await boot(fs.opts);
		// The load doesn't wait for it.
		expect(fs.curves().size).toBe(0);
		await a.archiveCatchUpNow();
		expect(fs.curves().size).toBe(25);
		expect(fs.curves().has(archiveNameOf(manual.id))).toBe(false);
		const archive = new CurveArchive(fs.fsRoot);
		for (const s of shots.slice(0, 25)) {
			const got = await archive.read(s.id);
			expect(got.kind === 'curve' && got.curve.samples).toEqual(s.record.samples);
		}
		// Written once: a second pass rewrites nothing.
		const before = new Map(fs.curves());
		await a.archiveCatchUpNow();
		for (const [name, bytes] of fs.curves()) expect(bytes).toBe(before.get(name));
	});

	it('a file is about 6 KB for a real-shaped 180-sample shot', async () => {
		const fs = memoryFs();
		await new CurveArchive(fs.fsRoot).write('shot:size', { samples: realSamples(180), brewSeries: null });
		const size = fs.curves().get(archiveNameOf('shot:size'))!.length;
		console.log(`[curve-archive] a 180-sample shot's file: ${size} bytes`);
		expect(size).toBeLessThan(10_000);
	});
});

describe('the archive always matches the store’s curve (#420 fix check)', () => {
	it('FC-S1, probe D: an empty file a closed tab left is written by the catch-up, and the curve comes back', async () => {
		const fs = memoryFs();
		const shots = small(3);
		await seedStore(shots);
		const root = await fs.fsRoot();
		await (await root.getDirectoryHandle('crema-history-curves', { create: true })).getFileHandle(archiveNameOf(shots[0].id), { create: true });
		// Today's whole check already ran: only a shot with no file, or an empty one, is owed.
		localStorage.setItem('crema.history.archiveCheckedOn', dayOf(Date.now()));
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		expect(fs.curves().get(archiveNameOf(shots[0].id))!.length).toBeGreaterThan(0);
		a.setRating(shots[1].id, 3);
		await a.flushed();
		await until(() => fs.copies() === 1);
		await lose(a, shots[2].id);
		const b = await boot(fs.opts);
		expect(await b.recoverSnapshot()).toBe(true);
		const { details } = await stored();
		expect(shots.map((s) => details.get(s.id)?.samples.length ?? 0)).toEqual([4, 4, 4]);
	});

	it('FC-S1, probe E: a corrupt file is rewritten from the store’s curve by the next day’s check', async () => {
		const fs = memoryFs();
		const shots = small(2);
		await seedStore(shots);
		const day = 86_400_000;
		const a = await boot({ ...fs.opts, now: () => 1_800_000_000_000 });
		await a.archiveCatchUpNow();
		fs.curves().set(archiveNameOf(shots[0].id), new Uint8Array([1, 2, 3]));
		const b = await boot({ ...fs.opts, now: () => 1_800_000_000_000 + day });
		await b.archiveCatchUpNow();
		const got = await new CurveArchive(fs.fsRoot).read(shots[0].id);
		expect(got.kind === 'curve' && got.curve.samples).toEqual(shots[0].record.samples);
	});

	it('FC-S1: a save of a shot whose file is corrupt rewrites it', async () => {
		const fs = memoryFs();
		await seedStore(small(1));
		const a = await boot(fs.opts);
		const made = a.recordCaptured({ ...realShot(60, realSamples(5)), visualizerId: null } as unknown as Record<string, unknown>)!;
		fs.curves().set(archiveNameOf(made.id), new Uint8Array([9, 9, 9]));
		await a.flushed();
		await a.archiveSettled();
		expect((await new CurveArchive(fs.fsRoot).read(made.id)).kind).toBe('curve');
	});

	it('FC-S2, probe F: a Replace restore rewrites the file, and a loss brings back the new curve', async () => {
		const fs = memoryFs();
		const shots = small(3).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		const x = shots[1];
		const replaced = { ...x, record: { ...x.record, samples: realSamples(9, 40) } } as StoredShot;
		const r = await a.restoreRows((rows) => ({ next: rows.map((row) => (row.id === x.id ? replaced : row)), series: [replaced] }));
		expect(r.stored).toBe(true);
		await a.flushed();
		await a.archiveSettled();
		const archived = await new CurveArchive(fs.fsRoot).read(x.id);
		expect(archived.kind === 'curve' && archived.curve.samples.length).toBe(9);
		a.setRating(shots[0].id, 3);
		await a.flushed();
		await until(() => fs.copies() === 1);
		await lose(a, shots[2].id);
		const b = await boot(fs.opts);
		expect(await b.recoverSnapshot()).toBe(true);
		expect((await stored()).details.get(x.id)!.samples).toEqual(replaced.record.samples);
	});

	it('a file that already matches is not rewritten', async () => {
		const fs = memoryFs();
		await seedStore(small(2));
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		const before = new Map(fs.curves());
		const b = await boot({ ...fs.opts, now: () => Date.now() + 86_400_000 });
		await b.archiveCatchUpNow();
		for (const [name, bytes] of fs.curves()) expect(bytes, name).toBe(before.get(name));
	});
});

describe('the archive’s nits (#420 fix check)', () => {
	it('FC-N1: a deleted shot’s file that wouldn’t go is removed at the next catch-up', async () => {
		const fs = memoryFs();
		const shots = small(2).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		fs.control.failRemove = true;
		a.delete(shots[0].id);
		await a.flushed();
		await new Promise((r) => setTimeout(r, 20));
		expect(fs.curves().has(archiveNameOf(shots[0].id))).toBe(true);
		fs.control.failRemove = false;
		await a.archiveCatchUpNow();
		expect(fs.curves().has(archiveNameOf(shots[0].id))).toBe(false);
		expect(fs.curves().has(archiveNameOf(shots[1].id))).toBe(true);
	});

	it('FC-N4: after a factory reset took the marker, a pending write brings no archive back', async () => {
		const fs = memoryFs();
		await seedStore(small(1));
		const a = await boot(fs.opts);
		fs.control.quota = true;
		const made = a.recordCaptured({ ...realShot(61, realSamples(5)), visualizerId: null } as unknown as Record<string, unknown>)!;
		await a.flushed();
		await a.archiveSettled();
		// The reset: the directory and every `crema.` key go.
		fs.control.quota = false;
		await (await fs.fsRoot()).removeEntry('crema-history-curves', { recursive: true });
		localStorage.clear();
		a.setRating(made.id, 2);
		await a.flushed();
		await a.archiveSettled();
		expect(fs.curves().has(archiveNameOf(made.id))).toBe(false);
	});

	it('the storage view counts the archive: its files and bytes against the shots with a curve, and a write that waits', async () => {
		const fs = memoryFs();
		await seedStore(small(3));
		const a = await boot(fs.opts);
		expect(a.archiveCaughtUp, 'the banner says the archive is still filling').toBe(false);
		await a.archiveCatchUpNow();
		expect(a.archiveCaughtUp).toBe(true);
		const status = a.archiveStatus!;
		expect(status).toMatchObject({ files: 3, curves: 3, waiting: false });
		expect(status.bytes).toBe([...fs.curves().values()].reduce((n, b) => n + b.length, 0));
		fs.control.quota = true;
		a.recordCaptured({ ...realShot(62, realSamples(5)), visualizerId: null } as unknown as Record<string, unknown>);
		await a.flushed();
		await a.archiveSettled();
		await a.countArchiveNow();
		expect(a.archiveStatus).toMatchObject({ files: 3, curves: 4, waiting: true });
	});
});

/** Channels that deliver to each other, as `BroadcastChannel` does. */
function bus() {
	const chans = new Set<ChannelLike>();
	return () => {
		const c: ChannelLike = {
			onmessage: null,
			postMessage(m) {
				for (const o of chans) if (o !== c) queueMicrotask(() => o.onmessage?.({ data: structuredClone(m) }));
			},
			close() {
				chans.delete(c);
			}
		};
		chans.add(c);
		return c;
	};
}

describe('only a recorded delete removes a file (#420 FC2-S1)', () => {
	it('probe G: a new tab’s recovery and check leave a stale tab’s only-copy curve, and the stale tab brings it back', async () => {
		const fs = memoryFs();
		const shots = small(20).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		a.setRating(shots[0].id, 3);
		await a.flushed();
		await until(() => fs.copies() === 1);
		// A shot made after today's copy: in the store and the archive, in no copy.
		const m = a.recordCaptured({ ...realShot(77, realSamples(7)), visualizerId: null } as unknown as Record<string, unknown>)!;
		await a.flushed();
		await a.archiveSettled();
		expect(fs.curves().has(archiveNameOf(m.id))).toBe(true);
		// Pushed out of the 16 kept, so A's memory holds it light.
		for (const s of shots.slice(0, 17)) await a.loadShot(s.id);
		await lose(a, shots[1].id);
		// The next morning a new tab recovers today's copy, and runs the day's check.
		const b = await boot({ ...fs.opts, now: () => Date.now() + 86_400_000 });
		expect(await b.recoverSnapshot()).toBe(true);
		await b.archiveCatchUpNow();
		expect(fs.curves().has(archiveNameOf(m.id)), 'no delete recorded it').toBe(true);
		// A, left open overnight, comes back and finds B's recovery.
		expect(await a.startEmpty()).toBe('reopened');
		await a.flushed();
		const { rows, details } = await stored();
		expect(rows.has(m.id)).toBe(true);
		expect(details.get(m.id)?.samples).toHaveLength(7);
	});

	it('a file no delete recorded is never removed, though no row or copy holds it', async () => {
		const fs = memoryFs();
		await seedStore(small(2));
		const a = await boot(fs.opts);
		await new CurveArchive(fs.fsRoot).write('shot:no-row', { samples: realSamples(4), brewSeries: null });
		await a.archiveCatchUpNow();
		const b = await boot({ ...fs.opts, now: () => Date.now() + 86_400_000 });
		await b.archiveCatchUpNow();
		expect(fs.curves().has(archiveNameOf('shot:no-row'))).toBe(true);
		// And Settings doesn't count it as archived.
		expect(b.archiveStatus).toMatchObject({ files: 2, curves: 2 });
	});

	it('a tombstone outlasts the load: a remove that failed goes at a later load’s catch-up, and the tombstone with it', async () => {
		const fs = memoryFs();
		const shots = small(2).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		fs.control.failRemove = true;
		a.delete(shots[0].id);
		await a.flushed();
		await new Promise((r) => setTimeout(r, 20));
		expect(fs.curves().has(archiveNameOf(shots[0].id))).toBe(true);
		expect(localStorage.getItem(`crema.history.archiveTombstone:${shots[0].id}`)).toBe('1');
		fs.control.failRemove = false;
		const b = await boot(fs.opts);
		await b.archiveCatchUpNow();
		expect(fs.curves().has(archiveNameOf(shots[0].id))).toBe(false);
		expect(localStorage.getItem(`crema.history.archiveTombstone:${shots[0].id}`)).toBeNull();
		expect(fs.curves().has(archiveNameOf(shots[1].id))).toBe(true);
	});

	it('a delete in another tab, landing while this tab writes that shot’s file, still removes it (FC2-N1)', async () => {
		const fs = memoryFs();
		const shots = small(2).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const channel = bus();
		const a = await boot({ ...fs.opts, channel });
		const b = await boot({ ...fs.opts, channel });
		// B starts writing X's file, and its close is held.
		let release = () => {};
		fs.control.hold = new Promise<void>((r) => (release = r));
		const x = shots[0].id;
		const writing = b.archiveCatchUpNow();
		await until(() => fs.curves().has(archiveNameOf(x)));
		// A deletes X: its remove finds an empty file, and B hears of it.
		a.delete(x);
		await a.flushed();
		await until(() => b.get(x) === undefined);
		fs.control.hold = null;
		release();
		await writing;
		await b.archiveSettled();
		await until(() => !fs.curves().has(archiveNameOf(x)));
		expect(fs.curves().has(archiveNameOf(shots[1].id))).toBe(true);
	});
});

describe('#420 fix check, c913b692', () => {
	it('FC3-S1: a check cut off mid-pass goes on from where it stopped at the next load, and a new pass waits for it', async () => {
		const fs = memoryFs();
		const shots = small(40);
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		// Late in id order: a file that went corrupt untouched.
		const order = shots.map((s) => s.id).sort();
		const late = order[order.length - 1];
		fs.curves().set(archiveNameOf(late), new Uint8Array([1, 2, 3]));
		// The next day's check is cut off after its second batch (the tab closed).
		let reads = 0;
		const day = 86_400_000;
		const b = await boot({
			...fs.opts,
			now: () => Date.now() + day,
			dbFault: (op) => {
				if (op === 'read-details' && ++reads > 2) throw new DOMException('closed', 'InvalidStateError');
			}
		});
		await b.archiveCatchUpNow();
		expect((await new CurveArchive(fs.fsRoot).read(late)).kind).toBe('corrupt');
		const cursor = JSON.parse(localStorage.getItem('crema.history.archiveCheck')!) as { after: string };
		expect(cursor.after).toBe(order[15]);
		// The next load, even another day on, goes on from there: it reads only the rest.
		let rest = 0;
		const c = await boot({
			...fs.opts,
			now: () => Date.now() + 2 * day,
			dbFault: (op) => {
				if (op === 'read-details') rest += 1;
			}
		});
		await c.archiveCatchUpNow();
		expect(rest).toBe(Math.ceil((40 - 16) / 8));
		expect((await new CurveArchive(fs.fsRoot).read(late)).kind).toBe('curve');
		expect(localStorage.getItem('crema.history.archiveCheck')).toBeNull();
	});

	it('FC3-N1: a delete while the store is lost records a tombstone, and a recovery brings no curve back with the row', async () => {
		const fs = memoryFs();
		const shots = small(3).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		await a.archiveCatchUpNow();
		a.setRating(shots[0].id, 2);
		await a.flushed();
		await until(() => fs.copies() === 1);
		await lose(a, shots[1].id);
		const doomed = shots[2].id;
		a.delete(doomed);
		await a.flushed();
		await until(() => !fs.curves().has(archiveNameOf(doomed)));
		const b = await boot(fs.opts);
		expect(await b.recoverSnapshot()).toBe(true);
		expect((await stored()).details.get(doomed)?.samples ?? []).toEqual([]);
	});

	it('FC3-N3: one tab’s tombstone survives another tab writing its own', async () => {
		const fs = memoryFs();
		const shots = small(3).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const a = await boot(fs.opts);
		const b = await boot(fs.opts);
		await a.archiveCatchUpNow();
		// Both tabs' removes fail: each records its own tombstone, and B's
		// doesn't write over A's.
		fs.control.failRemove = true;
		a.delete(shots[0].id);
		await a.flushed();
		b.delete(shots[1].id);
		await b.flushed();
		await new Promise((r) => setTimeout(r, 20));
		expect(localStorage.getItem(`crema.history.archiveTombstone:${shots[0].id}`)).toBe('1');
		expect(localStorage.getItem(`crema.history.archiveTombstone:${shots[1].id}`)).toBe('1');
		// Both close before they try again; a later load removes both files.
		fs.control.failRemove = false;
		const c = await boot(fs.opts);
		await c.archiveCatchUpNow();
		expect(fs.curves().has(archiveNameOf(shots[0].id))).toBe(false);
		expect(fs.curves().has(archiveNameOf(shots[1].id))).toBe(false);
		expect(fs.curves().has(archiveNameOf(shots[2].id))).toBe(true);
	});
});

describe('#420 FC4-N3', () => {
	it('one tab removes another tab’s tombstone file, and clears its key, without a new load', async () => {
		const fs = memoryFs();
		const shots = small(3).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		// Both tabs loaded before either delete.
		const a = await boot(fs.opts);
		const b = await boot(fs.opts);
		await a.archiveCatchUpNow();
		fs.control.failRemove = true;
		a.delete(shots[0].id);
		await a.flushed();
		await new Promise((r) => setTimeout(r, 20));
		expect(fs.curves().has(archiveNameOf(shots[0].id))).toBe(true);
		fs.control.failRemove = false;
		// B deletes another shot: its remove takes A's tombstone too.
		b.delete(shots[1].id);
		await b.flushed();
		await until(() => !fs.curves().has(archiveNameOf(shots[1].id)));
		await until(() => !fs.curves().has(archiveNameOf(shots[0].id)));
		expect(localStorage.getItem(`crema.history.archiveTombstone:${shots[0].id}`)).toBeNull();
		expect(fs.curves().has(archiveNameOf(shots[2].id))).toBe(true);
	});
});
