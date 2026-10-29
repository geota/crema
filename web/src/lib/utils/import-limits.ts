/**
 * `#lib/utils/import-limits.js` — size caps for files and zip archives the user
 * imports (backup restore, bean import, profile / shot import, capture replay).
 *
 * A crafted file must fail with a clear message rather than exhaust memory and
 * crash the tab. Two layers:
 *
 *  - **File size.** Checked from `Blob.size` before a single byte is read.
 *  - **Zip contents.** fflate inflates each entry into a buffer of the size
 *    the archive DECLARES (it never grows past it), so capping the declared
 *    `originalSize` per entry, the running total and the entry count bounds
 *    memory, even for a zip bomb that lies about its sizes. Only entries the
 *    caller asks for are inflated at all.
 *
 * The same numbers are used by the Android shell (`persist/ImportLimits.kt`).
 */

import { unzip, unzipSync, type UnzipFileInfo, type Unzipped } from 'fflate';

const MiB = 1024 * 1024;

export interface ImportLimits {
	/** Largest file any import reads. */
	maxFileBytes: number;
	/** Largest single zip entry that may be inflated. */
	maxEntryBytes: number;
	/** Total inflated bytes across the accepted entries of one zip. */
	maxTotalBytes: number;
	/** Most entries one zip may inflate. */
	maxEntries: number;
}

export const IMPORT_LIMITS: ImportLimits = {
	maxFileBytes: 200 * MiB,
	maxEntryBytes: 100 * MiB,
	maxTotalBytes: 400 * MiB,
	maxEntries: 10_000
};

/** An import refused because it is larger than {@link IMPORT_LIMITS} allow. */
export class ImportTooLargeError extends Error {
	override name = 'ImportTooLargeError';
}

function mb(bytes: number): string {
	return `${Math.ceil(bytes / MiB)} MB`;
}

/** Throw {@link ImportTooLargeError} when `size` exceeds the file cap. */
export function assertImportSize(
	size: number,
	what = 'That file',
	limits: ImportLimits = IMPORT_LIMITS
): void {
	if (size > limits.maxFileBytes) {
		throw new ImportTooLargeError(
			`${what} is ${mb(size)}. Crema imports files up to ${mb(limits.maxFileBytes)}.`
		);
	}
}

/** `file.text()` after the size check. */
export async function readImportText(file: Blob, limits: ImportLimits = IMPORT_LIMITS): Promise<string> {
	assertImportSize(file.size, fileLabel(file), limits);
	return file.text();
}

/** `file.arrayBuffer()` after the size check. */
export async function readImportBytes(
	file: Blob,
	limits: ImportLimits = IMPORT_LIMITS
): Promise<Uint8Array> {
	assertImportSize(file.size, fileLabel(file), limits);
	return new Uint8Array(await file.arrayBuffer());
}

function fileLabel(file: Blob): string {
	const name = (file as Partial<File>).name;
	return name ? `"${name}"` : 'That file';
}

/**
 * A stateful fflate `filter`: admits only entries `accept` wants, within the
 * caps. It never throws inside fflate; the first violation is recorded,
 * every later entry is skipped, and {@link ZipBudget.check} throws it.
 */
class ZipBudget {
	private entries = 0;
	private total = 0;
	private error: ImportTooLargeError | null = null;

	constructor(
		private readonly accept: (name: string) => boolean,
		private readonly limits: ImportLimits
	) {}

	readonly filter = (info: UnzipFileInfo): boolean => {
		if (this.error || !this.accept(info.name)) return false;
		// A stored (uncompressed) entry's bytes are a slice of the archive; its
		// declared size is still what we cap.
		const size = Math.max(info.originalSize, info.compression === 0 ? info.size : 0);
		if (++this.entries > this.limits.maxEntries) {
			this.error = new ImportTooLargeError(
				`The archive has more than ${this.limits.maxEntries} files to import.`
			);
			return false;
		}
		if (size > this.limits.maxEntryBytes) {
			this.error = new ImportTooLargeError(
				`"${info.name}" in the archive unpacks to ${mb(size)}; the limit is ${mb(this.limits.maxEntryBytes)}.`
			);
			return false;
		}
		this.total += size;
		if (this.total > this.limits.maxTotalBytes) {
			this.error = new ImportTooLargeError(
				`The archive unpacks to more than ${mb(this.limits.maxTotalBytes)}, too large to import.`
			);
			return false;
		}
		return true;
	};

	check(): void {
		if (this.error) throw this.error;
	}
}

/** `unzipSync` limited to the entries `accept` wants, within the caps. */
export function unzipImportSync(
	bytes: Uint8Array,
	accept: (name: string) => boolean,
	limits: ImportLimits = IMPORT_LIMITS
): Unzipped {
	assertImportSize(bytes.length, 'The archive', limits);
	const budget = new ZipBudget(accept, limits);
	const files = unzipSync(bytes, { filter: budget.filter });
	budget.check();
	return files;
}

/** Async `unzip` limited to the entries `accept` wants, within the caps. */
export function unzipImport(
	bytes: Uint8Array,
	accept: (name: string) => boolean,
	limits: ImportLimits = IMPORT_LIMITS
): Promise<Unzipped> {
	return new Promise<Unzipped>((resolve, reject) => {
		try {
			assertImportSize(bytes.length, 'The archive', limits);
			const budget = new ZipBudget(accept, limits);
			unzip(bytes, { filter: budget.filter }, (err, data) => {
				if (err) return reject(err);
				try {
					budget.check();
					resolve(data);
				} catch (e) {
					reject(e);
				}
			});
		} catch (e) {
			reject(e);
		}
	});
}
