/**
 * `#lib/backup/zip-reader.js` — read a backup from a `Blob` a piece at a time
 * (#310 P2w-b, SPEC §7 "Restore, web"), so a restore holds one line, not the
 * file.
 *
 * **Not fflate's streaming `Unzip`.** It finds an entry's end by scanning for
 * the `PK\x03\x04`, `PK\x07\x08` and `PK\x01\x02` signatures, so an entry
 * written with a data descriptor (Android's `ZipOutputStream`, fflate's
 * `ZipDeflate`) whose compressed bytes happen to hold one is cut short. This
 * reader takes each entry's sizes, offset and CRC from the central directory
 * instead, skips the local header, inflates the entry's exact bytes with
 * fflate `Inflate`, and checks the CRC and the size as it goes.
 *
 * **Limits** (the same in both shells): the file, the entry count, the bytes
 * each entry and all of them inflate to, checked from the central directory
 * before a byte is inflated, and again while inflating, so a zip that lies
 * about its sizes stops at the size it declared.
 */

import { Inflate } from 'fflate';

const KiB = 1024;
const MiB = 1024 * KiB;
const GiB = 1024 * MiB;

export interface BackupReadLimits {
	/** Largest backup file read. */
	maxFileBytes: number;
	/** Most bytes the entries read may inflate to, together. */
	maxInflatedBytes: number;
	/** Largest line, `0x0A` not included. */
	maxLineBytes: number;
	/** Most entries a zip may hold. */
	maxEntries: number;
	/** Largest photo entry. */
	maxPhotoBytes: number;
}

export const BACKUP_READ_LIMITS: BackupReadLimits = {
	maxFileBytes: 2 * GiB,
	maxInflatedBytes: 3 * GiB,
	maxLineBytes: 16 * MiB,
	maxEntries: 10_000,
	maxPhotoBytes: 100 * MiB
};

/** A backup that won't read: damaged, or past a limit. Nothing was written. */
export class BackupReadError extends Error {
	override name = 'BackupReadError';
}

function mb(bytes: number): string {
	return `${Math.ceil(bytes / MiB)} MB`;
}

// ── CRC-32 (fflate doesn't export its own) ────────────────────────────────

const CRC_TABLE = (() => {
	const t = new Int32Array(256);
	for (let n = 0; n < 256; n++) {
		let c = n;
		for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
		t[n] = c;
	}
	return t;
})();

/** A running CRC-32: start at `0`, feed every piece, compare the last value. */
export function crc32(crc: number, bytes: Uint8Array): number {
	let c = ~crc;
	for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
	return ~c >>> 0;
}

// ── The central directory ─────────────────────────────────────────────────

/** One entry, as the central directory describes it. */
export interface ZipEntry {
	name: string;
	/** 0 stored, 8 deflate. */
	method: number;
	crc: number;
	compressedSize: number;
	size: number;
	/** Where its local header starts. */
	localOffset: number;
}

const EOCD = 0x06054b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;
const EOCD_BYTES = 22;
const MAX_COMMENT = 0xffff;

async function bytesAt(file: Blob, start: number, end: number): Promise<Uint8Array> {
	return new Uint8Array(await file.slice(start, end).arrayBuffer());
}

function damaged(why: string): BackupReadError {
	return new BackupReadError(`This backup is damaged (${why}). Nothing was changed.`);
}

/** Whether `bytes` start like a zip (a local header). */
export function isZipStart(bytes: Uint8Array): boolean {
	return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
}

