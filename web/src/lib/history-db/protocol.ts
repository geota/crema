/**
 * `#lib/history-db/protocol.js` — what tabs and the owner's Worker say to each
 * other (#310 SPEC §8, PLAN W1, M1, M11). The first build with the protocol
 * carries everything later builds rely on:
 *
 * - **Every request** carries the protocol version, the sender's build and
 *   stage, its tab id and a request seq; a write also carries its tab's write
 *   seq (M11), which the owner applies through the store's `apply`.
 * - **Every reply** carries the owner: its tab, build, stage, role and the file
 *   it has open. A client refuses an owner whose stage or file isn't the one it
 *   expects ({@link acceptsOwner}); an owner refuses writes from a client of
 *   another stage.
 * - Replies, "owner ready" and "owner gone" come from the owner's **Worker**,
 *   never its page: a frozen page's main thread still gets `BroadcastChannel`
 *   messages while its Worker, which holds the database, is frozen (W0), so
 *   only the Worker's answer says the owner can answer.
 *
 * Plain data only: every message crosses `postMessage` or a `BroadcastChannel`
 * as a structured clone.
 */

/** Bumped when a message's shape changes. A client and an owner of different versions refuse each other. */
export const PROTOCOL_VERSION = 1;

/** 1: the index beside the P3 store. 2: `history.db` is the truth (SPEC §3). */
export type Stage = 1 | 2;
/** A file's `meta.role`. */
export type Role = 'index' | 'authority';
/** The pool a file lives in. */
export type Pool = 'index' | 'db';
/** The journal mode a file opens with (`journal.ts`). */
export type Journal = 'truncate' | 'exclusiveWal';

/** The file a Worker opens when it owns the database. */
export interface Target {
	pool: Pool;
	/** The pool's repair generation: 0 is the base directory. */
	generation: number;
	file: string;
	role: Role;
	/** Create it when missing: `index` files only (an `authority` file is never recreated). */
	create: boolean;
	journal: Journal;
}

/** What a client needs of an owner (M1): stage 1 the index, stage 2 the marker's file. */
export interface Expect {
	role: Role;
	file: string;
	/** Stage 2: the pool generation the marker names. */
	generation?: number;
}

/** Who answers, on every reply and every "ready". */
export interface OwnerInfo {
	v: number;
	tab: string;
	build: string;
	stage: Stage;
	role: Role;
	file: string;
	generation: number;
	/** The owner's health: `ok`, or why it can't serve its file (SPEC §12). */
	health: OwnerHealth;
	/** The error code behind it (`absent`, `corrupt`, `pool.opfs`, …), for diagnostics. */
	reason: string;
}

/** An owner's view of its file (SPEC §8's table, §12). */
export type OwnerHealth = 'ok' | 'index_fallback' | 'unavailable' | 'lost' | 'too_new';

/** The calls that only read. */
export const READS = [
	'count',
	'page',
	'indexOf',
	'groupCounts',
	'stats',
	'facets',
	'get',
	'byRemoteId',
	'byDecentId',
	'dosesForBag',
	'unsent',
	'syncRows',
	'syncProjection',
	'writeSeq',
	'info',
	'health',
	'ftsStatus',
	'indexState'
] as const;
export type ReadName = (typeof READS)[number];

/** A client's write (C4's `WriteJson`), applied through `apply` in its tab's seq order. */
export type Write =
	| { kind: 'upsert'; rows: { rowJson: string; pulled?: boolean }[] }
	| { kind: 'delete'; ids: string[] }
	| { kind: 'setLive'; live: unknown };

interface Header {
	t: 'req';
	v: number;
	build: string;
	stage: Stage;
	tab: string;
	/** This tab's request number. */
	seq: number;
	/** `${tab}:${seq}`: the reply names it. */
	id: string;
	/** The file the sender means (M1): an owner with another file open refuses its writes. */
	expect: Expect;
}

export type Request =
	| (Header & { op: 'read'; name: ReadName; arg?: unknown })
	| (Header & { op: 'write'; wseq: number; atMs: number; write: Write })
	/** The owner tab's drain of the pending journal (M5): insert each row whose id is absent. */
	| (Header & { op: 'drain'; rows: string[] });

export interface ReplyOk {
	t: 'reply';
	to: string;
	id: string;
	owner: OwnerInfo;
	ok: true;
	value: unknown;
}
export interface ReplyErr {
	t: 'reply';
	to: string;
	id: string;
	owner: OwnerInfo;
	ok: false;
	error: { code: string; message: string };
}
export type Reply = ReplyOk | ReplyErr;

/** A write's outcome (`AppliedJson`). */
export type Applied =
	| { outcome: 'done'; written: { change: ChangeSet } }
	| { outcome: 'alreadyApplied'; last: number }
	| { outcome: 'gap'; last: number }
	| { outcome: 'refused'; reason: string };

/** The core's change notice (SPEC §10). */
export interface ChangeSet {
	seq: number;
	upserted: string[];
	deleted: string[];
	kinds: Record<string, unknown>;
}

/** A drain's outcome: the ids it inserted, and those already there (left as they are). */
export interface Drained {
	inserted: string[];
	present: string[];
}

/** On `crema-history-rpc`, from the owner's Worker: it holds the lock and answers. */
export interface Ready {
	t: 'ready';
	owner: OwnerInfo;
}
/** On `crema-history-rpc`, from the owner's Worker: it let the lock go. */
export interface Gone {
	t: 'gone';
	owner: OwnerInfo;
}
/** On `crema-history-rpc`, from a client: who owns? The owner answers with `ready`. */
export interface Hello {
	t: 'hello';
	v: number;
	tab: string;
	build: string;
	stage: Stage;
}
/** On `crema-history-changes`, from the owner's Worker: one write's `ChangeSet`. */
export interface Change {
	t: 'change';
	owner: OwnerInfo;
	change: ChangeSet;
}

export type RpcMessage = Request | Reply | Ready | Gone | Hello;

/**
 * Whether a client of `stage` that expects `expect` may use `owner` (M1). A
 * client that refuses its owner behaves as if the owner can't answer, with
 * "Another version of Crema is open in another window".
 */
export function acceptsOwner(stage: Stage, expect: Expect, owner: Omit<OwnerInfo, 'tab' | 'build' | 'health' | 'reason'>): boolean {
	if (owner.v !== PROTOCOL_VERSION) return false;
	if (owner.stage !== stage) return false;
	if (owner.role !== expect.role || owner.file !== expect.file) return false;
	if (expect.generation !== undefined && owner.generation !== expect.generation) return false;
	return true;
}

/** Shown when the owner is another build, or another stage (M1). */
export const OTHER_BUILD_MESSAGE = 'Another version of Crema is open in another window';

/** Shown on stage 2 when the owner doesn't answer (SPEC §8). */
export const OTHER_WINDOW_MESSAGE = 'History is open in another window';
