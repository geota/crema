/**
 * `#lib/ble/de1-transport.js` — the transport-agnostic interface every
 * concrete DE1 link implementation satisfies.
 *
 * `BleDevice` (Web Bluetooth GATT, the current default) is one impl;
 * a future WebUSB / Android USB-host transport would be another. The
 * DE1 firmware's USB-CDC framing
 * (`<+X>\n` subscribe / `<X>HEX\n` write / `[X]HEX` notify, where `X`
 * is the low nibble of the BLE characteristic UUID A001–A012)
 * exposes the same characteristics as BLE — only the framing
 * changes, so the same `(service, characteristic, data)` interface
 * fits both.
 *
 * This file is the contract; `transport.ts` carries the BLE impl,
 * and any future transport lands as its own sibling. Consumers
 * (`de1.ts`, the orchestrator, the shell) program against
 * `De1Transport` only — no transport-specific surface leaks past
 * this boundary.
 *
 * Pure refactor (2026-05-22): every existing consumer already calls
 * exactly this surface on `BleDevice`; the interface just makes the
 * shape explicit and future-extensible.
 */

import type { BleNotification, ConnState, KickResult, NotificationSink } from './transport';
import type { ReconnectTrigger } from './reconnect-timeline';

/**
 * The transport-agnostic interface every concrete DE1 link satisfies.
 *
 * Methods mirror the existing `BleDevice` surface verbatim; the only
 * new surface is conceptual — calling code can now type against
 * `De1Transport` instead of `BleDevice` and a future non-BLE
 * implementation drops in transparently. Reaprime's
 * `SerialTransport extends DataTransport` follows the same shape
 * (`unified_de1.dart` reads BLE + USB interchangeably).
 */
export interface De1Transport {
	/** Coarse link state — read by the managers to drive UI. */
	readonly connectionState: ConnState;

	/**
	 * Human-readable device name — BLE pulls from the Web Bluetooth
	 * `BluetoothDevice.name`; USB pulls from the serial-port product
	 * descriptor. Used in status lines + the diagnostics panel.
	 * Can be empty if the transport hasn't resolved a name yet.
	 */
	readonly name: string | null | undefined;

	/**
	 * Stable per-origin device identifier. BLE uses the Web Bluetooth
	 * `BluetoothDevice.id`; USB transports use the device path or
	 * serial number. Logged in connect diagnostics so a user can
	 * confirm they're paired with the right machine.
	 */
	readonly id: string;

	/**
	 * Register the single sink every notification on this device
	 * dispatches to. One sink per device preserves cross-characteristic
	 * arrival order.
	 */
	setSink(sink: NotificationSink): void;

	/**
	 * Register a callback for a *terminal* disconnect — a user-initiated
	 * {@link disconnect} (the backoff loop never gives up on its own).
	 * Not fired for a transient drop the backoff loop recovers.
	 */
	onDisconnected(listener: () => void): void;

	/**
	 * Register a callback for every {@link connectionState} transition —
	 * lets a manager surface `reconnecting` without polling.
	 */
	onStateChanged(listener: (state: ConnState) => void): void;

	/**
	 * Register a callback fired at the start of each reconnect attempt,
	 * with the 1-based attempt number.
	 */
	onReconnectAttempt(listener: (attempt: number) => void): void;

	/**
	 * Register a callback fired once after the auto-reconnect loop has
	 * fully recovered the link — GATT back up, subscriptions replayed.
	 */
	onReconnected(listener: () => void): void;

	/** Connect the underlying transport. Resolves once connected. */
	connectGatt(): Promise<void>;

	/**
	 * The GATT service UUIDs discovered on the connected device, lowercased.
	 * Handed to `core.connectScale` so a distinctive service can identify the
	 * scale when the advertised name doesn't (Acaia generation, a rebrand, or
	 * mixed-case the name filter missed). Optional — a transport that can't
	 * enumerate services omits it and identification falls back to the name.
	 */
	discoveredServiceUuids?(): Promise<readonly string[]>;

	/**
	 * Subscribe to notifications on `(serviceUuid, characteristicUuid)`.
	 * Multiple calls for the same pair are idempotent. USB transports
	 * map the characteristic UUID's low nibble to the firmware's
	 * single-letter framing identifier internally.
	 */
	startNotifications(serviceUuid: string, characteristicUuid: string): Promise<void>;

	/** Write `data` to `(serviceUuid, characteristicUuid)`. */
	write(serviceUuid: string, characteristicUuid: string, data: Uint8Array): Promise<void>;

	/**
	 * Write `data` only if `guard()` still holds when the write reaches the
	 * head of the serial GATT queue. Resolves `true` once written, `false`
	 * when the guard refused (nothing was sent); rejects like {@link write}.
	 * `settled`, when given, hears the result (`true` written, `false`
	 * failed) inside the same queued step, before the next queued write's
	 * guard runs. The profile upload uses both so a superseded or failed
	 * upload's queued frames never reach the DE1 (cohort review 2026-10-28,
	 * #2 N2). Optional: without it the caller checks the guard before
	 * queueing.
	 */
	writeIf?(
		serviceUuid: string,
		characteristicUuid: string,
		data: Uint8Array,
		guard: () => boolean,
		settled?: (written: boolean) => void
	): Promise<boolean>;

	/**
	 * One-shot read of `(serviceUuid, characteristicUuid)`. Returns
	 * the raw bytes the device responded with.
	 */
	readCharacteristic(serviceUuid: string, characteristicUuid: string): Promise<Uint8Array>;

	/** Idempotently disconnect and suppress auto-reconnect. */
	disconnect(): void;

	/**
	 * Drop the GATT link *without* marking a user disconnect, so the ordinary
	 * reconnect loop runs — for a link that stopped answering mid-session
	 * (the liveness check). Optional: a transport without it is dropped
	 * outright instead.
	 */
	recycle?(): void;

	/**
	 * Hold the auto-reconnect loop while the owner's connect or re-seed
	 * program judges a fresh link: a drop meanwhile is noted, not acted on.
	 * Optional, paired with {@link releaseReconnect}.
	 */
	holdReconnect?(): void;

	/**
	 * End the hold. `healthy` = the program succeeded. Returns true when the
	 * link is up and healthy; otherwise the link goes to the reconnect loop
	 * (a hollow one resuming the backoff ladder where it was).
	 */
	releaseReconnect?(healthy: boolean): boolean;

	/**
	 * Cut a pending auto-reconnect wait short and attempt now (the tab came
	 * back into view, "Retry now"). Optional — a transport with no reconnect
	 * loop omits it.
	 */
	kick?(trigger: ReconnectTrigger): KickResult;

	/** Record this device's reconnect episodes under `label`. Optional. */
	setReconnectTimeline?(label: string, expectPostConnect?: boolean): void;

	/**
	 * Register a callback for each advertisement seen while the reconnect loop
	 * watches for the device, with its RSSI in dBm (null when the browser
	 * doesn't report one). Optional — BLE only.
	 */
	onAdvertisement?(listener: (rssi: number | null) => void): void;

	/**
	 * Register a callback for notifications the browser dropped under load
	 * (Safari via Beacio's `beacio:overflow`), with the evicted count.
	 * Optional — never fires on Chrome.
	 */
	onNotificationOverflow?(listener: (characteristic: string, evicted: number) => void): void;
}

// Re-export the shared types so callers that import the transport
// interface can also get `BleNotification`, `ConnState`, and
// `NotificationSink` from one place.
export type { BleNotification, ConnState, NotificationSink };
