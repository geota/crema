import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Unzip, UnzipInflate, Zip, ZipDeflate, zipSync } from 'fflate';
import {
	BACKUP_READ_LIMITS,
	BackupReadError,
	backupLines,
	blobBytes,
	checkEntries,
	crc32,
	entryBytes,
	readCentralDirectory,
	type BackupLinePiece,
	type ZipEntry
} from './zip-reader';

/**
 * The streaming restore's zip reader and line splitter (#310 P2w-b, SPEC §7):
 * the central directory, not signatures, says where an entry ends; the CRC
 * and sizes are checked; the limits hold; lines split as the core splits.
 */
const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const JSONL = new Uint8Array(readFileSync(`${FIXTURES}/android-backup.crema.jsonl`));
const ANDROID_ZIP = new Uint8Array(readFileSync(`${FIXTURES}/android-zipoutputstream.crema.zip`));

const SIGNATURES = [
	[0x50, 0x4b, 0x03, 0x04],
	[0x50, 0x4b, 0x07, 0x08],
	[0x50, 0x4b, 0x01, 0x02]
];

/** The photo Android's fixture holds: xorshift bytes with all three signatures in them. */
function photo(): Uint8Array {
	const out = new Uint8Array(8192);
	let x = 0x2545f4914f6cdd1dn;
	const mask = (1n << 64n) - 1n;
	for (let i = 0; i < out.length; i++) {
		x ^= (x << 13n) & mask;
		x ^= x >> 7n;
		x ^= (x << 17n) & mask;
		out[i] = Number(x & 0xffn);
	}
	[100, 2000, 5000].forEach((at, s) => out.set(SIGNATURES[s], at));
	return out;
}

function holds(bytes: Uint8Array, sig: number[]): boolean {
	for (let i = 0; i + 4 <= bytes.length; i++) {
		if (sig.every((b, k) => bytes[i + k] === b)) return true;
	}
	return false;
}

const PHOTO = 'images/bean:android-1';

/** fflate `zipSync`, the web's old export: sizes in the local header. */
function zipSyncFixture(): Uint8Array {
	return zipSync({ [PHOTO]: [photo(), { level: 0 }], 'backup.jsonl': JSONL });
}

/** fflate `Zip` with `ZipDeflate`, #386's streamed export: data descriptors. */
function zipDeflateFixture(): Uint8Array {
	const parts: Uint8Array[] = [];
	const zip = new Zip((err, chunk) => {
		if (err) throw err;
		parts.push(chunk);
	});
	for (const [name, bytes, level] of [
		[PHOTO, photo(), 0],
		['backup.jsonl', JSONL, 6]
	] as const) {
		const entry = new ZipDeflate(name, { level });
		zip.add(entry);
		entry.push(bytes, true);
	}
	zip.end();
	return join(parts);
}

function join(parts: Uint8Array[]): Uint8Array {
	const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
	let at = 0;
	for (const p of parts) {
		out.set(p, at);
		at += p.length;
	}
	return out;
}

async function all(it: AsyncIterable<Uint8Array>): Promise<Uint8Array> {
	const parts: Uint8Array[] = [];
	for await (const p of it) parts.push(p);
	return join(parts);
}

const blob = (bytes: Uint8Array) => new Blob([bytes as BlobPart]);

async function read(bytes: Uint8Array, name: string): Promise<Uint8Array> {
	const file = blob(bytes);
	const entry = (await readCentralDirectory(file)).find((e) => e.name === name)!;
	return all(entryBytes(file, entry));
}

async function refusal(p: Promise<unknown>): Promise<string> {
	try {
		await p;
	} catch (e) {
		expect(e).toBeInstanceOf(BackupReadError);
		return (e as Error).message;
	}
	throw new Error('not refused');
}

/** Where `entry`'s compressed bytes start in `zip`. */
function dataStart(zip: Uint8Array, entry: ZipEntry): number {
	const v = new DataView(zip.buffer, zip.byteOffset);
	return entry.localOffset + 30 + v.getUint16(entry.localOffset + 26, true) + v.getUint16(entry.localOffset + 28, true);
}

