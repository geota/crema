/**
 * A new bag snapshot never carries the bag's legacy grind and grinder
 * (REVIEW-FINAL S2): not on a logged brew, not on a shot moved to another
 * bag. Old rows keep theirs, and the fallback still reads them.
 */
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean } from '#lib/bean/index.js';
import { effectiveGrindSetting, grindLabel, snapshotFromBean, type StoredShot } from './model';
import { HistoryStore } from './store.svelte';

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => localStorage.clear());

const legacyBag = { ...blankBean('bean:a'), name: 'A', grinderSetting: '14', grinder: 'Niche', notes: 'jammy' };

describe('the bag snapshot', () => {
	it('drops the bag\'s legacy grind and grinder, and keeps its notes', () => {
		const snap = snapshotFromBean(legacyBag, null)!;
		expect(snap.beanId).toBe('bean:a');
		expect(snap.notes).toBe('jammy');
		expect(snap).not.toHaveProperty('grinderSetting');
		expect(snap).not.toHaveProperty('grinder');
	});

	it('a shot moved to a bag with a legacy grind shows and uploads no grind', () => {
		const store = new HistoryStore();
		const row = store.addManualBrew({
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
		});
		store.setBeanFromLive(row.id, legacyBag, null);
		const moved = store.get(row.id)!;
		expect(moved.bean?.beanId).toBe('bean:a');
		expect(effectiveGrindSetting(moved)).toBeNull();
		expect(grindLabel(moved)).toBeNull();
	});

	it('an old row keeps its own snapshot\'s grind as the fallback', () => {
		const old = { metadata: {}, bean: { name: 'A', grinderSetting: '14' } } as unknown as StoredShot;
		expect(effectiveGrindSetting(old)).toBe('14');
	});
});
