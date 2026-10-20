/**
 * Barista / drinker on stored shots (dye2 audit #1): the default barista is
 * stamped on a new brew, and the editor's setter trims, clears and survives
 * a reload.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { HistoryStore } from './store.svelte';

const brew = {
	method: 'pourover',
	completedAt: 1_700_000_000_000,
	bean: null,
	dose: 15,
	waterG: 250,
	yieldOut: null,
	grinderSetting: null,
	brewTempC: null,
	durationMs: null,
	rating: null,
	notes: null,
	nextPlan: null
};

beforeEach(() => localStorage.clear());

describe('barista and drinker', () => {
	it('stamps the default barista on a new brew, and none when blank', () => {
		const store = new HistoryStore();
		expect(store.addManualBrew({ ...brew, barista: ' Ana ' }).metadata.barista).toBe('Ana');
		expect(store.addManualBrew({ ...brew, barista: '  ' }).metadata.barista).toBeUndefined();
	});

	it('sets, trims and clears a person, and keeps it across a reload', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew(brew);
		store.setPerson(row.id, 'drinker', '  Ben ');
		store.setPerson(row.id, 'barista', 'Ana');
		const reloaded = new HistoryStore().get(row.id);
		expect(reloaded?.metadata.drinker).toBe('Ben');
		expect(reloaded?.metadata.barista).toBe('Ana');
		store.setPerson(row.id, 'barista', '');
		expect(store.get(row.id)?.metadata.barista).toBeNull();
	});
});
