/**
 * `#lib/profiles/profile-file` — profile files, read and written by the core
 * (`de1_domain::profile_file`), so web and Android hand out and accept the
 * same files.
 *
 * {@link importProfileFile} reads any profile file a user may hold — community
 * v2 `.json`, a `.jsonl` bundle, a de1app `.tcl`, a Crema backup, an old
 * Android Crema export — detecting the format from the content, and returns
 * fresh custom profiles (new id, brew temp from the first step) plus what
 * didn't import. {@link exportProfileFile} writes community v2: one profile
 * pretty-printed, several as JSON lines.
 */

import {
	exportProfileFile as wasmExportProfileFile,
	importProfileFile as wasmImportProfileFile
} from '#lib/wasm/de1_wasm.js';
import type { ProfileFileError } from '#lib/core/crema-core.js';
import { readImportText } from '#lib/utils/import-limits.js';
import type { CremaProfile } from './model';

export type { ProfileFileError };

/** What {@link importProfileFile} read: profiles ready to save, and errors. */
export interface ProfileFileImport {
	/** Fresh custom profiles, in file order. */
	profiles: CremaProfile[];
	/** What didn't import, in file order (`item` = 1-based line or element). */
	errors: ProfileFileError[];
	/** Profiles skipped as already in the library (same title and steps). */
	skipped_duplicates: number;
}

/**
 * Read a profile file's text, skipping the profiles already in `library` (the
 * whole library: custom profiles and built-ins, hidden ones included) — the
 * same title, trimmed and case-insensitive, and the same steps (core
 * `import_profile_file_into`).
 */
export function importProfileFile(
	text: string,
	library: readonly CremaProfile[] = []
): ProfileFileImport {
	return JSON.parse(wasmImportProfileFile(text, JSON.stringify(library))) as ProfileFileImport;
}

/** Write `profiles` as a profile file: `.json` for one, `.jsonl` for several. */
export function exportProfileFile(profiles: readonly CremaProfile[]): string {
	return wasmExportProfileFile(JSON.stringify(profiles));
}

/** "Item 3 of x.jsonl: …" — a file error as the import banner shows it. */
export function describeProfileFileError(fileName: string, error: ProfileFileError): string {
	return error.item != null
		? `Item ${error.item} of ${fileName}: ${error.message}`
		: `${fileName}: ${error.message}`;
}

/**
 * The Profiles page's Import: read each chosen file (within the import size
 * limits), let the core read it, and `save` every profile it returns. Returns
 * how many profiles were saved and every problem, worded for the banner.
 */
export async function importProfileFiles(
	files: ArrayLike<File>,
	save: (profile: CremaProfile) => void,
	library: () => readonly CremaProfile[] = () => []
): Promise<{ imported: number; skipped: number; errors: string[] }> {
	let imported = 0;
	let skipped = 0;
	const errors: string[] = [];
	for (const file of Array.from(files)) {
		const name = file.name;
		let text: string;
		try {
			text = await readImportText(file);
		} catch (e) {
			errors.push(`Could not read ${name}: ${e instanceof Error ? e.message : String(e)}`);
			continue;
		}
		// Read the library per file: an earlier file's profiles count too.
		const result = importProfileFile(text, library());
		for (const profile of result.profiles) save(profile);
		imported += result.profiles.length;
		skipped += result.skipped_duplicates;
		errors.push(...result.errors.map((e) => describeProfileFileError(name, e)));
	}
	return { imported, skipped, errors };
}

/**
 * The Profiles page's banner after an import: "Imported 2 profiles; 3 already
 * in your library.", with the first error when some didn't import (Android
 * words it the same).
 */
export function importSummary(r: { imported: number; skipped: number; errors: readonly string[] }): {
	kind: 'success' | 'error';
	message: string;
} {
	const plural = (n: number) => `${n} profile${n === 1 ? '' : 's'}`;
	const imported =
		r.errors.length === 0 ? `Imported ${plural(r.imported)}` : `Imported ${r.imported} of ${r.imported + r.errors.length}`;
	const skipped = r.skipped > 0 ? `; ${r.skipped} already in your library` : '';
	if (r.imported === 0 && r.skipped === 0) {
		return { kind: 'error', message: r.errors[0] ?? 'No profiles imported.' };
	}
	const tail = r.errors.length > 0 ? ` ${r.errors[0]}` : '';
	return { kind: 'success', message: `${imported}${skipped}.${tail}` };
}
