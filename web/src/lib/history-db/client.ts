/**
 * `#lib/history-db/client.js` — `HistoryDbClient`, a tab's side of the history
 * store (#310 SPEC §8, PLAN W1). Each tab starts one history Worker; the
 * Worker that holds the owner lock serves every tab. This tab's requests reach
 * its own Worker by `postMessage` when it is the owner, and the owner's Worker
 * over `crema-history-rpc` when it isn't.
 *
 * - **Locks** (M2, M11): the tab holds `crema-history-stage` (shared) and
 *   `crema-history-tab:<id>` for its life. The owner lock is the Worker's.
 * - **Cross-build** (M1): every reply and "ready" names its owner; one this
 *   client can't use ({@link acceptsOwner}) counts as an owner that doesn't
 *   answer, with "Another version of Crema is open in another window".
 * - **Order** (M11): writes wait in an outbox in seq order, and the whole
 *   outbox is resent, in order, whenever an owner appears, a write comes back
 *   `gap`, or an answer is late. The owner's `apply` skips what it already
 *   applied, so the last action wins and nothing applies twice.
 * - **No seq is abandoned** (REVIEW-456 F1): a write the owner answered with
 *   an error (its health, a full disk, another stage or file) didn't consume
 *   its seq, so it stays and is resent. After 30 s at the front of the outbox
 *   it is given up: the caller hears, and the seq goes as a no-op (an upsert
 *   of nothing), so the tab's later writes never wait on it. A missing seq the
 *   outbox no longer holds (a pruned tab) goes as a no-op too.
 * - **Can't answer** (S2): no answer for 5 s is `index_fallback` on stage 1
 *   and `unavailable` (read-only) on stage 2, where new shots go to the
 *   pending journal and edits are refused. Reads keep being resent and reject
 *   after 30 s; they never answer as if empty.
 * - **Lifecycle** (S1): `pagehide` releases (close, pause, let go);
 *   `pageshow` with `persisted` takes the tab's locks and the owner lock
 *   again; hidden and idle for 30 s also releases, so a tab the browser freezes
 *   in the background doesn't keep the database (owner question 4, W0);
 *   visible again acquires. A Worker that errors, or misses its pings for
 *   10 s, is terminated and a new one started, after a wait that doubles
 *   from 1 s to 60 s (N1); it queues for the lock.
 * - **Pending journal** (M5): a capture journals its row with its write's
 *   seq, before the write goes (F2). The owner tab drains the journal
 *   (`pending.ts`) when its Worker gets the lock, when a client says hello, and
 *   on a `storage` event.
 * - **The flip** (N4): a stage-1 tab that hears it calls `onFlipped`, and the
 *   shell reloads when it may.
 * - **Factory reset** (S10): {@link HistoryDbClient.stop} closes, pauses and
 *   releases before any removal, and never takes the lock again.
 */
import type { ChannelLike } from './ports.js';
import type { LockManagerLike } from './ports.js';
import {
	appendPending,
	drainPending,
	entryText,
	removeIfUnchanged,
	replaceIfUnchanged,
	type JournalStorage
} from './pending.js';
import { CHANGES_CHANNEL, PENDING_KEY_PREFIX, RPC_CHANNEL, STAGE_LOCK, TAB_LOCK_PREFIX } from './names.js';
import type { PageToWorker, WorkerToPage } from './owner.js';
import {
	OTHER_BUILD_MESSAGE,
	OTHER_WINDOW_MESSAGE,
	PROTOCOL_VERSION,
	acceptsOwner,
	type Applied,
	type Change,
	type ChangeSet,
	type Drained,
	type Expect,
	type OwnerInfo,
	type ReadName,
	type Reply,
	type Request,
	type RpcMessage,
	type Stage,
	type Target,
	type Write
} from './protocol.js';

/** No answer for this long: the owner can't answer (SPEC §8). */
export const ANSWER_MS = 5_000;
/** A read gives up after this long, and rejects; it never answers as if empty. */
export const GATED_MS = 30_000;
/** How often the page pings its Worker, and how long a silent one lives. */
export const PING_MS = 2_000;
export const WORKER_DEAD_MS = 10_000;
/** Hidden and idle this long: give the database up (owner question 4). */
export const IDLE_RELEASE_MS = 30_000;
/** How long `stop` waits for the Worker to close and let go. */
export const STOP_WAIT_MS = 5_000;
/** A Worker restart waits this long, doubling to {@link RESTART_MAX_MS} while restarts keep coming (N1). */
export const RESTART_FIRST_MS = 1_000;
export const RESTART_MAX_MS = 60_000;
/** A Worker that has run this long has its restart wait reset. */
const RESTART_RESET_AFTER_MS = 30_000;
const TICK_MS = 1_000;

