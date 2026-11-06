/**
 * What a completed shot records as taken from its bag (#303): the row's
 * `bagDebit` on the live path, the aborted-shot undo and a replay.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { coerceBean, getBeanStore } from '#lib/bean/index.js';
import { getToasts, dismissToast } from '#lib/components/shared/toast.svelte.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import { getHistoryStore } from './store.svelte';

const promptBagEmpty = vi.fn();
vi.mock('#lib/bean/bag-empty-prompt.js', () => ({
	promptBagEmpty: (id: string) => promptBagEmpty(id)
}));

const { commitShotCompletion } = await import('./shot-persistence');
type Event = Parameters<typeof commitShotCompletion>[0];

function captured(id: string, dose: number): Record<string, unknown> {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		metadata: { dose },
		record: { duration: 28_000, samples: [{ elapsed: 0, sample: { groupPressure: 9 } }] }
	};
}

function complete(id: string, disposition: 'Record' | 'DiscardAborted', source: 'live' | 'replay'): void {
	getActiveShotStore().set({
		bean: { beanId: 'a', name: 'a', tags: [] },
		profileName: null,
		dose: 18,
		brewParams: null,
		grinderModel: null,
		source
	});
	const event = { content: { duration: 28_000, disposition } } as unknown as Event;
	commitShotCompletion(event, {
		snapshot: { activeProfileName: null },
		shotStartedAtMs: null,
		lastNotificationAtMs: null,
		sliceJsonl: async () => '',
		fireWebhook: () => {},
		takeStoredShot: () => captured(id, 18),
		runtime: null
	} as unknown as Parameters<typeof commitShotCompletion>[1]);
}

function bag(remaining: number): void {
	getBeanStore().upsertBean({ ...coerceBean({ id: 'a', name: 'a' })!, bagSize: 250, remaining });
}

const remaining = (): number | undefined => getBeanStore().getBean('a')?.remaining;

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getToasts().forEach((t) => dismissToast(t.id));
	promptBagEmpty.mockClear();
});

describe('commitShotCompletion', () => {
	it('records the dose a shot took', () => {
		bag(100);
		complete('shot:1', 'Record', 'live');
		expect(getHistoryStore().get('shot:1')?.bagDebit).toBe(18);
		expect(remaining()).toBe(82);
	});

	it('records a partial debit and prompts', () => {
		bag(10);
		complete('shot:1', 'Record', 'live');
		expect(getHistoryStore().get('shot:1')?.bagDebit).toBe(10);
		expect(promptBagEmpty).toHaveBeenCalledWith('a');
	});

	it('records nothing taken from an empty bag', () => {
		bag(0);
		complete('shot:1', 'Record', 'live');
		expect(getHistoryStore().get('shot:1')?.bagDebit).toBe(0);
		expect(promptBagEmpty).not.toHaveBeenCalled();
	});

	it('leaves the bag alone on a replay', () => {
		bag(100);
		complete('shot:1', 'Record', 'replay');
		expect(getHistoryStore().get('shot:1')?.bagDebit).toBe(0);
		expect(remaining()).toBe(100);
	});

	it('keeps the debit through an aborted shot’s undo', () => {
		bag(100);
		complete('shot:1', 'DiscardAborted', 'live');
		expect(getHistoryStore().get('shot:1')).toBeUndefined();
		getToasts().at(-1)?.action?.run();
		expect(getHistoryStore().get('shot:1')?.bagDebit).toBe(18);
		expect(remaining()).toBe(82);
	});
});
