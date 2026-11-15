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
 * day's copy is written and read back whole, the oldest written beyond
 * {@link SNAPSHOTS_KEPT} go, never the one just written. Each copy starts
 * with a header (its write order, time and row count), and only a copy that
 * reads back whole against it counts: an empty or cut-short file, from a tab
 * closed mid-write, isn't one of the kept copies and doesn't block its day.
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

/** A copy's first line: when it was written, in what order, and how many rows follow. */
interface SnapshotHeader {
	snapshot: { v: 1; seq: number; at: number; rows: number };
}

/** A copy that read back whole: its header's rows all there. */
interface Copy {
	name: string;
	seq: number;
	at: number;
	rows: SnapshotRow[];
}

/** One copy: whole, `bad` (empty, cut short or not a copy), or `unreadable` now. */
async function readCopy(dir: FileSystemDirectoryHandle, name: string): Promise<Copy | 'bad' | 'unreadable'> {
	let bytes: Uint8Array;
	try {
		bytes = new Uint8Array(await (await (await dir.getFileHandle(name)).getFile()).arrayBuffer());
	} catch {
		return 'unreadable';
	}
	try {
		const lines = strFromU8(gunzipSync(bytes))
			.split('\n')
			.filter((l) => l.length > 0);
		const head = (JSON.parse(lines[0] ?? 'null') as SnapshotHeader | null)?.snapshot;
		if (!head || typeof head.seq !== 'number' || typeof head.at !== 'number' || typeof head.rows !== 'number') return 'bad';
		const rows = lines.slice(1).map((l) => JSON.parse(l) as SnapshotRow);
		if (rows.length !== head.rows || rows.some((r) => typeof r !== 'object' || r === null || typeof r.id !== 'string')) return 'bad';
		return { name, seq: head.seq, at: head.at, rows };
	} catch {
		return 'bad';
	}
}

/**
 * Every daily copy that reads back whole, newest first by the order they were
 * written (not by name: a clock set back or forward names a day wrongly), and
 * the names of files that aren't whole copies.
 */
async function readCopies(dir: FileSystemDirectoryHandle): Promise<{ copies: Copy[]; bad: string[] }> {
	const names: string[] = [];
	for await (const name of (dir as unknown as { keys(): AsyncIterable<string> }).keys()) {
		if (DAILY.test(name)) names.push(name);
	}
	const copies: Copy[] = [];
	const bad: string[] = [];
	for (const name of names) {
		const c = await readCopy(dir, name);
		if (c === 'bad') bad.push(name);
		else if (c !== 'unreadable') copies.push(c);
	}
	copies.sort((a, b) => b.seq - a.seq || (a.name < b.name ? 1 : a.name > b.name ? -1 : 0));
	return { copies, bad };
}

/** Whether the day of `ms` has a whole copy. With no private file system, yes: there's nothing to write. */
export async function snapshotWritten(ms: number, root: Root = defaultRoot): Promise<boolean> {
	if ((await root()) === undefined) return true;
	const dir = await snapshotDir(root, false);
	if (!dir) return false;
	const c = await readCopy(dir, snapshotNameOf(ms));
	return c !== 'bad' && c !== 'unreadable';
}

const pause = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

/**
 * Write `rows` as the day's copy, unless that day has a whole one. It must
 * read back whole (this tab's, or another tab's of the same day); then the
 * whole copies beyond {@link SNAPSHOTS_KEPT}, oldest written first, go, never
 * the one just written, and so do files that aren't whole copies. Whether it
 * was written; false with no private file system, when the day has a copy
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
	let before: Copy[];
	try {
		before = (await readCopies(dir)).copies;
	} catch {
		return false;
	}
	if (before.some((c) => c.name === name)) return false;
	const seq = before.reduce((m, c) => Math.max(m, c.seq), 0) + 1;
	try {
		const parts: Uint8Array[] = [];
		const gz = new Gzip((chunk) => parts.push(chunk));
		const header: SnapshotHeader = { snapshot: { v: 1, seq, at: now, rows: rows.length } };
		gz.push(strToU8(JSON.stringify(header)));
		for (let i = 0; i < rows.length; i += SNAPSHOT_CHUNK) {
			const text = rows
				.slice(i, i + SNAPSHOT_CHUNK)
				.map((r) => JSON.stringify(r))
				.join('\n');
			gz.push(strToU8(`\n${text}`));
			await pause();
		}
		gz.push(new Uint8Array(0), true);
		const writable = await (await dir.getFileHandle(name, { create: true })).createWritable();
		try {
			for (const p of parts) await writable.write(p as BufferSource);
			await writable.close();
		} catch (e) {
			await writable.abort().catch(() => {});
			throw e;
		}
	} catch {
		// Not written; whatever the day's file holds is judged below, next time.
		return false;
	}
	// Verified: the day's file reads back whole.
	const back = await readCopy(dir, name);
	if (back === 'bad' || back === 'unreadable') {
		if (back === 'bad') await dir.removeEntry(name).catch(() => {});
		return false;
	}
	try {
		const { copies, bad } = await readCopies(dir);
		const keep = new Set([name, ...copies.slice(0, SNAPSHOTS_KEPT).map((c) => c.name)]);
		for (const c of copies) if (!keep.has(c.name)) await dir.removeEntry(c.name).catch(() => {});
		for (const b of bad) if (b !== name) await dir.removeEntry(b).catch(() => {});
	} catch {
		// Pruned after the next day's copy.
	}
	return true;
}

/** What a daily copy holds (the newest written when `name` is left out), or null when there's none or it isn't whole. */
export async function readRowsSnapshot(
	root: Root = defaultRoot,
	name?: string
): Promise<{ rows: SnapshotRow[]; at: number } | null> {
	const dir = await snapshotDir(root, false);
	if (!dir) return null;
	try {
		if (name === undefined) {
			const newest = (await readCopies(dir)).copies[0];
			return newest ? { rows: newest.rows, at: newest.at } : null;
		}
		if (!DAILY.test(name)) return null;
		const c = await readCopy(dir, name);
		return c === 'bad' || c === 'unreadable' ? null : { rows: c.rows, at: c.at };
	} catch {
		return null;
	}
}

/**
 * Every whole daily copy merged by id, the newest written winning a clash, in
 * the newest copy's order and then each older copy's own; null when there's none.
 */
export async function readAllRowsSnapshots(root: Root = defaultRoot): Promise<{ rows: SnapshotRow[]; at: number } | null> {
	const dir = await snapshotDir(root, false);
	if (!dir) return null;
	let copies: Copy[];
	try {
		copies = (await readCopies(dir)).copies;
	} catch {
		return null;
	}
	if (copies.length === 0) return null;
	const seen = new Set<string>();
	const rows: SnapshotRow[] = [];
	for (const c of copies) {
		for (const r of c.rows) {
			if (seen.has(r.id)) continue;
			seen.add(r.id);
			rows.push(r);
		}
	}
	return { rows, at: copies[0].at };
}

/** The whole daily copies there are, newest written first, each with its date and row count read from the copy. */
export async function listRowsSnapshots(root: Root = defaultRoot): Promise<SnapshotInfo[]> {
	const dir = await snapshotDir(root, false);
	if (!dir) return [];
	try {
		return (await readCopies(dir)).copies.map((c) => ({ name: c.name, at: c.at, rows: c.rows.length }));
	} catch {
		return [];
	}
}
