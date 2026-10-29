import { describe, expect, it } from 'vitest';
import { strToU8, zipSync } from 'fflate';
import {
	IMPORT_LIMITS,
	ImportTooLargeError,
	assertImportSize,
	readImportText,
	unzipImport,
	unzipImportSync,
	type ImportLimits
} from './import-limits';
import { restoreBackupData } from '#lib/backup/index.js';

// Review 05 P3-2: crafted files and zip bombs must fail cleanly, not crash the tab.

const small: ImportLimits = { maxFileBytes: 64 * 1024, maxEntryBytes: 1024, maxTotalBytes: 2048, maxEntries: 3 };

/** Rewrite every declared uncompressed size in a zip (local + central headers). */
function declareSize(zip: Uint8Array, size: number): Uint8Array {
	const out = zip.slice();
	const dv = new DataView(out.buffer);
	for (let i = 0; i + 30 < out.length; i++) {
		const sig = dv.getUint32(i, true);
		if (sig === 0x04034b50) dv.setUint32(i + 22, size, true);
		else if (sig === 0x02014b50) dv.setUint32(i + 24, size, true);
	}
	return out;
}

describe('import limits', () => {
	it('refuses a file over the cap before reading it', async () => {
		expect(() => assertImportSize(IMPORT_LIMITS.maxFileBytes + 1)).toThrow(ImportTooLargeError);
		const file = new File(['x'.repeat(100)], 'big.json');
		await expect(readImportText(file, { ...small, maxFileBytes: 10 })).rejects.toThrow(
			/"big.json" is 1 MB\. Crema imports files up to 1 MB/
		);
	});

	it('inflates only the entries the caller wants', () => {
		const zip = zipSync({ 'backup.jsonl': strToU8('a'), 'junk.bin': new Uint8Array(5000) });
		expect(Object.keys(unzipImportSync(zip, (n) => n.endsWith('.jsonl'), small))).toEqual(['backup.jsonl']);
	});

	it('rejects an entry that unpacks past the per-entry cap (a zip bomb)', () => {
		const zip = zipSync({ 'backup.jsonl': new Uint8Array(4096) }); // compresses to a few bytes
		expect(zip.length).toBeLessThan(small.maxEntryBytes);
		expect(() => unzipImportSync(zip, () => true, small)).toThrow(/unpacks to .*the limit is/);
	});

	it('rejects archives over the total or entry-count caps', async () => {
		const many = zipSync(Object.fromEntries([1, 2, 3, 4].map((i) => [`images/${i}`, new Uint8Array(10)])));
		expect(() => unzipImportSync(many, () => true, small)).toThrow(/more than 3 files/);
		const heavy = zipSync({ a: new Uint8Array(1000), b: new Uint8Array(1000), c: new Uint8Array(1000) });
		await expect(unzipImport(heavy, () => true, small)).rejects.toThrow(/unpacks to more than/);
	});

	it('never inflates past a declared size that understates the content', () => {
		const zip = declareSize(zipSync({ 'backup.jsonl': new Uint8Array(4096).fill(65) }), 16);
		const files = unzipImportSync(zip, () => true, small);
		expect(files['backup.jsonl'].length).toBe(16);
	});

	it('restoreBackupData rejects an oversized backup zip with a clear error', async () => {
		const bomb = declareSize(zipSync({ 'backup.jsonl': strToU8('{"kind":"crema-backup/v1"}') }), 900 * 1024 * 1024);
		await expect(restoreBackupData(bomb, 'merge')).rejects.toThrow(ImportTooLargeError);
		await expect(restoreBackupData(bomb, 'merge')).rejects.toThrow(/backup\.jsonl.*unpacks to 900 MB/);
	});
});
