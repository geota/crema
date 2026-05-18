/**
 * `$lib/ble/scale` — Web Bluetooth manager for a Bookoo coffee scale.
 *
 * The web mirror of the Android shell's `ScaleBleManager`. A close sibling of
 * {@link De1Manager}. Responsibilities:
 *  1. `requestDevice()` for a scale (name-prefix filter), connect GATT, and
 *     tell the core which scale it is via `core.connectScale(name)` so the
 *     core selects the right codec.
 *  2. Ask the core for the scale's GATT UUIDs (`core.scaleUuids()`) — unlike
 *     the DE1, the scale's UUIDs are **not** hardcoded in the shell; the core
 *     knows them per scale model.
 *  3. `startNotifications()` on the weight-notify characteristic
 *     (→ `ScaleWeight`) and, when distinct, the command characteristic
 *     (→ `ScaleCommand`, the Bookoo's `ff12` serial / settings responses).
 *  4. Trigger `core.scaleCapabilities()` + `core.queryScaleSettings()` so the
 *     orchestrator can drive capability-gated config UI.
 *  5. Expose {@link writeScale} for `Command.WriteScale` command bytes.
 *
 * Web Bluetooth needs every service it will touch declared up front in
 * `optionalServices`. The scale UUIDs come from `core.scaleUuids()` — but
 * `requestDevice` runs *before* the core has identified the scale, so
 * `optionalServices` lists the known Bookoo service UUID. After connecting,
 * the core-reported UUIDs drive the actual subscriptions.
 */

import type { CoreOutput, CremaCore, NotificationSource, ScaleUuids } from '$lib/core';
import { BleDevice, requestDevice, type ConnState } from './transport';

/**
 * The Bookoo scale's advertised-name prefix — the `requestDevice` name filter.
 * Mirrors the Android shell's `ScaleUuids.BOOKOO_NAME_PREFIX`.
 */
const BOOKOO_NAME_PREFIX = 'BOOKOO_SC';

/**
 * The Bookoo GATT service UUID (`0ffe`), needed in `requestDevice`'s
 * `optionalServices` before the core can report the per-scale UUIDs. Web
 * Bluetooth blocks access to any service not listed at request time.
 */
const BOOKOO_SERVICE_UUID = '00000ffe-0000-1000-8000-00805f9b34fb';

/** Coarse state of the scale connection — mirrors the Android manager's enum. */
export type ScaleState =
	| 'idle'
	| 'connecting'
	| 'subscribing'
	| 'ready'
	| 'disconnected'
	| 'failed';

/** Callbacks the scale manager reports up to the orchestrator. */
export interface ScaleManagerCallbacks {
	/** A raw `CoreOutput` is ready to be folded into state. */
	onCoreOutput: (output: CoreOutput) => void;
	/** A human-readable status line for the UI. */
	onStatus: (line: string) => void;
	/** The coarse connection state advanced. */
	onState: (state: ScaleState) => void;
	/**
	 * The core identified the connected scale. Carries the BLE advertised name
	 * — the orchestrator surfaces it and reads `core.scaleCapabilities()`.
	 */
	onScaleIdentified: (advertisedName: string) => void;
}

/**
 * Owns the scale connection, its notification observation, and the command
 * write path. One instance per app; driven by the orchestrator.
 */
export class ScaleManager {
	private device: BleDevice | null = null;

	/** The connected scale's GATT UUIDs, learned from the core after connect. */
	private uuids: ScaleUuids | null = null;

	/**
	 * Serializes scale command writes. Web Bluetooth permits only one GATT
	 * operation at a time and rejects an overlapping one with "GATT operation
	 * already in progress" — and a single core action (mode selection) emits
	 * three `WriteScale` commands. Every {@link writeScale} chains onto this
	 * promise so writes run strictly one after another, in call order.
	 */
	private writeQueue: Promise<void> = Promise.resolve();

	constructor(
		private readonly core: CremaCore,
		private readonly callbacks: ScaleManagerCallbacks
	) {}

	/** The current GATT link state, for the scale card. */
	get connectionState(): ConnState {
		return this.device?.connectionState ?? 'disconnected';
	}

