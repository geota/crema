/**
 * `#lib/history/curve-archive.js` — every shot's curve, a second time, outside
 * the database (#310 P4w, owner decision on REVIEW-420 S1; SPEC §4, §6).
 *
 * The daily copies hold rows and profiles, not curves. So that a store lost
 * under the app gives back curves too, each shot's curve (its machine
 * samples, and a brew's scale series) is written once to its own gzipped
 * file, `<id>.json.gz` in the `crema-history-curves` directory of the origin
 * private file system, when it first lands in the store. A curve never
 * changes after that, so a file is never rewritten; one that is there and
 * not empty is left alone.
 *
 * - A file is removed only when its shot is deleted, after the store's
 *   delete has landed.
 * - A write that fails (quota, most likely) is tried again later. It never
 *   holds up a save and never touches the store.
 * - Each file carries its id, its counts and a hash of its curve. One that
 *   doesn't read back whole against them is corrupt: skipped, logged, never
 *   used.
 * - A directory, so a factory reset removes it whole.
 *
 * Compression runs through `CompressionStream` where there is one, which
 * the browser does off the main thread.
 */
import { gunzipSync, gzipSync, strFromU8, strToU8 } from 'fflate';
import type { TimedSample } from '#lib/core/index.js';
import type { BrewSeries } from '#lib/core/crema-core.js';
import { HISTORY_CURVES_NAME } from './store-names';
import { hashText } from './migrate';

/** The format a file holds. */
const ARCHIVE_VERSION = 1;
const SUFFIX = '.json.gz';

/** A shot's curve as the archive holds it. */
export interface ArchivedCurve {
	samples: TimedSample[];
	brewSeries: BrewSeries | null;
}

interface ArchiveFile {
	v: number;
	id: string;
	sampleCount: number;
	brewSampleCount: number;
	/** A hash of what's stored: `cols` (or `samples`) and `brewSeries`. */
	sum: string;
	/** The samples by column, which gzips to about half: one array per field. */
	cols?: Columns;
	/** The samples as they are, when they don't pack into columns exactly. */
	samples?: TimedSample[];
	brewSeries: BrewSeries | null;
}

/** Samples by field path (`elapsed`, `sample.groupPressure`), `~` where a sample has none. */
type Columns = Record<string, (number | boolean | null | '~')[]>;
const ABSENT = '~';

/** `samples` as columns, or null when a field isn't a plain value or a one-level object. */
function pack(samples: readonly TimedSample[]): Columns | null {
	const cols: Columns = {};
	const n = samples.length;
	const put = (path: string, i: number, v: unknown): boolean => {
		if (!(v === null || typeof v === 'number' || typeof v === 'boolean')) return false;
		(cols[path] ??= new Array(n).fill(ABSENT))[i] = v;
		return true;
	};
	for (let i = 0; i < n; i++) {
		for (const [k, v] of Object.entries(samples[i] as unknown as Record<string, unknown>)) {
			if (v === undefined) continue;
			if (v !== null && typeof v === 'object' && !Array.isArray(v)) {
				for (const [k2, v2] of Object.entries(v as Record<string, unknown>)) {
					if (v2 !== undefined && !put(`${k}.${k2}`, i, v2)) return null;
				}
			} else if (!put(k, i, v)) return null;
		}
	}
	return cols;
}

/** The samples `cols` holds. */
function unpack(cols: Columns, n: number): TimedSample[] {
	const out: Record<string, unknown>[] = Array.from({ length: n }, () => ({}));
	for (const [path, values] of Object.entries(cols)) {
		if (!Array.isArray(values) || values.length !== n) throw new Error('a column cut short');
		const dot = path.indexOf('.');
		for (let i = 0; i < n; i++) {
			const v = values[i];
			if (v === ABSENT) continue;
			if (dot < 0) out[i][path] = v;
			else ((out[i][path.slice(0, dot)] ??= {}) as Record<string, unknown>)[path.slice(dot + 1)] = v;
		}
	}
	return out as unknown as TimedSample[];
}

/** What reading a shot's file found. */
export type ArchiveRead = { kind: 'curve'; curve: ArchivedCurve } | { kind: 'missing' } | { kind: 'corrupt'; why: string };

export type ArchiveRoot = () => Promise<FileSystemDirectoryHandle | undefined>;

/** The origin private file system, where the browser has one. */
export async function defaultArchiveRoot(): Promise<FileSystemDirectoryHandle | undefined> {
	if (typeof navigator === 'undefined' || typeof navigator.storage?.getDirectory !== 'function') return undefined;
	try {
		return await navigator.storage.getDirectory();
	} catch {
		return undefined;
	}
}

/** The file name for shot `id`. */
export function archiveNameOf(id: string): string {
	return `${encodeURIComponent(id)}${SUFFIX}`;
}

/** The shot id a file name is for, or null for a file that isn't one. */
export function idOfArchiveName(name: string): string | null {
	if (!name.endsWith(SUFFIX)) return null;
	try {
		return decodeURIComponent(name.slice(0, -SUFFIX.length));
	} catch {
		return null;
	}
}

/** Whether `samples` and `brewSeries` hold a curve worth keeping. */
export function holdsCurve(samples: readonly unknown[], brewSeries: BrewSeries | null | undefined): boolean {
	return samples.length > 0 || (brewSeries?.samples.length ?? 0) > 0;
}

const sumOf = (payload: unknown, brewSeries: BrewSeries | null): string => hashText(JSON.stringify([payload, brewSeries]));

