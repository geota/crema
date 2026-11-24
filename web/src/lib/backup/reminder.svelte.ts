/**
 * `#lib/backup/reminder.svelte.js` — a gentle nudge to back up (#487 part 2).
 * The web keeps its history only in this browser: clearing site data, or the
 * browser evicting it, loses every shot not in a backup. So once the last
 * backup (a download or a Drive copy) is {@link BACKUP_REMINDER_DAYS} days old
 * and there are shots newer than it, the storage strip asks for one.
 *
 * - **Never backed up**: the clock starts at the oldest shot, so a new user
 *   isn't asked on day one.
 * - **Dismissed**: it stays away for another {@link BACKUP_REMINDER_DAYS}
 *   days, and comes back only if there are shots newer than the dismissal.
 * - A backup made in any tab ends it at once.
 *
 * Nothing here backs anything up or deletes anything.
 */
import { followOtherTabs, readJson, writeJson } from '#lib/utils/storage.js';
import { LAST_BACKUP_KEY, lastBackupAt, onBackupMade, validBackupTime } from './last-backup';

/** How old the last backup gets, with new shots since, before the reminder shows. */
export const BACKUP_REMINDER_DAYS = 14;
const DAY_MS = 24 * 60 * 60_000;
/** localStorage key: when the reminder was last dismissed (unix ms). */
export const BACKUP_REMINDER_DISMISSED_KEY = 'crema.backup.reminderDismissed.v1';
/** How often "now" moves while the app stays open. */
const TICK_MS = 60 * 60_000;

export interface ReminderInput {
	now: number;
	/** The last backup this device made, or null for never. */
	lastBackupAt: number | null;
	/** When the reminder was last dismissed, or null. */
	dismissedAt: number | null;
	/** The newest and oldest live shots' times, or null with no shots. */
	shots: { newest: number; oldest: number } | null;
	days?: number;
}

/** Whether the backup reminder is due. Pure. */
export function backupReminderDue(i: ReminderInput): boolean {
	if (!i.shots) return false;
	const days = i.days ?? BACKUP_REMINDER_DAYS;
	const covered = Math.max(i.lastBackupAt ?? -Infinity, i.dismissedAt ?? -Infinity);
	// Nothing newer than the last backup, or than the dismissal.
	if (!(i.shots.newest > covered)) return false;
	const since = Math.max(i.lastBackupAt ?? i.shots.oldest, i.dismissedAt ?? -Infinity);
	return i.now - since >= days * DAY_MS;
}

/** Whole days since `at`, for the reminder's line. */
export function daysSince(at: number, now: number): number {
	return Math.max(0, Math.floor((now - at) / DAY_MS));
}

/** The reminder's line. */
export function backupReminderMessage(last: number | null, now: number): string {
	if (last === null) {
		return 'Your shots aren’t backed up yet. They’re kept only in this browser, so clearing its site data would lose them. Back up to keep a copy.';
	}
	const days = daysSince(last, now);
	return `Your last backup was ${days} days ago, and there are new shots since. They’re kept only in this browser until you back up.`;
}

/** The reminder's state: the last backup, the dismissal and the clock, all reactive. */
export class BackupReminder {
	last = $state<number | null>(lastBackupAt());
	dismissedAt = $state<number | null>(readJson<number | null>(BACKUP_REMINDER_DISMISSED_KEY, null, { valid: validBackupTime }));
	now = $state(Date.now());

	constructor(private readonly clock: () => number = Date.now) {
		this.now = clock();
	}

	/** Whether it shows for `shots`. */
	dueFor(shots: { newest: number; oldest: number } | null): boolean {
		return backupReminderDue({ now: this.now, lastBackupAt: this.last, dismissedAt: this.dismissedAt, shots });
	}

	/** The line it shows now. */
	get message(): string {
		return backupReminderMessage(this.last, this.now);
	}

	/** Not now: it stays away for another while, and until there are newer shots. */
	dismiss(): void {
		const at = this.clock();
		this.now = at;
		this.dismissedAt = at;
		writeJson(BACKUP_REMINDER_DISMISSED_KEY, at);
	}

	/** Follow backups (this tab's and others'), dismissals in other tabs, and the clock. Returns the stop. */
	start(): () => void {
		const stops = [
			onBackupMade((at) => (this.last = at)),
			followOtherTabs(LAST_BACKUP_KEY, validBackupTime, (v) => (this.last = v as number | null)),
			followOtherTabs(BACKUP_REMINDER_DISMISSED_KEY, validBackupTime, (v) => (this.dismissedAt = v as number | null))
		];
		const tick = () => (this.now = this.clock());
		const timer = setInterval(tick, TICK_MS);
		const onShow = () => {
			if (document.visibilityState === 'visible') tick();
		};
		document.addEventListener('visibilitychange', onShow);
		return () => {
			for (const stop of stops) stop();
			clearInterval(timer);
			document.removeEventListener('visibilitychange', onShow);
		};
	}
}

let reminder: BackupReminder | null = null;
/** The app's one backup reminder. */
export function getBackupReminder(): BackupReminder {
	reminder ??= new BackupReminder();
	return reminder;
}
