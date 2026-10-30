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
}

/** Read a profile file's text. */
export function importProfileFile(text: string): ProfileFileImport {
	return JSON.parse(wasmImportProfileFile(text)) as ProfileFileImport;
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
	save: (profile: CremaProfile) => void
): Promise<{ imported: number; errors: string[] }> {
	let imported = 0;
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
		const result = importProfileFile(text);
		for (const profile of result.profiles) save(profile);
		imported += result.profiles.length;
		errors.push(...result.errors.map((e) => describeProfileFileError(name, e)));
	}
	return { imported, errors };
}
