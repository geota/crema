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
import {
	reconnectTimelines,
	type ReconnectTimelineRecorder,
	type ReconnectTrigger
} from './reconnect-timeline';

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
 * Fast-burst reconnect attempts before the persistent slow tier takes over.
 * With a base of 500 ms doubling to a 30 s cap, eight attempts span roughly
 * two minutes of eager retrying.
 */
const RECONNECT_MAX_ATTEMPTS = 8;

/**
 * Cadence of the persistent slow tier after the burst — the loop keeps trying
 * quietly forever (Decenza's scale ladder ends in the same 60 s forever-tier;
 * de1app retries continuously), so a machine or scale that comes back in
 * range / powers back on reconnects without a tap. Android's supervisor uses
 * the same value.
 */
const RECONNECT_LURK_DELAY_MS = 60_000;

/**
 * In the lurk tier, where the browser can watch advertisements, a cycle that
 * heard none skips its connect attempt — a device that isn't advertising is
 * off, out of range or held by another central, and on iOS each blind
 * `gatt.connect()` costs a 10 s timeout three times over. Every Nth quiet
 * cycle still tries blind, in case advertisement delivery itself is broken.
 */
const LURK_BLIND_EVERY = 5;

/** Dropped-notification reports are summed over this window, ms. */
const OVERFLOW_REPORT_MS = 10_000;

/**
 * Longest one `gatt.connect()` may run. Web Bluetooth sets no limit and some
 * platforms (Chrome on macOS / Windows) can leave a connect pending forever,
 * which would hold the serial GATT queue — and the reconnect loop — with it.
 */
const GATT_CONNECT_TIMEOUT_MS = 20_000;

/** Longest any other GATT operation (lookup, subscribe, read, write) may run. */
const GATT_OP_TIMEOUT_MS = 10_000;

/** Exported for tests. */
export const GATT_TIMEOUTS = {
	connectMs: GATT_CONNECT_TIMEOUT_MS,
	operationMs: GATT_OP_TIMEOUT_MS
} as const;

/** A GATT operation that never settled within its time limit. */
export class GattTimeoutError extends Error {
	override name = 'GattTimeoutError';
}

/** `p`, or a {@link GattTimeoutError} once `ms` pass first. */
function withGattTimeout<T>(p: Promise<T>, ms: number, what: string): Promise<T> {
	return new Promise<T>((resolve, reject) => {
		const timer = setTimeout(
			() => reject(new GattTimeoutError(`${what} timed out after ${ms / 1000} s`)),
			ms
		);
		p.then(
			(value) => {
				clearTimeout(timer);
				resolve(value);
			},
			(error: unknown) => {
				clearTimeout(timer);
				reject(error);
			}
		);
	});
}

/**
 * Kicks closer together than this collapse into one (rapid tab switches, a
 * `visibilitychange` and a `pageshow` for the same return, a "Retry now" tap
 * right after). Android's `ReconnectKicker` uses the same window.
 */
export const KICK_DEBOUNCE_MS = 1_500;

/** What {@link BleDevice.kick} did. */
export type KickResult =
	/** The backoff / lurk wait was cut short; an attempt starts now. */
	| 'kicked'
	/** An attempt is already running; the fast burst resets when it ends. */
	| 'reset-pending'
	| 'ignored-connected'
	| 'ignored-idle'
	| 'debounced';

/** Exported for tests: the reconnect ladder's wait before `attempt` (1-based). */
export function reconnectDelayMs(attempt: number): number {
	return attempt > RECONNECT_MAX_ATTEMPTS
		? RECONNECT_LURK_DELAY_MS
		: Math.min(RECONNECT_BASE_DELAY_MS * 2 ** (attempt - 1), RECONNECT_MAX_DELAY_MS);
}

/** Exported for tests. */
export const RECONNECT_LADDER = {
	maxAttempts: RECONNECT_MAX_ATTEMPTS,
	lurkDelayMs: RECONNECT_LURK_DELAY_MS,
	lurkBlindEvery: LURK_BLIND_EVERY
} as const;

/** How a reconnect wait ended — see `BleDevice.waitOrKick`. */
interface WaitOutcome {
	/** The kick that cut the wait short, or null. */
	readonly kickedBy: ReconnectTrigger | null;
	/** An advertisement from the device ended the wait. */
	readonly advertised: boolean;
	/** The browser watched advertisements for the whole wait. */
	readonly watched: boolean;
}

