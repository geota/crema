/**
 * `#lib/ble/de1.js` — Web Bluetooth manager for a DE1 espresso machine.
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
import type { CremaCore, NotificationSource } from '#lib/core/index.js';
import { WriteTarget } from '#lib/core/crema-core.js';
import { de1WriteTargetUuid as wasmDe1WriteTargetUuid } from '#lib/wasm/de1_wasm.js';
import { describeError } from '#lib/utils/error.js';
import { isCoreStopped } from '#lib/core/stopped-guard.js';
import type { AppRuntime } from '#lib/effect/runtime.js';
import { Effect } from 'effect';
import {
	de1ConnectProgram,
	de1MachineInfoProgram,
	de1SeedProgram,
	STATE_SEED_STEP
} from '#lib/services/de1-orchestrator.js';
import { De1Liveness } from './de1-liveness';
import { HOLLOW_LINK_STATUS, stuckLinkHint } from './stuck-link';
import type { BleConnectionState } from './connection-state';
import { De1Uuids } from './de1-uuids';
import {
	advertisingStatus,
	BleDevice,
	droppedNotificationsStatus,
	isChooserCancelled,
	requestDevice,
	type ConnState,
	type KickResult
} from './transport';
import type { ReconnectTrigger } from './reconnect-timeline';
import type { De1Transport } from './de1-transport';

/** Diagnostics publish cadence — advisory counters don't need 25 Hz. */
const DIAGNOSTICS_PUBLISH_INTERVAL_MS = 250;

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
 * `BleConnectionState` directly from `#lib/ble/index.js`.
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
	/**
	 * Signal strength (dBm) of the DE1's last advertisement heard while
	 * reconnecting, or `null`. A connected DE1 doesn't advertise, so this is
	 * only refreshed while the link is down.
	 */
	readonly rssi: number | null;
}

/** The pre-connect / disconnected diagnostics snapshot. */
export const EMPTY_DE1_DIAGNOSTICS: De1Diagnostics = {
	deviceName: null,
	deviceId: null,
	gattVerified: false,
	notificationCount: 0,
	lastNotificationAtMs: null,
	rssi: null
};

/** Callbacks the manager reports up to the orchestrator. */
export interface De1ManagerCallbacks {
	/** A raw `CoreOutput` is ready to be folded into state. */
	onCoreOutput: (output: import('#lib/core/index.js').CoreOutput) => void;
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

	/** Last time the notification tallies were published (throttle anchor). */
	private lastDiagnosticsPublishMs = 0;

	/** The newest arrival stamp, carried into the next throttled publish. */
	private pendingNotificationStamp = 0;

	/** Arrival stamp of the last notification from the current DE1 (liveness). */
	private lastInboundAtMs = 0;

	/** The mid-session dead-link check while the link is ready (`de1-liveness`). */
	private liveness: De1Liveness | null = null;
	/**
	 * This device's identity reads (FirmwareVersion + the MMR sweep) have not
	 * run over a healthy link yet: the first connect handed off to the
	 * reconnect loop before finishing (hollow), or the link dropped as the
	 * program ended. The first healthy re-seed runs them, then clears this
	 * (cohort review 2026-10-28, #3 P1).
	 */
	private identityPending = false;

	/**
	 * While a firmware flash holds the link, every write except the flash's
	 * own ({@link firmwareWrite}) is dropped — a stray 4-byte MMR write
	 * interleaved with the 16-byte firmware chunks on the same characteristic
	 * would corrupt the flash (decaid `firmware_mmr_gate.dart`, Decenza
	 * `de1device.cpp:2091-2124`). The core drops its own commands too; this
	 * catches every shell-side writer as well.
	 */
	private firmwareExclusive = false;

	/** Receives `FWMapRequest` (A009) notifications during a flash. */
	private fwMapListener: ((data: Uint8Array, atMs: number) => void) | null = null;