/** SPEC §12, as this tab sees it. */
export type ClientHealth = 'loading' | 'ok' | 'index_fallback' | 'unavailable' | 'lost' | 'too_new';

/** Why: the owner's own reason, `noAnswer`, or `otherBuild` (M1). */
export interface ClientStatus {
	health: ClientHealth;
	reason: string;
	/** For a banner: why this tab can't use the database. */
	message: string | null;
	/** The owner, when one answered. */
	owner: OwnerInfo | null;
	/** This tab's own Worker owns the database. */
	ownerHere: boolean;
}

export class HistoryDbError extends Error {
	constructor(
		readonly code: string,
		message: string
	) {
		super(message);
		this.name = 'HistoryDbError';
	}
}

export interface WorkerLike {
	postMessage(message: unknown): void;
	terminate(): void;
	onmessage: ((event: { data: unknown }) => void) | null;
	onerror: ((event: unknown) => void) | null;
}

/** The page events the client follows: `window` for `pagehide`, `pageshow`, `storage`; `document` for `visibilitychange`. */
export interface PageEvents {
	addEventListener(type: string, listener: (event: never) => void): void;
	removeEventListener(type: string, listener: (event: never) => void): void;
	/** `document.visibilityState`. */
	visibility(): 'visible' | 'hidden';
}

export interface ClientOptions {
	stage: Stage;
	/** The owner this client may use (M1). */
	expect: Expect;
	/** The file this tab's Worker opens when it owns the database. */
	target: Target;
	build: string;
	makeWorker(): WorkerLike;
	channel(name: string): ChannelLike;
	locks: LockManagerLike;
	/** `localStorage`, for the pending journal (stage 2). */
	storage?: JournalStorage;
	events?: PageEvents;
	tab?: string;
	now?(): number;
	/**
	 * A stage-1 tab heard the flip (SPEC §11): `history.db` is the truth now,
	 * so it must stop writing the P3 store and reload (N4).
	 */
	onFlipped?(): void;
	/** The page's timers (the tests freeze them with the tab). */
	timers?: Timers;
}

export interface Timers {
	setTimeout(f: () => void, ms: number): unknown;
	clearTimeout(t: unknown): void;
	setInterval(f: () => void, ms: number): unknown;
	clearInterval(t: unknown): void;
}

const globalTimers: Timers = {
	setTimeout: (f, ms) => setTimeout(f, ms),
	clearTimeout: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
	setInterval: (f, ms) => setInterval(f, ms),
	clearInterval: (t) => clearInterval(t as ReturnType<typeof setInterval>)
};

interface Pending {
	req: Request;
	firstAt: number;
	sentAt: number;
	/** The owner answered this copy, with an error the write survives (F1). */
	answered?: boolean;
	lastError?: { code: string; message: string };
	/** A seq given up (F1): a no-op that consumes it, so later writes don't wait. */
	skip?: boolean;
	/** When this write became the front of the outbox: its 30 s count from there (B1). */
	frontAt?: number;
	/** The owner answered `gap`: sent again as soon as the write before it lands (B2). */
	gapped?: boolean;
	/** A no-op's given-up write, settled from the no-op's answer. */
	origin?: Pending;
	/** A capture's journal entry, and the text it was written with (F2). */
	capture?: { key: string; text: string; rowJson: string };
	resolve(value: unknown): void;
	reject(error: unknown): void;
}

/** What a given-up seq is written as: an upsert of nothing, which `apply` consumes. */
const SKIP: Write = { kind: 'upsert', rows: [] };

const noop = () => {};

export class HistoryDbClient {
	readonly tab: string;
	readonly #o: ClientOptions;
	readonly #now: () => number;
	readonly #timers: Timers;
	#status: ClientStatus = { health: 'loading', reason: 'starting', message: null, owner: null, ownerHere: false };
	#worker: WorkerLike | null = null;
	#rpc: ChannelLike | null = null;
	#changes: ChannelLike | null = null;
	#seq = 0;
	#wseq = 0;
	/** Every request without its answer, by id. */
	readonly #pending = new Map<string, Pending>();
	/** The writes without their answer, in seq order (M11). */
	#outbox: Pending[] = [];
	#tick: unknown = null;
	#idle: unknown = null;
	#lastTick = 0;
	#pingN = 0;
	#lastPong = 0;
	#since = 0;
	/** The last time an owner this client accepts answered anything. */
	#lastHeard = 0;
	#gapResendAt = -Infinity;
	#wantOwner = true;
	#stopped = false;
	#releaseTabLocks: (() => void)[] = [];
	#tabLockHeld = false;
	#draining: Promise<unknown> | null = null;
	#restartWait = RESTART_FIRST_MS;
	#restartTimer: unknown = null;
	#workerStartedAt = 0;
	#flipped = false;
	readonly #diagnostics: string[] = [];
	readonly #unreadable = new Set<string>();
	#drainAgain = false;
	readonly #statusListeners = new Set<(s: ClientStatus) => void>();
	readonly #changeListeners = new Set<(c: ChangeSet) => void>();
	readonly #unlisten: (() => void)[] = [];

