/**
 * The connected grinder over Web Bluetooth (beta, spec
 * `.scratch/grinder-ble/SPEC.md` §2.6–2.8, §6.1): a scripted fake grinder
 * (a notification stream of Motto frames) drives the real `CremaApp` →
 * `GrinderManager` → connect program → the real wasm core.
 *
 * Covers the handshake bytes (§3.4 vector 1), readiness on the first
 * reading, a silent link dropped into the reconnect ladder once and
 * re-handshaken, "the grinder didn't answer" on a first connect, a firmware
 * refusal with no retry, a grind cut off by a drop, the serial kept out of
 * the event log, the beta gate, the flash hold and the §6.1 link rules.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect } from 'effect';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CremaCore, CoreOutput } from '#lib/core/index.js';
import type { Grinder } from '#lib/core/crema-core.js';
import type { BleDevice } from './transport';
import type { ConnectedGrinderView } from '#lib/state/ui-state.svelte.js';
import { getBeanStore } from '#lib/bean/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { LAST_DEVICES_KEY, readLastDevices } from './launch-reconnect';
import {
	getGrinderLinkStore,
	resolveGrinderLink,
	type GrinderLinks
} from '#lib/grinder/connected-link.svelte.js';
import { GrinderManager } from './grinder';

const NOTIFY = '4d543830-0003-4b80-8f00-424f4f4b4f4f';
const MOTTO = 'bookoo-motto-80';

/** One Motto message as BLE frames (`<BBHHH` header, `max_packet` bytes each). */
function frames(json: string, seq = 7, maxPacket = 20): Uint8Array[] {
	const body = new TextEncoder().encode(json);
	const out: Uint8Array[] = [];
	const room = maxPacket - 8;
	for (let offset = 0; offset < body.length; offset += room) {
		const chunk = body.slice(offset, offset + room);
		const f = new Uint8Array(8 + chunk.length);
		f.set([0xa5, 0x01, seq & 0xff, seq >> 8, body.length & 0xff, body.length >> 8, offset & 0xff, offset >> 8]);
		f.set(chunk, 8);
		out.push(f);
	}
	return out;
}

const period = (state = 'IDLE', gap = 480) =>
	JSON.stringify({
		broadcast: {
			periodInfo: {
				devState: state,
				bladeGap: gap,
				grindRpm: 700,
				feedingRpm: 50,
				humidity: 40,
				totalGrinds: 120,
				selectPreset: -1
			}
		}
	});

const base = (releaseVer: number, serial = 'MT80SECRET1234') =>
	JSON.stringify({ broadcast: { baseInfo: { releaseVer, snCode: serial, wifiName: 'HOME-SSID' } } });

const hex = (b: Uint8Array) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');

/** A scripted Motto 80: a `BleDevice` stand-in plus the stream it sends. */
function fakeGrinder(id = 'grinder-A') {
	const cbs: {
		state?: (s: string) => void;
		attempt?: (n: number) => void;
		reconnected?: () => void;
	} = {};
	let sink: ((n: { characteristic: string; data: Uint8Array; atMs: number }) => void) | null = null;
	const writes: Uint8Array[] = [];
	const device = {
		name: 'BOOKOO MT80 0011aabb',
		id,
		device: { name: 'BOOKOO MT80 0011aabb', id },
		connectionState: 'connected',
		disconnect: vi.fn(),
		setSink: (s: typeof sink) => (sink = s),
		setReconnectTimeline: () => undefined,
		onDisconnected: () => undefined,
		onStateChanged: (l: (s: string) => void) => (cbs.state = l),
		onReconnectAttempt: (l: (n: number) => void) => (cbs.attempt = l),
		onReconnected: (l: () => void) => (cbs.reconnected = l),
		onAdvertisement: () => undefined,
		onNotificationOverflow: () => undefined,
		connectGatt: async () => undefined,
		discoveredServiceUuids: async () => [] as string[],
		startNotifications: vi.fn(async () => undefined),
		write: vi.fn(async (_s: string, _c: string, data: Uint8Array) => {
			writes.push(data);
		}),
		kick: () => 'ignored-idle',
		/** Drop GATT without a user disconnect: the transport starts its loop. */
		recycle: vi.fn(() => {
			device.connectionState = 'reconnecting';
			cbs.state?.('reconnecting');
		})
	};
	return {
		device,
		writes,
		/** Send one message, fragmented, from the notify characteristic;
		 *  the fragments at [lost] never arrive. */
		send(json: string, atMs: number, lost: readonly number[] = []) {
			frames(json).forEach((data, i) => {
				if (!lost.includes(i)) sink?.({ characteristic: NOTIFY, data, atMs });
			});
		},
		/** The link drops on its own. */
		drop() {
			device.connectionState = 'reconnecting';
			cbs.state?.('reconnecting');
			cbs.attempt?.(1);
		},
		recover() {
			device.connectionState = 'connected';
			cbs.reconnected?.();
		}
	};
}

