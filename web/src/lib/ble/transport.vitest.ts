/**
 * `#lib/ble/transport.vitest` — the auto-reconnect loop's kick contract, on
 * fake timers: a kick (the tab back in view, "Retry now") cuts the backoff /
 * 60 s lurk wait short and resets the ladder to the fast burst; a connected
 * device ignores it; kicks are debounced and never stack a second attempt; a
 * deliberate disconnect stays disconnected. Plus the reconnect timeline the
 * loop records.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	BleDevice,
	GATT_TIMEOUTS,
	GattTimeoutError,
	KICK_DEBOUNCE_MS,
	RECONNECT_LADDER,
	abortableSleep,
	advertisingStatus,
	droppedNotificationsStatus,
	isChooserCancelled,
	reconnectDelayMs
} from './transport.ts';
import { STUCK_LINK_MISSES, stuckLinkHint } from './stuck-link.ts';
import { ReconnectTimelineRecorder } from './reconnect-timeline.ts';

/** `gatt.connect()` is retried 3× with 400 ms between inside one attempt. */
const FAILED_ATTEMPT_MS = 800;

/** A scripted Web Bluetooth device: `ok` decides whether `gatt.connect()` succeeds. */
function fakeDevice() {
	const listeners = new Map<string, () => void>();
	const script = { ok: false, hang: null as Promise<void> | null };
	const gatt = {
		connected: false,
		connect: vi.fn(async () => {
			if (script.hang) await script.hang;
			if (!script.ok) throw new Error('Connection attempt failed');
			gatt.connected = true;
			return gatt;
		}),
		disconnect: vi.fn(() => {
			gatt.connected = false;
		})
	};
	const device = {
		name: 'DE1',
		id: 'dev-1',
		gatt,
		addEventListener: (type: string, fn: () => void) => listeners.set(type, fn)
	};
	return {
		device: device as unknown as BluetoothDevice,
		gatt,
		script,
		/** The link drops under us (not a user disconnect). */
		drop: () => {
			gatt.connected = false;
			listeners.get('gattserverdisconnected')?.();
		}
	};
}

function setup() {
	const fake = fakeDevice();
	const recorder = new ReconnectTimelineRecorder(
		() => Date.now(),
		() => 0
	);
	const ble = new BleDevice(fake.device, recorder, () => Date.now());
	ble.setReconnectTimeline('DE1');
	/** Connect attempts across every episode. */
	const attempts = () => recorder.timelines.reduce((n, t) => n + t.attempts, 0);
	return { ...fake, recorder, ble, attempts };
}

/** The whole fast burst plus its failed attempts — ends inside the lurk wait. */
const BURST_MS =
	Array.from({ length: RECONNECT_LADDER.maxAttempts }, (_, i) => reconnectDelayMs(i + 1)).reduce(
		(a, b) => a + b,
		0
	) +
	RECONNECT_LADDER.maxAttempts * FAILED_ATTEMPT_MS +
	1_000;

beforeEach(() => {
	vi.useFakeTimers();
});
afterEach(() => {
	vi.useRealTimers();
});

