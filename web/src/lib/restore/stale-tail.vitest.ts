/**
 * A backup saved over a larger, older zip by a provider that doesn't truncate
 * on "w" (#413): the new zip, then the old zip's tail, whose end record ends
 * the file. Android restores these since #410; so does the web now, by the
 * same rule. An earlier directory is read only if it agrees exactly with the
 * local headers from offset 0, holds `backup.jsonl`, and every entry reads
 * whole. Otherwise the refusal is the one the end record's directory gives,
 * as before; never a partial read.
 *
 * The twin of Android's `BackupReaderTest` (`aBackupSavedOverALargerZip…`):
 * the same writers, two of them Android's, and the same damage.
 */
import { describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { zipSync } from 'fflate';
import * as zipReader from '#lib/backup/zip-reader.js';
import { BackupReadError, entryBytes, readCentralDirectory, type ZipEntry } from '#lib/backup/zip-reader.js';
import { prepareBackupFile, type PreparedBackup } from './engine';

// The real reader, with its earlier-record search one a test can turn off,
// to get the refusal a file gives without the fallback.
vi.mock('#lib/backup/zip-reader.js', async (importOriginal) => {
	const real = await importOriginal<typeof import('#lib/backup/zip-reader.js')>();
	return { ...real, earlierEndRecords: vi.fn(real.earlierEndRecords) };
});

const CORE = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const read = (path: string) => new Uint8Array(readFileSync(path));
const JSONL = read(`${CORE}/android-backup.crema.jsonl`);

/** The writers a backup can come from: Android's two, fflate's two, and the web's streamed export. */
const WRITERS: [string, Uint8Array][] = [
	['Android ZipOutputStream (before #391)', read(`${CORE}/android-zipoutputstream.crema.zip`)],
	['Android streamed (#310 P2a)', read(resolve(process.cwd(), 'src/lib/backup/fixtures/android-streamed-photo.crema.zip'))],
	['fflate zipSync', read(`${CORE}/fflate-zipsync.crema.zip`)],
	['fflate ZipDeflate', read(`${CORE}/fflate-zipdeflate.crema.zip`)],
	[
		'web streamed (#310 P2w)',
		read(resolve(process.cwd(), '../android/app/src/test/resources/backup/web-streamed-photo.crema.zip'))
	]
];

const blob = (bytes: Uint8Array) => new Blob([bytes as BlobPart]);
const text = (b: Uint8Array) => new TextDecoder().decode(b);

/** The photo Android's fixtures hold, as in `zip-reader.vitest.ts`. */
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
	return out;
}

/**
 * `newer` saved over a larger, older backup: its bytes first, then the old
 * zip's tail, whose end record ends the file.
 */