	constructor(o: ClientOptions) {
		this.#o = o;
		this.#now = o.now ?? (() => Date.now());
		this.#timers = o.timers ?? globalTimers;
		this.tab = o.tab ?? crypto.randomUUID();
	}

	get status(): ClientStatus {
		return this.#status;
	}

	/** The last few things worth a line in Settings' diagnostics (N5). */
	get diagnostics(): readonly string[] {
		return this.#diagnostics;
	}

	#diag(line: string): void {
		this.#diagnostics.push(line);
		if (this.#diagnostics.length > 50) this.#diagnostics.shift();
		console.warn(`history-db: ${line}`);
	}

	onStatus(f: (s: ClientStatus) => void): () => void {
		this.#statusListeners.add(f);
		return () => this.#statusListeners.delete(f);
	}

	/** Every write's `ChangeSet`, from the owner this client accepts (SPEC §10). */
	onChange(f: (c: ChangeSet) => void): () => void {
		this.#changeListeners.add(f);
		return () => this.#changeListeners.delete(f);
	}

	start(): void {
		if (this.#tick || this.#stopped) return;
		this.#since = this.#now();
		this.#takeTabLocks();
		this.#rpc = this.#o.channel(RPC_CHANNEL);
		this.#rpc.onmessage = (e) => this.#onRpc(e.data as RpcMessage);
		this.#changes = this.#o.channel(CHANGES_CHANNEL);
		this.#changes.onmessage = (e) => this.#onChange(e.data as Change);
		this.#startWorker();
		this.#hello();
		this.#lastTick = this.#now();
		this.#tick = this.#timers.setInterval(() => this.#onTick(), TICK_MS);
		this.#listen();
	}

	// ── Calls ──────────────────────────────────────────────────────────────

	/** A read, answered by the owner. Rejects after 30 s without one. */
	read<T = unknown>(name: ReadName, arg?: unknown): Promise<T> {
		if (this.#stopped) return Promise.reject(new HistoryDbError('stopped', 'the history store is stopped'));
		return this.#send(this.#header({ op: 'read', name, arg })) as Promise<T>;
	}

	/**
	 * A write, applied through the owner's `apply` in this tab's order (M11).
	 * Stage 2 only (stage 1's index follows the P3 store, and no tab writes it),
	 * and refused while the database is unavailable to this tab (read-only).
	 */
	write(write: Write): Promise<Applied> {
		if (this.#stopped) return Promise.reject(new HistoryDbError('stopped', 'the history store is stopped'));
		if (this.#o.stage !== 2) {
			return Promise.reject(new HistoryDbError('stage1', 'stage 1 writes the P3 store; the index follows it'));
		}
		const s = this.#status;
		if (s.health !== 'ok' && s.health !== 'loading') {
			return Promise.reject(new HistoryDbError('readOnly', s.message ?? OTHER_WINDOW_MESSAGE));
		}
		return this.#queueWrite(this.#writeRequest(write));
	}

	#writeRequest(write: Write, wseq = ++this.#wseq): Request & { op: 'write' } {
		return this.#header({ op: 'write', wseq, atMs: this.#now(), write }) as Request & { op: 'write' };
	}

	#queueWrite(req: Request, extra: Partial<Pending> = {}): Promise<Applied> {
		return new Promise<Applied>((resolve, reject) => {
			const p: Pending = {
				req,
				firstAt: this.#now(),
				sentAt: 0,
				resolve: resolve as (v: unknown) => void,
				reject,
				...extra
			};
			this.#pending.set(req.id, p);
			this.#outbox.push(p);
			this.#markFront();
			this.#transmit(p);
		});
	}

	/** The outbox's front write starts its 30 s now, if it hasn't (B1). */
	#markFront(): void {
		const f = this.#outbox.find((q) => !q.skip);
		if (f && f.frontAt === undefined) f.frontAt = this.#now();
	}

