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
