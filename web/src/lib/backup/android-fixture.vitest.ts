/**
 * A backup Android builds one line at a time (#310 P2a) restores here: the
 * fixture is written by Android's `streamBackupZip` (`BackupStreamTest`, with
 * `CREMA_WRITE_FIXTURE=1`) and read with the web restore's zip reader.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { strFromU8 } from 'fflate';
import { unzipImportSync } from '#lib/utils/import-limits.js';

const FIXTURE = resolve(process.cwd(), 'src/lib/backup/fixtures/android-streamed-photo.crema.zip');

describe('an Android-streamed backup', () => {
	it('reads with the restore’s reader: the lines, then the photo', () => {
		const entries = unzipImportSync(
			new Uint8Array(readFileSync(FIXTURE)),
			(name) => name === 'backup.jsonl' || name.startsWith('images/')
		);
		expect(Object.keys(entries)).toEqual(['backup.jsonl', 'images/bean-1']);
		const lines = strFromU8(entries['backup.jsonl']).split('\n');
		expect(JSON.parse(lines[0]).kind).toBe('crema-backup/v1');
		expect(lines.some((l) => l.includes('"kind":"shot"'))).toBe(true);
		const photo = entries['images/bean-1'];
		expect(photo.length).toBe(4096);
		expect(photo.every((b, i) => b === (i * 7919 + 13) % 251)).toBe(true);
	});
});
