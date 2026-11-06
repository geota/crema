import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import { coerceBean } from './model';
import { getBeanStore } from './store.svelte';

const promptBagEmpty = vi.fn();
vi.mock('./bag-empty-prompt', () => ({ promptBagEmpty: (id: string) => promptBagEmpty(id) }));

const { moveShotBag } = await import('./move-shot-bag');

function bag(id: string, bagSize: number, remaining: number): void {
	getBeanStore().upsertBean({ ...coerceBean({ id, name: id })!, bagSize, remaining });
}

const remaining = (id: string): number | undefined => getBeanStore().getBean(id)?.remaining;

/** A logged 18 g row on `beanId`, with `bagDebit` as given (omit for an old row). */
function row(beanId: string, bagDebit?: number): StoredShot {
	const shot = getHistoryStore().addManualBrew({
		method: 'espresso',
		completedAt: 1_700_000_000_000,
		bean: { beanId, name: beanId, tags: [] },
		dose: 18,
		waterG: null,
		yieldOut: 36,
		grinderSetting: null,
		brewTempC: null,
		durationMs: 28_000,
		rating: null,
		notes: null,
		nextPlan: null
	});
	if (bagDebit !== undefined) getHistoryStore().setBagDebit(shot.id, bagDebit);
	return getHistoryStore().get(shot.id)!;
}

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	promptBagEmpty.mockClear();
});

describe('moveShotBag', () => {
	it('credits only what the row took', () => {
		bag('a', 250, 100);
		moveShotBag(row('a', 10), null);
		expect(remaining('a')).toBe(110);
	});

	it('credits nothing for a row that took nothing', () => {
		bag('a', 250, 100);
		moveShotBag(row('a', 0), null);
		expect(remaining('a')).toBe(100);
	});

	it('gives an untracked bag nothing', () => {
		bag('a', 0, 0);
		moveShotBag(row('a', 18), null);
		expect(remaining('a')).toBe(0);
	});

	it('adds nothing moving five old shots off an empty bag', () => {
		bag('a', 250, 0);
		for (let i = 0; i < 5; i++) moveShotBag(row('a'), null);
		expect(remaining('a')).toBe(0);
	});

	it('still credits an old row’s dose while the bag holds grams', () => {
		bag('a', 250, 100);
		moveShotBag(row('a'), null);
		expect(remaining('a')).toBe(118);
	});

	it('caps an old row at the bag size', () => {
		bag('a', 250, 245);
		moveShotBag(row('a'), null);
		expect(remaining('a')).toBe(250);
	});

	it('debits the new bag and records what it took', () => {
		bag('a', 250, 100);
		bag('b', 250, 200);
		const shot = row('a', 18);
		moveShotBag(shot, 'b');
		expect(remaining('a')).toBe(118);
		expect(remaining('b')).toBe(182);
		expect(getHistoryStore().get(shot.id)?.bagDebit).toBe(18);
	});

	it('records a partial debit on the new bag', () => {
		bag('a', 250, 100);
		bag('b', 250, 10);
		const shot = row('a', 18);
		moveShotBag(shot, 'b');
		expect(remaining('b')).toBe(0);
		expect(getHistoryStore().get(shot.id)?.bagDebit).toBe(10);
	});

	it('prompts when the move empties the new bag', () => {
		bag('a', 250, 100);
		bag('b', 250, 10);
		moveShotBag(row('a', 18), 'b');
		expect(promptBagEmpty).toHaveBeenCalledWith('b');
	});

	it('moves back and forth without changing the total', () => {
		bag('a', 250, 100);
		bag('b', 250, 5);
		const shot = row('a', 18);
		moveShotBag(shot, 'b');
		// The row took 5 from b, so that's all b gets back.
		moveShotBag({ ...getHistoryStore().get(shot.id)!, bean: { beanId: 'b', name: 'b', tags: [] } }, 'a');
		expect(remaining('a')).toBe(100);
		expect(remaining('b')).toBe(5);
	});

	it('clears the record when the shot loses its bag', () => {
		bag('a', 250, 100);
		const shot = row('a', 18);
		moveShotBag(shot, null);
		expect(getHistoryStore().get(shot.id)?.bagDebit).toBeUndefined();
	});

	it('leaves a pick of the same bag alone', () => {
		bag('a', 250, 100);
		moveShotBag(row('a', 18), 'a');
		expect(remaining('a')).toBe(100);
	});
});