	/**
	 * Discover, select, and connect a Bookoo scale.
	 *
	 * Must be called from a user gesture. Filters on the `BOOKOO_SC` name
	 * prefix; `optionalServices` carries the known Bookoo service so its
	 * characteristics become accessible once the core reports the exact UUIDs.
	 */
	async connect(): Promise<void> {
		this.callbacks.onState('connecting');
		this.callbacks.onStatus('Requesting a scale…');
		try {
			const device = await requestDevice({
				filters: [{ namePrefix: BOOKOO_NAME_PREFIX }],
				optionalServices: [BOOKOO_SERVICE_UUID]
			});
			this.device = device;
			device.onDisconnected(() => {
				this.callbacks.onState('disconnected');
				this.callbacks.onStatus('Scale disconnected');
			});

			this.callbacks.onStatus(`Connecting to ${device.name}…`);
			await device.connectGatt();

			// Identify the scale with the core so it picks the right codec.
			const advertisedName = device.name;
			const label = await this.core.connectScale(advertisedName);
			if (label === undefined) {
				this.callbacks.onStatus(`Core did not recognise scale '${advertisedName}'`);
				this.device = null;
				device.disconnect();
				this.callbacks.onState('failed');
				return;
			}
			this.callbacks.onStatus(`Core recognised scale: ${label}`);

			// The core now knows the scale's GATT UUIDs — fetch them to learn
			// which characteristics to subscribe to.
			const uuids = await this.core.scaleUuids();
			if (uuids === undefined) {
				throw new Error('Core reported no scale UUIDs after connect');
			}
			this.uuids = uuids;

			// One sink for both characteristics — preserves arrival order.
			device.setSink((notification) => {
				const source = this.sourceFor(notification.characteristic);
				if (source === null) {
					return;
				}
				void this.core
					.onNotification(source, notification.data, notification.atMs)
					.then(this.callbacks.onCoreOutput);
			});

			this.callbacks.onState('subscribing');
			this.callbacks.onStatus('Subscribing to scale weight…');
			await device.startNotifications(uuids.service, uuids.weight_notify);
			// The Bookoo's command characteristic also has the NOTIFY property
			// (its `ff12` serial / settings responses). Subscribe to it too —
			// unless it is the same characteristic as weight-notify, in which
			// case the one subscription already covers it.
			if (uuids.command_write.toLowerCase() !== uuids.weight_notify.toLowerCase()) {
				await device.startNotifications(uuids.service, uuids.command_write);
			}

			this.callbacks.onState('ready');
			this.callbacks.onStatus('Ready — receiving scale weight');

			// Let the orchestrator read capabilities and render gated UI.
			this.callbacks.onScaleIdentified(advertisedName);

			// Fire a baseline settings query so the scale's `03 0e` response
			// lands — capability-gated, empty for a weight-only scale.
			const query = await this.core.queryScaleSettings();
			this.callbacks.onCoreOutput(query);
		} catch (error) {
			this.device?.disconnect();
			this.device = null;
			this.uuids = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus(`Scale connection failed: ${describe(error)}`);
		}
	}

	/** Disconnect the scale. */
	async disconnect(): Promise<void> {
		this.device?.disconnect();
		this.device = null;
		this.uuids = null;
		this.callbacks.onState('disconnected');
		this.callbacks.onStatus('Scale disconnected');
	}

	/**
	 * Write command bytes to the scale's command characteristic.
	 *
	 * The exact bytes come from the core via a `Command.WriteScale`; this
	 * manager owns no protocol. A no-op (with a status line) when no scale is
	 * connected — mirrors the Android manager's defensive `writeCommand`.
	 */
	async writeScale(data: Uint8Array): Promise<void> {
		// Chain onto the write queue so writes never overlap a GATT operation.
		// The chain assignment below is synchronous, so a burst of writeScale
		// calls — e.g. the three-command mode sequence dispatched in one
		// `applyCoreOutput` loop — enqueues in call order and runs serially.
		const run = this.writeQueue.then(() => this.doWriteScale(data));
		// The `catch` keeps the chain alive if one write rejects.
		this.writeQueue = run.catch(() => {});
		return run;
	}

	/** Perform a single scale command write. Serialized by {@link writeScale}. */
	private async doWriteScale(data: Uint8Array): Promise<void> {
		const device = this.device;
		const uuids = this.uuids;
		if (device === null || uuids === null) {
			this.callbacks.onStatus('Cannot write scale command — scale not connected');
			return;
		}
		try {
			await device.write(uuids.service, uuids.command_write, data);
		} catch (error) {
			this.callbacks.onStatus(`Scale command write was rejected: ${describe(error)}`);
		}
	}

	/**
	 * Map a scale characteristic UUID to its core `NotificationSource`. The
	 * weight-notify characteristic → `ScaleWeight`; the command characteristic
	 * → `ScaleCommand`. `null` for an unmapped characteristic.
	 */
	private sourceFor(characteristicUuid: string): NotificationSource | null {
		const uuids = this.uuids;
		if (uuids === null) {
			return null;
		}
		if (characteristicUuid === uuids.weight_notify.toLowerCase()) {
			return 'ScaleWeight';
		}
		if (characteristicUuid === uuids.command_write.toLowerCase()) {
			return 'ScaleCommand';
		}
		return null;
	}
}

/** Best-effort human-readable message from an unknown thrown value. */
function describe(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
