/**
 * `#lib/history/rows-snapshot.js` — a daily copy of every row, outside the
 * database (#310 P3w, SPEC §6).
 *
 * After the first save each day, every row (its fields and summary, no
 * series) is written, gzipped, to one file in the origin private file system.
 * If the database is lost, this is one of the lost banner's ways back. Written
 * in chunks of {@link SNAPSHOT_CHUNK} rows with a yield between them, and to
 * a writable that only replaces the file when it closes, so a tab closed
 * part-way leaves yesterday's whole. Skipped where there is no private file
 * system.
 */
import { Gzip, gunzipSync, strFromU8, strToU8 } from 'fflate';
import { HISTORY_SNAPSHOT_NAME } from './store-names';
import type { RowRecord } from './history-db';

export const SNAPSHOT_CHUNK = 500;
/** The local day the last snapshot was written. */
export const SNAPSHOT_DAY_KEY = 'crema.history.snapshotDay';

type Root = () => Promise<FileSystemDirectoryHandle | undefined>;

async function defaultRoot(): Promise<FileSystemDirectoryHandle | undefined> {
	if (typeof navigator === 'undefined' || typeof navigator.storage?.getDirectory !== 'function') return undefined;
	try {
		return await navigator.storage.getDirectory();
	} catch {
		return undefined;
	}
}

/** Today, as the local date. */
export function dayOf(ms: number): string {
	const d = new Date(ms);
	return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}

/** Whether today's snapshot is still to write. */
export function snapshotDue(now: number): boolean {
	try {
		return localStorage.getItem(SNAPSHOT_DAY_KEY) !== dayOf(now);
	} catch {
		return false;
	}
}

const pause = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/**
 * Write `rows` as the snapshot. Whether it was written; false with no private
 * file system, or when the write failed (yesterday's stays).
 */
export async function writeRowsSnapshot(
	rows: readonly RowRecord[],
	now: number,
	root: Root = defaultRoot
): Promise<boolean> {
	const dir = await root();
	if (!dir) return false;
	try {
		const handle = await dir.getFileHandle(HISTORY_SNAPSHOT_NAME, { create: true });
		const parts: Uint8Array[] = [];
		const gz = new Gzip((chunk) => parts.push(chunk));
		for (let i = 0; i < rows.length; i += SNAPSHOT_CHUNK) {
			const text = rows
				.slice(i, i + SNAPSHOT_CHUNK)
				.map((r) => JSON.stringify(r))
				.join('\n');
			gz.push(strToU8(i === 0 ? text : `\n${text}`));
			await pause();
		}
		gz.push(new Uint8Array(0), true);
		const writable = await handle.createWritable();
		try {
			for (const p of parts) await writable.write(p as BufferSource);
			await writable.close();
		} catch (e) {
			await writable.abort().catch(() => {});
			throw e;
		}
		try {
			localStorage.setItem(SNAPSHOT_DAY_KEY, dayOf(now));
		} catch {
			// Written anyway; tomorrow's goes again.
		}
		return true;
	} catch {
		return false;
	}
}

/** What the snapshot holds, or null when there's none or it can't be read. */
export async function readRowsSnapshot(
	root: Root = defaultRoot
): Promise<{ rows: RowRecord[]; at: number } | null> {
	const dir = await root();
	if (!dir) return null;
	try {
		const handle = await dir.getFileHandle(HISTORY_SNAPSHOT_NAME);
		const file = await handle.getFile();
		const text = strFromU8(gunzipSync(new Uint8Array(await file.arrayBuffer())));
		const rows = text
			.split('\n')
			.filter((l) => l.length > 0)
			.map((l) => JSON.parse(l) as RowRecord)
			.filter((r) => typeof r === 'object' && r !== null && typeof r.id === 'string');
		return { rows, at: file.lastModified };
	} catch {
		return null;
	}
}
