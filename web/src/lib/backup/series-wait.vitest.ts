/**
 * A backup holds the whole shot. Rows load at once and their curves follow
 * (#330), so a backup taken in between would save every shot without its curve.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getHistoryStore } from '#lib/history/index.js';
import { buildBackupJsonl, buildBackupZip } from './index';

afterEach(() => {
	vi.restoreAllMocks();
});

beforeEach(() => {
	localStorage.clear();
});

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
});
