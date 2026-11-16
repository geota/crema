/**
 * #310 R1w, "Done when": backups from both sources of the same history are
 * byte-identical. The same history: `crema-history` as the P3 store keeps it,
 * and `history.db` holding the same rows, in the P3 store's order, with the
 * same `details` (SPEC §2: details stay where P3 put them).
 *
 * The walk reads in `rid` order (SPEC §9), so the lines match when the rows
 * went into `history.db` in the P3 list's order, as here. A restore reads by
 * id, so no reader depends on the order.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { freshStorage } from '#lib/history/idb-test-kit.js';
import type { StoredShot } from '#lib/history/model.js';
import { buildBackupFile, buildBackupJsonl } from '#lib/backup/index.js';
import { memorySink } from '#lib/backup/stream.js';
import { setHistorySource } from './source';
import { historyWasmBuilt } from './sqlite-kit';
import { p3History, sqliteOfP3 } from './cross-kit';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
	vi.useFakeTimers({ toFake: ['Date'] });
	vi.setSystemTime(1_790_000_000_000);
});
afterEach(() => {
	setHistorySource(null);
	vi.useRealTimers();
});

async function fileBytes(): Promise<Uint8Array> {
	const built = (await buildBackupFile('test', { sink: memorySink(), pause: async () => {} }))!;
	const bytes = new Uint8Array(await built.file.arrayBuffer());
	await built.release();
	return bytes;
}

describe.runIf(historyWasmBuilt)('backups from both sources (#310 R1w)', () => {
	it('are byte-identical: the zip and the JSONL, tombstones, pins, curves and gaps alike', async () => {
		const shots = Array.from({ length: 120 }, (_, i) => {
			const s = realShot(i, i % 17 === 5 ? [] : realSamples(6, i % 60));
			if (i % 11 === 3) return { ...s, deletedAt: 1_790_000_000_000 } as StoredShot;
			if (i % 13 === 4) return { ...s, pinned: true } as StoredShot;
			if (i % 19 === 7) return { ...s, profile: null } as StoredShot;
			return s;
		});
		await p3History(shots);

		const p3Jsonl = (await buildBackupJsonl('test'))!;
		const p3File = await fileBytes();

		const { source, port } = await sqliteOfP3(7);
		setHistorySource(source);
		const sqlJsonl = (await buildBackupJsonl('test'))!;
		const sqlFile = await fileBytes();
		// Both through the walk: two walks a backup.
		expect(port.calls.get('exportBegin')).toBe(4);

		expect(sqlJsonl.jsonl).toBe(p3Jsonl.jsonl);
		expect(sqlJsonl.contents).toEqual(p3Jsonl.contents);
		expect(Buffer.from(sqlFile).equals(Buffer.from(p3File))).toBe(true);
		// The history in it, as written: every live shot, with its curve.
		const lines = p3Jsonl.jsonl.split('\n').filter(Boolean).map((l) => JSON.parse(l) as { kind: string });
		expect(lines.filter((l) => l.kind === 'shot')).toHaveLength(shots.filter((s) => s.deletedAt == null).length);
		expect(p3Jsonl.contents.noCurves).toBeGreaterThan(0);
	}, 60_000);
});