describe('the zip reader', () => {
	const fixtures = [
		['Android ZipOutputStream', () => ANDROID_ZIP],
		['fflate zipSync', zipSyncFixture],
		['fflate ZipDeflate', zipDeflateFixture]
	] as const;

	for (const [name, make] of fixtures) {
		it(`${name}: reads both entries whole, signatures and all`, async () => {
			const zip = make();
			const entries = await readCentralDirectory(blob(zip));
			const photoEntry = entries.find((e) => e.name === PHOTO)!;
			const compressed = zip.subarray(
				dataStart(zip, photoEntry),
				dataStart(zip, photoEntry) + photoEntry.compressedSize
			);
			for (const sig of SIGNATURES) expect(holds(compressed, sig), `${sig} in the compressed photo`).toBe(true);
			expect(await read(zip, PHOTO)).toEqual(photo());
			expect(await read(zip, 'backup.jsonl')).toEqual(JSONL);
		});
	}

	it('fflate streaming Unzip cuts the descriptor zip short, which is why this reader exists', () => {
		const zip = zipDeflateFixture();
		const got: Record<string, Uint8Array[]> = {};
		const unzip = new Unzip((file) => {
			got[file.name] = [];
			file.ondata = (err, chunk) => {
				if (!err) got[file.name].push(chunk);
			};
			file.start();
		});
		unzip.register(UnzipInflate);
		try {
			unzip.push(zip, true);
		} catch {
			// it may throw instead
		}
		const photoBytes = got[PHOTO] ? join(got[PHOTO]) : new Uint8Array();
		expect(photoBytes).not.toEqual(photo());
	});

	it('a corrupted byte fails the checksum, deflated or stored', async () => {
		// Android's photo is deflate in stored blocks (method 8); zipSync level 0 stores it (method 0).
		for (const [zip, method] of [
			[ANDROID_ZIP.slice(), 8],
			[zipSyncFixture(), 0]
		] as const) {
			const entry = (await readCentralDirectory(blob(zip))).find((e) => e.name === PHOTO)!;
			expect(entry.method).toBe(method);
			// Past any block header: the byte changes the output, not its length.
			zip[dataStart(zip, entry) + 300] ^= 0xff;
			expect(await refusal(read(zip, PHOTO))).toMatch(/checksum/);
		}
	});

	it('a central directory that understates a size stops at it: a zip bomb', async () => {
		const big = new Uint8Array(4 * 1024 * 1024);
		const zip = zipSync({ 'backup.jsonl': big });
		const v = new DataView(zip.buffer);
		const entry = (await readCentralDirectory(blob(zip)))[0];
		// The central directory's uncompressed size, rewritten to 1 KiB.
		const cd = zip.length - 22 - (46 + entry.name.length);
		expect(v.getUint32(cd, true)).toBe(0x02014b50);
		v.setUint32(cd + 24, 1024, true);
		const lying = (await readCentralDirectory(blob(zip)))[0];
		expect(lying.size).toBe(1024);
		let seen = 0;
		const message = await refusal(
			(async () => {
				for await (const piece of entryBytes(blob(zip), lying)) seen += piece.length;
			})()
		);
		expect(message).toMatch(/larger than it says/);
		expect(seen).toBeLessThanOrEqual(1024);
	});

	it('a central directory that overstates a size is cut short, even with the right CRC', async () => {
		const zip = zipSync({ 'backup.jsonl': JSONL });
		const v = new DataView(zip.buffer);
		const cd = zip.length - 22 - (46 + 'backup.jsonl'.length);
		expect(v.getUint32(cd + 16, true)).toBe(crc32(0, JSONL));
		v.setUint32(cd + 24, JSONL.length + 100, true);
		expect(await refusal(read(zip, 'backup.jsonl'))).toMatch(/cut short/);
	});

	it('declared sizes past the limits are refused before anything inflates', async () => {
		const entries = await readCentralDirectory(blob(ANDROID_ZIP));
		const kind = (e: ZipEntry): 'backup' | 'photo' => (e.name === 'backup.jsonl' ? 'backup' : 'photo');
		expect(() => checkEntries(entries, kind)).not.toThrow();
		expect(() =>
			checkEntries(entries, kind, {
				...BACKUP_READ_LIMITS,
				maxInflatedBytes: 10_000
			})
		).toThrow(/unpacks to more than/);
		expect(() =>
			checkEntries(entries, kind, {
				...BACKUP_READ_LIMITS,
				maxPhotoBytes: 1000
			})
		).toThrow(/the limit is/);
		const twice = [...entries, entries[1]];
		expect(() => checkEntries(twice, kind)).toThrow(/twice/);
		expect(() => checkEntries([{ ...entries[1], method: 12 }], kind)).toThrow(/compressed a way/);
		expect(
			await refusal(
				readCentralDirectory(blob(ANDROID_ZIP), {
					...BACKUP_READ_LIMITS,
					maxEntries: 1
				})
			)
		).toMatch(/more than 1 files/);
		expect(
			await refusal(
				readCentralDirectory(blob(ANDROID_ZIP), {
					...BACKUP_READ_LIMITS,
					maxFileBytes: 1000
				})
			)
		).toMatch(/restores backups up to/);
	});

	it('a truncated file is refused, wherever it was cut', async () => {
		const zip = zipDeflateFixture();
		for (const keep of [10, 100, Math.floor(zip.length / 2), zip.length - 30, zip.length - 1]) {
			const cut = zip.subarray(0, keep);
			await refusal(
				(async () => {
					const file = blob(cut);
					for (const e of await readCentralDirectory(file)) await all(entryBytes(file, e));
				})()
			);
		}
		// Cut inside an entry's data, with the directory intact: cut short, not read as complete.
		const entries = await readCentralDirectory(blob(zip));
		const jsonl = entries.find((e) => e.name === 'backup.jsonl')!;
		const short = { ...jsonl, compressedSize: jsonl.compressedSize - 10 };
		expect(await refusal(all(entryBytes(blob(zip), short)))).toMatch(/cut short|won’t unpack/);
	});

	it('zip64, encryption and a split archive are refused', async () => {
		const zip = zipSyncFixture();
		const eocd = zip.length - 22;
		const at = (patch: (v: DataView) => void) => {
			const copy = zip.slice();
			patch(new DataView(copy.buffer));
			return refusal(readCentralDirectory(blob(copy)));
		};
		expect(await at((v) => v.setUint32(eocd + 16, 0xffffffff, true))).toMatch(/zip64/);
		expect(await at((v) => v.setUint16(eocd + 4, 1, true))).toMatch(/split/);
		const cdOffset = new DataView(zip.buffer).getUint32(eocd + 16, true);
		expect(await at((v) => v.setUint16(cdOffset + 8, 1, true))).toMatch(/encrypted/);
		expect(await refusal(readCentralDirectory(blob(new Uint8Array(100))))).toMatch(/end of central directory/);
	});

	it('the CRC is the standard one', () => {
		expect(crc32(0, new TextEncoder().encode('123456789'))).toBe(0xcbf43926);
		const bytes = photo();
		expect(crc32(crc32(0, bytes.subarray(0, 3000)), bytes.subarray(3000))).toBe(crc32(0, bytes));
	});

	it('a stored entry reads too', async () => {
		const zip = zipSync({ 'backup.jsonl': [JSONL, { level: 0 }] });
		expect(new DataView(zip.buffer).getUint16(8, true)).toBe(0);
		expect(await read(zip, 'backup.jsonl')).toEqual(JSONL);
	});
});