describe('BleDevice reconnect kick', () => {
	it('a kick cuts the lurk wait short and resets the ladder to the fast burst', async () => {
		const t = setup();
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		expect(t.attempts()).toBe(RECONNECT_LADDER.maxAttempts);

		// 10 s into the 60 s lurk: kick → an attempt right now.
		await vi.advanceTimersByTimeAsync(10_000);
		expect(t.attempts()).toBe(RECONNECT_LADDER.maxAttempts);
		expect(t.ble.kick('foreground')).toBe('kicked');
		await vi.advanceTimersByTimeAsync(0);
		expect(t.attempts()).toBe(RECONNECT_LADDER.maxAttempts + 1);

		// It fails; the next try is 500 ms later, not 60 s.
		await vi.advanceTimersByTimeAsync(FAILED_ATTEMPT_MS + reconnectDelayMs(1) - 1);
		expect(t.attempts()).toBe(RECONNECT_LADDER.maxAttempts + 1);
		await vi.advanceTimersByTimeAsync(2);
		expect(t.attempts()).toBe(RECONNECT_LADDER.maxAttempts + 2);
		t.ble.disconnect();
	});

	it('a kicked attempt that succeeds recovers the link and records a foreground episode', async () => {
		const t = setup();
		const reconnected = vi.fn();
		t.ble.onReconnected(reconnected);
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		t.script.ok = true;
		expect(t.ble.kick('foreground')).toBe('kicked');
		await vi.advanceTimersByTimeAsync(0);
		expect(t.ble.connectionState).toBe('connected');
		expect(reconnected).toHaveBeenCalledOnce();

		const [fg, drop] = t.recorder.timelines;
		expect(fg.trigger).toBe('foreground');
		expect(fg.outcome).toBe('READY');
		expect(fg.attempts).toBe(1);
		expect(drop.trigger).toBe('drop');
		expect(drop.outcome).toBe('superseded');
		expect(drop.failures).toBe(RECONNECT_LADDER.maxAttempts);
	});

	it('a kick while connected is a no-op', async () => {
		const t = setup();
		t.script.ok = true;
		await t.ble.connectGatt();
		expect(t.ble.kick('foreground')).toBe('ignored-connected');
		expect(t.gatt.connect).toHaveBeenCalledOnce();
	});

	it('rapid kicks are debounced', async () => {
		const t = setup();
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		expect(t.ble.kick('foreground')).toBe('kicked');
		await vi.advanceTimersByTimeAsync(200);
		expect(t.ble.kick('foreground')).toBe('debounced');
		expect(t.ble.kick('user-retry')).toBe('debounced');
		await vi.advanceTimersByTimeAsync(KICK_DEBOUNCE_MS);
		expect(t.ble.kick('user-retry')).not.toBe('debounced');
		t.ble.disconnect();
	});

	it('a kick mid-attempt never starts a second attempt, but resets the burst', async () => {
		const t = setup();
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		// Let the next lurk attempt start and hang inside gatt.connect().
		let release!: () => void;
		t.script.hang = new Promise<void>((r) => (release = r));
		await vi.advanceTimersByTimeAsync(RECONNECT_LADDER.lurkDelayMs);
		const calls = t.gatt.connect.mock.calls.length;
		const attempts = t.attempts();
		expect(t.ble.kick('foreground')).toBe('reset-pending');
		await vi.advanceTimersByTimeAsync(0);
		expect(t.gatt.connect.mock.calls.length).toBe(calls);

		// The attempt fails; the reset makes the next try 500 ms out, not 60 s.
		t.script.hang = null;
		release();
		await vi.advanceTimersByTimeAsync(FAILED_ATTEMPT_MS + reconnectDelayMs(1) + 1);
		expect(t.attempts()).toBe(attempts + 1);
		t.ble.disconnect();
	});

	it('a deliberate disconnect stays disconnected', async () => {
		const t = setup();
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		t.ble.disconnect();
		const calls = t.gatt.connect.mock.calls.length;
		expect(t.ble.kick('foreground')).toBe('ignored-idle');
		await vi.advanceTimersByTimeAsync(10 * RECONNECT_LADDER.lurkDelayMs);
		expect(t.gatt.connect.mock.calls.length).toBe(calls);
		expect(t.recorder.timelines[0].outcome).toBe('cancelled');
	});

	it('a disconnect between connect retries starts no further connect (audit r10 L-1)', async () => {
		const t = setup();
		t.drop();
		// Into the first attempt: its gatt.connect() fails, then a 400 ms retry wait.
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1) + 100);
		expect(t.gatt.connect).toHaveBeenCalledTimes(1);
		t.ble.disconnect();
		expect(t.gatt.disconnect).toHaveBeenCalledTimes(1);
		// A replacement device on the same DE1 connects the shared GATT.
		t.script.ok = true;
		t.gatt.connected = true;
		await vi.advanceTimersByTimeAsync(10_000);
		expect(t.gatt.connect).toHaveBeenCalledTimes(1);
		expect(t.gatt.disconnect).toHaveBeenCalledTimes(1); // the shared link stays up
		expect(t.gatt.connected).toBe(true);
	});

	it('a disconnected device feeds nothing and never takes a shared GATT as its own (audit r11)', async () => {
		const t = setup();
		const sink = vi.fn();
		t.ble.setSink(sink);
		const notify = (t.ble as unknown as { onValueChanged(e: Event): void }).onValueChanged;
		const event = {
			target: { uuid: 'A00D', value: new DataView(new Uint8Array([1, 2]).buffer) }
		} as unknown as Event;
		notify(event);
		expect(sink).toHaveBeenCalledTimes(1);
		t.ble.disconnect();
		notify(event); // a link that connected anyway, or a shared characteristic
		expect(sink).toHaveBeenCalledTimes(1);
		// A replacement on the same DE1 connected the shared GATT.
		t.gatt.connected = true;
		await expect(t.ble.connectGatt()).rejects.toThrow('Device was disconnected');
		// Every public GATT operation refuses, and the sink can't be re-armed (audit r12).
		await expect(t.ble.readCharacteristic('a000', 'a00d')).rejects.toThrow('Device was disconnected');
		await expect(t.ble.write('a000', 'a002', new Uint8Array([1]))).rejects.toThrow('Device was disconnected');
		await expect(t.ble.startNotifications('a000', 'a00d')).rejects.toThrow('Device was disconnected');
		const late = vi.fn();
		t.ble.setSink(late);
		notify(event);
		expect(late).not.toHaveBeenCalled();
	});

	it('the first outage is announced even when a kick beats the first backoff', async () => {
		const t = setup();
		const announced = vi.fn();
		t.ble.onReconnectAttempt(announced);
		t.drop();
		expect(t.ble.kick('foreground')).toBe('kicked');
		await vi.advanceTimersByTimeAsync(0);
		expect(announced).toHaveBeenCalledWith(1);
		t.ble.disconnect();
	});
});

