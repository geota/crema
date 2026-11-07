/**
 * A shot finished in a tab a restore froze: nothing is written while frozen,
 * and after the reload it's recorded the normal way, row, series and debit.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { coerceBean, getBeanStore } from '#lib/bean/index.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import { freezeOverlay } from '#lib/restore/freeze-overlay.svelte.js';
import { installRestoreListener, resetTabSafetyForTest, type RestoreMessage } from '#lib/restore/tab-safety.js';
import { getHistoryStore } from './store.svelte';

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
	sessionStorage.clear();
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getBeanStore().upsertBean({ ...coerceBean({ id: 'a', name: 'a' })!, bagSize: 250, remaining: 100 });
});

describe('a shot in a frozen tab', () => {
	it('writes nothing while frozen, and is recorded with its debit after the reload', () => {
		freezeHere();
		finish('shot:frozen');
		expect(getHistoryStore().get('shot:frozen')).toBeUndefined();
		expect(getBeanStore().getBean('a')?.remaining).toBe(100);

		// The reload: a fresh tab, no longer frozen.
		resetTabSafetyForTest();
		commitFrozenShots(null);
		expect(getHistoryStore().get('shot:frozen')?.bagDebit).toBe(18);
		expect(getBeanStore().getBean('a')?.remaining).toBe(82);
		commitFrozenShots(null);
		expect(getHistoryStore().all.filter((s) => s.id === 'shot:frozen')).toHaveLength(1);
	});

	it('that sessionStorage refuses is offered on the overlay instead', () => {
		freezeHere();
		const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
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