// ── Lines ──────────────────────────────────────────────────────────────────

async function* chunked(bytes: Uint8Array, sizes: number[]): AsyncGenerator<Uint8Array> {
	let at = 0;
	for (let i = 0; at < bytes.length; i++) {
		const n = sizes[i % sizes.length];
		yield bytes.slice(at, at + n);
		at += n;
	}
}

async function linesOf(bytes: Uint8Array, sizes: number[], max = 1 << 20): Promise<string[]> {
	const out: string[] = [];
	const text = new TextDecoder();
	for await (const piece of backupLines(chunked(bytes, sizes), max)) {
		out.push('line' in piece ? text.decode(piece.line) : '<oversized>');
	}
	return out;
}

describe('backup lines', () => {
	const enc = new TextEncoder();

	it('split on 0x0A as the core splits a whole file, however the bytes arrive', async () => {
		for (const text of ['a\nbb\n\nccc', 'a\nbb\n', '\n', '', 'x', '\n\n\nlast']) {
			const want = text.split('\n');
			for (const sizes of [[1], [2], [3, 1], [1000], [5, 7, 1]]) {
				expect(await linesOf(enc.encode(text), sizes), `${JSON.stringify(text)} by ${sizes}`).toEqual(want);
			}
		}
		const fixture = new TextDecoder().decode(JSONL).split('\n');
		expect(await linesOf(JSONL, [97, 4096, 1])).toEqual(fixture);
	});

	it('a line past the limit is skipped, and one at it is kept', async () => {
		const text = enc.encode(`${'a'.repeat(10)}\n${'b'.repeat(11)}\nshort\n${'c'.repeat(25)}`);
		for (const sizes of [[1], [4], [100]]) {
			expect(await linesOf(text, sizes, 10)).toEqual(['a'.repeat(10), '<oversized>', 'short', '<oversized>']);
		}
	});

	it('a whitespace line past the limit is blank, not oversized', async () => {
		const text = enc.encode(`x\n${' \t\r'.repeat(10)}\n${'  '.repeat(4)}y${' '.repeat(10)}\nz`);
		for (const sizes of [[1], [3], [100]]) {
			expect(await linesOf(text, sizes, 8)).toEqual(['x', '', '<oversized>', 'z']);
		}
	});

	it('a bare JSONL file reads in slices', async () => {
		const pieces: BackupLinePiece[] = [];
		for await (const p of backupLines(blobBytes(blob(JSONL), 100))) pieces.push(p);
		expect(pieces.map((p) => ('line' in p ? new TextDecoder().decode(p.line) : null))).toEqual(
			new TextDecoder().decode(JSONL).split('\n')
		);
	});
});

describe('the read limits', () => {
	it('are the export’s (#386), so a restore reads what a backup may hold', async () => {
		const { BACKUP_LIMITS } = await import('./stream');
		expect(BACKUP_READ_LIMITS).toMatchObject(BACKUP_LIMITS);
	});
});
