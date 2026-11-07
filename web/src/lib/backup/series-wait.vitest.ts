/**
 * A backup holds the whole shot. Rows load at once and their curves follow
 * (#330), so a backup taken in between would save every shot without its curve.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { initTestWasm } from '#lib/testing/test-init.js';
import {
	backupNote,
	buildBackupJsonl,
	buildBackupZip,
	describeBackupGaps,
	driveBackupRefusal
} from './index';

afterEach(() => {
	vi.restoreAllMocks();
});

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
	getHistoryStore().clearAllShots();
});

const machineShot = (id: string, samples: unknown[]): StoredShot =>
	({
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		profileName: 'P',
		metadata: {},
		record: { duration: 30_000, samples },
		tags: []
	}) as unknown as StoredShot;

describe('buildBackupJsonl', () => {
	it('backup_waits_for_series: reads the rows only once the series are in', async () => {
		let land = (_: boolean): void => {};
		vi.spyOn(getHistoryStore(), 'seriesReady').mockImplementation(
			() => new Promise((resolve) => (land = resolve))
		);
		const all = vi.spyOn(getHistoryStore(), 'all', 'get');

		const building = buildBackupJsonl('test');
		await Promise.resolve();
		expect(all).not.toHaveBeenCalled();

		land(true);
		await building;
		expect(all).toHaveBeenCalled();
	});

	it('refuses rather than save shots without curves when the series never arrive', async () => {
		vi.spyOn(getHistoryStore(), 'seriesReady').mockResolvedValue(false);

		await expect(buildBackupJsonl('test')).rejects.toThrow(/still loading/);
		await expect(buildBackupZip('test')).rejects.toThrow(/still loading/);
	});

	it('the backup note says why, not just that it failed', async () => {
		vi.spyOn(getHistoryStore(), 'seriesReady').mockResolvedValue(false);

		expect(await backupNote(() => buildBackupZip('test').then((b) => b?.contents ?? null))).toBe(
			'Backup failed: nothing was saved. Shot data is still loading — try again in a moment.'
		);
	});
});

describe('a backup after the series failed to load', () => {
	const seed = () => {
		const store = getHistoryStore();
		store.insertPulled(machineShot('flat', []));
		store.insertPulled(machineShot('flat2', []));
		store.insertPulled({ ...machineShot('brew', []), brewMethod: 'pourover' } as StoredShot);
	};

	it('counts the shots with no curve and names them in the note', async () => {
		seed();

		const built = await buildBackupJsonl('test');

		expect(built?.contents.noCurves).toBe(2);
		expect(describeBackupGaps(built!.contents)).toContain('2 shots were backed up without their curve.');
	});

	it('a local backup goes ahead while the read failed, and says so', async () => {
		seed();
		vi.spyOn(getHistoryStore(), 'seriesFailed', 'get').mockReturnValue(true);

		const note = await backupNote(() => buildBackupZip('test').then((b) => b?.contents ?? null));

		expect(note).toMatch(/^Backed up — /);
		expect(note).toContain('2 shots were backed up without their curve.');
	});

	it('Drive refuses while the read failed, as Restore latest would take that file', async () => {
		seed();
		vi.spyOn(getHistoryStore(), 'seriesFailed', 'get').mockReturnValue(true);

		const built = await buildBackupJsonl('test');

		expect(driveBackupRefusal(built!.contents)).toContain('Nothing was uploaded');
	});

	it('Drive takes a shot that really has no curve when the read worked', async () => {
		seed();

		const built = await buildBackupJsonl('test');

		expect(driveBackupRefusal(built!.contents)).toBeNull();
	});

	it('Drive takes a backup with nothing missing, even after a failed read', async () => {
		vi.spyOn(getHistoryStore(), 'seriesFailed', 'get').mockReturnValue(true);

		expect(driveBackupRefusal({ profiles: 0, beans: 0, roasters: 0, shots: 0, noCurves: 0 })).toBeNull();
	});
});