	/**
	 * A new shot's row (stage 2). Journaled first (M9: nothing durable lives
	 * only in memory), then written; the entry goes once its write commits.
	 * While the database is unavailable to this tab, it stays journaled for the
	 * owner's drain.
	 */
	async capture(rowJson: string): Promise<'written' | 'journaled'> {
		const storage = this.#o.storage;
		if (!storage || this.#o.stage !== 2) throw new HistoryDbError('stage1', 'captures journal on stage 2 only');
		if (this.#stopped || this.#status.health !== 'ok') {
			// No write goes: journaled with no seq, for the owner's insert-if-absent.
			appendPending(storage, rowJson);
			return 'journaled';
		}
		// The write's seq is given first and journaled with the row (F2), so a
		// drain can tell this capture landed even if the shot is deleted after.
		const req = this.#writeRequest({ kind: 'upsert', rows: [{ rowJson }] });
		let entry: ReturnType<typeof appendPending> | null = null;
		try {
			entry = appendPending(storage, rowJson, { tab: this.tab, wseq: req.wseq });
		} catch (e) {
			// B3: the journal is full, but the owner is ok: the write still goes,
			// first in SPEC §9's ladder. If it's given up, the caller hears, and
			// keeps the shot in memory with the banner.
			this.#diag(`the pending journal refused a capture (${(e as Error).name ?? 'error'}); writing it without one`);
		}
		try {
			const applied = await this.#queueWrite(
				req,
				entry ? { capture: { key: entry.key, text: entry.text, rowJson } } : {}
			);
			if (applied.outcome === 'done' || applied.outcome === 'alreadyApplied') {
				if (entry) removeIfUnchanged(storage, entry.key, entry.text);
				return 'written';
			}
		} catch (e) {
			// With no journal entry, nothing durable holds the shot: say so.
			if (!entry) throw e;
			// Kept in the journal: the owner drains it.
		}
		return 'journaled';
	}

