import { describe, expect, it, vi } from 'vitest';
import { CoreRecoveryWatch } from './recovery-watch.js';

describe('CoreRecoveryWatch', () => {
	it('stays quiet while the reset count does not change', () => {
		const onReset = vi.fn();
		const watch = new CoreRecoveryWatch(() => 0, onReset);
		watch.check();
		watch.check();
		expect(onReset).not.toHaveBeenCalled();
	});

	it('fires once per reset, with the new count', () => {
		let count = 0;
		const onReset = vi.fn();
		const watch = new CoreRecoveryWatch(() => count, onReset);
		count = 1;
		watch.check();
		watch.check();
		count = 2;
		watch.check();
		expect(onReset.mock.calls).toEqual([[1], [2]]);
	});

	it('ignores resets that happened before it was created', () => {
		const onReset = vi.fn();
		const watch = new CoreRecoveryWatch(() => 3, onReset);
		watch.check();
		expect(onReset).not.toHaveBeenCalled();
	});

	it('is a no-op on a core without the counter', () => {
		const onReset = vi.fn();
		new CoreRecoveryWatch(undefined, onReset).check();
		expect(onReset).not.toHaveBeenCalled();
	});
});
