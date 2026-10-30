/**
 * No scale writes while the scale is not ready, and no settings-query storm
 * around a reconnect (core/shell review 2026-10-30 §2.6-1). Before: every
 * rejected write re-queried the settings, the query's own writes were rejected
 * in turn, and the heartbeat kept writing while the scale reconnected.
 *
 * Drives the real `CremaApp` → `ScaleManager` → connect program over a fake
 * scale transport and a fake core that counts settings queries.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect } from 'effect';
import type { CoreOutput, ScaleUuids } from '#lib/core/index.js';
import { ScaleManager } from './scale';
import type { BleDevice } from './transport';

const UUIDS: ScaleUuids = {
	service: 'svc',
	weight_notify: 'wn',
	command_write: 'cw',
	command_notifies: false,
	command_notify_optional: false
} as ScaleUuids;

const writes = (...tags: number[]): CoreOutput => ({
	events: [],
	commands: tags.map((t) => ({ type: 'WriteScale', content: { data: [t] } }))
});
const HEARTBEAT = 0xa1;
const TARE = 0xa2;
const SETTING = 0xa3;
const QUERY_A = 0xb1;
const QUERY_B = 0xb2;

function fakeScale() {
	const cbs: {
		attempt?: (n: number) => void;
		reconnected?: () => void;
	} = {};
	/** Bytes the scale accepted or rejected — what reached the transport. */
	const sent: number[] = [];
	/** Bytes the scale rejects (all when `rejectAll`). */
	const reject = new Set<number>();
	let rejectAll = false;
	/** When set, writes hang until released — a write in flight at the drop. */
	let hold = false;
	const held: ((error: Error) => void)[] = [];
	const device = {
		name: 'BOOKOO_SC',
		id: 'scale-A',
		device: { name: 'BOOKOO_SC', id: 'scale-A' },
		connectionState: 'connected',
		disconnect: vi.fn(),
		setSink: () => undefined,
		setReconnectTimeline: () => undefined,
		onDisconnected: () => undefined,
		onStateChanged: () => undefined,
		onReconnectAttempt: (l: (n: number) => void) => (cbs.attempt = l),
		onReconnected: (l: () => void) => (cbs.reconnected = l),
		onAdvertisement: () => undefined,
		onNotificationOverflow: () => undefined,
		connectGatt: async () => undefined,
		startNotifications: async () => undefined,
		write: vi.fn(async (_s: string, _c: string, data: Uint8Array) => {
			sent.push(data[0]);
			if (hold) return new Promise<void>((_resolve, rejectWrite) => held.push(rejectWrite));
			if (rejectAll || reject.has(data[0])) throw new Error('GATT operation failed');
		})
	};
	return {
		device,
		cbs,
		sent,
		reject,
		setRejectAll: (on: boolean) => (rejectAll = on),
		setHold: (on: boolean) => (hold = on),
		/** Reject every held write: the link is gone. */
		failHeld: () => held.splice(0).forEach((r) => r(new Error('GATT Server is disconnected'))),
		/** The link drops: Web Bluetooth's state flips before the first attempt. */
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

async function connectedApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const counts = { query: 0, heartbeat: 0 };
	const coreImpl: Record<string, (...args: unknown[]) => Promise<unknown>> = {
		connectScale: async () => 'Bookoo',
		scaleUuids: async () => UUIDS,
		scaleCapabilities: async () => ({ heartbeat_interval_ms: 2_000 }),
		queryScaleSettings: async () => {
			counts.query += 1;
			return writes(QUERY_A, QUERY_B);
		},
		scaleHeartbeat: async () => {
			counts.heartbeat += 1;
			return writes(HEARTBEAT);
		},
		tareScale: async () => writes(TARE),
		setScaleFlowSmoothing: async () => writes(SETTING)
	};
	// Every other core call resolves to an empty output.
	const core = new Proxy(coreImpl, {
		get: (target, prop: string) =>
			prop in target ? target[prop] : async () => ({ events: [], commands: [] })
	});
	const runtime = {
		runPromiseExit: (effect: Effect.Effect<unknown, unknown>) => Effect.runPromiseExit(effect),
		// Background work (webhooks, the upload queue) is not under test.
		runPromise: async () => undefined,
		runFork: () => undefined
	};
	const app = new CremaApp(core as never, runtime as never);
	const scale = fakeScale();
	const manager = (app as unknown as { scale: { attach(d: BleDevice): Promise<void> } }).scale;
	await manager.attach(scale.device as unknown as BleDevice);
	await settle();
	const scaleState = () =>
		(app as unknown as { state: { current: { scaleState: string } } }).state.current.scaleState;
	return { app, scale, counts, scaleState };
}