function savedOverALargerZip(newer: Uint8Array): Uint8Array {
	const old = text(JSONL).replace('android-1', 'android-old') + text(JSONL);
	const big = new Uint8Array(40 * 8192);
	for (let i = 0; i < 40; i++) big.set(photo(), i * 8192);
	const bytes = zipSync({
		'backup.jsonl': new TextEncoder().encode(old),
		'images/bean:android-old': [big, { level: 0 }]
	});
	expect(bytes.length).toBeGreaterThan(newer.length + 1000);
	bytes.set(newer, 0);
	return bytes;
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

/** What `bytes` gives with no earlier directory looked for: the refusal before #413. */
async function withoutTheFallback(bytes: Uint8Array): Promise<string> {
	vi.mocked(zipReader.earlierEndRecords).mockResolvedValueOnce([]);
	return refusal(prepareBackupFile(blob(bytes), 'no fallback'));
}

async function linesOf(p: PreparedBackup): Promise<string[]> {
	const out: string[] = [];
	for await (const piece of p.lines()) out.push('line' in piece ? text(piece.line) : '<oversized>');
	return out;
}

async function photoIds(zip: Uint8Array): Promise<string[]> {
	return (await readCentralDirectory(blob(zip)))
		.filter((e) => e.name.startsWith('images/') && e.size > 0)
		.map((e) => e.name.slice('images/'.length));
}

const u32 = (b: Uint8Array, at: number) => new DataView(b.buffer, b.byteOffset).getUint32(at, true);
const putU32 = (b: Uint8Array, at: number, v: number) => new DataView(b.buffer, b.byteOffset).setUint32(at, v, true);

/** Where `entry`'s compressed bytes start in `zip`. */
function dataStart(zip: Uint8Array, entry: ZipEntry): number {
	const v = new DataView(zip.buffer, zip.byteOffset);
	return entry.localOffset + 30 + v.getUint16(entry.localOffset + 26, true) + v.getUint16(entry.localOffset + 28, true);
}

/** Where `name` sits in its central directory header. */
function centralNameAt(zip: Uint8Array, name: string): number {
	for (let i = 0; i + 46 < zip.length; i++) {
		if (u32(zip, i) === 0x02014b50 && text(zip.subarray(i + 46, i + 46 + name.length)) === name) return i + 46;
	}
	throw new Error(`no central header for ${name}`);
}

describe('a backup saved over a larger zip (#413)', () => {
	for (const [name, newer] of WRITERS) {
		it(`${name}: reads as the new backup exactly`, async () => {
			const bytes = savedOverALargerZip(newer);
			// The end record's directory is the old zip's, and doesn't read.
			expect(await withoutTheFallback(bytes)).toMatch(/damaged/);
			const want = await prepareBackupFile(blob(newer), name);
			const got = await prepareBackupFile(blob(bytes), name);
			expect(got.manifestJson).toBe(want.manifestJson);
			expect(await linesOf(got)).toEqual(await linesOf(want));
			const ids = await photoIds(newer);
			expect(ids.length).toBeGreaterThan(0);
			for (const id of ids) expect(await got.photo(id)).toEqual(await want.photo(id));
			// Nothing of the old zip's comes through.
			expect(await got.photo('bean:android-old')).toBeNull();
		});
	}

	describe('whose new part doesn’t agree is refused, as without the fallback', async () => {
		const newer = WRITERS[0][1];
		const photoEntry = (await readCentralDirectory(blob(newer))).find((e) => e.name.startsWith('images/'))!;
		const photoName = photoEntry.name;
		const flip = (b: Uint8Array, at: number) => {
			b[at] ^= 1;
		};
		const damage: [string, (b: Uint8Array) => void][] = [
			// Its photo's bytes: the directory agrees, the read fails the checksum.
			['a photo byte', (b) => flip(b, dataStart(newer, photoEntry) + 300)],
			// A name in its directory: no longer the local header's.
			['a directory name', (b) => (b[centralNameAt(newer, photoName) + 'images/b'.length] = 'd'.charCodeAt(0))],
			// Its directory's CRC for the photo: the header and descriptor disagree.
			['a directory CRC', (b) => flip(b, centralNameAt(newer, photoName) - 46 + 16)],
			// Its end record no longer just after its directory.
			['the end record’s offset', (b) => flip(b, newer.length - 22 + 16)]
		];
		const inputs: [string, Uint8Array][] = damage.map(([what, hurt]) => {
			const bytes = savedOverALargerZip(newer);
			hurt(bytes);
			return [what, bytes];
		});

		// Bytes between its last entry and its directory: a zip that reads on
		// its own, but not one whose records run exactly to its directory.
		const cdOffset = u32(newer, newer.length - 22 + 16);
		const gapped = new Uint8Array(newer.length + 16);
		gapped.set(newer.subarray(0, cdOffset), 0);
		gapped.set(newer.subarray(cdOffset), cdOffset + 16);
		putU32(gapped, gapped.length - 22 + 16, cdOffset + 16);
		inputs.push(['a gap before its directory', savedOverALargerZip(gapped)]);

		// An entry no restore reads, damaged: the new zip alone still prepares
		// (only the member and the photos are read), but an earlier directory
		// is taken only when every entry reads whole.
		const notes = new TextEncoder().encode('notes '.repeat(200));
		const withNotes = zipSync({
			'backup.jsonl': JSONL,
			'notes.txt': [notes, { level: 0 }]
		});
		const notesEntry = (await readCentralDirectory(blob(withNotes))).find((e) => e.name === 'notes.txt')!;
		const hurtNotes = withNotes.slice();
		flip(hurtNotes, dataStart(withNotes, notesEntry) + 10);
		inputs.push(['an entry no restore reads', savedOverALargerZip(hurtNotes)]);

		it('the gapped zip and the damaged-notes zip each read on their own', async () => {
			await prepareBackupFile(blob(gapped), 'gapped');
			await prepareBackupFile(blob(hurtNotes), 'notes');
			// And the notes entry really is damaged.
			await expect(
				(async () => {
					for await (const piece of entryBytes(blob(hurtNotes), notesEntry)) void piece;
				})()
			).rejects.toThrow(/checksum/);
		});

		for (const [what, bytes] of inputs) {
			it(what, async () => {
				const why = await refusal(prepareBackupFile(blob(bytes), what));
				expect(why).toBe(await withoutTheFallback(bytes));
			});
		}
	});
});