/**
 * A device that can watch advertisements (`watchAdvertisements` stops when its
 * signal aborts) and has one DE1 service, for the lurk-tier and replay tests.
 */
function watchingSetup() {
	const target = new EventTarget();
	const script = { ok: false };
	const characteristic = Object.assign(new EventTarget(), {
		uuid: 'a00d',
		startNotifications: vi.fn(async () => characteristic),
		writeValueWithResponse: vi.fn(async (_value: ArrayBuffer) => undefined)
	});
	const service = { getCharacteristic: vi.fn(async () => characteristic) };
	const gatt = {
		connected: false,
		connect: vi.fn(async () => {
			if (!script.ok) throw new Error('Connection attempt failed');
			gatt.connected = true;
			return gatt;
		}),
		// Like the browser: disconnecting a live link fires gattserverdisconnected.
		disconnect: vi.fn(() => {
			const wasConnected = gatt.connected;
			gatt.connected = false;
			if (wasConnected) target.dispatchEvent(new Event('gattserverdisconnected'));
		}),
		getPrimaryService: vi.fn(async () => service),
		connectAndDiscover: undefined as ((services: string[]) => Promise<unknown>) | undefined
	};
	const device = Object.assign(target, {
		name: 'DE1',
		id: 'dev-1',
		gatt,
		watchingAdvertisements: false,
		watchAdvertisements: vi.fn(async (opts?: { signal?: AbortSignal }) => {
			device.watchingAdvertisements = true;
			opts?.signal?.addEventListener('abort', () => (device.watchingAdvertisements = false));
		})
	});
	const recorder = new ReconnectTimelineRecorder(
		() => Date.now(),
		() => 0
	);
	const ble = new BleDevice(device as unknown as BluetoothDevice, recorder, () => Date.now());
	return {
		device,
		gatt,
		script,
		ble,
		characteristic,
		drop: () => {
			gatt.connected = false;
			target.dispatchEvent(new Event('gattserverdisconnected'));
		},
		advertise: (rssi: number) =>
			target.dispatchEvent(Object.assign(new Event('advertisementreceived'), { rssi }))
	};
}

