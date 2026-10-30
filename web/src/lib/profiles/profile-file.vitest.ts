/**
 * F12: profile files on web go through the core. The fixtures live in
 * `core/de1-domain/tests/fixtures/profiles/`: `android-profile.json` and
 * `android-profiles.json` are Android Crema's exports from before F12 (its
 * single export and "Export all"), `crema-profile.json` /
 * `crema-profiles.jsonl` the core's export of the Android set (regenerate them
 * from the core: `CREMA_REGEN_PROFILE_FIXTURES=1 cargo test -p de1-domain
 * profile_file`).
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import {
	exportProfileFile,
	getProfileStore,
	importProfileFiles,
	importSummary
} from '#lib/profiles/index.js';

const FIXTURES = path.resolve(process.cwd(), '../core/de1-domain/tests/fixtures');
const fixture = (name: string): string => readFileSync(path.resolve(FIXTURES, name), 'utf8');
const file = (name: string, text = fixture(`profiles/${name}`)): File => new File([text], name);

beforeEach(async () => {
	await initTestWasm();
	localStorage.clear();
	const store = getProfileStore();
	await store.ensureLoaded();
	store.clearAllCustom();
});

/**
 * Read `files` into the real store, every profile in them: no library to skip
 * against (the format tests; three of Android's fixtures are built-ins).
 */
async function readAll(...files: File[]) {
	const store = getProfileStore();
	return importProfileFiles(files, (p) => store.save(p));
}

/** Import `files` the way the Profiles page does, into the real store. */
async function pageImport(...files: File[]) {
	const store = getProfileStore();
	return importProfileFiles(
		files,
		(p) => store.save(p),
		() => [...store.all, ...store.hiddenBuiltinProfiles]
	);
}

describe('profile files cross shells (AC-F12.6, web)', () => {
	it("f12_6 Android's exports import on web", async () => {
		const result = await readAll(file('android-profile.json'), file('android-profiles.json'));
		expect(result).toEqual({ imported: 5, skipped: 0, errors: [] });
		const custom = getProfileStore().all.filter((p) => p.source === 'custom');
		expect(custom.map((p) => p.name).sort()).toEqual(
			['A-Flow / default-dark', 'Blooming Allongé', 'My 7g', 'My 7g', '7g basket'].sort()
		);
		for (const p of custom) {
			expect(p.tags).not.toContain('Built-in');
			expect(p.brewTemp).toBe(p.segments[0].temp);
		}
		const mine = custom.find((p) => p.name === 'My 7g')!;
		expect(mine.tags).toEqual(['Fixture', 'Small']);
		expect(mine.id).not.toMatch(/^profile:/);
	});

	it("f12_6 web's export of a set is the core's file, byte for byte", async () => {
		await readAll(file('android-profiles.json'));
		const store = getProfileStore();
		const names = ['My 7g', '7g basket', 'Blooming Allongé', 'A-Flow / default-dark'];
		const set = names.map((n) => store.all.find((p) => p.source === 'custom' && p.name === n)!);
		expect(exportProfileFile(set)).toBe(fixture('profiles/crema-profiles.jsonl'));
		expect(exportProfileFile(set.slice(0, 1))).toBe(fixture('profiles/crema-profile.json'));
	});

	it('f12_1 the old web exports, a de1app .tcl and a backup import on web too', async () => {
		const result = await readAll(
			file('web-profile.json'),
			file('web-profiles.jsonl'),
			file('default.tcl', fixture('default.tcl')),
			file('web-backup.crema.jsonl', fixture('backup/web-backup.crema.jsonl'))
		);
		expect(result).toEqual({ imported: 6, skipped: 0, errors: [] });
	});

	it('f12_3 what is not a profile is reported per item', async () => {
		const lines = fixture('profiles/web-profiles.jsonl').split('\n');
		lines.splice(1, 0, '{"name":"Groceries"}');
		const result = await readAll(file('mixed.jsonl', lines.join('\n')));
		expect(result.imported).toBe(3);
		expect(result.errors).toEqual([
			'Item 2 of mixed.jsonl: not a profile (it has neither steps nor segments)'
		]);
	});
});

const SRC = path.resolve('src');

