/**
 * The backup built one line at a time (#310 P2w): the same bytes as the
 * whole-string path, a sink that sees the zip as the walk goes, cancel and
 * limits that leave nothing, and the temporary files swept.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { strFromU8, unzipSync } from 'fflate';
import { initTestWasm } from '#lib/testing/test-init.js';
import { exportBackupBundle } from '#lib/wasm/de1_wasm.js';
import { getBeanStore, blankBean, blankRoaster } from '#lib/bean/index.js';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { blankProfile } from '#lib/profiles/model.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { buildBackupFile, buildBackupJsonl, driveBackupFailure } from './index';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
	BACKUP_JSONL_MEMBER,
	BACKUP_LIMITS,
	BACKUP_TEMP_PREFIX,
	BackupCancelled,
	BackupTooLarge,
	backupTempLock,
	memorySink,
	openBackupSink,
	streamBackup,
	sweepBackupTemp,
	type BackupSink
} from './stream';

const NOW = 1_790_000_000_000;

const curve = Array.from({ length: 180 }, (_, i) => ({
	elapsed: i * 227,
	sample: {
		sampleTime: i,
		groupPressure: 9 * Math.sin(i / 60),
		groupFlow: 2,
		headTemp: 92,
		mixTemp: 93,
		setMixTemp: 93,
		setHeadTemp: 92,
		setGroupPressure: 9,
		setGroupFlow: 0,
		frameNumber: 1,
		steamTemp: 140
	},
	scaleWeight: i / 5
}));

function shot(i: number): StoredShot {
	return {
		formatVersion: 3,
		id: `shot-${i}`,
		completedAt: NOW - i * 60_000,
		profileName: 'Test Profile',
		profile: null,
		stopReason: null,
		metadata: { dose: 18, yieldOut: 36, notes: `shot ${i}`, rating: 4 },
		record: { duration: 40_000, samples: curve }
	} as unknown as StoredShot;
}

async function unzipJsonl(file: Blob): Promise<string> {
	const entries = unzipSync(new Uint8Array(await file.arrayBuffer()));
	return strFromU8(entries[BACKUP_JSONL_MEMBER]);
}

/** A sink that keeps only counts, so a long walk's memory is the walk's own. */
function countingSink(): BackupSink & { bytes: number; writes: number; largest: number; finished: boolean; discarded: boolean } {
	const s = {
		inMemory: true,
		bytes: 0,
		writes: 0,
		largest: 0,
		finished: false,
		discarded: false,
		async write(chunk: Uint8Array) {
			s.bytes += chunk.length;
			s.writes += 1;
			s.largest = Math.max(s.largest, chunk.length);
		},
		async finish() {
			s.finished = true;
			return new Blob([]);
		},
		async discard() {
			s.discarded = true;
		},
		async release() {}
	};
	return s;
}

const input = (shots: readonly unknown[], envelope: Record<string, unknown> = {}) => ({
	envelope: JSON.stringify(envelope),
	shots,
	createdAt: NOW,
	appVersion: 'test',
	deviceLabel: 'Crema (web)'
});

beforeAll(async () => {
	await initTestWasm();
});

describe('the same bytes as the whole-string path', () => {
	beforeEach(() => {
		getBeanStore().clearAll();
		getHistoryStore().clearAllShots();
		getProfileStore().clearAllCustom();
		getSettingsStore().reset();
		getRecipeStore().applyBackup([], null, true);
		getCustomMethodStore().applyBackup([], true);
		localStorage.clear();
		vi.useFakeTimers({ toFake: ['Date'] });
		vi.setSystemTime(NOW);
	});
	afterEach(() => vi.useRealTimers());

	it('300 shots, beans, a roaster and a profile, from the real stores', async () => {
		getBeanStore().bulkAdd(
			[{ ...blankBean('bean-1'), name: 'Ethiopia', roasterId: 'roaster-1' }],
			[blankRoaster('Test Roaster', 'roaster-1')]
		);
		for (let i = 0; i < 300; i++) getHistoryStore().insertPulled(shot(i));
		getProfileStore().save({ ...blankProfile(), name: 'My Custom' });

		const whole = (await buildBackupJsonl('test'))!;
		const streamed = (await buildBackupFile('test'))!;
		expect(await unzipJsonl(streamed.file)).toBe(whole.jsonl);
		expect(streamed.contents.shots).toBe(300);
		expect(streamed.contents).toMatchObject({
			profiles: whole.contents.profiles,
			beans: whole.contents.beans,
			roasters: whole.contents.roasters,
			shots: whole.contents.shots,
			noCurves: whole.contents.noCurves
		});
	});

	it('rows the core can’t read are left out and counted, as the whole path does', async () => {
		const shots = [shot(0), { id: 'broken', record: 'nope' }, shot(1), null];
		const envelope = { beans: [JSON.stringify(blankBean('ok')), '{"id":7}'], settings: { common: {} } };
		const whole = JSON.parse(
			exportBackupBundle(JSON.stringify({ ...envelope, shots: shots.map((s) => JSON.stringify(s)) }), NOW, 'test', 'Crema (web)')
		) as { jsonl: string };
		const built = await streamBackup(input(shots, envelope));
		expect(await unzipJsonl(built.file)).toBe(whole.jsonl);
		expect(built.shotsWritten).toBe(2);
		expect(built.shotsOmitted.map((o) => o.id)).toEqual(['broken', null]);
		expect(built.omitted.map((o) => o.kind)).toEqual(['bean']);
	});
});

