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
import { Gunzip, Gzip, strToU8 } from 'fflate';
import { HISTORY_SNAPSHOT_NAME } from './store-names';
import type { HistoryDb, RowRecord } from './history-db';
import { readProfiles, type ProfilesOf } from './snapshot-profiles';
import type { LockManagerLike } from '#lib/restore/tab-safety.js';

/**
 * Rows a step writes or parses before it yields (#420 FC3-N2: 500 made
 * tasks of 369 ms at a 6× CPU slowdown).
 */
export const SNAPSHOT_CHUNK = 100;
/**
 * Bytes a step inflates before it yields, counted as they come out, not as
 * they go in: a day's copy inflates about 100 times (#421: 64 KB in made
 * 7 MB out, and tasks of 139 ms at 6×). A step ends at the first slice past it.
 */
const INFLATE_STEP = 64 * 1024;
/**
 * Compressed bytes pushed at once. Deflate inflates at most 1032 times over
 * (a run of one byte), and fflate's `Gunzip` hands out each push's output at
 * once, so one slice of 63 bytes gives at most 65,016 bytes. A step is then
 * under {@link INFLATE_STEP} before its last slice and at most 130,551 bytes
 * after it, whatever the data. At 512 bytes a slice could give 528 KB, and
 * where the slices fell in a copy (its header's time moves them) decided the
 * step: up to 137,972 bytes once on CI.
 */
export const INFLATE_SLICE = 63;
/** The most a step inflates: {@link INFLATE_STEP} less a byte, then one slice at deflate's ratio. */
export const INFLATE_STEP_MAX = INFLATE_STEP - 1 + 1032 * INFLATE_SLICE;
/** How many daily copies are kept. */
export const SNAPSHOTS_KEPT = 3;
/** The Web Lock a tab holds while it checks, writes, verifies and prunes the copies. */
export const SNAPSHOT_LOCK = 'crema-history-snapshot';

function defaultLocks(): LockManagerLike | null {
	return typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks
		? (navigator.locks as unknown as LockManagerLike)
		: null;
}

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

/** How much work ran between two yields, at most, for a test to bound. */
const steps = { rows: 0, bytes: 0, maxRows: 0, maxBytes: 0 };

/** Test seam: the most rows and bytes a step handled before yielding, since the last reset. */
export function snapshotStepsForTest(): { maxRows: number; maxBytes: number } {
	return { maxRows: steps.maxRows, maxBytes: steps.maxBytes };
}
export function resetSnapshotStepsForTest(): void {
	Object.assign(steps, { rows: 0, bytes: 0, maxRows: 0, maxBytes: 0 });
}

/** Yield to the page, and start counting the next step's work. */
async function step(): Promise<void> {
	steps.maxRows = Math.max(steps.maxRows, steps.rows);
	steps.maxBytes = Math.max(steps.maxBytes, steps.bytes);
	steps.rows = 0;
	steps.bytes = 0;
	await pause();
}

/**
 * Inflate `bytes` a slice at a time, yielding once a step has inflated
 * {@link INFLATE_STEP}, into its non-empty lines. The text is decoded and
 * split as it comes out, so no step decodes or splits the whole copy.
 */
export async function inflateLines(bytes: Uint8Array): Promise<string[]> {
	const lines: string[] = [];
	const dec = new TextDecoder();
	let carry = '';
	let ended = false;
	const gz = new Gunzip((chunk, final) => {
		steps.bytes += chunk.length;
		const parts = (carry + dec.decode(chunk, { stream: true })).split('\n');
		carry = parts.pop() ?? '';
		for (const l of parts) if (l.length > 0) lines.push(l);
		if (final) ended = true;
	});
	for (let i = 0; i < bytes.length; i += INFLATE_SLICE) {
		gz.push(bytes.subarray(i, i + INFLATE_SLICE), i + INFLATE_SLICE >= bytes.length);
		if (steps.bytes >= INFLATE_STEP) await step();
	}
	await step();
	if (!ended) throw new Error('cut short');
	carry += dec.decode();
	if (carry.length > 0) lines.push(carry);
	return lines;
}

