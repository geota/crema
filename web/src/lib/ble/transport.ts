/**
 * `$lib/ble/transport` — Crema's small abstraction over the Web Bluetooth
 * central stack. The web mirror of the Android shell's `BleTransport`.
 *
 * ## How this differs from the Android model
 *
 * The Android shell scans (`BleScanner`) for an advertisement, gets a device
 * handle, then `connect`s. **Web Bluetooth has no background scan**:
 * `navigator.bluetooth.requestDevice()` pops a *browser-chrome* device chooser
 * — discovery and selection happen in the browser, gated behind a user
 * gesture. So there is no separate scanner here: a "connect" is one
 * `requestDevice()` + `gatt.connect()`. The name-prefix match the Android
 * `BleScanner` ran against advertisements is instead expressed as a
 * `requestDevice` `filters` clause.
 *
 * Notifications: Web Bluetooth delivers them as `characteristicvaluechanged`
 * DOM events. There is no `Flow` to `merge`; instead this transport attaches
 * **one shared listener** and dispatches each event to a single per-device
 * sink, which preserves arrival order across characteristics — the web
 * equivalent of the Android managers merging characteristic flows. Each
 * notification is stamped with `performance.now()` at delivery, matching the
 * Android transport's `elapsedRealtime()` arrival stamp and the
 * `performance.now()` clock the core expects.
 *
 * `ConnState` mirrors the Android transport's coarse flat enum.
 */

/** Coarse connection state — connected-or-not plus the failure case. */
export type ConnState = 'disconnected' | 'connecting' | 'connected' | 'failed';

/** One characteristic-value notification, arrival-stamped at delivery. */
export interface BleNotification {
	/** Source characteristic UUID (lowercase) — lets a sink route by UUID. */
	readonly characteristic: string;
	/** The raw notification payload. */
	readonly data: Uint8Array;
	/** A `performance.now()` millisecond timestamp, captured at delivery. */
	readonly atMs: number;
}

/** A sink for a device's notifications — invoked in strict arrival order. */
export type NotificationSink = (notification: BleNotification) => void;

/**
 * A live connection to one BLE device, over Web Bluetooth GATT.
 *
 * Created by {@link requestDevice}. Owns its `BluetoothDevice`, GATT server,
 * and the shared `characteristicvaluechanged` listener. `connectionState`
 * tracks the link; `disconnect()` is idempotent.
 */
export class BleDevice {
	/** Coarse link state — read by the managers to drive their UI state. */
	connectionState: ConnState = 'disconnected';

	/** The single sink every notification on this device is dispatched to. */
	private sink: NotificationSink | null = null;

	/** Characteristics subscribed via {@link startNotifications}, by UUID. */
	private readonly characteristics = new Map<string, BluetoothRemoteGATTCharacteristic>();

	/** The bound `characteristicvaluechanged` handler — one per device. */
	private readonly onValueChanged = (event: Event): void => {
		const target = event.target as BluetoothRemoteGATTCharacteristic;
		const value = target.value;
		if (value === null || value === undefined || this.sink === null) {
			return;
		}
		// Stamp at delivery, before the sink runs — the same monotonic clock
		// the core expects, mirroring the Android transport's arrival stamp.
		const atMs = performance.now();
		this.sink({
			characteristic: target.uuid.toLowerCase(),
			data: new Uint8Array(value.buffer.slice(0)),
			atMs
		});
	};

	private readonly onGattDisconnected = (): void => {
		this.connectionState = 'disconnected';
		this.onDisconnectedListeners.forEach((listener) => listener());
	};

	private readonly onDisconnectedListeners = new Set<() => void>();

	constructor(
		/** The underlying Web Bluetooth device. */
		readonly device: BluetoothDevice
	) {
		device.addEventListener('gattserverdisconnected', this.onGattDisconnected);
	}

	/** The device's advertised / chosen name, for logs and the scale codec. */
	get name(): string {
		return this.device.name ?? 'Unknown device';
	}

