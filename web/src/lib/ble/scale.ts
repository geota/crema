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

import { parseConnectExit } from './connect-exit.ts';
import type { CoreOutput, CremaCore, NotificationSource, ScaleUuids } from '$lib/core';
import { describeError } from '$lib/utils/error';
import type { AppRuntime } from '$lib/effect/runtime';
import { scaleConnectProgram } from '$lib/services/scale-orchestrator';
import type { BleConnectionState } from './connection-state';
import { BleDevice, requestDevice, type ConnState } from './transport';
import { scaleScanUuids } from '$lib/wasm/de1_wasm';

/** The pre-connect scan filter set across all supported scales (the core owns
 *  `Scale::identify`, so it owns the scan filters). */
interface ScaleScanFilters {
	/** GATT service UUIDs to list in `requestDevice`'s `optionalServices`. */
	service_uuids: string[];
	/** Advertised-name prefixes to filter the scan on. */
	name_prefixes: string[];
}

/**
 * The generic scan filter set across ALL supported scales, sourced from the
 * Rust core (`scaleScanUuids` → `Scale::scan_uuids`) rather than hardcoding one
 * scale. A free wasm fn (synchronous), so it's safe to call inside the
 * gesture-bound `requestDevice` path — no `await` to consume the user
 * activation, and never at module scope (the wasm bundle loads first).
 */
function scanFilters(): ScaleScanFilters {
	return JSON.parse(scaleScanUuids()) as ScaleScanFilters;
}

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
	 * A scale command write was rejected by GATT. Optional: the app layer
	 * re-queries the scale's settings so the live stream corrects any
	 * optimistically-patched control (review #34).
	 */
	onWriteFailed?: () => void;
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


	/**
	 * Discover, select, and connect a supported scale.
	 *
	 * Must be called from a user gesture. Filters on the core's generic scan
	 * set ({@link scanFilters} — every supported scale's name prefixes);
	 * `optionalServices` carries every supported scale's service UUID so the
	 * connected scale's characteristics become accessible once the core
	 * identifies it and reports the exact per-model UUIDs.
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
			// Synchronous (free wasm fn) — read it here, inside the gesture,
			// without an `await` before `requestDevice` (a microtask wouldn't
			// clear the user activation, but the sync read keeps it simplest).
			const scan = scanFilters();
			device = await requestDevice({
				filters: scan.name_prefixes.map((namePrefix) => ({ namePrefix })),
				optionalServices: scan.service_uuids
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
		const outcome = parseConnectExit(exit);
		if (!outcome.ok) {
			device.disconnect();
			this.device = null;
			this.uuids = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus(
				`Scale connection failed at "${outcome.step}": ${describeError(outcome.cause)}`
			);
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
		// Reset the core's scale slice so a vanished scale leaves no stale
		// weight + a reconnect starts clean (the same gap AND4 closed for
		// Android's ScaleBleManager). Best-effort — never blocks the teardown.
		await this.core.disconnectScale();
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
			// The gen-1/IPS Acaia's command characteristic rejects acknowledged
			// writes (Decenza acaiascale.cpp:279-295) — the core flags it.
			await device.write(
				uuids.service,
				uuids.command_write,
				data,
				!uuids.command_write_no_response
			);
		} catch (error) {
			this.callbacks.onStatus(`Scale command write was rejected: ${describeError(error)}`);
			// Let the app layer re-sync optimistic UI state — a rejected
			// config write otherwise leaves the control showing a value the
			// scale doesn't hold (review #34).
			this.callbacks.onWriteFailed?.();
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
		if (
			uuids.button_notify !== undefined &&
			characteristicUuid === uuids.button_notify.toLowerCase()
		) {
			return 'ScaleButton';
		}
		return null;
	}
}
