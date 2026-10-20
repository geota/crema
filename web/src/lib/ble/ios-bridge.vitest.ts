import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	BEACIO_READY_EVENT,
	installIosBridge,
	isBridgedBluetooth,
	resetIosBridgeForTests
} from './ios-bridge';

const IPHONE_UA =
	'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1';

type Nav = Navigator & { beacio?: unknown; bluetooth?: unknown };

/** A loader standing in for `@beacio/core/auto`: maps the extension onto `navigator.bluetooth`. */
function fakeLoad() {
	return vi.fn(async () => {
		Object.defineProperty(navigator, 'bluetooth', { value: {}, configurable: true });
	});
}

function setExtension(present: boolean): void {
	Object.defineProperty(navigator, 'beacio', {
		value: present ? { __beacio: true } : undefined,
		configurable: true
	});
}

function setUserAgent(ua: string): void {
	vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ua);
}

beforeEach(() => {
	resetIosBridgeForTests();
	Reflect.deleteProperty(navigator, 'bluetooth');
	Reflect.deleteProperty(navigator, 'beacio');
	Reflect.deleteProperty(window, 'beacioAutoReconnect');
});

afterEach(() => {
	vi.restoreAllMocks();
	vi.useRealTimers();
});

describe('installIosBridge', () => {
	it('leaves native Web Bluetooth alone and never loads the polyfill', async () => {
		Object.defineProperty(navigator, 'bluetooth', { value: {}, configurable: true });
		setExtension(true);
		const load = fakeLoad();
		expect(await installIosBridge({ load })).toBe(false);
		expect(load).not.toHaveBeenCalled();
		expect(isBridgedBluetooth()).toBe(false);
	});

	it('without the extension: no polyfill, no stub navigator.bluetooth', async () => {
		const load = fakeLoad();
		expect(await installIosBridge({ load })).toBe(false);
		expect(load).not.toHaveBeenCalled();
		expect((navigator as Nav).bluetooth).toBeUndefined();
	});

	it('with the extension: loads once, with its auto-reconnect off', async () => {
		setExtension(true);
		const load = fakeLoad();
		expect(await installIosBridge({ load })).toBe(true);
		expect(await installIosBridge({ load })).toBe(true);
		expect(load).toHaveBeenCalledTimes(1);
		expect(isBridgedBluetooth()).toBe(true);
		expect((window as Window & { beacioAutoReconnect?: unknown }).beacioAutoReconnect).toEqual({
			enabled: false
		});
	});

	it('on iOS: waits for a late extension', async () => {
		setUserAgent(IPHONE_UA);
		const load = fakeLoad();
		const done = installIosBridge({ load, waitMs: 1_000 });
		setExtension(true);
		window.dispatchEvent(new Event(BEACIO_READY_EVENT));
		expect(await done).toBe(true);
		expect(load).toHaveBeenCalledTimes(1);
	});

	it('on iOS: gives up after the wait, but a later extension still installs', async () => {
		vi.useFakeTimers();
		setUserAgent(IPHONE_UA);
		const load = fakeLoad();
		const done = installIosBridge({ load, waitMs: 1_000 });
		await vi.advanceTimersByTimeAsync(1_000);
		expect(await done).toBe(false);
		setExtension(true);
		window.dispatchEvent(new Event(BEACIO_READY_EVENT));
		await vi.waitFor(() => expect(isBridgedBluetooth()).toBe(true));
	});

	it('a failed load reports false and can be retried', async () => {
		setExtension(true);
		const failing = vi.fn(async () => {
			throw new Error('chunk failed');
		});
		expect(await installIosBridge({ load: failing })).toBe(false);
		expect(await installIosBridge({ load: fakeLoad() })).toBe(true);
	});
});
