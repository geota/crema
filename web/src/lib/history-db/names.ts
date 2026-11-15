/**
 * `#lib/history-db/names.js` — the names the SQLite history uses on web (its
 * SPEC §8 and §11, N2). Constants only. This build writes none of them: a
 * factory reset removes them, so a reset after a rollback to this build still
 * takes everything a newer build kept. The Worker (C4, W1) imports them from
 * here, and the default `.opfs-sahpool` directory is never used.
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
