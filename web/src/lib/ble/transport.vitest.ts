/**
 * `$lib/ble/transport.vitest` — the auto-reconnect loop's kick contract, on
 * fake timers: a kick (the tab back in view, "Retry now") cuts the backoff /
 * 60 s lurk wait short and resets the ladder to the fast burst; a connected
 * device ignores it; kicks are debounced and never stack a second attempt; a
 * deliberate disconnect stays disconnected. Plus the reconnect timeline the
 * loop records.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	BleDevice,
	KICK_DEBOUNCE_MS,
	RECONNECT_LADDER,
	abortableSleep,
	reconnectDelayMs
} from './transport.ts';
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
		startNotifications: vi.fn(async () => characteristic)
	});
	const service = { getCharacteristic: vi.fn(async () => characteristic) };
	const gatt = {
		connected: false,
		connect: vi.fn(async () => {
			if (!script.ok) throw new Error('Connection attempt failed');
			gatt.connected = true;
			return gatt;
		}),
		disconnect: vi.fn(() => {
			gatt.connected = false;
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
