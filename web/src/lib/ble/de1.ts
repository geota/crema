/**
 * `$lib/ble/de1` — Web Bluetooth manager for a DE1 espresso machine.
 *
 * The web mirror of the Android shell's `De1BleManager`. Responsibilities, and
 * nothing more:
 *  1. `requestDevice()` for a DE1 (name-prefix filter), connect GATT.
 *  2. `startNotifications()` on the StateInfo / ShotSample / WaterLevels
 *     characteristics.
 *  3. Route each `characteristicvaluechanged` to the core under the right
 *     `NotificationSource`, forwarding the resulting `CoreOutput`.
 *
 * Web Bluetooth has no background scan: discovery is the `requestDevice()`
 * chooser, which must run inside a user gesture — `connect()` is therefore
 * called straight from a button handler. All three characteristics share the
 * device's single notification sink, so their notifications reach the core in
 * strict arrival order (the web equivalent of the Android manager merging
 * characteristic flows).
 *
 * The DE1 is read-only in the shell — no write path.
 */

import type { CremaCore, NotificationSource } from '$lib/core';
import { De1Uuids } from './de1-uuids';
import { BleDevice, requestDevice, type ConnState } from './transport';

/** Coarse state of the DE1 connection — mirrors the Android manager's enum. */
export type De1State =
	| 'idle'
	| 'connecting'
	| 'subscribing'
	| 'ready'
	| 'disconnected'
	| 'failed';

/** Callbacks the manager reports up to the orchestrator. */
export interface De1ManagerCallbacks {
	/** A raw `CoreOutput` is ready to be folded into state. */
	onCoreOutput: (output: import('$lib/core').CoreOutput) => void;
	/** A human-readable status line for the UI. */
	onStatus: (line: string) => void;
	/** The coarse connection state advanced. */
	onState: (state: De1State) => void;
}

/**
 * Owns the DE1 connection and its notification observation. One instance per
 * app; `connect()` / `disconnect()` are driven by the orchestrator.
 */
export class De1Manager {
	private device: BleDevice | null = null;

	constructor(
		private readonly core: CremaCore,
		private readonly callbacks: De1ManagerCallbacks
	) {}

	/** The current GATT link state, for the connection card. */
	get connectionState(): ConnState {
		return this.device?.connectionState ?? 'disconnected';
	}

	/**
	 * Discover, select, and connect a DE1.
	 *
	 * Must be called from a user gesture (a click handler) — `requestDevice`
	 * pops the browser chooser, which Web Bluetooth gates behind a gesture.
	 * Filters on the DE1 / BENGLE name prefixes and lists the `A000` service
	 * as optional so its characteristics become accessible.
	 */
	async connect(): Promise<void> {
		this.callbacks.onState('connecting');
		this.callbacks.onStatus('Requesting a DE1…');
		try {
			const device = await requestDevice({
				filters: De1Uuids.NAME_PREFIXES.map((namePrefix) => ({ namePrefix })),
				optionalServices: [De1Uuids.SERVICE]
			});
			this.device = device;
			device.onDisconnected(() => {
				this.callbacks.onState('disconnected');
				this.callbacks.onStatus('DE1 disconnected');
			});

			this.callbacks.onStatus(`Connecting to ${device.name}…`);
			await device.connectGatt();

			// One sink for all three characteristics — preserves arrival order.
			device.setSink((notification) => {
				const source = sourceFor(notification.characteristic);
				if (source === null) {
					return;
				}
				void this.core
					.onNotification(source, notification.data, notification.atMs)
					.then(this.callbacks.onCoreOutput);
			});

			this.callbacks.onState('subscribing');
			this.callbacks.onStatus('Subscribing to StateInfo + ShotSample…');
			// Subscribe in series — Web Bluetooth serialises GATT ops anyway,
			// and the device's shared sink keeps cross-characteristic order.
			await device.startNotifications(De1Uuids.SERVICE, De1Uuids.STATE_INFO);
			await device.startNotifications(De1Uuids.SERVICE, De1Uuids.SHOT_SAMPLE);
			await device.startNotifications(De1Uuids.SERVICE, De1Uuids.WATER_LEVELS);

			this.callbacks.onState('ready');
			this.callbacks.onStatus('Ready — receiving DE1 notifications');
		} catch (error) {
			this.device = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus(`DE1 connection failed: ${describe(error)}`);
		}
	}

	/** Disconnect the DE1 and discard the core's session state. */
	async disconnect(): Promise<void> {
		this.device?.disconnect();
		this.device = null;
		await this.core.reset();
		this.callbacks.onState('disconnected');
		this.callbacks.onStatus('DE1 disconnected');
	}
}

/**
 * Map a DE1 characteristic UUID to its core `NotificationSource`. Returns
 * `null` for an unmapped characteristic, which is dropped.
 */
function sourceFor(characteristicUuid: string): NotificationSource | null {
	switch (characteristicUuid) {
		case De1Uuids.STATE_INFO:
			return 'De1State';
		case De1Uuids.SHOT_SAMPLE:
			return 'De1ShotSample';
		case De1Uuids.WATER_LEVELS:
			return 'De1WaterLevels';
		default:
			return null;
	}
}

/** Best-effort human-readable message from an unknown thrown value. */
function describe(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
