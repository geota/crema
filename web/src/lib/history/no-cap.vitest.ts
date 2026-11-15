/**
 * #310 P6w: the cap lifts on web (SPEC §8, "P6w + P6a"). Every row on
 * `crema-history` is kept and shown; nothing is evicted, refused for its date
 * or hidden; a full disk loses nothing and says so; Free up space is the only
 * bulk removal, run by the user, sparing pinned shots and bag weights.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { getBeanStore, blankBean } from '#lib/bean/index.js';
import { remoteKeptOut } from '#lib/visualizer/upload-marks.js';
import { buildBackupJsonl, restoreBackup } from '#lib/backup/index.js';
import { HistoryDb, META_MIGRATION, type MigrationRecord } from './history-db';
import type { StoredShot } from './model';
import { writeDoneMarker } from './store-marker';
import { summarize } from './summary';
import { freeUpPlan } from './free-up';
import { readPending } from './pending';
import { boot, freshStorage, stored } from './idb-test-kit';
import { getHistoryStore } from './store.svelte';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
});

const small = (n: number, from = 0) => Array.from({ length: n }, (_, i) => realShot(from + i, realSamples(4, (from + i) % 60)));

/** A device already on `crema-history` with `shots`, as a migration leaves it. */
async function seedStore(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	for (let i = 0; i < shots.length; i += 1_000) {
		await db.putShots(
			shots.slice(i, i + 1_000).map((shot) => ({ shot, details: true })),
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
}

const brew = (completedAt: number) => ({
	method: 'pourover' as const,
	completedAt,
	bean: null,
	dose: 15,
	waterG: 250,
	yieldOut: null,
	grinderSetting: null,
	brewTempC: null,
	durationMs: null,
	rating: null,
	notes: null,
	nextPlan: null
});

describe('every row is kept', () => {
	it('row 301 survives a save and a reload', async () => {
		await seedStore(small(300));
		const store = await boot();
		const made = store.recordCaptured(realShot(9_001, realSamples(6)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		expect(store.all).toHaveLength(301);
		const oldest = store.all[300].id;
		// A save of another row afterwards drops nothing either.
		store.setRating(store.all[5].id, 2);
		await store.flushed();
		expect((await stored()).rows.size).toBe(301);
		const again = await boot();
		expect(again.all).toHaveLength(301);
		expect(again.get(made.id)).toBeDefined();
		expect(again.get(oldest)).toBeDefined();
		expect((await again.loadShot(made.id))!.record.samples).toHaveLength(6);
	});

	it('a brew backdated before all 5,000 rows is kept and shown, last', async () => {
		const shots = small(5_000);
		await seedStore(shots);
		const store = await boot();
		const oldestAt = Math.min(...shots.map((s) => s.completedAt));
		const at = oldestAt - 86_400_000;
		expect(store.fitsHistory(at)).toBe(true);
		const made = store.addManualBrew(brew(at))!;
		await store.flushed();
		expect(store.all).toHaveLength(5_001);
		expect(store.all.at(-1)!.id).toBe(made.id);
		// The newest row is still the last brew.
		expect(store.all[0].id).toBe(shots[0].id);
		const again = await boot();
		expect(again.all).toHaveLength(5_001);
		expect(again.all.at(-1)!.id).toBe(made.id);
	}, 120_000);

	it('no automatic deletion: 20,000 rows, then 100 more saved, keeps all 20,100', async () => {
		await seedStore(small(20_000));
		const store = await boot();
		expect(store.all).toHaveLength(20_000);
		const made: string[] = [];
		for (let i = 0; i < 100; i++) {
			made.push(store.recordCaptured(realShot(30_000 + i, realSamples(3)) as unknown as Record<string, unknown>)!.id);
			if (i % 10 === 9) await store.flushed();
		}
		await store.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(20_100);
		expect(details.size).toBe(20_100);
		for (const id of made) expect(rows.has(id), id).toBe(true);
		const again = await boot();
		expect(again.all).toHaveLength(20_100);
	}, 300_000);
});

describe('a full disk loses nothing and says so', () => {
	it('a quota failure keeps the shot, its curve and its row, shows it unsaved, and saves it once there’s room', async () => {
		await seedStore(small(320));
		let full = false;
		const store = await boot({
			dbFault: (op) => {
				if (full && op === 'write') throw new DOMException('full', 'QuotaExceededError');
			}
		});
		full = true;
		const made = store.recordCaptured(realShot(9_100, realSamples(7)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		// Nothing stored, nothing lost: the row in memory and the journal, the curve in memory.
		expect((await stored()).rows.has(made.id)).toBe(false);
		expect(store.get(made.id)).toBeDefined();
		expect(store.unsavedShots).toEqual([made.id]);
		expect(store.hasUnsaved).toBe(true);
		expect(readPending().map((r) => r.id)).toContain(made.id);
		expect((await store.loadShot(made.id))!.record.samples).toHaveLength(7);
		// The other 320 are untouched.
		expect((await stored()).rows.size).toBe(320);
		full = false;
		store.setRating(made.id, 3);
		await store.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(321);
		expect(details.get(made.id)!.samples).toHaveLength(7);
		expect(store.hasUnsaved).toBe(false);
	});
});

describe('Free up space', () => {
	it('removes the unpinned shots older than the date, spares pinned ones, and leaves bag weights alone', async () => {
		const bean = { ...blankBean('bean:p6w'), name: 'Gesha', bagSize: 250, remaining: 120 };
		getBeanStore().upsertBean(bean);
		const shots = small(40).map((s, i) => ({
			...s,
			bean: { ...s.bean!, beanId: bean.id },
			bagDebit: 15,
			visualizerId: i === 35 ? 'viz-35' : null,
			pinned: i === 38 || i === 30
		}));
		await seedStore(shots);
		const store = await boot();
		// Older than the 25th row: rows 25..39, of which 30 and 38 are pinned.
		const before = shots[24].completedAt;
		const plan = freeUpPlan(store.rawAll, before);
		expect(plan.ids.sort()).toEqual(shots.slice(25).filter((s) => !s.pinned).map((s) => s.id).sort());
		expect(plan.pinnedKept).toBe(2);
		expect(plan.bytes).toBeGreaterThan(0);
		const beanBefore = JSON.stringify(getBeanStore().getBean(bean.id));
		const result = await store.freeUpSpace([...plan.ids, shots[30].id, 'shot:not-here']);
		expect(result).toEqual({ removed: 13, skipped: 2 });
		await store.flushed();
		const { rows, details } = await stored();
		expect(rows.size).toBe(27);
		for (const id of plan.ids) {
			expect(rows.has(id), id).toBe(false);
			expect(details.has(id), id).toBe(false);
		}
		expect(rows.has(shots[30].id)).toBe(true);
		expect(rows.has(shots[38].id)).toBe(true);
		// No removal credits a bag.
		expect(JSON.stringify(getBeanStore().getBean(bean.id))).toBe(beanBefore);
		expect(getBeanStore().getBean(bean.id)!.remaining).toBe(120);
		// The Visualizer copy stays there, and a pull leaves it out.
		expect(remoteKeptOut().has('viz-35')).toBe(true);
		// Newest rows, the "last brew", untouched.
		expect(store.all[0].id).toBe(shots[0].id);
		const again = await boot();
		expect(again.all).toHaveLength(27);
	});
});

describe('a 5,000-shot backup from Android', () => {
	it('restores into the real store with every row and sample count, and backs up again whole', async () => {
		const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
		const lines = readFileSync(resolve(FIXTURES, 'android-backup.crema.jsonl'), 'utf8').split('\n').filter(Boolean);
		const head = JSON.parse(lines[0]);
		const shotLine = JSON.parse(lines.find((l) => l.includes('"kind":"shot"'))!);
		const base = shotLine.record.samples as { elapsed?: number; elapsedMs?: number }[];
		const counts = new Map<string, number>();
		const shots = Array.from({ length: 5_000 }, (_, i) => {
			const n = 1 + (i % 9);
			const samples = Array.from({ length: n }, (_, k) => ({ ...base[k % base.length], elapsed: k * 250 }));
			const id = `shot:android-${String(i).padStart(5, '0')}`;
			counts.set(id, n);
			return JSON.stringify({ ...shotLine, id, completedAt: shotLine.completedAt - i * 3_600_000, record: { ...shotLine.record, samples } });
		});
		const others = lines.slice(1).filter((l) => !l.includes('"kind":"shot"'));
		const jsonl = [JSON.stringify({ ...head, shotCount: 5_000 }), ...others, ...shots].join('\n') + '\n';

		const history = getHistoryStore();
		await history.whenReady();
		expect(history.health).toBe('ok');
		const summary = await restoreBackup(jsonl, 'merge');
		expect(summary.outcome.kind).toBe('done');
		await history.flushed();
		expect(history.all).toHaveLength(5_000);
		for (const row of history.rawAll) expect(row.summary.sampleCount, row.id).toBe(counts.get(row.id));
		const { rows, details } = await stored();
		expect(rows.size).toBe(5_000);
		for (const [id, n] of counts) expect(details.get(id)?.samples, id).toHaveLength(n);

		// And back out: a backup holds all 5,000, each with its curve.
		const built = (await buildBackupJsonl('test'))!;
		const out = built.jsonl
			.split('\n')
			.filter((l) => l.includes('"kind":"shot"'))
			.map((l) => JSON.parse(l) as { id: string; record: { samples: unknown[] } });
		expect(out).toHaveLength(5_000);
		for (const s of out) expect(s.record.samples, s.id).toHaveLength(counts.get(s.id)!);
	}, 300_000);
});
