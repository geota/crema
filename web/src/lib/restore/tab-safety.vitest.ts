/**
 * A restore in one tab can't cost another tab its machine, and a frozen tab
 * reloads only when nothing would be lost (restore-safety design, section 2).
 * jsdom has neither Web Locks nor a shared BroadcastChannel, so both are
 * stood in for.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import {
	FROZEN_SHOTS_KEY,
	MACHINE_BUSY_LOCK,
	MachineBusy,
	RESTORE_FALLBACK_MS,
	RESTORE_LOCK,
	installRestoreListener,
	markRestoringHere,
	resetTabSafetyForTest,
	restoreFrozen,
	stashFrozenShot,
	takeFrozenShots,
	type RestoreMessage
} from './tab-safety';

/** A channel a test can post into, as another tab would. */
function fakeChannel(): { channel: BroadcastChannel; post: (m: RestoreMessage) => void } {
	const channel = { onmessage: null, close: () => {} } as unknown as BroadcastChannel;
	return {
		channel,
		post: (m) => (channel.onmessage as (e: MessageEvent) => void)?.({ data: m } as MessageEvent)
	};
}

const settle = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => resetTabSafetyForTest());
afterEach(() => vi.useRealTimers());

describe('MachineBusy', () => {
	it('holds the lock shared while a shot runs, and lets it go after', async () => {
		const locks = new FakeLocks();
		const busy = new MachineBusy(locks);
		expect(await busy.hold('shot')).toBe(true);
		expect(locks.held).toEqual([{ name: MACHINE_BUSY_LOCK, mode: 'shared' }]);
		busy.release('shot');
		await settle();
		expect(locks.held).toEqual([]);
	});

	it('a shot in one tab refuses a restore in another', async () => {
		const locks = new FakeLocks();
		await new MachineBusy(locks).hold('shot');
		let refused = false;
		await locks.request(MACHINE_BUSY_LOCK, { mode: 'exclusive', ifAvailable: true }, (lock) => {
			refused = lock === null;
		});
		expect(refused).toBe(true);
	});

	it('a flash can not start while a restore holds the machine', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(MACHINE_BUSY_LOCK);
		const busy = new MachineBusy(locks);
		expect(await busy.hold('flash-start')).toBe(false);
		expect(busy.held).toBe(false);
		release();
	});

	it('hands over between reasons without letting go', async () => {
		const locks = new FakeLocks();
		const busy = new MachineBusy(locks);
		await busy.hold('flash-start');
		void busy.hold('flash');
		busy.release('flash-start');
		await settle();
		expect(busy.held).toBe(true);
		busy.release('flash');
		await settle();
		expect(busy.held).toBe(false);
	});

	it('without Web Locks a shot or flash just runs', async () => {
		expect(await new MachineBusy(undefined).hold('flash-start')).toBe(true);
	});
});

describe('a frozen tab', () => {
	function listener(over: { locks?: FakeLocks; busy?: () => boolean; holdReload?: () => boolean } = {}) {
		const { channel, post } = fakeChannel();
		const reload = vi.fn();
		const freeze = vi.fn();
		const onFrozen = vi.fn();
		const l = installRestoreListener({
			busy: over.busy ?? (() => false),
			holdReload: over.holdReload,
			onFrozen,
			reload,
			freeze,
			locks: over.locks,
			channel: () => channel
		});
		return { l, post, reload, freeze, onFrozen };
	}

	it('freezes and shows the overlay when a restore starts elsewhere', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(RESTORE_LOCK);
		const t = listener({ locks });
		t.post({ type: 'started' });
		expect(t.freeze).toHaveBeenCalledOnce();
		expect(t.onFrozen).toHaveBeenCalledOnce();
		expect(restoreFrozen()).toBe(true);
		await settle();
		expect(t.reload).not.toHaveBeenCalled();
		release();
	});

	it('reloads when the restoring tab lets go of its lock', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(RESTORE_LOCK);
		const t = listener({ locks });
		t.post({ type: 'started' });
		await settle();
		release();
		await settle();
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('reloads on done, whichever comes first', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(RESTORE_LOCK);
		const t = listener({ locks });
		t.post({ type: 'started' });
		t.post({ type: 'done' });
		expect(t.reload).toHaveBeenCalledOnce();
		release();
		await settle();
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('never reloads mid-shot or mid-flash: it waits for the end', async () => {
		let running = true;
		const t = listener({ busy: () => running });
		t.post({ type: 'done' });
		expect(t.reload).not.toHaveBeenCalled();
		t.l.idle();
		expect(t.reload).not.toHaveBeenCalled();
		running = false;
		t.l.idle();
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('waits for a shot it could not keep to be downloaded', () => {
		let unsaved = true;
		const t = listener({ holdReload: () => unsaved });
		t.post({ type: 'done' });
		expect(t.reload).not.toHaveBeenCalled();
		unsaved = false;
		t.l.idle();
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('without Web Locks, reloads after the fallback, which progress resets', () => {
		vi.useFakeTimers();
		const t = listener();
		t.post({ type: 'started' });
		vi.advanceTimersByTime(RESTORE_FALLBACK_MS - 1000);
		t.post({ type: 'progress' });
		vi.advanceTimersByTime(RESTORE_FALLBACK_MS - 1000);
		expect(t.reload).not.toHaveBeenCalled();
		vi.advanceTimersByTime(2000);
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('a refused lock request falls back to the timer', async () => {
		vi.useFakeTimers();
		const locks = new FakeLocks();
		locks.request = () => Promise.reject(new Error('opaque origin'));
		const t = listener({ locks });
		t.post({ type: 'started' });
		await vi.advanceTimersByTimeAsync(RESTORE_FALLBACK_MS + 1);
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('the restoring tab ignores its own messages', () => {
		markRestoringHere(true);
		const t = listener();
		t.post({ type: 'started' });
		t.post({ type: 'done' });
		expect(t.freeze).not.toHaveBeenCalled();
		expect(t.reload).not.toHaveBeenCalled();
	});

	it('a tab that boots during a restore freezes and waits the same way', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(RESTORE_LOCK);
		const t = listener({ locks });
		await t.l.checkAtBoot();
		expect(t.freeze).toHaveBeenCalledOnce();
		release();
		await settle();
		expect(t.reload).toHaveBeenCalledOnce();
	});

	it('a tab that boots with no restore running does nothing', async () => {
		const t = listener({ locks: new FakeLocks() });
		await t.l.checkAtBoot();
		expect(t.freeze).not.toHaveBeenCalled();
	});
});

describe('shots a frozen tab finished', () => {
	beforeEach(() => sessionStorage.clear());

	it('are kept for the reload, in a key the freeze leaves alone', () => {
		const shot = { captured: { id: 's1' }, beanId: 'b', dose: 18, live: true };
		expect(stashFrozenShot(shot)).toBe(true);
		expect(stashFrozenShot({ ...shot, captured: { id: 's2' } })).toBe(true);
		expect(FROZEN_SHOTS_KEY.startsWith('crema.')).toBe(false);
		expect(takeFrozenShots().map((s) => s.captured.id)).toEqual(['s1', 's2']);
		expect(takeFrozenShots()).toEqual([]);
	});

	it('say so when sessionStorage refuses them', () => {
		const storage = { getItem: () => null, setItem: () => { throw new DOMException('full', 'QuotaExceededError'); } } as unknown as Storage;
		expect(stashFrozenShot({ captured: {}, beanId: null, dose: null, live: true }, storage)).toBe(false);
	});
});