let core: CremaCore;

beforeAll(async () => {
	await initTestWasm();
	core = await (await import('#lib/core/index.js')).loadCore();
});

beforeEach(() => {
	vi.useFakeTimers();
	localStorage.clear();
	getBeanStore().clearAll();
	getGrinderLinkStore().reload();
	getSettingsStore().reset();
});

afterEach(async () => {
	await core.disconnectGrinder();
	vi.useRealTimers();
});

async function settle() {
	await vi.advanceTimersByTimeAsync(0);
}

async function connectedApp(id?: string) {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const runtime = {
		runPromiseExit: (effect: Effect.Effect<unknown, unknown>) => Effect.runPromiseExit(effect),
		runPromise: async () => undefined,
		runFork: () => undefined
	};
	const app = new CremaApp(core, runtime as never);
	const internals = app as unknown as {
		grinder: { attach(d: BleDevice): Promise<void> };
		state: { current: { grinder: ConnectedGrinderView; eventLog: readonly { text: string }[] } };
		applyCoreOutput(out: CoreOutput): void;
	};
	const grinder = fakeGrinder(id);
	await internals.grinder.attach(grinder.device as unknown as BleDevice);
	await settle();
	const tick = async (nowMs: number) => {
		internals.applyCoreOutput(await core.onTick(nowMs));
		await settle();
	};
	const send = async (json: string, atMs: number, lost: readonly number[] = []) => {
		grinder.send(json, atMs, lost);
		await settle();
	};
	return {
		app,
		grinder,
		tick,
		send,
		view: () => internals.state.current.grinder,
		log: () => internals.state.current.eventLog.map((l) => l.text).join('\n')
	};
}

