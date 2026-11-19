/**
 * #420 FC3-N2: the daily copy writes, checks and reads back in small
 * yielding steps, so a big history makes no long task: at most
 * SNAPSHOT_CHUNK rows, or about 64 KB inflated, between yields. #421: the
 * 64 KB is counted as it comes out of the gzip, not as it goes in.
 */
import { expect, it } from 'vitest';
import { realShot } from '#lib/testing/real-shots.js';
import { summarize } from './summary';
import { rowOf } from './history-db';
import { gzipSync } from 'fflate';
import {
	INFLATE_SLICE,
	INFLATE_STEP_MAX,
	SNAPSHOT_CHUNK,
	inflateLines,
	readRowsSnapshot,
	resetSnapshotStepsForTest,
	snapshotStepsForTest,
	writeRowsSnapshot,
	type SnapshotRow
} from './rows-snapshot';
import { memoryFs } from './archive-test-kit';
import { initTestWasm } from '#lib/testing/test-init.js';

/** 64 KB, and the one slice that takes a step past it: 128 KB, which a step's worst case stays under. */
const STEP_BYTES = 128 * 1024;

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
	expect(maxBytes).toBeLessThanOrEqual(STEP_BYTES);
	expect(maxRows).toBeGreaterThan(0);
	expect(fs.copyBytes(), 'past one inflate step').toBeGreaterThan(2 * 64 * 1024);
});

it('a copy that inflates 100 times over is read back in steps of about 64 KB out, not in (#421)', async () => {
	await initTestWasm();
	const fs = memoryFs();
	const one = realShot(1, []);
	const summary = summarize(one);
	// One profile and one note for every row, as a real history mostly has.
	const rows: SnapshotRow[] = Array.from({ length: 2_000 }, (_, i) => ({ ...rowOf({ ...one, id: `shot:${i}` }, summary), profile: one.profile }));
	expect(await writeRowsSnapshot(rows, Date.now(), fs.fsRoot, null)).toBe(true);
	const text = rows.reduce((n, r) => n + JSON.stringify(r).length + 1, 0);
	expect(text / fs.copyBytes(), 'inflates many times over').toBeGreaterThan(50);
	expect(fs.copyBytes(), 'past one slice').toBeGreaterThan(4 * 512);
	resetSnapshotStepsForTest();
	const back = await readRowsSnapshot(fs.fsRoot);
	expect(back?.rows).toStrictEqual(JSON.parse(JSON.stringify(rows)));
	const { maxBytes } = snapshotStepsForTest();
	expect(maxBytes).toBeGreaterThan(0);
	expect(maxBytes).toBeLessThanOrEqual(STEP_BYTES);
});

it('a step is bounded whatever the data: deflate’s best ratio, and every slice boundary (CI flake, 137,972 bytes)', async () => {
	expect(INFLATE_STEP_MAX).toBeLessThanOrEqual(STEP_BYTES);
	// A run of one byte inflates 1032 times over: the worst a slice can give.
	const worst = gzipSync(new Uint8Array(4_000_000).fill(0x20), { level: 9 });
	// Text that inflates hundreds of times over, at every offset a slice can start from.
	const text = new TextEncoder().encode(`${'{"profile":"turbo","notes":"x"}\n'.repeat(60_000)}`);
	for (const lead of [0, 1, 7, 31, INFLATE_SLICE - 1]) {
		for (const data of [worst, gzipSync(new Uint8Array([...new Uint8Array(lead).fill(0x41), ...text]), { level: 9 })]) {
			resetSnapshotStepsForTest();
			await inflateLines(data);
			const { maxBytes } = snapshotStepsForTest();
			expect(maxBytes).toBeGreaterThan(0);
			expect(maxBytes).toBeLessThanOrEqual(INFLATE_STEP_MAX);
		}
	}
});