describe('a section with no row that reads', () => {
	it('fails the backup, as the whole-string path does', async () => {
		const sink = countingSink();
		await expect(streamBackup(input([{ id: 'a', record: 'x' }, null]), { sink })).rejects.toThrow(
			'none of the 2 shot rows could be read'
		);
		expect(sink.discarded).toBe(true);
		expect(() =>
			exportBackupBundle(JSON.stringify({ shots: ['{"id":"a","record":"x"}', null] }), NOW, 't', 'd')
		).toThrow('none of the 2 shot rows could be read');
	});
});

describe('a long walk', () => {
	it('5,000 shots stream through the sink as they go, in small writes', async () => {
		const shots = Array.from({ length: 5_000 }, (_, i) => shot(i));
		const sink = countingSink();
		let bytesAtHalf = -1;
		const built = await streamBackup(input(shots), {
			sink,
			batch: 250,
			pause: async () => {},
			onProgress: (p) => {
				if (bytesAtHalf < 0 && p.shotsWritten >= 2_500) bytesAtHalf = sink.bytes;
			}
		});
		expect(built.shotsWritten).toBe(5_000);
		expect(sink.finished).toBe(true);
		expect(bytesAtHalf, 'the zip reached the sink before the walk ended').toBeGreaterThan(sink.bytes / 4);
		// No write holds more than fflate's own chunking, whatever the shot count.
		expect(sink.largest).toBeLessThan(1024 * 1024);
		expect(sink.writes).toBeGreaterThan(100);
	}, 120_000);

	it('reports progress and yields between batches', async () => {
		const seen: number[] = [];
		let pauses = 0;
		await streamBackup(input(Array.from({ length: 60 }, (_, i) => shot(i))), {
			sink: countingSink(),
			batch: 20,
			pause: async () => {
				pauses += 1;
			},
			onProgress: (p) => seen.push(p.shotsWritten)
		});
		expect(seen).toEqual([20, 40, 60, 60]);
		expect(pauses).toBeGreaterThanOrEqual(6);
	});
});

describe('cancel and limits leave nothing', () => {
	const shots = Array.from({ length: 100 }, (_, i) => shot(i));

	it('a cancel mid-walk discards what was written', async () => {
		const sink = countingSink();
		const run = new AbortController();
		await expect(
			streamBackup(input(shots), {
				sink,
				signal: run.signal,
				batch: 10,
				pause: async () => {},
				onProgress: (p) => {
					if (p.shotsWritten >= 30) run.abort();
				}
			})
		).rejects.toBeInstanceOf(BackupCancelled);
		expect(sink.discarded).toBe(true);
		expect(sink.finished).toBe(false);
	});

	it('a shot line past the limit refuses the backup', async () => {
		const sink = countingSink();
		await expect(
			streamBackup(input(shots), { sink, limits: { ...BACKUP_LIMITS, maxLineBytes: 1024 } })
		).rejects.toBeInstanceOf(BackupTooLarge);
		expect(sink.discarded).toBe(true);
	});

	it('a line of the prelude or the tail past the limit refuses it too (#386 review)', async () => {
		// A shot well inside the limit, so only the prelude or tail line is long.
		const small = { ...shot(0), record: { duration: 40_000, samples: [] } };
		await expect(
			streamBackup(input([small]), { sink: countingSink(), limits: { ...BACKUP_LIMITS, maxLineBytes: 2048 } })
		).resolves.toBeDefined();
		for (const envelope of [
			{ settings: { common: {}, notes: 'x'.repeat(4096) } },
			{ profiles: [JSON.stringify({ id: 'p1', name: 'x'.repeat(4096) })] }
		]) {
			const sink = countingSink();
			await expect(
				streamBackup(input([small], envelope), { sink, limits: { ...BACKUP_LIMITS, maxLineBytes: 2048 } })
			).rejects.toBeInstanceOf(BackupTooLarge);
			expect(sink.discarded).toBe(true);
		}
	});

	it('past the unzipped or the file limit too', async () => {
		for (const limits of [
			{ ...BACKUP_LIMITS, maxInflatedBytes: 200_000 },
			{ ...BACKUP_LIMITS, maxFileBytes: 20_000 }
		]) {
			const sink = countingSink();
			await expect(streamBackup(input(shots), { sink, limits })).rejects.toBeInstanceOf(BackupTooLarge);
			expect(sink.discarded).toBe(true);
			expect(sink.finished).toBe(false);
		}
	});
});

