/**
 * #310 P3w, #409: the lost banner over a real store. "Restore a backup" doesn't
 * open Settings when another tab had already recovered the history (N3), and
 * Download works while the store is lost, in a dev build too.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { goto } from '$app/navigation';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { downloadBlob } from '#lib/utils/download.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import { boot, freshStorage, writeOldCopy } from '#lib/history/idb-test-kit.js';
import { p3RowsOf } from '#lib/history/p3-rows.js';

vi.mock('$app/navigation', () => ({ goto: vi.fn(() => Promise.resolve()) }));
vi.mock('#lib/utils/download.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/utils/download.js')>()),
	downloadBlob: vi.fn()
}));
vi.mock('#lib/components/shared/confirm-dialog.svelte.js', () => ({ confirmDialog: vi.fn(async () => true) }));
let current: HistoryStore | null = null;
vi.mock('#lib/history/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/history/index.js')>()),
	getHistoryStore: () => current
}));

const { default: HistoryStorageStrip } = await import('./HistoryStorageStrip.svelte');

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
let idb: IDBFactory;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	idb = freshStorage();
	vi.mocked(goto).mockClear();
	vi.mocked(downloadBlob).mockClear();
});

let component: ReturnType<typeof mount> | null = null;
afterEach(() => {
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
	current = null;
});

function deleteHistoryDb(): Promise<void> {
	return new Promise<void>((resolve) => {
		const req = idb.deleteDatabase('crema-history');
		req.onsuccess = () => resolve();
		req.onblocked = () => resolve();
	});
}

const until = async (ok: () => boolean, ms = 2_000) => {
	const end = Date.now() + ms;
	while (!ok()) {
		if (Date.now() > end) throw new Error('timed out');
		await new Promise((r) => setTimeout(r, 2));
	}
};

function show(store: HistoryStore): HTMLElement {
	current = store;
	const target = document.body.appendChild(document.createElement('div'));
	component = mount(HistoryStorageStrip, { target });
	flushSync();
	return target;
}

const button = (target: HTMLElement, text: string) =>
	[...target.querySelectorAll('button')].find((b) => b.textContent?.includes(text));

describe('the lost banner', () => {
	it('Restore a backup doesn’t open Settings when another tab had already recovered (N3)', async () => {
		await writeOldCopy([realShot(0, realSamples(4)), realShot(1, realSamples(4))]);
		await boot();
		await deleteHistoryDb();
		const a = await boot();
		const b = await boot();
		const target = show(b);
		expect(button(target, 'Restore a backup')).toBeDefined();
		// A recovers; B never hears of it.
		expect(await a.recoverOldCopy()).toBe(true);
		button(target, 'Restore a backup')!.click();
		await until(() => b.health === 'ok');
		await tick();
		await new Promise((r) => setTimeout(r, 10));
		expect(goto).not.toHaveBeenCalled();
		expect(p3RowsOf(b).raw).toHaveLength(2);
	});

	it('Restore a backup opens Settings once the history starts empty', async () => {
		await writeOldCopy([realShot(0, realSamples(4))]);
		await boot();
		await deleteHistoryDb();
		const b = await boot();
		const target = show(b);
		button(target, 'Restore a backup')!.click();
		await until(() => vi.mocked(goto).mock.calls.length > 0);
		expect(goto).toHaveBeenCalledWith('/settings#sharing');
	});

	it('Download works while the store is lost, with the dev load check on', async () => {
		await writeOldCopy([realShot(0, realSamples(4))]);
		const store = await boot();
		const made = store.recordCaptured(realShot(9, realSamples(4)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		await deleteHistoryDb();
		store.setRating(made.id, 2);
		await store.flushed();
		expect(store.health).toBe('lost');
		expect(store.loaded).toBe(false);
		const target = show(store);
		const download = button(target, 'Download these shots') ?? button(target, 'Download this shot');
		expect(download).toBeDefined();
		const errors: unknown[] = [];
		const onError = (e: ErrorEvent) => errors.push(e.error);
		window.addEventListener('error', onError);
		try {
			download!.click();
		} finally {
			window.removeEventListener('error', onError);
		}
		expect(errors).toEqual([]);
		expect(vi.mocked(downloadBlob).mock.calls.length).toBe(store.unsavedShots.length);
		expect(vi.mocked(downloadBlob).mock.calls.length).toBeGreaterThan(0);
	});
});
