/**
 * `#lib/history-db/owner.js` — the history Worker's side (#310 SPEC §8, PLAN
 * W1): it takes the owner lock, installs its pool and opens its file, and then
 * answers every tab, its own included, one call at a time.
 *
 * - **The Worker takes the lock** (S1), so a Worker that crashes or is killed
 *   releases it. Its page only asks it to `acquire`, `release` or `stop`.
 * - **Install, with retries.** The previous owner's handles can outlive its
 *   lock, so an install that fails on a held handle is retried, 100 ms doubling
 *   to 5 s, for about 30 s, before the file is `unavailable` (`index_fallback`
 *   on stage 1).
 * - **Lost or unavailable** comes from `poolInfo().files`, not from
 *   `cantOpen` (REVIEW-444 note 7): an `authority` file the pool doesn't list
 *   is `lost`; one it lists but can't open is `unavailable`, or `lost` when
 *   it's corrupt or foreign. An `authority` file is never created here.
 * - **A repair generation** is installed only when its `.repair-done` is there
 *   and names the file (REVIEW-444 notes 2 and 3).
 * - **One call at a time** (REVIEW-444 note 5): every request, from any tab,
 *   waits for the one before it, so no call runs while another waits for a
 *   pool slot.
 * - **Owner ready** goes out on `crema-history-rpc` when it starts serving,
 *   and again to every hello; **gone** when it lets go. Both come from the
 *   Worker, as every reply does: a frozen page's main thread still gets
 *   `BroadcastChannel` messages, its Worker doesn't (W0).
 * - **Writes** go through the store's `apply` with the tab's write seq (M11),
 *   so a resend, or a resend to the next owner, is applied once. A client of
 *   another stage may read but not write (M1). Every write's `ChangeSet` goes
 *   out on `crema-history-changes`.
 * - **Release** (handover, the page hidden and idle, a factory reset):
 *   finish the calls queued until the queue is quiet, close the file, pause
 *   the pool, then let the lock go. `pause` refuses while a file is open, so
 *   the order is the rule. A `retry` while letting go is ignored, and an open
 *   in progress stops at its next step, so nothing opens after the lock is
 *   gone (REVIEW-456 F3); an `acquire` while letting go queues again after.
 * - **A paused pool** lists no files, so it is `unavailable`, never `lost` (N3).
 * - **The drain** (M5, F2) applies a journaled capture as its own write, under
 *   its tab and seq, so a capture that already landed is never inserted again,
 *   even after the user deleted it or while an export holds it.
 * - **Stage 1's index** (W2): once the index is open, its upkeep
 *   (`index-sync.ts`) keeps it in step with the P3 store, which it reads
 *   itself. Until the boot check passes or its full diff is done the index
 *   is behind, so the owner says `index_fallback` and refuses its reads, and
 *   every tab lists from its in-memory rows (SPEC §3 "A miss"). An index call
 *   that fails for good closes the file and opens it again (`create`), so an
 *   index deleted under the owner is rebuilt. `live` takes a tab's live
 *   libraries for the index's queries.
 * - **Every change set** goes out once, in `write_seq` order: a write that
 *   changed nothing carries a seq already posted, and isn't (S14).
 * - Nothing here deletes a file, a pool or a slot.
 */
import type { ChannelLike } from './ports.js';
import type { LockManagerLike } from './ports.js';
import { engineError, type Engine, type EngineDb, type EngineError } from './engine.js';
import { CHANGES_CHANNEL, OWNER_LOCK, RPC_CHANNEL, TAB_LOCK_PREFIX } from './names.js';
import type { IndexSyncLike, SyncDeps } from './index-sync.js';
import {
	PROTOCOL_VERSION,
	READS,
	acceptsOwner,
	type Applied,
	type ChangeSet,
	type Drained,
	type JournalEntry,
	type OwnerHealth,
	type OwnerInfo,
	type Reply,
	type Request,
	type RpcMessage,
	type Stage,
	type Target
} from './protocol.js';

