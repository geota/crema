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
 * Writes are routed via {@link De1Manager.writeCharacteristic}, which maps a
 * core `WriteTarget` to its GATT UUID and dispatches the bytes — the
 * orchestrator (`CremaApp.executeCommand`) calls this for every
 * `Command::WriteCharacteristic` the core emits.
 */

import { parseConnectExit } from './connect-exit.ts';
import type { CremaCore, NotificationSource } from '$lib/core';
import { WriteTarget } from '$lib/core/crema-core';
import { de1WriteTargetUuid as wasmDe1WriteTargetUuid } from '$lib/wasm/de1_wasm';
import { describeError } from '$lib/utils/error';
import type { AppRuntime } from '$lib/effect/runtime';
import { de1ConnectProgram } from '$lib/services/de1-orchestrator';
import type { BleConnectionState } from './connection-state';
import { De1Uuids } from './de1-uuids';
import { requestDevice, type ConnState } from './transport';
import type { De1Transport } from './de1-transport';

/** The `NotificationSource`s the DE1's characteristics route to. */
type De1NotificationSource = Extract<
	NotificationSource,
	| 'De1State'
	| 'De1ShotSample'
	| 'De1WaterLevels'
	| 'De1MmrRead'
	| 'De1ShotSettings'
	| 'De1FrameAck'
	| 'De1Calibration'
>;

/**
 * Coarse state of the DE1 connection — an alias of {@link BleConnectionState},
 * the shared BLE-manager lifecycle. Kept as `De1State` for backward
 * compatibility at the existing call sites; new code should import
 * `BleConnectionState` directly from `$lib/ble`.
 */
export type De1State = BleConnectionState;

/**
 * The connection-diagnostics snapshot folded into the UI state — proof, after
 * a connect, that the device the chooser selected is genuinely a DE1.
 *
 * The DE1's BLE module is a Nordic nRF5x chip and can appear in the chooser
 * under a generic name like "nRF5x" — name alone is not proof. The proof is
 * structural: a real DE1's GATT has the `A000` service with the StateInfo,
 * ShotSample and WaterLevels characteristics, and a non-DE1 board fails
 * service / characteristic resolution. `gattVerified` is `true` only once all
 * four have resolved; `notificationCount` ticking upward is live proof the
 * device is streaming decodable DE1 data.
 */
export interface De1Diagnostics {
	/** The selected device's advertised / chosen name, or `null` pre-connect. */
	readonly deviceName: string | null;
	/** The browser's opaque per-origin device id, or `null` pre-connect. */
	readonly deviceId: string | null;
	/**
	 * `true` once the `A000` service and all three DE1 characteristics
	 * (StateInfo / ShotSample / WaterLevels) resolved and subscribed — the
	 * structural proof the connected device is a DE1.
	 */
	readonly gattVerified: boolean;
	/** Count of DE1 notifications received since the connect began. */
	readonly notificationCount: number;
	/**
	 * `performance.now()` timestamp of the most recent DE1 notification, or
	 * `null` before the first one — drives the "data is flowing" indicator.
	 */
	readonly lastNotificationAtMs: number | null;
}

/** The pre-connect / disconnected diagnostics snapshot. */
export const EMPTY_DE1_DIAGNOSTICS: De1Diagnostics = {
	deviceName: null,
	deviceId: null,
	gattVerified: false,
	notificationCount: 0,
	lastNotificationAtMs: null
};

/** Callbacks the manager reports up to the orchestrator. */
export interface De1ManagerCallbacks {
	/** A raw `CoreOutput` is ready to be folded into state. */
	onCoreOutput: (output: import('$lib/core').CoreOutput) => void;
	/** A human-readable status line for the UI. */
	onStatus: (line: string) => void;
	/** The coarse connection state advanced. */
	onState: (state: De1State) => void;
	/** The connection-diagnostics snapshot changed — fold it into UI state. */
	onDiagnostics: (diagnostics: De1Diagnostics) => void;
}

/**
 * Owns the DE1 connection and its notification observation. One instance per
 * app; `connect()` / `disconnect()` are driven by the orchestrator.
 */
export class De1Manager {
	private device: De1Transport | null = null;

	/**
	 * The live connection-diagnostics snapshot. Mutated through {@link patchDiagnostics}
	 * so every change is published to the orchestrator via `onDiagnostics`.
	 */
	private diagnostics: De1Diagnostics = EMPTY_DE1_DIAGNOSTICS;

	/**
	 * Per-source notification tallies since the current connect began. Kept
	 * separately from the published {@link diagnostics} so the manager can
	 * report a per-characteristic breakdown and a running total. Reset to
	 * {@link freshCounts} on every connect / disconnect.
	 */
	private notificationCounts: Record<De1NotificationSource, number> = freshCounts();