describe('the connected grinder over Web Bluetooth', () => {
	it('subscribes, then writes the handshake in 20-byte packets with response (§3.4 vector 1)', async () => {
		const t = await connectedApp();
		expect(t.grinder.device.startNotifications).toHaveBeenCalledWith(
			'4d543830-0001-4b80-8f00-424f4f4b4f4f',
			NOTIFY
		);
		expect(t.grinder.writes.map(hex)).toEqual([
			'a50101002b0000007b2272657175657374223a7b',
			'a50101002b000c002261707048656c6c6f223a7b',
			'a50101002b001800226f70223a2268616e647368',
			'a50101002b002400616b65227d7d7d'
		]);
		const [, char, , withResponse] = t.grinder.device.write.mock.calls[0] as unknown as [
			string,
			string,
			Uint8Array,
			boolean
		];
		expect(char).toBe('4d543830-0002-4b80-8f00-424f4f4b4f4f');
		expect(withResponse).toBe(true);
		// Not ready until the grinder answers.
		expect(t.view().state).toBe('subscribing');
		expect(t.view().model).toBe(MOTTO);
		expect(t.view().capabilities?.setting?.unit).toBe('μm');
	});

	it('is ready on the first reading, shows it live and remembers the grinder', async () => {
		const t = await connectedApp();
		await t.send(period(), 200);
		expect(t.view().state).toBe('ready');
		expect(t.view().reading?.setting?.value).toBe('480');
		expect(t.view().reading?.grind_rpm).toBe(700);
		expect(readLastDevices().grinder).toEqual({
			id: 'grinder-A',
			name: 'BOOKOO MT80 0011aabb',
			auto: true
		});
	});

	it('a lost first fragment drops that reading, not the link (official §5.1)', async () => {
		// BOOKOO's README periodInfo: 248 bytes, 21 fragments at 20-byte packets.
		const readme =
			'{"broadcast":{"periodInfo":{"feedingRpm":65,"bladeGap":500,"grindRpm":700,"humidity":25,"devState":"IDLE","netState":"CONNECTED","totalGrinds":120,"cupDetect":true,"autoStop":true,"fastClean":true,"brightness":5,"standbySec":60,"selectPreset":-1}}}';
		expect(frames(readme)).toHaveLength(21);
		const t = await connectedApp();
		await t.send(period(), 200);
		for (const at of [400, 600, 800]) await t.send(readme, at, [0]);
		await t.send(readme, 1_000);
		await t.tick(1_100);
		expect(t.grinder.device.recycle).not.toHaveBeenCalled();
		expect(t.view().state).toBe('ready');
		expect(t.view().reading?.setting?.value).toBe('500');
	});

	it('a silent link is dropped into the reconnect ladder once, and the reconnect handshakes again', async () => {
		const t = await connectedApp();
		await t.send(period(), 200);
		await t.tick(5_000);
		expect(t.grinder.device.recycle).not.toHaveBeenCalled();
		await t.tick(5_300);
		expect(t.grinder.device.recycle).toHaveBeenCalledTimes(1);
		expect(t.view().state).toBe('reconnecting');
		// The reading stays, not live.
		expect(t.view().reading).not.toBeNull();
		for (let now = 6_000; now <= 20_000; now += 1_000) await t.tick(now);
		expect(t.grinder.device.recycle).toHaveBeenCalledTimes(1);

		// The transport recovers at 21 s: the handshake's deadline runs from then.
		await vi.advanceTimersByTimeAsync(21_000 - performance.now());
		t.grinder.recover();
		await settle();
		expect(t.grinder.writes).toHaveLength(8);
		expect(t.view().state).toBe('subscribing');
		await t.send(period('IDLE', 500), 21_200);
		expect(t.view().state).toBe('ready');
		expect(t.view().reading?.setting?.value).toBe('500');
	});

	it('a grinder that reconnects but never streams stops reconnecting after 3 tries, and Reconnect brings it back', async () => {
		const t = await connectedApp();
		await t.send(period(), 200);
		// Silent: dropped into the ladder.
		await t.tick(5_300);
		expect(t.grinder.device.recycle).toHaveBeenCalledTimes(1);
		// Each reconnect handshakes, then nothing comes.
		let now = 10_000;
		for (let i = 1; i <= 3; i++) {
			await vi.advanceTimersByTimeAsync(now - performance.now());
			t.grinder.recover();
			await settle();
			await t.tick(now + 5_100);
			now += 10_000;
		}
		// Two more recycles, then it gives up instead of a third.
		expect(t.grinder.device.recycle).toHaveBeenCalledTimes(3);
		expect(t.view().state).toBe('failed');
		expect(t.view().problem).toEqual({ kind: 'no-data' });
		expect(t.grinder.device.disconnect).toHaveBeenCalled();
		expect(readLastDevices().grinder?.auto).toBe(false);
		// Nothing more happens on its own.
		for (let n = now; n <= now + 60_000; n += 1_000) await t.tick(n);
		expect(t.grinder.device.recycle).toHaveBeenCalledTimes(3);

		// Reconnect: the same device, no chooser.
		const internals = t.app as unknown as { grinder: GrinderManager };
		const known = vi.spyOn(GrinderManager.prototype, 'connectKnown').mockResolvedValue();
		expect(await internals.grinder.reconnectStalled()).toBe(true);
		expect(known).toHaveBeenCalledWith(t.grinder.device.device);
		known.mockRestore();
	});

	it('a reading between silent reconnects starts the count over', async () => {
		const t = await connectedApp();
		await t.send(period(), 200);
		await t.tick(5_300);
		let now = 10_000;
		const reconnectSilently = async () => {
			await vi.advanceTimersByTimeAsync(now - performance.now());
			t.grinder.recover();
			await settle();
			await t.tick(now + 5_100);
			now += 10_000;
		};
		await reconnectSilently();
		await reconnectSilently();
		// The third reconnect streams, then goes silent again.
		await vi.advanceTimersByTimeAsync(now - performance.now());
		t.grinder.recover();
		await settle();
		await t.send(period(), now + 200);
		expect(t.view().state).toBe('ready');
		await t.tick(now + 5_400);
		now += 10_000;
		await reconnectSilently();
		await reconnectSilently();
		expect(t.view().state).toBe('reconnecting');
		expect(t.view().problem).toBeNull();
	});

	it("a first connect the grinder never answers gives up with the close-the-app message", async () => {
		const t = await connectedApp();
		await t.tick(5_100);
		expect(t.view().state).toBe('failed');
		expect(t.view().problem).toEqual({ kind: 'no-answer' });
		expect(t.grinder.device.recycle).not.toHaveBeenCalled();
		expect(t.grinder.device.disconnect).toHaveBeenCalled();
	});

	it('old firmware ends the link with no retry, and launch leaves it alone', async () => {
		const t = await connectedApp();
		await t.send(period(), 100);
		await t.send(base(102_040_416), 300);
		expect(t.view().state).toBe('failed');
		expect(t.view().problem).toMatchObject({ kind: 'firmware' });
		expect(t.grinder.device.disconnect).toHaveBeenCalled();
		expect(readLastDevices().grinder?.auto).toBe(false);
	});

	it('a drop mid-grind reports the grind cut off', async () => {
		const t = await connectedApp();
		await t.send(period('GRINDING'), 200);
		await t.send(period('GRINDING'), 400);
		await t.send(period('GRINDING'), 1_400);
		t.grinder.drop();
		await settle();
		expect(t.view().state).toBe('reconnecting');
		expect(t.view().lastRun).toMatchObject({ kind: 'Grind', ended_by: 'LinkLost' });
	});

	it('keeps the serial out of the event log (it is in memory only)', async () => {
		const t = await connectedApp();
		await t.send(base(102_190_820), 100);
		await t.send(period(), 300);
		expect(t.view().state).toBe('ready');
		expect(t.view().info?.serial).toBe('MT80SECRET1234');
		expect(t.view().info?.firmware).toBe('1.2.19.0820');
		expect(t.log()).toContain('Grinder ready');
		expect(t.log()).not.toContain('SECRET');
		expect(t.log()).not.toContain('HOME-SSID');
		expect(localStorage.getItem(LAST_DEVICES_KEY) ?? '').not.toContain('SECRET');
	});

	it('shows and logs the advertised name by its model prefix only (REVIEW-565)', async () => {
		const t = await connectedApp();
		await t.send(period(), 300);
		expect(t.view().state).toBe('ready');
		// The suffix may derive from the serial: never on screen or in the log.
		expect(t.view().name).toBe('BOOKOO MT80');
		expect(t.log()).toContain('Connecting to BOOKOO MT80…');
		expect(t.log()).not.toContain('0011aabb');
		// The reconnect keeps the full name, on this device only.
		expect(readLastDevices().grinder?.name).toBe('BOOKOO MT80 0011aabb');
	});

	it('links the one unlinked Motto record on Ready, with a message', async () => {
		const lib = getBeanStore();
		const motto = lib.addGrinder('', MOTTO)!;
		const t = await connectedApp();
		await t.send(period(), 200);
		expect(getGrinderLinkStore().linkOf('grinder-A')).toBe(motto.id);
		expect(t.log()).toContain(`Grinder linked to ${motto.name}`);
	});

	it('the user Disconnect forgets the readings and stops launch reconnects', async () => {
		const t = await connectedApp();
		await t.send(period(), 200);
		await t.app.disconnectGrinder();
		expect(t.view()).toMatchObject({ state: 'disconnected', reading: null, model: null });
		expect(readLastDevices().grinder?.auto).toBe(false);
	});

	it('is behind the beta: Connect does nothing while it is off', async () => {
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const app = new CremaApp(core, null as never);
		const connect = vi.spyOn(GrinderManager.prototype, 'connect');
		await app.connectGrinder();
		expect(connect).not.toHaveBeenCalled();
		expect(getSettingsStore().current.connectedGrinderBeta).toBe(false);
	});

	it('holds connects while a DE1 firmware flash runs', async () => {
		const statuses: string[] = [];
		const m = new GrinderManager(core, {
			onCoreOutput: () => undefined,
			onStatus: (l) => statuses.push(l),
			onState: () => undefined,
			onProblem: () => undefined,
			onIdentified: () => undefined
		});
		m.heldForFirmwareFlash = true;
		await m.connectKnown({ id: 'x', name: 'BOOKOO MT80 1' } as BluetoothDevice);
		expect(m.identity()).toBeNull();
		expect(statuses).toEqual(['Grinder waits for the firmware update']);
	});
});