/** A private file system with one directory, enough for the sink and the sweep. */
function fakeRoot() {
	const files = new Map<string, Uint8Array[]>();
	const closed = new Set<string>();
	const root = {
		files,
		async getFileHandle(name: string, opts?: { create?: boolean }) {
			if (!files.has(name)) {
				if (!opts?.create) throw new DOMException('gone', 'NotFoundError');
				files.set(name, []);
			}
			return {
				async createWritable() {
					return {
						async write(chunk: Uint8Array) {
							files.get(name)!.push(new Uint8Array(chunk));
						},
						async close() {
							closed.add(name);
						},
						async abort() {}
					};
				},
				async getFile() {
					return new File(files.get(name)! as BlobPart[], name);
				}
			};
		},
		async removeEntry(name: string) {
			if (!files.delete(name)) throw new DOMException('gone', 'NotFoundError');
		},
		async *keys() {
			yield* files.keys();
		}
	};
	return root;
}

describe('the temporary file', () => {
	it('a backup lands in the private file system, and goes when released', async () => {
		const root = fakeRoot();
		const sink = await openBackupSink(async () => root as unknown as FileSystemDirectoryHandle);
		expect(sink.inMemory).toBe(false);
		const built = await streamBackup(input([shot(0)]), { sink });
		expect([...root.files.keys()]).toHaveLength(1);
		expect([...root.files.keys()][0]).toMatch(new RegExp(`^${BACKUP_TEMP_PREFIX}`));
		expect(await unzipJsonl(built.file)).toContain('"kind":"shot"');
		expect(built.largeInMemory).toBe(false);
		await built.release();
		expect(root.files.size).toBe(0);
	});

	it('a cancelled one goes at once', async () => {
		const root = fakeRoot();
		const sink = await openBackupSink(async () => root as unknown as FileSystemDirectoryHandle);
		const run = new AbortController();
		run.abort();
		await expect(streamBackup(input([shot(0)]), { sink, signal: run.signal })).rejects.toBeInstanceOf(
			BackupCancelled
		);
		expect(root.files.size).toBe(0);
	});

	it('without a private file system, it’s built in memory', async () => {
		const sink = await openBackupSink(async () => undefined);
		expect(sink.inMemory).toBe(true);
		const built = await streamBackup(input([shot(0)]), { sink });
		expect(await unzipJsonl(built.file)).toContain('"kind":"shot"');
	});

	it('the sweep removes old temporary files, and leaves new and other ones', async () => {
		const root = fakeRoot();
		root.files.set(`${BACKUP_TEMP_PREFIX}${NOW - 3_600_000}-aaaa.zip`, []);
		root.files.set(`${BACKUP_TEMP_PREFIX}${NOW - 60_000}-bbbb.zip`, []);
		root.files.set('crema-history-snapshot', []);
		const removed = await sweepBackupTemp(10 * 60_000, NOW, async () => root as unknown as FileSystemDirectoryHandle);
		expect(removed).toBe(1);
		expect([...root.files.keys()].sort()).toEqual([`${BACKUP_TEMP_PREFIX}${NOW - 60_000}-bbbb.zip`, 'crema-history-snapshot']);
	});

	it('bean photos go in as their own entries, after the lines', async () => {
		const built = await streamBackup({
			...input([shot(0)]),
			photos: async function* () {
				yield ['bean-1', new Uint8Array([7, 8, 9])] as [string, Uint8Array];
			}
		});
		const entries = unzipSync(new Uint8Array(await built.file.arrayBuffer()));
		expect([...entries['images/bean-1']]).toEqual([7, 8, 9]);
		expect(Object.keys(entries)).toEqual([BACKUP_JSONL_MEMBER, 'images/bean-1']);
	});

	it('a file still being read keeps its lock, and the sweep leaves it however old (#386 review)', async () => {
		const root = fakeRoot();
		const locks = new FakeLocks();
		const sink = await openBackupSink(async () => root as unknown as FileSystemDirectoryHandle, locks);
		const built = await streamBackup(input([shot(0)]), { sink });
		const name = [...root.files.keys()][0];
		expect((await locks.query()).held.map((l) => l.name)).toEqual([backupTempLock(name)]);
		root.files.set(`${BACKUP_TEMP_PREFIX}${NOW - 3_600_000}-old.zip`, []);
		const later = Date.now() + 3_600_000;
		const at = async () => root as unknown as FileSystemDirectoryHandle;
		expect(await sweepBackupTemp(10 * 60_000, later, at, locks)).toBe(1);
		expect(root.files.has(name), 'an upload an hour long keeps its file').toBe(true);
		await built.release();
		expect(root.files.has(name)).toBe(false);
		expect((await locks.query()).held).toEqual([]);
	});

	it('one file the sweep can’t remove doesn’t stop the rest', async () => {
		const root = fakeRoot();
		root.files.set(`${BACKUP_TEMP_PREFIX}1-a.zip`, []);
		root.files.set(`${BACKUP_TEMP_PREFIX}2-b.zip`, []);
		const remove = root.removeEntry.bind(root);
		root.removeEntry = async (name: string) => {
			if (name.endsWith('-a.zip')) throw new DOMException('open', 'NoModificationAllowedError');
			return remove(name);
		};
		expect(await sweepBackupTemp(0, NOW, async () => root as unknown as FileSystemDirectoryHandle)).toBe(1);
		expect([...root.files.keys()]).toEqual([`${BACKUP_TEMP_PREFIX}1-a.zip`]);
	});

	it('a memory sink holds its parts until finished', async () => {
		const sink = memorySink();
		await sink.write(new Uint8Array([1, 2, 3]));
		expect((await sink.finish()).size).toBe(3);
	});
});

