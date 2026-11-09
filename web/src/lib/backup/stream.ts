/**
 * `#lib/backup/stream.js` — a `.crema.zip` backup built one line at a time
 * (#310 P2w, SPEC §7), so a large history never sits in memory as one string
 * or one zip.
 *
 * The core gives the lines: a prelude (header, settings-like lines, roasters,
 * beans), one line per shot, and a tail (methods, recipes, profiles). Joined,
 * they're the bytes of `exportBackupBundle`. Each line goes through fflate's
 * streaming `Zip` / `ZipDeflate` into a sink: a temporary file in the origin
 * private file system where the browser has one, so the result is a
 * disk-backed `File`; else `Blob` parts in memory.
 *
 * The header counts the shots first, so the walk reads every shot twice: once
 * to learn which ones the core can write, once to write them. Rows are the
 * snapshot taken at the start, so a shot saved during the walk is in the next
 * backup. Between batches the walk yields, reports progress, and stops on an
 * abort, leaving nothing behind.
 */

import { Zip, ZipDeflate } from 'fflate';
import type { LockManagerLike } from '#lib/restore/tab-safety.js';
import { backupPrelude, backupShotLine, backupTail } from '#lib/wasm/de1_wasm.js';

const MiB = 1024 * 1024;

/** The same in both shells (SPEC §7): what an export refuses past, and a restore reads. */
export const BACKUP_LIMITS = {
	/** One line of `backup.jsonl`. */
	maxLineBytes: 16 * MiB,
	/** `backup.jsonl` inflated: below zip64, which fflate can't write. */
	maxInflatedBytes: 3 * 1024 * MiB,
	/** The `.crema.zip` file. */
	maxFileBytes: 2 * 1024 * MiB
} as const;

/** Past this, a backup built in memory may not fit: say so. */
export const IN_MEMORY_WARN_BYTES = 512 * MiB;

/** Temporary backup files in the origin private file system start with this. */
export const BACKUP_TEMP_PREFIX = 'crema-backup-tmp-';

/** The Web Lock a temporary backup file holds until its reader is done. */
export const backupTempLock = (name: string): string => `crema-backup-tmp:${name}`;

function navigatorLocks(): LockManagerLike | undefined {
	return typeof navigator === 'undefined'
		? undefined
		: (navigator.locks as unknown as LockManagerLike | undefined);
}

/** The member names inside a `.crema.zip`. */
export const BACKUP_JSONL_MEMBER = 'backup.jsonl';

/** The user cancelled: nothing was kept. */
export class BackupCancelled extends Error {
	override name = 'BackupCancelled';
	constructor() {
		super('Backup cancelled.');
	}
}

/** A backup past {@link BACKUP_LIMITS}: refused, nothing kept. */
export class BackupTooLarge extends Error {
	override name = 'BackupTooLarge';
}

/** Where the zip's bytes go. */
export interface BackupSink {
	write(chunk: Uint8Array): Promise<void>;
	/** The finished file. */
	finish(): Promise<Blob>;
	/** Drop everything written. */
	discard(): Promise<void>;
	/** Remove the finished file once its reader is done; a no-op in memory. */
	release(): Promise<void>;
	/** Whether the bytes are held in memory, not on disk. */
	readonly inMemory: boolean;
}

/** Collects the zip in memory: the fallback without a private file system. */
export function memorySink(): BackupSink {
	let parts: Uint8Array[] = [];
	return {
		inMemory: true,
		async write(chunk) {
			parts.push(chunk);
		},
		async finish() {
			const blob = new Blob(parts as BlobPart[], { type: 'application/zip' });
			parts = [];
			return blob;
		},
		async discard() {
			parts = [];
		},
		async release() {}
	};
}

/** The origin private file system's root, or undefined. */
async function privateRoot(): Promise<FileSystemDirectoryHandle | undefined> {
	try {
		if (typeof navigator === 'undefined' || typeof navigator.storage?.getDirectory !== 'function') return undefined;
		return await navigator.storage.getDirectory();
	} catch {
		return undefined;
	}
}

/**
 * A temporary file in the origin private file system, or {@link memorySink}
 * where there's none or it can't be written. `root` is for tests.
 */
export async function openBackupSink(
	root: () => Promise<FileSystemDirectoryHandle | undefined> = privateRoot,
	locks: LockManagerLike | undefined = navigatorLocks()
): Promise<BackupSink> {
	const dir = await root();
	if (!dir) return memorySink();
	const name = `${BACKUP_TEMP_PREFIX}${Date.now()}-${Math.random().toString(36).slice(2, 8)}.zip`;
	// Held from creation until the reader is done, so no tab's sweep removes
	// the file while it's uploaded or downloaded, however long that takes.
	let unlock = (): void => {};
	const held = new Promise<void>((resolve) => (unlock = resolve));
	await new Promise<void>((granted) => {
		if (!locks) return granted();
		void locks
			.request(backupTempLock(name), { mode: 'exclusive' }, () => {
				granted();
				return held;
			})
			.catch(() => granted());
	});
	let writable: FileSystemWritableFileStream;
	let handle: FileSystemFileHandle;
	try {
		handle = await dir.getFileHandle(name, { create: true });
		writable = await handle.createWritable();
	} catch {
		await dir.removeEntry(name).catch(() => {});
		unlock();
		return memorySink();
	}
	const remove = async (): Promise<void> => {
		await dir.removeEntry(name).catch(() => {});
		unlock();
	};
	return {
		inMemory: false,
		async write(chunk) {
			await writable.write(chunk as BlobPart);
		},
		async finish() {
			await writable.close();
			return handle.getFile();
		},
		async discard() {
			await writable.abort().catch(() => {});
			await remove();
		},
		async release() {
			await remove();
		}
	};
}

