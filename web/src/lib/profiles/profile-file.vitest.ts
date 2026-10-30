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
import { exportProfileFile, getProfileStore, importProfileFiles } from '#lib/profiles/index.js';

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

/** Import `files` the way the Profiles page does, into the real store. */
async function pageImport(...files: File[]) {
	const store = getProfileStore();
	return importProfileFiles(files, (p) => store.save(p));
}

describe('profile files cross shells (AC-F12.6, web)', () => {
	it("f12_6 Android's exports import on web", async () => {
		const result = await pageImport(file('android-profile.json'), file('android-profiles.json'));
		expect(result).toEqual({ imported: 5, errors: [] });
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
		await pageImport(file('android-profiles.json'));
		const store = getProfileStore();
		const names = ['My 7g', '7g basket', 'Blooming Allongé', 'A-Flow / default-dark'];
		const set = names.map((n) => store.all.find((p) => p.source === 'custom' && p.name === n)!);
		expect(exportProfileFile(set)).toBe(fixture('profiles/crema-profiles.jsonl'));
		expect(exportProfileFile(set.slice(0, 1))).toBe(fixture('profiles/crema-profile.json'));
	});

	it('f12_1 the old web exports, a de1app .tcl and a backup import on web too', async () => {
		const result = await pageImport(
			file('web-profile.json'),
			file('web-profiles.jsonl'),
			file('default.tcl', fixture('default.tcl')),
			file('web-backup.crema.jsonl', fixture('backup/web-backup.crema.jsonl'))
		);
		expect(result).toEqual({ imported: 6, errors: [] });
	});

	it('f12_3 what is not a profile is reported per item', async () => {
		const lines = fixture('profiles/web-profiles.jsonl').split('\n');
		lines.splice(1, 0, '{"name":"Groceries"}');
		const result = await pageImport(file('mixed.jsonl', lines.join('\n')));
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
