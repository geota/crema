/**
 * `$lib/firmware/flash-driver` — the web I/O half of the firmware flash.
 *
 * Two layers:
 * - a scripted core proves the driver's mechanics (one action at a time,
 *   every result reported back, the exclusive link and keep-alive following
 *   the core's status, notifications / link events / ticks routed);
 * - the real wasm core proves the wiring end to end: refusals, and — when
 *   `CREMA_FIRMWARE_DIR` holds Decent's `de1nightly.dat` locally (crema never
 *   commits firmware) — a whole flash against a simulated DE1.
 */

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { CremaBridge, NotificationSource } from '$lib/wasm/de1_wasm.js';
import { initTestWasm } from '$lib/testing/test-init';
import {
	FlashPhase,
	WriteTarget,
	type FlashAction,
	type FlashShellConditions,
	type FlashStatus,
	type FlashStep
} from '$lib/core/crema-core';
import { FlashDriver, type FlashCore, type FlashIo } from './flash-driver';

beforeAll(initTestWasm);

const OK: FlashShellConditions = { connected: true, ready: true, guidedBrewActive: false, powerOk: true };

function status(phase: FlashPhase, extra: Partial<FlashStatus> = {}): FlashStatus {
	return {
		phase,
		artifact: {} as FlashStatus['artifact'],
		targetBuild: 1358,
		chunksTotal: 2,
		chunksAcked: 0,
		percent: 0,
		etaIsEstimate: true,
		elapsedSeconds: 0,
		canCancel: true,
		locksWrites: phase !== FlashPhase.AwaitingPowerCycle && phase !== FlashPhase.Failed,
		verifyUnconfirmed: false,
		machineRestarted: false,
		...extra
	};
}

const write = (target: WriteTarget, data: number[]): FlashAction => ({
	type: 'Write',
	content: { target, data }
});

function step(actions: FlashAction[], s: FlashStatus | null): FlashStep {
	return { actions, status: s ?? undefined };
}

/** A recording fake DE1 link. */
function fakeIo(over: Partial<FlashIo> = {}) {
	let inFlight = 0;
	let maxInFlight = 0;
	const writes: { target: WriteTarget; data: number[] }[] = [];
	const exclusive: boolean[] = [];
	let fwListener: ((d: Uint8Array, t: number) => void) | null = null;
	let link: { lost(): void; restored(): void } | null = null;
	const io: FlashIo = {
		async write(target, data) {
			inFlight++;
			maxInFlight = Math.max(maxInFlight, inFlight);
			await new Promise((r) => setTimeout(r, 1));
			writes.push({ target, data: [...data] });
			inFlight--;
		},
		readFwMap: async () => Uint8Array.from([0, 0, 0, 1, 0xff, 0xff, 0xfd]),
		subscribeFwMap: async () => undefined,
		readBuild: async () => undefined,
		setExclusive: (on) => exclusive.push(on),
		setFwMapListener: (l) => {
			fwListener = l;
		},
		addLinkListener: (l) => {
			link = l;
			return () => {
				link = null;
			};
		},
		...over
	};
	return {
		io,
		writes,
		exclusive,
		maxInFlight: () => maxInFlight,
		notify: (d: number[]) => fwListener?.(Uint8Array.from(d), 1),
		link: () => link
	};
}

/** A scripted core: each method returns the next queued step. */
function scriptedCore(script: Partial<Record<keyof FlashCore, FlashStep[]>>) {
	const calls: string[] = [];
	const next =
		(name: keyof FlashCore) =>
		async (..._args: unknown[]): Promise<FlashStep> => {
			calls.push(name);
			const q = script[name];
			return q && q.length > 0 ? (q.shift() as FlashStep) : step([], null);
		};
	const core = {
		firmwareFlashStart: vi.fn(next('firmwareFlashStart')),
		firmwareFlashOnWriteAck: vi.fn(next('firmwareFlashOnWriteAck')),
		firmwareFlashOnWriteFailed: vi.fn(next('firmwareFlashOnWriteFailed')),
		firmwareFlashOnFwMap: vi.fn(next('firmwareFlashOnFwMap')),
		firmwareFlashOnReadFailed: vi.fn(next('firmwareFlashOnReadFailed')),
		firmwareFlashTick: vi.fn(next('firmwareFlashTick')),
		firmwareFlashLinkRestored: vi.fn(next('firmwareFlashLinkRestored')),
		firmwareFlashCancel: vi.fn(next('firmwareFlashCancel')),
		firmwareFlashDismiss: vi.fn(async () => true),
		firmwareFlashStatus: vi.fn(next('firmwareFlashStatus'))
	} satisfies FlashCore;
	return { core, calls };
}

const settle = () => new Promise((r) => setTimeout(r, 20));