/** Connect calls one failed attempt makes (3 tries). */
const TRIES_PER_ATTEMPT = 3;

describe('BleDevice lurk tier with advertisement watching', () => {
	it('a quiet watched cycle skips the blind connect; every Nth still tries', async () => {
		const t = watchingSetup();
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		const afterBurst = t.gatt.connect.mock.calls.length;
		expect(afterBurst).toBe(RECONNECT_LADDER.maxAttempts * TRIES_PER_ATTEMPT);
		await vi.advanceTimersByTimeAsync(
			(RECONNECT_LADDER.lurkBlindEvery - 1) * RECONNECT_LADDER.lurkDelayMs
		);
		expect(t.gatt.connect.mock.calls.length).toBe(afterBurst);
		await vi.advanceTimersByTimeAsync(RECONNECT_LADDER.lurkDelayMs + FAILED_ATTEMPT_MS);
		expect(t.gatt.connect.mock.calls.length).toBe(afterBurst + TRIES_PER_ATTEMPT);
		t.ble.disconnect();
	});

	it('an advertisement ends the lurk wait at once and reports its RSSI', async () => {
		const t = watchingSetup();
		const rssi = vi.fn();
		t.ble.onAdvertisement(rssi);
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS + 5_000);
		expect(t.device.watchingAdvertisements).toBe(true);
		t.script.ok = true;
		t.advertise(-61);
		await vi.advanceTimersByTimeAsync(0);
		expect(rssi).toHaveBeenCalledWith(-61);
		expect(t.ble.connectionState).toBe('connected');
		expect(t.device.watchingAdvertisements).toBe(false);
	});

	it('a cycle the browser stopped watching (page hidden) still tries blind', async () => {
		const t = watchingSetup();
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		const afterBurst = t.gatt.connect.mock.calls.length;
		t.device.watchingAdvertisements = false;
		await vi.advanceTimersByTimeAsync(RECONNECT_LADDER.lurkDelayMs + FAILED_ATTEMPT_MS);
		expect(t.gatt.connect.mock.calls.length).toBe(afterBurst + TRIES_PER_ATTEMPT);
		t.ble.disconnect();
	});

	it("reconnects through Beacio's connectAndDiscover with the subscribed services", async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		await t.ble.startNotifications('a000', 'a00d');
		const fast = vi.fn(async () => {
			t.gatt.connected = true;
		});
		t.gatt.connectAndDiscover = fast;
		t.drop();
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1));
		expect(fast).toHaveBeenCalledWith(['a000']);
		expect(t.ble.connectionState).toBe('connected');
		expect(t.characteristic.startNotifications).toHaveBeenCalledTimes(2);
		t.ble.disconnect();
	});
});

describe('BleDevice GATT time limits', () => {
	it('a hung connect is abandoned after the limit and not retried', async () => {
		const t = watchingSetup();
		t.gatt.connect.mockImplementation(() => new Promise(() => undefined));
		const result = t.ble.connectGatt().catch((e: unknown) => e);
		await vi.advanceTimersByTimeAsync(GATT_TIMEOUTS.connectMs);
		expect(await result).toBeInstanceOf(GattTimeoutError);
		expect(t.gatt.connect).toHaveBeenCalledOnce();
		expect(t.gatt.disconnect).toHaveBeenCalledOnce();
		expect(t.ble.connectionState).toBe('failed');
		t.ble.disconnect();
	});

	it('a hung write frees the queue and drops the link into the reconnect loop', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		t.characteristic.writeValueWithResponse.mockImplementationOnce(
			() => new Promise(() => undefined)
		);
		const hung = t.ble.write('a000', 'a00d', new Uint8Array([1])).catch((e: unknown) => e);
		const next = t.ble.write('a000', 'a00d', new Uint8Array([2])).catch((e: unknown) => e);
		await vi.advanceTimersByTimeAsync(GATT_TIMEOUTS.operationMs);
		expect(await hung).toBeInstanceOf(GattTimeoutError);
		expect(t.gatt.disconnect).toHaveBeenCalledOnce();
		expect(t.ble.connectionState).toBe('reconnecting');
		// The queued write ran instead of waiting forever behind the hung one.
		await next;
		expect(t.characteristic.writeValueWithResponse).toHaveBeenCalledTimes(2);
		t.ble.disconnect();
	});

	it('an operation is timed from when it starts, not when it was queued', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		let finishFirst!: () => void;
		t.characteristic.writeValueWithResponse.mockImplementationOnce(
			() => new Promise<undefined>((r) => (finishFirst = () => r(undefined)))
		);
		const first = t.ble.write('a000', 'a00d', new Uint8Array([1]));
		const second = t.ble.write('a000', 'a00d', new Uint8Array([2]));
		await vi.advanceTimersByTimeAsync(GATT_TIMEOUTS.operationMs - 1_000);
		finishFirst();
		await first;
		// The second write started ~9 s after it was queued; it still gets its full limit.
		await vi.advanceTimersByTimeAsync(0);
		await expect(second).resolves.toBeUndefined();
		expect(t.gatt.disconnect).not.toHaveBeenCalled();
		t.ble.disconnect();
	});
});