/** The install's backoff: the first wait, the longest, and how long it keeps trying (SPEC §8). */
export const INSTALL_FIRST_WAIT_MS = 100;
export const INSTALL_MAX_WAIT_MS = 5_000;
export const INSTALL_BUDGET_MS = 30_000;
/** How long a tab's `rpc_applied` entry outlives its lock (SPEC §8). */
export const PRUNE_AFTER_MS = 24 * 60 * 60 * 1000;
/** How often an owner prunes. */
export const PRUNE_EVERY_MS = 6 * 60 * 60 * 1000;

/** An index that failed for good is opened again after this. */
export const REOPEN_WAIT_MS = 1_000;

/** Errors that say the index file itself failed, which a reopen mends (W2). */
const BROKEN = new Set(['cantOpen', 'corrupt', 'poisoned', 'readOnly']);

/** Calls that answer while the index is behind: its bookkeeping, not its rows. */
const WHILE_BEHIND = new Set(['indexState', 'info', 'health', 'writeSeq', 'ftsStatus', 'indexSync']);

/** Install errors no wait will clear. */
const PERMANENT = new Set(['pool.invalidHeader', 'pool.configurationMismatch', 'pool.notSupported']);

/** From the page to its Worker. */
export type PageToWorker =
	| { t: 'config'; tab: string; build: string; stage: Stage; target: Target }
	| { t: 'acquire' }
	/** Let the lock go; `stop` also never takes it again (factory reset). */
	| { t: 'release'; n: number }
	| { t: 'stop'; n: number }
	/** Try the file again if it failed (the page came back to the front). */
	| { t: 'retry' }
	| { t: 'ping'; n: number }
	| Request;

/** From the Worker to its page. */
export type WorkerToPage =
	| { t: 'pong'; n: number }
	/** Whether this Worker owns the database, and as whom. */
	| { t: 'state'; owner: boolean; info: OwnerInfo | null }
	| { t: 'released'; n: number }
	/** Drain the pending journal now: the lock arrived, or a client said hello (M5). */
	| { t: 'drainNow' }
	| Reply;

export interface WorkerScope {
	postMessage(message: unknown): void;
	onmessage: ((event: { data: unknown }) => void) | null;
}

export interface OwnerDeps {
	scope: WorkerScope;
	engine: Engine;
	locks: LockManagerLike;
	channel(name: string): ChannelLike;
	now?(): number;
	/**
	 * Stage 1's index upkeep over an open index (W2): `index-sync.ts` over the
	 * P3 store. Without it the index is served as it is (W1's tests).
	 */
	indexSync?(deps: Pick<SyncDeps, 'db' | 'queue' | 'notify' | 'state' | 'broken'>): IndexSyncLike;
}

type Phase = 'idle' | 'waiting' | 'owner' | 'stopped';

class Owner {
	readonly #d: OwnerDeps;
	readonly #now: () => number;
	#tab = '';
	#build = '';
	#stage: Stage = 1;
	#target: Target | null = null;
	#phase: Phase = 'idle';
	#stopping = false;
	#releasing = false;
	#abort: AbortController | null = null;
	#letGo: (() => void) | null = null;
	#held: Promise<unknown> | null = null;
	#queue: Promise<unknown> = Promise.resolve();
	#db: EngineDb | null = null;
	#health: OwnerHealth = 'unavailable';
	#reason = 'not open';
	#rpc: ChannelLike | null = null;
	#changes: ChannelLike | null = null;
	#seen = new Set<string>();
	/** `acquire` came while letting go: queue again once released. */
	#again = false;
	#prune: ReturnType<typeof setInterval> | null = null;
	/** Stage 1's index upkeep, while the index is open. */
	#sync: IndexSyncLike | null = null;
	/** Whether the index can answer: always, without an upkeep. */
	#indexReady = true;
	#indexReason = 'ok';
	/** The last `write_seq` posted (S14). */
	#posted = 0;
	#reopen: ReturnType<typeof setTimeout> | null = null;

