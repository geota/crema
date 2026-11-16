/**
 * #310 P6w: Free up space in Settings. It deletes only when the user says so:
 * it offers a backup first, says how many shots and about how much, and
 * pinned shots always stay. A backup that doesn't finish, a dismissed
 * question or a No deletes nothing.
 */
import '#lib/testing/dom-stubs.js';
import { afterAll, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION } from '#lib/history/history-db.js';
import { writeDoneMarker } from '#lib/history/store-marker.js';
import { summarize } from '#lib/history/summary.js';
import { boot, freshStorage, stored } from '#lib/history/idb-test-kit.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import { choiceDialog, confirmDialog } from '#lib/components/shared/confirm-dialog.svelte.js';
import { exportBackup, lastBackupAt } from '#lib/backup/index.js';
import { p3RowsOf } from '#lib/history/p3-rows.js';

vi.mock('#lib/components/shared/confirm-dialog.svelte.js', () => ({
	confirmDialog: vi.fn(async () => true),
	choiceDialog: vi.fn(async () => 'secondary')
}));
vi.mock('#lib/backup/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/backup/index.js')>()),
	exportBackup: vi.fn(async () => ({ shots: 20 }))
}));

let current: HistoryStore | null = null;
vi.mock('#lib/history/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/history/index.js')>()),
	getHistoryStore: () => current
}));
const { default: StorageSection } = await import('./StorageSection.svelte');

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	vi.mocked(confirmDialog).mockReset().mockResolvedValue(true);
	vi.mocked(choiceDialog).mockReset().mockResolvedValue('secondary');
	vi.mocked(exportBackup).mockReset().mockResolvedValue({ shots: 20 } as never);
});

const settle = async (ms = 20) => {
	await new Promise((r) => setTimeout(r, ms));
	flushSync();
};

const DAY = 86_400_000;
const NOW = Date.UTC(2026, 10, 15, 12);

/** 20 shots a day apart, the oldest two of the ten past the cut pinned. */
async function seed() {
	freshStorage();
	const shots = Array.from({ length: 20 }, (_, i) => ({
		...realShot(i, realSamples(4, i)),
		completedAt: NOW - i * DAY,
		visualizerId: null,
		pinned: i === 18 || i === 19
	}));
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);
	const history = await boot();
	current = history;
	return { shots, history };
}

const midnightOf = (ms: number) => {
	const d = new Date(ms);
	d.setHours(0, 0, 0, 0);
	return d.getTime();
};

async function freeUpBefore(cut: number): Promise<{ target: HTMLElement; done: () => void }> {
	const target = document.body.appendChild(document.createElement('div'));
	const app = mount(StorageSection, { target });
	await settle();
	const input = target.querySelector<HTMLInputElement>('input[type="date"]')!;
	const d = new Date(cut);
	input.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
	input.dispatchEvent(new Event('input', { bubbles: true }));
	flushSync();
	[...target.querySelectorAll('button')].find((b) => b.textContent?.includes('Free up space'))!.click();
	await settle(50);
	return { target, done: () => unmount(app) };
}

it('offers a backup, previews the count, and deletes the unpinned shots older than the date', async () => {
	const { shots, history } = await seed();
	vi.mocked(choiceDialog).mockResolvedValue('primary');
	// Shot 9's day, from midnight: shots 10 to 19 are older.
	const { target, done } = await freeUpBefore(midnightOf(shots[9].completedAt));
	expect(exportBackup).toHaveBeenCalledTimes(1);
	const message = vi.mocked(confirmDialog).mock.calls[0][0].message;
	expect(message).toMatch(/^Delete 8 shots older than .* about \d+(\.\d)? KB\? 2 pinned shots older than that stay\./);
	const { rows } = await stored();
	expect(rows.size).toBe(12);
	for (const s of shots.slice(10, 18)) expect(rows.has(s.id), s.id).toBe(false);
	expect(rows.has(shots[18].id) && rows.has(shots[19].id)).toBe(true);
	expect(p3RowsOf(history).live).toHaveLength(12);
	expect(target.textContent).toContain('Deleted 8 shots.');
	done();
});