	constructor(
		private readonly core: CremaCore,
		private readonly callbacks: De1ManagerCallbacks,
		/**
		 * The app runtime the connect program runs on (T-17/T-18). `null` only on
		 * unsupported browsers where the shell never mounts it — `connect()` then
		 * fails gracefully.
		 */
		private readonly runtime: AppRuntime | null = null
	) {}

	/** The current GATT link state, for the connection card. */
	get connectionState(): ConnState {
		return this.device?.connectionState ?? 'disconnected';
	}

	/** Patch the diagnostics snapshot and publish it to the orchestrator. */
	private patchDiagnostics(partial: Partial<De1Diagnostics>): void {
		this.diagnostics = { ...this.diagnostics, ...partial };
		this.callbacks.onDiagnostics(this.diagnostics);
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
		// Tear down any previous device first. Without this a re-connect leaks
		// the old `BleDevice` — its `gattserverdisconnected` handler and every
		// characteristic value-change listener stay bound, and its auto-reconnect
		// loop may still be armed.
		this.device?.disconnect();
		this.device = null;
		// Start each connect from a clean diagnostics slate.
		this.patchDiagnostics(EMPTY_DE1_DIAGNOSTICS);
		this.notificationCounts = freshCounts();
		this.callbacks.onStatus('Requesting a DE1…');

		// ── Phase 1: gesture-bound discovery + device wiring ───────────────
		// The chooser MUST run synchronously off the user gesture, and the sink +
		// lifecycle listeners are callback-driven (they mutate this manager's
		// diagnostics counts) — so this stays in the manager, not the program.
		let device: De1Transport;
		try {
			// Match the DE1 by its A000 service OR by the DE1/BENGLE name prefix
			// (`filters` is an OR-list); `optionalServices` grants A000 access
			// once connected regardless of which filter matched.
			device = await requestDevice({
				filters: [
					{ services: [De1Uuids.SERVICE] },
					...De1Uuids.NAME_PREFIXES.map((namePrefix) => ({ namePrefix }))
				],
				optionalServices: [De1Uuids.SERVICE]
			});
		} catch (error) {
			this.device = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus(
				`DE1 connection failed at "device chooser": ${describeError(error)}`
			);
			return;
		}
		this.device = device;
		this.patchDiagnostics({ deviceName: device.name, deviceId: device.id });
		this.callbacks.onStatus(`Selected device: ${device.name} (id ${device.id})`);
		// A terminal disconnect — user-initiated or auto-reconnect giving up.
		device.onDisconnected(() => {
			this.callbacks.onState('disconnected');
			this.callbacks.onStatus('DE1 disconnected');
		});
		device.onReconnectAttempt((attempt) => {
			this.callbacks.onState('reconnecting');
			this.callbacks.onStatus(`Reconnecting to DE1… (attempt ${attempt})`);
		});
		device.onReconnected(() => {
			this.callbacks.onState('ready');
			this.callbacks.onStatus('Reconnected — receiving DE1 notifications');
		});
		// One sink for all characteristics — preserves arrival order + tallies
		// diagnostics. Set before the program subscribes so early HVNs are caught.
		device.setSink((notification) => {
			const source = sourceFor(notification.characteristic);
			if (source === null) {
				return;
			}
			// Count the notification per source and stamp the arrival — live
			// proof the device is streaming decodable DE1 data.
			this.notificationCounts[source] += 1;
			const total = Object.values(this.notificationCounts).reduce((sum, n) => sum + n, 0);
			this.patchDiagnostics({
				notificationCount: total,
				lastNotificationAtMs: notification.atMs
			});
			// The core's `onNotification` captures the raw bytes itself —
			// see `core/de1-app/src/capture.rs`. No separate tee here.
			void this.core
				.onNotification(source, notification.data, notification.atMs)
				.then(this.callbacks.onCoreOutput)
				.catch((error: unknown) => {
					// A rejection here — a wasm panic or a bad-packet JSON parse
					// error — would otherwise silently kill the notification
					// pipeline. Surface it instead.
					this.callbacks.onStatus(
						`DE1 notification processing failed: ${describeError(error)}`
					);
				});
		});

		if (this.runtime === null) {
			device.disconnect();
			this.device = null;
			this.callbacks.onState('failed');
			this.callbacks.onStatus('DE1 connection failed: app runtime unavailable');
			return;
		}

		// ── Phase 2: GATT connect → subscribe → seed reads → MMR sweep ─────
		// Delegated to the Effect connect program (T-17): step-tagged fatal
		// errors, serial best-effort sweep. The manager just maps the outcome.
		this.callbacks.onState('subscribing');
		const exit = await this.runtime.runPromiseExit(
			de1ConnectProgram({
				device,
				core: this.core,
				onStatus: (line) => this.callbacks.onStatus(line),
				onCoreOutput: (output) => this.callbacks.onCoreOutput(output),
				onGattVerified: () => this.patchDiagnostics({ gattVerified: true })
			})
		);
		const outcome = parseConnectExit(exit);
		if (!outcome.ok) {
			// Disconnect before nulling — nulling alone leaves the transport's
			// auto-reconnect loop running on a half-dead device.
			device.disconnect();
			this.device = null;
			this.patchDiagnostics({ gattVerified: false });
			this.callbacks.onState('failed');
			// Name the failed step — a service / characteristic failure means the
			// selected device is almost certainly NOT a DE1.
			this.callbacks.onStatus(
				`DE1 connection failed at "${outcome.step}": ${describeError(outcome.cause)}`
			);
			return;
		}
		this.callbacks.onState('ready');
	}

