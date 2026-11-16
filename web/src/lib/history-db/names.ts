/**
 * `#lib/history-db/names.js` — the names the SQLite history uses on web (its
 * SPEC §8 and §11, N2). Constants only. From W1 the app's Worker keeps
 * `crema-index` (stage 1); a factory reset removes every name here, so a reset
 * after a rollback still takes everything a newer build kept. The Worker (C4,
 * W1) imports them from here, and the default `.opfs-sahpool` directory is
 * never used.
 */

/** Every OPFS entry the SQLite history keeps, at the root, starts with this. */
export const HISTORY_SQLITE_OPFS_PREFIX = '.crema-history-';

/** The `crema-index` pool's VFS name. */
export const INDEX_POOL_VFS = 'crema-index';
/** The `crema-index` pool's directory: `history-index.db` (stage 1). Repair generations add `-<n>`. */
export const INDEX_POOL_DIR = '.crema-history-index';

/** The `crema-db` pool's VFS name. */
export const DB_POOL_VFS = 'crema-db';
/** The `crema-db` pool's directory: `history.db`, any `history-<n>.db`, the snapshots. Repair generations add `-<n>`. */
export const DB_POOL_DIR = '.crema-history-db';

/** The done-record (M8), at the OPFS root, outside both pools. */
export const DONE_RECORD_NAME = '.crema-history-done.json';

/** The marker, in `localStorage`. */
export const MARKER_KEY = 'crema.history.sqlite';
/** Each pending-journal entry's `localStorage` key: this plus the shot id. */
export const PENDING_KEY_PREFIX = 'crema.history.sqlite.pending.';

/** Stage 1's index file, in `crema-index`. */
export const INDEX_FILE = 'history-index.db';
/** Stage 2's truth, in `crema-db` (a recovery generation is `history-<n>.db`). */
export const DB_FILE = 'history.db';

/** The Web Lock the owner's Worker holds: whoever holds it owns the database (SPEC §8). */
export const OWNER_LOCK = 'crema-history-db';
/** Every tab holds this, shared, for its life; the flip takes it exclusive (M2). */
export const STAGE_LOCK = 'crema-history-stage';
/** Every tab holds this plus its id for its life (M11): an owner prunes tabs whose lock is gone. */
export const TAB_LOCK_PREFIX = 'crema-history-tab:';

/** Requests, replies, "owner ready" and hellos (SPEC §8). */
export const RPC_CHANNEL = 'crema-history-rpc';
/** Every write's `ChangeSet`, to every tab (SPEC §10). */
export const CHANGES_CHANNEL = 'crema-history-changes';

/**
 * The P3 store's own channel, where it posts the ids and `writeSeq` of each
 * write (`HISTORY_CHANNEL` in `store.svelte.ts`): the stage-1 index's upkeep
 * listens on it (W2, M4). Read here so the Worker imports nothing of the store.
 */
export const P3_CHANNEL = 'crema-history';
