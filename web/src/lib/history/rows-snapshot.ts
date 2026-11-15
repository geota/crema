/**
 * `#lib/history/rows-snapshot.js` — daily copies of every row, outside the
 * database (#310 P3w, SPEC §6).
 *
 * After the first save each day, every row (its fields, summary and as-run
 * profile, no series) is written, gzipped, to that day's file,
 * `rows-YYYYMMDD.jsonl.gz`, in the `crema-history-snapshot` directory of the
 * origin private file system. A day's file, once there, is never written
 * again, and nothing else ever deletes or overwrites one: a recovery that
 * leaves the store smaller only means the next day's copy is smaller. Once a
 * day's copy is written and read back whole, the oldest beyond
 * {@link SNAPSHOTS_KEPT} go.
 *
 * If the database is lost, each copy is one of the lost banner's ways back,
 * newest first, with the date and row count read from the copy itself.
 * Written in chunks of {@link SNAPSHOT_CHUNK} rows with a yield between them,
 * to a writable that only lands when it closes, so a tab closed part-way
 * leaves no half copy. Skipped where there is no private file system.
 */
import { Gzip, gunzipSync, strFromU8, strToU8 } from 'fflate';
import { HISTORY_SNAPSHOT_NAME } from './store-names';
import type { RowRecord } from './history-db';

export const SNAPSHOT_CHUNK = 500;
/** How many daily copies are kept. */
export const SNAPSHOTS_KEPT = 3;

/** One snapshot line: a row, with its as-run profile. */
export type SnapshotRow = RowRecord & { profile?: unknown };

/** A daily copy there is to recover: its file, when it was written, and its rows. */
export interface SnapshotInfo {
	name: string;
	at: number;
	rows: number;
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

/** The copies' directory; with `create`, made if missing. Undefined when there's none. */
async function snapshotDir(root: Root, create: boolean): Promise<FileSystemDirectoryHandle | undefined> {
	const top = await root();
	if (!top) return undefined;
	try {
		return await top.getDirectoryHandle(HISTORY_SNAPSHOT_NAME, { create });
	} catch {
		return undefined;
	}
}

/** Today, as the local date. */
export function dayOf(ms: number): string {
	const d = new Date(ms);
	return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}

/** The copy's file for the local day of `ms`. */
export function snapshotNameOf(ms: number): string {
	const d = new Date(ms);
	const pad = (n: number) => String(n).padStart(2, '0');
	return `rows-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}.jsonl.gz`;
}

const DAILY = /^rows-\d{8}\.jsonl\.gz$/;

/** The daily copies' names, newest first. */
async function listNames(dir: FileSystemDirectoryHandle): Promise<string[]> {
	const names: string[] = [];
	for await (const name of (dir as unknown as { keys(): AsyncIterable<string> }).keys()) {
		if (DAILY.test(name)) names.push(name);
	}
	return names.sort().reverse();
}

async function exists(dir: FileSystemDirectoryHandle, name: string): Promise<boolean> {
	try {
		await dir.getFileHandle(name);
		return true;
	} catch {
		return false;
	}
}

/** Whether the day of `ms` has its copy. With no private file system, yes: there's nothing to write. */
export async function snapshotWritten(ms: number, root: Root = defaultRoot): Promise<boolean> {
	if ((await root()) === undefined) return true;
	const dir = await snapshotDir(root, false);
	return dir ? exists(dir, snapshotNameOf(ms)) : false;
}

const pause = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

function parse(bytes: Uint8Array): SnapshotRow[] {
	return strFromU8(gunzipSync(bytes))
		.split('\n')
		.filter((l) => l.length > 0)
		.map((l) => JSON.parse(l) as SnapshotRow)
		.filter((r) => typeof r === 'object' && r !== null && typeof r.id === 'string');
}

/**
 * Write `rows` as the day's copy, unless that day has one. Once it reads back
 * whole, the copies beyond {@link SNAPSHOTS_KEPT} go, oldest first. Whether
 * it was written; false with no private file system, when the day has a copy
 * already, or when the write or the read-back failed (every older copy stays).
 */
export async function writeRowsSnapshot(
	rows: readonly SnapshotRow[],
	now: number,
	root: Root = defaultRoot
): Promise<boolean> {
	const dir = await snapshotDir(root, true);
	if (!dir) return false;
	const name = snapshotNameOf(now);
	if (await exists(dir, name)) return false;
	let made = false;
	try {
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
		const handle = await dir.getFileHandle(name, { create: true });
		made = true;
		const writable = await handle.createWritable();
		try {
			for (const p of parts) await writable.write(p as BufferSource);
			await writable.close();
		} catch (e) {
			await writable.abort().catch(() => {});
			throw e;
		}
		// Verified: every row back, in order.
		const back = parse(new Uint8Array(await (await handle.getFile()).arrayBuffer()));
		if (back.length !== rows.length || back.some((r, i) => r.id !== rows[i].id)) {
			throw new Error('The daily copy didn’t read back whole.');
		}
	} catch {
		// Today's, unfinished or unreadable, goes; every older copy stays.
		if (made) await dir.removeEntry(name).catch(() => {});
		return false;
	}
	try {
		for (const old of (await listNames(dir)).slice(SNAPSHOTS_KEPT)) await dir.removeEntry(old).catch(() => {});
	} catch {
		// Pruned after the next day's copy.
	}
	return true;
}

/** What a daily copy holds (the newest when `name` is left out), or null when there's none or it can't be read. */
export async function readRowsSnapshot(
	root: Root = defaultRoot,
	name?: string
): Promise<{ rows: SnapshotRow[]; at: number } | null> {
	const dir = await snapshotDir(root, false);
	if (!dir) return null;
	try {
		const which = name ?? (await listNames(dir))[0];
		if (!which || !DAILY.test(which)) return null;
		const file = await (await dir.getFileHandle(which)).getFile();
		return { rows: parse(new Uint8Array(await file.arrayBuffer())), at: file.lastModified };
	} catch {
		return null;
	}
}

/** The daily copies there are, newest first, each with its date and row count read from the copy. */
export async function listRowsSnapshots(root: Root = defaultRoot): Promise<SnapshotInfo[]> {
	const dir = await snapshotDir(root, false);
	if (!dir) return [];
	let names: string[];
	try {
		names = await listNames(dir);
	} catch {
		return [];
	}
	const out: SnapshotInfo[] = [];
	for (const name of names) {
		const snap = await readRowsSnapshot(root, name);
		if (snap) out.push({ name, at: snap.at, rows: snap.rows.length });
	}
	return out;
}