/** Each local file header's compression method, in order. */
function methods(zip: Uint8Array): number[] {
	const out: number[] = [];
	for (let i = 0; i + 30 <= zip.length; i++) {
		if (zip[i] === 0x50 && zip[i + 1] === 0x4b && zip[i + 2] === 0x03 && zip[i + 3] === 0x04) {
			out.push(zip[i + 8] | (zip[i + 9] << 8));
			const nameLen = zip[i + 26] | (zip[i + 27] << 8);
			i += 29 + nameLen;
		}
	}
	return out;
}

/** The cross-shell fixture Android's reader is tested on (`WebStreamedBackupTest`). */
const FIXTURE = resolve(process.cwd(), '../android/app/src/test/resources/backup/web-streamed-photo.crema.zip');

describe('a streamed zip Android can read (#386 review)', () => {
	const build = () =>
		streamBackup({
			...input([shot(0)], { beans: [JSON.stringify({ ...blankBean('bean-1'), name: 'Kenya' })] }),
			photos: async function* () {
				// Random bytes, as a JPEG barely compresses.
				yield ['bean-1', Uint8Array.from({ length: 4096 }, (_, i) => (i * 7919 + 13) % 251)] as [
					string,
					Uint8Array
				];
			}
		});

	it('every entry is deflated: Java refuses a stored entry with a data descriptor', async () => {
		const zip = new Uint8Array(await (await build()).file.arrayBuffer());
		expect(methods(zip)).toEqual([8, 8]);
	});

	it('the committed fixture is one this build writes', async () => {
		const zip = new Uint8Array(await (await build()).file.arrayBuffer());
		if (process.env.CREMA_WRITE_FIXTURE === '1') writeFileSync(FIXTURE, zip);
		const fixture = new Uint8Array(readFileSync(FIXTURE));
		expect(methods(fixture)).toEqual([8, 8]);
		const entries = unzipSync(fixture);
		expect(Object.keys(entries)).toEqual([BACKUP_JSONL_MEMBER, 'images/bean-1']);
		expect(entries['images/bean-1'].length).toBe(4096);
	});
});

describe('a Drive backup that didn’t finish', () => {
	it('a cancel during the upload doesn’t promise nothing went up (#386 review)', () => {
		const abort = new DOMException('aborted', 'AbortError');
		expect(driveBackupFailure(abort, true)).toMatch(/may have it anyway/);
		expect(driveBackupFailure(new BackupCancelled(), false)).toBe('Backup cancelled: nothing was uploaded.');
		expect(driveBackupFailure(new Error('no network'), true)).toBe('Drive backup failed: no network');
	});
});