/**
 * Remove temporary backup files left in the private file system: at boot, and
 * before a new backup. One younger than `keepMs` may still be downloading.
 */
export async function sweepBackupTemp(
	keepMs = 10 * 60_000,
	now = Date.now(),
	root: () => Promise<FileSystemDirectoryHandle | undefined> = privateRoot,
	locks: LockManagerLike | undefined = navigatorLocks()
): Promise<number> {
	const dir = await root();
	if (!dir) return 0;
	const names: string[] = [];
	try {
		for await (const name of (dir as unknown as { keys(): AsyncIterable<string> }).keys()) {
			if (name.startsWith(BACKUP_TEMP_PREFIX)) names.push(name);
		}
	} catch {
		return 0;
	}
	// A file whose lock is held is still being read, in this tab or another.
	let inUse = new Set<string>();
	try {
		const held = (await locks?.query?.())?.held ?? [];
		inUse = new Set(held.map((l) => l.name ?? ''));
	} catch {
		// No query: the age rule alone.
	}
	let removed = 0;
	for (const name of names) {
		if (inUse.has(backupTempLock(name))) continue;
		const stamp = Number(name.slice(BACKUP_TEMP_PREFIX.length).split('-')[0]);
		if (Number.isFinite(stamp) && now - stamp < keepMs) continue;
		try {
			await dir.removeEntry(name);
			removed += 1;
		} catch {
			// Open for writing, or gone: the next sweep looks again.
		}
	}
	return removed;
}

/** What a backup walk writes. Rows are JSON strings, or null where encoding failed. */
export interface BackupWalkInput {
	/** The export envelope without `shots` (see `exportBackupBundle`). */
	envelope: string;
	/** The shots to write, a snapshot taken before the walk. */
	shots: readonly unknown[];
	/** Bean photos, by bean id, read one at a time. */
	photos?: () => AsyncIterable<[beanId: string, bytes: Uint8Array]>;
	createdAt: number;
	appVersion: string;
	deviceLabel: string;
}

/** How far the walk is. */
export interface BackupProgress {
	shotsWritten: number;
	shotsTotal: number;
}

export interface BackupWalkOptions {
	signal?: AbortSignal;
	onProgress?: (p: BackupProgress) => void;
	sink?: BackupSink;
	/** Shots per batch between yields. */
	batch?: number;
	/** Let the page breathe between batches. */
	pause?: () => Promise<void>;
	limits?: typeof BACKUP_LIMITS;
}

/** What the walk built, and how to let go of it. */
export interface StreamedBackup {
	file: Blob;
	/** Shots written, and those left out because the core couldn't read them. */
	shotsWritten: number;
	shotsOmitted: { index: number; id: string | null }[];
	/** Shots written without their profile, by id. */
	degraded: string[];
	/** Rows of other kinds left out, as the core reports them. */
	omitted: { kind: string; index: number; id: string | null }[];
	/** The bytes are in memory, past {@link IN_MEMORY_WARN_BYTES}. */
	largeInMemory: boolean;
	/** Remove the temporary file, once whatever reads it is done. */
	release(): Promise<void>;
}

interface ShotLineOut {
	line: string;
	withoutProfile: boolean;
	hasCurve: boolean;
}

const nextTick = (): Promise<void> => new Promise((resolve) => setTimeout(resolve, 0));

/** The shot's line, or null when the core can't read it. */
function shotLine(row: unknown): ShotLineOut | null {
	let json: string | undefined;
	try {
		json = typeof row === 'string' ? row : JSON.stringify(row);
	} catch {
		return null;
	}
	if (json == null) return null;
	try {
		return JSON.parse(backupShotLine(json)) as ShotLineOut;
	} catch {
		return null;
	}
}

function shotId(row: unknown): string | null {
	const id = (row as { id?: unknown } | null)?.id;
	return typeof id === 'string' ? id : null;
}

