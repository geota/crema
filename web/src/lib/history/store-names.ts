/**
 * Names of the history's storage (#310, SPEC §2 and §6). Named apart from the
 * store, so a factory reset removes them without loading it (P0).
 */

/** The IndexedDB database the rows and details live in (P3w). */
export const HISTORY_DB_NAME = 'crema-history';

/** The daily rows snapshot, in the origin private file system (§6). */
export const HISTORY_SNAPSHOT_NAME = 'crema-history-snapshot';

/**
 * The snapshot kept aside when a smaller store's was written (after Start
 * empty, a recovery or a restore to fewer rows), so the copy from before stays.
 */
export const HISTORY_SNAPSHOT_PREV_NAME = 'crema-history-snapshot-prev';
