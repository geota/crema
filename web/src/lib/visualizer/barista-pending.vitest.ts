import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BARISTA_PENDING_KEY, BaristaPending } from './barista-pending.ts';

describe('BaristaPending', () => {
	beforeEach(() => localStorage.clear());
	afterEach(() => vi.restoreAllMocks());

	it('a mark cleared in another tab is cleared here too (C3 #8)', () => {
		const tabA = new BaristaPending();
		const tabB = new BaristaPending();
		tabA.add('x');
		expect(tabB.has('x')).toBe(true);
		// Tab B's PATCH lands.
		tabB.delete('x');
		expect(tabA.has('x')).toBe(false);
		expect(localStorage.getItem(BARISTA_PENDING_KEY)).toBe('[]');
	});

	it('a snapshot reads storage once and holds every pending id (C3 #10)', () => {
		const tab = new BaristaPending();
		tab.add('a');
		tab.add('b');
		const reads = vi.spyOn(localStorage, 'getItem');
		const pending = tab.snapshot();
		expect(reads).toHaveBeenCalledTimes(1);
		expect([...pending].sort()).toEqual(['a', 'b']);
		expect(pending.has('c')).toBe(false);
	});

	it('keeps a mark storage refused in memory for this tab', () => {
		vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
		const tab = new BaristaPending();
		tab.add('y');
		expect(tab.has('y')).toBe(true);
		expect(new BaristaPending().has('y'), 'nothing was persisted').toBe(false);
		tab.delete('y');
		expect(tab.has('y')).toBe(false);
	});
});