	/**
	 * Write `data` to the DE1 characteristic identified by `target`.
	 *
	 * The orchestrator calls this for every `Command::WriteCharacteristic`
	 * the core emits. Unrecognised / unmapped targets are dropped with a
	 * status log — the caller is not penalised, but the failure is visible
	 * in diagnostics so a forgotten UUID mapping does not silently disappear.
	 */
	async writeCharacteristic(target: WriteTarget, data: Uint8Array): Promise<void> {
		const uuid = uuidForWriteTarget(target);
		if (uuid === null) {
			this.callbacks.onStatus(`DE1 write skipped: no UUID for target ${target}`);
			return;
		}
		const device = this.device;
		if (device === null) {
			this.callbacks.onStatus(`DE1 write skipped: not connected (${target})`);
			return;
		}
		try {
			await device.write(De1Uuids.SERVICE, uuid, data);
		} catch (error) {
			this.callbacks.onStatus(
				`DE1 write to ${target} failed: ${describeError(error)}`
			);
		}
	}

	/** Disconnect the DE1 and discard the core's session state. */
	async disconnect(): Promise<void> {
		this.device?.disconnect();
		this.device = null;
		await this.core.reset();
		// Clear the diagnostics — the device is gone, so its name / id / GATT
		// verification / notification tally no longer hold.
		this.patchDiagnostics(EMPTY_DE1_DIAGNOSTICS);
		this.notificationCounts = freshCounts();
		this.callbacks.onState('disconnected');
		this.callbacks.onStatus('DE1 disconnected');
	}
}

/**
 * Map a core `WriteTarget` to the DE1 characteristic UUID its bytes get
 * written to. Returns `null` for a target Crema does not yet wire on the BLE
 * side — the orchestrator surfaces this as a "no UUID for target" log entry.
 */
function uuidForWriteTarget(target: WriteTarget): string | null {
	// Delegates to the core's single `WriteTarget` → UUID map so the web and
	// Android shells don't each carry their own switch. `null` for an unknown
	// target — the orchestrator surfaces it as a "no UUID for target" log entry.
	return wasmDe1WriteTargetUuid(target) ?? null;
}

/**
 * Map a DE1 characteristic UUID to its core `NotificationSource`. Returns
 * `null` for an unmapped characteristic, which is dropped.
 */
function sourceFor(characteristicUuid: string): De1NotificationSource | null {
	switch (characteristicUuid) {
		case De1Uuids.STATE_INFO:
			return 'De1State';
		case De1Uuids.SHOT_SAMPLE:
			return 'De1ShotSample';
		case De1Uuids.WATER_LEVELS:
			return 'De1WaterLevels';
		case De1Uuids.MMR_READ:
			return 'De1MmrRead';
		case De1Uuids.SHOT_SETTINGS:
			return 'De1ShotSettings';
		case De1Uuids.FRAME_WRITE:
			return 'De1FrameAck';
		case De1Uuids.CALIBRATION:
			return 'De1Calibration';
		default:
			return null;
	}
}

/**
 * A fresh per-source notification tally — every {@link De1NotificationSource}
 * at zero. The one place the zeroed shape is spelled out, so a connect, a
 * disconnect and the field initialiser cannot drift apart.
 */
function freshCounts(): Record<De1NotificationSource, number> {
	return {
		De1State: 0,
		De1ShotSample: 0,
		De1WaterLevels: 0,
		De1MmrRead: 0,
		De1ShotSettings: 0,
		De1FrameAck: 0,
		De1Calibration: 0
	};
}
