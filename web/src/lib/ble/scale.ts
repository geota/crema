/**
 * `#lib/ble/scale.js` — Web Bluetooth manager for a Bookoo coffee scale.
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
import type { CoreOutput, CremaCore, NotificationSource, ScaleUuids } from '#lib/core/index.js';
import { describeError } from '#lib/utils/error.js';
import { isCoreStopped } from '#lib/core/stopped-guard.js';
import type { AppRuntime } from '#lib/effect/runtime.js';
import { scaleConnectProgram } from '#lib/services/scale-orchestrator.js';
import type { BleConnectionState } from './connection-state';
import {
	BleDevice,
	isChooserCancelled,
	requestDevice,
	advertisingStatus,
	droppedNotificationsStatus,
	type ConnState,
	type KickResult
} from './transport';
import type { ReconnectTrigger } from './reconnect-timeline';
import { scaleScanUuids } from '#lib/wasm/de1_wasm.js';
import { ScaleLinkDeadReason } from '#lib/core/crema-core.js';

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
 * A rejected write may be the first sign of a dropped link, with the drop
 * event a beat behind: wait this long before asking for a re-query, and skip
 * it if the scale stopped being ready meanwhile (the reconnect re-queries).
 */
const RESYNC_DELAY_MS = 1_000;

/**
 * No re-query within this long of the last settings query (the connect query,
 * the post-reconnect one or a re-query), and none until a write has landed
 * since then. A query's own writes can be rejected too; before this, each
 * rejection re-queried, and its writes were rejected again (core/shell review
 * 2026-10-30 §2.6-1).
 */
const RESYNC_COOLDOWN_MS = 5_000;