	constructor(d: OwnerDeps) {
		this.#d = d;
		this.#now = d.now ?? (() => Date.now());
	}

	info(): OwnerInfo {
		const t = this.#target!;
		const behind = this.#health === 'ok' && !this.#indexReady;
		return {
			v: PROTOCOL_VERSION,
			tab: this.#tab,
			build: this.#build,
			stage: this.#stage,
			role: t.role,
			file: t.file,
			generation: t.generation,
			health: behind ? 'index_fallback' : this.#health,
			reason: behind ? this.#indexReason : this.#reason
		};
	}

	onPage(m: PageToWorker): void {
		switch (m.t) {
			case 'config':
				this.#tab = m.tab;
				this.#build = m.build;
				this.#stage = m.stage;
				this.#target = m.target;
				return;
			case 'ping':
				this.#post({ t: 'pong', n: m.n });
				return;
			case 'acquire':
				this.acquire();
				return;
			case 'release':
			case 'stop':
				void this.release(m.t === 'stop').then(() => this.#post({ t: 'released', n: m.n }));
				return;
			case 'retry':
				// Never while letting go: the open would land after the lock is gone (F3).
				// Open and in step: the boot check again, as on resume (W2).
				if (this.#phase === 'owner' && !this.#releasing && this.#health === 'ok') this.#sync?.resume();
				if (this.#phase === 'owner' && !this.#releasing && this.#health !== 'ok') {
					this.#enqueue(async () => {
						await this.#open();
						// Letting go meanwhile: no `ready` just before `gone`.
						if (!this.#releasing) this.#announce();
					});
				}
				return;
			case 'req':
				this.#request(m, (r) => this.#post(r));
				return;
		}
	}

	#post(m: WorkerToPage): void {
		this.#d.scope.postMessage(m);
	}

