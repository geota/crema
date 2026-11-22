import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { AnyShot } from '#lib/history/model.js';
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
function row(beanId: string, bagDebit?: number, replay = false): AnyShot {
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
	const stored = getHistoryStore().get(shot.id)!;
	return replay ? { ...stored, replay: true } : stored;
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

	it('gives an untracked bag nothing', () => {
		bag('a', 0, 0);
		moveShotBag(row('a'), null);
		moveShotBag(row('a', 0), null);
		expect(remaining('a')).toBe(0);
	});

	it('gives a sizeless bag back a recorded debit', () => {
		bag('a', 0, 0);
		moveShotBag(row('a', 18), null);
		expect(remaining('a')).toBe(18);
	});

	it('gives nothing back for an imported row', () => {
		bag('a', 250, 100);
		moveShotBag(row('a', 0), null);
		expect(remaining('a')).toBe(100);
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

	it('never debits the new bag for a replay, and records 0 (REVIEW-FINAL-2 MF1)', () => {
		bag('a', 250, 100);
		bag('b', 250, 200);
		const shot = row('a', 0, true);
		moveShotBag(shot, 'b');
		expect(remaining('a')).toBe(100);
		expect(remaining('b')).toBe(200);
		expect(getHistoryStore().get(shot.id)?.bagDebit).toBe(0);
	});

	it('moves nothing for an Android remote\'s mirror copy from a backup (#488 follow-up 51)', () => {
		bag('a', 250, 100);
		bag('b', 250, 200);
		const shot = { ...row('a'), mirrorCopy: true };
		moveShotBag(shot, 'b');
		expect(remaining('a')).toBe(100);
		expect(remaining('b')).toBe(200);
		expect(getHistoryStore().get(shot.id)?.bagDebit).toBe(0);
	});

	it('records 0 moving to a bag this library doesn\'t hold', () => {
		bag('a', 250, 100);
		const shot = row('a', 18);
		moveShotBag(shot, 'gone');
		expect(remaining('a')).toBe(118);
		expect(getHistoryStore().get(shot.id)?.bagDebit).toBe(0);
	});

	it('leaves a pick of the same bag alone', () => {
		bag('a', 250, 100);
		moveShotBag(row('a', 18), 'a');
		expect(remaining('a')).toBe(100);
	});
});
