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

import { Cause, Exit } from 'effect';
import type { CoreOutput, CremaCore, NotificationSource, ScaleUuids } from '$lib/core';
import { describeError } from '$lib/utils/error';
import type { AppRuntime } from '$lib/effect/runtime';
import { scaleConnectProgram } from '$lib/services/scale-orchestrator';
import type { BleConnectionState } from './connection-state';
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

/**
 * Coarse state of the scale connection — an alias of
 * {@link BleConnectionState}, the shared BLE-manager lifecycle. Kept as
 * `ScaleState` for backward compatibility at the existing call sites; new
 * code should import `BleConnectionState` directly from `$lib/ble`.
 */
export type ScaleState = BleConnectionState;

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
	/**
	 * The auto-reconnect loop recovered the scale link — GATT back up,
	 * notifications replayed. Lets the orchestrator re-run a settings query so
	 * the config read-back refreshes after the outage.
	 */
	onReconnected: () => void;
}

/**
 * Owns the scale connection, its notification observation, and the command
 * write path. One instance per app; driven by the orchestrator.
 */
export class ScaleManager {
	private device: BleDevice | null = null;

	/** The connected scale's GATT UUIDs, learned from the core after connect. */
	private uuids: ScaleUuids | null = null;

	constructor(
		private readonly core: CremaCore,
		private readonly callbacks: ScaleManagerCallbacks,
		/** The app runtime the connect program runs on (T-19); `null` only on
		 *  unsupported browsers — `connect()` then fails gracefully. */
		private readonly runtime: AppRuntime | null = null
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
		// Tear down any previous device first. Without this a re-connect leaks
		// the old `BleDevice` — its `gattserverdisconnected` handler and every
		// characteristic value-change listener stay bound, and its auto-reconnect
		// loop may still be armed.
		this.device?.disconnect();
		this.device = null;
		this.uuids = null;
		this.callbacks.onStatus('Requesting a scale…');

		// ── Phase 1: gesture-bound discovery + lifecycle wiring (manager) ──
		let device: BleDevice;
		try {
			device = await requestDevice({
				filters: [{ namePrefix: BOOKOO_NAME_PREFIX }],
				optionalServices: [BOOKOO_SERVICE_UUID]
			});
		} catch (error) {
			this.device = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus(`Scale connection failed: ${describeError(error)}`);
			return;
		}
		this.device = device;
		// A terminal disconnect — user-initiated or auto-reconnect giving up.
		device.onDisconnected(() => {
			this.callbacks.onState('disconnected');
			this.callbacks.onStatus('Scale disconnected');
		});
		// On recovery the manager re-fires a settings query (via onReconnected).
		device.onReconnectAttempt((attempt) => {
			this.callbacks.onState('reconnecting');
			this.callbacks.onStatus(`Reconnecting to scale… (attempt ${attempt})`);
		});
		device.onReconnected(() => {
			this.callbacks.onState('ready');
			this.callbacks.onStatus('Reconnected — receiving scale weight');
			this.callbacks.onReconnected();
		});

		if (this.runtime === null) {
			device.disconnect();
			this.device = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus('Scale connection failed: app runtime unavailable');
			return;
		}

		// ── Phase 2: connect → identify → UUIDs → subscribe → query (Effect) ──
		this.callbacks.onState('subscribing');
		const exit = await this.runtime.runPromiseExit(
			scaleConnectProgram({
				device,
				core: this.core,
				advertisedName: device.name,
				onStatus: (line) => this.callbacks.onStatus(line),
				onCoreOutput: (output) => this.callbacks.onCoreOutput(output),
				onScaleIdentified: (name) => this.callbacks.onScaleIdentified(name),
				// Store the UUIDs + install the sink HERE — before the program
				// subscribes — so the sink's UUID→source mapping is ready.
				onUuidsResolved: (uuids) => {
					this.uuids = uuids;
					device.setSink((notification) => {
						const source = this.sourceFor(notification.characteristic);
						if (source === null) {
							return;
						}
						// The core's `onNotification` captures the raw bytes itself.
						void this.core
							.onNotification(source, notification.data, notification.atMs)
							.then(this.callbacks.onCoreOutput)
							.catch((error: unknown) => {
								this.callbacks.onStatus(
									`Scale notification processing failed: ${describeError(error)}`
								);
							});
					});
				}
			})
		);
		if (Exit.isFailure(exit)) {
			device.disconnect();
			this.device = null;
			this.uuids = null;
			this.callbacks.onState('failed');
			const failure = Cause.failureOption(exit.cause);
			const step = failure._tag === 'Some' ? failure.value.step : 'connect';
			const cause = failure._tag === 'Some' ? failure.value.cause : exit.cause;
			this.callbacks.onStatus(`Scale connection failed at "${step}": ${describeError(cause)}`);
			return;
		}
		this.callbacks.onState('ready');
		this.callbacks.onStatus('Ready — receiving scale weight');
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
	 *
	 * No write-specific queue is needed here: `BleDevice` funnels every GATT
	 * operation — writes included — through its per-device serial queue, and
	 * `enqueue` fixes order synchronously. So a burst of `writeScale` calls
	 * made in one synchronous stretch (the three-command mode sequence the
	 * orchestrator dispatches in a single `applyCoreOutput` loop) still runs
	 * strictly in call order, with no overlap against any other GATT op.
	 */
	async writeScale(data: Uint8Array): Promise<void> {
		const device = this.device;
		const uuids = this.uuids;
		if (device === null || uuids === null) {
			this.callbacks.onStatus('Cannot write scale command — scale not connected');
			return;
		}
		try {
			await device.write(uuids.service, uuids.command_write, data);
		} catch (error) {
			this.callbacks.onStatus(`Scale command write was rejected: ${describeError(error)}`);
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