/** One copy: whole, `bad` (empty, cut short or not a copy), or `unreadable` now. Read in yielding steps. */
async function readCopy(dir: FileSystemDirectoryHandle, name: string): Promise<Copy | 'bad' | 'unreadable'> {
	let bytes: Uint8Array;
	try {
		bytes = new Uint8Array(await (await (await dir.getFileHandle(name)).getFile()).arrayBuffer());
	} catch {
		return 'unreadable';
	}
	try {
		if (bytes.length === 0) return 'bad';
		const lines = await inflateLines(bytes);
		const head = (JSON.parse(lines[0] ?? 'null') as SnapshotHeader | null)?.snapshot;
		if (!head || typeof head.seq !== 'number' || typeof head.at !== 'number' || typeof head.rows !== 'number') return 'bad';
		const rows: SnapshotRow[] = [];
		for (let i = 1; i < lines.length; i++) {
			const r = JSON.parse(lines[i]) as SnapshotRow;
			if (typeof r !== 'object' || r === null || typeof r.id !== 'string') return 'bad';
			rows.push(r);
			steps.rows += 1;
			if (steps.rows >= SNAPSHOT_CHUNK) await step();
		}
		if (rows.length !== head.rows) return 'bad';
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

/** What the daily copy reads from the store. */
export type SnapshotSource = Pick<HistoryDb, 'rowIds' | 'rowsOf' | 'detailsOf'>;

/**
 * Every stored row with its as-run profile, in id order, as the day's copy
 * holds them. Read {@link SNAPSHOT_CHUNK} rows at a time with a yield between
 * (#421: all 5,000 at once made one long task). The ids are taken first: a
 * row stored throughout is in the copy; one added after goes in the next
 * day's, and one deleted before its turn is left out, as if the copy had
 * been read a moment later. `profilesOf` reads the profiles: from the rows'
 * details here by default, from a worker in the app ({@link ProfilesOf}).
 */
export async function snapshotLines(
	db: SnapshotSource,
	profilesOf: ProfilesOf = (ids) => readProfiles(db, ids)
): Promise<SnapshotRow[]> {
	const ids = await db.rowIds();
	const lines: SnapshotRow[] = [];
	for (let i = 0; i < ids.length; i += SNAPSHOT_CHUNK) {
		const part = ids.slice(i, i + SNAPSHOT_CHUNK);
		const rows = await db.rowsOf(part);
		const here = part.filter((id) => rows.has(id));
		const profiles = await profilesOf(here);
		for (const id of here) lines.push({ ...rows.get(id)!, profile: profiles.get(id) ?? null });
		await pause();
	}
	return lines;
}

/**
 * Write `rows` as the day's copy, unless that day has a whole one. It must
 * read back whole; then the whole copies beyond {@link SNAPSHOTS_KEPT},
 * oldest written first, go, never the one just written, and so do files that
 * aren't whole copies. All of it under {@link SNAPSHOT_LOCK}, so two tabs
 * never write, check or prune at once. Whether it was written; false with no
 * private file system, when the day has a copy already, or when the write or
 * the read-back failed (every older copy stays).
 */
export async function writeRowsSnapshot(
	rows: readonly SnapshotRow[],
	now: number,
	root: Root = defaultRoot,
	locks: LockManagerLike | null = defaultLocks()
): Promise<boolean> {
	if (!locks) return writeNow(rows, now, root);
	let ran = false;
	try {
		return (await locks.request(SNAPSHOT_LOCK, { mode: 'exclusive' }, () => {
			ran = true;
			return writeNow(rows, now, root);
		})) as boolean;
	} catch {
		// No lock to be had (an old browser's stub): write anyway, as a tab alone.
		return ran ? false : writeNow(rows, now, root);
	}
}

async function writeNow(rows: readonly SnapshotRow[], now: number, root: Root): Promise<boolean> {
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
			const part = rows.slice(i, i + SNAPSHOT_CHUNK);
			steps.rows += part.length;
			gz.push(strToU8(`\n${part.map((r) => JSON.stringify(r)).join('\n')}`));
			await step();
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