/** The zip's entries, from its central directory, within `limits`. */
export async function readCentralDirectory(
	file: Blob,
	limits: BackupReadLimits = BACKUP_READ_LIMITS
): Promise<ZipEntry[]> {
	if (file.size > limits.maxFileBytes) {
		throw new BackupReadError(
			`This backup is ${mb(file.size)}. Crema restores backups up to ${mb(limits.maxFileBytes)}.`
		);
	}
	if (file.size < EOCD_BYTES) throw damaged('too short for a zip');
	const tailStart = Math.max(0, file.size - EOCD_BYTES - MAX_COMMENT);
	const tail = await bytesAt(file, tailStart, file.size);
	const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
	let at = -1;
	for (let i = tail.length - EOCD_BYTES; i >= 0; i--) {
		if (view.getUint32(i, true) === EOCD && i + EOCD_BYTES + view.getUint16(i + 20, true) === tail.length) {
			at = i;
			break;
		}
	}
	if (at < 0) throw damaged('no end of central directory');
	const disk = view.getUint16(at + 4, true);
	const cdDisk = view.getUint16(at + 6, true);
	const count = view.getUint16(at + 10, true);
	const cdSize = view.getUint32(at + 12, true);
	const cdOffset = view.getUint32(at + 16, true);
	if (count === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
		throw new BackupReadError('This backup is too large to restore (zip64).');
	}
	if (disk !== 0 || cdDisk !== 0 || count !== view.getUint16(at + 8, true)) throw damaged('a split archive');
	if (count > limits.maxEntries) {
		throw new BackupReadError(`This backup holds more than ${limits.maxEntries} files.`);
	}
	const eocdOffset = tailStart + at;
	if (cdOffset + cdSize > eocdOffset) throw damaged('the central directory is out of place');
	const cd = await bytesAt(file, cdOffset, cdOffset + cdSize);
	const cv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
	const entries: ZipEntry[] = [];
	const decoder = new TextDecoder('utf-8', { fatal: true });
	let p = 0;
	for (let n = 0; n < count; n++) {
		if (p + 46 > cd.length || cv.getUint32(p, true) !== CENTRAL) throw damaged('a central directory entry');
		const flags = cv.getUint16(p + 8, true);
		const nameLen = cv.getUint16(p + 28, true);
		const extraLen = cv.getUint16(p + 30, true);
		const commentLen = cv.getUint16(p + 32, true);
		const entry: ZipEntry = {
			method: cv.getUint16(p + 10, true),
			crc: cv.getUint32(p + 16, true),
			compressedSize: cv.getUint32(p + 20, true),
			size: cv.getUint32(p + 24, true),
			localOffset: cv.getUint32(p + 42, true),
			name: ''
		};
		if (p + 46 + nameLen > cd.length) throw damaged('a central directory entry');
		try {
			entry.name = decoder.decode(cd.subarray(p + 46, p + 46 + nameLen));
		} catch {
			throw damaged('a file name');
		}
		if (flags & 1) throw new BackupReadError('This backup is encrypted, which Crema can’t read.');
		if (entry.compressedSize === 0xffffffff || entry.size === 0xffffffff || entry.localOffset === 0xffffffff) {
			throw new BackupReadError('This backup is too large to restore (zip64).');
		}
		if (entry.localOffset + 30 + entry.compressedSize > cdOffset) throw damaged(`"${entry.name}" is out of place`);
		entries.push(entry);
		p += 46 + nameLen + extraLen + commentLen;
	}
	return entries;
}

/** The entries a restore reads, refused past the limits or when a name repeats. */
export function checkEntries(
	entries: ZipEntry[],
	read: (e: ZipEntry) => 'backup' | 'photo' | null,
	limits: BackupReadLimits = BACKUP_READ_LIMITS
): void {
	const names = new Set<string>();
	let total = 0;
	for (const e of entries) {
		const kind = read(e);
		if (!kind) continue;
		if (names.has(e.name)) throw damaged(`"${e.name}" twice`);
		names.add(e.name);
		if (e.method !== 0 && e.method !== 8) throw damaged(`"${e.name}" is compressed a way Crema can’t read`);
		if (e.method === 0 && e.compressedSize !== e.size) throw damaged(`"${e.name}"'s sizes disagree`);
		if (kind === 'photo' && e.size > limits.maxPhotoBytes) {
			throw new BackupReadError(
				`"${e.name}" in this backup unpacks to ${mb(e.size)}; the limit is ${mb(limits.maxPhotoBytes)}.`
			);
		}
		total += e.size;
		if (total > limits.maxInflatedBytes) {
			throw new BackupReadError(
				`This backup unpacks to more than ${mb(limits.maxInflatedBytes)}, too large to restore.`
			);
		}
	}
}

// ── An entry's bytes ──────────────────────────────────────────────────────

/**
 * Compressed bytes pushed to `Inflate` at a time. fflate inflates a push in
 * one go, and deflate reaches about 1032:1, so this bounds what one push can
 * make: 64 KiB in, at most about 64 MiB out, even from a bomb.
 */
const SLICE_BYTES = 64 * KiB;

/**
 * An entry's inflated bytes, a piece at a time. Its size is checked as it
 * goes, so past its declared size it stops at once (a zip bomb), and its CRC
 * at the end: until the iterator finishes, what it gave is unchecked, so a
 * restore validates the whole entry before it writes anything.
 */
