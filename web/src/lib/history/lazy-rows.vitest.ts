/**
 * #310 P4w: the history's rows are light (SPEC §4). A row carries its summary
 * and no samples, profile or brew series; `loadShot` gives the whole shot.
 *
 * - Startup and the list read no `details`, and the list makes no core call
 *   that takes a shot, per row or at all.
 * - The four quiet failures a row with empty samples would cause: a new
 *   de-dup signature, a measured row that looks manual, a backfill over the
 *   shot's own curve, and a row handed to the core reading as zeros.
 * - A backfill recomputes the summary; a pulled recipe only fills.
 * - Legacy mode merges on write, so a row edit keeps profile, brew series and
 *   samples.
 * - Whole shots for compare, export and the detail, from a cold start; the
 *   last 16 are kept; a store lost under the tab keeps the ones it loaded.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as Wasm from '#lib/wasm/de1_wasm.js';

/** Every wasm export, counted, so a test can say which ran. */
const wasmCalls = vi.hoisted(() => new Map<string, number>());
vi.mock('#lib/wasm/de1_wasm.js', async (importOriginal) => {
	const real = await importOriginal<typeof Wasm>();
	const out: Record<string, unknown> = { ...real };
	for (const [name, fn] of Object.entries(real)) {
		if (name === 'default' || typeof fn !== 'function' || /^[A-Z]/.test(name)) continue;
		out[name] = (...args: unknown[]) => {
			wasmCalls.set(name, (wasmCalls.get(name) ?? 0) + 1);
			return (fn as (...a: unknown[]) => unknown)(...args);
		};
	}
	return out;
});

import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot, medianProfile } from '#lib/testing/real-shots.js';
import { reconcileShots, signatureForShot, type WireShot } from '#lib/visualizer/shot-sync-signatures.js';
import { HistoryDb, META_MIGRATION, type MigrationRecord } from './history-db';
import {
	brewStatsOf,
	hasTelemetry,
	isManualLog,
	peaksOf,
	ratioLabel,
	sampleCountOf,
	statsOf,
	yieldOf,
	type ShotRow,
	type StoredShot
} from './model';
import { writeDoneMarker } from './store-marker';
import { summarize } from './summary';
import { decodeSpark } from './spark';
import { HistoryStore, LOADED_SHOTS_KEPT, HISTORY_KEY } from './store.svelte';
import { nextShots } from '#lib/restore/engine.js';
import { boot, freshStorage, stored, whole, writeOldCopy, LEGACY_KEY } from './idb-test-kit';
import { getAllSeries } from './series-store';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;

beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
	wasmCalls.clear();
});

const small = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));

/** A guided pourover: a scale series and a recipe, no machine samples. */
function guidedBrew(i: number): StoredShot {
	const base = realShot(i, []);
	return {
		...base,
		id: `shot:brew-${i}`,
		brewMethod: 'pourover',
		visualizerId: null,
		machine: null,
		brewSeries: {
			samples: Array.from({ length: 30 }, (_, k) => ({ elapsedMs: k * 5_000, weightG: k * 8, flowGS: 1.5 })),
			stageMarks: [
				{ elapsedMs: 0, stepIndex: 0, targetWaterG: 50 },
				{ elapsedMs: 45_000, stepIndex: 1, targetWaterG: 250 }
			]
		}
	} as StoredShot;
}

/** A manually logged brew: a method, and nothing measured. */
function manualBrew(i: number): StoredShot {
	const { profile: _p, ...base } = realShot(i, []);
	return { ...base, id: `shot:manual-${i}`, brewMethod: 'aeropress', visualizerId: null, machine: null, profile: null } as StoredShot;
}

