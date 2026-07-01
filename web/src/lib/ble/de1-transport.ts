/**
 * `$lib/ble/de1-transport` — the transport-agnostic interface every
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

import type { BleNotification, ConnState, NotificationSink } from './transport';

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
	 * Register a callback for a *terminal* disconnect — either a
	 * user-initiated {@link disconnect} or auto-reconnect giving up
	 * after exhausting its attempts. Not fired for a transient drop
	 * the backoff loop recovers.
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
	 * One-shot read of `(serviceUuid, characteristicUuid)`. Returns
	 * the raw bytes the device responded with.
	 */
	readCharacteristic(serviceUuid: string, characteristicUuid: string): Promise<Uint8Array>;

	/** Idempotently disconnect and suppress auto-reconnect. */
	disconnect(): void;
}

// Re-export the shared types so callers that import the transport
// interface can also get `BleNotification`, `ConnState`, and
// `NotificationSink` from one place.
export type { BleNotification, ConnState, NotificationSink };
