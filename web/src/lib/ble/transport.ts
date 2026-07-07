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
 * ## Robustness patterns borrowed from `blu` / Nordic's BLE library
 *
 * Two robustness patterns are folded into {@link BleDevice}, both internal to
 * the transport so the managers stay simple:
 *
 *  1. **A single serial GATT operation queue.** Web Bluetooth permits only ONE
 *     in-flight GATT operation per device and rejects an overlapping one with
 *     "GATT operation already in progress". Every GATT call — `gatt.connect()`,
 *     `getPrimaryService` / `getCharacteristic`, `startNotifications`, `write`
 *     — funnels through {@link BleDevice}'s per-device {@link GattQueue}, so
 *     bursts (the scale's three-command mode sequence) never collide. The
 *     queue is per `BleDevice`: the DE1 and the scale are independent devices
 *     with independent queues.
 *
 *  2. **Auto-reconnect with exponential backoff.** Borrowed from Nordic's
 *     auto-reconnect. {@link BleDevice} records its subscription set — every
 *     (service, characteristic) pair passed to `startNotifications` — and
 *     distinguishes a deliberate {@link BleDevice.disconnect} from an
 *     unexpected `gattserverdisconnected`. On an unexpected drop it runs a
 *     bounded backoff loop that reconnects GATT, re-resolves the
 *     characteristics, replays the subscriptions, and re-attaches the sink —
 *     no user gesture needed, since `device.gatt.connect()` works on the same
 *     `BluetoothDevice` for the page session.
 *
 * `ConnState` mirrors the Android transport's coarse flat enum, plus a
 * `reconnecting` value the backoff loop drives.
 */

/**
 * Coarse connection state — connected-or-not, the failure case, and the
 * `reconnecting` value driven by the auto-reconnect backoff loop. The managers
 * map this onto their own state enums and the UI's enabled rules.
 */
import type { De1Transport } from './de1-transport';

export type ConnState =
	| 'disconnected'
	| 'connecting'
	| 'connected'
	| 'reconnecting'
	| 'failed';

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

/** A (service, characteristic) pair the device has subscribed to. */
interface Subscription {
	readonly serviceUuid: string;
	readonly characteristicUuid: string;
}

/** First reconnect-backoff delay, in milliseconds. Doubles each attempt. */
const RECONNECT_BASE_DELAY_MS = 500;

/** Reconnect-backoff ceiling, in milliseconds — the delay never exceeds this. */
const RECONNECT_MAX_DELAY_MS = 30_000;

/**
 * How many reconnect attempts to make before giving up. With a base of 500 ms
 * doubling to a 30 s cap, eight attempts span roughly two minutes of retrying
 * — long enough to ride out a brief outage, short enough that a UI showing
 * "reconnecting" is not stuck forever.
 */
const RECONNECT_MAX_ATTEMPTS = 8;

/**
 * A strictly-serial async operation queue.
 *
 * Web Bluetooth allows one in-flight GATT operation per device; this queue
 * makes that guarantee structural. {@link enqueue} appends an operation and
 * returns a promise for its result; the queued operation does not start until
 * every earlier one has settled (resolved *or* rejected — one failure must not
 * stall the queue). Enqueuing is **synchronous**: the moment {@link enqueue}
 * returns, the operation's place in line is fixed, so a burst of calls made in
 * one synchronous stretch (e.g. the scale's three-command mode sequence) runs
 * in strict call order.
 */
class GattQueue {
	/** The tail of the chain — the promise the next operation waits on. */
	private tail: Promise<unknown> = Promise.resolve();

	/**
	 * Append `operation` to the queue. Returns a promise that settles with the
	 * operation's result. The operation starts only once all earlier ones have
	 * settled; a rejection in an earlier operation does not stall later ones.
	 */
	enqueue<T>(operation: () => Promise<T>): Promise<T> {
		// Chain onto the tail; `.then(operation, operation)` runs `operation`
		// whether the previous link resolved or rejected, so one failed GATT
		// call cannot deadlock the queue.
		const result = this.tail.then(operation, operation);
		// Advance the tail synchronously, swallowing rejections so the *next*
		// enqueue waits only for completion, not for success. Callers still see
		// the real rejection through `result`.
		this.tail = result.then(
			() => undefined,
			() => undefined
		);
		return result;
	}
}

/**
 * A live connection to one BLE device, over Web Bluetooth GATT.
 *
 * Created by {@link requestDevice}. Owns its `BluetoothDevice`, GATT server,
 * the shared `characteristicvaluechanged` listener, a per-device serial GATT
 * operation queue, and an auto-reconnect backoff loop. `connectionState`
 * tracks the link; `disconnect()` is idempotent and suppresses reconnect.
 *
 * Implements the transport-agnostic {@link De1Transport} interface so
 * the orchestrator + `de1.ts` can swap in a future USB / mock
 * transport without code changes. The class keeps its
 * full public surface for back-compat — the interface is a subset.
 */
export class BleDevice implements De1Transport {
	/** Coarse link state — read by the managers to drive their UI state. */
	connectionState: ConnState = 'disconnected';

	/** The single sink every notification on this device is dispatched to. */
	private sink: NotificationSink | null = null;

	/** Characteristics resolved via {@link getCharacteristic}, by UUID. */
	private readonly characteristics = new Map<string, BluetoothRemoteGATTCharacteristic>();

	/**
	 * Characteristic objects the `characteristicvaluechanged` listener is
	 * already attached to.
	 *
	 * `addEventListener` only de-duplicates an *identical* (object, type,
	 * handler) triple — and after a reconnect the characteristic is re-resolved
	 * to a fresh object, so the de-dup does not apply. Without this set the
	 * reconnect loop's subscription replay would attach the listener a second
	 * time to a characteristic that survived as the same object across the
	 * replay, delivering every packet twice. Tracked by object identity so a
	 * post-reconnect fresh object is correctly treated as not-yet-listening.
	 */
	private readonly listenerAttached = new WeakSet<BluetoothRemoteGATTCharacteristic>();

	/**
	 * Per-device serial GATT operation queue. Every GATT call — connect,
	 * service / characteristic resolution, notification subscribe, write —
	 * funnels through this so two operations never overlap.
	 */
	private readonly gattQueue = new GattQueue();

	/**
	 * The device's subscription set — every (service, characteristic) pair
	 * passed to {@link startNotifications}. Recorded so the auto-reconnect loop
	 * can replay them after a drop. A `Map` keyed by characteristic UUID keeps
	 * it de-duplicated.
	 */
	private readonly subscriptions = new Map<string, Subscription>();

	/**
	 * `true` once {@link disconnect} has been called — a *deliberate* teardown.
	 * Suppresses the auto-reconnect loop: a user-initiated disconnect must stay
	 * disconnected. An unexpected `gattserverdisconnected` leaves this `false`,
	 * which is what arms the reconnect.
	 */
	private userDisconnected = false;

	/** `true` while the auto-reconnect backoff loop is running. */
	private reconnecting = false;

	/**
	 * The bound `characteristicvaluechanged` handler — one per device.
	 *
	 * Re-used across reconnects: the listener is attached per characteristic in
	 * {@link startNotifications}, and after a reconnect the characteristics are
	 * re-resolved (fresh objects) and the listener re-attached.
	 */
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

	/**
	 * Handler for the device's `gattserverdisconnected` event.
	 *
	 * The link dropped. If {@link disconnect} caused it the drop is expected —
	 * settle on `disconnected` and notify listeners. Otherwise it is an
	 * *unexpected* drop: enter `reconnecting` and arm the backoff loop, which
	 * runs without a user gesture.
	 */
	private readonly onGattDisconnected = (): void => {
		if (this.userDisconnected) {
			this.connectionState = 'disconnected';
			this.notifyDisconnected();
			return;
		}
		// Unexpected drop — if a reconnect loop is already running, let it be.
		if (this.reconnecting) {
			return;
		}
		this.connectionState = 'reconnecting';
		this.notifyStateChanged();
		void this.runReconnectLoop();
	};

	private readonly onDisconnectedListeners = new Set<() => void>();

	private readonly onStateChangedListeners = new Set<(state: ConnState) => void>();

	private readonly onReconnectAttemptListeners = new Set<(attempt: number) => void>();

	private readonly onReconnectedListeners = new Set<() => void>();

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
	 * The browser's opaque, per-origin stable identifier for this device — the
	 * `BluetoothDevice.id`. Distinct from the advertised name (which a DE1's
	 * Nordic module may show as "nRF5x"); surfaced in the connection
	 * diagnostics so the user can pin down exactly which device was selected.
	 */
	get id(): string {
		return this.device.id;
	}

	/**
	 * Register the single sink every notification on this device dispatches
	 * to. One sink per device preserves cross-characteristic arrival order —
	 * the web equivalent of the Android managers merging characteristic flows.
	 * The sink is retained across reconnects and re-attached automatically.
	 */
	setSink(sink: NotificationSink): void {
		this.sink = sink;
	}

	/**
	 * Register a callback for a *terminal* disconnect — either a user-initiated
	 * {@link disconnect} or auto-reconnect giving up after exhausting its
	 * attempts. Not fired for a transient drop that the backoff loop recovers.
	 */
	onDisconnected(listener: () => void): void {
		this.onDisconnectedListeners.add(listener);
	}

	/**
	 * Register a callback for every {@link connectionState} transition — lets
	 * a manager surface `reconnecting` without polling.
	 */
	onStateChanged(listener: (state: ConnState) => void): void {
		this.onStateChangedListeners.add(listener);
	}

	/**
	 * Register a callback fired at the start of each reconnect attempt, with
	 * the 1-based attempt number — drives a "Reconnecting… (attempt N)" line.
	 */
	onReconnectAttempt(listener: (attempt: number) => void): void {
		this.onReconnectAttemptListeners.add(listener);
	}

	/**
	 * Register a callback fired once after the auto-reconnect loop has fully
	 * recovered the link — GATT back up, subscriptions replayed. Lets a manager
	 * re-run any post-connect work (e.g. a scale settings query).
	 */
	onReconnected(listener: () => void): void {
		this.onReconnectedListeners.add(listener);
	}

	/**
	 * Connect the GATT server. Resolves once connected; rejects on failure.
	 * The `requestDevice` chooser already happened in {@link requestDevice}.
	 *
	 * The `gatt.connect()` call is queued so it cannot overlap any other GATT
	 * operation on this device.
	 */
	async connectGatt(): Promise<void> {
		this.connectionState = 'connecting';
		this.notifyStateChanged();
		try {
			await this.connectGattRaw();
			this.connectionState = 'connected';
			this.notifyStateChanged();
		} catch (error) {
			this.connectionState = 'failed';
			this.notifyStateChanged();
			throw error;
		}
	}

	/**
	 * The connected device's primary GATT service UUIDs (lowercased) — passed to
	 * `core.connectScale` so a distinctive service can identify the scale when
	 * the advertised name doesn't. Best-effort: some platforms reject
	 * `getPrimaryServices` if nothing is cached/accessible, in which case
	 * identification falls back to the name.
	 */
	async discoveredServiceUuids(): Promise<readonly string[]> {
		const gatt = this.device.gatt;
		if (!gatt?.connected) return [];
		try {
			const services = await gatt.getPrimaryServices();
			return services.map((s) => s.uuid.toLowerCase());
		} catch {
			return [];
		}
	}

	/**
	 * The raw queued `gatt.connect()` — used by both {@link connectGatt} and
	 * the reconnect loop, so it does not touch {@link connectionState} itself.
	 */
	private connectGattRaw(): Promise<void> {
		return this.gattQueue.enqueue(async () => {
			const gatt = this.device.gatt;
			if (gatt === undefined) {
				throw new Error('Device exposes no GATT server');
			}
			if (gatt.connected) return;
			// Web Bluetooth's `gatt.connect()` fails intermittently for some
			// peripherals ("Connection attempt failed" / NetworkError) — several
			// scales (Bookoo included) are notorious for it — where an immediate
			// retry succeeds. Try a few times with a short backoff before surfacing
			// the failure; the DE1 path benefits from the same resilience.
			const attempts = 3;
			const backoffMs = 400;
			let lastError: unknown;
			for (let attempt = 1; attempt <= attempts; attempt++) {
				try {
					await gatt.connect();
					return;
				} catch (error) {
					lastError = error;
					if (attempt < attempts) {
						await new Promise((resolve) => setTimeout(resolve, backoffMs));
					}
				}
			}
			throw lastError;
		});
	}

	/**
	 * Resolve a characteristic of `serviceUuid`, caching it for later writes.
	 * The service / characteristic lookups are queued so they cannot overlap
	 * another GATT operation on this device.
	 */
	getCharacteristic(
		serviceUuid: string,
		characteristicUuid: string
	): Promise<BluetoothRemoteGATTCharacteristic> {
		return this.gattQueue.enqueue(() =>
			this.resolveCharacteristic(serviceUuid, characteristicUuid)
		);
	}

	/**
	 * Resolve and cache a characteristic. The body of {@link getCharacteristic}
	 * and the reconnect-time replay — kept un-queued so the reconnect loop can
	 * call it from inside its own single queued step without self-deadlocking.
	 */
	private async resolveCharacteristic(
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
	 *
	 * The pair is recorded in the subscription set so the auto-reconnect loop
	 * can replay it after a drop. The whole resolve-attach-subscribe sequence
	 * runs as one queued GATT step.
	 */
	async startNotifications(serviceUuid: string, characteristicUuid: string): Promise<void> {
		await this.gattQueue.enqueue(() =>
			this.subscribeRaw(serviceUuid, characteristicUuid)
		);
		// Record only after a successful subscribe, so a failed attempt is not
		// replayed on reconnect. Keyed by characteristic UUID — de-duplicated.
		this.subscriptions.set(characteristicUuid.toLowerCase(), {
			serviceUuid,
			characteristicUuid
		});
	}

	/**
	 * Resolve a characteristic, attach the value-change listener, and enable
	 * notifications. The un-queued body of {@link startNotifications}, also
	 * called by the reconnect loop's replay.
	 */
	private async subscribeRaw(serviceUuid: string, characteristicUuid: string): Promise<void> {
		const characteristic = await this.resolveCharacteristic(serviceUuid, characteristicUuid);
		// Attach the value-change listener at most once per characteristic
		// object. A re-subscribe of an already-subscribed characteristic — the
		// reconnect loop replaying the same object, or a manager subscribing the
		// same characteristic twice — must not double up the listener, or every
		// packet would be delivered twice.
		if (!this.listenerAttached.has(characteristic)) {
			characteristic.addEventListener('characteristicvaluechanged', this.onValueChanged);
			this.listenerAttached.add(characteristic);
		}
		await characteristic.startNotifications();
	}

	/**
	 * Write `data` to a characteristic. Used for scale command writes; the
	 * exact bytes come from the core via `Command.WriteScale`.
	 *
	 * The whole resolve-then-write is one queued GATT step, so a burst of
	 * writes (the scale's three-command mode sequence) runs strictly serially
	 * and cannot collide with a notification subscribe or a reconnect.
	 */
	write(
		serviceUuid: string,
		characteristicUuid: string,
		data: Uint8Array,
		withResponse = true
	): Promise<void> {
		return this.gattQueue.enqueue(async () => {
			let characteristic = this.characteristics.get(characteristicUuid.toLowerCase());
			if (characteristic === undefined) {
				characteristic = await this.resolveCharacteristic(
					serviceUuid,
					characteristicUuid
				);
			}
			// `writeValueWithResponse` is the equivalent of the Android
			// transport's acknowledged write; copy into a fresh buffer for a
			// clean ArrayBuffer. `withResponse = false` for characteristics
			// that only accept unacknowledged writes (gen-1/IPS Acaia).
			const buffer = data.slice().buffer;
			if (withResponse) {
				await characteristic.writeValueWithResponse(buffer);
			} else {
				await characteristic.writeValueWithoutResponse(buffer);
			}
		});
	}

	/**
	 * Read a characteristic's current value. Used for the DE1 `Version`
	 * characteristic, which is a one-shot Read (not a Notify) — there is no
	 * value-change stream, so the manager reads it once after connecting.
	 *
	 * The resolve-then-read runs as one queued GATT step, so it cannot collide
	 * with a notification subscribe, a write, or a reconnect.
	 */
	readCharacteristic(serviceUuid: string, characteristicUuid: string): Promise<Uint8Array> {
		return this.gattQueue.enqueue(async () => {
			let characteristic = this.characteristics.get(characteristicUuid.toLowerCase());
			if (characteristic === undefined) {
				characteristic = await this.resolveCharacteristic(
					serviceUuid,
					characteristicUuid
				);
			}
			const view = await characteristic.readValue();
			return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
		});
	}

	/**
	 * Disconnect the GATT server and drop the device's listeners. Idempotent.
	 *
	 * Marks the teardown *deliberate*, which suppresses the auto-reconnect loop
	 * — a user-initiated disconnect must stay disconnected.
	 */
	disconnect(): void {
		this.userDisconnected = true;
		this.reconnecting = false;
		const gatt = this.device.gatt;
		if (gatt !== undefined && gatt.connected) {
			gatt.disconnect();
		}
		this.connectionState = 'disconnected';
		this.notifyStateChanged();
	}

	/**
	 * The auto-reconnect backoff loop — Nordic's auto-reconnect, ported.
	 *
	 * Runs after an *unexpected* drop. Each attempt waits an exponentially
	 * growing delay (≈500 ms, doubling, capped at 30 s), then reconnects GATT,
	 * clears the stale characteristic cache, re-resolves and replays every
	 * recorded subscription, and re-stamps the link `connected`. The sink is
	 * untouched — it is replayed implicitly because the re-attached listeners
	 * dispatch to the same `sink` field.
	 *
	 * After {@link RECONNECT_MAX_ATTEMPTS} failed attempts it gives up to a
	 * terminal `failed` state and fires the disconnect listeners, so the UI is
	 * never stuck showing "reconnecting" forever.
	 */
	private async runReconnectLoop(): Promise<void> {
		this.reconnecting = true;
		for (let attempt = 1; attempt <= RECONNECT_MAX_ATTEMPTS; attempt++) {
			// A deliberate disconnect during the wait aborts the loop.
			if (this.userDisconnected) {
				this.reconnecting = false;
				return;
			}
			const delay = Math.min(
				RECONNECT_BASE_DELAY_MS * 2 ** (attempt - 1),
				RECONNECT_MAX_DELAY_MS
			);
			await sleep(delay);
			if (this.userDisconnected) {
				this.reconnecting = false;
				return;
			}
			this.notifyReconnectAttempt(attempt);
			try {
				await this.reconnectAndReplay();
				// Recovered.
				this.reconnecting = false;
				this.connectionState = 'connected';
				this.notifyStateChanged();
				this.notifyReconnected();
				return;
			} catch {
				// This attempt failed — fall through to the next iteration.
				// A `userDisconnected` flip between attempts is caught at the
				// top of the loop.
			}
		}
		// Exhausted every attempt — give up to a terminal state so the UI is
		// not stuck "reconnecting". Treated like a final disconnect.
		this.reconnecting = false;
		this.connectionState = 'failed';
		this.notifyStateChanged();
		this.notifyDisconnected();
	}

	/**
	 * One reconnect attempt: reconnect GATT, then re-resolve and replay every
	 * recorded subscription. The characteristic cache is dropped first because
	 * its `BluetoothRemoteGATTCharacteristic` objects are stale after a drop.
	 *
	 * All GATT work goes through the queue, so a reconnect cannot collide with
	 * a write that a manager issued before noticing the drop.
	 */
	private async reconnectAndReplay(): Promise<void> {
		await this.connectGattRaw();
		// The cached characteristics belong to the dead GATT session.
		this.characteristics.clear();
		// Replay subscriptions in their recorded order; each is one queued step
		// so they never overlap.
		for (const sub of this.subscriptions.values()) {
			await this.gattQueue.enqueue(() =>
				this.subscribeRaw(sub.serviceUuid, sub.characteristicUuid)
			);
		}
	}

	private notifyDisconnected(): void {
		this.onDisconnectedListeners.forEach((listener) => listener());
	}

	private notifyStateChanged(): void {
		this.onStateChangedListeners.forEach((listener) => listener(this.connectionState));
	}

	private notifyReconnectAttempt(attempt: number): void {
		this.onReconnectAttemptListeners.forEach((listener) => listener(attempt));
	}

	private notifyReconnected(): void {
		this.onReconnectedListeners.forEach((listener) => listener());
	}
}

/** Resolve after `ms` milliseconds — the reconnect loop's backoff wait. */
function sleep(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
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
