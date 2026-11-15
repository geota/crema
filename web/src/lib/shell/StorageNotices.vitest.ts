/**
 * #310 P6w: the storage notices (SPEC §1). A write the disk refuses shows the
 * unsaved banner and loses nothing; 95% of the quota shows a lasting banner;
 * 80% shows one notice, until dismissed, and again only after use drops below
 * 80% and climbs back. Each offers Back up and Free up space, and none of
 * them deletes anything.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { goto } from '$app/navigation';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import { boot, freshStorage, stored, writeOldCopy } from '#lib/history/idb-test-kit.js';
import { StorageWatch, storageLevelOf } from '#lib/history/storage-watch.svelte.js';

vi.mock('$app/navigation', () => ({ goto: vi.fn(() => Promise.resolve()) }));
let current: HistoryStore | null = null;
vi.mock('#lib/history/index.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/history/index.js')>()),
	getHistoryStore: () => current
}));
let used = 0;
const quota = 1_000_000;
let watch: StorageWatch;
vi.mock('#lib/history/storage-watch.svelte.js', async (importOriginal) => ({
	...(await importOriginal<typeof import('#lib/history/storage-watch.svelte.js')>()),
	getStorageWatch: () => watch
}));

const { default: HistoryStorageStrip } = await import('./HistoryStorageStrip.svelte');

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
	watch = new StorageWatch(async () => ({ usage: used, quota }));
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

describe('storage levels', () => {
	it('80% and 95% of the quota; unknown when the browser doesn’t say', () => {
		expect(storageLevelOf({ usage: 79, quota: 100 })).toBe('ok');
		expect(storageLevelOf({ usage: 80, quota: 100 })).toBe('getting-full');
		expect(storageLevelOf({ usage: 94.9, quota: 100 })).toBe('getting-full');
		expect(storageLevelOf({ usage: 95, quota: 100 })).toBe('nearly-full');
		expect(storageLevelOf(undefined)).toBeNull();
		expect(storageLevelOf({ usage: 5 })).toBeNull();
		expect(storageLevelOf({ usage: 5, quota: 0 })).toBeNull();
	});
});

describe('the banners', () => {
	it('a write the disk refuses: the unsaved banner, with Download and Free up space, and nothing lost', async () => {
		await writeOldCopy([realShot(0, realSamples(4))]);
		await boot();
		let full = false;
		const store = await boot({
			dbFault: (op) => {
				if (full && op === 'write') throw new DOMException('full', 'QuotaExceededError');
			}
		});
		full = true;
		const made = store.recordCaptured(realShot(5, realSamples(6)) as unknown as Record<string, unknown>)!;
		await store.flushed();
		const target = show(store);
		expect(target.textContent).toContain('isn’t saved: storage is full');
		expect(button(target, 'Download this shot')).toBeDefined();
		button(target, 'Free up space')!.click();
		expect(goto).toHaveBeenCalledWith('/settings#sharing');
		expect(store.get(made.id)).toBeDefined();
		full = false;
		store.setRating(made.id, 4);
		await store.flushed();
		expect((await stored()).details.get(made.id)!.samples).toHaveLength(6);
		await settle();
		expect(target.textContent).not.toContain('storage is full');
	});

	it('95%: a lasting banner with Back up and Free up space', async () => {
		await writeOldCopy([realShot(0, realSamples(4))]);
		const store = await boot();
		used = 960_000;
		const target = show(store);
		await settle();
		const strip = target.querySelector('[data-testid="storage-nearly-full"]');
		expect(strip?.textContent).toContain('Storage is nearly full (96%');
		expect(button(target, 'Dismiss')).toBeUndefined();
		button(target, 'Back up')!.click();
		button(target, 'Free up space')!.click();
		expect(vi.mocked(goto).mock.calls).toEqual([['/settings#sharing'], ['/settings#sharing']]);
		// Nothing was deleted to make room.
		expect(store.rawAll).toHaveLength(1);
	});

	it('80%: one notice, dismissed for good until use drops below 80% and climbs again', async () => {
		await writeOldCopy([realShot(0, realSamples(4))]);
		const store = await boot();
		used = 850_000;
		let target = show(store);
		await settle();
		expect(target.querySelector('[data-testid="storage-getting-full"]')?.textContent).toContain('Storage is getting full (85%');
		button(target, 'Dismiss')!.click();
		flushSync();
		expect(target.querySelector('[data-testid="storage-getting-full"]')).toBeNull();
		// Another load, still at 85%: no notice.
		unmount(component!);
		watch = new StorageWatch(async () => ({ usage: used, quota }));
		target = show(store);
		await settle();
		expect(target.querySelector('[data-testid="storage-getting-full"]')).toBeNull();
		// Below 80%, then back over: the notice again.
		used = 500_000;
		await watch.check();
		flushSync();
		used = 820_000;
		await watch.check();
		flushSync();
		expect(target.querySelector('[data-testid="storage-getting-full"]')).not.toBeNull();
	});
});