/** Build the zip. Throws {@link BackupCancelled} or {@link BackupTooLarge}, keeping nothing. */
export async function streamBackup(input: BackupWalkInput, opts: BackupWalkOptions = {}): Promise<StreamedBackup> {
	const limits = opts.limits ?? BACKUP_LIMITS;
	const batch = opts.batch ?? 25;
	const pause = opts.pause ?? nextTick;
	const sink = opts.sink ?? (await openBackupSink());
	const total = input.shots.length;
	const enc = new TextEncoder();

	// Zip output, pushed synchronously by fflate and written between pushes.
	let pending: Uint8Array[] = [];
	let zipError: Error | null = null;
	let fileBytes = 0;
	const zip = new Zip((err, chunk) => {
		if (err) zipError = err;
		else if (chunk.length > 0) pending.push(chunk);
	});
	const flush = async (): Promise<void> => {
		if (zipError) throw zipError;
		const out = pending;
		pending = [];
		for (const chunk of out) {
			fileBytes += chunk.length;
			if (fileBytes > limits.maxFileBytes) {
				throw new BackupTooLarge(`The backup would pass ${Math.round(limits.maxFileBytes / MiB)} MB.`);
			}
			await sink.write(chunk);
		}
	};
	const stop = (): void => {
		if (opts.signal?.aborted) throw new BackupCancelled();
	};

	try {
		stop();
		// Pass 1: which shots the core can write, so the header counts them.
		const readable: boolean[] = new Array(total);
		const shotsOmitted: { index: number; id: string | null }[] = [];
		for (let i = 0; i < total; i++) {
			readable[i] = shotLine(input.shots[i]) != null;
			if (!readable[i]) shotsOmitted.push({ index: i, id: shotId(input.shots[i]) });
			if ((i + 1) % batch === 0) {
				await pause();
				stop();
			}
		}
		const written = total - shotsOmitted.length;
		// As the whole-string export: shots of which none could be read are a
		// failed section, not a gap. That file would look like a good backup of
		// nothing, and retention would let it replace the copies that hold them.
		// Thrown as the core throws it, a string, so the caller words it the same.
		if (total > 0 && written === 0) throw `none of the ${total} shot rows could be read`;
		const prelude = JSON.parse(
			backupPrelude(
				input.envelope,
				written,
				shotsOmitted.length,
				input.createdAt,
				input.appVersion,
				input.deviceLabel
			)
		) as { jsonl: string; omitted: { kind: string; index: number; id: string | null }[] };

		const jsonl = new ZipDeflate(BACKUP_JSONL_MEMBER, { level: 6 });
		zip.add(jsonl);
		let inflated = 0;
		const tooLong = (): BackupTooLarge =>
			new BackupTooLarge(
				`A record is larger than ${Math.round(limits.maxLineBytes / MiB)} MB, which a backup can't hold.`
			);
		const push = async (text: string, final = false, oneLine = false): Promise<void> => {
			const bytes = enc.encode(text);
			// Each line within the limit the restore reads with: a shot line is
			// one line and its newline, and the prelude and tail are checked line
			// by line.
			if (oneLine) {
				if (bytes.length - 1 > limits.maxLineBytes) throw tooLong();
			} else if (bytes.length > limits.maxLineBytes) {
				for (const line of text.split('\n')) {
					if (enc.encode(line).length > limits.maxLineBytes) throw tooLong();
				}
			}
			inflated += bytes.length;
			if (inflated > limits.maxInflatedBytes) {
				throw new BackupTooLarge(
					`The backup would pass ${Math.round(limits.maxInflatedBytes / MiB)} MB unzipped.`
				);
			}
			jsonl.push(bytes, final);
			await flush();
		};
		await push(prelude.jsonl);

		// Pass 2: the lines, in order.
		const degraded: string[] = [];
		let shotsWritten = 0;
		for (let i = 0; i < total; i++) {
			if (!readable[i]) continue;
			const out = shotLine(input.shots[i]);
			if (out == null) {
				// Readable a moment ago: the snapshot's rows don't change.
				throw new Error('A shot changed while it was being backed up. Try again.');
			}
			if (out.withoutProfile) degraded.push(shotId(input.shots[i]) ?? '');
			await push(out.line, false, true);
			shotsWritten += 1;
			if (shotsWritten % batch === 0) {
				opts.onProgress?.({ shotsWritten, shotsTotal: written });
				await pause();
				stop();
			}
		}
		await push(backupTail(input.envelope), true);
		opts.onProgress?.({ shotsWritten, shotsTotal: written });

		for await (const [beanId, bytes] of input.photos?.() ?? []) {
			stop();
			// Deflated, not stored: a stored entry with a data descriptor, which a
			// streamed entry always has, is refused by Android's ZipInputStream.
			const photo = new ZipDeflate(`images/${beanId}`, { level: 1 });
			zip.add(photo);
			photo.push(bytes, true);
			await flush();
		}
		zip.end();
		await flush();
		const file = await sink.finish();
		return {
			file,
			shotsWritten,
			shotsOmitted,
			degraded,
			omitted: prelude.omitted,
			largeInMemory: sink.inMemory && file.size > IN_MEMORY_WARN_BYTES,
			release: () => sink.release()
		};
	} catch (e) {
		zip.terminate();
		await sink.discard();
		throw e;
	}
}
