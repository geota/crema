/**
 * `#lib/ble/grinder.js` — Web Bluetooth manager for a connected grinder
 * (beta; spec `.scratch/grinder-ble/SPEC.md` §2.6–2.8, phase G2b).
 *
 * The scale manager's sibling, with the same patterns: a name-prefix chooser
 * from a click, the shared `BleDevice` transport (one GATT queue, the
 * reconnect ladder, advertisement watch), a connect program on the app
 * runtime, a firmware-flash hold, and "only while current" callbacks so a
 * replaced device's late events never touch the new link.
 *
 * What differs from a scale:
 *  - The core rebuilds the driver on every connect and reconnect, so a
 *    recovered link runs the handshake again ({@link grinderHandshakeProgram})
 *    and any drop tells the core first (`disconnectGrinder`: a grind cut off
 *    ends as `LinkLost`).
 *  - Ready is the core's `GrinderReady` (the first valid reading after the
 *    handshake), not the end of the connect program: until then the state
 *    stays `subscribing` ("Waiting for the grinder…").
 *  - `GrinderLinkDead` drops the link into the reconnect ladder; on a first
 *    connect that never answered (`NoFirstReading`) it gives up instead, so
 *    the user can close the vendor's app and try again (§2.7).
 *  - `GrinderUnsupported` (firmware too old) ends the link with no retry.
 *
 * The manager owns no protocol: the UUIDs and the handshake come from the
 * core. Notifications go to the core whole and are never logged here; the
 * core records them in the capture through the driver's redaction (the
 * serial and Wi-Fi name never reach a capture or a log).
 */

import { parseConnectExit } from './connect-exit.ts';
import type { CoreOutput, CremaCore, GrinderUuids } from '#lib/core/index.js';
import { GrinderLinkDeadReason } from '#lib/core/index.js';
import { describeError } from '#lib/utils/error.js';
import { isCoreStopped } from '#lib/core/stopped-guard.js';
import type { AppRuntime } from '#lib/effect/runtime.js';
import {
	grinderConnectProgram,
	grinderHandshakeProgram
} from '#lib/services/grinder-orchestrator.js';
import type { BleConnectionState } from './connection-state';
import {
	BleDevice,
	isChooserCancelled,
	requestDevice,
	advertisingStatus,
	droppedNotificationsStatus,
	type KickResult
} from './transport';
import type { ReconnectTrigger } from './reconnect-timeline';
import { deviceScanFilters } from '#lib/wasm/de1_wasm.js';

/** The core's pre-connect filters for one device kind. */
interface ScanFilters {
	name_prefixes: string[];
	service_uuids: string[];
}

/**
 * The grinder kind's scan filters, from the core (`deviceScanFilters`), so the
 * Grinder chooser lists no scales (§2.2). A free wasm fn: synchronous, so the
 * chooser opens inside the click's user activation.
 */
function grinderScanFilters(): ScanFilters {
	return JSON.parse(deviceScanFilters('Grinder')) as ScanFilters;
}

/** Why the grinder link ended with no retry, for the Devices card (§2.7). */
export type GrinderProblem =
	/** The chooser was closed: nothing picked (or nothing to pick). */
	| { kind: 'not-found' }
	/** Connected, but no reading came within 5 s of the handshake. */
	| { kind: 'no-answer' }
	/**
	 * It answered before, but {@link NO_DATA_GIVE_UP} reconnects in a row
	 * came up with no reading: auto-reconnect stopped, the user reconnects.
	 */
	| { kind: 'no-data' }
	/** Its firmware is older than Crema needs. */
	| { kind: 'firmware'; firmware: string; minimum: string }
	/** The connect failed at a step. */
	| { kind: 'failed'; step: string; message: string };

/**
 * A link that comes back but never streams would otherwise recycle every
 * 5–6 s forever: after this many `NoFirstReading`s in a row it stops.
 */
export const NO_DATA_GIVE_UP = 3;

/** Callbacks the grinder manager reports up to the app. */
export interface GrinderManagerCallbacks {
	/** A `CoreOutput` to fold into state (readings, a cut-off grind). */
	onCoreOutput: (output: CoreOutput) => void;
	/** A human-readable status line (the event log too). */
	onStatus: (line: string) => void;
	/** The coarse connection state advanced. */
	onState: (state: BleConnectionState) => void;
	/** The link ended with no retry, or a problem cleared (`null`). */
	onProblem: (problem: GrinderProblem | null) => void;
	/** The core identified the grinder: its model id and advertised name. */
	onIdentified: (model: string, advertisedName: string) => void;
}

