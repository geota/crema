import { describe, expect, it, vi } from 'vitest';
import { CoreResetNotice } from './reset-notice.js';

function setup() {
	let idle = true;
	const applies: Array<() => void> = [];
	const withdrawn: number[] = [];
	const show = vi.fn((apply: () => void) => {
		applies.push(apply);
		const n = applies.length;
		return () => withdrawn.push(n);
	});
	const onDeferred = vi.fn();
	const reload = vi.fn();
	const notice = new CoreResetNotice({ canReload: () => idle, show, onDeferred, reload });
	return { notice, show, applies, withdrawn, onDeferred, reload, setIdle: (v: boolean) => (idle = v) };
}

describe('CoreResetNotice (final review 03 P3-2, 07 P2 #7)', () => {
	it('reloads on tap when nothing is running', () => {
		const t = setup();
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(1);
		t.applies[0]();
		expect(t.reload).toHaveBeenCalledTimes(1);
	});

	it('refuses to reload mid-shot: withdraws, says why, and offers again once idle', () => {
		const t = setup();
		t.notice.onReset();
		t.setIdle(false); // a shot started after the notice appeared
		t.applies[0]();
		expect(t.reload).not.toHaveBeenCalled();
		expect(t.withdrawn).toEqual([1]);
		expect(t.onDeferred).toHaveBeenCalledTimes(1);
		t.notice.onStateChange(); // still busy
		expect(t.show).toHaveBeenCalledTimes(1);
		t.setIdle(true);
		t.notice.onStateChange();
		expect(t.show).toHaveBeenCalledTimes(2);
		t.notice.onStateChange();
		expect(t.show).toHaveBeenCalledTimes(2);
		t.applies[1]();
		expect(t.reload).toHaveBeenCalledTimes(1);
	});

	it('a second reset does not stack a second notice, shown or parked', () => {
		const t = setup();
		t.notice.onReset();
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(1);
		t.setIdle(false);
		t.applies[0]();
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(1);
	});
});