it('a backup that doesn’t finish, a dismissed question or a No deletes nothing', async () => {
	for (const step of ['backup fails', 'dismissed', 'no'] as const) {
		const { history } = await seed();
		if (step === 'backup fails') {
			vi.mocked(choiceDialog).mockResolvedValue('primary');
			vi.mocked(exportBackup).mockResolvedValue(null);
		}
		if (step === 'dismissed') vi.mocked(choiceDialog).mockResolvedValue(null);
		if (step === 'no') vi.mocked(confirmDialog).mockResolvedValue(false);
		const { target, done } = await freeUpBefore(midnightOf(NOW - 9 * DAY));
		expect((await stored()).rows.size, step).toBe(20);
		expect(p3RowsOf(history).live, step).toHaveLength(20);
		if (step === 'backup fails') expect(target.textContent).toContain('nothing was deleted');
		done();
		vi.mocked(confirmDialog).mockResolvedValue(true);
		vi.mocked(exportBackup).mockResolvedValue({ shots: 20 } as never);
	}
});

/** A backup that finished, written with `gaps`. */
const backupWith = (gaps: Record<string, unknown>) =>
	({ profiles: 0, beans: 0, roasters: 0, shots: 19, omitted: [], degraded: [], noCurves: 0, noCurveIds: [], missing: [], largeInMemory: false, ...gaps }) as never;

it('#437 B1: a shot the "Back up first" backup left out is kept, and said so', async () => {
	const { shots } = await seed();
	vi.mocked(choiceDialog).mockResolvedValue('primary');
	// The backup finished, but the core couldn't write shot 12, so it isn't in the file.
	vi.mocked(exportBackup).mockResolvedValue(backupWith({ omitted: [{ kind: 'shot', index: 12, id: shots[12].id }] }));
	const { target, done } = await freeUpBefore(midnightOf(shots[9].completedAt));
	expect(exportBackup).toHaveBeenCalledTimes(1);
	const message = vi.mocked(confirmDialog).mock.calls[0][0].message;
	expect(message).toMatch(/^Delete 7 shots older than/);
	expect(message).toContain('One of these shots couldn’t be backed up and was kept.');
	const { rows } = await stored();
	expect(rows.has(shots[12].id), 'shot 12 is in no backup').toBe(true);
	for (const s of [10, 11, 13, 14, 15, 16, 17]) expect(rows.has(shots[s].id), String(s)).toBe(false);
	expect(rows.size).toBe(13);
	// The backup's own summary, and Last backup moves.
	expect(target.textContent).toContain('Backed up — ');
	expect(target.textContent).toContain('1 shot couldn’t be read and was left out.');
	expect(target.textContent).toContain('One of these shots couldn’t be backed up and was kept.');
	expect(lastBackupAt()).not.toBeNull();
	done();
});

it('#437 B1: shots the backup wrote without their curve are kept too', async () => {
	const { shots } = await seed();
	vi.mocked(choiceDialog).mockResolvedValue('primary');
	vi.mocked(exportBackup).mockResolvedValue(backupWith({ noCurves: 2, noCurveIds: [shots[11].id, shots[16].id] }));
	const { target, done } = await freeUpBefore(midnightOf(shots[9].completedAt));
	expect(vi.mocked(confirmDialog).mock.calls[0][0].message).toContain('2 of these shots couldn’t be backed up and were kept.');
	const { rows } = await stored();
	expect(rows.has(shots[11].id) && rows.has(shots[16].id)).toBe(true);
	expect(rows.size).toBe(14);
	expect(target.textContent).toContain('2 shots were backed up without their curve.');
	done();
});

it('#437 B1: a backup that can’t say which shots it holds whole deletes nothing', async () => {
	for (const gaps of [{ omitted: [{ kind: 'shot', index: 3, id: null }] }, { noCurves: 1, noCurveIds: undefined }, { missing: ['shot history'] }]) {
		const { history } = await seed();
		vi.mocked(choiceDialog).mockResolvedValue('primary');
		vi.mocked(exportBackup).mockResolvedValue(backupWith(gaps));
		const { target, done } = await freeUpBefore(midnightOf(NOW - 9 * DAY));
		expect(confirmDialog, JSON.stringify(gaps)).not.toHaveBeenCalled();
		expect((await stored()).rows.size).toBe(20);
		expect(p3RowsOf(history).live).toHaveLength(20);
		expect(target.textContent).toContain('nothing was deleted');
		done();
	}
});