async function gzip(text: string): Promise<Uint8Array> {
	if (typeof CompressionStream === 'function' && typeof Response === 'function') {
		try {
			const stream = new Blob([text]).stream().pipeThrough(new CompressionStream('gzip'));
			return new Uint8Array(await new Response(stream).arrayBuffer());
		} catch {
			// Fall through to the library, which does the same on this thread.
		}
	}
	return gzipSync(strToU8(text));
}

/** The archive's files, one per shot. */
export class CurveArchive {
	constructor(private readonly root: ArchiveRoot = defaultArchiveRoot) {}

	private async dir(create: boolean): Promise<FileSystemDirectoryHandle | undefined> {
		const top = await this.root();
		if (!top) return undefined;
		try {
			return await top.getDirectoryHandle(HISTORY_CURVES_NAME, { create });
		} catch (e) {
			if (!create) return undefined;
			throw e;
		}
	}

	/** Whether there's a private file system to keep curves in. */
	async available(): Promise<boolean> {
		return (await this.root()) !== undefined;
	}

	/** The ids of every file there, whole or not. */
	async ids(): Promise<Set<string>> {
		const out = new Set<string>();
		const dir = await this.dir(false);
		if (!dir) return out;
		for await (const name of (dir as unknown as { keys(): AsyncIterable<string> }).keys()) {
			const id = idOfArchiveName(name);
			if (id !== null) out.add(id);
		}
		return out;
	}

	/** Whether `id`'s file is there and not empty (an empty one is a write a closed tab cut off). */
	private async held(dir: FileSystemDirectoryHandle, id: string): Promise<boolean> {
		try {
			const file = await (await dir.getFileHandle(archiveNameOf(id))).getFile();
			return (file as { size?: number }).size !== 0;
		} catch {
			return false;
		}
	}

	/**
	 * Keep `id`'s curve, once: a file already there is left as it is.
	 * `written` when this call wrote it, `kept` when it was there, `none` for
	 * no curve or no private file system. Throws when the write fails.
	 */
	async write(id: string, curve: ArchivedCurve): Promise<'written' | 'kept' | 'none'> {
		if (!holdsCurve(curve.samples, curve.brewSeries)) return 'none';
		const dir = await this.dir(true);
		if (!dir) return 'none';
		if (await this.held(dir, id)) return 'kept';
		const brewSeries = curve.brewSeries ?? null;
		// Columns only when they give the samples back exactly, key order too.
		let cols = pack(curve.samples);
		if (cols && JSON.stringify(unpack(cols, curve.samples.length)) !== JSON.stringify(curve.samples)) cols = null;
		const body: ArchiveFile = {
			v: ARCHIVE_VERSION,
			id,
			sampleCount: curve.samples.length,
			brewSampleCount: brewSeries?.samples.length ?? 0,
			sum: sumOf(cols ?? curve.samples, brewSeries),
			...(cols ? { cols } : { samples: curve.samples }),
			brewSeries
		};
		const bytes = await gzip(JSON.stringify(body));
		const handle = await dir.getFileHandle(archiveNameOf(id), { create: true });
		const w = await handle.createWritable();
		try {
			await w.write(bytes as unknown as BufferSource);
			await w.close();
		} catch (e) {
			await (w as { abort?: () => Promise<void> }).abort?.().catch(() => {});
			throw e;
		}
		return 'written';
	}

	/** `id`'s curve, if its file reads back whole. */
	async read(id: string): Promise<ArchiveRead> {
		const dir = await this.dir(false);
		if (!dir) return { kind: 'missing' };
		let bytes: Uint8Array;
		try {
			bytes = new Uint8Array(await (await (await dir.getFileHandle(archiveNameOf(id))).getFile()).arrayBuffer());
		} catch {
			return { kind: 'missing' };
		}
		try {
			const f = JSON.parse(strFromU8(gunzipSync(bytes))) as ArchiveFile;
			if (f.v !== ARCHIVE_VERSION || f.id !== id) return { kind: 'corrupt', why: 'not this shot’s file' };
			const brew = f.brewSeries ?? null;
			if (sumOf(f.cols ?? f.samples, brew) !== f.sum) return { kind: 'corrupt', why: 'its hash doesn’t match' };
			const samples = f.cols ? unpack(f.cols, f.sampleCount) : f.samples;
			if (!Array.isArray(samples) || samples.length !== f.sampleCount) return { kind: 'corrupt', why: 'samples cut short' };
			if (brew !== null && (!Array.isArray(brew.samples) || !Array.isArray(brew.stageMarks))) {
				return { kind: 'corrupt', why: 'brew series unreadable' };
			}
			if ((brew?.samples.length ?? 0) !== f.brewSampleCount) return { kind: 'corrupt', why: 'brew series cut short' };
			return { kind: 'curve', curve: { samples, brewSeries: brew } };
		} catch (e) {
			return { kind: 'corrupt', why: e instanceof Error ? e.message : String(e) };
		}
	}

	/** Remove the files of `ids`, as their shots are deleted. A file that isn't there is fine. */
	async remove(ids: readonly string[]): Promise<void> {
		const dir = await this.dir(false);
		if (!dir) return;
		for (const id of ids) {
			try {
				await dir.removeEntry(archiveNameOf(id));
			} catch (e) {
				if (!(e instanceof DOMException && e.name === 'NotFoundError')) throw e;
			}
		}
	}
}
