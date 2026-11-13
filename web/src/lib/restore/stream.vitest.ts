import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Zip, ZipDeflate, strToU8, zipSync } from 'fflate';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { buildBackupJsonl, webSingletons } from '#lib/backup/index.js';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import { Verdict } from '#lib/core/crema-core.js';
import { BACKUP_READ_LIMITS, BackupReadError } from '#lib/backup/zip-reader.js';
import { planNow, prepareBackupFile, readRecords, runRestore, shotsTheRunWrites, type RestoreDeps } from './engine';
import { resetTabSafetyForTest } from './tab-safety';

/**
 * The streamed restore (#310 P2w-b) on the real stores: a backup read from a
 * `Blob` a piece at a time, checked before the plan, and holding only the
 * shots the run writes.
 */

const samples = [
	{
		elapsed: 100,
		sample: {
			sampleTime: 10,
			groupPressure: 9,
			groupFlow: 2,
			headTemp: 92,
			mixTemp: 93,
			setMixTemp: 93,
			setHeadTemp: 92,
			setGroupPressure: 9,
			setGroupFlow: 0,
			frameNumber: 1,
			steamTemp: 140
		}
	}
] as StoredShot['record']['samples'];

function shot(id: string, completedAt: number): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt,
		profileName: 'Test',
		profile: null,
		stopReason: null,
		metadata: {
			dose: 18,
			yieldOut: 36,
			beans: null,
			grinderSetting: null,
			notes: '',
			rating: 4,
			tds: null,
			extractionYield: null
		},
		record: { duration: 30_000, samples }
	} as StoredShot;
}

function resetAll(): void {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	getRecipeStore().applyBackup([], null, true);
	getCustomMethodStore().applyBackup([], true);
	localStorage.clear();
}

function deps(more: Partial<RestoreDeps> = {}): RestoreDeps {
	return { singletons: webSingletons().sink, locks: new FakeLocks(), machineWaitMs: 200, progressMs: 5, ...more };
}

/** A backup's JSONL with `n` shots, the newest at `newest`, a minute apart: the export's own bytes. */
async function backupWithShots(n: number, newest: number): Promise<string> {
	resetAll();
	getBeanStore().bulkAdd([{ ...blankBean('b1'), name: 'Bean' }], []);
	// The export writes what history holds; past its cap, lines are cloned from one.
	const template = shot('template', newest);
	getHistoryStore().insertPulled(template);
	const built = (await buildBackupJsonl())!;
	resetAll();
	const lines = built.jsonl.split('\n');
	const at = lines.findIndex((l) => l.includes('"id":"template"'));
	const header = JSON.parse(lines[0]) as Record<string, unknown>;
	const shots = Array.from({ length: n }, (_, i) =>
		lines[at].replace('"id":"template"', `"id":"s${i}"`).replace(`"completedAt":${newest}`, `"completedAt":${newest - i * 60_000}`)
	);
	return [JSON.stringify({ ...header, shotCount: n }), ...lines.slice(1, at), ...shots, ...lines.slice(at + 1)].join('\n');
}

/** A `.crema.zip` the way #386 streams one: `ZipDeflate`, data descriptors. */
function zipOf(jsonl: string, level: 0 | 6 = 6): Uint8Array {
	const parts: Uint8Array[] = [];
	const zip = new Zip((err, chunk) => {
		if (err) throw err;
		parts.push(chunk);
	});
	const entry = new ZipDeflate('backup.jsonl', { level });
	zip.add(entry);
	entry.push(strToU8(jsonl), true);
	zip.end();
	const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
	let o = 0;
	for (const p of parts) {
		out.set(p, o);
		o += p.length;
	}
	return out;
}

/** A `Blob` that says how much any one read took. */
function watched(bytes: Uint8Array): { file: Blob; largestRead: () => number } {
	const real = new Blob([bytes as BlobPart]);
	let largest = 0;
	const file = {
		size: real.size,
		type: '',
		slice(start?: number, end?: number) {
			const s = real.slice(start, end);
			largest = Math.max(largest, s.size);
			return s;
		}
	} as unknown as Blob;
	return { file, largestRead: () => largest };
}

beforeEach(() => {
	resetAll();
	resetTabSafetyForTest();
});
afterEach(() => {
	vi.unstubAllGlobals();
});

