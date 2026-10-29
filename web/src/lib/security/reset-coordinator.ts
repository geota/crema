/**
 * `#lib/security/reset-coordinator.js` — keep a factory reset from being
 * undone by the live app (final review 05 N-3, 06 #12).
 *
 * Every open Crema tab holds its settings, library and history in memory and
 * writes them back on its next change; a shot that completes reopens and
 * refills IndexedDB. So while a reset runs, and until each tab reloads, no
 * tab may write Crema data:
 *
 *  - The resetting tab announces `started` on the `crema-reset`
 *    BroadcastChannel, revokes the sign-ins (which still needs the stored
 *    tokens and the secret-box key), then calls {@link freezeCremaWrites}
 *    just before the wipe, and reloads as soon as the wipe returns — with no
 *    dialog in between. What the user must still be told (a database another
 *    tab held open) travels across the reload in a session report
 *    ({@link stashResetReport} / {@link takeResetReport}).
 *  - Every other tab ({@link installResetListener}) freezes its writes the
 *    moment it hears `started` and reloads on `done`, or after
 *    {@link FALLBACK_RELOAD_MS} if the resetting tab went away.
 *
 * "Freeze" is enforced at the two storage entry points rather than in each of
 * the app's ~20 writers: `Storage.prototype.setItem` drops `crema.*` keys and
 * `indexedDB.open` refuses `crema-*` databases. It is never lifted — the tab
 * reloads — so it cannot leak into normal use.
 */

/** BroadcastChannel name every Crema tab listens on. */
export const RESET_CHANNEL = 'crema-reset';
/** Another tab reloads anyway after this long without `done`. */
export const FALLBACK_RELOAD_MS = 30_000;
/**
 * sessionStorage key for the post-reload report. Deliberately not a `crema.`
 * key: it is written after the wipe and must survive the freeze. It holds no
 * user data and is removed when read.
 */
export const RESET_REPORT_KEY = 'crema-reset-report';

const STORAGE_PREFIX = 'crema.';
const DATABASE_PREFIX = 'crema-';

export type ResetMessage = { type: 'started' } | { type: 'done' };

/** What the reloaded tab tells the user. */
export interface ResetReport {
	/** Databases the browser did not delete in time (another tab held them). */
	databasesFailed: string[];
	/** Revocations that threw or timed out. */
	revokeFailures: number;
}

export interface FreezeTargets {
	/** Prototype whose `setItem` is wrapped (default `Storage.prototype`). */
	storageProto?: { setItem(key: string, value: string): void };
	/** IndexedDB factory whose `open` is wrapped (default the global one). */
	indexedDB?: IDBFactory;
}

let frozen = false;
/**
 * Set once THIS tab announced a reset. A BroadcastChannel delivers to every
 * other channel object of the same name, including the layout's listener in
 * the resetting tab itself, which must not freeze early: the revokes still
 * need the secret-box key.
 */
let resettingHere = false;

/** Test seam: forget this module's state. */
export function resetCoordinatorForTest(): void {
	frozen = false;
	resettingHere = false;
}

/** Has this tab frozen its Crema writes (a reset is under way)? */
export function cremaWritesFrozen(): boolean {
	return frozen;
}

/**
 * Stop every Crema write in this tab: `crema.*` storage writes are dropped
 * and opening a `crema-*` database throws. Deletes and reads still work, so
 * the wipe itself runs. Idempotent. Never undone — the tab reloads.
 */
export function freezeCremaWrites(targets: FreezeTargets = {}): void {
	frozen = true;
	const proto =
		targets.storageProto ?? (typeof Storage === 'undefined' ? undefined : Storage.prototype);
	if (proto && !(proto.setItem as { cremaFrozen?: boolean }).cremaFrozen) {
		const setItem = proto.setItem;
		const guarded = function (this: unknown, key: string, value: string): void {
			if (String(key).startsWith(STORAGE_PREFIX)) return;
			setItem.call(this, key, value);
		};
		(guarded as { cremaFrozen?: boolean }).cremaFrozen = true;
		proto.setItem = guarded;
	}
	const idb = targets.indexedDB ?? (typeof indexedDB === 'undefined' ? undefined : indexedDB);
	if (idb && !(idb.open as { cremaFrozen?: boolean }).cremaFrozen) {
		const open = idb.open.bind(idb);
		const guarded = (name: string, version?: number): IDBOpenDBRequest => {
			if (String(name).startsWith(DATABASE_PREFIX)) {
				throw new DOMException('Crema is resetting; storage is closed', 'InvalidStateError');
			}
			return version === undefined ? open(name) : open(name, version);
		};
		(guarded as { cremaFrozen?: boolean }).cremaFrozen = true;
		idb.open = guarded;
	}
}

