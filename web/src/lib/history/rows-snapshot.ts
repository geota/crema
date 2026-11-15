/**
 * `#lib/history/rows-snapshot.js` — a daily copy of every row, outside the
 * database (#310 P3w, SPEC §6).
 *
 * After the first save each day, every row (its fields, summary and as-run
 * profile, no series) is written, gzipped, to one file in the origin private
 * file system. A store with fewer rows than the last snapshot, less the
 * deletes by name since (Start empty, a recovery, a restore to fewer rows),
 * keeps the last one aside as the previous snapshot and writes its own beside
 * it, so there is never a day without a copy and the copy from before stays.
 * If the database is lost, this is one of the lost banner's ways back. Written
 * in chunks of {@link SNAPSHOT_CHUNK} rows with a yield between them, and to
 * a writable that only replaces the file when it closes, so a tab closed
 * part-way leaves yesterday's whole. Skipped where there is no private file
 * system.
 */
import { Gzip, gunzipSync, strFromU8, strToU8 } from 'fflate';
import { HISTORY_SNAPSHOT_NAME, HISTORY_SNAPSHOT_PREV_NAME } from './store-names';
import type { RowRecord } from './history-db';

export const SNAPSHOT_CHUNK = 500;
/** The local day the last snapshot was written. */
export const SNAPSHOT_DAY_KEY = 'crema.history.snapshotDay';
/** How many rows the last snapshot held. */
export const SNAPSHOT_ROWS_KEY = 'crema.history.snapshotRows';

/** The previous snapshot's row count and date, as `{ rows, at }`. */
export const SNAPSHOT_PREV_KEY = 'crema.history.snapshotPrev';

/** Which snapshot: today's, or the one kept aside when a smaller store's was written. */
export type SnapshotWhich = 'latest' | 'previous';

const fileOf = (which: SnapshotWhich): string => (which === 'previous' ? HISTORY_SNAPSHOT_PREV_NAME : HISTORY_SNAPSHOT_NAME);

function readPrevMeta(): { rows: number; at: number } | null {
	try {
		const v = JSON.parse(localStorage.getItem(SNAPSHOT_PREV_KEY) ?? 'null') as { rows?: unknown; at?: unknown } | null;
		return v && typeof v.rows === 'number' && typeof v.at === 'number' ? { rows: v.rows, at: v.at } : null;
	} catch {
		return null;
	}
}

/** One snapshot line: a row, with its as-run profile. */
export type SnapshotRow = RowRecord & { profile?: unknown };

/** How many rows the last snapshot held, or null for none. */
export function lastSnapshotRows(): number | null {
	try {
		const v = localStorage.getItem(SNAPSHOT_ROWS_KEY);
		const n = v == null ? NaN : Number(v);
		return Number.isFinite(n) ? n : null;
	} catch {
		return null;
	}
}

/**
 * `n` rows were deleted by name: the next snapshot may be that much smaller.
 * Kept with the count, so it holds across tabs and reloads. A store that is
 * smaller for any other reason (Start empty, a restore under way) keeps the
 * last snapshot, which may be the only way back.
 */
export function snapshotRowsDropped(n: number): void {
	const last = lastSnapshotRows();
	if (last === null || n <= 0) return;
	try {
		localStorage.setItem(SNAPSHOT_ROWS_KEY, String(Math.max(0, last - n)));
	} catch {
		// Then the next snapshot waits until the store has grown back.
	}
}

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
 * Before a smaller store's snapshot replaces the last one: keep the last one
 * as the previous snapshot, unless the previous one already holds more rows
 * (then that is the copy from before, and it stays).
 */
async function keepAside(dir: FileSystemDirectoryHandle): Promise<void> {
	let file: File;
	try {
		file = await (await dir.getFileHandle(HISTORY_SNAPSHOT_NAME)).getFile();
	} catch {
		return;
	}
	const rows = lastSnapshotRows() ?? 0;
	const prev = readPrevMeta();
	if (prev && prev.rows > rows) return;
	const bytes = new Uint8Array(await file.arrayBuffer());
	const writable = await (await dir.getFileHandle(HISTORY_SNAPSHOT_PREV_NAME, { create: true })).createWritable();
	try {
		await writable.write(bytes as BufferSource);
		await writable.close();
	} catch (e) {
		await writable.abort().catch(() => {});
		throw e;
	}
	try {
		localStorage.setItem(SNAPSHOT_PREV_KEY, JSON.stringify({ rows, at: file.lastModified }));
	} catch {
		// Its date reads as the copy's.
	}
}

/**
 * Write `rows` as the snapshot. With `keepPrevious` (the store is smaller
 * than the last snapshot), the last one is kept aside first. Whether it was
 * written; false with no private file system, or when the write failed
 * (yesterday's stays).
 */
export async function writeRowsSnapshot(
	rows: readonly SnapshotRow[],
	now: number,
	root: Root = defaultRoot,
	{ keepPrevious = false }: { keepPrevious?: boolean } = {}
): Promise<boolean> {
	const dir = await root();
	if (!dir) return false;
	try {
		if (keepPrevious) await keepAside(dir);
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
			localStorage.setItem(SNAPSHOT_ROWS_KEY, String(rows.length));
		} catch {
			// Written anyway; tomorrow's goes again.
		}
		return true;
	} catch {
		return false;
	}
}

/** What a snapshot holds, or null when there's none or it can't be read. */
export async function readRowsSnapshot(
	root: Root = defaultRoot,
	which: SnapshotWhich = 'latest'
): Promise<{ rows: SnapshotRow[]; at: number } | null> {
	const dir = await root();
	if (!dir) return null;
	try {
		const handle = await dir.getFileHandle(fileOf(which));
		const file = await handle.getFile();
		const text = strFromU8(gunzipSync(new Uint8Array(await file.arrayBuffer())));
		const rows = text
			.split('\n')
			.filter((l) => l.length > 0)
			.map((l) => JSON.parse(l) as SnapshotRow)
			.filter((r) => typeof r === 'object' && r !== null && typeof r.id === 'string');
		const at = which === 'previous' ? (readPrevMeta()?.at ?? file.lastModified) : file.lastModified;
		return { rows, at };
	} catch {
		return null;
	}
}
