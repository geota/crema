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
import { BACKUP_LIMITS, EntryBudget } from './stream';

const KiB = 1024;
const MiB = 1024 * KiB;

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

/** #386's export limits: a restore reads exactly what an export may write. */
export const BACKUP_READ_LIMITS: BackupReadLimits = BACKUP_LIMITS;

/**
 * A backup that won't read: damaged, or past a limit. Nothing was written.
 * `damaged` is the first kind, which an earlier directory may still read
 * (#413, as on Android since #410).
 */
export class BackupReadError extends Error {
	override name = 'BackupReadError';
	readonly damaged: boolean;

	constructor(message: string, damaged = false) {
		super(message);
		this.damaged = damaged;
	}
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
const DESCRIPTOR = 0x08074b50;
const EOCD_BYTES = 22;
const MAX_COMMENT = 0xffff;

async function bytesAt(file: Blob, start: number, end: number): Promise<Uint8Array> {
	return new Uint8Array(await file.slice(start, end).arrayBuffer());
}

function damaged(why: string): BackupReadError {
	return new BackupReadError(`This backup is damaged (${why}). Nothing was changed.`, true);
}

/** Whether `bytes` start like a zip (a local header). */
export function isZipStart(bytes: Uint8Array): boolean {
	return bytes.length >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04;
}

/** The end-of-central-directory record: the last one whose comment ends the file. */
async function findEndRecord(file: Blob): Promise<{ view: DataView; at: number; tailStart: number } | null> {
	if (file.size < EOCD_BYTES) return null;
	const tailStart = Math.max(0, file.size - EOCD_BYTES - MAX_COMMENT);
	const tail = await bytesAt(file, tailStart, file.size);
	const view = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
	for (let i = tail.length - EOCD_BYTES; i >= 0; i--) {
		if (view.getUint32(i, true) === EOCD && i + EOCD_BYTES + view.getUint16(i + 20, true) === tail.length) {
			return { view, at: i, tailStart };
		}
	}
	return null;
}

/**
 * Whether `file` is a zip: it starts like one, or it ends like one, so a zip
 * whose first bytes are damaged is read, and refused, as a zip, not as text.
 */
export async function looksLikeZip(file: Blob): Promise<boolean> {
	if (isZipStart(new Uint8Array(await file.slice(0, 4).arrayBuffer()))) return true;
	return (await findEndRecord(file)) !== null;
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
	const end = await findEndRecord(file);
	if (!end) throw damaged(file.size < EOCD_BYTES ? 'too short for a zip' : 'no end of central directory');
	const { view, at, tailStart } = end;
	const record = new DataView(view.buffer, view.byteOffset + at, EOCD_BYTES);
	return (await parseDirectory(file, tailStart + at, record, limits, false)).entries;
}

/** One entry as the directory gives it, with what the exact check compares. */
interface DirEntry extends ZipEntry {
	/** `name` as stored, to compare with the local header's. */
	nameBytes: Uint8Array;
	/** The general-purpose flags. */
	flags: number;
	/** The DOS time and date, as one number. */
	dosTime: number;
}

/** A zip's entries, and where its directory and end record are. */
export interface ZipDirectory {
	entries: ZipEntry[];
	cdOffset: number;
	endAt: number;
}

/**
 * Largest central directory read. A Crema backup's is about 100 bytes an
 * entry; this bounds what a zip that lies about it can make the reader hold.
 * Android's `MAX_DIRECTORY_BYTES`.
 */
const MAX_DIRECTORY_BYTES = 16 * MiB;

/**
 * The directory the end record `record`, at `eocdOffset`, gives. `exact` (an
 * earlier directory, #413) also asks that the directory end where the end
 * record starts and that its entries fill it.
 */
async function parseDirectory(
	file: Blob,
	eocdOffset: number,
	record: DataView,
	limits: BackupReadLimits,
	exact: boolean
): Promise<{ entries: DirEntry[]; cdOffset: number; endAt: number }> {
	const disk = record.getUint16(4, true);
	const cdDisk = record.getUint16(6, true);
	const count = record.getUint16(10, true);
	const cdSize = record.getUint32(12, true);
	const cdOffset = record.getUint32(16, true);
	if (count === 0xffff || cdSize === 0xffffffff || cdOffset === 0xffffffff) {
		throw new BackupReadError('This backup is too large to restore (zip64).');
	}
	if (disk !== 0 || cdDisk !== 0 || count !== record.getUint16(8, true)) throw damaged('a split archive');
	if (count > limits.maxEntries) {
		throw new BackupReadError(`This backup holds more than ${limits.maxEntries} files.`);
	}
	if (exact && count === 0) throw damaged('no files in the zip');
	if (cdOffset + cdSize > eocdOffset) throw damaged('the central directory is out of place');
	if (exact && cdOffset + cdSize !== eocdOffset) throw damaged('the central directory is out of place');
	if (cdSize > MAX_DIRECTORY_BYTES) throw new BackupReadError('This backup’s list of files is too large to restore.');
	const cd = await bytesAt(file, cdOffset, cdOffset + cdSize);
	const cv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
	const entries: DirEntry[] = [];
	const decoder = new TextDecoder('utf-8', { fatal: true });
	let p = 0;
	for (let n = 0; n < count; n++) {
		if (p + 46 > cd.length || cv.getUint32(p, true) !== CENTRAL) throw damaged('a central directory entry');
		const flags = cv.getUint16(p + 8, true);
		const nameLen = cv.getUint16(p + 28, true);
		const extraLen = cv.getUint16(p + 30, true);
		const commentLen = cv.getUint16(p + 32, true);
		if (p + 46 + nameLen > cd.length) throw damaged('a central directory entry');
		if (exact && p + 46 + nameLen + extraLen > cd.length) throw damaged('a central directory entry');
		const nameBytes = cd.slice(p + 46, p + 46 + nameLen);
		const entry: DirEntry = {
			method: cv.getUint16(p + 10, true),
			crc: cv.getUint32(p + 16, true),
			compressedSize: cv.getUint32(p + 20, true),
			size: cv.getUint32(p + 24, true),
			localOffset: cv.getUint32(p + 42, true),
			name: '',
			nameBytes,
			flags,
			dosTime: cv.getUint32(p + 12, true)
		};
		try {
			entry.name = decoder.decode(nameBytes);
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
	if (exact && p !== cd.length) throw damaged('a central directory entry');
	return { entries, cdOffset, endAt: eocdOffset };
}

// ── An earlier directory (#413) ───────────────────────────────────────────
//
// Android backups saved before #391 used mode "w". A provider that doesn't
// truncate on "w" leaves, after a save over a larger file, the old zip's
// tail after the new zip: the end record that ends the file is the old one's,
// and its directory doesn't read. Android restores these since #410; the
// same rule, here: an earlier directory is read only if it agrees exactly
// with the local headers from offset 0, holds `backup.jsonl`, and every
// entry reads whole. Otherwise the refusal stands, never a partial read.

/** Most earlier end records {@link earlierEndRecords} gives: each costs a read of its directory. */
const MAX_EARLIER_END_RECORDS = 64;
/** Bytes searched at a time, going backwards. */
const SEARCH_WINDOW = 64 * KiB;

/**
 * Where `file` may hold an earlier zip's end record, last first: each
 * signature before the end record that ends the file (or before the end,
 * when none does) whose record is whole, unsplit, and has its directory just
 * before it.
 */
export async function earlierEndRecords(file: Blob): Promise<number[]> {
	const size = file.size;
	const found: number[] = [];
	const last = await findEndRecord(file);
	let top = last ? last.tailStart + last.at : size;
	while (top > 0 && found.length < MAX_EARLIER_END_RECORDS) {
		const from = Math.max(0, top - SEARCH_WINDOW);
		const buf = await bytesAt(file, from, Math.min(size, top + 3));
		const v = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
		for (let i = top - from - 1; i >= 0; i--) {
			if (i + 4 > buf.length || v.getUint32(i, true) !== EOCD) continue;
			const at = from + i;
			if (at + EOCD_BYTES > size) continue;
			const r = await bytesAt(file, at, at + EOCD_BYTES);
			const e = new DataView(r.buffer, r.byteOffset, r.byteLength);
			const count = e.getUint16(10, true);
			const ok =
				e.getUint16(4, true) === 0 &&
				e.getUint16(6, true) === 0 &&
				e.getUint16(8, true) === count &&
				count > 0 &&
				e.getUint32(16, true) + e.getUint32(12, true) === at &&
				at + EOCD_BYTES + e.getUint16(20, true) <= size;
			if (ok) {
				found.push(at);
				if (found.length === MAX_EARLIER_END_RECORDS) break;
			}
		}
		top = from;
	}
	return found;
}

/**
 * The directory whose end record is at `endAt`, only if it agrees exactly
 * with the local headers: its entries, in order, are the zip's records from
 * offset 0 to the directory with nothing between them, each local header
 * names its entry as the directory does, with the same flags, method, time,
 * and CRC and sizes (in its header, or its data descriptor), and it holds
 * `backup.jsonl`. Otherwise null. The caller then reads every entry whole.
 */
export async function exactDirectoryAt(
	file: Blob,
	endAt: number,
	limits: BackupReadLimits = BACKUP_READ_LIMITS
): Promise<ZipDirectory | null> {
	if (endAt < 0 || endAt + EOCD_BYTES > file.size) return null;
	const r = await bytesAt(file, endAt, endAt + EOCD_BYTES);
	const record = new DataView(r.buffer, r.byteOffset, r.byteLength);
	if (record.getUint32(0, true) !== EOCD) return null;
	let d: { entries: DirEntry[]; cdOffset: number; endAt: number };
	try {
		d = await parseDirectory(file, endAt, record, limits, true);
	} catch (e) {
		if (e instanceof BackupReadError) return null;
		throw e;
	}
	if (!d.entries.some((e) => e.name === 'backup.jsonl')) return null;
	return (await agreesExactly(file, d)) ? d : null;
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
	return true;
}

async function agreesExactly(file: Blob, d: { entries: DirEntry[]; cdOffset: number }): Promise<boolean> {
	let at = 0;
	for (const e of d.entries) {
		if (e.localOffset !== at || at + 30 > d.cdOffset) return false;
		const head = await bytesAt(file, at, at + 30);
		const h = new DataView(head.buffer, head.byteOffset, head.byteLength);
		if (h.getUint32(0, true) !== LOCAL) return false;
		const flags = h.getUint16(6, true);
		if (flags !== e.flags || h.getUint16(8, true) !== e.method || h.getUint32(10, true) !== e.dosTime) return false;
		const nameLen = h.getUint16(26, true);
		const extraLen = h.getUint16(28, true);
		const dataEnd = at + 30 + nameLen + extraLen + e.compressedSize;
		if (dataEnd > d.cdOffset) return false;
		if (!sameBytes(await bytesAt(file, at + 30, at + 30 + nameLen), e.nameBytes)) return false;
		const crc = h.getUint32(14, true);
		const compressed = h.getUint32(18, true);
		const size = h.getUint32(22, true);
		if (flags & 8) {
			// The header may give them as 0, for its data descriptor to give.
			if ((crc !== 0 && crc !== e.crc) || (compressed !== 0 && compressed !== e.compressedSize) || (size !== 0 && size !== e.size)) {
				return false;
			}
			const n = Math.min(16, d.cdOffset - dataEnd);
			if (n < 12) return false;
			const desc = await bytesAt(file, dataEnd, dataEnd + n);
			const dv = new DataView(desc.buffer, desc.byteOffset, desc.byteLength);
			const gives = (o: number) =>
				dv.getUint32(o, true) === e.crc &&
				dv.getUint32(o + 4, true) === e.compressedSize &&
				dv.getUint32(o + 8, true) === e.size;
			if (n === 16 && dv.getUint32(0, true) === DESCRIPTOR && gives(4)) at = dataEnd + 16;
			else if (gives(0)) at = dataEnd + 12;
			else return false;
		} else {
			if (crc !== e.crc || compressed !== e.compressedSize || size !== e.size) return false;
			at = dataEnd;
		}
	}
	return at === d.cdOffset;
}

/** The entries a restore reads, refused past the limits or when a name repeats. */
export function checkEntries(
	entries: ZipEntry[],
	read: (e: ZipEntry) => 'backup' | 'photo' | null,
	limits: BackupReadLimits = BACKUP_READ_LIMITS
): void {
	const names = new Set<string>();
	// The export's rules (stream.ts), so a backup it wrote always reads here.
	const budget = new EntryBudget(limits);
	for (const e of entries) {
		const kind = read(e);
		if (!kind) continue;
		if (names.has(e.name)) throw damaged(`"${e.name}" twice`);
		names.add(e.name);
		if (e.method !== 0 && e.method !== 8) throw damaged(`"${e.name}" is compressed a way Crema can’t read`);
		const over = budget.add(kind, e.name, e.size);
		if (over) throw new BackupReadError(`This backup ${over}, too large to restore.`);
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
	const nameLen = hv.getUint16(26, true);
	// The local header names the entry too: one that disagrees with the
	// directory is damage, which would put a photo on another bean.
	const localName = await bytesAt(file, entry.localOffset + 30, entry.localOffset + 30 + nameLen);
	if (new TextDecoder().decode(localName) !== entry.name) throw damaged(`"${entry.name}"'s names disagree`);
	const start = entry.localOffset + 30 + nameLen + hv.getUint16(28, true);
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
