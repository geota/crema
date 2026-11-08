/**
 * A shot finished in a tab a restore froze: nothing is written while frozen,
 * and after the reload it's recorded the normal way, row, series and debit.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { coerceBean, getBeanStore } from '#lib/bean/index.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import { freezeOverlay } from '#lib/restore/freeze-overlay.svelte.js';
import {
	FROZEN_SHOTS_KEY,
	installRestoreListener,
	peekFrozenShots,
	resetTabSafetyForTest,
	type RestoreMessage
} from '#lib/restore/tab-safety.js';
import { getHistoryStore } from './store.svelte';
import { FakeLocks } from '#lib/testing/fake-locks.js';

vi.mock('#lib/bean/bag-empty-prompt.js', () => ({ promptBagEmpty: () => {} }));

const { commitShotCompletion, commitFrozenShots } = await import('./shot-persistence');

function finish(id: string): void {
	getActiveShotStore().set({
		bean: { beanId: 'a', name: 'a', tags: [] },
		profileName: null,
		dose: 18,
		brewParams: null,
		grinderModel: null,
		source: 'live'
	});
	commitShotCompletion({ content: { duration: 28_000, disposition: 'Record' } } as never, {
		snapshot: { activeProfileName: null },
		shotStartedAtMs: null,
		lastNotificationAtMs: null,
		sliceJsonl: async () => '',
		fireWebhook: () => {},
		takeStoredShot: () => ({
			formatVersion: 3,
			id,
			completedAt: 1_700_000_000_000,
			metadata: { dose: 18 },
			record: { duration: 28_000, samples: [{ elapsed: 0, sample: { groupPressure: 9 } }] }
		}),
		runtime: null
	} as never);
}

/** Freeze this tab as a restore elsewhere would, without dropping the test's own writes. */
function freezeHere(): void {
	const channel = { onmessage: null, close: () => {} } as unknown as BroadcastChannel;
	installRestoreListener({ busy: () => true, onFrozen: () => {}, freeze: () => {}, reload: () => {}, channel: () => channel });
	(channel.onmessage as (e: MessageEvent) => void)({ data: { type: 'started' } as RestoreMessage } as MessageEvent);
}

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	resetTabSafetyForTest();
	freezeOverlay.resetForTest();
	localStorage.clear();
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getBeanStore().upsertBean({ ...coerceBean({ id: 'a', name: 'a' })!, bagSize: 250, remaining: 100 });
});

describe('a shot in a frozen tab', () => {
	it('writes nothing while frozen, and is recorded with its debit after the reload', async () => {
		freezeHere();
		finish('shot:frozen');
		expect(getHistoryStore().get('shot:frozen')).toBeUndefined();
		expect(getBeanStore().getBean('a')?.remaining).toBe(100);

		// The reload: a fresh tab, no longer frozen.
		resetTabSafetyForTest();
		await commitFrozenShots(null);
		expect(getHistoryStore().get('shot:frozen')?.bagDebit).toBe(18);
		expect(getBeanStore().getBean('a')?.remaining).toBe(82);
		await commitFrozenShots(null);
		expect(getHistoryStore().all.filter((s) => s.id === 'shot:frozen')).toHaveLength(1);
	});

	it('waits while this boot is frozen too, and is kept', async () => {
		freezeHere();
		finish('shot:twice');
		// A boot that a restore still freezes: nothing is recorded, nothing lost.
		await commitFrozenShots(null);
		expect(getHistoryStore().get('shot:twice')).toBeUndefined();
		expect(peekFrozenShots()).toHaveLength(1);
		resetTabSafetyForTest();
		await commitFrozenShots(null);
		expect(getHistoryStore().get('shot:twice')?.bagDebit).toBe(18);
		expect(peekFrozenShots()).toEqual([]);
	});

	it('stays kept when recording it fails', async () => {
		freezeHere();
		finish('shot:fails');
		resetTabSafetyForTest();
		const spy = vi.spyOn(getHistoryStore(), 'recordCaptured').mockImplementation(() => {
			throw new Error('quota');
		});
		await expect(commitFrozenShots(null)).rejects.toThrow('quota');
		spy.mockRestore();
		expect(peekFrozenShots()).toHaveLength(1);
	});

	it('already in history (a duplicated tab) is not recorded or debited twice', async () => {
		freezeHere();
		finish('shot:dup');
		const stash = localStorage.getItem(FROZEN_SHOTS_KEY)!;
		resetTabSafetyForTest();
		await commitFrozenShots(null);
		expect(getBeanStore().getBean('a')?.remaining).toBe(82);
		// The second tab boots with its own copy of the same stash.
		localStorage.setItem(FROZEN_SHOTS_KEY, stash);
		await commitFrozenShots(null);
		expect(getHistoryStore().all.filter((s) => s.id === 'shot:dup')).toHaveLength(1);
		expect(getBeanStore().getBean('a')?.remaining).toBe(82);
		expect(peekFrozenShots()).toEqual([]);
	});

	it('survives a boot that stops part-way: recorded before it is removed', async () => {
		freezeHere();
		finish('shot:one');
		finish('shot:two');
		expect(peekFrozenShots()).toHaveLength(2);
		resetTabSafetyForTest();
		const store = getHistoryStore();
		const record = store.recordCaptured.bind(store);
		const spy = vi.spyOn(store, 'recordCaptured').mockImplementation((captured) => {
			if (captured.id === 'shot:two') throw new Error('the tab closed');
			return record(captured);
		});
		await expect(commitFrozenShots(null)).rejects.toThrow('the tab closed');
		spy.mockRestore();
		// Nothing stashed is gone; the next boot finishes, once per shot.
		expect(peekFrozenShots().map((s) => s.captured.id)).toEqual(['shot:one', 'shot:two']);
		await commitFrozenShots(null);
		expect(store.get('shot:one')).toBeDefined();
		expect(store.get('shot:two')).toBeDefined();
		expect(getBeanStore().getBean('a')?.remaining).toBe(64);
		expect(peekFrozenShots()).toEqual([]);
	});

	it('is committed by one tab at a time, so a shot is recorded and uploaded once', async () => {
		freezeHere();
		finish('shot:shared');
		resetTabSafetyForTest();
		const locks = new FakeLocks();
		const release = await locks.hold(FROZEN_SHOTS_KEY);
		// Another tab is committing: this boot waits for it.
		const first = commitFrozenShots(null, locks);
		await new Promise((r) => setTimeout(r, 0));
		expect(getHistoryStore().get('shot:shared')).toBeUndefined();
		// That tab took the stash; this one then finds nothing to do.
		const stash = localStorage.getItem(FROZEN_SHOTS_KEY);
		localStorage.removeItem(FROZEN_SHOTS_KEY);
		release();
		await first;
		expect(getHistoryStore().get('shot:shared')).toBeUndefined();
		expect(stash).not.toBeNull();
	});

	it('that storage refuses is offered on the overlay instead', () => {
		freezeHere();
		const spy = vi.spyOn(Object.getPrototypeOf(localStorage) as Storage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
		finish('shot:lost');
		spy.mockRestore();
		expect(freezeOverlay.unsaved?.id).toBe('shot:lost');
	});

	it('is recorded at once when nothing is frozen', () => {
		finish('shot:normal');
		expect(getHistoryStore().get('shot:normal')?.bagDebit).toBe(18);
	});
});
