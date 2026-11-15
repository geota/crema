/**
 * #420 FC2-N1: a factory reset waits for a curve-archive write in flight
 * before it removes the archive's directory, so no write lands after the
 * erase.
 */
import { expect, it } from 'vitest';
import { IDBFactory as FakeIDBFactory } from 'fake-indexeddb';
import { realSamples } from '#lib/testing/real-shots.js';
import { CurveArchive, archiveNameOf } from '#lib/history/curve-archive.js';
import { memoryFs } from '#lib/history/archive-test-kit.js';
import { factoryResetLocalData } from './factory-reset';

it('the reset removes the archive only after the write in flight has landed', async () => {
	const fs = memoryFs();
	let release = () => {};
	fs.control.hold = new Promise<void>((r) => (release = r));
	const write = new CurveArchive(fs.fsRoot).write('shot:a', { samples: realSamples(5), brewSeries: null });
	await new Promise((r) => setTimeout(r, 20));
	const reset = factoryResetLocalData({ indexedDB: new FakeIDBFactory() as unknown as IDBFactory, privateFiles: fs.fsRoot });
	await new Promise((r) => setTimeout(r, 20));
	expect(fs.control.events.filter((e) => e.startsWith('remove crema-history-curves')), 'it waits').toEqual([]);
	release();
	await reset;
	expect(await write).toBe('written');
	const closed = fs.control.events.indexOf(`close ${archiveNameOf('shot:a')}`);
	const removed = fs.control.events.indexOf('remove crema-history-curves');
	expect(closed).toBeGreaterThanOrEqual(0);
	expect(removed).toBeGreaterThan(closed);
	expect(fs.curves().size).toBe(0);
});
