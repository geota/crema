import { describe, expect, it, vi } from 'vitest';
import { CoreResetNotice } from './reset-notice.js';

function setup() {
	let idle = true;
	const applies: Array<() => void> = [];
	/** Each shown notice's toast-gone hook (×, Reload, or withdrawn). */
	const closes: Array<() => void> = [];
	const withdrawn: number[] = [];
	const show = vi.fn((apply: () => void, closed: () => void) => {
		applies.push(apply);
		closes.push(closed);
		const n = applies.length;
		return () => {
			withdrawn.push(n);
			closed(); // the toast store runs the hook on any removal
		};
	});
	const stopReloads: Array<() => void> = [];
	const showStopped = vi.fn((reload: () => void) => void stopReloads.push(reload));
	const onDeferred = vi.fn();
	const reload = vi.fn();
	const notice = new CoreResetNotice({ canReload: () => idle, show, showStopped, onDeferred, reload });
	return {
		notice,
		show,
		showStopped,
		stopReloads,
		applies,
		closes,
		withdrawn,
		onDeferred,
		reload,
		setIdle: (v: boolean) => (idle = v)
	};
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

	it('closing the notice with × lets a later reset show it again (V3 #3)', () => {
		const t = setup();
		t.notice.onReset();
		t.closes[0](); // the user taps ×
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(2);
		// Still no stacking while the new one is up.
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(2);
	});

	it('a stale close hook does not forget the notice shown after it', () => {
		const t = setup();
		t.notice.onReset();
		t.closes[0]();
		t.notice.onReset();
		t.closes[0](); // a late duplicate from the first toast
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(2);
	});
});

describe('CoreResetNotice when the core stops (V3 #7, V2 P3-1)', () => {
	it('withdraws the reset notice and pins the stop notice once', () => {
		const t = setup();
		t.notice.onReset();
		t.notice.onCoreStopped();
		expect(t.withdrawn).toEqual([1]);
		expect(t.showStopped).toHaveBeenCalledTimes(1);
		t.notice.onCoreStopped();
		expect(t.showStopped).toHaveBeenCalledTimes(1);
	});

	it("the stop notice's Reload is not gated: it reloads mid-shot", () => {
		const t = setup();
		t.notice.onReset();
		t.setIdle(false);
		t.notice.onCoreStopped();
		t.stopReloads[0]();
		expect(t.reload).toHaveBeenCalledTimes(1);
		expect(t.onDeferred).not.toHaveBeenCalled();
	});

	it('no reset notice comes back after the stop, shown or parked', () => {
		const t = setup();
		t.notice.onReset();
		t.setIdle(false);
		t.applies[0](); // parked: busy tap
		t.notice.onCoreStopped();
		expect(t.withdrawn).toEqual([1]); // nothing up to withdraw again
		t.setIdle(true);
		t.notice.onStateChange();
		t.notice.onReset();
		expect(t.show).toHaveBeenCalledTimes(1);
		expect(t.showStopped).toHaveBeenCalledTimes(1);
	});
});