	/**
	 * For a factory reset (S10): the Worker closes its file, pauses its pool and
	 * lets the lock go, and never takes it again. Then the Worker, the channels
	 * and the tab's locks go. Every call after this is refused.
	 */
	async stop(): Promise<void> {
		if (this.#stopped) return;
		this.#stopped = true;
		this.#wantOwner = false;
		const w = this.#worker;
		if (w) {
			await this.#ask(w, (n) => ({ t: 'stop', n }), STOP_WAIT_MS);
			w.terminate();
		}
		this.#worker = null;
		if (this.#restartTimer) this.#timers.clearTimeout(this.#restartTimer);
		this.#restartTimer = null;
		this.#teardown();
		for (const p of this.#pending.values()) {
			const e = new HistoryDbError('stopped', 'the history store stopped');
			p.reject(e);
			p.origin?.reject(e);
		}
		this.#pending.clear();
		this.#outbox = [];
	}

	// ── Requests ───────────────────────────────────────────────────────────

	#header<T extends object>(body: T): Request {
		const seq = ++this.#seq;
		return {
			t: 'req',
			v: PROTOCOL_VERSION,
			build: this.#o.build,
			stage: this.#o.stage,
			tab: this.tab,
			seq,
			id: `${this.tab}:${seq}`,
			expect: this.#o.expect,
			...body
		} as unknown as Request;
	}

	#send(req: Request): Promise<unknown> {
		return new Promise((resolve, reject) => {
			const p: Pending = { req, firstAt: this.#now(), sentAt: 0, resolve, reject };
			this.#pending.set(req.id, p);
			this.#transmit(p);
		});
	}

	#transmit(p: Pending): void {
		p.sentAt = this.#now();
		p.answered = false;
		p.gapped = false;
		if (this.#status.ownerHere && this.#worker) this.#worker.postMessage(p.req);
		else this.#rpc?.postMessage(p.req);
	}

	/** Resend every read, then the whole outbox in seq order (M11). */
	#resendAll(): void {
		for (const p of this.#pending.values()) if (p.req.op !== 'write') this.#transmit(p);
		for (const p of this.#outbox) this.#transmit(p);
	}

	#onReply(r: Reply): void {
		if (r.to !== this.tab) return;
		const p = this.#pending.get(r.id);
		if (!p) return;
		if (!acceptsOwner(this.#o.stage, this.#o.expect, r.owner)) {
			// M1: never use an owner of another build or stage; keep waiting for one we can.
			this.#cannotUse('otherBuild', r.owner);
			return;
		}
		if (!r.ok && r.error.code === 'notOwner') {
			// That Worker let go: the request goes to whoever owns now.
			if (r.owner.tab === this.tab) this.#setStatus({ ownerHere: false });
			return;
		}
		this.#answered(r.owner);
		if (!r.ok) {
			if (p.req.op === 'write') {
				// F1: the owner threw before or inside `apply` (its health, a full
				// disk, another stage or file), so the seq wasn't consumed. Never
				// drop it: it stays, in order, is resent, and after 30 s is given up
				// as a no-op that consumes the seq (`#giveUp`).
				p.answered = true;
				p.lastError = r.error;
				// A given-up write's no-op failed too: the write hasn't landed.
				if (p.origin) {
					p.origin.lastError ??= r.error;
					this.#settleOrigin(p, null);
				}
				return;
			}
			this.#settle(p);
			p.reject(new HistoryDbError(r.error.code, r.error.message));
			return;
		}
		if (p.req.op !== 'write') {
			this.#settle(p);
			p.resolve(r.value);
			return;
		}
		const a = r.value as Applied;
		const wseq = p.req.wseq;
		if (a.outcome === 'gap') {
			// A write before it never arrived: send the outbox again, in order.
			// A missing seq the outbox no longer holds (the owner pruned this
			// tab's entry, C2a note 7) never will: it's sent as a no-op first.
			const held = new Set(this.#outbox.map((q) => (q.req.op === 'write' ? q.req.wseq : 0)));
			const first = Math.min(...held);
			const skips: Pending[] = [];
			for (let s = a.last + 1; s < first; s++) {
				if (!held.has(s)) skips.push(this.#skipFor(s));
			}
			this.#outbox.unshift(...skips);
			for (const q of skips) this.#transmit(q);
			// B2: never the whole outbox on every gap. This write goes again as soon
			// as the one before it lands. That one is resent only if it's neither
			// on its way nor answered with an error (the tick resends those), and
			// the whole outbox at most once per ANSWER_MS.
			p.gapped = true;
			const now = this.#now();
			const before = this.#outbox.find((q) => q.req.op === 'write' && q.req.wseq === a.last + 1);
			if (skips.length || (before && (before.answered || now - before.sentAt < ANSWER_MS))) return;
			if (now - this.#gapResendAt < ANSWER_MS) return;
			this.#gapResendAt = now;
			for (const q of this.#outbox) this.#transmit(q);
			return;
		}
		// `done` for this seq; `alreadyApplied` means every seq up to `last` is in.
		const upTo = a.outcome === 'alreadyApplied' ? Math.max(a.last, wseq) : wseq;
		for (const q of [...this.#outbox]) {
			if (q.req.op !== 'write' || q.req.wseq > upTo) continue;
			this.#settle(q);
			const outcome: Applied = q === p ? a : { outcome: 'alreadyApplied', last: upTo };
			if (q.origin) this.#settleOrigin(q, q === p ? a : null);
			if (outcome.outcome === 'refused') q.reject(new HistoryDbError('refused', outcome.reason));
			else q.resolve(outcome);
		}
		this.#markFront();
		// B2: the writes the owner answered `gap` go now, in order.
		for (const q of this.#outbox) {
			if (!q.gapped) continue;
			q.gapped = false;
			this.#transmit(q);
		}
	}

	/**
	 * A given-up write's caller hears from its no-op: `done` means the write
	 * never landed (the no-op took its seq), `alreadyApplied` that it did. A
	 * no-op settled by a later write's answer can't tell, so that's a failure.
	 */
	#settleOrigin(skip: Pending, own: Applied | null): void {
		const o = skip.origin!;
		skip.origin = undefined;
		if (own?.outcome === 'alreadyApplied') o.resolve(own);
		else o.reject(o.lastError ? new HistoryDbError(o.lastError.code, o.lastError.message) : new HistoryDbError('unavailable', 'the history store did not take the write'));
	}

	/** A no-op write that consumes `wseq` (F1). */
	#skipFor(wseq: number): Pending {
		const p: Pending = {
			req: this.#writeRequest(SKIP, wseq),
			firstAt: this.#now(),
			sentAt: 0,
			skip: true,
			resolve: noop,
			reject: noop
		};
		this.#pending.set(p.req.id, p);
		return p;
	}

	/**
	 * F1: a write the owner hasn't taken in 30 s is given up. A capture's
	 * journal entry first loses its seq (so the drain inserts it if absent,
	 * rather than read the no-op as the capture having landed); then the seq
	 * goes as a no-op in the write's place, so the tab's later writes don't
	 * wait on it; then the caller hears.
	 */
	#giveUp(p: Pending): void {
		const storage = this.#o.storage;
		if (p.capture && storage) {
			replaceIfUnchanged(storage, p.capture.key, p.capture.text, entryText({ row: p.capture.rowJson }));
		}
		const skip = this.#skipFor((p.req as Request & { op: 'write' }).wseq);
		const i = this.#outbox.indexOf(p);
		if (i >= 0) this.#outbox.splice(i, 1, skip);
		this.#pending.delete(p.req.id);
		// An owner answering now settles the caller from the no-op's answer: a
		// write merely queued at a busy owner may have landed. With no owner
		// heard for 5 s, the caller hears at once.
		if (this.#now() - this.#lastHeard < ANSWER_MS) skip.origin = p;
		else {
			p.reject(
				new HistoryDbError(p.lastError?.code ?? 'unavailable', p.lastError?.message ?? 'the history store did not take the write')
			);
		}
		this.#transmit(skip);
		this.#markFront();
	}

	#settle(p: Pending): void {
		this.#pending.delete(p.req.id);
		const i = this.#outbox.indexOf(p);
		if (i >= 0) {
			this.#outbox.splice(i, 1);
			this.#markFront();
		}
	}

	// ── The owner ──────────────────────────────────────────────────────────

	#onRpc(m: RpcMessage): void {
		switch (m.t) {
			case 'reply':
				this.#onReply(m);
				return;
			case 'ready':
				if (!acceptsOwner(this.#o.stage, this.#o.expect, m.owner)) {
					this.#cannotUse('otherBuild', m.owner);
					return;
				}
				this.#answered(m.owner, m.owner.tab === this.tab);
				this.#resendAll();
				return;
			case 'flipped':
				// N4: only a stage-1 tab acts; a stage-2 tab already reads history.db.
				if (this.#o.stage === 1 && !this.#flipped) {
					this.#flipped = true;
					this.#o.onFlipped?.();
				}
				return;
			case 'gone':
				if (this.#status.owner?.tab === m.owner.tab) {
					this.#setStatus({ owner: null, ownerHere: false });
					this.#since = this.#now();
				}
				return;
			default:
				return;
		}
	}

	#onChange(m: Change): void {
		if (m?.t !== 'change' || !acceptsOwner(this.#o.stage, this.#o.expect, m.owner)) return;
		for (const f of this.#changeListeners) f(m.change);
	}

	/** An owner this client accepts answered: its health is ours. */
	#answered(owner: OwnerInfo, here?: boolean): void {
		this.#since = this.#now();
		this.#lastHeard = this.#since;
		const health: ClientHealth = owner.health === 'ok' ? 'ok' : owner.health;
		this.#setStatus({
			health,
			reason: owner.health === 'ok' ? 'ok' : `owner:${owner.health}:${owner.reason}`,
			message: null,
			owner,
			...(here === undefined ? {} : { ownerHere: here })
		});
	}

	/** No owner this client can use: stage 1 falls back, stage 2 is read-only (S2). */
	#cannotUse(reason: 'noAnswer' | 'otherBuild', owner: OwnerInfo | null = null): void {
		this.#setStatus({
			health: this.#o.stage === 1 ? 'index_fallback' : 'unavailable',
			reason,
			message: reason === 'otherBuild' ? OTHER_BUILD_MESSAGE : this.#o.stage === 2 ? OTHER_WINDOW_MESSAGE : null,
			owner
		});
	}

