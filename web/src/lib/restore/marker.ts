/**
 * `#lib/restore/marker` — the restore's marker (restore-safety design,
 * section 8): `applying` while a restore writes, so a tab closed part-way is
 * seen at the next boot; `finishing` once it's done, to hold the deletes it
 * owes until they're safe.
 *
 * One owner for a restore's deletes. The series and captures of the shots it
 * removed, and the photos of the beans it removed, are deleted here by name,
 * never by a save's diff, and only:
 * - at a boot at least {@link DELETE_AFTER_MS} after `finishingAt`, as
 *   `setItem` returns before Chromium commits, and a crash could bring back
 *   rows whose curves were already gone; a frozen tab's reload is a boot too;
 * - when the rows loaded as `ok`, as a corrupt load reads as "not listed";
 * - when no restore is running in any tab;
 * - when the rows no longer list the id.
 */
import { readStored, storeHealth, writeJsonChecked } from '#lib/utils/storage.js';

export const MARKER_KEY = 'crema.restore.pending';

/** The wait between a restore finishing and its deletes running. */
export const DELETE_AFTER_MS = 60 * 60 * 1000;

/** What a restore owes to delete, by id. */
export interface MarkerDeletes {
	/** Shot ids: their series and raw captures. */
	shots: string[];
	/** Bean ids: their photos. */
	photos: string[];
}

export interface RestoreMarker {
	phase: 'applying' | 'finishing';
	mode: 'merge' | 'replace';
	backupName: string;
	startedAt: number;
	finishingAt?: number;
	deletes: MarkerDeletes;
}

const union = (a: readonly string[], b: readonly string[]): string[] => [...new Set([...a, ...b])];

function isMarker(v: unknown): v is RestoreMarker {
	if (typeof v !== 'object' || v === null) return false;
	const m = v as Partial<RestoreMarker>;
	return (
		(m.phase === 'applying' || m.phase === 'finishing') &&
		typeof m.backupName === 'string' &&
		typeof m.deletes === 'object' &&
		m.deletes !== null &&
		Array.isArray(m.deletes.shots) &&
		Array.isArray(m.deletes.photos)
	);
}

/** The marker, or `null` when there's none, or it can't be read. */
export function readMarker(): RestoreMarker | null {
	const r = readStored(MARKER_KEY);
	return r.kind === 'loaded' && isMarker(r.value) ? r.value : null;
}

/**
 * Step 1: mark a restore as applying. Deletes left by an earlier marker are
 * kept: after a kill, the re-run's plan removes nothing, so replacing them
 * would leak those files. Whether it was stored.
 */
export function beginMarker(
	mode: RestoreMarker['mode'],
	backupName: string,
	deletes: MarkerDeletes,
	now: number = Date.now()
): boolean {
	const prior = readMarker();
	const marker: RestoreMarker = {
		phase: 'applying',
		mode,
		backupName,
		startedAt: now,
		deletes: {
			shots: union(prior?.deletes.shots ?? [], deletes.shots),
			photos: union(prior?.deletes.photos ?? [], deletes.photos)
		}
	};
	return writeJsonChecked(MARKER_KEY, marker);
}

/** Step 9, or Dismiss on an interrupted one: the deletes wait their hour. */
export function finishMarker(now: number = Date.now()): boolean {
	const m = readMarker();
	if (!m) return true;
	return writeJsonChecked(MARKER_KEY, { ...m, phase: 'finishing', finishingAt: now } satisfies RestoreMarker);
}

/** Remove the marker, its deletes with it. */
export function dropMarker(): void {
	clearMarker();
}

function clearMarker(): void {
	try {
		localStorage.removeItem(MARKER_KEY);
	} catch {
		// It stays; the next boot runs the same checks.
	}
}

/** What the boot check reads, for tests. */
export interface MarkerBootDeps {
	now?: number;
	/** A restore runs in some tab. */
	restoreRunning: () => Promise<boolean>;
	/** Shot ids the rows list, tombstones included. */
	listedShots: () => ReadonlySet<string>;
	/** Bean ids the library lists. */
	listedBeans: () => ReadonlySet<string>;
	/** The health of the rows and the library, as loaded. */
	shotsHealth?: () => string;
	beansHealth?: () => string;
	deleteSeries: (id: string) => Promise<void>;
	deleteCapture: (id: string) => Promise<void>;
	deletePhoto: (beanId: string) => Promise<void>;
}

/** What a boot found. `interrupted` wants the notice with Dismiss. */
export type MarkerBoot =
	| { kind: 'none' }
	| { kind: 'interrupted'; backupName: string }
	| { kind: 'waiting' }
	| { kind: 'deleted'; shots: number; photos: number };

/**
 * At boot: an `applying` marker with nothing running is an interrupted restore,
 * to say so; a `finishing` one runs its deletes once they're safe, then goes.
 */
export async function checkMarkerAtBoot(deps: MarkerBootDeps): Promise<MarkerBoot> {
	const m = readMarker();
	if (!m) return { kind: 'none' };
	if (await deps.restoreRunning()) return { kind: 'waiting' };
	if (m.phase === 'applying') return { kind: 'interrupted', backupName: m.backupName };
	const now = deps.now ?? Date.now();
	if (m.finishingAt === undefined || now - m.finishingAt < DELETE_AFTER_MS) return { kind: 'waiting' };
	const shotsOk = (deps.shotsHealth ?? (() => storeHealth('crema.history.v2')))() === 'ok';
	const beansOk = (deps.beansHealth ?? (() => storeHealth('crema.beans.v1')))() === 'ok';
	let shots = 0;
	let photos = 0;
	if (shotsOk) {
		const listed = deps.listedShots();
		for (const id of m.deletes.shots) {
			if (listed.has(id)) continue;
			await deps.deleteSeries(id).catch(() => {});
			await deps.deleteCapture(id).catch(() => {});
			shots++;
		}
	}
	if (beansOk) {
		const listed = deps.listedBeans();
		for (const id of m.deletes.photos) {
			if (listed.has(id)) continue;
			await deps.deletePhoto(id).catch(() => {});
			photos++;
		}
	}
	// A load that wasn't `ok` keeps the marker: its deletes wait for one that is.
	if (shotsOk && beansOk) clearMarker();
	return { kind: 'deleted', shots, photos };
}