export async function* entryBytes(file: Blob, entry: ZipEntry, sliceBytes = SLICE_BYTES): AsyncGenerator<Uint8Array> {
	const head = await bytesAt(file, entry.localOffset, entry.localOffset + 30);
	const hv = new DataView(head.buffer, head.byteOffset, head.byteLength);
	if (head.length < 30 || hv.getUint32(0, true) !== LOCAL) throw damaged(`"${entry.name}"'s local header`);
	const start = entry.localOffset + 30 + hv.getUint16(26, true) + hv.getUint16(28, true);
	const end = start + entry.compressedSize;
	if (end > file.size) throw damaged(`"${entry.name}" is cut short`);

	let crc = 0;
	let size = 0;
	let ended = entry.method !== 8;
	const out: Uint8Array[] = [];
	const take = (piece: Uint8Array) => {
		size += piece.length;
		if (size > entry.size) throw damaged(`"${entry.name}" is larger than it says`);
		crc = crc32(crc, piece);
		if (piece.length) out.push(piece);
	};
	const inflate =
		entry.method === 8
			? new Inflate((piece, final) => {
					take(piece);
					if (final) ended = true;
				})
			: null;
	const push = (slice: Uint8Array, last: boolean) => {
		if (!inflate) return take(slice);
		try {
			inflate.push(slice, last);
		} catch (e) {
			if (e instanceof BackupReadError) throw e;
			throw damaged(`"${entry.name}" won’t unpack`);
		}
	};
	if (start === end) push(new Uint8Array(0), true);
	for (let at = start; at < end; at += sliceBytes) {
		const to = Math.min(end, at + sliceBytes);
		push(await bytesAt(file, at, to), to === end);
		while (out.length) yield out.shift()!;
	}
	if (!ended || size !== entry.size) throw damaged(`"${entry.name}" is cut short`);
	if (crc !== entry.crc) throw damaged(`"${entry.name}" fails its checksum`);
}

/** Bytes read per slice of a bare JSONL backup. */
const BLOB_SLICE_BYTES = 1 * MiB;

/** A whole `Blob`, a piece at a time: a bare JSONL backup. */
export async function* blobBytes(file: Blob, sliceBytes = BLOB_SLICE_BYTES): AsyncGenerator<Uint8Array> {
	for (let at = 0; at < file.size; at += sliceBytes)
		yield await bytesAt(file, at, Math.min(file.size, at + sliceBytes));
}

// ── Lines ─────────────────────────────────────────────────────────────────

/** A line, `0x0A` not included, or one too long to hold, which was skipped. */
export type BackupLinePiece = { line: Uint8Array } | { oversized: true };

function isBlank(bytes: Uint8Array): boolean {
	for (let i = 0; i < bytes.length; i++) {
		const b = bytes[i];
		if (b !== 0x20 && b !== 0x09 && b !== 0x0a && b !== 0x0c && b !== 0x0d) return false;
	}
	return true;
}

const EMPTY = new Uint8Array(0);

/**
 * `bytes` split on `0x0A`, as the core splits a whole file: the piece after
 * the last newline is a line too. A line longer than `maxLineBytes` isn't
 * held: its bytes are skipped and it comes out `oversized`, unless every byte
 * of it was whitespace, which is a blank line however long (an empty line).
 */
export async function* backupLines(
	bytes: AsyncIterable<Uint8Array>,
	maxLineBytes: number = BACKUP_READ_LIMITS.maxLineBytes
): AsyncGenerator<BackupLinePiece> {
	let parts: Uint8Array[] = [];
	let held = 0;
	// Past the limit: the line's bytes are dropped, and whether all were blank kept.
	let skipping = false;
	let skippedBlank = true;
	const flush = (): BackupLinePiece => {
		if (skipping) {
			const piece: BackupLinePiece = skippedBlank ? { line: EMPTY } : { oversized: true };
			skipping = false;
			skippedBlank = true;
			return piece;
		}
		const line = parts.length === 1 ? parts[0] : concat(parts, held);
		parts = [];
		held = 0;
		return { line };
	};
	const add = (piece: Uint8Array) => {
		if (piece.length === 0) return;
		if (skipping) {
			skippedBlank &&= isBlank(piece);
			return;
		}
		if (held + piece.length > maxLineBytes) {
			skipping = true;
			skippedBlank = parts.every(isBlank) && isBlank(piece);
			parts = [];
			held = 0;
			return;
		}
		parts.push(piece);
		held += piece.length;
	};
	for await (const chunk of bytes) {
		let from = 0;
		for (let nl = chunk.indexOf(0x0a, from); nl >= 0; nl = chunk.indexOf(0x0a, from)) {
			add(chunk.subarray(from, nl));
			yield flush();
			from = nl + 1;
		}
		// Copy what's held over: the chunk's buffer may be reused.
		if (from < chunk.length) add(chunk.slice(from));
	}
	yield flush();
}

function concat(parts: Uint8Array[], length: number): Uint8Array {
	const out = new Uint8Array(length);
	let at = 0;
	for (const p of parts) {
		out.set(p, at);
		at += p.length;
	}
	return out;
}