	#setStatus(patch: Partial<ClientStatus>): void {
		const next = { ...this.#status, ...patch };
		const s = this.#status;
		if (
			next.health === s.health &&
			next.reason === s.reason &&
			next.ownerHere === s.ownerHere &&
			next.owner?.tab === s.owner?.tab &&
			next.owner?.health === s.owner?.health
		) {
			this.#status = next;
			return;
		}
		this.#status = next;
		for (const f of this.#statusListeners) f(next);
	}

	#hello(): void {
		this.#rpc?.postMessage({
			t: 'hello',
			v: PROTOCOL_VERSION,
			tab: this.tab,
			build: this.#o.build,
			stage: this.#o.stage
		} satisfies RpcMessage);
	}

	#onTick(): void {
		const now = this.#now();
		// A tick long after the last: the tab was frozen, and so was its Worker.
		// Give the Worker a fresh wait rather than killing it for the freeze.
		if (now - this.#lastTick > 3 * TICK_MS) this.#lastPong = now;
		this.#lastTick = now;
		let late = false;
		const heard = now - this.#lastHeard < ANSWER_MS;
		const front = this.#outbox.find((q) => !q.skip);
		for (const p of [...this.#pending.values()]) {
			if (p.req.op === 'write') {
				// F1, B1: a write gives up after 30 s at the front of the outbox, one
				// at a time: a write behind it waits on it, not on the owner, and a
				// healthy owner's long queue doesn't count against it.
				if (p === front && p.frontAt !== undefined && now - p.frontAt >= GATED_MS) {
					this.#giveUp(p);
					continue;
				}
				// A no-op whose own answer hasn't come settles its caller as failed.
				if (p.origin && now - p.firstAt >= 2 * ANSWER_MS) this.#settleOrigin(p, null);
			} else if (now - p.firstAt >= GATED_MS) {
				this.#settle(p);
				p.reject(new HistoryDbError('unavailable', this.#status.message ?? 'the history store did not answer'));
				continue;
			}
			if (now - p.sentAt < ANSWER_MS) continue;
			// Answered with an error or `gap`, or late at an owner that answers
			// other calls (busy): this one again, alone. Late with no owner heard
			// from for 5 s: the owner can't answer.
			if (p.answered || p.gapped || heard) this.#transmit(p);
			else late = true;
		}
		if (late || (!this.#status.owner && now - this.#since >= ANSWER_MS)) {
			// An owner of another build answers, but not as one we may use: that stays the reason.
			if (this.#status.reason !== 'otherBuild') this.#cannotUse('noAnswer');
			this.#since = now;
			this.#hello();
			this.#resendAll();
		}
		this.#watchWorker(now);
	}

	// ── The Worker ─────────────────────────────────────────────────────────

	#startWorker(): void {
		const w = this.#o.makeWorker();
		this.#worker = w;
		this.#lastPong = this.#now();
		this.#workerStartedAt = this.#now();
		w.onmessage = (e) => {
			if (this.#worker === w) this.#onWorker(e.data as WorkerToPage);
		};
		w.onerror = () => {
			if (this.#worker === w) this.#restartWorker();
		};
		const t = this.#o.target;
		w.postMessage({ t: 'config', tab: this.tab, build: this.#o.build, stage: this.#o.stage, target: t } satisfies PageToWorker);
		if (this.#wantOwner) w.postMessage({ t: 'acquire' } satisfies PageToWorker);
	}

	/**
	 * S1: a Worker that errors or stops answering is terminated; a new one
	 * queues for the lock, after a wait that doubles from 1 s to 60 s while
	 * restarts keep coming, so a Worker that can't load isn't restarted in a
	 * tight loop (N1). Meanwhile this tab is a client of whoever owns.
	 */
	#restartWorker(): void {
		if (this.#stopped) return;
		this.#worker?.terminate();
		this.#worker = null;
		this.#setStatus({ ownerHere: false });
		this.#resendAll();
		if (this.#restartTimer) return;
		const wait = this.#restartWait;
		this.#restartWait = Math.min(wait * 2, RESTART_MAX_MS);
		this.#restartTimer = this.#timers.setTimeout(() => {
			this.#restartTimer = null;
			if (this.#stopped) return;
			this.#startWorker();
			this.#resendAll();
		}, wait);
	}

	#watchWorker(now: number): void {
		const w = this.#worker;
		if (!w) return;
		if (now - this.#lastPong >= WORKER_DEAD_MS) {
			this.#restartWorker();
			return;
		}
		w.postMessage({ t: 'ping', n: ++this.#pingN } satisfies PageToWorker);
	}

	#onWorker(m: WorkerToPage): void {
		switch (m.t) {
			case 'pong':
				this.#lastPong = this.#now();
				if (this.#lastPong - this.#workerStartedAt >= RESTART_RESET_AFTER_MS) this.#restartWait = RESTART_FIRST_MS;
				return;
			case 'state':
				if (m.owner && m.info) {
					this.#answered(m.info, true);
					this.#resendAll();
					if (this.#o.stage === 2) void this.#drain();
				} else if (this.#status.ownerHere) {
					this.#setStatus({ ownerHere: false, owner: null });
					this.#since = this.#now();
					this.#hello();
				}
				return;
			case 'drainNow':
				if (this.#o.stage === 2) void this.#drain();
				return;
			case 'reply':
				this.#onReply(m);
				return;
			case 'released':
				this.#released.get(m.n)?.();
				this.#released.delete(m.n);
				return;
		}
	}

	readonly #released = new Map<number, () => void>();
	#releaseN = 0;

	/** Sends `make(n)` to `w` and waits for its `released`, at most `ms`. */
	#ask(w: WorkerLike, make: (n: number) => PageToWorker, ms: number): Promise<void> {
		const n = ++this.#releaseN;
		return new Promise<void>((resolve) => {
			const t = this.#timers.setTimeout(() => {
				this.#released.delete(n);
				resolve();
			}, ms);
			this.#released.set(n, () => {
				this.#timers.clearTimeout(t);
				resolve();
			});
			w.postMessage(make(n));
		});
	}

	/** Give the database up (pagehide, hidden and idle). This tab stays a client. */
	release(): void {
		this.#wantOwner = false;
		this.#worker?.postMessage({ t: 'release', n: ++this.#releaseN } satisfies PageToWorker);
	}

	/** Queue for the database again (pageshow, visible). */
	acquire(): void {
		if (this.#stopped) return;
		this.#wantOwner = true;
		this.#worker?.postMessage({ t: 'acquire' } satisfies PageToWorker);
		this.#worker?.postMessage({ t: 'retry' } satisfies PageToWorker);
	}

	// ── The tab's locks and the page's events ──────────────────────────────

	#takeTabLocks(): void {
		const { locks } = this.#o;
		const hold = (name: string, mode: 'shared' | 'exclusive', ifAvailable: boolean) => {
			const abort = new AbortController();
			let release = noop;
			const held = new Promise<void>((r) => (release = r));
			void locks
				.request(name, { mode, ifAvailable, signal: ifAvailable ? undefined : abort.signal }, (lock) => {
					if (!lock) return undefined;
					if (mode === 'exclusive') this.#tabLockHeld = true;
					return held;
				})
				.catch(() => {})
				.finally(() => {
					if (mode === 'exclusive') this.#tabLockHeld = false;
				});
			this.#releaseTabLocks.push(() => {
				abort.abort();
				release();
			});
		};
		hold(STAGE_LOCK, 'shared', false);
		// Already ours (a bfcache restore that kept it): `ifAvailable` answers null and nothing changes.
		if (!this.#tabLockHeld) hold(TAB_LOCK_PREFIX + this.tab, 'exclusive', true);
	}

	#listen(): void {
		const ev = this.#o.events;
		if (!ev) return;
		const on = (type: string, f: (e: never) => void) => {
			ev.addEventListener(type, f);
			this.#unlisten.push(() => ev.removeEventListener(type, f));
		};
		on('pagehide', () => {
			this.#clearIdle();
			this.release();
		});
		on('pageshow', (e: { persisted?: boolean }) => {
			if (!e.persisted) return;
			this.#takeTabLocks();
			if (!this.#worker) this.#startWorker();
			this.acquire();
			this.#hello();
		});
		on('visibilitychange', () => {
			if (ev.visibility() === 'hidden') this.#armIdle();
			else {
				this.#clearIdle();
				this.acquire();
			}
		});
		on('storage', (e: { key?: string | null }) => {
			if (this.#o.stage === 2 && this.#status.ownerHere && e.key?.startsWith(PENDING_KEY_PREFIX)) void this.#drain();
		});
	}

	#armIdle(): void {
		this.#clearIdle();
		this.#idle = this.#timers.setTimeout(() => {
			this.#idle = null;
			if (this.#o.events?.visibility() !== 'hidden') return;
			// Idle: nothing of ours in flight. Otherwise look again later.
			// A no-op seq still on its way isn't the user's work (F1, N6).
			const busy = [...this.#pending.values()].some((p) => !p.skip);
			if (busy || this.#draining) this.#armIdle();
			else this.release();
		}, IDLE_RELEASE_MS);
	}

	#clearIdle(): void {
		if (this.#idle) this.#timers.clearTimeout(this.#idle);
		this.#idle = null;
	}

	// ── The pending journal ────────────────────────────────────────────────

	/** One drain at a time; a trigger during one runs another after it. */
	#drain(): Promise<unknown> {
		const storage = this.#o.storage;
		if (!storage) return Promise.resolve();
		if (this.#draining) {
			this.#drainAgain = true;
			return this.#draining;
		}
		const run = async () => {
			do {
				this.#drainAgain = false;
				if (!this.#status.ownerHere || this.#stopped) return;
				const done = await drainPending(
					storage,
					(entries) => this.#send(this.#header({ op: 'drain', entries })) as Promise<Drained>,
					(key) => {
						if (this.#unreadable.has(key)) return;
						this.#unreadable.add(key);
						this.#diag(`pending journal entry ${key} isn't a shot row; left in place`);
					}
				);
				for (const id of done.refused) this.#diag(`pending journal entry for ${id} was refused by the store; left in place`);
			} while (this.#drainAgain);
		};
		this.#draining = run()
			.catch(() => {
				// The entries stay for the next drain.
			})
			.finally(() => (this.#draining = null));
		return this.#draining;
	}

	#teardown(): void {
		if (this.#tick) this.#timers.clearInterval(this.#tick);
		this.#tick = null;
		this.#clearIdle();
		for (const f of this.#unlisten.splice(0)) f();
		for (const f of this.#releaseTabLocks.splice(0)) f();
		if (this.#rpc) {
			this.#rpc.onmessage = null;
			this.#rpc.close();
		}
		if (this.#changes) {
			this.#changes.onmessage = null;
			this.#changes.close();
		}
		this.#rpc = this.#changes = null;
	}
}