describe('BleDevice reconnect hold (hollow links, #167 follow-up)', () => {
	it('a drop while the owner holds the link starts no loop until release, then exactly one', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		t.ble.holdReconnect();
		t.drop();
		expect(t.ble.connectionState).toBe('reconnecting');
		await vi.advanceTimersByTimeAsync(5_000);
		expect(t.gatt.connect).toHaveBeenCalledTimes(1); // only the first connect
		expect(t.ble.releaseReconnect(true)).toBe(false); // the link is gone
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1));
		expect(t.gatt.connect).toHaveBeenCalledTimes(2);
		expect(t.ble.connectionState).toBe('connected');
		t.ble.disconnect();
	});

	it("a read that times out mid-program drops the link but doesn't race the program with a loop", async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		t.ble.holdReconnect();
		t.characteristic.writeValueWithResponse.mockImplementationOnce(
			() => new Promise(() => undefined)
		);
		const hung = t.ble.write('a000', 'a00d', new Uint8Array([1])).catch((e: unknown) => e);
		await vi.advanceTimersByTimeAsync(GATT_TIMEOUTS.operationMs);
		expect(await hung).toBeInstanceOf(GattTimeoutError);
		expect(t.gatt.disconnect).toHaveBeenCalledOnce();
		await vi.advanceTimersByTimeAsync(5_000);
		expect(t.gatt.connect).toHaveBeenCalledTimes(1);
		t.ble.releaseReconnect(false);
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1));
		expect(t.gatt.connect).toHaveBeenCalledTimes(2);
		t.ble.disconnect();
	});

	it('a healthy release reports the link up and starts nothing', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		t.ble.holdReconnect();
		expect(t.ble.releaseReconnect(true)).toBe(true);
		await vi.advanceTimersByTimeAsync(5_000);
		expect(t.gatt.connect).toHaveBeenCalledTimes(1);
		expect(t.ble.connectionState).toBe('connected');
		t.ble.disconnect();
	});

	it('a hollow first connect is handed to the loop from attempt 1', async () => {
		const t = watchingSetup();
		const attempts: number[] = [];
		t.ble.onReconnectAttempt((n) => attempts.push(n));
		t.script.ok = true;
		await t.ble.connectGatt();
		t.ble.holdReconnect();
		expect(t.ble.releaseReconnect(false)).toBe(false);
		expect(t.gatt.disconnect).toHaveBeenCalledOnce();
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1));
		expect(attempts).toEqual([1]);
		expect(t.gatt.connect).toHaveBeenCalledTimes(2);
		t.ble.disconnect();
	});

	it('a link that keeps coming up hollow keeps its place on the ladder and reaches the stuck-link hint', async () => {
		const t = watchingSetup();
		const attempts: number[] = [];
		t.ble.onReconnectAttempt((n) => attempts.push(n));
		// Every recovered link fails its re-seed: hold, then judge it hollow.
		t.ble.onReconnected(() => {
			t.ble.holdReconnect();
			void Promise.resolve().then(() => t.ble.releaseReconnect(false));
		});
		t.script.ok = true;
		await t.ble.connectGatt();
		t.drop();
		// attempt 1 after 500 ms, 2 after 1 s more, 3 after 2 s more …
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1));
		expect(attempts).toEqual([1]);
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(2) - 1);
		expect(attempts).toEqual([1]); // backed off, not retried at once
		await vi.advanceTimersByTimeAsync(1);
		expect(attempts).toEqual([1, 2]);
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(3));
		expect(attempts).toEqual([1, 2, 3]);
		// The manager's hint rule: attempt N means N-1 misses.
		expect(stuckLinkHint(true, attempts[attempts.length - 1] - 1)).not.toBeNull();
		expect(STUCK_LINK_MISSES).toBe(2);
		// … and the lurk tier after the burst, rather than attempt 1 forever.
		for (let n = 4; n <= RECONNECT_LADDER.maxAttempts; n++) {
			await vi.advanceTimersByTimeAsync(reconnectDelayMs(n));
		}
		expect(attempts[attempts.length - 1]).toBe(RECONNECT_LADDER.maxAttempts);
		t.ble.disconnect();
	});

	it('a kick still resets a hollow streak to the fast burst', async () => {
		const t = watchingSetup();
		const attempts: number[] = [];
		t.ble.onReconnectAttempt((n) => attempts.push(n));
		let hollow = true;
		t.ble.onReconnected(() => {
			t.ble.holdReconnect();
			void Promise.resolve().then(() => t.ble.releaseReconnect(!hollow));
		});
		t.script.ok = true;
		await t.ble.connectGatt();
		t.drop();
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(1) + reconnectDelayMs(2));
		expect(attempts).toEqual([1, 2]);
		hollow = false;
		t.script.ok = false; // next attempt fails, so the loop sits in its backoff
		await vi.advanceTimersByTimeAsync(reconnectDelayMs(3) + FAILED_ATTEMPT_MS);
		expect(t.ble.kick('user-retry')).toBe('kicked');
		t.script.ok = true;
		await vi.advanceTimersByTimeAsync(0);
		expect(t.ble.connectionState).toBe('connected');
		t.ble.disconnect();
	});
});

