/**
 * `#lib/drive/backup-list` — how a Drive backup reads in the picker: when it was
 * saved, and what its name says about it. Where a name holds no counts, or
 * doesn't name a device, the line just says less.
 */
import type { BackupFileInfo } from '#lib/core/crema-core.js';
import { describeBackupFile } from '#lib/wasm/de1_wasm.js';
import type { DriveFile } from './rest';

/** When `file` was saved; "Unknown date" if Drive's time can't be read. */
export function backupDate(file: DriveFile): string {
	const at = new Date(file.modifiedTime);
	if (Number.isNaN(at.getTime())) return 'Unknown date';
	return at.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** The second line: what's known of the device, the counts and the kind. */
export function backupSubtitle(file: DriveFile): string {
	const info = JSON.parse(describeBackupFile(file.name)) as BackupFileInfo;
	return [
		info.device,
		info.counts ? `${info.counts.shots} shots, ${info.counts.curves} with curves` : null,
		info.automatic ? 'Daily' : 'Manual'
	]
		.filter((part) => part != null)
		.join(' · ');
}