describe('a streamed restore', () => {
	it('5,000 shots stream through: reads stay small, and it holds only what history keeps', async () => {
		const jsonl = await backupWithShots(5000, 1_790_000_000_000);
		// Stored deflate blocks, so the entry is megabytes on disk, not a few kilobytes.
		const zip = zipOf(jsonl, 0);
		expect(zip.length).toBeGreaterThan(1024 * 1024);
		const { file, largestRead } = watched(zip);
		const prepared = await prepareBackupFile(file, 'big.crema.zip');
		expect(largestRead()).toBeLessThanOrEqual(64 * 1024 + 30);
		const plan = await planNow(prepared, 'merge', false, new FakeLocks());
		expect(plan.verdict).not.toBe(Verdict.Refused);
		const capacity = getHistoryStore().capacity;
		expect(plan.shots.add.length).toBe(capacity);
		expect(plan.shots.notAdded).toBe(5000 - capacity);
		// What the run reads: the shots it writes, not the backup's 5,000.
		const records = await readRecords(prepared, shotsTheRunWrites(plan));
		expect(records.shots.length).toBe(capacity);
		const out = await runRestore(prepared, 'merge', { expected: plan }, deps());
		expect(out.kind).toBe('done');
		expect(getHistoryStore().all.length).toBe(capacity);
		expect(getHistoryStore().all[0].id).toBe('s0');
	}, 60_000);

	it('300 rows here, all newer than the backup: Replace leaves exactly the backup’s rows', async () => {
		const jsonl = await backupWithShots(20, 1_700_000_000_000);
		const capacity = getHistoryStore().capacity;
		for (let i = 0; i < capacity; i++) getHistoryStore().insertPulled(shot(`here${i}`, 1_800_000_000_000 - i * 60_000));
		expect(getHistoryStore().all.length).toBe(capacity);
		const prepared = await prepareBackupFile(new Blob([zipOf(jsonl) as BlobPart]), 'old.crema.zip');
		const plan = await planNow(prepared, 'wipe', false, new FakeLocks());
		expect(plan.verdict).toBe(Verdict.Confirm);
		expect(await runRestore(prepared, 'wipe', { expected: plan }, deps())).toMatchObject({ kind: 'done' });
		expect(
			getHistoryStore()
				.all.map((s) => s.id)
				.sort()
		).toEqual(Array.from({ length: 20 }, (_, i) => `s${i}`).sort());
	});

	it('Replace over the cap is refused; Merge over it says how many it leaves out', async () => {
		const capacity = getHistoryStore().capacity;
		const jsonl = await backupWithShots(capacity + 5, 1_790_000_000_000);
		const prepared = await prepareBackupFile(new Blob([zipOf(jsonl) as BlobPart]), 'over.crema.zip');
		expect((await planNow(prepared, 'wipe', false, new FakeLocks())).verdict).toBe(Verdict.Refused);
		const merge = await planNow(prepared, 'merge', false, new FakeLocks());
		expect(merge.verdict).toBe(Verdict.Confirm);
		expect(merge.shots.notAdded).toBe(5);
	});

	it('a line past the limit refuses Replace and is reported by Merge; a blank one is just blank', async () => {
		const jsonl = await backupWithShots(3, 1_790_000_000_000);
		const limits = { ...BACKUP_READ_LIMITS, maxLineBytes: 4096 };
		const lines = jsonl.split('\n');
		const long = JSON.stringify({ kind: 'bean', id: 'long', name: 'x'.repeat(5000) });
		const withLong = [...lines.slice(0, 2), long, ...lines.slice(2)].join('\n');
		const prepared = await prepareBackupFile(new Blob([zipOf(withLong) as BlobPart]), 'long.crema.zip', limits);
		const manifest = JSON.parse(prepared.manifestJson) as { badLines: number; problems: { issue: string }[] };
		expect(manifest.badLines).toBe(1);
		expect(manifest.problems[0].issue).toBe('tooLong');
		expect((await planNow(prepared, 'wipe', false, new FakeLocks())).verdict).toBe(Verdict.Refused);
		const merge = await planNow(prepared, 'merge', false, new FakeLocks());
		expect(merge.verdict).toBe(Verdict.Confirm);
		expect(merge.warnings.length).toBeGreaterThan(0);

		const withBlank = [...lines.slice(0, 2), ' '.repeat(5000), ...lines.slice(2)].join('\n');
		const blank = await prepareBackupFile(new Blob([zipOf(withBlank) as BlobPart]), 'blank.crema.zip', limits);
		expect((JSON.parse(blank.manifestJson) as { badLines: number }).badLines).toBe(0);
		expect((await planNow(blank, 'wipe', false, new FakeLocks())).verdict).not.toBe(Verdict.Refused);
	});

	it('a damaged zip is refused before the plan, and nothing is written', async () => {
		const jsonl = await backupWithShots(3, 1_790_000_000_000);
		getHistoryStore().insertPulled(shot('mine', 1_800_000_000_000));
		const good = zipSync({ 'backup.jsonl': [strToU8(jsonl), { level: 0 }] });
		const crc = good.slice();
		// A byte of the stored JSONL, past the local header.
		crc[30 + 'backup.jsonl'.length + 40] ^= 0x01;
		const truncated = good.subarray(0, good.length - 10);
		for (const bytes of [crc, truncated]) {
			await expect(prepareBackupFile(new Blob([bytes as BlobPart]), 'bad.crema.zip')).rejects.toBeInstanceOf(BackupReadError);
		}
		expect(getHistoryStore().all.map((s) => s.id)).toEqual(['mine']);
	});

	it('a photo that fails its checksum is refused before the plan, not at step 3', async () => {
		const jsonl = await backupWithShots(1, 1_790_000_000_000);
		const zip = zipSync({ 'images/b1': [new Uint8Array(4096).fill(7), { level: 0 }], 'backup.jsonl': strToU8(jsonl) });
		zip[30 + 'images/b1'.length + 100] ^= 0x01;
		await expect(prepareBackupFile(new Blob([zip as BlobPart]), 'photo.crema.zip')).rejects.toThrow(/checksum/);
	});

	it('a file that changes between the plan and the run is refused before the first write', async () => {
		// The reviewer's probe: Merge planned 4 shots, the second read held 3, and it said done.
		const four = await backupWithShots(4, 1_790_000_000_000);
		const lines = four.split('\n');
		const three = lines.filter((l) => !l.includes('"id":"s3"')).join('\n');
		for (const [a, b] of [
			[strToU8(four), strToU8(three)],
			[zipOf(four), zipOf(three)]
		]) {
			resetAll();
			let reads = 0;
			// The first full read sees `a`; every read after sees `b`.
			const real = [new Blob([a as BlobPart]), new Blob([b as BlobPart])];
			let current = real[0];
			const file = {
				get size() {
					return current.size;
				},
				type: '',
				slice(start?: number, end?: number) {
					return current.slice(start, end);
				}
			} as unknown as Blob;
			const prepared = await prepareBackupFile(file, 'moving');
			const plan = await planNow(prepared, 'merge', false, new FakeLocks());
			expect(plan.shots.add).toHaveLength(4);
			current = real[1];
			reads += 1;
			await expect(runRestore(prepared, 'merge', { expected: plan }, deps())).rejects.toBeInstanceOf(BackupReadError);
			expect(getHistoryStore().all).toHaveLength(0);
			expect(reads).toBe(1);
		}
	});

	it('a zip whose first bytes are damaged is refused as a zip, not read as text', async () => {
		const jsonl = await backupWithShots(2, 1_790_000_000_000);
		const zip = zipOf(jsonl);
		zip[0] = 0x00;
		await expect(prepareBackupFile(new Blob([zip as BlobPart]), 'head.crema.zip')).rejects.toBeInstanceOf(BackupReadError);
	});

	it('a bare JSONL file streams the same as its zip', async () => {
		const jsonl = await backupWithShots(4, 1_790_000_000_000);
		const fromText = await prepareBackupFile(new Blob([jsonl]), 'plain.jsonl');
		const fromZip = await prepareBackupFile(new Blob([zipOf(jsonl) as BlobPart]), 'plain.crema.zip');
		expect(JSON.parse(fromText.manifestJson)).toEqual(JSON.parse(fromZip.manifestJson));
		const plan = await planNow(fromText, 'merge', false, new FakeLocks());
		expect(await runRestore(fromText, 'merge', { expected: plan }, deps())).toMatchObject({ kind: 'done' });
		expect(getHistoryStore().all.length).toBe(4);
	});
});
