import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	LAST_DEVICES_KEY,
	LaunchReconnect,
	markUserDisconnect,
	readLastDevices,
	rememberDevice,
	type DeviceKind
} from './launch-reconnect';

/** A permitted `BluetoothDevice` stand-in; `watch` adds `watchAdvertisements`. */
function fakeDevice(id: string, name: string | null, watch = false) {
	const target = new EventTarget() as EventTarget & {
		id: string;
		name: string | null;
		watchAdvertisements?: (opts?: { signal?: AbortSignal }) => Promise<void>;
		watchSignal?: AbortSignal;
	};
	target.id = id;
	target.name = name;
	if (watch) {
		target.watchAdvertisements = vi.fn(async (opts?: { signal?: AbortSignal }) => {
			target.watchSignal = opts?.signal;
		});
	}
	return target;
}

type FakeDevice = ReturnType<typeof fakeDevice>;

function fakeBluetooth(devices: FakeDevice[]): Bluetooth {
	return { getDevices: vi.fn(async () => devices) } as unknown as Bluetooth;
}

function advertise(device: FakeDevice): void {
	device.dispatchEvent(new Event('advertisementreceived'));
}

function runner(launch: LaunchReconnect, bluetooth: Bluetooth | undefined, timeoutMs?: number) {
	const connect = vi.fn(async (_kind: DeviceKind, _device: BluetoothDevice) => {});
	const done = launch.run({ bluetooth, isIdle: () => true, connect, timeoutMs });
	return { connect, done };
}

beforeEach(() => {
	localStorage.clear();
});

afterEach(() => {
	vi.useRealTimers();
});

describe('device memory', () => {
	it('remembers each connect under one versioned key', () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		rememberDevice('scale', { id: 'sc-id', name: 'BOOKOO_SC' });
		expect(JSON.parse(localStorage.getItem(LAST_DEVICES_KEY)!)).toEqual({
			de1: { id: 'de1-id', name: 'DE1', auto: true },
			scale: { id: 'sc-id', name: 'BOOKOO_SC', auto: true }
		});
	});

	it('a deliberate disconnect keeps the identity but turns auto off; a connect turns it back on', () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		markUserDisconnect('de1');
		expect(readLastDevices().de1).toEqual({ id: 'de1-id', name: 'DE1', auto: false });
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		expect(readLastDevices().de1?.auto).toBe(true);
	});

	it('reads a malformed entry as nothing remembered', () => {
		localStorage.setItem(LAST_DEVICES_KEY, JSON.stringify({ de1: { id: 3 }, scale: 'x' }));
		expect(readLastDevices()).toEqual({ de1: null, scale: null });
	});
});

describe('LaunchReconnect', () => {
	it('unsupported (no navigator.bluetooth, or no getDevices) is a silent no-op', async () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const a = runner(new LaunchReconnect(), undefined);
		expect(await a.done).toEqual({ de1: 'unsupported', scale: 'unsupported' });
		const b = runner(new LaunchReconnect(), {} as Bluetooth);
		expect(await b.done).toEqual({ de1: 'unsupported', scale: 'unsupported' });
		expect(a.connect).not.toHaveBeenCalled();
		expect(b.connect).not.toHaveBeenCalled();
	});

	it('without watchAdvertisements: connects the remembered device directly', async () => {
		const de1 = fakeDevice('de1-id', 'DE1');
		const scale = fakeDevice('sc-id', 'BOOKOO_SC');
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		rememberDevice('scale', { id: 'sc-id', name: 'BOOKOO_SC' });
		const r = runner(new LaunchReconnect(), fakeBluetooth([scale, de1]));
		expect(await r.done).toEqual({ de1: 'connecting', scale: 'connecting' });
		expect(r.connect).toHaveBeenCalledWith('de1', de1);
		expect(r.connect).toHaveBeenCalledWith('scale', scale);
	});

	it('falls back to the name when the id changed', async () => {
		const de1 = fakeDevice('new-id', 'DE1');
		rememberDevice('de1', { id: 'old-id', name: 'DE1' });
		const r = runner(new LaunchReconnect(), fakeBluetooth([de1]));
		expect((await r.done).de1).toBe('connecting');
		expect(r.connect).toHaveBeenCalledWith('de1', de1);
	});

	it('nothing found (not permitted, nothing remembered) does nothing', async () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const r = runner(new LaunchReconnect(), fakeBluetooth([fakeDevice('other', 'Other')]));
		expect(await r.done).toEqual({ de1: 'not-found', scale: 'not-remembered' });
		expect(r.connect).not.toHaveBeenCalled();
	});

	it('with watchAdvertisements: waits for the first advertisement, then connects', async () => {
		const de1 = fakeDevice('de1-id', 'DE1', true);
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const launch = new LaunchReconnect();
		const r = runner(launch, fakeBluetooth([de1]));
		await vi.waitFor(() => expect(de1.watchAdvertisements).toHaveBeenCalled());
		expect(launch.isPending('de1')).toBe(true);
		expect(r.connect).not.toHaveBeenCalled();
		advertise(de1);
		expect((await r.done).de1).toBe('connecting');
		expect(r.connect).toHaveBeenCalledWith('de1', de1);
		// The watch is stopped once seen.
		expect(de1.watchSignal?.aborted).toBe(true);
		expect(launch.isPending('de1')).toBe(false);
	});

	it('with watchAdvertisements: gives up quietly after the timeout', async () => {
		vi.useFakeTimers();
		const de1 = fakeDevice('de1-id', 'DE1', true);
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const r = runner(new LaunchReconnect(), fakeBluetooth([de1]), 30_000);
		await vi.waitFor(() => expect(de1.watchAdvertisements).toHaveBeenCalled());
		await vi.advanceTimersByTimeAsync(30_000);
		expect((await r.done).de1).toBe('not-found');
		advertise(de1);
		expect(r.connect).not.toHaveBeenCalled();
		expect(de1.watchSignal?.aborted).toBe(true);
	});

	it('a user action cancels the pending wait', async () => {
		const de1 = fakeDevice('de1-id', 'DE1', true);
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const launch = new LaunchReconnect();
		const r = runner(launch, fakeBluetooth([de1]));
		await vi.waitFor(() => expect(launch.isPending('de1')).toBe(true));
		await vi.waitFor(() => expect(de1.watchAdvertisements).toHaveBeenCalled());
		launch.cancel('de1');
		expect((await r.done).de1).toBe('aborted');
		advertise(de1);
		expect(r.connect).not.toHaveBeenCalled();
	});

	it('a deliberate disconnect is skipped next launch', async () => {
		const de1 = fakeDevice('de1-id', 'DE1');
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		markUserDisconnect('de1');
		const r = runner(new LaunchReconnect(), fakeBluetooth([de1]));
		expect((await r.done).de1).toBe('not-remembered');
		expect(r.connect).not.toHaveBeenCalled();
	});

	it('skips a device that is already connecting or connected', async () => {
		const de1 = fakeDevice('de1-id', 'DE1');
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const connect = vi.fn(async () => {});
		await new LaunchReconnect().run({
			bluetooth: fakeBluetooth([de1]),
			isIdle: () => false,
			connect
		});
		expect(connect).not.toHaveBeenCalled();
	});
});

