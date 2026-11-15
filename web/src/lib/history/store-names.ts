/**
 * Names of the history's storage (#310, SPEC §2 and §6). Named apart from the
 * store, so a factory reset removes them without loading it (P0).
 */

/** The IndexedDB database the rows and details live in (P3w). */
export const HISTORY_DB_NAME = 'crema-history';

/**
 * The daily rows snapshots: a directory in the origin private file system
 * (§6), one dated file a day. A directory, so the P0 erase, which removes this
 * name recursively, takes every copy.
 */
export const HISTORY_SNAPSHOT_NAME = 'crema-history-snapshot';
