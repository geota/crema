/**
 * #487 part 2: when the backup reminder is due. The last backup is 14 days
 * old and there are newer shots; never backed up, the clock starts at the
 * oldest shot; a dismissal puts it off for as long again, and until there
 * are shots newer than it.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import {
	BACKUP_REMINDER_DAYS,
	BACKUP_REMINDER_DISMISSED_KEY,
	BackupReminder,
	backupReminderDue,
	backupReminderMessage
} from './reminder.svelte';
import { LAST_BACKUP_KEY, lastBackupAt, noteBackupMade } from './last-backup';

const DAY = 24 * 60 * 60_000;
const NOW = Date.UTC(2026, 10, 23, 12);
const shots = (newestDaysAgo: number, oldestDaysAgo = newestDaysAgo) => ({
	newest: NOW - newestDaysAgo * DAY,
	oldest: NOW - oldestDaysAgo * DAY
});
const due = (o: Partial<Parameters<typeof backupReminderDue>[0]>) =>
	backupReminderDue({ now: NOW, lastBackupAt: null, dismissedAt: null, shots: null, ...o });

describe('backupReminderDue', () => {
	it('is 14 days', () => {
		expect(BACKUP_REMINDER_DAYS).toBe(14);
	});

	it('no shots: never due', () => {
		expect(due({ shots: null })).toBe(false);
		expect(due({ shots: null, lastBackupAt: NOW - 100 * DAY })).toBe(false);
	});

	it('backed up: due 14 days after the backup, with shots newer than it', () => {
		expect(due({ lastBackupAt: NOW - 14 * DAY, shots: shots(1, 30) })).toBe(true);
		expect(due({ lastBackupAt: NOW - 14 * DAY + 1, shots: shots(1, 30) })).toBe(false);
		// Old backup, nothing newer than it.
		expect(due({ lastBackupAt: NOW - 20 * DAY, shots: shots(21, 30) })).toBe(false);
		expect(due({ lastBackupAt: NOW - 20 * DAY, shots: shots(20, 30) })).toBe(false);
	});

	it('never backed up: due once the oldest shot is 14 days old', () => {
		expect(due({ shots: shots(0, 13) })).toBe(false);
		expect(due({ shots: shots(0, 14) })).toBe(true);
		expect(due({ shots: shots(14) })).toBe(true);
	});

	it('dismissed: away for another 14 days, and until there are newer shots', () => {
		const lastBackupAt = NOW - 40 * DAY;
		expect(due({ lastBackupAt, dismissedAt: NOW - 13 * DAY, shots: shots(1, 60) })).toBe(false);
		expect(due({ lastBackupAt, dismissedAt: NOW - 14 * DAY, shots: shots(1, 60) })).toBe(true);
		// 14 days on, but nothing new since the dismissal.
		expect(due({ lastBackupAt, dismissedAt: NOW - 15 * DAY, shots: shots(16, 60) })).toBe(false);
		// Never backed up, dismissed.
		expect(due({ dismissedAt: NOW - 13 * DAY, shots: shots(1, 60) })).toBe(false);
		expect(due({ dismissedAt: NOW - 14 * DAY, shots: shots(1, 60) })).toBe(true);
	});

	it('a clock behind the backup is not due', () => {
		expect(due({ lastBackupAt: NOW + 5 * DAY, shots: shots(-6, 30) })).toBe(false);
	});
});

describe('the line', () => {
	it('says how long since the backup, or that there is none', () => {
		expect(backupReminderMessage(NOW - 15.5 * DAY, NOW)).toBe(
			'Your last backup was 15 days ago, and there are new shots since. They’re kept only in this browser until you back up.'
		);
		expect(backupReminderMessage(null, NOW)).toContain('Your shots aren’t backed up yet.');
	});
});

describe('BackupReminder', () => {
	beforeEach(() => localStorage.clear());

	it('a dismissal is kept, and read back by the next load', () => {
		const r = new BackupReminder(() => NOW);
		expect(r.dueFor(shots(1, 30))).toBe(true);
		r.dismiss();
		expect(r.dueFor(shots(1, 30))).toBe(false);
		expect(JSON.parse(localStorage.getItem(BACKUP_REMINDER_DISMISSED_KEY)!)).toBe(NOW);
		expect(new BackupReminder(() => NOW).dismissedAt).toBe(NOW);
	});

	it('a backup made in this tab ends it at once', () => {
		const r = new BackupReminder(() => NOW);
		const stop = r.start();
		try {
			expect(r.dueFor(shots(1, 30))).toBe(true);
			noteBackupMade(NOW);
			flushSync();
			expect(r.last).toBe(NOW);
			expect(lastBackupAt()).toBe(NOW);
			expect(r.dueFor(shots(1, 30))).toBe(false);
		} finally {
			stop();
		}
	});

	it('a backup made in another tab ends it too', () => {
		const r = new BackupReminder(() => NOW);
		const stop = r.start();
		try {
			localStorage.setItem(LAST_BACKUP_KEY, JSON.stringify(NOW));
			window.dispatchEvent(new StorageEvent('storage', { key: LAST_BACKUP_KEY, newValue: localStorage.getItem(LAST_BACKUP_KEY) }));
			expect(r.last).toBe(NOW);
			expect(r.dueFor(shots(1, 30))).toBe(false);
		} finally {
			stop();
		}
	});
});
