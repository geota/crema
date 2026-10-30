/**
 * A dead scale link is torn down once and reconnected, never in a storm
 * (core/shell review 2026-10-30 §2.6-2). The rule lives in the core
 * (`ScaleLinkDead`: silent for 1 s + a 5 s grace, or three failed writes with
 * no reading in between, once per episode); the shell drops the link at the
 * transport while the scale is ready and lets the reconnect loop recover it.
 *
 * Drives the real `CremaApp` → `ScaleManager` → connect program and the real
 * wasm core over a fake scale transport.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect } from 'effect';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CremaCore, CoreOutput } from '#lib/core/index.js';
import type { BleDevice } from './transport';

type Notification = { characteristic: string; data: Uint8Array; atMs: number };

/** A Bookoo weight frame (the core's own test helper, in TS). */
function bookooPacket(centigrams: number): Uint8Array {
	const p = new Uint8Array(20);
	p.set([0x03, 0x0b, 0, 0, 0, 0, 0x2b, (centigrams >> 16) & 0xff, (centigrams >> 8) & 0xff, centigrams & 0xff]);
	p[19] = p.slice(0, 19).reduce((a, b) => a ^ b, 0);
	return p;
}

function fakeScale() {
	const cbs: { attempt?: (n: number) => void; reconnected?: () => void } = {};
	let sink: ((n: Notification) => void) | null = null;
	let rejectWrites = false;
	const device = {
		name: 'BOOKOO_SC',
		id: 'scale-A',
		device: { name: 'BOOKOO_SC', id: 'scale-A' },
		connectionState: 'connected',
		disconnect: vi.fn(),
		setSink: (s: (n: Notification) => void) => (sink = s),
		setReconnectTimeline: () => undefined,
		onDisconnected: () => undefined,
		onStateChanged: () => undefined,
		onReconnectAttempt: (l: (n: number) => void) => (cbs.attempt = l),
		onReconnected: (l: () => void) => (cbs.reconnected = l),
		onAdvertisement: () => undefined,
		onNotificationOverflow: () => undefined,
		connectGatt: async () => undefined,
		startNotifications: async () => undefined,
		write: vi.fn(async () => {
			if (rejectWrites) throw new Error('GATT operation failed');
		}),
		/** Drop GATT without a user disconnect: the reconnect loop starts. */
		recycle: vi.fn(() => {
			device.connectionState = 'reconnecting';
			cbs.attempt?.(1);
		})
	};
	return {
		device,
		setRejectWrites: (on: boolean) => (rejectWrites = on),
		notify: (n: Notification) => sink?.(n),
		/** The link drops on its own: the transport is already reconnecting. */
		drop() {
			device.connectionState = 'reconnecting';
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
});

afterEach(async () => {
	await core.disconnectScale();
	vi.useRealTimers();
});

async function connectedApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const runtime = {
		runPromiseExit: (effect: Effect.Effect<unknown, unknown>) => Effect.runPromiseExit(effect),
		runPromise: async () => undefined,
		runFork: () => undefined
	};
	const app = new CremaApp(core, runtime as never);
	const internals = app as unknown as {
		scale: { attach(d: BleDevice): Promise<void> };
		state: { current: { scaleState: string } };
		applyCoreOutput(out: CoreOutput): void;
	};
	const scale = fakeScale();
	await internals.scale.attach(scale.device as unknown as BleDevice);
	await settle();
	const uuids = (await core.scaleUuids())!;
	const weigh = (atMs: number) =>
		scale.notify({ characteristic: uuids.weight_notify.toLowerCase(), data: bookooPacket(2_000), atMs });
	/** What the app's ~1 s ticker does. */
	const tick = async (nowMs: number) => {
		internals.applyCoreOutput(await core.onTick(nowMs));
		await settle();
	};
	return { app, scale, weigh, tick, scaleState: () => internals.state.current.scaleState };
}

async function settle() {
	await vi.advanceTimersByTimeAsync(0);
}

describe('stale scale recovery', () => {
	it('AC-F18d a silent scale link is torn down and reconnected once, with no storm', async () => {
		const t = await connectedApp();
		expect(t.scaleState()).toBe('ready');
		t.weigh(1_000);
		await settle();
		await t.tick(2_100); // ScaleStale: within the grace, nothing is torn down
		await t.tick(6_900);
		expect(t.scale.device.recycle).not.toHaveBeenCalled();
		await t.tick(7_000);
		expect(t.scale.device.recycle).toHaveBeenCalledTimes(1);
		expect(t.scaleState()).toBe('reconnecting');

		// The link comes back, but the scale stays silent: no second teardown.
		t.scale.recover();
		await settle();
		expect(t.scaleState()).toBe('ready');
		for (let now = 8_000; now <= 120_000; now += 1_000) await t.tick(now);
		expect(t.scale.device.recycle).toHaveBeenCalledTimes(1);

		// Readings resume, then stop again: a new episode is recovered again.
		t.weigh(121_000);
		await settle();
		await t.tick(127_000);
		expect(t.scale.device.recycle).toHaveBeenCalledTimes(2);
	});

	it('AC-F18d three failed writes with no reading in between tear the link down once', async () => {
		const t = await connectedApp();
		t.weigh(1_000);
		await settle();
		t.scale.setRejectWrites(true);
		await t.app.tareScale();
		await t.app.tareScale();
		await settle();
		expect(t.scale.device.recycle).not.toHaveBeenCalled();
		await t.app.tareScale();
		await settle();
		expect(t.scale.device.recycle).toHaveBeenCalledTimes(1);
		// While reconnecting no write is sent, so nothing more is counted.
		await t.app.tareScale();
		await t.app.tareScale();
		await t.app.tareScale();
		await settle();
		expect(t.scale.device.recycle).toHaveBeenCalledTimes(1);
	});

	it('a link already reconnecting is left to its loop', async () => {
		const t = await connectedApp();
		t.weigh(1_000);
		await settle();
		t.scale.drop();
		await t.tick(7_000); // the core reports the dead link
		expect(t.scaleState()).toBe('reconnecting');
		expect(t.scale.device.recycle).not.toHaveBeenCalled();
	});

	it('a scale that streams weight while rejecting writes is not torn down', async () => {
		const t = await connectedApp();
		t.weigh(900); // the first reading's one-shot config queries land
		await settle();
		t.scale.setRejectWrites(true);
		for (let i = 0; i < 6; i++) {
			t.weigh(1_000 + i * 100);
			await settle();
			await t.app.tareScale();
			await settle();
		}
		expect(t.scale.device.recycle).not.toHaveBeenCalled();
	});
});
