/**
 * #420: the curve archive in the storage view and the lost banner. Settings
 * shows it on its own line, and says "incomplete" with a count when a write
 * failed; the banner promises curves only for shots already archived, and
 * says so plainly when the archive was still filling.
 */
import '#lib/testing/dom-stubs.js';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION } from '#lib/history/history-db.js';
import { writeDoneMarker } from '#lib/history/store-marker.js';
import { summarize } from '#lib/history/summary.js';
import { freshStorage } from '#lib/history/idb-test-kit.js';
import { memoryFs } from '#lib/history/archive-test-kit.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import StorageSection from './StorageSection.svelte';
import HistoryStorageStrip from '#lib/shell/HistoryStorageStrip.svelte';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});

const settle = async (ms = 20) => {
	await new Promise((r) => setTimeout(r, ms));
	flushSync();
};

it('Settings says the archive is incomplete, with a count; the banner promises only archived curves', async () => {
	const fs = memoryFs();
	const idb = freshStorage(fs.opts);
	const shots = Array.from({ length: 3 }, (_, i) => ({ ...realShot(i, realSamples(4, i)), visualizerId: null }));
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);

	const history = getHistoryStore();
	await history.whenReady();
	await history.archiveCatchUpNow();
	fs.control.quota = true;
	history.recordCaptured({ ...realShot(9, realSamples(5)), visualizerId: null } as unknown as Record<string, unknown>);
	await history.flushed();
	await history.archiveSettled();

	const target = document.createElement('div');
	document.body.appendChild(target);
	const settings = mount(StorageSection, { target });
	await settle();
	const text = target.textContent ?? '';
	expect(text).toContain('Curve archive incomplete');
	expect(text).toContain('3 of 4 shots’ curves are archived.');
	expect(text).toContain('1 wait');
	expect(target.querySelector('[data-testid="curve-archive-size"]')?.textContent).toMatch(/KB/);
	unmount(settings);

	// The store goes under the app: the banner says what the archive holds.
	await new Promise<void>((resolve) => {
		const req = idb.deleteDatabase('crema-history');
		req.onsuccess = () => resolve();
		req.onblocked = () => resolve();
	});
	history.setRating(shots[0].id, 2);
	await history.flushed();
	expect(history.health).toBe('lost');
	const strip = mount(HistoryStorageStrip, { target });
	await settle();
	expect(target.textContent).toContain('Curves come back for shots already archived (3 curves)');
	expect(target.textContent).not.toContain('Every shot');
	unmount(strip);
	target.remove();
});
