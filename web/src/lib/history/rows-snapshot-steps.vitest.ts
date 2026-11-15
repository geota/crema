/**
 * #420 FC3-N2: the daily copy writes, checks and reads back in small
 * yielding steps, so a big history makes no long task: at most
 * SNAPSHOT_CHUNK rows, or 64 KB inflated, between yields.
 */
import { expect, it } from 'vitest';
import { realShot } from '#lib/testing/real-shots.js';
import { summarize } from './summary';
import { rowOf } from './history-db';
import {
	SNAPSHOT_CHUNK,
	readRowsSnapshot,
	resetSnapshotStepsForTest,
	snapshotStepsForTest,
	writeRowsSnapshot,
	type SnapshotRow
} from './rows-snapshot';
import { memoryFs } from './archive-test-kit';
import { initTestWasm } from '#lib/testing/test-init.js';

it('2,000 rows are written, verified, pruned and read back in steps of at most 100 rows and 64 KB', async () => {
	await initTestWasm();
	const fs = memoryFs();
	const one = realShot(1, []);
	const summary = summarize(one);
	// Notes that don't compress away, so the file is well past one inflate step.
	const noise = (i: number) => Array.from({ length: 40 }, (_, k) => ((i * 7919 + k * 104729) % 65536).toString(36)).join(' ');
	const rows: SnapshotRow[] = Array.from({ length: 2_000 }, (_, i) => ({
		...rowOf({ ...one, id: `shot:${i}`, metadata: { ...one.metadata, notes: noise(i) } }, summary),
		profile: one.profile
	}));
	resetSnapshotStepsForTest();
	expect(await writeRowsSnapshot(rows, Date.now(), fs.fsRoot, null)).toBe(true);
	const back = await readRowsSnapshot(fs.fsRoot);
	expect(back?.rows).toHaveLength(2_000);
	const { maxRows, maxBytes } = snapshotStepsForTest();
	expect(SNAPSHOT_CHUNK).toBeLessThanOrEqual(100);
	expect(maxRows).toBeLessThanOrEqual(SNAPSHOT_CHUNK);
	expect(maxBytes).toBeLessThanOrEqual(64 * 1024);
	expect(maxRows).toBeGreaterThan(0);
	expect(fs.copyBytes(), 'past one inflate step').toBeGreaterThan(2 * 64 * 1024);
});
