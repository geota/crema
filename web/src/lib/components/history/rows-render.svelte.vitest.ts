/**
 * #310 P4w: drawing the list reads no `details` and makes no core call that
 * takes a shot (the only per-row call left is the ratio rule, on four numbers). Every row of a cold-started store is mounted as History draws
 * it, wide and phone, page after page as a scroll would, with a spy on the
 * store's details reads and on the wasm calls that parse a shot.
 */
import '#lib/testing/dom-stubs.js';
import { afterAll, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import type * as Wasm from '#lib/wasm/de1_wasm.js';

const wasmCalls = vi.hoisted(() => new Map<string, number>());
vi.mock('#lib/wasm/de1_wasm.js', async (importOriginal) => {
	const real = await importOriginal<typeof Wasm>();
	const out: Record<string, unknown> = { ...real };
	for (const [name, fn] of Object.entries(real)) {
		if (name === 'default' || typeof fn !== 'function' || /^[A-Z]/.test(name)) continue;
		out[name] = (...args: unknown[]) => {
			wasmCalls.set(name, (wasmCalls.get(name) ?? 0) + 1);
			return (fn as (...a: unknown[]) => unknown)(...args);
		};
	}
	return out;
});

import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { HistoryDb, META_MIGRATION } from '#lib/history/history-db.js';
import { writeDoneMarker } from '#lib/history/store-marker.js';
import { summarize } from '#lib/history/summary.js';
import { boot, freshStorage } from '#lib/history/idb-test-kit.js';
import type { StoredShot } from '#lib/history/model.js';
import ShotRow from './ShotRow.svelte';
import PhoneShotRow from './PhoneShotRow.svelte';

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});
beforeEach(() => {
	freshStorage();
	wasmCalls.clear();
});

const SHOT_CALLS = ['shotSummary', 'peaksForShot', 'pourAveragesForShot'];

it('mounting every row, page by page, reads no details and parses no shot', async () => {
	const shots: StoredShot[] = Array.from({ length: 320 }, (_, i) => realShot(i, realSamples(30, i % 60)));
	const db = new HistoryDb({ expectExisting: () => false });
	await db.putShots(
		shots.map((shot) => ({ shot, details: true })),
		summarize
	);
	await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: shots.map((s) => s.id) });
	db.close();
	writeDoneMarker(1, shots.length);

	let detailReads = 0;
	const store = await boot({
		dbFault: (op) => {
			if (op === 'read-details') detailReads += 1;
		}
	});
	wasmCalls.clear();
	const rows = store.all;
	expect(rows).toHaveLength(320);
	// Page after page, as a scroll down and back up would draw them.
	const pages = [0, 50, 100, 150, 200, 250, 200, 100, 0];
	let sparks = 0;
	for (const from of pages) {
		const target = document.createElement('div');
		document.body.appendChild(target);
		const mounted = rows.slice(from, from + 50).flatMap((shot) => [
			mount(ShotRow, { target, props: { shot, onclick: () => {} } }),
			mount(PhoneShotRow, { target, props: { shot, syncPip: 'local', syncTitle: '', onclick: () => {} } })
		]);
		flushSync();
		sparks += target.querySelectorAll('svg.spark-chart').length;
		for (const m of mounted) unmount(m);
		target.remove();
	}
	expect(sparks, 'each row drew its spark').toBe(pages.length * 50 * 2);
	expect(detailReads).toBe(0);
	for (const name of SHOT_CALLS) expect(wasmCalls.get(name) ?? 0, name).toBe(0);
	// The one core call a row makes is the ratio rule, which takes four numbers
	// and no shot. Any other call made per row fails here.
	const perRow = [...wasmCalls].filter(([, n]) => n >= 50).map(([name]) => name);
	expect(perRow).toEqual(['ratioForMethod']);
}, 60_000);
