import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';

import {
	LEGACY_BARISTA_PENDING_KEY,
	PENDING_EDITS_KEY,
	clearPendingEdits,
	markShotEdit,
	pendingEdit,
	pendingEdits,
	resetPendingEditsForTest,
	settlePendingEdit
} from './pending-edits.ts';

beforeAll(async () => {
	await initTestWasm();
});

describe('pending edits', () => {
	beforeEach(() => {
		localStorage.clear();
		resetPendingEditsForTest();
	});
	afterEach(() => vi.restoreAllMocks());

	it('a mark cleared in another tab is cleared here too (C3 #8)', () => {
		markShotEdit('x', 'barista');
		expect(pendingEdit('x')).toEqual({ barista: true });
		// Another tab's PATCH lands.
		localStorage.setItem(PENDING_EDITS_KEY, '{}');
		expect(pendingEdit('x')).toBeUndefined();
	});

	it('the set reads storage once and holds every pending shot (C3 #10)', () => {
		markShotEdit('a', 'annotations');
		markShotEdit('b', 'barista');
		const reads = vi.spyOn(localStorage, 'getItem');
		const all = pendingEdits();
		// The current set and the old one it migrates from.
		expect(reads).toHaveBeenCalledTimes(2);
		expect([...all.keys()].sort()).toEqual(['a', 'b']);
	});

	it('a tag add cancels a matching removal, and the reverse', () => {
		markShotEdit('x', { tags: { before: ['a', 'b'], after: ['a'] } });
		expect(pendingEdit('x')?.tags).toEqual({ add: [], remove: ['b'] });
		markShotEdit('x', { tags: { before: ['a'], after: ['a', 'B', 'c'] } });
		expect(pendingEdit('x')?.tags).toEqual({ add: ['c'], remove: [] });
	});

	it('a shot that owes nothing drops out', () => {
		markShotEdit('x', { tags: { before: [], after: ['a'] } });
		markShotEdit('x', { tags: { before: ['a'], after: [] } });
		expect(pendingEdit('x')).toBeUndefined();
		markShotEdit('y', 'annotations');
		settlePendingEdit('y', { annotations: undefined });
		expect(pendingEdits().size).toBe(0);
	});

	it('a new edit makes the shot due at once', () => {
		settlePendingEdit('x', { annotations: true, attempts: 2, nextAttemptAt: Date.now() + 60_000 });
		markShotEdit('x', 'barista');
		expect(pendingEdit('x')).toEqual({ annotations: true, barista: true });
	});

	it('the old baristaPending list migrates in on the first read', () => {
		localStorage.setItem(LEGACY_BARISTA_PENDING_KEY, JSON.stringify(['x', 'y']));
		markShotEdit('y', 'annotations');
		expect(Object.fromEntries(pendingEdits())).toEqual({
			x: { barista: true },
			y: { barista: true, annotations: true }
		});
		expect(localStorage.getItem(LEGACY_BARISTA_PENDING_KEY)).toBe('[]');
	});

	it('an account change clears both sets', () => {
		markShotEdit('x', 'annotations');
		localStorage.setItem(LEGACY_BARISTA_PENDING_KEY, JSON.stringify(['y']));
		clearPendingEdits();
		expect(pendingEdits().size).toBe(0);
	});

	it('keeps a mark storage refused in memory for this tab', () => {
		vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
		markShotEdit('y', 'barista');
		expect(pendingEdit('y')).toEqual({ barista: true });
		expect(localStorage.getItem(PENDING_EDITS_KEY), 'nothing was persisted').toBeNull();
		settlePendingEdit('y', undefined);
		expect(pendingEdit('y')).toBeUndefined();
	});
});
