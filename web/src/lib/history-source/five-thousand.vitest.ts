/**
 * #310 R1, both shells: a 5,000-shot backup restored web to Android and back
 * by fixture keeps every row and sample count. The fixtures live in
 * `core/de1-domain/tests/fixtures/backup/`: `web-5000.crema.zip` is written
 * here from {@link seedShots}, `android-5000.crema.zip` by Android (R1a).
 * Each shell restores both, on each of its stores, and backs the history up
 * again: the same ids, each with its sample count.
 *
 * Regenerate the web one with
 * `CREMA_REGEN_BACKUP_FIXTURES=1 pnpm vitest run src/lib/history-source/five-thousand.vitest.ts`.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { unzipSync, strFromU8 } from 'fflate';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples } from '#lib/testing/real-shots.js';
import { HistoryDb } from '#lib/history/history-db.js';
import { freshStorage } from '#lib/history/idb-test-kit.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import { summarize } from '#lib/history/summary.js';
import { buildBackupFile, restoreBackupData } from '#lib/backup/index.js';
import { memorySink } from '#lib/backup/stream.js';
import { planNow, prepareBackup } from '#lib/restore/engine.js';
import { setHistorySource } from './source';
import { SqliteSource } from './sqlite';
import { idbDetails } from './details';
import { p3History } from './cross-kit';
import { directPort, freshFile, historyWasmBuilt, makeTruth, openTruth } from './sqlite-kit';

const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const WEB = resolve(FIXTURES, 'web-5000.crema.zip');
const ANDROID = resolve(FIXTURES, 'android-5000.crema.zip');
const N = 5_000;

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
	localStorage.clear();
	vi.useFakeTimers({ toFake: ['Date'] });
	vi.setSystemTime(1_790_000_000_000);
});
afterEach(() => {
	setHistorySource(null);
	vi.useRealTimers();
});

/** Shot `i` of the fixture: 0 to 4 real samples; every 97th pinned. */
function seedShot(i: number): StoredShot {
	return {
		formatVersion: 3,
		id: `shot:5000-${String(i).padStart(5, '0')}`,
		completedAt: 1_780_000_000_000 + i * 60_000,
		profileName: 'Fixture',
		profile: null,
		stopReason: null,
		metadata: { dose: 18, yieldOut: 36, beans: null, grinderSetting: '2.5', notes: `shot ${i}`, rating: i % 6, tds: null, extractionYield: null },
		record: { duration: 30_000, samples: i % 5 === 0 ? [] : realSamples(i % 5, i % 60) },
		...(i % 97 === 0 ? { pinned: true } : {})
	} as StoredShot;
}

const seedShots = (): StoredShot[] => Array.from({ length: N }, (_, i) => seedShot(i));

/** A backup's shots: each id with its sample count. */
function shotsOf(zip: Uint8Array): Map<string, number> {
	const jsonl = strFromU8(unzipSync(zip)['backup.jsonl']);
	const out = new Map<string, number>();
	for (const line of jsonl.split('\n')) {
		if (!line.includes('"kind":"shot"')) continue;
		const s = JSON.parse(line) as { kind: string; id: string; record: { samples: unknown[] } };
		if (s.kind === 'shot') out.set(s.id, s.record.samples.length);
	}
	return out;
}

async function backupBytes(): Promise<Uint8Array> {
	const built = (await buildBackupFile('fixture', { sink: memorySink(), pause: async () => {} }))!;
	const bytes = new Uint8Array(await built.file.arrayBuffer());
	await built.release();
	return bytes;
}

/** An empty `history.db` behind the seam, over `crema-history`'s details. */
async function emptySqlite(): Promise<void> {
	await getHistoryStore().whenReady();
	const made = new HistoryDb({ expectExisting: () => false });
	await made.open();
	made.close();
	const file = freshFile();
	await makeTruth(file);
	const port = directPort(await openTruth(file));
	setHistorySource(
		new SqliteSource({ db: port, details: idbDetails(new HistoryDb({ expectExisting: () => true })), summarize })
	);
}

async function restoreWipe(bytes: Uint8Array): Promise<void> {
	const plan = await planNow(prepareBackup(bytes, 'fixture'), 'wipe');
	const { outcome } = await restoreBackupData(bytes, 'wipe', { name: 'fixture', confirmed: plan });
	expect(outcome.kind).toBe('done');
}

it.runIf(process.env.CREMA_REGEN_BACKUP_FIXTURES === '1')('regenerates the web 5,000-shot fixture', async () => {
	await p3History(seedShots());
	writeFileSync(WEB, await backupBytes());
}, 120_000);

describe.runIf(historyWasmBuilt)('a 5,000-shot backup across shells (#310 R1)', () => {
	const cases: [string, string][] = [
		['web', WEB],
		['android', ANDROID]
	];
	for (const [shell, path] of cases) {
		for (const store of ['p3', 'sqlite'] as const) {
			it.runIf(existsSync(path))(`${shell}'s, restored on the ${store} store and backed up again, keeps every row and sample count`, async () => {
				const bytes = new Uint8Array(readFileSync(path));
				const want = shotsOf(bytes);
				expect(want.size).toBe(N);
				if (store === 'sqlite') await emptySqlite();
				await restoreWipe(bytes);
				const again = shotsOf(await backupBytes());
				expect(again.size).toBe(N);
				expect(again).toEqual(want);
			}, 120_000);
		}
	}

	it('the web fixture is the seed’s: every id, each with its count', () => {
		const want = new Map(seedShots().map((s) => [s.id, s.record.samples.length]));
		expect(shotsOf(new Uint8Array(readFileSync(WEB)))).toEqual(want);
	});
});
