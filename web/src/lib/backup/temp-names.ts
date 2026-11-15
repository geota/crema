/**
 * `#lib/backup/temp-names.js` — the names of the temporary backup files
 * (`#lib/backup/stream`), apart from it so the factory reset can find them
 * without loading the zip writer or the core.
 */

/** Temporary backup files in the origin private file system start with this. */
export const BACKUP_TEMP_PREFIX = 'crema-backup-tmp-';

/** The Web Lock a temporary backup file holds until its reader is done. */
export const backupTempLock = (name: string): string => `crema-backup-tmp:${name}`;