describe('BleDevice advertisement watch', () => {
	it('a watch that never settles cannot hold the loop: the wait still ends and kicks still land', async () => {
		const t = watchingSetup();
		t.device.watchAdvertisements.mockImplementation(() => new Promise(() => undefined));
		t.drop();
		await vi.advanceTimersByTimeAsync(BURST_MS);
		const before = t.gatt.connect.mock.calls.length;
		expect(t.ble.kick('foreground')).toBe('kicked');
		await vi.advanceTimersByTimeAsync(0);
		expect(t.gatt.connect.mock.calls.length).toBeGreaterThan(before);
		t.ble.disconnect();
	});
});

describe('shared status lines', () => {
	it('format advertisements and dropped notifications the same for every device', () => {
		expect(advertisingStatus('DE1', -61)).toBe('DE1 is advertising (-61 dBm) — reconnecting');
		expect(advertisingStatus('Scale', null)).toBe('Scale is advertising — reconnecting');
		expect(
			droppedNotificationsStatus('DE1', '0000a00d-0000-1000-8000-00805f9b34fb', 1)
		).toBe('DE1: the browser dropped 1 notification (A00D)');
		expect(droppedNotificationsStatus('Scale', 'custom-uuid', 3)).toBe(
			'Scale: the browser dropped 3 notifications (custom-uuid)'
		);
	});
});