export class GrinderManager {
	private device: BleDevice | null = null;
	private uuids: GrinderUuids | null = null;
	/** The advertised name and services from the first connect, for re-identifying. */
	private advertisedName = '';
	private services: readonly string[] = [];
	/** The core holds a driver for this link (cleared on any drop). */
	private driverLive = false;
	/** The grinder answered on this device at least once (a reconnect may retry). */
	private everReady = false;
	/** `NoFirstReading`s in a row since the last reading (see {@link NO_DATA_GIVE_UP}). */
	private silentReconnects = 0;
	/** The grinder auto-reconnect gave up on, for {@link reconnectStalled}. */
	private stalled: BluetoothDevice | null = null;
	private state: BleConnectionState = 'idle';

	/**
	 * A DE1 firmware flash runs: connects are refused and kicks ignored, so no
	 * grinder link competes with the flash for the radio. Set and cleared by
	 * the app (`ScaleFlashPause`, labelled for the grinder), which drops the
	 * link first and reconnects after.
	 */
	heldForFirmwareFlash = false;

	constructor(
		private readonly core: CremaCore,
		private readonly callbacks: GrinderManagerCallbacks,
		private readonly runtime: AppRuntime | null = null,
		private readonly now: () => number = () => performance.now()
	) {}

	/** Pick a grinder in the chooser and connect it. Call from a click. */
	async connect(): Promise<void> {
		if (this.#refuseForFlash()) return;
		this.stalled = null;
		this.silentReconnects = 0;
		this.setState('connecting');
		this.callbacks.onProblem(null);
		this.dropDevice();
		this.callbacks.onStatus('Requesting a grinder…');
		let device: BleDevice;
		try {
			const scan = grinderScanFilters();
			device = await requestDevice({
				filters: scan.name_prefixes.map((namePrefix) => ({ namePrefix })),
				optionalServices: scan.service_uuids
			});
		} catch (error) {
			if (isChooserCancelled(error)) {
				// The chooser shows only grinders that advertise; one held by its
				// maker's app or another phone doesn't (§2.7).
				this.setState('idle');
				this.callbacks.onProblem({ kind: 'not-found' });
				this.callbacks.onStatus('Grinder chooser closed — not connected');
				return;
			}
			this.setState('failed');
			this.callbacks.onProblem({ kind: 'failed', step: 'chooser', message: describeError(error) });
			this.callbacks.onStatus(`Grinder connection failed: ${describeError(error)}`);
			return;
		}
		if (this.#refuseForFlash()) {
			this.setState('idle');
			return;
		}
		await this.attach(device);
	}

	/** Connect a grinder this site may already use (the launch reconnect, no chooser). */
	async connectKnown(known: BluetoothDevice): Promise<void> {
		if (this.#refuseForFlash()) return;
		this.stalled = null;
		this.silentReconnects = 0;
		this.setState('connecting');
		this.callbacks.onProblem(null);
		this.dropDevice();
		this.callbacks.onStatus(`Reconnecting to the last grinder (${known.name ?? known.id})…`);
		await this.attach(new BleDevice(known));
	}

	#refuseForFlash(): boolean {
		if (!this.heldForFirmwareFlash) return false;
		this.callbacks.onStatus('Grinder waits for the firmware update');
		return true;
	}

	/** The current grinder's Web Bluetooth device (for `forget()`), or null. */
	bluetoothDevice(): BluetoothDevice | null {
		return this.device?.device ?? null;
	}

	/** The connected (or connecting) grinder's browser id and advertised name, or null. */
	identity(): { id: string; name: string | null } | null {
		const d = this.device;
		return d === null ? null : { id: d.id, name: d.device.name ?? null };
	}

	private setState(state: BleConnectionState): void {
		this.state = state;
		this.callbacks.onState(state);
	}

	/** Tell the core the link is gone (once per link): its driver goes, a grind ends. */
	private dropDriver(): void {
		if (!this.driverLive) return;
		this.driverLive = false;
		void this.core
			.disconnectGrinder()
			.then(this.callbacks.onCoreOutput)
			.catch(() => undefined);
	}

	/** Drop the current device and the core's driver (a new connect, a failed one). */
	private dropDevice(): void {
		const device = this.device;
		this.device = null;
		this.uuids = null;
		this.everReady = false;
		this.dropDriver();
		device?.disconnect();
	}

	private async attach(device: BleDevice): Promise<void> {
		this.device = device;
		this.everReady = false;
		this.advertisedName = device.name;
		this.services = [];
		device.setReconnectTimeline('Grinder');
		const current = () => this.device === device;
		device.onDisconnected(() => {
			if (!current()) return;
			this.dropDriver();
			this.setState('disconnected');
			this.callbacks.onStatus('Grinder disconnected');
		});
		// Any drop the transport will recover: the core's driver goes now, so
		// its deadlines stop and a running grind ends as cut off.
		device.onStateChanged((state) => {
			if (!current() || state !== 'reconnecting') return;
			this.dropDriver();
			if (this.state !== 'reconnecting') this.setState('reconnecting');
		});
		device.onReconnectAttempt((attempt) => {
			if (!current()) return;
			this.dropDriver();
			if (this.state !== 'reconnecting') this.setState('reconnecting');
			this.callbacks.onStatus(`Reconnecting to grinder… (attempt ${attempt})`);
		});
		device.onAdvertisement((rssi) => {
			if (current()) this.callbacks.onStatus(advertisingStatus('Grinder', rssi));
		});
		device.onNotificationOverflow((characteristic, evicted) => {
			if (current()) {
				this.callbacks.onStatus(droppedNotificationsStatus('Grinder', characteristic, evicted));
			}
		});
		device.onReconnected(() => {
			if (current()) void this.rehandshake(device);
		});

		if (this.runtime === null) {
			this.dropDevice();
			this.setState('failed');
			this.callbacks.onStatus('Grinder connection failed: app runtime unavailable');
			return;
		}

		this.setState('subscribing');
		const exit = await this.runtime.runPromiseExit(
			grinderConnectProgram({
				device,
				core: this.coreFor(device),
				advertisedName: device.name,
				now: this.now,
				onStatus: (line) => {
					if (current()) this.callbacks.onStatus(line);
				},
				onCoreOutput: (output) => {
					if (current()) this.callbacks.onCoreOutput(output);
				},
				onIdentified: (model, services) => {
					if (!current()) return;
					this.services = services;
					this.callbacks.onIdentified(model, device.name);
				},
				onUuidsResolved: (uuids) => {
					if (current()) this.installSink(device, uuids);
				}
			})
		);
		if (!current()) {
			if (this.device?.id !== device.id) device.disconnect();
			return;
		}
		const outcome = parseConnectExit(exit);
		if (!outcome.ok) {
			this.dropDevice();
			this.setState('failed');
			const message = describeError(outcome.cause);
			this.callbacks.onProblem({ kind: 'failed', step: outcome.step, message });
			this.callbacks.onStatus(`Grinder connection failed at "${outcome.step}": ${message}`);
			return;
		}
		this.callbacks.onStatus('Waiting for the grinder…');
	}

	/**
	 * The core calls a connect program makes, refused once `device` is no
	 * longer current: a stale program must not build a driver for a grinder
	 * the user already disconnected or replaced.
	 */
	private coreFor(device: BleDevice) {
		const stale = () => Promise.reject(new Error('Grinder was disconnected'));
		const current = () => this.device === device;
		return {
			connectGrinder: (name: string, services: readonly string[]) => {
				if (!current()) return stale();
				this.driverLive = true;
				return this.core.connectGrinder(name, services);
			},
			grinderUuids: () => (current() ? this.core.grinderUuids() : stale()),
			grinderSubscribed: (max: number, now: number) =>
				current() ? this.core.grinderSubscribed(max, now) : stale()
		};
	}

	/** Route the notify characteristic to the core; nothing else is the grinder's. */
	private installSink(device: BleDevice, uuids: GrinderUuids): void {
		this.uuids = uuids;
		const notify = uuids.notify.toLowerCase();
		device.setSink((notification) => {
			if (notification.characteristic !== notify || this.device !== device) return;
			void this.core
				.onNotification('GrinderNotify', notification.data, notification.atMs)
				.then((output) => {
					if (this.device === device) this.callbacks.onCoreOutput(output);
				})
				.catch((error: unknown) => {
					if (isCoreStopped(error)) return;
					this.callbacks.onStatus(`Grinder notification processing failed: ${describeError(error)}`);
				});
		});
	}

	/** The link came back: a new driver and handshake on the replayed subscription. */
	private async rehandshake(device: BleDevice): Promise<void> {
		const current = () => this.device === device;
		if (this.runtime === null) return;
		this.setState('subscribing');
		this.callbacks.onStatus('Reconnected — saying hello to the grinder…');
		const exit = await this.runtime.runPromiseExit(
			grinderHandshakeProgram({
				device,
				core: this.coreFor(device),
				advertisedName: this.advertisedName,
				services: this.services,
				now: this.now,
				onStatus: (line) => {
					if (current()) this.callbacks.onStatus(line);
				},
				onCoreOutput: (output) => {
					if (current()) this.callbacks.onCoreOutput(output);
				}
			})
		);
		if (!current()) return;
		const outcome = parseConnectExit(exit);
		if (!outcome.ok) {
			this.callbacks.onStatus(
				`Grinder handshake failed at "${outcome.step}": ${describeError(outcome.cause)} — reconnecting…`
			);
			this.dropDriver();
			this.setState('reconnecting');
			device.recycle();
		}
	}

	/** The core's `GrinderReady`: the grinder answered the handshake. */
	onReady(): void {
		if (this.device === null || this.state === 'ready') return;
		this.everReady = true;
		this.silentReconnects = 0;
		this.callbacks.onProblem(null);
		this.setState('ready');
		this.callbacks.onStatus('Ready — receiving grinder readings');
	}

	/**
	 * The core presumed the link dead (`GrinderLinkDead`). A first connect
	 * that never answered gives up, with the "close the vendor's app" message;
	 * otherwise the link goes to the reconnect ladder (dropped at the
	 * transport, not as a user disconnect). A link already reconnecting is
	 * left to its loop.
	 */
	presumeDead(reason: GrinderLinkDeadReason): void {
		const device = this.device;
		if (device === null || (this.state !== 'ready' && this.state !== 'subscribing')) return;
		if (reason === GrinderLinkDeadReason.NoFirstReading && !this.everReady) {
			this.dropDevice();
			this.setState('failed');
			this.callbacks.onProblem({ kind: 'no-answer' });
			this.callbacks.onStatus("Grinder didn't answer — disconnected");
			return;
		}
		if (reason === GrinderLinkDeadReason.NoFirstReading) {
			this.silentReconnects += 1;
			if (this.silentReconnects >= NO_DATA_GIVE_UP) {
				this.stalled = device.device;
				this.dropDevice();
				this.setState('failed');
				this.callbacks.onProblem({ kind: 'no-data' });
				this.callbacks.onStatus(
					`Grinder not sending data after ${NO_DATA_GIVE_UP} reconnects — stopped reconnecting`
				);
				return;
			}
		}
		const why =
			reason === GrinderLinkDeadReason.Malformed
				? 'unreadable data'
				: reason === GrinderLinkDeadReason.NoFirstReading
					? 'no answer'
					: 'no readings';
		this.callbacks.onStatus(`Grinder unresponsive (${why}) — reconnecting…`);
		this.dropDriver();
		this.setState('reconnecting');
		device.recycle();
	}

	/**
	 * Reconnect the grinder auto-reconnect gave up on (the card's Reconnect),
	 * with no chooser. False when there is none.
	 */
	async reconnectStalled(): Promise<boolean> {
		const known = this.stalled;
		if (known === null) return false;
		await this.connectKnown(known);
		return true;
	}

	/** The core's `GrinderUnsupported`: end the link, no retry (§2.7). */
	unsupported(firmware: string, minimum: string): void {
		if (this.device === null) return;
		this.dropDevice();
		this.setState('failed');
		this.callbacks.onProblem({ kind: 'firmware', firmware, minimum });
		this.callbacks.onStatus(`Grinder firmware ${firmware} is too old (needs ${minimum}) — disconnected`);
	}

	/** Attempt a pending reconnect now (the tab is back in view, "Retry now"). */
	kick(trigger: ReconnectTrigger): KickResult {
		if (this.heldForFirmwareFlash) return 'ignored-idle';
		return this.device?.kick(trigger) ?? 'ignored-idle';
	}

	/** Disconnect the grinder (the user, a flash, a firmware refusal). */
	async disconnect(): Promise<void> {
		const device = this.device;
		this.device = null;
		this.uuids = null;
		this.everReady = false;
		device?.disconnect();
		if (this.driverLive) {
			this.driverLive = false;
			try {
				this.callbacks.onCoreOutput(await this.core.disconnectGrinder());
			} catch {
				// Best effort: the teardown never waits on the core.
			}
		}
		this.setState('disconnected');
		this.callbacks.onStatus('Grinder disconnected');
	}

	/**
	 * Write the core's `WriteGrinder` bytes. The connect program writes the
	 * handshake itself; this is for any later command the core sends, and
	 * only while a driver is live on a connected link.
	 */
	async writeGrinder(data: Uint8Array): Promise<void> {
		const device = this.device;
		const uuids = this.uuids;
		if (device === null || uuids === null || !this.driverLive || device.connectionState !== 'connected') {
			this.callbacks.onStatus('Grinder write not sent — the grinder is not connected');
			return;
		}
		try {
			await device.write(uuids.service, uuids.write, data, uuids.write_with_response);
		} catch (error) {
			this.callbacks.onStatus(`Grinder write was rejected: ${describeError(error)}`);
		}
	}
}