describe('CremaApp wiring', () => {
	// Imported lazily so the module-level tests above stay light.
	async function makeApp() {
		const { CremaApp } = await import('$lib/state/app.svelte');
		const { De1Manager, ScaleManager } = await import('$lib/ble');
		const spies = {
			de1Known: vi.spyOn(De1Manager.prototype, 'connectKnown').mockResolvedValue(),
			de1Connect: vi.spyOn(De1Manager.prototype, 'connect').mockResolvedValue(),
			de1Disconnect: vi.spyOn(De1Manager.prototype, 'disconnect').mockResolvedValue(),
			de1Identity: vi
				.spyOn(De1Manager.prototype, 'identity')
				.mockReturnValue({ id: 'de1-id', name: 'DE1' }),
			scaleKnown: vi.spyOn(ScaleManager.prototype, 'connectKnown').mockResolvedValue(),
			scaleDisconnect: vi.spyOn(ScaleManager.prototype, 'disconnect').mockResolvedValue()
		};
		// Every core call resolves to an empty output — none is exercised here.
		const core = new Proxy({}, { get: () => async () => ({}) });
		const app = new CremaApp(core as never, null);
		return { app, spies };
	}

	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('remembers the DE1 when it reaches ready, and reconnects it next launch', async () => {
		const { app, spies } = await makeApp();
		const callbacks = (app as unknown as { de1: { callbacks: { onState(s: string): void } } }).de1
			.callbacks;
		callbacks.onState('ready');
		expect(readLastDevices().de1).toEqual({ id: 'de1-id', name: 'DE1', auto: true });
		// An unexpected drop leaves it eligible.
		callbacks.onState('reconnecting');
		callbacks.onState('disconnected');
		expect(readLastDevices().de1?.auto).toBe(true);
		const de1 = fakeDevice('de1-id', 'DE1');
		await app.reconnectLastOnLaunch(fakeBluetooth([de1]));
		expect(spies.de1Known).toHaveBeenCalledWith(de1);
	});

	it('Disconnect marks the device so the next launch skips it', async () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		rememberDevice('scale', { id: 'sc-id', name: 'BOOKOO_SC' });
		const { app, spies } = await makeApp();
		await app.disconnectDe1();
		await app.disconnectScale();
		expect(readLastDevices().de1?.auto).toBe(false);
		expect(readLastDevices().scale?.auto).toBe(false);
		await app.reconnectLastOnLaunch(
			fakeBluetooth([fakeDevice('de1-id', 'DE1'), fakeDevice('sc-id', 'BOOKOO_SC')])
		);
		expect(spies.de1Known).not.toHaveBeenCalled();
		expect(spies.scaleKnown).not.toHaveBeenCalled();
	});

	it('a user Connect during the wait cancels it', async () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const { app, spies } = await makeApp();
		const de1 = fakeDevice('de1-id', 'DE1', true);
		const pending = app.reconnectLastOnLaunch(fakeBluetooth([de1]));
		await vi.waitFor(() => expect(de1.watchAdvertisements).toHaveBeenCalled());
		await app.connectDe1();
		await pending;
		advertise(de1);
		expect(spies.de1Connect).toHaveBeenCalledOnce();
		expect(spies.de1Known).not.toHaveBeenCalled();
	});

	it('is a no-op without getDevices', async () => {
		rememberDevice('de1', { id: 'de1-id', name: 'DE1' });
		const { app, spies } = await makeApp();
		await app.reconnectLastOnLaunch(undefined);
		await app.reconnectLastOnLaunch({} as Bluetooth);
		expect(spies.de1Known).not.toHaveBeenCalled();
	});
});
