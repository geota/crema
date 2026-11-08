/**
 * `#lib/restore/tab-safety` — keep a restore in one tab from costing another
 * tab its machine or its writes (restore-safety design, section 2, "Other web
 * tabs").
 *
 * **The machine comes first.** A tab holds the `crema-machine-busy` Web Lock in
 * shared mode while a shot or a firmware flash runs ({@link MachineBusy}).
 * Taking it never waits, as a shot needs no permission. A restoring tab takes
 * it exclusive, without waiting, so any shot or flash anywhere refuses the
 * restore; and a flash that can't get it doesn't start.
 *
 * **A frozen tab.** When a restore starts in another tab, this one stops every
 * Crema write ({@link freezeCremaWrites}, the factory reset's freeze) and shows
 * a blocking overlay, which is what makes the freeze honest: writes are dropped
 * silently, so nothing may look saved. It reloads when the restoring tab
 * releases `crema-restore` or says `done`, whichever comes first, but never
 * while a shot or a flash runs here: then it waits for that to end. Without Web
 * Locks, it reloads on `done`, or after {@link RESTORE_FALLBACK_MS} with no
 * `progress`, should the restoring tab have died.
 *
 * **A shot finished in a frozen tab** would lose its row, debit and series, so
 * it's kept in `sessionStorage` ({@link FROZEN_SHOTS_KEY}, a non-`crema.` key
 * like the reset report's) and recorded by the normal path after the reload.
 *
 * Nothing posts `started` yet: the restore itself does, from restore-safety PR7w.
 */
import { freezeCremaWrites } from '#lib/security/reset-coordinator.js';
import { canReloadNow, type ReloadInputs } from '#lib/pwa/update-gate.js';

/** BroadcastChannel name every Crema tab listens on for restores. */
export const RESTORE_CHANNEL = 'crema-restore';
/** Held by the restoring tab from confirm to the end. */
export const RESTORE_LOCK = 'crema-restore';
/** Held shared by a tab running a shot or a flash; exclusive by a restore. */
export const MACHINE_BUSY_LOCK = 'crema-machine-busy';
/** Without Web Locks: reload anyway after this long without word. */
export const RESTORE_FALLBACK_MS = 30_000;
/**
 * localStorage key for shots a frozen tab finished: not a `crema.` key, so the
 * freeze leaves it alone, and not sessionStorage, so closing the tab doesn't
 * lose them. Any tab commits them at its next boot.
 */
export const FROZEN_SHOTS_KEY = 'crema-frozen-shots';
/** How long a frozen tab waits for its machine before offering "Reload now". */
export const STUCK_AFTER_MS = 120_000;

/** One message on {@link RESTORE_CHANNEL}, tagged with the tab that sent it. */
export type RestoreMessage = { type: 'started' | 'progress' | 'done'; from?: string };

/** This tab's id in restore messages: a tab hears its own posts too. */
export const TAB_ID: string =
	typeof crypto !== 'undefined' && 'randomUUID' in crypto
		? crypto.randomUUID()
		: `tab-${Math.random().toString(36).slice(2)}`;

/** The slice of `LockManager` this module uses, so tests can stand one in. */
export interface LockManagerLike {
	request(
		name: string,
		options: { mode?: 'shared' | 'exclusive'; ifAvailable?: boolean; signal?: AbortSignal },
		callback: (lock: unknown) => unknown
	): Promise<unknown>;
	query?(): Promise<{ held?: { name?: string }[] }>;
}

function navigatorLocks(): LockManagerLike | undefined {
	return typeof navigator === 'undefined'
		? undefined
		: (navigator.locks as unknown as LockManagerLike | undefined);
}

// ── The machine ────────────────────────────────────────────────────────────

/**
 * This tab's shared hold on {@link MACHINE_BUSY_LOCK}, for as long as any
 * reason for it lasts: a shot, a flash, a flash starting. One lock for them
 * all, so handing over from one reason to the next never lets it go.
 */
export class MachineBusy {
	readonly #reasons = new Set<string>();
	#release: (() => void) | null = null;
	#acquiring: Promise<boolean> | null = null;
	/** A queued request after a refusal, so a restore that comes later sees us. */
	#retry: AbortController | null = null;

	constructor(private readonly locks: LockManagerLike | undefined = navigatorLocks()) {}