	/** Queue for the owner lock; when it arrives, open the file and serve. */
	acquire(): void {
		if (!this.#target || this.#phase === 'stopped' || this.#stopping) return;
		if (this.#phase !== 'idle') {
			// Asked again while letting go (pagehide, then pageshow at once):
			// queue for the lock again once this hold has ended (F3).
			if (this.#releasing) this.#again = true;
			return;
		}
		this.#phase = 'waiting';
		this.#stopping = false;
		this.#releasing = false;
		const abort = new AbortController();
		this.#abort = abort;
		this.#held = this.#d.locks
			.request(OWNER_LOCK, { mode: 'exclusive', signal: abort.signal }, () => this.#own())
			.catch(() => {
				// Aborted while queued: nothing was held.
			})
			.finally(() => {
				if (this.#abort === abort) this.#abort = null;
				this.#phase = this.#stopping ? 'stopped' : 'idle';
				if (this.#again && !this.#stopping) {
					this.#again = false;
					this.acquire();
				}
			});
	}

	/** Holds the lock: open, serve until released, then close, pause, let go. */
	async #own(): Promise<void> {
		if (this.#releasing) return;
		this.#phase = 'owner';
		const released = new Promise<void>((r) => (this.#letGo = r));
		this.#rpc = this.#d.channel(RPC_CHANNEL);
		this.#changes = this.#d.channel(CHANGES_CHANNEL);
		this.#rpc.onmessage = (e) => this.#onRpc(e.data as RpcMessage);
		await this.#enqueue(() => this.#open());
		if (!this.#releasing) {
			this.#announce();
			this.#prune = setInterval(() => void this.#enqueue(() => this.#pruneTabs()), PRUNE_EVERY_MS);
		}
		await released;
		// Finish the call that runs, refuse the rest (`notOwner`), then close.
		// Until the queue is quiet: work queued while it drained runs before
		// the file closes, never after the lock is gone (F3).
		for (let q = this.#queue; ; q = this.#queue) {
			await q;
			if (q === this.#queue) break;
		}
		if (this.#prune) clearInterval(this.#prune);
		this.#prune = null;
		if (this.#reopen) clearTimeout(this.#reopen);
		this.#reopen = null;
		this.#close();
		const gone = this.info();
		this.#rpc.onmessage = null;
		this.#rpc.postMessage({ t: 'gone', owner: gone } satisfies RpcMessage);
		this.#rpc.close();
		this.#changes.close();
		this.#rpc = this.#changes = null;
		this.#post({ t: 'state', owner: false, info: null });
	}

	/** Let the lock go, or stop waiting for it. `final`: never take it again. */
	async release(final: boolean): Promise<void> {
		if (final) this.#stopping = true;
		this.#releasing = true;
		this.#again = false;
		if (this.#phase === 'waiting') this.#abort?.abort();
		this.#letGo?.();
		this.#letGo = null;
		await this.#held;
		if (final) this.#phase = 'stopped';
	}

	#announce(): void {
		const info = this.info();
		this.#rpc?.postMessage({ t: 'ready', owner: info } satisfies RpcMessage);
		this.#post({ t: 'state', owner: true, info });
		if (this.#stage === 2 && this.#health === 'ok') this.#post({ t: 'drainNow' });
		void this.#enqueue(() => this.#pruneTabs());
	}

	#enqueue<T>(f: () => Promise<T>): Promise<T> {
		const run = this.#queue.then(f, f);
		this.#queue = run.catch(() => {});
		return run;
	}

	#fail(health: OwnerHealth, reason: string): void {
		this.#health = health;
		this.#reason = reason;
	}

	/** Install (with the backoff), check the pool's list, open. Sets the health. */
	async #open(): Promise<void> {
		const t = this.#target!;
		const { engine } = this.#d;
		const stage1 = this.#stage === 1;
		// Letting go, or no longer the owner: stop before the next step (F3).
		const gone = () => this.#releasing || this.#phase !== 'owner';
		this.#close();
		if (gone()) return;
		if (t.pool === 'db' && t.generation > 0) {
			const done = await engine.readRepairDone('db', t.generation);
			if (gone()) return;
			if (!done || !done.copied.includes(`/${t.file}`)) {
				this.#fail('unavailable', 'repairUnfinished');
				return;
			}
		}
		const start = this.#now();
		let wait = INSTALL_FIRST_WAIT_MS;
		for (;;) {
			try {
				if (gone()) return;
				const info = await engine.installPool(t.pool, t.generation);
				if (gone()) return;
				if (info.paused) await engine.resumePool(t.pool);
				break;
			} catch (e) {
				const err = engineError(e);
				const late = this.#now() - start + wait > INSTALL_BUDGET_MS;
				if (PERMANENT.has(err.code) || late || this.#releasing) {
					this.#fail(stage1 ? 'index_fallback' : 'unavailable', err.code);
					return;
				}
				await new Promise((r) => setTimeout(r, wait));
				wait = Math.min(wait * 2, INSTALL_MAX_WAIT_MS);
			}
		}
		if (gone()) return;
		let listed: string[];
		try {
			const info = engine.poolInfo(t.pool);
			// A paused pool lists no files: that is "can't see", not "absent" (N3).
			if (info.paused) {
				this.#fail(stage1 ? 'index_fallback' : 'unavailable', 'paused');
				return;
			}
			listed = info.files;
		} catch (e) {
			this.#fail(stage1 ? 'index_fallback' : 'unavailable', engineError(e).code);
			return;
		}
		if (!t.create && !listed.includes(`/${t.file}`)) {
			this.#fail(stage1 ? 'index_fallback' : 'lost', 'absent');
			return;
		}
		try {
			const db = await engine.open({
				pool: t.pool,
				file: t.file,
				role: t.role,
				create: t.create && t.role === 'index',
				generation: t.generation,
				journal: t.journal
			});
			if (gone()) {
				db.close();
				return;
			}
			this.#db = db;
			this.#fail('ok', 'open');
			try {
				this.#posted = Number(await db.call('writeSeq')) || 0;
			} catch {
				this.#posted = 0;
			}
			if (gone()) return;
			if (stage1 && t.role === 'index') this.#startSync(db);
		} catch (e) {
			const code = engineError(e).code;
			if (stage1) this.#fail('index_fallback', code);
			else if (code === 'corrupt' || code === 'foreign' || code === 'wrongRole') this.#fail('lost', code);
			else if (code === 'tooNew' || code === 'readOnlyTooNew') this.#fail('too_new', code);
			else this.#fail('unavailable', code);
		}
	}

	/** Stage 1: the index's upkeep over `db` (W2). */
	#startSync(db: EngineDb): void {
		const make = this.#d.indexSync;
		if (!make) return;
		this.#indexReady = false;
		this.#indexReason = 'checking';
		const sync = make({
			db,
			queue: (f) => this.#enqueue(f),
			notify: (c) => this.#notify(c),
			state: (ready, reason) => {
				if (this.#sync !== sync) return;
				this.#indexReady = ready;
				this.#indexReason = reason;
				if (!this.#releasing && this.#phase === 'owner') this.#tell();
			},
			broken: (code) => {
				if (this.#sync !== sync) return;
				this.#broken(code);
			}
		});
		this.#sync = sync;
		sync.start();
	}

	/**
	 * An index call failed for good: close it, say so, and open it again after
	 * a wait (`create`), so an index lost under the owner is rebuilt (W2). The
	 * P3 store is the truth, so nothing is lost meanwhile.
	 */
	#broken(code: string): void {
		void this.#enqueue(async () => {
			if (this.#releasing || this.#phase !== 'owner') return;
			this.#close();
			this.#fail('index_fallback', code);
			this.#tell();
			if (this.#reopen) return;
			this.#reopen = setTimeout(() => {
				this.#reopen = null;
				if (this.#releasing || this.#phase !== 'owner') return;
				void this.#enqueue(async () => {
					await this.#open();
					if (!this.#releasing) this.#tell();
				});
			}, REOPEN_WAIT_MS);
		});
	}

	/** Tells every tab, and the page, who answers and how (no drain, no prune). */
	#tell(): void {
		const info = this.info();
		this.#rpc?.postMessage({ t: 'ready', owner: info } satisfies RpcMessage);
		this.#post({ t: 'state', owner: true, info });
	}

	#close(): void {
		this.#sync?.stop();
		this.#sync = null;
		this.#indexReady = true;
		this.#indexReason = 'ok';
		const db = this.#db;
		this.#db = null;
		if (db) {
			try {
				db.close();
			} catch {
				// Closing is best-effort; the handles go with the pause or the Worker.
			}
		}
		if (this.#target) {
			try {
				this.#d.engine.pausePool(this.#target.pool);
			} catch {
				// Not installed, or already paused.
			}
		}
	}

	/** Forget tabs whose lock went more than a day ago (M11). */
	async #pruneTabs(): Promise<void> {
		const db = this.#db;
		if (!db || !this.#d.locks.query || this.#seen.size === 0) return;
		try {
			const q = await this.#d.locks.query();
			const held = new Set((q.held ?? []).map((l) => l.name ?? ''));
			const gone = [...this.#seen].filter((tab) => !held.has(TAB_LOCK_PREFIX + tab));
			if (gone.length === 0) return;
			await db.call('pruneTabs', { gone, beforeMs: this.#now() - PRUNE_AFTER_MS });
		} catch {
			// Pruning is housekeeping: the next round tries again.
		}
	}

	#onRpc(m: RpcMessage): void {
		if (this.#phase !== 'owner') return;
		if (m.t === 'hello') {
			if (m.v === PROTOCOL_VERSION) this.#seen.add(m.tab);
			this.#rpc?.postMessage({ t: 'ready', owner: this.info() } satisfies RpcMessage);
			// A client that says hello may have journaled shots while it couldn't reach us.
			if (this.#stage === 2 && this.#health === 'ok') this.#post({ t: 'drainNow' });
			return;
		}
		// Its own page's requests come by postMessage, but one sent here while
		// the page didn't know yet is answered here too.
		if (m.t === 'req') this.#request(m, (r) => this.#rpc?.postMessage(r));
	}

	#request(req: Request, reply: (r: Reply) => void): void {
		const answer = (ok: boolean, valueOrError: unknown) =>
			reply(
				ok
					? { t: 'reply', to: req.tab, id: req.id, owner: this.info(), ok: true, value: valueOrError }
					: {
							t: 'reply',
							to: req.tab,
							id: req.id,
							owner: this.info(),
							ok: false,
							error: valueOrError as EngineError
						}
			);
		if (this.#phase !== 'owner' || this.#releasing || !this.#target) {
			// The page routes it to the owner over the channel instead.
			if (this.#target) answer(false, { code: 'notOwner', message: 'this Worker does not own the database' });
			return;
		}
		void this.#enqueue(async () => {
			if (this.#releasing) {
				answer(false, { code: 'notOwner', message: 'the owner is letting go' });
				return;
			}
			try {
				answer(true, await this.#serve(req));
			} catch (e) {
				const err = engineError(e);
				answer(false, err);
				// Stage 1: the index file failed under its connection (gone,
				// corrupt): reopen it, so it's rebuilt (W2).
				if (this.#sync && BROKEN.has(err.code)) this.#broken(err.code);
			}
		});
	}

	async #serve(req: Request): Promise<unknown> {
		if (req.v !== PROTOCOL_VERSION) {
			throw { code: 'protocol', message: `protocol ${req.v}, owner speaks ${PROTOCOL_VERSION}` };
		}
		this.#seen.add(req.tab);
		const db = this.#db;
		if (!db || this.#health !== 'ok') throw { code: this.#health, message: this.#reason };
		switch (req.op) {
			case 'read':
				if (!(READS as readonly string[]).includes(req.name)) {
					throw { code: 'refused', message: `${req.name} isn't a read` };
				}
				// The upkeep's own state, not the file's.
				if (req.name === 'indexSync') return this.#sync?.stats ?? null;
				// Behind the P3 store: its rows would be stale, so the tab lists from memory (W2).
				if (!this.#indexReady && !WHILE_BEHIND.has(req.name)) {
					throw { code: 'index_fallback', message: this.#indexReason };
				}
				return db.call(req.name, req.arg);
			case 'live': {
				if (req.stage !== 1 || this.#stage !== 1) {
					throw { code: 'otherStage', message: 'the live libraries go through `apply` on stage 2' };
				}
				this.#notify((await db.call('setLive', req.live)) as ChangeSet);
				return null;
			}
			case 'write': {
				// M1: a client of another stage never writes through this owner.
				if (req.stage !== this.#stage) {
					throw { code: 'otherStage', message: `a stage-${req.stage} client, a stage-${this.#stage} owner` };
				}
				// Nor one that means another file (another generation, after a recovery).
				this.#sameFile(req);
				const applied = (await db.call('apply', {
					tab: req.tab,
					seq: req.wseq,
					atMs: req.atMs,
					write: req.write
				})) as Applied;
				if (applied.outcome === 'done') this.#notify(applied.written.change);
				return applied;
			}
			case 'drain':
				if (req.stage !== 2 || this.#stage !== 2) {
					throw { code: 'otherStage', message: 'the pending journal is stage 2’s' };
				}
				this.#sameFile(req);
				return this.#drain(db, req.entries);
		}
	}

	#sameFile(req: Request): void {
		if (!req.expect || !acceptsOwner(req.stage, req.expect, this.info())) {
			throw { code: 'otherFile', message: `this owner has ${this.#target!.file} generation ${this.#target!.generation} open` };
		}
	}

	/**
	 * The pending journal's drain (M5, REVIEW-456 F2), each entry in one queued
	 * call, so no other write runs between its checks and its insert:
	 * - **An id already there** is left as it is (it may have been edited since).
	 * - **An entry with its write's `{tab, wseq}`** is applied *as that write*,
	 *   through `apply`. If the write already landed, that's `alreadyApplied`
	 *   and nothing is written, even when the shot has since been deleted, or
	 *   is held by an export (which `get` hides): the user's delete stands. If it
	 *   hadn't, the shot goes in now, under its seq, so the tab's own resend
	 *   lands as `alreadyApplied` and can't undo a later edit.
	 * - **A gap** (an earlier write of that tab hasn't landed) waits while the
	 *   tab lives, since its outbox will close it. A tab that's gone never will,
	 *   so its entry falls back to the next rule.
	 * - **An entry with no write** is inserted only if its id is absent, under
	 *   a seq of its own (`journal:<id>`, 1), so a second drain of the same
	 *   entry, after a crash between the insert and the key's removal, is
	 *   `alreadyApplied` and can't bring a deleted shot back.
	 */
	async #drain(db: EngineDb, entries: JournalEntry[]): Promise<Drained> {
		const out: Drained = { inserted: [], present: [], applied: [], waiting: [], refused: [] };
		let alive: Set<string> | null = null;
		const tabAlive = async (tab: string): Promise<boolean> => {
			if (!alive) {
				try {
					const q = await this.#d.locks.query?.();
					alive = new Set((q?.held ?? []).map((l) => l.name ?? ''));
				} catch {
					alive = null;
					return true;
				}
			}
			return alive.has(TAB_LOCK_PREFIX + tab);
		};
		const put = async (id: string, tab: string, seq: number, rowJson: string): Promise<Applied['outcome']> => {
			const a = (await db.call('apply', {
				tab,
				seq,
				atMs: this.#now(),
				write: { kind: 'upsert', rows: [{ rowJson }] }
			})) as Applied;
			if (a.outcome === 'done') {
				this.#notify(a.written.change);
				out.inserted.push(id);
			} else if (a.outcome === 'alreadyApplied') out.applied.push(id);
			else if (a.outcome === 'refused') out.refused.push(id);
			return a.outcome;
		};
		for (const e of entries) {
			let id: string;
			try {
				id = String(JSON.parse(e.row).id);
			} catch {
				continue;
			}
			const have = (await db.call('get', [id])) as unknown[];
			if (have.length > 0) {
				out.present.push(id);
				continue;
			}
			if (e.tab && e.wseq) {
				this.#seen.add(e.tab);
				const outcome = await put(id, e.tab, e.wseq, e.row);
				if (outcome !== 'gap') continue;
				if (await tabAlive(e.tab)) {
					out.waiting.push(id);
					continue;
				}
			}
			const own = `journal:${id}`;
			this.#seen.add(own);
			if ((await put(id, own, 1, e.row)) === 'gap') out.waiting.push(id);
		}
		return out;
	}

	/** Posts `change` once: a write that changed nothing carries a seq already posted (S14). */
	#notify(change: ChangeSet): void {
		if (!change || typeof change.seq !== 'number' || change.seq <= this.#posted) return;
		this.#posted = change.seq;
		this.#changes?.postMessage({ t: 'change', owner: this.info(), change });
	}
}

/** Runs the Worker's side: wire it with `serveWorker({ scope: self, engine: wasmEngine(), … })`. */
export function serveWorker(deps: OwnerDeps): void {
	const owner = new Owner(deps);
	deps.scope.onmessage = (e) => owner.onPage(e.data as PageToWorker);
}