/** Every shell source file (generated wasm glue + typeshare bindings and tests excluded). */
function shellSources(dir = SRC): string[] {
	return readdirSync(dir).flatMap((name) => {
		const full = path.join(dir, name);
		if (statSync(full).isDirectory()) return name === 'wasm' ? [] : shellSources(full);
		if (!/\.(ts|svelte)$/.test(name) || /\.(vitest|test)\.ts$/.test(name)) return [];
		if (name === 'crema-core.ts') return [];
		return [full];
	});
}

/** The files (relative to src/) containing `needle`. */
function filesWith(needle: RegExp): string[] {
	return shellSources()
		.filter((f) => needle.test(readFileSync(f, 'utf-8')))
		.map((f) => path.relative(SRC, f))
		.sort();
}

describe('re-import skips what the library has (F12b, web)', () => {
	it("f12b Android's exports: the built-ins and the repeated profile are skipped", async () => {
		// "Export all" holds "My 7g" and three built-ins; the single export is
		// "My 7g" again.
		const result = await pageImport(file('android-profiles.json'), file('android-profile.json'));
		expect(result).toEqual({ imported: 1, skipped: 4, errors: [] });
		expect(importSummary(result)).toEqual({
			kind: 'success',
			message: 'Imported 1 profile; 4 already in your library.'
		});
		const custom = getProfileStore().all.filter((p) => p.source === 'custom');
		expect(custom.map((p) => p.name)).toEqual(['My 7g']);
		const again = await pageImport(file('android-profiles.json'));
		expect(again).toEqual({ imported: 0, skipped: 4, errors: [] });
		expect(getProfileStore().all.filter((p) => p.source === 'custom')).toHaveLength(1);
	});

	it('f12b built-ins count, hidden ones too; titles match trimmed and case-insensitive', async () => {
		const store = getProfileStore();
		const builtin = store.all.find((p) => p.source === 'builtin')!;
		const renamed = { ...builtin, name: `  ${builtin.name.toUpperCase()} ` };
		const text = exportProfileFile([renamed]);
		store.delete(builtin.id);
		try {
			expect(store.hiddenBuiltinProfiles.map((p) => p.id)).toContain(builtin.id);
			expect(await pageImport(file('builtin.json', text))).toEqual({
				imported: 0,
				skipped: 1,
				errors: []
			});
		} finally {
			store.unhideBuiltin(builtin.id);
		}
		// Same title, other steps: a different profile.
		const changed = { ...builtin, segments: builtin.segments.slice(0, 1) };
		const other = await pageImport(file('changed.json', exportProfileFile([changed])));
		expect(other).toEqual({ imported: 1, skipped: 0, errors: [] });
	});

	it("f12b the banner: 'Imported 2 profiles; 3 already in your library.'", () => {
		expect(importSummary({ imported: 2, skipped: 3, errors: [] }).message).toBe(
			'Imported 2 profiles; 3 already in your library.'
		);
		expect(importSummary({ imported: 1, skipped: 0, errors: [] }).message).toBe('Imported 1 profile.');
		expect(importSummary({ imported: 2, skipped: 1, errors: ['Item 3 of x.jsonl: bad'] })).toEqual({
			kind: 'success',
			message: 'Imported 2 of 3; 1 already in your library. Item 3 of x.jsonl: bad'
		});
		expect(importSummary({ imported: 0, skipped: 0, errors: ['Nope'] })).toEqual({
			kind: 'error',
			message: 'Nope'
		});
		expect(importSummary({ imported: 0, skipped: 0, errors: [] })).toEqual({
			kind: 'error',
			message: 'No profiles imported.'
		});
	});
});

describe('one profile-file entry point (AC-F12.7, web)', () => {
	it('f12_7 no shell code reads or writes a profile file except through the core', () => {
		expect(
			filesWith(
				/import_v2_json_profile|import_legacy_tcl_profile|export_v2_json_profile|importV2JsonProfile|importLegacyTclProfile|exportV2JsonProfile/
			)
		).toEqual([]);
		expect(filesWith(/wasmImportProfileFile|wasmExportProfileFile/)).toEqual([
			'lib/profiles/profile-file.ts'
		]);
		// The Profiles page's buttons are checked by behaviour in
		// `routes/profiles/page-export.vitest.ts`.
	});
});
