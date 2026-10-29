import { afterEach, describe, expect, it, vi } from 'vitest';
import { prefersReducedMotion, scrollBehavior } from './motion.js';

function stubReduce(matches: boolean): void {
	vi.stubGlobal(
		'matchMedia',
		vi.fn((query: string) => ({
			matches: query === '(prefers-reduced-motion: reduce)' && matches,
			media: query
		}))
	);
}

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('scrollBehavior', () => {
	it('scrolls smoothly by default', () => {
		stubReduce(false);
		expect(prefersReducedMotion()).toBe(false);
		expect(scrollBehavior()).toBe('smooth');
	});

	it('jumps instantly when the user prefers reduced motion', () => {
		stubReduce(true);
		expect(prefersReducedMotion()).toBe(true);
		expect(scrollBehavior()).toBe('auto');
	});

	it('falls back to smooth where matchMedia does not exist', () => {
		vi.stubGlobal('matchMedia', undefined);
		expect(scrollBehavior()).toBe('smooth');
	});
});
