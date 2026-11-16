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

/**
 * Bumped when a message's shape changes. A client and an owner of different
 * versions refuse each other. 2 (W2): the owner keeps the stage-1 index in
 * step with the P3 store, which a W1 owner doesn't, so a W2 client must not
 * read the index through one; and the `live` request.
 */
export const PROTOCOL_VERSION = 2;

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
	'indexState',
	// The restore's (#310 R1w, M3): reads, safe to resend.
	'restoreBegin',
	'idsCreatedBefore',
	/** Stage 1 (W2): how the owner's index upkeep stands (`SyncStats`), for diagnostics. */
	'indexSync',
	// Stage 2 (W5): a `meta` value (the P3 store's own late-row record), the
	// flip's source ids, the late rows still waiting for samples, and which
	// ids have a row (held ones too), for a delete's details.
	'metaValue',
	'migrationSourceIds',
	'lateIds',
	'existing'
] as const;
export type ReadName = (typeof READS)[number];

/**
 * The calls sent once and never resent (#310 R1w, SPEC §9, S6). The export
 * walk's token and cursor live on the owner's connection: a resend after a
 * late answer would run `exportNext` twice and skip a step, or leak a walk,
 * and an owner change ends the walk, so the call is refused, not sent on. A
 * hold's release (`releaseHolds`, `deleteHeld`) goes with them.
 */
export const ONCE = ['exportBegin', 'exportNext', 'exportRewind', 'exportEnd', 'releaseHolds', 'deleteHeld'] as const;
export type OnceName = (typeof ONCE)[number];

/** A client's write (C4's `WriteJson`), applied through `apply` in its tab's seq order. */
export type Write =
	| { kind: 'upsert'; rows: { rowJson: string; pulled?: boolean }[] }
	| { kind: 'delete'; ids: string[] }
	| { kind: 'setLive'; live: unknown }
	/** Stage 2: the P3 store's own late-row record, kept in `history.db` (S13). */
	| { kind: 'p3Migration'; text: string };

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

/**
 * The live libraries a query reads besides the rows (the core's `Live`): the
 * bean library, resolved, and the custom methods. They live in this tab's
 * `localStorage`, which the Worker can't read, so each tab sends them.
 */
export interface Live {
	beans: { beanId: string; name: string; roaster: string | null; tags: string[] }[];
	methods: { method: string; label: string }[];
}

export type Request =
	| (Header & { op: 'read'; name: ReadName; arg?: unknown })
	/**
	 * Stage 1 (W2): the live libraries for the index's queries. Not rows: the
	 * index reads those from the P3 store itself (M4). Idempotent, so resent
	 * like a read.
	 */
	| (Header & { op: 'live'; live: Live })
	| (Header & { op: 'write'; wseq: number; atMs: number; write: Write })
	/** A walk call (stage 2): sent once, to the owner that has the walk. */
	| (Header & { op: 'once'; name: OnceName; arg?: unknown })
	/** The owner tab's drain of the pending journal (M5): insert each row whose id is absent. */
	| (Header & { op: 'drain'; entries: JournalEntry[] })
	/**
	 * Stage 2 (W5, SPEC §11 step 8): late rows the page read from P3's own
	 * journal or the pre-P3 store, inserted by the id rule (never an id
	 * `migration_source` or `history.db` has). Idempotent, so resent like a read.
	 */
	| (Header & { op: 'importLate'; rows: { rowJson: string }[] })
	/**
	 * Stage 2 (W5c, SPEC §11, S9): the `lost` banner's choice, into a new
	 * `history-<n>.db`, whatever the open file's health. Sent once, never resent.
	 */
	| (Header & { op: 'recover'; kind: RecoverKind; from?: string; known?: string[] })
	/** Stage 2 (W5c): the daily rows snapshot, `history-snapshot.db`, keeping one previous copy. */
	| (Header & { op: 'snapshot' })
	/** Stage 2 (W5c): the rows snapshots there are, for the `lost` banner. */
	| (Header & { op: 'snapshotInfo' });

/** What a recovery file starts from (S9). */
export type RecoverKind = 'empty' | 'oldCopy' | 'snapshot';

/** A rows snapshot, as the `lost` banner lists it. */
export interface SnapshotFile {
	name: string;
	rows: number;
	at: number;
}

/**
 * One pending-journal entry, as the drain sends it (M5, REVIEW-456 F2). A
 * capture journals its row with the tab and write seq its write was given, so
 * the owner can tell an entry whose write already landed (and may have been
 * deleted since) from one that never did. An entry journaled without a write
 * (while read-only) has neither.
 */
export interface JournalEntry {
	row: string;
	tab?: string;
	wseq?: number;
}

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
	| { outcome: 'done'; written: { change: ChangeSet; duplicates?: { id: string; existingId: string }[] } }
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

/**
 * A drain's outcome, by id (F2). The page removes the entries of `inserted`,
 * `present` and `applied`; it keeps `waiting` (the capturing tab is alive and
 * its write hasn't landed yet) and `refused` for a later drain.
 */
export interface Drained {
	/** Inserted now. */
	inserted: string[];
	/** Already there: left as it is (it may have been edited since). */
	present: string[];
	/** Its write already landed: not inserted again, even if the row is gone or held now. */
	applied: string[];
	waiting: string[];
	refused: string[];
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

/**
 * On `crema-history-rpc`, from the tab that flipped (SPEC §11 step 7, W5):
 * `history.db` is the truth now. A stage-1 tab that hears it stops writing the
 * P3 store and reloads. W1 is the first build that listens, so every later
 * build's flip reaches it (REVIEW-456 N4).
 */
export interface Flipped {
	t: 'flipped';
	v: number;
	build: string;
	generation: number;
}

export type RpcMessage = Request | Reply | Ready | Gone | Hello | Flipped;

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