describe('which owned grinder a connected one is (§6.1)', () => {
	const rec = (id: string, over: Partial<Grinder> = {}): Grinder => ({
		id,
		name: id,
		scale: { min: 0, max: 999, step: 1, unit: 'μm' },
		presetId: MOTTO,
		updatedAt: 0,
		...over
	});
	const resolve = (grinders: Grinder[], links: GrinderLinks = {}, deviceId = 'dev') =>
		resolveGrinderLink({ deviceId, model: MOTTO, grinders, links });

	it('a linked live record wins', () => {
		const r = resolve([rec('a'), rec('b')], { dev: { grinderId: 'b', linkedAt: 1 } });
		expect(r).toMatchObject({ kind: 'linked', grinder: { id: 'b' } });
	});

	it('a link to an archived record is kept, not replaced', () => {
		const r = resolve([rec('a', { archivedAt: 5 }), rec('b')], { dev: { grinderId: 'a', linkedAt: 1 } });
		expect(r).toMatchObject({ kind: 'archived', grinder: { id: 'a' } });
	});

	it('exactly one unlinked record of the model links silently', () => {
		expect(resolve([rec('a'), rec('n', { presetId: 'niche-zero' })])).toMatchObject({
			kind: 'auto',
			grinder: { id: 'a' }
		});
	});

	it("another device's record isn't a candidate", () => {
		const r = resolve([rec('a'), rec('b')], { other: { grinderId: 'a', linkedAt: 1 } });
		expect(r).toMatchObject({ kind: 'auto', grinder: { id: 'b' } });
	});

	it('several candidates ask; none offers to add one', () => {
		expect(resolve([rec('a'), rec('b')]).kind).toBe('choose');
		expect(resolve([rec('n', { presetId: 'niche-zero' })]).kind).toBe('add');
		expect(resolve([rec('a', { deletedAt: 3 })]).kind).toBe('add');
	});

	it('the store keeps one device per record', () => {
		const links = getGrinderLinkStore();
		links.link('d1', 'a');
		links.link('d2', 'a');
		expect(links.linkOf('d1')).toBeNull();
		expect(links.linkOf('d2')).toBe('a');
		links.unlink('d2');
		expect(links.links).toEqual({});
	});
});