describe('FlashDriver mechanics (scripted core)', () => {
	it('performs actions one at a time and reports every ACK', async () => {
		const up = status(FlashPhase.Uploading);
		const { core } = scriptedCore({
			firmwareFlashStart: [
				step(
					[{ type: 'SubscribeFwMap' }, write(WriteTarget.De1RequestedState, [0])],
					status(FlashPhase.Preparing)
				)
			],
			firmwareFlashOnWriteAck: [
				step([write(WriteTarget.De1MmrWrite, [16, 0, 0, 0])], up),
				step([write(WriteTarget.De1MmrWrite, [16, 0, 0, 16])], up),
				step([], status(FlashPhase.Verifying))
			]
		});
		const f = fakeIo();
		const keep: boolean[] = [];
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			keepAlive: { set: (a) => keep.push(a) },
			setInterval: () => 0,
			clearInterval: () => undefined
		});
		expect(await driver.start(new Uint8Array(32), OK)).toBeNull();
		await settle();
		expect(f.writes.map((w) => w.data)).toEqual([[0], [16, 0, 0, 0], [16, 0, 0, 16]]);
		expect(f.maxInFlight()).toBe(1);
		expect(core.firmwareFlashOnWriteAck).toHaveBeenCalledTimes(3);
		expect(f.exclusive.at(-1)).toBe(true);
		expect(keep.at(-1)).toBe(true);
	});

	it('a failed write is reported and the queue is dropped', async () => {
		const { core } = scriptedCore({
			firmwareFlashStart: [
				step(
					[write(WriteTarget.De1MmrWrite, [1]), write(WriteTarget.De1MmrWrite, [2])],
					status(FlashPhase.Uploading)
				)
			],
			firmwareFlashOnWriteFailed: [step([], status(FlashPhase.Failed, { locksWrites: false }))]
		});
		const f = fakeIo({
			write: async () => {
				throw new Error('GATT 133');
			}
		});
		const statuses: (FlashStatus | null)[] = [];
		const driver = new FlashDriver(core, f.io, {
			onStatus: (s) => statuses.push(s),
			setInterval: () => 0,
			clearInterval: () => undefined
		});
		await driver.start(new Uint8Array(32), OK);
		await settle();
		expect(core.firmwareFlashOnWriteFailed).toHaveBeenCalledTimes(1);
		expect(core.firmwareFlashOnWriteFailed.mock.calls[0][0]).toContain('GATT 133');
		expect(statuses.at(-1)?.phase).toBe(FlashPhase.Failed);
		expect(f.exclusive.at(-1)).toBe(false);
	});

	it('poll reads, notifications, link recovery and ticks reach the core', async () => {
		let tick: () => void = () => undefined;
		const { core } = scriptedCore({
			firmwareFlashStart: [step([{ type: 'ReadFwMap' }], status(FlashPhase.Erasing))]
		});
		const f = fakeIo();
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			setInterval: (fn) => {
				tick = fn;
				return 1;
			},
			clearInterval: () => undefined
		});
		await driver.start(new Uint8Array(32), OK);
		await settle();
		expect(Array.from(core.firmwareFlashOnFwMap.mock.calls[0][0] as Uint8Array)).toEqual([
			0, 0, 0, 1, 0xff, 0xff, 0xfd
		]);
		f.notify([0, 0, 0, 1, 0xff, 0xff, 0xff]);
		f.link()?.restored();
		await settle();
		// A tick while an action is in flight is skipped; an idle one runs.
		tick();
		await settle();
		expect(core.firmwareFlashOnFwMap).toHaveBeenCalledTimes(2);
		expect(core.firmwareFlashLinkRestored).toHaveBeenCalledTimes(1);
		expect(core.firmwareFlashTick).toHaveBeenCalled();
	});

	it('a failed poll read is reported, not fatal', async () => {
		const { core } = scriptedCore({
			firmwareFlashStart: [step([{ type: 'ReadFwMap' }], status(FlashPhase.Verifying))]
		});
		const f = fakeIo({
			readFwMap: async () => {
				throw new Error('busy');
			}
		});
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			setInterval: () => 0,
			clearInterval: () => undefined
		});
		await driver.start(new Uint8Array(32), OK);
		await settle();
		expect(core.firmwareFlashOnReadFailed).toHaveBeenCalledTimes(1);
	});

	it('a refused start returns the core error and attaches nothing', async () => {
		const { core } = scriptedCore({
			firmwareFlashStart: [
				{
					actions: [],
					error: { type: 'Blocked', content: { blockers: [{ type: 'LowPower' }] } }
				}
			]
		});
		const f = fakeIo();
		const driver = new FlashDriver(core, f.io, { onStatus: () => undefined });
		const err = await driver.start(new Uint8Array(1), OK);
		expect(err).toEqual({ type: 'Blocked', content: { blockers: [{ type: 'LowPower' }] } });
		expect(f.link()).toBeNull();
	});
});

