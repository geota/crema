/**
 * `#lib/history-db/journal.js` — the journal mode every SQLite history file
 * opens with on web (#310 SPEC §8 "Journal mode", PLAN W0).
 *
 * W0 killed tabs inside write transactions 200 times per mode in Chromium and
 * WebKit (`tests/browser/durability.spec.ts`). Both `truncate` and
 * `exclusiveWal` (`locking_mode=EXCLUSIVE`, then WAL, `synchronous=FULL`) kept
 * every transaction whole or absent; `exclusiveWal` committed faster in every
 * browser, so it is the one kept.
 *
 * Fixed for good once a file exists: `sahpool` has no shared memory, so a file
 * in WAL format opens only in `exclusiveWal` (REVIEW-444 note 8). W1 opens
 * every file, and the flip and snapshot copy into every file, with this mode.
 */
export const HISTORY_JOURNAL = 'exclusiveWal' as const;