/** A GATT server with Beacio's one-round-trip connect + discover (iOS only). */
type FastConnectServer = BluetoothRemoteGATTServer & {
	connectAndDiscover?: (services: BluetoothServiceUUID[]) => Promise<unknown>;
};

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

	/** Aborts the reconnect loop's current backoff / lurk wait — see {@link kick}. */
	private waitAbort: AbortController | null = null;

	/** The trigger of the kick that aborted {@link waitAbort}. */
	private kickTrigger: ReconnectTrigger | null = null;

	/** A kick that landed mid-attempt: reset the burst when the attempt ends. */
	private resetRequested = false;

	/** `performance.now()` of the last accepted kick, for the debounce. */
	private lastKickAtMs: number | null = null;

	/** The reconnect-timeline key ("DE1" / "Scale"); null = don't record. */
	private timelineLabel: string | null = null;

	/** Whether the owner marks `post-connect` after READY (the DE1's profile sync). */
	private timelineExpectsPostConnect = false;

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
			// Respect the DataView's window (review #34): Web Bluetooth hands
			// back zero-offset views today, but slicing the whole backing
			// buffer decodes garbage the moment that assumption breaks. Same
			// shape readCharacteristic uses.
			data: new Uint8Array(value.buffer, value.byteOffset, value.byteLength).slice(),
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

	private readonly onAdvertisementListeners = new Set<(rssi: number | null) => void>();

	private readonly onOverflowListeners = new Set<
		(characteristic: string, evicted: number) => void
	>();

	/**
	 * Safari (via Beacio) drops notifications under sustained load and says so
	 * with a `beacio:overflow` event on the characteristic. Surfaced, not
	 * recovered: the DE1 and scale streams are state snapshots, so the next
	 * notification resynchronises.
	 */
	private readonly onOverflow = (event: Event): void => {
		const target = event.target as BluetoothRemoteGATTCharacteristic;
		const detail = (event as CustomEvent<{ evictedCount?: number } | null>).detail;
		const evicted = typeof detail?.evictedCount === 'number' ? detail.evictedCount : 1;
		const uuid = target.uuid.toLowerCase();
		this.droppedSinceReport.set(uuid, (this.droppedSinceReport.get(uuid) ?? 0) + evicted);
		// Report the first drop at once, then at most one summed report per
		// window, so a sustained overload can't flood the event log.
		if (this.overflowTimer !== null) return;
		this.reportDropped();
		this.overflowTimer = setTimeout(() => {
			this.overflowTimer = null;
			this.reportDropped();
		}, OVERFLOW_REPORT_MS);
	};

	/** Notifications dropped since the last report, by characteristic. */
	private readonly droppedSinceReport = new Map<string, number>();

	private overflowTimer: ReturnType<typeof setTimeout> | null = null;

	private reportDropped(): void {
		for (const [uuid, evicted] of this.droppedSinceReport) {
			this.onOverflowListeners.forEach((l) => l(uuid, evicted));
		}
		this.droppedSinceReport.clear();
	}

	constructor(
		/** The underlying Web Bluetooth device. */
		readonly device: BluetoothDevice,
		/** Where reconnect episodes are recorded (the app-wide recorder by default). */
		private readonly timeline: ReconnectTimelineRecorder = reconnectTimelines,
		/** Monotonic clock for the kick debounce. */
		private readonly nowMs: () => number = () => performance.now()
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
		if (this.userDisconnected) return; // single-use: a stale program can't re-arm it
		this.sink = sink;
	}

	/**
	 * Register a callback for a *terminal* disconnect — a user-initiated
	 * {@link disconnect} (the backoff loop never gives up on its own). Not
	 * fired for a transient drop that the loop recovers.
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
	 * Register a callback for each advertisement the reconnect loop hears while
	 * watching for this device, with its RSSI (dBm, or null).
	 */
	onAdvertisement(listener: (rssi: number | null) => void): void {
		this.onAdvertisementListeners.add(listener);
	}

	/** Register a callback for notifications the browser dropped (Safari / Beacio). */
	onNotificationOverflow(listener: (characteristic: string, evicted: number) => void): void {
		this.onOverflowListeners.add(listener);
	}

	/**
	 * Record this device's reconnect episodes under `label` ("DE1" / "Scale").
	 * `expectPostConnect`: the owner marks `post-connect` once its after-READY
	 * work (the DE1's profile sync) is done.
	 */
	setReconnectTimeline(label: string, expectPostConnect = false): void {
		this.timelineLabel = label;
		this.timelineExpectsPostConnect = expectPostConnect;
	}

	/**
	 * Attempt a reconnect NOW — the tab came back into view, or the user
	 * tapped "Retry now". Cuts the backoff / 60 s lurk wait short and resets
	 * the ladder to the fast burst. Rules (pinned by `transport.vitest.ts`):
	 * a no-op while connected or after a deliberate {@link disconnect};
	 * debounced; and never a second concurrent attempt — a kick mid-attempt
	 * only asks for the burst to restart when that attempt ends.
	 */
	kick(trigger: ReconnectTrigger): KickResult {
		if (this.userDisconnected) return 'ignored-idle';
		if (!this.reconnecting) {
			return this.connectionState === 'connected' ? 'ignored-connected' : 'ignored-idle';
		}
		const now = this.nowMs();
		if (this.lastKickAtMs !== null && now - this.lastKickAtMs < KICK_DEBOUNCE_MS) {
			return 'debounced';
		}
		this.lastKickAtMs = now;
		if (this.waitAbort !== null) {
			this.kickTrigger = trigger;
			this.waitAbort.abort();
			return 'kicked';
		}
		this.resetRequested = true;
		return 'reset-pending';
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
		if (this.userDisconnected || !gatt?.connected) return [];
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
	private connectGattRaw(services: readonly string[] = []): Promise<void> {
		return this.gattQueue.enqueue(async () => {
			const gatt = this.device.gatt as FastConnectServer | undefined;
			if (gatt === undefined) {
				throw new Error('Device exposes no GATT server');
			}
			// A disconnected device never connects again — checked before the
			// shared `gatt` too: a replacement device on the same DE1 may have
			// connected it (audit r10 L-1).
			this.assertLive();
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
				// No retry after a deliberate disconnect: it would land after a
				// replacement connect, and the reconnect loop would then drop the
				// shared link (audit r10 L-1).
				this.assertLive();
				try {
					// Beacio's iOS bridge can connect and discover the services we
					// will replay in one round-trip through the extension, instead
					// of a connect then a lookup per service.
					const connecting =
						services.length > 0 && typeof gatt.connectAndDiscover === 'function'
							? gatt.connectAndDiscover([...services])
							: gatt.connect();
					await withGattTimeout(connecting, GATT_CONNECT_TIMEOUT_MS, 'GATT connect');
					return;
				} catch (error) {
					if (error instanceof GattTimeoutError) {
						// Abandon the hung connect (Chromium cancels a pending
						// connect on `disconnect()`). No quick retry: those are for
						// the flaky-but-fast failures; the reconnect loop's backoff
						// takes it from here.
						gatt.disconnect();
						throw error;
					}
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
			this.bounded('characteristic lookup', () => {
				this.assertLive();
				return this.resolveCharacteristic(serviceUuid, characteristicUuid);
			})
		);
	}

	/**
	 * Run one GATT operation under {@link GATT_OP_TIMEOUT_MS}, timed from when
	 * it starts (inside the queue), not when it was queued. An operation that
	 * never settles means the link is wedged: drop it, which frees the queue
	 * and hands recovery to the reconnect loop like any other drop.
	 */
	private async bounded<T>(what: string, operation: () => Promise<T>): Promise<T> {
		try {
			return await withGattTimeout(operation(), GATT_OP_TIMEOUT_MS, what);
		} catch (error) {
			if (error instanceof GattTimeoutError) {
				const gatt = this.device.gatt;
				if (gatt?.connected) gatt.disconnect();
			}
			throw error;
		}
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
			this.bounded('subscribe', () => {
				this.assertLive();
				return this.subscribeRaw(serviceUuid, characteristicUuid);
			})
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
			characteristic.addEventListener('beacio:overflow', this.onOverflow);
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
		return this.gattQueue.enqueue(() => this.bounded('write', async () => {
			this.assertLive();
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
		}));
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
		return this.gattQueue.enqueue(() => this.bounded('read', async () => {
			this.assertLive();
			let characteristic = this.characteristics.get(characteristicUuid.toLowerCase());
			if (characteristic === undefined) {
				characteristic = await this.resolveCharacteristic(
					serviceUuid,
					characteristicUuid
				);
			}
			const view = await characteristic.readValue();
			return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
		}));
	}

	/**
	 * A device is single-use after {@link disconnect}: every public GATT
	 * operation (connect, subscribe, read, write, characteristic lookup)
	 * refuses and service discovery reports nothing, so a stale connect
	 * program that outlives its device can't touch the DE1 / scale or feed
	 * the core (audit r10–r12). The reconnect loop's private replay is not
	 * gated, so it can still close its own ghost link.
	 */
	private assertLive(): void {
		if (this.userDisconnected) throw new Error('Device was disconnected');
	}

	/**
	 * Disconnect the GATT server and drop the device's listeners. Idempotent.
	 *
	 * Marks the teardown *deliberate*, which suppresses the auto-reconnect loop
	 * — a user-initiated disconnect must stay disconnected.
	 */
	disconnect(): void {
		this.userDisconnected = true;
		if (this.reconnecting && this.timelineLabel !== null) {
			this.timeline.end(this.timelineLabel, 'cancelled');
		}
		this.reconnecting = false;
		// End a pending backoff wait now rather than leaving a timer to fire.
		this.waitAbort?.abort();
		// Also when not connected: on current Chromium `disconnect()` aborts a
		// GATT connect still in flight (a reconnect attempt); `connectGattRaw`
		// starts no new one once `userDisconnected` is set (audit r9 / r10 L-1).
		const gatt = this.device.gatt;
		if (gatt !== undefined) {
			gatt.disconnect();
		}
		// A device is single-use after a disconnect: drop its sink (and refuse
		// a new one, see setSink) so a link that connected anyway (a browser
		// that doesn't cancel a pending connect, or characteristics shared
		// with a replacement on the same device) feeds nothing more (audit r11
		// L-1).
		this.sink = null;
		if (this.overflowTimer !== null) clearTimeout(this.overflowTimer);
		this.overflowTimer = null;
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
	 * After {@link RECONNECT_MAX_ATTEMPTS} failed fast attempts the loop drops
	 * to a quiet persistent tier — one attempt every
	 * {@link RECONNECT_LURK_DELAY_MS} for as long as the session lasts — rather
	 * than stranding the device until a manual tap (Android's supervisor has
	 * the same tier; Decenza's ladders end in a 60 s / 5 min forever-tier and
	 * de1app retries continuously). Attempt notifications stop after the burst
	 * so the status line isn't spammed every cycle; the `reconnecting` state
	 * (and its pip) persists. Only a deliberate disconnect ends the loop.
	 */
	private async runReconnectLoop(): Promise<void> {
		this.reconnecting = true;
		const label = this.timelineLabel;
		if (label !== null) this.timeline.begin(label, 'drop');
		// Clamped so days of slow retries can't grow the counter without bound.
		let attempt = 0;
		// Whether this outage has been announced (attempt 1 tells the core
		// the link was lost) — a kick before the first backoff ends must not
		// skip that.
		let announced = false;
		// Lurk cycles in a row that watched for the device and heard nothing.
		let quietCycles = 0;
		while (true) {
			// A deliberate disconnect during the wait aborts the loop.
			if (this.userDisconnected) {
				this.reconnecting = false;
				return;
			}
			// A kick that landed while the previous attempt ran couldn't start a
			// second attempt — it asked for the fast burst back instead.
			if (this.resetRequested) {
				this.resetRequested = false;
				attempt = 0;
			}
			attempt = Math.min(attempt + 1, RECONNECT_MAX_ATTEMPTS + 1);
			let lurking = attempt > RECONNECT_MAX_ATTEMPTS;
			if (label !== null) this.timeline.mark(label, 'backoff');
			// The wait is interruptible: a kick (the tab coming back into view,
			// "Retry now") ends it early — attempt now, then 500 ms, 1 s, … —
			// instead of sitting out a lurk timer Chrome throttles in hidden tabs.
			// In the lurk tier it also listens for the device's advertisements:
			// one ends the wait at once (the machine was switched back on).
			const waited = await this.waitOrKick(reconnectDelayMs(attempt), lurking);
			if (this.userDisconnected) {
				this.reconnecting = false;
				return;
			}
			const kickedBy = waited.kickedBy;
			if (kickedBy !== null) {
				attempt = 0;
				quietCycles = 0;
				lurking = true; // a kicked attempt isn't a numbered burst step
				if (label !== null) this.timeline.begin(label, kickedBy);
			} else if (waited.advertised) {
				quietCycles = 0;
			} else if (lurking && waited.watched) {
				// A whole lurk cycle without hearing the device: don't spend a
				// blind connect on it, except every LURK_BLIND_EVERY-th time.
				quietCycles += 1;
				if (quietCycles % LURK_BLIND_EVERY !== 0) continue;
			}
			if (!lurking) {
				this.notifyReconnectAttempt(attempt);
				announced = true;
			} else if (!announced) {
				this.notifyReconnectAttempt(1);
				announced = true;
			}
			if (label !== null) this.timeline.attemptStarted(label);
			try {
				await this.reconnectAndReplay();
				// A user disconnect landed WHILE this attempt was in flight —
				// it couldn't cancel the promise, so honour it now instead of
				// resurrecting the link as a ghost session (review #34).
				if (this.userDisconnected) {
					this.reconnecting = false;
					this.disconnect();
					return;
				}
				// Recovered.
				this.reconnecting = false;
				this.resetRequested = false;
				this.connectionState = 'connected';
				if (label !== null) this.timeline.ready(label, this.timelineExpectsPostConnect);
				this.notifyStateChanged();
				this.notifyReconnected();
				return;
			} catch (error) {
				// This attempt failed — fall through to the next iteration.
				// A `userDisconnected` flip between attempts is caught at the
				// top of the loop.
				if (label !== null) {
					this.timeline.attemptFailed(
						label,
						error instanceof Error ? error.message : String(error)
					);
				}
			}
		}
	}

	/**
	 * Wait `ms`, or less if {@link kick} cuts it short — or, with
	 * `watchAdverts` where the browser supports `watchAdvertisements()`, as soon
	 * as the device advertises. `watched` is true only when the browser was
	 * still watching at the end (it stops when the page is hidden), so a quiet
	 * cycle it didn't watch through still gets its blind attempt.
	 */
	private async waitOrKick(ms: number, watchAdverts = false): Promise<WaitOutcome> {
		const abort = new AbortController();
		this.waitAbort = abort;
		this.kickTrigger = null;
		const stopWatch = new AbortController();
		let advertised = false;
		let watching = false;
		const onAdvert = (event: Event): void => {
			const rssi = (event as Event & { rssi?: number | null }).rssi;
			this.onAdvertisementListeners.forEach((l) => l(typeof rssi === 'number' ? rssi : null));
			if (abort.signal.aborted) return;
			advertised = true;
			abort.abort();
		};
		const device = this.device;
		const listening = watchAdverts && typeof device.watchAdvertisements === 'function';
		if (listening) {
			device.addEventListener('advertisementreceived', onAdvert);
			try {
				await device.watchAdvertisements({ signal: stopWatch.signal });
				watching = true;
			} catch {
				// Not permitted or not supported here: a plain timed wait.
			}
		}
		let watched = false;
		try {
			await abortableSleep(ms, abort.signal);
		} finally {
			this.waitAbort = null;
			watched = watching && device.watchingAdvertisements !== false;
			if (listening) {
				stopWatch.abort();
				device.removeEventListener('advertisementreceived', onAdvert);
			}
		}
		const kickedBy = abort.signal.aborted && !advertised ? this.kickTrigger : null;
		this.kickTrigger = null;
		return { kickedBy, advertised, watched };
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
		const label = this.timelineLabel;
		if (label !== null) this.timeline.mark(label, 'gatt');
		const services = new Set([...this.subscriptions.values()].map((s) => s.serviceUuid));
		await this.connectGattRaw([...services]);
		// The cached characteristics belong to the dead GATT session.
		this.characteristics.clear();
		// Web Bluetooth discovers services lazily, so resolving + subscribing
		// below is one span.
		if (label !== null) this.timeline.mark(label, 'subscribe');
		// Replay subscriptions in their recorded order; each is one queued step
		// so they never overlap.
		for (const sub of this.subscriptions.values()) {
			await this.gattQueue.enqueue(() =>
				this.bounded('resubscribe', () =>
					this.subscribeRaw(sub.serviceUuid, sub.characteristicUuid)
				)
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

/**
 * Resolve after `ms` milliseconds, or as soon as `signal` aborts — the
 * reconnect loop's interruptible backoff wait. Never rejects.
 */
export function abortableSleep(ms: number, signal: AbortSignal): Promise<void> {
	return new Promise((resolve) => {
		if (signal.aborted) {
			resolve();
			return;
		}
		const timer = setTimeout(done, ms);
		function done(): void {
			clearTimeout(timer);
			signal.removeEventListener('abort', done);
			resolve();
		}
		signal.addEventListener('abort', done, { once: true });
	});
}

/** `0000a00d-0000-1000-8000-00805f9b34fb` → `A00D`, for log lines. */
export function shortUuid(uuid: string): string {
	return /^0000[0-9a-f]{4}-0000-1000-8000-00805f9b34fb$/i.test(uuid)
		? uuid.slice(4, 8).toUpperCase()
		: uuid;
}

/**
 * Whether `requestDevice()` rejected because the user dismissed the chooser
 * (Chrome: `NotFoundError` "User cancelled the requestDevice() chooser.") —
 * a choice, not a failure. Other `NotFoundError`s (no adapter, Bluetooth off)
 * stay failures.
 */
export function isChooserCancelled(error: unknown): boolean {
	const e = error as { name?: unknown; message?: unknown } | null;
	return (
		(e?.name === 'NotFoundError' || e?.name === 'AbortError') &&
		typeof e.message === 'string' &&
		/cancel/i.test(e.message)
	);
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
