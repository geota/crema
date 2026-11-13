/**
 * The two fflate-written zips the Android reader is tested on (#395,
 * REVIEW-395): `zipSync`, with sizes in the local header, and `Zip` with
 * `ZipDeflate`, the streamed export's shape (data descriptors). The photo comes
 * first and holds all three zip signatures, stored, so they show in its
 * compressed bytes; it's the Android fixture's photo.
 *
 * Built here with the fflate this build ships, and compared with the committed
 * files entry by entry (name, method, flags, contents) so they can't drift from
 * how the web writes a zip. fflate stamps local time, so whole-file bytes can
 * differ between machines; entries can't. Rerun with CREMA_WRITE_FIXTURE=1 to
 * rewrite them.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { Zip, ZipDeflate, unzipSync, zipSync } from 'fflate';

const DIR = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const PHOTO = 'images/bean:android-1';
const STALE = 'The fixture is stale: rerun this test with CREMA_WRITE_FIXTURE=1 and commit it.';

/** xorshift bytes with the three zip signatures in them, as BackupReaderTest's `photo()`. */
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
	[
		[0x50, 0x4b, 0x03, 0x04],
		[0x50, 0x4b, 0x07, 0x08],
		[0x50, 0x4b, 0x01, 0x02]
	].forEach((sig, s) => out.set(sig, [100, 2000, 5000][s]));
	return out;
}

const mtime = new Date(Date.UTC(2026, 10, 8));
const jsonl = () => new Uint8Array(readFileSync(resolve(DIR, 'android-backup.crema.jsonl')));

function buildZipSync(): Uint8Array {
	return zipSync({ [PHOTO]: [photo(), { level: 0, mtime }], 'backup.jsonl': [jsonl(), { mtime }] });
}

function buildZipDeflate(): Uint8Array {
	const parts: Uint8Array[] = [];
	const zip = new Zip((err, chunk) => {
		if (err) throw err;
		parts.push(chunk);
	});
	for (const [name, bytes, level] of [
		[PHOTO, photo(), 0],
		['backup.jsonl', jsonl(), 6]
	] as const) {
		const entry = new ZipDeflate(name, { level });
		entry.mtime = mtime;
		zip.add(entry);
		entry.push(bytes, true);
	}
	zip.end();
	const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
	let at = 0;
	for (const p of parts) {
		out.set(p, at);
		at += p.length;
	}
	return out;
}

/** The central directory's entries: name, method and flags. */
function directory(zip: Uint8Array): { name: string; method: number; flags: number }[] {
	const u16 = (at: number) => zip[at] | (zip[at + 1] << 8);
	const u32 = (at: number) => (u16(at) | (u16(at + 2) << 16)) >>> 0;
	let end = zip.length - 22;
	while (end >= 0 && u32(end) !== 0x06054b50) end--;
	if (end < 0) throw new Error('no end of central directory');
	const out: { name: string; method: number; flags: number }[] = [];
	let at = u32(end + 16);
	for (let n = u16(end + 10); n > 0; n--) {
		if (u32(at) !== 0x02014b50) throw new Error('bad central directory');
		const nameLen = u16(at + 28);
		out.push({ name: new TextDecoder().decode(zip.subarray(at + 46, at + 46 + nameLen)), method: u16(at + 10), flags: u16(at + 8) });
		at += 46 + nameLen + u16(at + 30) + u16(at + 32);
	}
	return out;
}

describe('the fflate zips the Android reader reads', () => {
	for (const [file, build] of [
		['fflate-zipsync.crema.zip', buildZipSync],
		['fflate-zipdeflate.crema.zip', buildZipDeflate]
	] as const) {
		it(`${file} is what this build's fflate writes`, () => {
			const ours = build();
			const path = resolve(DIR, file);
			if (process.env.CREMA_WRITE_FIXTURE === '1') writeFileSync(path, ours);
			const fixture = new Uint8Array(readFileSync(path));
			expect(directory(fixture), STALE).toEqual(directory(ours));
			const theirs = unzipSync(fixture);
			const mine = unzipSync(ours);
			expect(Object.keys(theirs), STALE).toEqual(Object.keys(mine));
			for (const name of Object.keys(mine)) {
				expect(Buffer.from(theirs[name]).equals(Buffer.from(mine[name])), `${name}: ${STALE}`).toBe(true);
			}
		});
	}
});
