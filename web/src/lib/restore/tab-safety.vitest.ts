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
	STUCK_AFTER_MS,
	TAB_ID,
	announceRestore,
	dropFrozenShots,
	getMachineBusy,
	installRestoreListener,
	markRestoringHere,
	peekFrozenShots,
	reloadBlocked,
	resetTabSafetyForTest,
	restoreFrozen,
	shotFinished,
	shotIsOpen,
	shotStarted,
	stashFrozenShot,
	type RestoreMessage
} from './tab-safety';
import { MachineState } from '#lib/core/crema-core.js';
import type { ReloadInputs } from '#lib/pwa/update-gate.js';

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

	it('a shot refused during a restore queues, so a later restore still sees it', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(MACHINE_BUSY_LOCK);
		const busy = new MachineBusy(locks);
		busy.set('shot', true);
		await settle();
		expect(busy.held).toBe(false);
		release();
		await settle();
		expect(busy.held).toBe(true);
		let refused = false;
		await locks.request(MACHINE_BUSY_LOCK, { mode: 'exclusive', ifAvailable: true }, (lock) => {
			refused = lock === null;
		});
		expect(refused).toBe(true);
		busy.set('shot', false);
		await settle();
		expect(locks.held).toEqual([]);
	});

	it('a queued shot that ends before the restore does withdraws its request', async () => {
		const locks = new FakeLocks();
		const release = await locks.hold(MACHINE_BUSY_LOCK);
		const busy = new MachineBusy(locks);
		busy.set('shot', true);
		await settle();
		expect((await locks.query()).pending).toHaveLength(1);
		busy.set('shot', false);
		await settle();
		expect((await locks.query()).pending).toEqual([]);
		release();
		await settle();
		expect(busy.held).toBe(false);
		expect(locks.held).toEqual([]);
	});
});

describe('a shot, for the reload', () => {
	const parked: ReloadInputs = {
		link: 'up',
		machineStateName: MachineState.Idle,
		shotInProgress: false,
		guidedBrewLive: false,
		firmwareBusy: false
	};
	const blocked = (inputs: Partial<ReloadInputs>, more: { flash?: boolean; shotOpen?: boolean } = {}) =>
		reloadBlocked({ inputs: { ...parked, ...inputs }, flash: more.flash ?? false, shotOpen: more.shotOpen ?? false });

	it('holds the machine from ShotStarted until its completion is committed', async () => {
		const locks = new FakeLocks();
		const busy = new MachineBusy(locks);
		resetTabSafetyForTest(busy);
		shotStarted();
		await settle();
		expect(shotIsOpen()).toBe(true);
		expect(busy.held).toBe(true);
		// The machine is Idle, but the drip settle hasn't ended: still blocked.
		expect(blocked({}, { shotOpen: shotIsOpen() })).toBe(true);
		shotFinished();
		await settle();
		expect(busy.held).toBe(false);
		expect(blocked({}, { shotOpen: shotIsOpen() })).toBe(false);
	});

	it('waits for hot water, steam, a guided brew, a start or a link coming back', () => {
		expect(blocked({ machineStateName: MachineState.HotWater })).toBe(true);
		expect(blocked({ machineStateName: MachineState.Steam })).toBe(true);
		expect(blocked({ machineStateName: null })).toBe(true);
		expect(blocked({ link: 'transient' })).toBe(true);
		expect(blocked({ guidedBrewLive: true })).toBe(true);
		expect(blocked({ guidedBrewLive: true, link: 'down' })).toBe(true);
	});

	it('counts a machine that has gone away as idle, unless a flash waits on its power cycle', () => {
		expect(blocked({ link: 'down', shotInProgress: true }, { shotOpen: true })).toBe(false);
		expect(blocked({ link: 'down' }, { flash: true })).toBe(true);
		expect(blocked({}, { flash: true })).toBe(true);
	});
});

describe('a frozen tab', () => {
	function listener(over: { locks?: FakeLocks; busy?: () => boolean; holdReload?: () => boolean } = {}) {
		const { channel, post } = fakeChannel();
		const reload = vi.fn();
		const freeze = vi.fn();
		const onFrozen = vi.fn();
		const onStuck = vi.fn();
		const l = installRestoreListener({
			busy: over.busy ?? (() => false),
			holdReload: over.holdReload,
			onStuck,
			onFrozen,
			reload,
			freeze,
			locks: over.locks,
			channel: () => channel
		});
		return { l, post, reload, freeze, onFrozen, onStuck };
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

	it('offers a reload by hand when the machine keeps it waiting', () => {
		vi.useFakeTimers();
		const t = listener({ busy: () => true });
		t.post({ type: 'done' });
		vi.advanceTimersByTime(STUCK_AFTER_MS - 1);
		expect(t.onStuck).not.toHaveBeenCalled();
		vi.advanceTimersByTime(1);
		expect(t.onStuck).toHaveBeenCalledOnce();
		expect(t.reload).not.toHaveBeenCalled();
	});

	it('ignores a message it sent itself, however late it arrives', () => {
		const t = listener();
		t.post({ type: 'started', from: TAB_ID });
		t.post({ type: 'done', from: TAB_ID });
		expect(t.freeze).not.toHaveBeenCalled();
		expect(t.reload).not.toHaveBeenCalled();
		t.post({ type: 'started', from: 'another-tab' });
		expect(t.freeze).toHaveBeenCalledOnce();
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

describe('announceRestore', () => {
	it('tags every message with this tab', () => {
		const posted: unknown[] = [];
		vi.stubGlobal(
			'BroadcastChannel',
			class {
				postMessage(m: unknown) {
					posted.push(m);
				}
				close() {}
			}
		);
		try {
			announceRestore('started');
			announceRestore('progress');
			announceRestore('done');
		} finally {
			vi.unstubAllGlobals();
		}
		expect(posted).toEqual(['started', 'progress', 'done'].map((type) => ({ type, from: TAB_ID })));
	});
});

describe('shots a frozen tab finished', () => {
	beforeEach(() => localStorage.clear());

	it('are kept in localStorage, once per shot, in a key the freeze leaves alone', () => {
		const shot = { captured: { id: 's1' }, beanId: 'b', dose: 18, live: true };
		expect(stashFrozenShot(shot)).toBe(true);
		expect(stashFrozenShot({ ...shot, captured: { id: 's2' } })).toBe(true);
		expect(stashFrozenShot({ ...shot, dose: 19 })).toBe(true);
		expect(FROZEN_SHOTS_KEY.startsWith('crema.')).toBe(false);
		expect(localStorage.getItem(FROZEN_SHOTS_KEY)).not.toBeNull();
		expect(peekFrozenShots().map((s) => [s.captured.id, s.dose])).toEqual([
			['s2', 18],
			['s1', 19]
		]);
		// Looking doesn't take them: only a recorded shot is forgotten.
		expect(peekFrozenShots()).toHaveLength(2);
		dropFrozenShots(['s1']);
		expect(peekFrozenShots().map((s) => s.captured.id)).toEqual(['s2']);
		dropFrozenShots(['s2']);
		expect(localStorage.getItem(FROZEN_SHOTS_KEY)).toBeNull();
	});

	it('say so when storage refuses them', () => {
		const storage = { getItem: () => null, setItem: () => { throw new DOMException('full', 'QuotaExceededError'); } } as unknown as Storage;
		expect(stashFrozenShot({ captured: {}, beanId: null, dose: null, live: true }, storage)).toBe(false);
	});
});
