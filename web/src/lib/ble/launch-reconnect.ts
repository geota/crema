/**
 * `$lib/ble/launch-reconnect` — reconnect the DE1 and scale the user last
 * connected, automatically, when the app starts.
 *
 * Web Bluetooth only opens its device chooser from a click, so a PWA can't
 * scan for a machine at launch the way Android does. Where the browser
 * exposes `navigator.bluetooth.getDevices()` (the devices this site was
 * already allowed to use), it can find the remembered device without a
 * chooser, wait for it to advertise (`watchAdvertisements()`, when present)
 * and connect. Chrome keeps `getDevices()` behind
 * `chrome://flags/#enable-experimental-web-platform-features` (plus
 * `#enable-web-bluetooth-new-permissions-backend` for permissions that
 * survive a restart); Firefox and Safari have no Web Bluetooth at all.
 * Without it this is a silent no-op — no UI, no setting, no message.
 *
 * ## What's remembered
 *
 * Every successful connect stores the device's identity (`BluetoothDevice.id`
 * + name) per device under {@link LAST_DEVICES_KEY}, through the shared
 * `$lib/utils/storage` helpers. It's device-local (the browser's id is
 * per-origin, per-profile), so it is not part of a backup. A deliberate
 * disconnect (rail menu, Settings) clears `auto` so the next launch leaves
 * that device alone until the user connects it again; an unexpected drop
 * keeps it eligible.
 */

import { isJsonObject, readJson, writeJson } from '$lib/utils/storage';

/** The two devices the app connects. */
export type DeviceKind = 'de1' | 'scale';

/** The browser's identity for a connected device. */
export interface DeviceIdentity {
	readonly id: string;
	readonly name: string | null;
}

/** A remembered device: its identity, and whether launch may reconnect it. */
export interface RememberedDevice extends DeviceIdentity {
	/** False after a user-initiated disconnect, until the next connect. */
	readonly auto: boolean;
}

export type LastDevices = Record<DeviceKind, RememberedDevice | null>;

/** localStorage key for the last-connected devices (not in backups). */
export const LAST_DEVICES_KEY = 'crema.ble.lastDevices.v1';

/** How long launch waits for the remembered device to advertise, ms. */
export const ADVERTISEMENT_TIMEOUT_MS = 30_000;

function isRemembered(v: unknown): v is RememberedDevice {
	return (
		isJsonObject(v) &&
		typeof v.id === 'string' &&
		(v.name === null || typeof v.name === 'string') &&
		typeof v.auto === 'boolean'
	);
}

/** Read the remembered devices (missing / malformed entries read as null). */
export function readLastDevices(): LastDevices {
	const raw = readJson<Record<string, unknown> | null>(LAST_DEVICES_KEY, null, {
		valid: isJsonObject
	});
	return {
		de1: raw && isRemembered(raw.de1) ? raw.de1 : null,
		scale: raw && isRemembered(raw.scale) ? raw.scale : null
	};
}

function writeEntry(kind: DeviceKind, entry: RememberedDevice): void {
	const prev = readLastDevices();
	const cur = prev[kind];
	if (cur && cur.id === entry.id && cur.name === entry.name && cur.auto === entry.auto) return;
	writeJson(LAST_DEVICES_KEY, { ...prev, [kind]: entry });
}

/** A successful connect: remember the device and make it eligible again. */
export function rememberDevice(kind: DeviceKind, device: DeviceIdentity): void {
	writeEntry(kind, { id: device.id, name: device.name, auto: true });
}

/** A user-initiated disconnect: keep the identity, but don't auto-reconnect it. */
export function markUserDisconnect(kind: DeviceKind): void {
	const cur = readLastDevices()[kind];
	if (cur) writeEntry(kind, { ...cur, auto: false });
}

/** Whether this browser can reconnect on launch (`navigator.bluetooth.getDevices`). */
export function supportsLaunchReconnect(bluetooth: Bluetooth | undefined): boolean {
	return bluetooth !== undefined && typeof bluetooth.getDevices === 'function';
}

/** Find the remembered device among the permitted ones: by id, else by name. */
export function findRemembered(
	devices: readonly BluetoothDevice[],
	remembered: DeviceIdentity
): BluetoothDevice | null {
	return (
		devices.find((d) => d.id === remembered.id) ??
		(remembered.name !== null ? devices.find((d) => d.name === remembered.name) : undefined) ??
		null
	);
}

/** What the launch pass did for one device. */
export type LaunchOutcome =
	| 'unsupported'
	| 'not-remembered'
	| 'not-found'
	| 'aborted'
	| 'connecting';

/**
 * Wait for `device`'s first advertisement. Resolves `'advertised'`,
 * `'timeout'`, `'aborted'` (the `signal` fired), or `'unavailable'` (the
 * browser refused to watch — the caller then tries a connect directly).
 */