/** The real wasm core behind the FlashCore surface (the facade's mapping). */
function realCore(bridge: CremaBridge): FlashCore {
	const p = (raw: string) => JSON.parse(raw) as FlashStep;
	return {
		firmwareFlashStart: async (image, c, now) =>
			p(bridge.firmware_flash_start(image, JSON.stringify(c), now)),
		firmwareFlashOnWriteAck: async (now) => p(bridge.firmware_flash_on_write_ack(now)),
		firmwareFlashOnWriteFailed: async (m, now) => p(bridge.firmware_flash_on_write_failed(m, now)),
		firmwareFlashOnFwMap: async (d, now) => p(bridge.firmware_flash_on_fw_map(d, now)),
		firmwareFlashOnReadFailed: async (now) => p(bridge.firmware_flash_on_read_failed(now)),
		firmwareFlashTick: async (now) => p(bridge.firmware_flash_tick(now)),
		firmwareFlashLinkRestored: async (now) => p(bridge.firmware_flash_link_restored(now)),
		firmwareFlashCancel: async (now) => p(bridge.firmware_flash_cancel(now)),
		firmwareFlashDismiss: async () => bridge.firmware_flash_dismiss(),
		firmwareFlashStatus: async (now) => p(bridge.firmware_flash_status(now))
	};
}

function mmr(address: number, value: number): Uint8Array {
	const b = new Uint8Array(20);
	b[1] = (address >> 16) & 0xff;
	b[2] = (address >> 8) & 0xff;
	b[3] = address & 0xff;
	new DataView(b.buffer).setUint32(4, value, true);
	return b;
}

/** A connected, idle DE1Pro on build 1333. */
function readyBridge(): CremaBridge {
	const bridge = new CremaBridge();
	bridge.on_notification(NotificationSource.De1MmrRead, mmr(0x800010, 1333), 1);
	bridge.on_notification(NotificationSource.De1MmrRead, mmr(0x80000c, 3), 2);
	bridge.on_notification(NotificationSource.De1State, Uint8Array.from([2, 0]), 3);
	return bridge;
}

describe('FlashDriver with the real core', () => {
	it('the core refuses a file that is not a pinned official image', async () => {
		const f = fakeIo();
		const driver = new FlashDriver(realCore(readyBridge()), f.io, { onStatus: () => undefined });
		const err = await driver.start(new Uint8Array(4096).fill(0x42), OK);
		expect(err?.type).toBe('InvalidImage');
		expect(f.writes).toEqual([]);
	});

	it('the core refuses when a precondition fails', async () => {
		const driver = new FlashDriver(realCore(new CremaBridge()), fakeIo().io, {
			onStatus: () => undefined
		});
		const err = await driver.start(new Uint8Array(64), { ...OK, connected: false });
		expect(err?.type).toBe('Blocked');
	});

	it('flashes a real image end to end against a simulated DE1 (needs CREMA_FIRMWARE_DIR)', async () => {
		const dir = process.env.CREMA_FIRMWARE_DIR;
		if (!dir) return;
		const { readFile } = await import('node:fs/promises');
		const image = new Uint8Array(await readFile(`${dir}/de1nightly.dat`));
		const bridge = readyBridge();
		const bank = new Uint8Array(image.length);
		let verifyRequested = false;
		let clock = 100;
		let tick: () => void = () => undefined;
		const linkRef: { current: { lost(): void; restored(): void } | null } = { current: null };
		const io: FlashIo = {
			async write(target, data) {
				if (target === WriteTarget.De1FwMapRequest) verifyRequested = data[2] === 0;
				if (target === WriteTarget.De1MmrWrite) {
					expect(data[0]).toBe(0x10);
					const off = (data[1] << 16) | (data[2] << 8) | data[3];
					bank.set(data.subarray(4), off);
				}
				clock += 1;
			},
			readFwMap: async () =>
				Uint8Array.from(verifyRequested ? [0, 0, 0, 1, 0xff, 0xff, 0xfd] : [0, 0, 0, 1, 0xff, 0xff, 0xff]),
			subscribeFwMap: async () => undefined,
			readBuild: async () => {
				bridge.on_notification(NotificationSource.De1MmrRead, mmr(0x800010, 1358), clock);
			},
			setExclusive: () => undefined,
			setFwMapListener: () => undefined,
			addLinkListener: (l) => {
				linkRef.current = l;
				return () => undefined;
			}
		};
		let last: FlashStatus | null = null;
		const driver = new FlashDriver(realCore(bridge), io, {
			onStatus: (s) => {
				last = s;
			},
			now: () => clock,
			setInterval: (fn) => {
				tick = fn;
				return 1;
			},
			clearInterval: () => undefined
		});
		expect(await driver.start(image, OK)).toBeNull();
		for (let i = 0; i < 10_000 && driver.current?.phase !== FlashPhase.Succeeded; i++) {
			clock += 250;
			tick();
			await new Promise((r) => setTimeout(r, 0));
			if (driver.current?.phase === FlashPhase.AwaitingPowerCycle && !driver.current.machineRestarted) {
				bridge.de1_link_lost();
				linkRef.current?.lost();
				clock += 40_000;
				linkRef.current?.restored();
			}
		}
		expect(driver.current?.phase).toBe(FlashPhase.Succeeded);
		expect(bank).toEqual(image);
		expect(last).not.toBeNull();
	}, 120_000);
});