/** A device already on `crema-history` with `shots`, as a migration leaves it. */
async function seedStore(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	for (let i = 0; i < shots.length; i += 500) {
		await db.putShots(
			shots.slice(i, i + 500).map((shot) => ({ shot, details: true })),
			summarize
		);
	}
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

/** A store's reads of `details`, counted through the database's fault seam. */
function countingDetails() {
	const reads = { details: 0 };
	return {
		reads,
		dbFault: (op: string) => {
			if (op === 'read-details') reads.details += 1;
		}
	};
}

/** Wasm calls that take a whole shot or its samples. */
const SHOT_CALLS = ['shotSummary', 'peaksForShot', 'pourAveragesForShot', 'analyzeStoredShotQuality'];
const shotCalls = () => SHOT_CALLS.reduce((n, k) => n + (wasmCalls.get(k) ?? 0), 0);

describe('startup and the list read no details', () => {
	it('a boot, then every row read as the list reads it, reads no details and makes no core call on a shot', async () => {
		const shots = [...small(320), guidedBrew(400), manualBrew(401)];
		await seedStore(shots);
		const { reads, dbFault } = countingDetails();
		wasmCalls.clear();
		const store = await boot({ dbFault });
		expect(store.health).toBe('ok');
		expect(reads.details, 'the boot').toBe(0);

		// What a row, the stat strip, the filters and the facets read, for every
		// row, three times over as a list scrolls up and down.
		for (let pass = 0; pass < 3; pass++) {
			for (const row of store.all) {
				peaksOf(row);
				yieldOf(row);
				ratioLabel(row);
				hasTelemetry(row);
				isManualLog(row);
				decodeSpark(row.summary.spark);
				store.get(row.id);
			}
			for (const row of store.rawAll) signatureForShot(row);
		}
		statsOf(store.all);
		brewStatsOf(store.all);
		void store.withoutTelemetry;
		void store.visualizerBacklog;
		expect(reads.details, 'scrolling').toBe(0);
		expect(shotCalls(), 'core calls that take a shot').toBe(0);
		// One stats call for the strip, not one per row.
		expect(wasmCalls.get('historyStats')).toBe(1);
		expect(wasmCalls.get('brewHistoryStats')).toBe(1);

		// Opening a shot reads its details once; opening it again reads none.
		const id = store.all[5].id;
		expect((await store.loadShot(id))!.record.samples).toHaveLength(4);
		expect(reads.details).toBe(1);
		await store.loadShot(id);
		expect(reads.details).toBe(1);
	}, 60_000);

	it('every row is light, in view and past it, and its summary is the whole shot’s', async () => {
		const shots = [...small(310), guidedBrew(400)];
		await seedStore(shots);
		const store = await boot();
		for (const row of store.rawAll) {
			expect('samples' in row.record).toBe(false);
			expect('profile' in row).toBe(false);
			expect('brewSeries' in row).toBe(false);
		}
		const brew = store.get('shot:brew-400')!;
		expect(brew.summary.brewSampleCount).toBe(30);
		expect(decodeSpark(brew.summary.spark)).toMatchObject({ brew: true, machine: false, hasWeight: true });
		const machine = store.all[0];
		expect(decodeSpark(machine.summary.spark)).toMatchObject({ machine: true });
	});

	it('the last 16 loaded are kept: a 17th pushes the first out', async () => {
		await seedStore(small(40));
		const { reads, dbFault } = countingDetails();
		const store = await boot({ dbFault });
		const ids = store.all.map((s) => s.id);
		for (let i = 0; i <= LOADED_SHOTS_KEPT; i++) await store.loadShot(ids[i]);
		expect(reads.details).toBe(LOADED_SHOTS_KEPT + 1);
		await store.loadShot(ids[LOADED_SHOTS_KEPT]);
		expect(reads.details, 'the newest is kept').toBe(LOADED_SHOTS_KEPT + 1);
		await store.loadShot(ids[0]);
		expect(reads.details, 'the first was pushed out').toBe(LOADED_SHOTS_KEPT + 2);
	});

	it('a shot just recorded opens with no read: its written parts are kept', async () => {
		await seedStore(small(3));
		const { reads, dbFault } = countingDetails();
		const store = await boot({ dbFault });
		const made = store.recordCaptured(realShot(90, realSamples(6)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		expect('samples' in store.get(made.id)!.record).toBe(false);
		expect(store.get(made.id)!.summary.sampleCount).toBe(6);
		expect((await store.loadShot(made.id))!.record.samples).toHaveLength(6);
		expect(reads.details).toBe(0);
	});
});

describe('the four quiet failures of a row with empty samples (SPEC §4)', () => {
	it('signature: every light row signs as its whole shot, so a pull binds instead of adding a copy', async () => {
		const shots = small(20).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const store = await boot();
		for (const row of store.rawAll) {
			const full = whole(shots.find((s) => s.id === row.id)!);
			expect(signatureForShot(row), row.id).toBe(signatureForShot(full));
		}
		const target = whole(shots[7]);
		const p = peaksOf(target);
		const remote = {
			id: 'remote-7',
			clock: target.completedAt,
			duration_ms: target.record.duration,
			profile_title: target.profileName ?? null,
			final_weight_g: p.finalWeight ?? p.peakWeight,
			notes: null,
			rating: null,
			updated_at_ms: null,
			tag_list: []
		} as unknown as WireShot;
		expect(reconcileShots(store.rawAll, [remote])).toEqual([
			expect.objectContaining({ kind: 'bind', localId: target.id, visualizerId: 'remote-7' })
		]);
	});

	it('isManualLog: a guided brew row is measured, so its facts stay fixed; a manual log stays editable', async () => {
		await seedStore([guidedBrew(1), manualBrew(2)]);
		const store = await boot();
		const brew = store.get('shot:brew-1')!;
		expect(isManualLog(brew)).toBe(false);
		store.updateManualBrew(brew.id, { dose: 99 });
		expect(store.get(brew.id)!.metadata.dose).toBe(18);
		const manual = store.get('shot:manual-2')!;
		expect(isManualLog(manual)).toBe(true);
		store.updateManualBrew(manual.id, { dose: 21 });
		expect(store.get(manual.id)!.metadata.dose).toBe(21);
		// An unknown count is not "none": not manual.
		expect(isManualLog({ ...brew, summary: { ...brew.summary, sampleCount: undefined, brewSampleCount: 0 } } as ShotRow)).toBe(false);
	});

	it('backfill: a pulled curve never replaces a row’s stored one, and a pulled recipe never its stored profile', async () => {
		const shots = small(5).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const store = await boot();
		const id = shots[2].id;
		const pulled = realSamples(9, 30);
		store.backfillTelemetry(id, pulled, 30_000);
		// Refused outright: the summary says the store holds a curve, so nothing
		// is even queued to write (the store's fill rule is a second guard).
		expect(store.hasUnsaved).toBe(false);
		store.applyRemote(id, { backfillSamples: true, fillProfile: true } as never, {
			samples: pulled,
			durationMs: 30_000,
			profile: { title: 'Pulled recipe' }
		});
		await store.flushed();
		const { details, rows } = await stored();
		expect(details.get(id)!.samples).toEqual(shots[2].record.samples);
		expect((details.get(id)!.profile as { title?: string }).title).toBe((medianProfile() as { title?: string }).title);
		expect(rows.get(id)!.summary.sampleCount).toBe(4);
		expect(store.get(id)!.summary.sampleCount).toBe(4);
	});

	it('rows handed to the core: peaks, stats and the spark come from the summary, never zeros', async () => {
		const shots = small(12);
		await seedStore(shots);
		const store = await boot();
		const rows = store.rawAll;
		const wholes = rows.map((r) => whole(shots.find((s) => s.id === r.id)!));
		rows.forEach((r, i) => {
			expect(peaksOf(r), r.id).toEqual(peaksOf(wholes[i]));
			expect(peaksOf(r).peakPressure).toBeGreaterThan(0);
		});
		expect(statsOf(rows)).toEqual(statsOf(wholes));
		// The same rows read as whole shots with empty samples would be zeros.
		const curveless = wholes.map((w) => ({ ...w, record: { duration: w.record.duration, samples: [] } }));
		expect(peaksOf(curveless[0]).peakPressure).toBe(0);
	});
});

describe('a backfill recomputes the summary', () => {
	it('on crema-history: a row with no curve takes the pulled one, and its counts, peaks and spark follow', async () => {
		const stub = { ...realShot(3, []), id: 'shot:remote:77', visualizerId: '77' } as StoredShot;
		await seedStore([stub, ...small(3)]);
		const store = await boot();
		const before = store.get(stub.id)!;
		expect(before.summary.sampleCount).toBe(0);
		expect(before.summary.spark ?? null).toBeNull();
		const pulled = realSamples(12, 5);
		store.backfillTelemetry(stub.id, pulled, 40_000);
		await store.flushed();
		const after = store.get(stub.id)!;
		expect('samples' in after.record).toBe(false);
		expect(after.summary.sampleCount).toBe(12);
		expect(after.summary.peakPressure).toBeGreaterThan(0);
		expect(decodeSpark(after.summary.spark)?.machine).toBe(true);
		expect((await stored()).rows.get(stub.id)!.summary.sampleCount).toBe(12);
		expect((await store.loadShot(stub.id))!.record.samples).toHaveLength(12);
		// And after a reload.
		expect((await boot()).get(stub.id)!.summary.sampleCount).toBe(12);
	});

	it('on the legacy backend: the summary follows the curve in memory', async () => {
		const stub = { ...realShot(3, []), id: 'shot:remote:78', visualizerId: '78' } as StoredShot;
		await writeOldCopy([stub]);
		const store = new HistoryStore({ backend: 'legacy' });
		await store.seriesReady();
		expect(store.get(stub.id)!.summary.sampleCount).toBe(0);
		store.backfillTelemetry(stub.id, realSamples(7, 2), 40_000);
		expect(store.get(stub.id)!.summary.sampleCount).toBe(7);
		expect(decodeSpark(store.get(stub.id)!.summary.spark)?.machine).toBe(true);
	});
});

describe('the legacy backend merges on write', () => {
	it('a row edit, a restore plan passing rows through, and adopted rows keep profile, brew series and samples', async () => {
		const machine = { ...realShot(1, realSamples(5)), visualizerId: null };
		const brew = guidedBrew(2);
		await writeOldCopy([machine, brew]);
		const store = new HistoryStore({ backend: 'legacy' });
		await store.seriesReady();

		store.setRating(machine.id, 1);
		store.setNotes(brew.id, 'edited');
		store.setPinned(brew.id, true);
		// A restore's plan works on rows, and may hand one back edited.
		const r = await store.restoreRows((rows) => ({
			next: rows.map((s) => (s.id === machine.id ? { ...s, bagDebit: 3 } : s)),
			series: []
		}));
		expect(r.stored).toBe(true);
		// Rows another tab wrote, handed in as rows.
		store.adoptRows(store.rawAll);
		store.setRating(brew.id, 5);

		// Whole in this tab, too: memory kept what the rows handed in lacked.
		expect((await store.loadShot(machine.id))!.record.samples).toEqual(machine.record.samples);
		expect((await store.loadShot(brew.id))!.brewSeries).toEqual(brew.brewSeries);
		const blob = JSON.parse(localStorage.getItem(HISTORY_KEY)!) as StoredShot[];
		const m = blob.find((s) => s.id === machine.id)!;
		const b = blob.find((s) => s.id === brew.id)!;
		expect(m.profile).toEqual(machine.profile);
		expect(m.bagDebit).toBe(3);
		expect(m.metadata.rating).toBe(1);
		expect(b.profile).toEqual(brew.profile);
		expect(b.brewSeries).toEqual(brew.brewSeries);
		expect(b.metadata.notes).toBe('edited');
		expect(b.metadata.rating).toBe(5);
		expect((await getAllSeries()).get(machine.id)).toEqual(machine.record.samples);

		// And whole again after a reload.
		const again = new HistoryStore({ backend: 'legacy' });
		await again.seriesReady();
		const mm = (await again.loadShot(machine.id))!;
		expect(mm.record.samples).toEqual(machine.record.samples);
		expect(mm.profile).toEqual(machine.profile);
		expect((await again.loadShot(brew.id))!.brewSeries).toEqual(brew.brewSeries);
		expect(again.get(brew.id)!.summary.brewSampleCount).toBe(30);
	});
});

describe('whole shots from a cold start', () => {
	it('compare and export load every picked shot whole, in order, though no row holds a curve', async () => {
		const shots = small(320);
		await seedStore(shots);
		const store = await boot();
		// Five picks, one past the view.
		const picked = [store.all[3].id, store.rawAll[315].id, store.all[0].id, store.all[200].id, store.all[299].id];
		const got = await store.loadMany(picked);
		expect(got.map((s) => s.id)).toEqual(picked);
		for (const s of got) expect(s.record.samples, s.id).toEqual(shots.find((x) => x.id === s.id)!.record.samples);
		for (const s of got) expect(s.profile, s.id).toEqual(shots.find((x) => x.id === s.id)!.profile);
		// Every row, as the history export walks them.
		const all = await store.loadMany(store.all.map((s) => s.id));
		expect(all).toHaveLength(300);
		for (const s of all) expect(s.record.samples, s.id).toEqual(shots.find((x) => x.id === s.id)!.record.samples);
	}, 60_000);

	it('a read that fails twice throws: the caller stops, and never goes on with empty curves', async () => {
		await seedStore(small(5));
		let fail = false;
		const store = await boot({
			dbFault: (op) => {
				if (fail && op === 'read-details') throw new DOMException('gone', 'UnknownError');
			}
		});
		fail = true;
		await expect(store.loadShot(store.all[1].id)).rejects.toThrow();
		await expect(store.loadMany(store.all.map((s) => s.id))).rejects.toThrow();
	});
});

describe('the restore plan reads counts from the summary', () => {
	it('a row with a stored curve is not filled; one with none is; one whose count is unknown is not', async () => {
		const shots = small(3).map((s) => ({ ...s, visualizerId: null }));
		await seedStore(shots);
		const store = await boot();
		const rows = store.rawAll;
		const backup = shots.map((s) => ({ ...s, record: { ...s.record, samples: realSamples(8, 40) } }));
		const unknown = { ...rows[1], summary: { ...rows[1].summary, sampleCount: undefined } } as ShotRow;
		const none = { ...rows[2], summary: { ...rows[2].summary, sampleCount: 0 } } as ShotRow;
		const plan = { add: [], overwrite: [], revive: [], fill: rows.map((r) => r.id), remove: [], zeroDebit: [] } as never;
		const { series } = nextShots([rows[0], unknown, none], backup, plan);
		expect(series.map((s) => s.id)).toEqual([rows[2].id]);
		expect(sampleCountOf(rows[0])).toBe(4);
	});
});

describe('a store lost under the tab', () => {
	it('the shots this tab loaded or wrote last keep their curves into the recovery', async () => {
		const shots = small(5);
		await writeOldCopy(shots);
		const a = await boot();
		const made = a.recordCaptured(realShot(64, realSamples(4)) as unknown as Record<string, unknown>)!;
		await a.flushed();
		const opened = a.all.find((s) => s.id !== made.id)!.id;
		await a.loadShot(opened);
		await new Promise<void>((resolve, reject) => {
			const req = indexedDB.deleteDatabase('crema-history');
			req.onsuccess = () => resolve();
			req.onerror = () => reject(req.error);
		});
		a.setRating(opened, 3);
		await a.flushed();
		expect(a.health).toBe('lost');
		expect(a.unsavedShots).toContain(made.id);
		expect(await a.startEmpty()).toBe(true);
		await a.flushed();
		const { details } = await stored();
		expect(details.get(made.id)!.samples).toHaveLength(4);
		expect(details.get(opened)!.samples).toHaveLength(4);
	});
});

describe('Replace keeps the device’s profile when the backup row has none (Android’s rule)', () => {
	for (const backend of ['idb', 'legacy'] as const) {
		it(`on ${backend === 'idb' ? 'crema-history' : 'the legacy backend'}`, async () => {
			const own = { ...realShot(1, realSamples(5)), visualizerId: null };
			if (backend === 'idb') await seedStore([own]);
			else await writeOldCopy([own]);
			const store = backend === 'idb' ? await boot() : new HistoryStore({ backend: 'legacy' });
			await store.seriesReady();
			// The backup's copy of the same shot: other notes, no profile, no curve.
			const theirs = { ...own, profile: null, metadata: { ...own.metadata, notes: 'from the backup' }, record: { duration: own.record.duration, samples: [] } } as StoredShot;
			const r = await store.restoreRows(() => ({ next: [theirs], series: [theirs] }));
			expect(r.stored).toBe(true);
			const reloaded = backend === 'idb' ? await boot() : new HistoryStore({ backend: 'legacy' });
			await reloaded.seriesReady();
			for (const s of [store, reloaded]) {
				const whole = (await s.loadShot(own.id))!;
				expect(whole.metadata.notes).toBe('from the backup');
				expect(whole.profile).toEqual(own.profile);
				expect(whole.record.samples).toEqual(own.record.samples);
			}
		});
	}
});
