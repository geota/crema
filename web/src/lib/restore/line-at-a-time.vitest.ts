import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
	BackupImporter,
	BackupScanner,
	backupMaxLineBytes,
	importBackupBytes,
	scanBackupBytes
} from '#lib/wasm/de1_wasm.js';

/**
 * The core's scanner and importer fed one line at a time (#310 P2w), over the
 * wasm bridge: the same manifest and records as the whole-file calls, on both
 * shells' fixtures.
 */
const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const fixtures = ['web-backup.crema.jsonl', 'android-backup.crema.jsonl'].map(
	(name) => [name, new Uint8Array(readFileSync(`${FIXTURES}/${name}`))] as const
);

function linesOf(bytes: Uint8Array): Uint8Array[] {
	const out: Uint8Array[] = [];
	let start = 0;
	for (let i = 0; i <= bytes.length; i++) {
		if (i === bytes.length || bytes[i] === 0x0a) {
			out.push(bytes.subarray(start, i));
			start = i + 1;
		}
	}
	return out;
}

describe('a backup a line at a time, through wasm', () => {
	for (const [name, bytes] of fixtures) {
		it(`${name}: the scanner gives the whole scan's manifest`, () => {
			const scanner = new BackupScanner();
			for (const line of linesOf(bytes)) scanner.pushLine(line);
			expect(JSON.parse(scanner.finish('["b1"]'))).toEqual(JSON.parse(scanBackupBytes(bytes, '["b1"]')));
		});

		it(`${name}: the importer gives the whole import's records, in its line shape`, () => {
			const importer = new BackupImporter();
			const records = linesOf(bytes)
				.map((line) => importer.pushLine(line))
				.filter((r): r is string => r !== undefined)
				.map((r) => JSON.parse(r) as Record<string, unknown>);
			expect(importer.hasHeader()).toBe(true);
			const whole = JSON.parse(importBackupBytes(bytes)) as Record<string, unknown>;
			const field = {
				shot: 'shot',
				bean: 'bean',
				roaster: 'roaster',
				recipe: 'recipe',
				brewMethod: 'method',
				profile: 'profile'
			} as const;
			const list = {
				shot: 'shots',
				bean: 'beans',
				roaster: 'roasters',
				recipe: 'recipes',
				brewMethod: 'customMethods',
				profile: 'profiles'
			} as const;
			for (const [line, key] of Object.entries(list)) {
				const got = records.filter((r) => r.line === line).map((r) => r[field[line as keyof typeof field]]);
				expect(got, key).toEqual(whole[key]);
			}
			for (const r of records.filter((r) => r.line === 'singleton')) {
				expect(r.value, String(r.kind)).toEqual(whole[r.kind as string]);
			}
		});
	}

	it('a line too long to read is skipped, and counted as the scan would count it', () => {
		const [, bytes] = fixtures[0];
		const lines = linesOf(bytes);
		const scanner = new BackupScanner();
		const importer = new BackupImporter();
		scanner.pushOversizedLine();
		importer.pushOversizedLine();
		for (const line of lines) {
			scanner.pushLine(line);
			importer.pushLine(line);
		}
		const manifest = JSON.parse(scanner.finish('[]')) as {
			headerDamaged: boolean;
			badLines: number;
		};
		expect(manifest.headerDamaged).toBe(true);
		expect(importer.hasHeader()).toBe(false);
		expect(backupMaxLineBytes()).toBe(16 * 1024 * 1024);
	});

	it('the scanner refuses photo ids that aren’t a string array, rather than dropping them', () => {
		const scanner = new BackupScanner();
		for (const line of linesOf(fixtures[0][1])) scanner.pushLine(line);
		expect(() => scanner.finish('{"not": "a list"}')).toThrow(/photos/);
	});
});