	/**
	 * Hold the lock for `reason`, without waiting. False when a restore holds it
	 * exclusive. True when there are no Web Locks: there's nothing to ask. With
	 * `retry`, a refused reason stays, and a request queues behind the restore,
	 * so a later restore elsewhere still sees this tab's shot.
	 */
	hold(reason: string, retry = false): Promise<boolean> {
		this.#reasons.add(reason);
		if (!this.locks || this.#release) return Promise.resolve(true);
		this.#acquiring ??= this.#acquire();
		return this.#acquiring.then((held) => {
			if (!held && !retry) this.#reasons.delete(reason);
			if (!held && retry && this.#reasons.has(reason)) this.#queueRetry();
			return held;
		});
	}

	/** `reason` has ended; the lock goes with the last one. */
	release(reason: string): void {
		this.#reasons.delete(reason);
		if (this.#reasons.size > 0) return;
		this.#retry?.abort();
		this.#retry = null;
		if (!this.#release) return;
		this.#release();
		this.#release = null;
	}

	/** Hold or release for `reason`, as a flag changes; a refused hold retries. */
	set(reason: string, on: boolean): void {
		if (on) void this.hold(reason, true);
		else this.release(reason);
	}

	#queueRetry(): void {
		if (this.#retry || !this.locks) return;
		const retry = new AbortController();
		this.#retry = retry;
		this.locks
			.request(MACHINE_BUSY_LOCK, { mode: 'shared', signal: retry.signal }, () => {
				if (this.#retry !== retry || this.#reasons.size === 0 || this.#release) return undefined;
				this.#retry = null;
				return new Promise<void>((released) => {
					this.#release = released;
				});
			})
			.catch(() => {
				if (this.#retry === retry) this.#retry = null;
			});
	}

	/** Whether this tab holds the lock. */
	/** Whether this tab has a shot or a flash running, Web Locks or not. */
	get busy(): boolean {
		return this.#reasons.size > 0;
	}

	get held(): boolean {
		return this.#release !== null;
	}

	#acquire(): Promise<boolean> {
		return new Promise<boolean>((resolve) => {
			this.locks!
				.request(MACHINE_BUSY_LOCK, { mode: 'shared', ifAvailable: true }, (lock) => {
					if (lock === null) {
						resolve(false);
						return undefined;
					}
					// A queued retry got there first: one shared hold is enough.
					if (this.#release) {
						resolve(true);
						return undefined;
					}
					return new Promise<void>((released) => {
						this.#release = released;
						resolve(true);
						// Every reason may have ended while this was granted.
						if (this.#reasons.size === 0) this.release('');
					});
				})
				// A refused request (an opaque origin): nothing to hold, nothing to refuse.
				.catch(() => resolve(true));
		}).finally(() => {
			this.#acquiring = null;
		});
	}
}

let machineBusy: MachineBusy | null = null;

/** This tab's {@link MachineBusy}. */
export function getMachineBusy(): MachineBusy {
	machineBusy ??= new MachineBusy();
	return machineBusy;
}

// ── What a reload would interrupt ──────────────────────────────────────────

let shotOpen = false;
const idleListeners = new Set<() => void>();
let linkDownTimer: ReturnType<typeof setTimeout> | null = null;
/** The link-down grace let a shot's hold go: the shot may still be running. */
let shotLapsed = false;

/** How long a shot's hold outlives a lost link: a reconnect within it can still finish the shot. */
export const SHOT_LINK_GRACE_MS = 120_000;
let shotLinkGraceMs = SHOT_LINK_GRACE_MS;

function clearLinkDownTimer(): void {
	if (linkDownTimer) clearTimeout(linkDownTimer);
	linkDownTimer = null;
}

/** A shot has started: a reload waits until it's recorded, not until the machine stops. */
export function shotStarted(): void {
	clearLinkDownTimer();
	shotLapsed = false;
	shotOpen = true;
	getMachineBusy().set(SHOT, true);
}

/**
 * The DE1 link went down (`true`) or came back (`false`). A shot's hold stays
 * while a reconnect could still finish it, then goes after
 * {@link SHOT_LINK_GRACE_MS}, so a DE1 switched off mid-shot can't refuse a
 * restore in another tab for good.
 */
export function shotLinkDown(down: boolean): void {
	if (!down) {
		clearLinkDownTimer();
		return;
	}
	if (!shotOpen || linkDownTimer) return;
	linkDownTimer = setTimeout(() => {
		linkDownTimer = null;
		shotFinished();
		shotLapsed = true;
	}, shotLinkGraceMs);
}

/**
 * The machine reports Espresso. After an outage longer than the grace, the
 * shot it let go is still pulling: hold again for its tail.
 */
export function machineInEspresso(): void {
	if (shotLapsed && !shotOpen) shotStarted();
}

/**
 * The core's session has ended with the shot unfinished: a user Disconnect,
 * or a core reset or stop. No `ShotCompleted` will come for it.
 */
export function shotAbandoned(): void {
	shotLapsed = false;
	if (shotOpen) shotFinished();
}

/**
 * The shot's completion has been committed (or stashed). With a scale, that's
 * up to 3 s after the machine went Idle: the drip settle.
 */
export function shotFinished(): void {
	clearLinkDownTimer();
	shotOpen = false;
	getMachineBusy().set(SHOT, false);
	for (const l of idleListeners) l();
}

/** Whether a shot has started and its completion isn't committed yet. */
export function shotIsOpen(): boolean {
	return shotOpen;
}

/** Hear when a shot is done with, to retry a reload. Returns the unsubscribe. */
export function onShotFinished(listener: () => void): () => void {
	idleListeners.add(listener);
	return () => idleListeners.delete(listener);
}

/** {@link MachineBusy} reason for a shot, from `ShotStarted` to its commit. */
export const SHOT = 'shot';

/** What a reload would interrupt, as {@link reloadBlocked} reads it. */
export interface ReloadState {
	inputs: ReloadInputs;
	/** A flash runs, or holds the link for a power cycle. */
	flash: boolean;
	shotOpen: boolean;
}

/**
 * Whether a frozen tab must wait to reload: the update gate's rule (a shot, a
 * hot-water stop by weight, steam, a guided brew, a start's sync, a link coming
 * back), plus a shot not yet recorded and a flash. A down link counts as idle,
 * as the gate counts it, so a DE1 switched off mid-shot can't freeze a tab for
 * good; but not for a flash waiting on its power cycle, or a guided brew, which
 * runs on the scale alone.
 */
export function reloadBlocked(s: ReloadState): boolean {
	if (s.flash || s.inputs.guidedBrewLive) return true;
	if (s.inputs.link === 'down') return false;
	return s.shotOpen || !canReloadNow(s.inputs);
}

/** What a flash says when a restore holds the machine. */
export const FLASH_REFUSED_FOR_RESTORE = 'A restore is running. Start the update once it has finished.';

// ── A frozen tab ───────────────────────────────────────────────────────────

let frozen = false;
let restoringHere = false;

/** Whether a restore in another tab has frozen this one. */
export function restoreFrozen(): boolean {
	return frozen;
}

/** This tab is the one restoring: its own messages mustn't freeze it. */
export function markRestoringHere(on: boolean): void {
	restoringHere = on;
}

/** Test seam: forget this module's state. */
export function resetTabSafetyForTest(busy: MachineBusy | null = null, linkGraceMs = SHOT_LINK_GRACE_MS): void {
	shotLinkGraceMs = linkGraceMs;
	frozen = false;
	restoringHere = false;
	machineBusy = busy;
	shotOpen = false;
	idleListeners.clear();
	clearLinkDownTimer();
	shotLapsed = false;
}

export interface RestoreListenerOptions {
	/** Whether a shot or a flash runs here, which a reload would interrupt. */
	busy: () => boolean;
	/** Show the overlay. */
	onFrozen: () => void;
	/** Whether a reload must wait for the user (a shot that couldn't be kept). */
	holdReload?: () => boolean;
	/** A reload has waited {@link STUCK_AFTER_MS} for the machine: offer one by hand. */
	onStuck?: () => void;
	stuckAfterMs?: number;
	reload?: () => void;
	freeze?: () => void;
	locks?: LockManagerLike;
	channel?: () => BroadcastChannel | null;
	fallbackMs?: number;
}

export interface RestoreListener {
	/** A shot or a flash here has ended, or the user is done: reload if one's due. */
	idle(): void;
	/** Check, at boot, whether a restore is already running elsewhere. */
	checkAtBoot(): Promise<void>;
	stop(): void;
}

function openChannel(): BroadcastChannel | null {
	try {
		return typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(RESTORE_CHANNEL);
	} catch {
		return null;
	}
}

/** Tell every other Crema tab where a restore is, as this tab. Best-effort. For PR7w. */
export function announceRestore(type: RestoreMessage['type']): void {
	const ch = openChannel();
	if (!ch) return;
	try {
		ch.postMessage({ type, from: TAB_ID } satisfies RestoreMessage);
	} finally {
		ch.close();
	}
}

/** In every tab: freeze when a restore starts elsewhere, then reload once it's over. */
export function installRestoreListener(o: RestoreListenerOptions): RestoreListener {
	const locks = o.locks ?? navigatorLocks();
	const ch = (o.channel ?? openChannel)();
	const reload = o.reload ?? (() => location.reload());
	const fallbackMs = o.fallbackMs ?? RESTORE_FALLBACK_MS;
	let fallback: ReturnType<typeof setTimeout> | null = null;
	let stuck: ReturnType<typeof setTimeout> | null = null;
	let wanted = false;
	let reloaded = false;
	let stopped = false;

	const tryReload = (): void => {
		if (!wanted || reloaded || stopped) return;
		if (o.busy() || o.holdReload?.()) {
			stuck ??= setTimeout(() => o.onStuck?.(), o.stuckAfterMs ?? STUCK_AFTER_MS);
			return;
		}
		reloaded = true;
		if (fallback) clearTimeout(fallback);
		if (stuck) clearTimeout(stuck);
		reload();
	};
	const wantReload = (): void => {
		wanted = true;
		tryReload();
	};
	const startFallback = (): void => {
		if (fallback) clearTimeout(fallback);
		fallback = setTimeout(wantReload, fallbackMs);
	};
	const enterFrozen = (): void => {
		if (frozen) return;
		frozen = true;
		(o.freeze ?? (() => freezeCremaWrites()))();
		o.onFrozen();
		if (!locks) {
			startFallback();
			return;
		}
		// Granted once the restoring tab lets go, whether it finished or died.
		locks.request(RESTORE_LOCK, {}, () => wantReload()).catch(() => startFallback());
	};

	if (ch) {
		ch.onmessage = (e: MessageEvent<RestoreMessage>) => {
			// A tab hears its own posts, later than it might clear a flag.
			if (restoringHere || e.data?.from === TAB_ID) return;
			const type = e.data?.type;
			if (type === 'started') enterFrozen();
			else if (type === 'progress' && fallback) startFallback();
			else if (type === 'done') {
				enterFrozen();
				wantReload();
			}
		};
	}
	return {
		idle: tryReload,
		async checkAtBoot() {
			const held = await locks?.query?.().catch(() => undefined);
			if (held?.held?.some((l) => l.name === RESTORE_LOCK) && !restoringHere) enterFrozen();
		},
		stop() {
			stopped = true;
			if (fallback) clearTimeout(fallback);
			if (stuck) clearTimeout(stuck);
			ch?.close();
		}
	};
}

// ── Shots a frozen tab finished ────────────────────────────────────────────

/** A shot a frozen tab finished, kept for the reload. */
export interface FrozenShot {
	/** The core's assembled shot. */
	captured: Record<string, unknown>;
	/** The bag it came out of, when the shot was live and had one. */
	beanId: string | null;
	/** The dose to debit, when it was live. */
	dose: number | null;
	/** Live, not a replay: a replay debits nothing. */
	live: boolean;
}

function defaultStorage(): Storage | undefined {
	return typeof localStorage === 'undefined' ? undefined : localStorage;
}

const idOf = (shot: FrozenShot): unknown => shot.captured.id;

/** Keep `shot` for after the reload, once per shot id. False when storage won't take it. */
export function stashFrozenShot(shot: FrozenShot, storage: Storage | undefined = defaultStorage()): boolean {
	try {
		if (!storage) return false;
		const kept = readFrozen(storage).filter((s) => idOf(s) !== idOf(shot));
		storage.setItem(FROZEN_SHOTS_KEY, JSON.stringify([...kept, shot]));
		return true;
	} catch {
		return false;
	}
}

function readFrozen(storage: Storage): FrozenShot[] {
	const raw = storage.getItem(FROZEN_SHOTS_KEY);
	if (raw == null) return [];
	const parsed: unknown = JSON.parse(raw);
	return Array.isArray(parsed) ? (parsed as FrozenShot[]) : [];
}

/** The shots kept for after a reload. They stay until {@link dropFrozenShots}. */
export function peekFrozenShots(storage: Storage | undefined = defaultStorage()): FrozenShot[] {
	try {
		return storage ? readFrozen(storage) : [];
	} catch {
		return [];
	}
}

/** Forget the kept shots with these ids, once they're recorded. */
export function dropFrozenShots(ids: readonly unknown[], storage: Storage | undefined = defaultStorage()): void {
	try {
		if (!storage) return;
		const left = readFrozen(storage).filter((s) => !ids.includes(idOf(s)));
		if (left.length === 0) storage.removeItem(FROZEN_SHOTS_KEY);
		else storage.setItem(FROZEN_SHOTS_KEY, JSON.stringify(left));
	} catch {
		// They stay, and the next boot tries again; history skips ids it has.
	}
}
