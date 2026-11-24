/**
 * `#lib/backup/last-backup` — when this device last made a backup, a file
 * download or a Drive copy. Settings shows it as "Last backup", and the
 * backup reminder (`reminder.svelte.ts`) reads it. Kept apart from
 * `#lib/backup/index` so the reminder doesn't load the backup builder.
 */
import { readJson, writeJson } from '#lib/utils/storage.js';

/** localStorage key: when this device last made a backup, file or Drive (unix ms). */
export const LAST_BACKUP_KEY = 'crema.backup.last.v1';

/** A stored last-backup time: a finite unix ms, or null for never. */
export const validBackupTime = (v: unknown): v is number | null => v === null || (typeof v === 'number' && Number.isFinite(v));

const listeners = new Set<(at: number) => void>();

/** Note a backup that landed, for Settings' "Last backup" and the reminder. */
export function noteBackupMade(at: number = Date.now()): void {
	writeJson(LAST_BACKUP_KEY, at);
	for (const fn of listeners) fn(at);
}

/** When this device last made a backup, or `null`. */
export function lastBackupAt(): number | null {
	return readJson<number | null>(LAST_BACKUP_KEY, null, { valid: validBackupTime });
}

/** Hear of each backup this tab makes. Returns the unsubscribe. */
export function onBackupMade(fn: (at: number) => void): () => void {
	listeners.add(fn);
	return () => listeners.delete(fn);
}