	/**
	 * Register the single sink every notification on this device dispatches
	 * to. One sink per device preserves cross-characteristic arrival order —
	 * the web equivalent of the Android managers merging characteristic flows.
	 */
	setSink(sink: NotificationSink): void {
		this.sink = sink;
	}

	/** Register a callback for an unexpected GATT disconnect. */
	onDisconnected(listener: () => void): void {
		this.onDisconnectedListeners.add(listener);
	}

	/**
	 * Connect the GATT server. Resolves once connected; rejects on failure.
	 * The `requestDevice` chooser already happened in {@link requestDevice}.
	 */
	async connectGatt(): Promise<void> {
		this.connectionState = 'connecting';
		try {
			const gatt = this.device.gatt;
			if (gatt === undefined) {
				throw new Error('Device exposes no GATT server');
			}
			if (!gatt.connected) {
				await gatt.connect();
			}
			this.connectionState = 'connected';
		} catch (error) {
			this.connectionState = 'failed';
			throw error;
		}
	}

	/** Resolve a characteristic of `serviceUuid`, caching it for later writes. */
	async getCharacteristic(
		serviceUuid: string,
		characteristicUuid: string
	): Promise<BluetoothRemoteGATTCharacteristic> {
		const gatt = this.device.gatt;
		if (gatt === undefined || !gatt.connected) {
			throw new Error('GATT not connected');
		}
		const service = await gatt.getPrimaryService(serviceUuid);
		const characteristic = await service.getCharacteristic(characteristicUuid);
		this.characteristics.set(characteristicUuid.toLowerCase(), characteristic);
		return characteristic;
	}

	/**
	 * Subscribe to notifications on a characteristic. Web Bluetooth enables the
	 * CCCD as a side effect of `startNotifications()`. Every value-change
	 * routes to the device's single sink — see {@link setSink}.
	 */
	async startNotifications(serviceUuid: string, characteristicUuid: string): Promise<void> {
		const characteristic = await this.getCharacteristic(serviceUuid, characteristicUuid);
		characteristic.addEventListener('characteristicvaluechanged', this.onValueChanged);
		await characteristic.startNotifications();
	}

	/**
	 * Write `data` to a characteristic. Used for scale command writes; the
	 * exact bytes come from the core via `Command.WriteScale`.
	 */
	async write(serviceUuid: string, characteristicUuid: string, data: Uint8Array): Promise<void> {
		let characteristic = this.characteristics.get(characteristicUuid.toLowerCase());
		if (characteristic === undefined) {
			characteristic = await this.getCharacteristic(serviceUuid, characteristicUuid);
		}
		// `writeValueWithResponse` is the equivalent of the Android transport's
		// acknowledged write; copy into a fresh buffer for a clean ArrayBuffer.
		await characteristic.writeValueWithResponse(data.slice().buffer);
	}

	/** Disconnect the GATT server and drop the device's listeners. Idempotent. */
	disconnect(): void {
		const gatt = this.device.gatt;
		if (gatt !== undefined && gatt.connected) {
			gatt.disconnect();
		}
		this.connectionState = 'disconnected';
	}
}

/** `true` when the running browser exposes the Web Bluetooth API. */
export function isWebBluetoothSupported(): boolean {
	return typeof navigator !== 'undefined' && navigator.bluetooth !== undefined;
}

/**
 * Pop the browser device chooser and wrap the chosen device.
 *
 * This is the web replacement for the Android `BleScanner` — `requestDevice`
 * both discovers and selects, and **must run inside a user gesture** (a click
 * handler). `filters` carries the name-prefix match; `optionalServices` lists
 * every GATT service the shell will later access (Web Bluetooth blocks access
 * to un-listed services).
 *
 * Rejects if the user dismisses the chooser or the browser lacks Web
 * Bluetooth.
 */
export async function requestDevice(options: RequestDeviceOptions): Promise<BleDevice> {
	if (!isWebBluetoothSupported()) {
		throw new Error('Web Bluetooth is not available in this browser');
	}
	const device = await navigator.bluetooth.requestDevice(options);
	return new BleDevice(device);
}
