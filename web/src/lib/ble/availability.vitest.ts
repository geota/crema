import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
	bluetoothAvailable,
	resetBluetoothAvailabilityForTests,
	watchBluetoothAvailability
} from './availability';

function fakeBluetooth(initial: boolean) {
	const target = new EventTarget() as EventTarget & { getAvailability: () => Promise<boolean> };
	target.getAvailability = vi.fn(async () => initial);
	return {
		bluetooth: target as unknown as Bluetooth,
		change(value: boolean) {
			target.dispatchEvent(Object.assign(new Event('availabilitychanged'), { value }));
		}
	};
}

beforeEach(() => {
	resetBluetoothAvailabilityForTests();
});

describe('watchBluetoothAvailability', () => {
	it('is unknown without the API, and stays out of the way', () => {
		const stop = watchBluetoothAvailability(vi.fn(), undefined);
		expect(bluetoothAvailable()).toBeNull();
		stop();
	});

	it('reads the initial answer, then follows availabilitychanged', async () => {
		const fake = fakeBluetooth(true);
		const onChange = vi.fn();
		const stop = watchBluetoothAvailability(onChange, fake.bluetooth);
		await vi.waitFor(() => expect(bluetoothAvailable()).toBe(true));
		fake.change(false);
		expect(bluetoothAvailable()).toBe(false);
		fake.change(false);
		expect(onChange.mock.calls).toEqual([[true], [false]]);
		stop();
		fake.change(true);
		expect(bluetoothAvailable()).toBe(false);
	});
});