async function waitForAdvertisement(
	device: BluetoothDevice,
	signal: AbortSignal,
	timeoutMs: number
): Promise<'advertised' | 'timeout' | 'aborted' | 'unavailable'> {
	const watch = new AbortController();
	let resolve!: (v: 'advertised' | 'timeout' | 'aborted') => void;
	const outcome = new Promise<'advertised' | 'timeout' | 'aborted'>((r) => (resolve = r));
	const onAdvert = (): void => resolve('advertised');
	const onAbort = (): void => resolve('aborted');
	const timer = setTimeout(() => resolve('timeout'), timeoutMs);
	device.addEventListener('advertisementreceived', onAdvert);
	signal.addEventListener('abort', onAbort);
	const cleanup = (): void => {
		clearTimeout(timer);
		device.removeEventListener('advertisementreceived', onAdvert);
		signal.removeEventListener('abort', onAbort);
		watch.abort();
	};
	try {
		await device.watchAdvertisements({ signal: watch.signal });
	} catch {
		cleanup();
		return 'unavailable';
	}
	const result = await outcome;
	cleanup();
	return result;
}

/**
 * Reconnect one remembered device at launch: look it up among the permitted
 * devices and hand it to `connect` — after its first advertisement when the
 * browser can watch for one (giving up after `timeoutMs`), straight away
 * otherwise. `signal` cancels the wait (the user connected or disconnected
 * meanwhile).
 */
export async function reconnectOnLaunch(opts: {
	bluetooth: Bluetooth | undefined;
	remembered: RememberedDevice | null;
	connect: (device: BluetoothDevice) => Promise<void> | void;
	signal: AbortSignal;
	timeoutMs?: number;
}): Promise<LaunchOutcome> {
	const { bluetooth, remembered, connect, signal } = opts;
	if (!supportsLaunchReconnect(bluetooth)) return 'unsupported';
	if (remembered === null || !remembered.auto) return 'not-remembered';
	let devices: BluetoothDevice[];
	try {
		devices = await bluetooth!.getDevices();
	} catch {
		return 'not-found';
	}
	if (signal.aborted) return 'aborted';
	const device = findRemembered(devices, remembered);
	if (device === null) return 'not-found';
	if (typeof device.watchAdvertisements === 'function') {
		const seen = await waitForAdvertisement(
			device,
			signal,
			opts.timeoutMs ?? ADVERTISEMENT_TIMEOUT_MS
		);
		if (seen === 'timeout') return 'not-found';
		if (seen === 'aborted') return 'aborted';
	}
	if (signal.aborted) return 'aborted';
	await connect(device);
	return 'connecting';
}

/**
 * The app's launch-reconnect pass for both devices, plus the cancel hook a
 * user Connect / Disconnect calls while a wait is pending.
 */
export class LaunchReconnect {
	readonly #pending: Record<DeviceKind, AbortController | null> = { de1: null, scale: null };

	/** Stop a pending launch reconnect for `kind` (the user took over). */
	cancel(kind: DeviceKind): void {
		this.#pending[kind]?.abort();
		this.#pending[kind] = null;
	}

	/** Whether a launch wait is pending for `kind` (tests). */
	isPending(kind: DeviceKind): boolean {
		return this.#pending[kind] !== null;
	}

	/**
	 * Run the pass: for each device remembered as eligible and still idle,
	 * reconnect it via `connect`. Best effort; a no-op without `getDevices`.
	 */
	async run(opts: {
		bluetooth: Bluetooth | undefined;
		isIdle: (kind: DeviceKind) => boolean;
		connect: (kind: DeviceKind, device: BluetoothDevice) => Promise<void>;
		timeoutMs?: number;
	}): Promise<Record<DeviceKind, LaunchOutcome>> {
		const { bluetooth } = opts;
		if (!supportsLaunchReconnect(bluetooth)) return { de1: 'unsupported', scale: 'unsupported' };
		const last = readLastDevices();
		const [de1, scale] = await Promise.all(
			(['de1', 'scale'] as const).map(async (kind): Promise<LaunchOutcome> => {
				const remembered = last[kind];
				if (remembered === null || !remembered.auto || !opts.isIdle(kind)) return 'not-remembered';
				const abort = new AbortController();
				this.#pending[kind] = abort;
				try {
					return await reconnectOnLaunch({
						bluetooth,
						remembered,
						signal: abort.signal,
						timeoutMs: opts.timeoutMs,
						connect: async (device) => {
							// From here the connect is the managers' — no longer cancellable here.
							if (this.#pending[kind] === abort) this.#pending[kind] = null;
							if (!opts.isIdle(kind)) return;
							await opts.connect(kind, device);
						}
					});
				} finally {
					if (this.#pending[kind] === abort) this.#pending[kind] = null;
				}
			})
		);
		return { de1, scale };
	}
}
