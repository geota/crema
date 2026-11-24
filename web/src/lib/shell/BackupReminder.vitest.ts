/**
 * #487 part 2: the backup reminder in the storage strip, over a real store.
 * Shots older than two weeks and no backup: the reminder, with Back up and
 * Dismiss; a backup or a dismissal ends it; a storage notice comes first.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { goto } from '$app/navigation';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import { boot, freshStorage, writeOldCopy } from '#lib/history/idb-test-kit.js';
import { StorageWatch } from '#lib/history/storage-watch.svelte.js';
import { BackupReminder } from '#lib/backup/reminder.svelte.js';
import { noteBackupMade } from '#lib/backup/last-backup.js';

vi.mock('$app/navigation', () => ({ goto: vi.fn(() => Promise.resolve()) }));
let current: HistoryStore | null = null;
vi.mock('#lib/history/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/history/index.js')>()),
	getHistoryStore: () => current
}));
let used = 0;
let watch: StorageWatch;
vi.mock('#lib/history/storage-watch.svelte.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/history/storage-watch.svelte.js')>()),
	getStorageWatch: () => watch
}));
let reminder: BackupReminder;
vi.mock('#lib/backup/reminder.svelte.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/backup/reminder.svelte.js')>()),
	getBackupReminder: () => reminder
}));

const { default: HistoryStorageStrip } = await import('./HistoryStorageStrip.svelte');

const DAY = 24 * 60 * 60_000;
/** The real shots' newest time, from `realShot(0)`. */
const NEWEST = realShot(0, []).completedAt;
let now = NEWEST + 20 * DAY;

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
	used = 0;
	now = NEWEST + 20 * DAY;
	watch = new StorageWatch(async () => ({ usage: used, quota: 1_000_000 }));
	reminder = new BackupReminder(() => now);
	vi.mocked(goto).mockClear();
});
let component: ReturnType<typeof mount> | null = null;
afterEach(() => {
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
	current = null;
});

function show(store: HistoryStore): HTMLElement {
	current = store;
	const target = document.body.appendChild(document.createElement('div'));
	component = mount(HistoryStorageStrip, { target });
	flushSync();
	return target;
}
const button = (target: HTMLElement, text: string) =>
	[...target.querySelectorAll('button')].find((b) => b.textContent?.includes(text));
const settle = async () => {
	await new Promise((r) => setTimeout(r, 5));
	await tick();
	flushSync();
};
const strip = (target: HTMLElement) => target.querySelector('[data-testid="backup-reminder"]');

describe('the backup reminder', () => {
	it('never backed up, shots two weeks old: Back up opens Settings, Dismiss puts it off', async () => {
		await writeOldCopy([realShot(0, realSamples(4)), realShot(1, realSamples(4))]);
		const store = await boot();
		const target = show(store);
		await settle();
		expect(strip(target)?.textContent).toContain('Your shots aren’t backed up yet.');
		button(target, 'Back up')!.click();
		expect(goto).toHaveBeenCalledWith('/settings#sharing');
		button(target, 'Dismiss')!.click();
		flushSync();
		expect(strip(target)).toBeNull();
		// Two weeks on, with no new shot: still away.
		now += 15 * DAY;
		reminder.now = now;
		flushSync();
		expect(strip(target)).toBeNull();
	});

	it('an old backup and newer shots: says how old; a backup ends it', async () => {
		noteBackupMade(NEWEST - DAY);
		reminder = new BackupReminder(() => now);
		await writeOldCopy([realShot(0, realSamples(4))]);
		const store = await boot();
		const target = show(store);
		await settle();
		expect(strip(target)?.textContent).toContain('Your last backup was 21 days ago, and there are new shots since.');
		noteBackupMade(now);
		flushSync();
		expect(strip(target)).toBeNull();
	});

	it('not yet two weeks: no reminder', async () => {
		now = NEWEST + 13 * DAY;
		reminder = new BackupReminder(() => now);
		await writeOldCopy([realShot(0, realSamples(4))]);
		const store = await boot();
		const target = show(store);
		await settle();
		expect(strip(target)).toBeNull();
	});

	it('a storage notice comes first', async () => {
		await writeOldCopy([realShot(0, realSamples(4))]);
		const store = await boot();
		used = 960_000;
		const target = show(store);
		await settle();
		expect(target.querySelector('[data-testid="storage-nearly-full"]')).not.toBeNull();
		expect(strip(target)).toBeNull();
	});
});