/** Let queued promise chains run, then advance fake time. */
async function settle(ms = 0) {
	await vi.advanceTimersByTimeAsync(ms);
}

beforeEach(() => {
	vi.useFakeTimers();
	localStorage.clear();
});

afterEach(() => {
	vi.useRealTimers();
	vi.restoreAllMocks();
});

describe('scale writes around a reconnect', () => {
	it('connects ready, then sends the connect query and the heartbeat', async () => {
		const t = await connectedApp();
		expect(t.scaleState()).toBe('ready');
		expect(t.counts.query).toBe(1);
		expect(t.scale.sent).toEqual([QUERY_A, QUERY_B]);
		await settle(2_000);
		expect(t.scale.sent).toEqual([QUERY_A, QUERY_B, HEARTBEAT]);
	});

	it('AC-F3.1 no heartbeat, tare or settings write is issued while the scale is not ready', async () => {
		const t = await connectedApp();
		t.scale.sent.length = 0;
		t.scale.drop();
		expect(t.scaleState()).toBe('reconnecting');
		await t.app.tareScale();
		await t.app.setScaleFlowSmoothing(true);
		await settle(10_000); // five heartbeat periods
		expect(t.scale.sent).toEqual([]);
		expect(t.counts.heartbeat).toBe(0); // the heartbeat clock idles too
		expect(t.counts.query).toBe(1);

		// Back to ready: the writes resume.
		t.scale.recover();
		await settle();
		await t.app.tareScale();
		await settle(2_000);
		expect(t.scale.sent).toEqual([QUERY_A, QUERY_B, TARE, HEARTBEAT]);
	});

	it('AC-F3.1 holds the connect query until the scale is ready', async () => {
		const order: string[] = [];
		const core = {
			connectScale: async () => 'Bookoo',
			scaleUuids: async () => UUIDS,
			queryScaleSettings: async () => writes(QUERY_A),
			disconnectScale: async () => undefined,
			onScaleWriteResult: async () => writes()
		};
		const manager: ScaleManager = new ScaleManager(
			core as never,
			{
				onCoreOutput: (out) => {
					// Write-result reports come back as empty outputs.
					if (out.commands.length === 0) return;
					order.push('query output');
					for (const c of out.commands) void manager.writeScale(new Uint8Array(c.content.data));
				},
				onStatus: () => undefined,
				onState: (s) => order.push(s),
				onScaleIdentified: () => undefined,
				onReconnected: () => undefined
			},
			{ runPromiseExit: (e: Effect.Effect<unknown, unknown>) => Effect.runPromiseExit(e) } as never
		);
		const scale = fakeScale();
		await (manager as unknown as { attach(d: BleDevice): Promise<void> }).attach(
			scale.device as unknown as BleDevice
		);
		await settle();
		expect(order).toEqual(['subscribing', 'ready', 'query output']);
		expect(scale.sent).toEqual([QUERY_A]);
	});

	it('AC-F3.2 a reconnect produces at most one settings query', async () => {
		const t = await connectedApp();
		await settle(10_000); // heartbeats land; the cooldown since the connect query is over
		t.counts.query = 0;
		// The link dies: a heartbeat is rejected half a second before the drop
		// is seen.
		t.scale.setRejectAll(true);
		await settle(2_500);
		t.scale.drop();
		await t.app.tareScale();
		await t.app.setScaleFlowSmoothing(true);
		await settle(10_000);
		// The link comes back but the scale still rejects writes for a while.
		t.scale.recover();
		await settle(20_000);
		expect(t.counts.query).toBe(1);
	});

	it('AC-F3.2 a write in flight at the drop, rejected after it, does not re-query', async () => {
		const t = await connectedApp();
		await settle(10_000);
		t.counts.query = 0;
		t.scale.setHold(true);
		void t.app.tareScale();
		await settle();
		t.scale.drop();
		t.scale.failHeld();
		t.scale.setHold(false);
		await settle(10_000);
		expect(t.counts.query).toBe(0);
		t.scale.recover();
		await settle(20_000);
		expect(t.counts.query).toBe(1);
	});

	it('AC-F3.2 a query whose own write is always rejected does not re-query', async () => {
		const t = await connectedApp();
		t.scale.reject.add(QUERY_B);
		t.scale.drop();
		t.scale.recover();
		await settle(60_000);
		expect(t.counts.query).toBe(2); // connect + reconnect
	});

	it('still re-queries once after a rejected settings write while ready (review #34)', async () => {
		const t = await connectedApp();
		await settle(10_000);
		t.counts.query = 0;
		t.scale.reject.add(SETTING);
		await t.app.setScaleFlowSmoothing(true);
		await settle(1_000);
		expect(t.counts.query).toBe(1);
		await settle(30_000);
		expect(t.counts.query).toBe(1);
	});
});