function channel(): BroadcastChannel | null {
	try {
		return typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(RESET_CHANNEL);
	} catch {
		return null;
	}
}

/** Tell every other Crema tab about the reset. Best-effort. */
export function announceReset(message: ResetMessage): void {
	if (message.type === 'started') resettingHere = true;
	const ch = channel();
	if (!ch) return;
	try {
		ch.postMessage(message);
	} finally {
		ch.close();
	}
}

export interface ResetListenerOptions {
	/** How to reload (default `location.reload()`). */
	reload?: () => void;
	/** How to freeze (default {@link freezeCremaWrites} on the globals). */
	freeze?: () => void;
	fallbackMs?: number;
}

/**
 * In every tab: freeze on `started`, reload on `done` (or after the fallback
 * delay). Returns a function that stops listening.
 */
export function installResetListener(options: ResetListenerOptions = {}): () => void {
	const ch = channel();
	if (!ch) return () => {};
	const reload = options.reload ?? (() => location.reload());
	const freeze = options.freeze ?? (() => freezeCremaWrites());
	let fallback: ReturnType<typeof setTimeout> | null = null;
	let reloaded = false;
	const reloadOnce = (): void => {
		if (reloaded) return;
		reloaded = true;
		if (fallback) clearTimeout(fallback);
		reload();
	};
	ch.onmessage = (e: MessageEvent<ResetMessage>) => {
		// The resetting tab runs its own freeze and reload.
		if (resettingHere) return;
		if (e.data?.type === 'started') {
			freeze();
			fallback ??= setTimeout(reloadOnce, options.fallbackMs ?? FALLBACK_RELOAD_MS);
		} else if (e.data?.type === 'done') {
			freeze();
			reloadOnce();
		}
	};
	return () => {
		if (fallback) clearTimeout(fallback);
		ch.close();
	};
}

/** Keep the outcome for the reloaded page. Best-effort. */
export function stashResetReport(report: ResetReport, storage: Storage | undefined = sessionStorage): void {
	try {
		storage?.setItem(RESET_REPORT_KEY, JSON.stringify(report));
	} catch {
		// Storage disabled: the reset still happened; only the notice is lost.
	}
}

/** Read and remove the report a reset left before reloading, if any. */
export function takeResetReport(storage: Storage | undefined = sessionStorage): ResetReport | null {
	try {
		const raw = storage?.getItem(RESET_REPORT_KEY);
		if (raw == null) return null;
		storage?.removeItem(RESET_REPORT_KEY);
		const parsed = JSON.parse(raw) as Partial<ResetReport>;
		return {
			databasesFailed: Array.isArray(parsed.databasesFailed) ? parsed.databasesFailed.map(String) : [],
			revokeFailures: typeof parsed.revokeFailures === 'number' ? parsed.revokeFailures : 0
		};
	} catch {
		return null;
	}
}

/** The notice for a report, or null when everything went through. */
export function resetReportMessage(report: ResetReport): string | null {
	if (report.databasesFailed.length > 0) {
		return (
			'Crema was open in another tab or window, so part of the stored data ' +
			'(shot telemetry, captures or bag photos) could not be deleted. ' +
			'Close the other Crema tabs, then run Factory reset again.'
		);
	}
	if (report.revokeFailures > 0) {
		return (
			'Crema was reset, but Visualizer or Google Drive could not be reached to revoke ' +
			'its access. Remove Crema from the connected apps on those services to be sure.'
		);
	}
	return null;
}
