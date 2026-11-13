/**
 * Names the history's next storage will use (#310, SPEC §2 and §6). Nothing
 * on this build creates them. They're named now so that a factory reset here
 * removes what a newer build left behind, if the user rolls back (P0).
 */

/** The IndexedDB database the rows and details move to (P3w). */
export const HISTORY_DB_NAME = 'crema-history';

/** The daily rows snapshot, in the origin private file system (§6). */
export const HISTORY_SNAPSHOT_NAME = 'crema-history-snapshot';