	/** Link-loss / link-restored observers (the firmware flash). */
	private readonly linkListeners = new Set<{ lost(): void; restored?(): void }>();

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
		this.dropDevice();
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
			if (isChooserCancelled(error)) {
				// Dismissing the chooser is a choice, not a failed connection.
				this.callbacks.onState('idle');
				this.callbacks.onStatus('DE1 chooser closed — not connected');
				return;
			}
			this.callbacks.onState('failed');
			this.callbacks.onStatus(
				`DE1 connection failed at "device chooser": ${describeError(error)}`
			);
			return;
		}
		await this.attach(device);
	}

	/**
	 * Connect a DE1 this site was already allowed to use — the launch
	 * reconnect over `navigator.bluetooth.getDevices()` (no chooser, so no
	 * user gesture needed).
	 */
	async connectKnown(known: BluetoothDevice): Promise<void> {
		this.callbacks.onState('connecting');
		this.dropDevice();
		this.patchDiagnostics(EMPTY_DE1_DIAGNOSTICS);
		this.notificationCounts = freshCounts();
		this.callbacks.onStatus(`Reconnecting to the last DE1 (${known.name ?? known.id})…`);
		await this.attach(new BleDevice(known));
	}

	/** The current DE1's Web Bluetooth device (for `forget()`), or null. */
	bluetoothDevice(): BluetoothDevice | null {
		return this.device instanceof BleDevice ? this.device.device : null;
	}

	/** The connected (or connecting) DE1's browser id + advertised name, or null. */
	identity(): { id: string; name: string | null } | null {
		const d = this.device;
		if (d === null) return null;
		// `BleDevice.name` falls back to a placeholder; remember the real one.
		const name = d instanceof BleDevice ? d.device.name : d.name;
		return { id: d.id, name: name ?? null };
	}

	/** Wire a chosen device and run the connect program (both connect paths). */
	private async attach(device: De1Transport): Promise<void> {
		this.device = device;
		// Reconnect episodes are recorded as "DE1"; READY waits for the
		// post-connect profile sync (CremaApp marks it).
		device.setReconnectTimeline?.('DE1', true);
		this.patchDiagnostics({ deviceName: device.name, deviceId: device.id });
		this.callbacks.onStatus(`Selected device: ${device.name} (id ${device.id})`);
		// Each callback checks the device is still the current one: a replaced
		// device's late events (its reconnect loop finishing after a new
		// connect, its terminal disconnect) must not touch the new link
		// (audit r9 L-1). `dropDevice` reports the replaced link's loss itself.
		// A terminal disconnect of the current device.
		device.onDisconnected(() => {
			if (this.device !== device) return;
			this.stopLiveness();
			this.notifyLinkLost();
			for (const l of this.linkListeners) l.lost();
			this.callbacks.onState('disconnected');
			this.callbacks.onStatus('DE1 disconnected');
		});
		device.onReconnectAttempt((attempt) => {
			if (this.device !== device) return;
			this.stopLiveness();
			// The link is gone; the core keeps the session for a resume but
			// stops the scale's timer if a shot had it running (once per
			// outage — the core latches it).
			if (attempt === 1) {
				this.notifyLinkLost();
				for (const l of this.linkListeners) l.lost();
			}
			this.callbacks.onState('reconnecting');
			// Attempt N starting means N-1 failed since the drop. A DE1 we were
			// just connected to that keeps refusing is most likely holding the
			// dead link — say so, with the remedies in order (stuck-link.ts).
			this.callbacks.onStatus(
				stuckLinkHint(true, attempt - 1) ?? `Reconnecting to DE1… (attempt ${attempt})`
			);
		});
		device.onAdvertisement?.((rssi) => {
			if (this.device !== device) return;
			this.patchDiagnostics({ rssi });
			this.callbacks.onStatus(advertisingStatus('DE1', rssi));
		});
		device.onNotificationOverflow?.((characteristic, evicted) => {
			if (this.device !== device) return;
			this.callbacks.onStatus(droppedNotificationsStatus('DE1', characteristic, evicted));
		});
		device.onReconnected(() => {
			if (this.device !== device) return;
			// Hold the reconnect loop synchronously, before anything can drop
			// the link again: the re-seed below judges this link first.
			device.holdReconnect?.();
			void this.reseedAfterReconnect(device);
		});
		// One sink for all characteristics — preserves arrival order + tallies
		// diagnostics. Set before the program subscribes so early HVNs are caught.
		device.setSink((notification) => {
			if (notification.characteristic === De1Uuids.FW_MAP_REQUEST) {
				this.fwMapListener?.(notification.data, notification.atMs);
				return;
			}
			const source = sourceFor(notification.characteristic);
			if (source === null) {
				return;
			}
			// Count the notification per source and stamp the arrival — live
			// proof the device is streaming decodable DE1 data. Published at
			// most ~4 Hz (review #38): the un-throttled version reassigned
			// the whole UiSnapshot per notification (~25-50 Hz during a
			// shot) just for an advisory counter, doubling reactive churn.
			this.notificationCounts[source] += 1;
			this.pendingNotificationStamp = notification.atMs;
			this.lastInboundAtMs = notification.atMs;
			const now = performance.now();
			if (now - this.lastDiagnosticsPublishMs >= DIAGNOSTICS_PUBLISH_INTERVAL_MS) {
				this.lastDiagnosticsPublishMs = now;
				const total = Object.values(this.notificationCounts).reduce(
					(sum, n) => sum + n,
					0
				);
				this.patchDiagnostics({
					notificationCount: total,
					lastNotificationAtMs: this.pendingNotificationStamp
				});
			}
			// The core's `onNotification` captures the raw bytes itself —
			// see `core/de1-app/src/capture.rs`. No separate tee here.
			void this.core
				.onNotification(source, notification.data, notification.atMs)
				.then(this.callbacks.onCoreOutput)
				.catch((error: unknown) => {
					// A stopped core refuses every call: the stop is logged once
					// with its context, so a line per notification would only push
					// it out of the diagnostics log (V2 P3-2).
					if (isCoreStopped(error)) return;
					// A rejection here — a wasm panic or a bad-packet JSON parse
					// error — would otherwise silently kill the notification
					// pipeline. Surface it instead.
					this.callbacks.onStatus(
						`DE1 notification processing failed: ${describeError(error)}`
					);
				});
		});

		if (this.runtime === null) {
			this.dropDevice();
			this.callbacks.onState('failed');
			this.callbacks.onStatus('DE1 connection failed: app runtime unavailable');
			return;
		}

		// ── Phase 2: GATT connect → subscribe → seed reads → MMR sweep ─────
		// Delegated to the Effect connect program (T-17): step-tagged fatal
		// errors, serial best-effort sweep. The manager just maps the outcome.
		this.callbacks.onState('subscribing');
		// The program's reports count only while this is still the current
		// device (its GATT I/O already refuses once the device is dropped).
		const current = () => this.device === device;
		// No reconnect loop while the program runs: a read that times out
		// drops the link (transport `bounded`), and a loop started then would
		// race the program. The outcome below decides what happens next.
		device.holdReconnect?.();
		this.identityPending = true;
		const exit = await this.runtime.runPromiseExit(
			de1ConnectProgram({
				device,
				core: this.core,
				onStatus: (line) => {
					if (current()) this.callbacks.onStatus(line);
				},
				onCoreOutput: (output) => {
					if (current()) this.callbacks.onCoreOutput(output);
				},
				onGattVerified: () => {
					if (current()) this.patchDiagnostics({ gattVerified: true });
				}
			})
		);
		// Replaced or disconnected while the program ran: the drop already
		// reported it and the device refuses GATT I/O and feeds nothing, so
		// leave the state to whatever replaced it (audit r10 L-2). A browser that
		// doesn't cancel a pending connect may have let this handshake connect
		// after the drop: close that link — unless the replacement is the same
		// DE1, whose GATT link is shared and now belongs to it (audit r11 L-1).
		if (this.device !== device) {
			if (this.device?.id !== device.id) device.disconnect();
			return;
		}
		const outcome = parseConnectExit(exit);
		if (!outcome.ok && outcome.step === STATE_SEED_STEP && device.releaseReconnect) {
			// Hollow: GATT, services and subscriptions all came up, the
			// StateInfo read never answered (edge of range). That is a DE1 we
			// can't hear yet, not a failed connect — hand the link to the
			// reconnect loop, as a re-seed does, instead of giving up.
			this.patchDiagnostics({ gattVerified: false });
			this.callbacks.onState('reconnecting');
			this.callbacks.onStatus(HOLLOW_LINK_STATUS);
			device.releaseReconnect(false);
			return;
		}
		if (!outcome.ok) {
			// Disconnect before nulling — nulling alone leaves the transport's
			// auto-reconnect loop running on a half-dead device.
			this.dropDevice();
			this.patchDiagnostics({ gattVerified: false });
			this.callbacks.onState('failed');
			// Name the failed step — a service / characteristic failure means the
			// selected device is almost certainly NOT a DE1.
			this.callbacks.onStatus(
				`DE1 connection failed at "${outcome.step}": ${describeError(outcome.cause)}`
			);
			return;
		}
		// The program finished, but a late read or write may have dropped the
		// link meanwhile: then the reconnect loop takes over (and re-seeds),
		// and `ready` waits for it.
		if (device.releaseReconnect && !device.releaseReconnect(true)) {
			this.callbacks.onState('reconnecting');
			this.callbacks.onStatus('DE1 link dropped while connecting — reconnecting…');
			return;
		}
		this.identityPending = false;
		this.callbacks.onState('ready');
		this.startLiveness(device);
	}

	/**
	 * Write `data` to the DE1 characteristic identified by `target`.
	 *
	 * The orchestrator calls this for every `Command::WriteCharacteristic`
	 * the core emits. Unrecognised / unmapped targets are dropped with a
	 * status log — the caller is not penalised, but the failure is visible
	 * in diagnostics so a forgotten UUID mapping does not silently disappear.
	 *
	 * Resolves `true` only when the GATT write succeeded. Every skip and
	 * failure resolves `false` (never throws), so the caller can refuse to
	 * synthesise a profile header / frame ack for bytes that never reached
	 * the DE1 (cohort review 02 P0-2).
	 *
	 * `guard`, when given, is checked when the write reaches the head of the
	 * GATT queue; a refusal sends nothing and resolves `false` (the caller's
	 * guard knows it refused). `settled` hears the write's result inside the
	 * same queued step, so a failure is recorded before the next queued write
	 * checks its guard. The profile upload passes its generation rule through
	 * both (cohort review 2026-10-28, #2 N2).
	 */
	async writeCharacteristic(
		target: WriteTarget,
		data: Uint8Array,
		guard?: () => boolean,
		settled?: (written: boolean) => void
	): Promise<boolean> {
		if (this.firmwareExclusive) {
			this.callbacks.onStatus(`DE1 write skipped: firmware update in progress (${target})`);
			return false;
		}
		const uuid = uuidForWriteTarget(target);
		if (uuid === null) {
			this.callbacks.onStatus(`DE1 write skipped: no UUID for target ${target}`);
			return false;
		}
		const device = this.device;
		if (device === null) {
			this.callbacks.onStatus(`DE1 write skipped: not connected (${target})`);
			return false;
		}
		try {
			if (guard === undefined) {
				await device.write(De1Uuids.SERVICE, uuid, data);
				return true;
			}
			if (device.writeIf) {
				return await device.writeIf(De1Uuids.SERVICE, uuid, data, guard, settled);
			}
			if (!guard()) return false;
			await device.write(De1Uuids.SERVICE, uuid, data);
			return true;
		} catch (error) {
			this.callbacks.onStatus(
				`DE1 write to ${target} failed: ${describeError(error)}`
			);
			return false;
		}
	}

	// ── Firmware flash I/O ──────────────────────────────────────────────
	// The flash driver (`#lib/firmware/flash-driver`) performs the core's
	// `FlashAction`s through these. Unlike `writeCharacteristic` they throw,
	// because the state machine must see every failure.

	/** Drop every other write while a flash holds the link (see the field). */
	setFirmwareExclusive(on: boolean): void {
		this.firmwareExclusive = on;
	}

	/** Route A009 notifications to `listener` (`null` to stop). */
	setFwMapListener(listener: ((data: Uint8Array, atMs: number) => void) | null): void {
		this.fwMapListener = listener;
	}

	/** Observe link loss (and auto-reconnect recovery); returns the unsubscribe. */
	addLinkListener(listener: { lost(): void; restored?(): void }): () => void {
		this.linkListeners.add(listener);
		return () => this.linkListeners.delete(listener);
	}

	/** One flash write, with response; throws on failure or no link. */
	async firmwareWrite(target: WriteTarget, data: Uint8Array): Promise<void> {
		const uuid = uuidForWriteTarget(target);
		const device = this.device;
		if (uuid === null) throw new Error(`no UUID for ${target}`);
		if (device === null || device.connectionState !== 'connected') {
			throw new Error('DE1 not connected');
		}
		await device.write(De1Uuids.SERVICE, uuid, data);
	}

	/** A fresh read of A009 (decaid's erase / verify poll). */
	async firmwareReadFwMap(): Promise<Uint8Array> {
		const device = this.device;
		if (device === null) throw new Error('DE1 not connected');
		return device.readCharacteristic(De1Uuids.SERVICE, De1Uuids.FW_MAP_REQUEST);
	}

	/** Enable A009 notifications (re-enabled before verify, like de1app). */
	async firmwareSubscribeFwMap(): Promise<void> {
		const device = this.device;
		if (device === null) throw new Error('DE1 not connected');
		await device.startNotifications(De1Uuids.SERVICE, De1Uuids.FW_MAP_REQUEST);
	}

	/**
	 * Drop the current device: disconnect it (which also stops its reconnect
	 * loop) and tell the core and the link listeners the link is gone. The
	 * device's own late callbacks are ignored once it isn't current, so this
	 * is where a deliberate teardown reports the loss.
	 */
	private dropDevice(): void {
		this.stopLiveness();
		const device = this.device;
		if (device === null) return;
		this.device = null;
		device.disconnect();
		this.notifyLinkLost();
		for (const l of this.linkListeners) l.lost();
	}

	/**
	 * Tell the core the DE1 link dropped (Decenza 29878266). Fire-and-forget:
	 * the output (at most one scale-timer stop) routes like any other.
	 */
	private notifyLinkLost(): void {
		this.core
			.de1LinkLost()
			.then(this.callbacks.onCoreOutput)
			.catch(() => {
				// Best-effort — a failed teardown hint must not break the disconnect.
			});
	}

	/**
	 * A transport-level reconnect replays the subscriptions but not the
	 * connect-time seed reads, so the dashboard would come back `ready` over
	 * whatever state it held before the drop. Re-seed first: `ready` only once
	 * the DE1 has reported a state on *this* link. A StateInfo read that fails
	 * twice means the link is hollow (edge of range) — hand it back to the
	 * transport's reconnect loop, which resumes its backoff ladder, rather
	 * than report ready. The caller has already put the link on hold
	 * (`holdReconnect`), so a timed-out read can't start a second loop.
	 */
	private async reseedAfterReconnect(device: De1Transport): Promise<void> {
		this.callbacks.onState('subscribing');
		this.callbacks.onStatus('Reconnected — reading machine state…');
		const current = () => this.device === device;
		const program = de1SeedProgram({
			device,
			core: this.core,
			onStatus: (line) => {
				if (current()) this.callbacks.onStatus(line);
			},
			onCoreOutput: (output) => {
				if (current()) this.callbacks.onCoreOutput(output);
			}
		});
		const exit =
			this.runtime === null
				? await Effect.runPromiseExit(program)
				: await this.runtime.runPromiseExit(program);
		if (!current()) {
			device.releaseReconnect?.(false);
			return;
		}
		if (!parseConnectExit(exit).ok) {
			this.callbacks.onStatus(HOLLOW_LINK_STATUS);
			if (device.releaseReconnect) {
				device.releaseReconnect(false);
			} else if (device.recycle) {
				device.recycle();
			} else {
				this.dropDevice();
				this.callbacks.onState('failed');
			}
			return;
		}
		// The first connect never got to the identity reads over a healthy
		// link: run them now, before `ready`, so this session learns the model
		// (frame encoding), serial, firmware build and heater voltage.
		if (this.identityPending) {
			this.callbacks.onStatus('Reading machine identity…');
			await this.runMachineInfo(device);
			if (!current()) {
				device.releaseReconnect?.(false);
				return;
			}
			this.identityPending = false;
		}
		// A read or write may have dropped the link after the seed: the loop
		// that release starts reconnects (and re-seeds) again.
		if (device.releaseReconnect && !device.releaseReconnect(true)) return;
		// The firmware flash hears link-up from the app's transition into
		// `ready` (every connect path, audit r2 M-A), not from here.
		for (const l of this.linkListeners) l.restored?.();
		this.callbacks.onState('ready');
		this.callbacks.onStatus('Reconnected — receiving DE1 notifications');
		this.startLiveness(device);
	}

	/**
	 * Re-read the machine identity (firmware build, model, serial, GHC, heater
	 * voltage, …) over the current link. For a firmware flash letting go of
	 * the link: the reads a reconnect ran while the flash held it were refused,
	 * and the build changed (Android's `afterFirmwareRelease` →
	 * `readMachineInfo`). A no-op with no DE1.
	 */
	async readMachineInfo(): Promise<void> {
		if (this.device !== null) await this.runMachineInfo(this.device);
	}

	/** Run {@link de1MachineInfoProgram} on `device`; its output counts only while it is current. */
	private async runMachineInfo(device: De1Transport): Promise<void> {
		const current = () => this.device === device;
		const info = de1MachineInfoProgram({
			core: this.core,
			onStatus: (line) => {
				if (current()) this.callbacks.onStatus(line);
			},
			onCoreOutput: (output) => {
				if (current()) this.callbacks.onCoreOutput(output);
			}
		});
		if (this.runtime === null) await Effect.runPromiseExit(info);
		else await this.runtime.runPromiseExit(info);
	}

	/**
	 * Watch the ready link for silence (`de1-liveness`, Android's rule): a
	 * link quiet for 5 s gets a passive StateInfo probe, and two failed probes
	 * in a row drop it into the reconnect loop (~10 s from the last notification).
	 */
	private startLiveness(device: De1Transport): void {
		this.stopLiveness();
		this.lastInboundAtMs = performance.now();
		const liveness = new De1Liveness({
			now: () => performance.now(),
			lastInboundAtMs: () => this.lastInboundAtMs,
			probe: () => device.readCharacteristic(De1Uuids.SERVICE, De1Uuids.STATE_INFO),
			paused: () => this.firmwareExclusive || device.connectionState !== 'connected',
			onProbeFailed: (failures, quietMs) => {
				if (this.device !== device) return;
				this.callbacks.onStatus(
					`DE1 liveness probe failed (${failures}) after ${Math.round(quietMs / 1000)} s quiet`
				);
			},
			onDead: () => {
				if (this.device !== device) return;
				this.callbacks.onStatus('DE1 link went quiet — reconnecting…');
				if (device.recycle) device.recycle();
				else {
					this.dropDevice();
					this.callbacks.onState('failed');
				}
			}
		});
		this.liveness = liveness;
		liveness.start();
	}

	private stopLiveness(): void {
		this.liveness?.stop();
		this.liveness = null;
	}

	/**
	 * Attempt a pending reconnect NOW (tab back in view, "Retry now"). A no-op
	 * with no device, while connected, or after a deliberate disconnect.
	 */
	kick(trigger: ReconnectTrigger): KickResult {
		return this.device?.kick?.(trigger) ?? 'ignored-idle';
	}

	/** Disconnect the DE1 and discard the core's session state. */
	async disconnect(): Promise<void> {
		this.dropDevice();
		// The reset's output is the scale-timer stop for a mid-shot teardown.
		this.callbacks.onCoreOutput(await this.core.reset());
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