describe('isChooserCancelled', () => {
	it('is true only for a dismissed chooser', () => {
		const err = (name: string, message: string) => Object.assign(new Error(message), { name });
		expect(isChooserCancelled(err('NotFoundError', 'User cancelled the requestDevice() chooser.'))).toBe(true);
		expect(isChooserCancelled(err('NotFoundError', 'Bluetooth adapter not available.'))).toBe(false);
		expect(isChooserCancelled(err('NetworkError', 'cancelled'))).toBe(false);
		expect(isChooserCancelled(null)).toBe(false);
	});
});

describe('BleDevice notification overflow', () => {
	it('reports the first drop at once, then one summed report per window', async () => {
		const t = watchingSetup();
		const dropped = vi.fn();
		t.ble.onNotificationOverflow(dropped);
		const overflow = (t.ble as unknown as { onOverflow(e: Event): void }).onOverflow;
		const event = (evictedCount: number) =>
			({ target: { uuid: 'A00D' }, detail: { evictedCount } }) as unknown as Event;
		overflow(event(3));
		expect(dropped).toHaveBeenLastCalledWith('a00d', 3);
		overflow(event(2));
		overflow(event(4));
		expect(dropped).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(10_000);
		expect(dropped).toHaveBeenLastCalledWith('a00d', 6);
		expect(dropped).toHaveBeenCalledTimes(2);
	});
});

describe('abortableSleep', () => {
	it('resolves after the delay, or at once when aborted', async () => {
		const done = vi.fn();
		void abortableSleep(60_000, new AbortController().signal).then(done);
		await vi.advanceTimersByTimeAsync(59_999);
		expect(done).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(1);
		expect(done).toHaveBeenCalledOnce();

		const abort = new AbortController();
		const early = vi.fn();
		void abortableSleep(60_000, abort.signal).then(early);
		abort.abort();
		await vi.advanceTimersByTimeAsync(0);
		expect(early).toHaveBeenCalledOnce();
	});
});

describe('BleDevice write and writeIf share one body (V3 #10)', () => {
	const bytes = (v: unknown) => Array.from(new Uint8Array(v as ArrayBuffer));

	it('writeIf resolves an uncached characteristic once and writes like write()', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		const settled = vi.fn();
		await expect(t.ble.writeIf('a000', 'a00d', new Uint8Array([7, 8]), () => true, settled)).resolves.toBe(true);
		await t.ble.write('a000', 'a00d', new Uint8Array([9]));
		expect(settled).toHaveBeenCalledWith(true);
		const writes = t.characteristic.writeValueWithResponse.mock.calls.map((c) => bytes(c[0]));
		expect(writes).toEqual([[7, 8], [9]]);
		// Looked up by the first write, reused by the second.
		expect(t.gatt.getPrimaryService).toHaveBeenCalledOnce();
		t.ble.disconnect();
	});

	it('a refused guard sends nothing', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		await expect(t.ble.writeIf('a000', 'a00d', new Uint8Array([1]), () => false)).resolves.toBe(false);
		expect(t.characteristic.writeValueWithResponse).not.toHaveBeenCalled();
		t.ble.disconnect();
	});

	it('writeIf is bounded like write(): a hung write times out, settles false and drops the link', async () => {
		const t = watchingSetup();
		t.script.ok = true;
		await t.ble.connectGatt();
		t.characteristic.writeValueWithResponse.mockImplementationOnce(() => new Promise(() => undefined));
		const settled = vi.fn();
		const hung = t.ble.writeIf('a000', 'a00d', new Uint8Array([1]), () => true, settled).catch((e: unknown) => e);
		await vi.advanceTimersByTimeAsync(GATT_TIMEOUTS.operationMs);
		expect(await hung).toBeInstanceOf(GattTimeoutError);
		expect(settled).toHaveBeenCalledWith(false);
		expect(t.ble.connectionState).toBe('reconnecting');
		t.ble.disconnect();
	});

	it('writeIf refuses a disconnected device like write()', async () => {
		const t = setup();
		t.ble.disconnect();
		const settled = vi.fn();
		await expect(t.ble.writeIf('a000', 'a002', new Uint8Array([1]), () => true, settled)).rejects.toThrow(
			'Device was disconnected'
		);
		expect(settled).toHaveBeenCalledWith(false);
	});
});