/**
 * Coarse state of the scale connection — an alias of
 * {@link BleConnectionState}, the shared BLE-manager lifecycle. Kept as
 * `ScaleState` for backward compatibility at the existing call sites; new
 * code should import `BleConnectionState` directly from `#lib/ble/index.js`.
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
	 * optimistically-patched control (review #34). Fired only while the scale
	 * is ready, never because a settings query's own writes were rejected —
	 * see {@link RESYNC_COOLDOWN_MS}.
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

	/**
	 * Whether the last reported state is `ready`. Scale writes (heartbeat,
	 * tare, settings) go out only while it is, and the link is up: a write to
	 * a reconnecting scale fails, and each failure used to re-query the
	 * settings, whose writes failed again (core/shell review 2026-10-30
	 * §2.6-1). Android stops its heartbeat when the scale is not READY too.
	 */
	private ready = false;

	/** When the last settings query went out (`Date.now()` ms). */
	private lastQueryAt = -Infinity;

	/** Whether a scale write has landed since {@link lastQueryAt}. */
	private wroteSinceQuery = false;

	/** The pending {@link RESYNC_DELAY_MS} wait before a re-query, if any. */
	private resyncTimer: ReturnType<typeof setTimeout> | null = null;

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
		this.setState('connecting');
		// Tear down any previous device first. Without this a re-connect leaks
		// the old `BleDevice` — its `gattserverdisconnected` handler and every
		// characteristic value-change listener stay bound, and its auto-reconnect
		// loop may still be armed.
		this.dropDevice();
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
			// The previous device was already dropped above; whatever is current
			// now (nothing, or a connect that raced this chooser) is left alone.
			if (isChooserCancelled(error)) {
				// Dismissing the chooser is a choice, not a failed connection.
				this.setState('idle');
				this.callbacks.onStatus('Scale chooser closed — not connected');
				return;
			}
			this.setState('failed');
			this.callbacks.onStatus(`Scale connection failed: ${describeError(error)}`);
			return;
		}
		await this.attach(device);
	}

	/**
	 * Connect a scale this site was already allowed to use — the launch
	 * reconnect over `navigator.bluetooth.getDevices()` (no chooser).
	 */
	async connectKnown(known: BluetoothDevice): Promise<void> {
		this.setState('connecting');
		this.dropDevice();
		this.callbacks.onStatus(`Reconnecting to the last scale (${known.name ?? known.id})…`);
		await this.attach(new BleDevice(known));
	}

	/** The current scale's Web Bluetooth device (for `forget()`), or null. */
	bluetoothDevice(): BluetoothDevice | null {
		return this.device?.device ?? null;
	}

	/** The connected (or connecting) scale's browser id + advertised name, or null. */
	identity(): { id: string; name: string | null } | null {
		const d = this.device;
		return d === null ? null : { id: d.id, name: d.device.name ?? null };
	}

	/** Report a state change, tracking whether the scale is ready for writes. */
	private setState(state: ScaleState): void {
		this.ready = state === 'ready';
		if (!this.ready) this.cancelResync();
		this.callbacks.onState(state);
	}

	/** Whether `device` is the current scale, reported ready, with its link up. */
	private writable(device: BleDevice): boolean {
		return this.device === device && this.ready && device.connectionState === 'connected';
	}

	/** A settings query is going out: it is the resync for what came before. */
	private noteQuery(): void {
		this.cancelResync();
		this.lastQueryAt = Date.now();
		this.wroteSinceQuery = false;
	}

	/** Whether a rejected write on `device` may ask for a re-query now. */
	private mayResync(device: BleDevice): boolean {
		return (
			this.writable(device) &&
			this.wroteSinceQuery &&
			Date.now() - this.lastQueryAt >= RESYNC_COOLDOWN_MS
		);
	}

	private cancelResync(): void {
		if (this.resyncTimer !== null) clearTimeout(this.resyncTimer);
		this.resyncTimer = null;
	}

	/**
	 * Drop the current scale: forget it, close its link, and clear the core's
	 * scale (the DE1 manager's `dropDevice`). Every teardown that doesn't end
	 * in {@link disconnect} — a new connect (even one whose chooser is then
	 * cancelled), a failed handshake — goes through here, so no "ghost" scale
	 * is left in the core to arm stop-at-weight with no weight data (audit
	 * r13 / r14 L-1). The wasm bridge runs the clear synchronously, so it
	 * lands before a replacement's `connectScale`.
	 */
	private dropDevice(): void {
		const device = this.device;
		this.device = null;
		this.uuids = null;
		if (device === null) return;
		device.disconnect();
		void this.core.disconnectScale().catch(() => undefined);
	}

	/** Wire a chosen device and run the connect program (both connect paths). */
	private async attach(device: BleDevice): Promise<void> {
		this.device = device;
		device.setReconnectTimeline('Scale');
		// Each callback counts only while this is still the current device: a
		// replaced device's late events must not touch the new link (the same
		// rule as the DE1 manager; `disconnect` reports its own teardown).
		const current = () => this.device === device;
		// A terminal disconnect (user-initiated; auto-reconnect never gives up).
		device.onDisconnected(() => {
			if (!current()) return;
			this.setState('disconnected');
			this.callbacks.onStatus('Scale disconnected');
		});
		// On recovery the manager re-fires a settings query (via onReconnected).
		device.onReconnectAttempt((attempt) => {
			if (!current()) return;
			this.setState('reconnecting');
			this.callbacks.onStatus(`Reconnecting to scale… (attempt ${attempt})`);
		});
		device.onAdvertisement((rssi) => {
			if (!current()) return;
			this.callbacks.onStatus(advertisingStatus('Scale', rssi));
		});
		device.onNotificationOverflow((characteristic, evicted) => {
			if (!current()) return;
			this.callbacks.onStatus(droppedNotificationsStatus('Scale', characteristic, evicted));
		});
		device.onReconnected(() => {
			if (!current()) return;
			this.setState('ready');
			this.callbacks.onStatus('Reconnected — receiving scale weight');
			// The app re-queries the settings here.
			this.noteQuery();
			this.callbacks.onReconnected();
		});

		if (this.runtime === null) {
			this.dropDevice();
			this.setState('failed');
			this.callbacks.onStatus('Scale connection failed: app runtime unavailable');
			return;
		}

		// ── Phase 2: connect → identify → UUIDs → subscribe → query (Effect) ──
		this.setState('subscribing');
		// The settings query's output (its writes) is held until the scale is
		// ready, so nothing is written to a scale that isn't.
		let queryOutput: CoreOutput | null = null;
		// The core too: a stale program must not identify a scale the user
		// already disconnected (a ghost scale arms stop-at-weight with no
		// weight data; audit r13 L-1). `current()` is checked in the same
		// synchronous turn as the call, and the wasm bridge runs it at once.
		const stale = () => Promise.reject(new Error('Scale was disconnected'));
		const exit = await this.runtime.runPromiseExit(
			scaleConnectProgram({
				device,
				core: {
					connectScale: (name, services) =>
						current() ? this.core.connectScale(name, services) : stale(),
					scaleUuids: () => (current() ? this.core.scaleUuids() : stale()),
					queryScaleSettings: () => (current() ? this.core.queryScaleSettings() : stale())
				},
				advertisedName: device.name,
				onStatus: (line) => {
					if (current()) this.callbacks.onStatus(line);
				},
				onCoreOutput: (output) => {
					if (current()) queryOutput = output;
				},
				onScaleIdentified: (name) => {
					if (current()) this.callbacks.onScaleIdentified(name);
				},
				// Store the UUIDs + install the sink HERE — before the program
				// subscribes — so the sink's UUID→source mapping is ready.
				onUuidsResolved: (uuids) => {
					if (!current()) return;
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
								// Logged once when the core stopped (V2 P3-2).
								if (isCoreStopped(error)) return;
								this.callbacks.onStatus(
									`Scale notification processing failed: ${describeError(error)}`
								);
							});
					});
				}
			})
		);
		// Replaced or disconnected while the program ran: leave the state to
		// whatever replaced it; close a late link unless it's the same scale
		// (a shared GATT link).
		if (!current()) {
			if (this.device?.id !== device.id) device.disconnect();
			return;
		}
		const outcome = parseConnectExit(exit);
		if (!outcome.ok) {
			// The core may already have identified the scale: the drop clears
			// it so a failed handshake leaves no ghost scale behind.
			this.dropDevice();
			this.setState('failed');
			this.callbacks.onStatus(
				`Scale connection failed at "${outcome.step}": ${describeError(outcome.cause)}`
			);
			return;
		}
		this.setState('ready');
		this.callbacks.onStatus('Ready — receiving scale weight');
		if (queryOutput !== null) {
			this.noteQuery();
			this.callbacks.onCoreOutput(queryOutput);
		}
	}

	/**
	 * The core presumed the link dead (`ScaleLinkDead`): drop it at the
	 * transport, not as a user disconnect, so the reconnect loop recovers it
	 * (Android's `ScaleBleManager.presumeDead`). Only while the scale is
	 * ready: a link already reconnecting is left to its loop, which also
	 * keeps one dead episode from tearing down twice.
	 */
	presumeDead(reason: ScaleLinkDeadReason): void {
		const device = this.device;
		if (device === null || !this.ready) return;
		const why =
			reason === ScaleLinkDeadReason.Silent ? 'no readings' : 'writes keep failing';
		this.callbacks.onStatus(`Scale unresponsive (${why}) — reconnecting…`);
		this.setState('reconnecting');
		device.recycle();
	}

	/**
	 * Tell the core how a sent write went, for its dead-link count. A failure
	 * counts only while the link is up: one rejected because the link already
	 * dropped says nothing new (the reconnect loop has it).
	 */
	private reportWrite(device: BleDevice, ok: boolean): void {
		if (ok ? this.device !== device : !this.writable(device)) return;
		void this.core
			.onScaleWriteResult(ok)
			.then((out) => {
				if (this.device === device) this.callbacks.onCoreOutput(out);
			})
			.catch(() => undefined);
	}

	/** Attempt a pending reconnect NOW — the scale twin of `De1Manager.kick`. */
	kick(trigger: ReconnectTrigger): KickResult {
		return this.device?.kick(trigger) ?? 'ignored-idle';
	}

	/** Disconnect the scale. */
	async disconnect(): Promise<void> {
		// Forget the device before closing it, so its own disconnect event
		// (ignored once it isn't current) can't report over this teardown.
		const device = this.device;
		this.device = null;
		this.uuids = null;
		device?.disconnect();
		// Reset the core's scale slice so a vanished scale leaves no stale
		// weight + a reconnect starts clean (the same gap AND4 closed for
		// Android's ScaleBleManager). Best-effort — never blocks the teardown.
		await this.core.disconnectScale();
		this.setState('disconnected');
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
		if (!this.writable(device)) {
			this.callbacks.onStatus('Scale command not sent — the scale is not ready');
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
			if (this.device === device) this.wroteSinceQuery = true;
			this.reportWrite(device, true);
		} catch (error) {
			this.callbacks.onStatus(`Scale command write was rejected: ${describeError(error)}`);
			this.reportWrite(device, false);
			// Let the app layer re-sync optimistic UI state — a rejected
			// config write otherwise leaves the control showing a value the
			// scale doesn't hold (review #34). Not while the link is down (the
			// reconnect re-queries once it's back), and not for a query's own
			// rejected writes (RESYNC_COOLDOWN_MS).
			if (this.resyncTimer !== null || !this.mayResync(device)) return;
			this.resyncTimer = setTimeout(() => {
				this.resyncTimer = null;
				if (!this.mayResync(device)) return;
				this.noteQuery();
				this.callbacks.onWriteFailed?.();
			}, RESYNC_DELAY_MS);
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
