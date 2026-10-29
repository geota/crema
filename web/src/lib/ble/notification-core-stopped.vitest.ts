/**
 * A stopped core must not flood the event log (round-2 verification V2
 * P3-2): every DE1 and scale notification then fails with
 * `CoreStoppedError`, and a status line per notification (2–15/s) pushed the
 * stop's own log line out of the 200-line diagnostics log within a minute.
 * The stop is logged once by the app; the managers drop these failures and
 * still report any other one.
 */
import { describe, expect, it, vi } from 'vitest';
import { Exit } from 'effect';
import type { AppRuntime } from '#lib/effect/runtime.js';
import type { CremaCore } from '#lib/core/index.js';
import { CoreStoppedError } from '#lib/core/stopped-guard.js';
import type { ScaleConnectDeps } from '#lib/services/scale-orchestrator.js';
import { De1Manager } from './de1';
import { De1Uuids } from './de1-uuids';
import { ScaleManager } from './scale';
import type { De1Transport } from './de1-transport';
import type { BleDevice, BleNotification as Notification } from './transport';

const scaleProgramDeps: ScaleConnectDeps[] = [];
vi.mock('#lib/services/scale-orchestrator.js', () => ({
	scaleConnectProgram: (deps: ScaleConnectDeps) => {
		scaleProgramDeps.push(deps);
		return {};
	}
}));

type Sink = (n: Notification) => void;

/** Let the notification's promise chain settle. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

function failingCore(error: () => unknown) {
	return {
		onNotification: vi.fn(() => Promise.reject(error())),
		de1LinkLost: async () => ({ actions: [] }),
		disconnectScale: async () => undefined
	};
}

async function de1Sink(core: ReturnType<typeof failingCore>, lines: string[]): Promise<Sink> {
	let sink: Sink | undefined;
	const device = {
		name: 'DE1',
		id: 'dev-A',
		disconnect: vi.fn(),
		setSink: (s: Sink) => (sink = s),
		onDisconnected: () => undefined,
		onReconnectAttempt: () => undefined,
		onReconnected: () => undefined
	} as unknown as De1Transport;
	const manager = new De1Manager(core as unknown as CremaCore, {
		onCoreOutput: () => undefined,
		onStatus: (l) => lines.push(l),
		onState: () => undefined,
		onDiagnostics: () => undefined
	});
	// No runtime: attach installs the sink, then drops the device.
	await (manager as unknown as { attach(d: De1Transport): Promise<void> }).attach(device);
	lines.length = 0;
	return sink!;
}

async function scaleSink(core: ReturnType<typeof failingCore>, lines: string[]): Promise<Sink> {
	scaleProgramDeps.length = 0;
	let sink: Sink | undefined;
	const device = {
		name: 'BOOKOO_SC',
		id: 'scale-A',
		disconnect: vi.fn(),
		setSink: (s: Sink) => (sink = s),
		setReconnectTimeline: () => undefined,
		onDisconnected: () => undefined,
		onReconnectAttempt: () => undefined,
		onReconnected: () => undefined,
		onAdvertisement: () => undefined,
		onNotificationOverflow: () => undefined
	} as unknown as BleDevice;
	const runtime = {
		runPromiseExit: () => new Promise<Exit.Exit<void, unknown>>(() => undefined)
	} as unknown as AppRuntime;
	const manager = new ScaleManager(
		core as unknown as CremaCore,
		{
			onCoreOutput: () => undefined,
			onStatus: (l) => lines.push(l),
			onState: () => undefined,
			onScaleIdentified: () => undefined,
			onReconnected: () => undefined
		},
		runtime
	);
	void (manager as unknown as { attach(d: BleDevice): Promise<void> }).attach(device);
	await Promise.resolve();
	scaleProgramDeps[0].onUuidsResolved({
		service: 'svc',
		weight_notify: 'weight',
		command_write: 'cmd'
	} as never);
	lines.length = 0;
	return sink!;
}

const de1Hvn = (): Notification =>
	({ characteristic: De1Uuids.SHOT_SAMPLE, data: new Uint8Array(19), atMs: 1 }) as Notification;
const scaleHvn = (): Notification =>
	({ characteristic: 'weight', data: new Uint8Array(20), atMs: 1 }) as Notification;

describe('notifications on a stopped core (V2 P3-2)', () => {
	it('the DE1 manager writes no status line per refused notification', async () => {
		const lines: string[] = [];
		const core = failingCore(() => new CoreStoppedError('crema core stopped'));
		const sink = await de1Sink(core, lines);
		for (let i = 0; i < 50; i++) sink(de1Hvn());
		await settle();
		expect(core.onNotification).toHaveBeenCalledTimes(50);
		expect(lines).toEqual([]);
	});

	it('the DE1 manager still reports any other notification failure', async () => {
		const lines: string[] = [];
		const sink = await de1Sink(failingCore(() => new Error('bad packet')), lines);
		sink(de1Hvn());
		await settle();
		expect(lines).toEqual([expect.stringContaining('DE1 notification processing failed: ')]);
	});

	it('the scale manager writes no status line per refused notification', async () => {
		const lines: string[] = [];
		const core = failingCore(() => new CoreStoppedError('crema core stopped'));
		const sink = await scaleSink(core, lines);
		for (let i = 0; i < 50; i++) sink(scaleHvn());
		await settle();
		expect(core.onNotification).toHaveBeenCalledTimes(50);
		expect(lines).toEqual([]);
	});

	it('the scale manager still reports any other notification failure', async () => {
		const lines: string[] = [];
		const sink = await scaleSink(failingCore(() => new Error('bad packet')), lines);
		sink(scaleHvn());
		await settle();
		expect(lines).toEqual([expect.stringContaining('Scale notification processing failed: ')]);
	});
});
