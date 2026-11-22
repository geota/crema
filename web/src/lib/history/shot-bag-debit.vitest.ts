/**
 * What a completed shot records as taken from its bag (#303): the row's
 * `bagDebit` on the live path, the aborted-shot undo and a replay. The bag
 * is the one the core recorded on the shot (frozen at its shot start,
 * REVIEW-CORE H3), and `shot_effects` decides whether it is debited (M4).
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { coerceBean, getBeanStore } from '#lib/bean/index.js';
import { getToasts, dismissToast } from '#lib/components/shared/toast.svelte.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import { ratioLabel, yieldOf } from './model';
import { getHistoryStore } from './store.svelte';

const promptBagEmpty = vi.fn();
vi.mock('#lib/bean/bag-empty-prompt.js', () => ({
	promptBagEmpty: (id: string) => promptBagEmpty(id)
}));

const { commitShotCompletion } = await import('./shot-persistence');
type Event = Parameters<typeof commitShotCompletion>[0];

function captured(id: string, dose: number, beanId = 'a'): Record<string, unknown> {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		metadata: { dose },
		bean: { beanId, name: beanId },
		record: { duration: 28_000, samples: [{ elapsed: 0, sample: { groupPressure: 9 } }] }
	};
}

const fireWebhook = vi.fn();

function complete(
	id: string,
	disposition: 'Record' | 'DiscardAborted',
	source: 'live' | 'replay',
	{ inFlightBeanId = 'a', recordedBeanId = 'a' } = {}
): void {
	getActiveShotStore().set({
		bean: { beanId: inFlightBeanId, name: inFlightBeanId, tags: [] },
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
		fireWebhook,
		// As the core does: the capture context's replay flag becomes the row's mark.
		takeStoredShot: (c: { replay?: boolean }) => ({
			...captured(id, 18, recordedBeanId),
			...(c.replay ? { replay: true } : {})
		}),
		runtime: null
	} as unknown as Parameters<typeof commitShotCompletion>[1]);
}

function bag(remaining: number, id = 'a'): void {
	getBeanStore().upsertBean({ ...coerceBean({ id, name: id })!, bagSize: 250, remaining });
}

const remaining = (id = 'a'): number | undefined => getBeanStore().getBean(id)?.remaining;

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
	it('takes the dose of a shot with no yield, and reports no ratio', () => {
		bag(100);
		fireWebhook.mockClear();
		complete('shot:1', 'Record', 'live');
		const shot = getHistoryStore().get('shot:1')!;
		expect(shot.bagDebit).toBe(18);
		expect(remaining()).toBe(82);
		expect(yieldOf(shot)).toBeNull();
		expect(ratioLabel(shot)).toBe('1:—');
		expect(fireWebhook).toHaveBeenCalledWith(
			'shotCompleted',
			expect.objectContaining({ finalWeight: null, brewRatio: null })
		);
	});

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

	it('debits the bag the shot recorded, not the shell\'s in-flight copy (REVIEW-CORE H3)', () => {
		// The shell's in-flight copy says bag b (a stale or later selection);
		// the core froze bag a at shot start, and the row records a.
		bag(100, 'a');
		bag(100, 'b');
		complete('shot:1', 'Record', 'live', { inFlightBeanId: 'b', recordedBeanId: 'a' });
		expect(getHistoryStore().get('shot:1')?.bean?.beanId).toBe('a');
		expect(remaining('a')).toBe(82);
		expect(remaining('b')).toBe(100);
	});

	it('an aborted pull debits the bag it recorded, at once', () => {
		bag(100, 'a');
		bag(100, 'b');
		complete('shot:1', 'DiscardAborted', 'live', { inFlightBeanId: 'b', recordedBeanId: 'a' });
		expect(remaining('a')).toBe(82);
		expect(remaining('b')).toBe(100);
	});

	it('a replay row keeps its replay mark; a live one has none (#488 batch 6)', () => {
		complete('shot:1', 'Record', 'replay');
		complete('shot:2', 'Record', 'live');
		expect(getHistoryStore().get('shot:1')?.replay).toBe(true);
		expect(getHistoryStore().get('shot:2')?.replay).toBeUndefined();
	});

	it('a shot on a bag this library doesn\'t hold records 0, never "not recorded" (MF1)', () => {
		complete('shot:1', 'Record', 'live', { recordedBeanId: 'gone' });
		expect(getHistoryStore().get('shot:1')?.bagDebit).toBe(0);
	});

	it('a replay fires no webhook (shot_effects)', () => {
		fireWebhook.mockClear();
		complete('shot:1', 'Record', 'replay');
		expect(fireWebhook).not.toHaveBeenCalled();
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
