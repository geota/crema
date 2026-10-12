/**
 * `$lib/firmware/flash-driver` — the web I/O half of the firmware flash.
 *
 * Three layers:
 * - the core's golden `FlashStep` fixture (`core/de1-app/tests/fixtures/
 *   flash-steps.json`, drift-checked by a Rust test) replayed through the
 *   driver pins the bytes and targets the shell actually writes;
 * - a scripted core proves the mechanics (one action at a time, results
 *   routed back, timeouts, ticks while an action is in flight, the
 *   cancel race, the exclusive link and keep-alive);
 * - the real wasm core proves the wiring: refusals, and — when
 *   `CREMA_FIRMWARE_DIR` holds Decent's `de1nightly.dat` locally (crema never
 *   commits firmware) — a whole flash against a simulated DE1 (reported as
 *   skipped, with the reason, otherwise).
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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
import { FlashDriver, withTimeout, type FlashCore, type FlashIo } from './flash-driver';

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
		locksWrites: phase !== FlashPhase.Succeeded && phase !== FlashPhase.Failed,
		eraseSettling: false,
		canExit: false,
		verifyUnconfirmed: false,
		machineRestarted: false,
		linkUp: true,
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
	let link: { lost(): void; restored?(): void } | null = null;
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
	const next =
		(name: keyof FlashCore) =>
		async (..._args: unknown[]): Promise<FlashStep> => {
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
		firmwareFlashExit: vi.fn(async () => true),
		firmwareFlashStatus: vi.fn(next('firmwareFlashStatus')),
		firmwareFlashRestoreAwaiting: vi.fn(next('firmwareFlashRestoreAwaiting')),
		firmwareFlashRestoreEraseHold: vi.fn(next('firmwareFlashRestoreEraseHold'))
	} satisfies FlashCore;
	return core;
}

const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));
const noTimer = { setInterval: () => 0, clearInterval: () => undefined };

describe('FlashDriver writes exactly the bytes the core emits (golden fixture)', () => {
	const fixture = JSON.parse(
		readFileSync(resolve(process.cwd(), '../core/de1-app/tests/fixtures/flash-steps.json'), 'utf8')
	) as Record<string, FlashStep>;

	it('Sleep, erase, chunk 0, chunk 0x1234, verify, cancel Sleep, build read', async () => {
		const ack = (s: FlashStep): FlashStep => ({ actions: s.actions, status: s.status });
		const core = scriptedCore({
			firmwareFlashStart: [fixture.start],
			firmwareFlashOnWriteAck: [
				{ actions: [], status: fixture.start.status },
				{ actions: [], status: fixture.erase.status },
				ack(fixture.chunk0x1234),
				ack(fixture.verify),
				ack(fixture.readBuild)
			],
			firmwareFlashTick: [fixture.erase],
			firmwareFlashOnFwMap: [fixture.chunk0]
		});
		const f = fakeIo();
		let tick: () => void = () => undefined;
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			setInterval: (fn) => {
				tick = fn;
				return 1;
			},
			clearInterval: () => undefined
		});
		await driver.start(new Uint8Array(16), OK);
		await settle();
		tick(); // → erase
		await settle();
		f.notify([0, 0, 0, 1, 0xff, 0xff, 0xff]); // → chunk 0, then the scripted rest
		await settle(60);
		const sent = f.writes.map((w) => [w.target, w.data.slice(0, 7)]);
		expect(sent[0]).toEqual([WriteTarget.De1RequestedState, [0x00]]);
		expect(sent[1]).toEqual([WriteTarget.De1FwMapRequest, [0, 0, 1, 1, 0xff, 0xff, 0xff]]);
		expect(sent[2]).toEqual([WriteTarget.De1MmrWrite, [0x10, 0, 0, 0, 0, 1, 2]]);
		// Chunk 0x1234: offset 0x12340, big-endian, length byte 0x10.
		expect(sent[3][0]).toBe(WriteTarget.De1MmrWrite);
		expect((sent[3][1] as number[]).slice(0, 4)).toEqual([0x10, 0x01, 0x23, 0x40]);
		expect(f.writes[3].data.length).toBe(20);
		expect(sent[4]).toEqual([WriteTarget.De1FwMapRequest, [0, 0, 0, 1, 0xff, 0xff, 0xff]]);
		// The build read is an MMR read request for 0x800010, through the lock.
		expect(f.writes[5].target).toBe(WriteTarget.De1MmrRequest);
		expect(f.writes[5].data.slice(0, 4)).toEqual([0x00, 0x80, 0x00, 0x10]);
		expect(fixture.cancel.actions).toEqual([write(WriteTarget.De1RequestedState, [0])]);
	});
});

describe('FlashDriver mechanics (scripted core)', () => {
	it('performs actions one at a time and reports every ACK', async () => {
		const up = status(FlashPhase.Uploading);
		const core = scriptedCore({
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
			...noTimer
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
		const core = scriptedCore({
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
		const driver = new FlashDriver(core, f.io, { onStatus: (s) => statuses.push(s), ...noTimer });
		await driver.start(new Uint8Array(32), OK);
		await settle();
		expect(core.firmwareFlashOnWriteFailed).toHaveBeenCalledTimes(1);
		expect(core.firmwareFlashOnWriteFailed.mock.calls[0][0]).toContain('GATT 133');
		expect(statuses.at(-1)?.phase).toBe(FlashPhase.Failed);
		expect(f.exclusive.at(-1)).toBe(false);
	});

	it('a write that never settles times out and is reported (audit M4)', async () => {
		const core = scriptedCore({
			firmwareFlashStart: [step([write(WriteTarget.De1MmrWrite, [1])], status(FlashPhase.Uploading))]
		});
		const f = fakeIo({ write: () => new Promise<void>(() => undefined) });
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			writeTimeoutMs: 30,
			...noTimer
		});
		await driver.start(new Uint8Array(32), OK);
		await settle(80);
		expect(core.firmwareFlashOnWriteFailed).toHaveBeenCalledTimes(1);
		expect(core.firmwareFlashOnWriteFailed.mock.calls[0][0]).toContain('timed out');
	});

	it('ticks reach the core while an action is in flight (audit M4)', async () => {
		let tick: () => void = () => undefined;
		let release: () => void = () => undefined;
		const core = scriptedCore({
			firmwareFlashStart: [step([write(WriteTarget.De1MmrWrite, [1])], status(FlashPhase.Uploading))]
		});
		const f = fakeIo({
			write: () =>
				new Promise<void>((r) => {
					release = r;
				})
		});
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			setInterval: (fn) => {
				tick = fn;
				return 1;
			},
			clearInterval: () => undefined,
			writeTimeoutMs: 10_000
		});
		await driver.start(new Uint8Array(32), OK);
		await settle(5);
		tick();
		tick(); // coalesced
		release();
		await settle();
		expect(core.firmwareFlashTick).toHaveBeenCalledTimes(1);
	});

	it('a chunk queued before a cancel is never written after it (audit L1)', async () => {
		let release: () => void = () => undefined;
		let calls = 0;
		const core = scriptedCore({
			firmwareFlashStart: [step([write(WriteTarget.De1MmrWrite, [1])], status(FlashPhase.Uploading))],
			// Chunk 1's ACK queues chunk 2 — but the cancel was asked first.
			firmwareFlashOnWriteAck: [step([write(WriteTarget.De1MmrWrite, [2])], status(FlashPhase.Uploading))],
			firmwareFlashCancel: [
				step(
					[write(WriteTarget.De1RequestedState, [0])],
					status(FlashPhase.Cancelled, { locksWrites: false })
				)
			]
		});
		const f = fakeIo({
			write: async (target, data) => {
				calls++;
				if (calls === 1) await new Promise<void>((r) => (release = r));
				f.writes.push({ target, data: [...data] });
			}
		});
		const driver = new FlashDriver(core, f.io, { onStatus: () => undefined, ...noTimer });
		await driver.start(new Uint8Array(32), OK);
		await settle(5);
		const cancelled = driver.cancel();
		release();
		await cancelled;
		await settle();
		expect(f.writes.map((w) => w.data)).toEqual([[1], [0]]);
	});

	it('poll reads, notifications and link recovery reach the core', async () => {
		const core = scriptedCore({
			firmwareFlashStart: [step([{ type: 'ReadFwMap' }], status(FlashPhase.Erasing))]
		});
		const f = fakeIo();
		const driver = new FlashDriver(core, f.io, { onStatus: () => undefined, ...noTimer });
		await driver.start(new Uint8Array(32), OK);
		await settle();
		expect(Array.from(core.firmwareFlashOnFwMap.mock.calls[0][0] as Uint8Array)).toEqual([
			0, 0, 0, 1, 0xff, 0xff, 0xfd
		]);
		f.notify([0, 0, 0, 1, 0xff, 0xff, 0xff]);
		await driver.linkRestored();
		await settle();
		expect(core.firmwareFlashOnFwMap).toHaveBeenCalledTimes(2);
		expect(core.firmwareFlashLinkRestored).toHaveBeenCalledTimes(1);
	});

	it('a failed or hung poll read is reported, not fatal', async () => {
		const core = scriptedCore({
			firmwareFlashStart: [step([{ type: 'ReadFwMap' }], status(FlashPhase.Verifying))]
		});
		const f = fakeIo({ readFwMap: () => new Promise<Uint8Array>(() => undefined) });
		const driver = new FlashDriver(core, f.io, {
			onStatus: () => undefined,
			readTimeoutMs: 20,
			...noTimer
		});
		await driver.start(new Uint8Array(32), OK);
		await settle(60);
		expect(core.firmwareFlashOnReadFailed).toHaveBeenCalledTimes(1);
	});

	it('a refused start returns the core error and attaches nothing', async () => {
		const core = scriptedCore({
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

	it('withTimeout rejects a promise that never settles', async () => {
		await expect(withTimeout(new Promise(() => undefined), 10, 'x')).rejects.toThrow('timed out');
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
		firmwareFlashExit: async () => bridge.firmware_flash_exit(),
		firmwareFlashStatus: async (now) => p(bridge.firmware_flash_status(now)),
		firmwareFlashRestoreAwaiting: async (b, u, now) =>
			p(bridge.firmware_flash_restore_awaiting(b, u, now)),
		firmwareFlashRestoreEraseHold: async (b, left, now) =>
			p(bridge.firmware_flash_restore_erase_hold(b, left, now))
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

const FIRMWARE_DIR = process.env.CREMA_FIRMWARE_DIR;

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

	it('a restored power-cycle wait reads the build on a fresh connect minutes later (audit r2 M-A)', async () => {
		const bridge = readyBridge();
		let clock = 10;
		const f = fakeIo({
			async write(target, data) {
				// The DE1 answers the build read with the new build.
				if (target === WriteTarget.De1MmrRequest && data[1] === 0x80 && data[3] === 0x10) {
					bridge.on_notification(NotificationSource.De1MmrRead, mmr(0x800010, 1358), clock);
				}
			}
		});
		const driver = new FlashDriver(realCore(bridge), f.io, {
			onStatus: () => undefined,
			now: () => clock,
			...noTimer
		});
		const restored = await driver.restoreAwaiting(1358, false);
		expect(restored?.locksWrites).toBe(true);
		expect(restored?.linkUp).toBe(false);
		expect(bridge.connect_sweep('{}', 20)).toContain('FirmwareLockoutHit');
		// The user clicks Connect long after the old 60 s read window.
		clock += 5 * 60_000;
		await driver.linkRestored();
		await settle();
		const after = await driver.refresh();
		expect(after?.phase).toBe(FlashPhase.Succeeded);
		expect(after?.locksWrites).toBe(false);
	});

	it('the core\'s per-attempt diagnostic lines reach the log', async () => {
		const logs: string[] = [];
		const driver = new FlashDriver(realCore(readyBridge()), fakeIo().io, {
			onStatus: () => undefined,
			onLog: (l) => logs.push(l),
			transportLabel: () => 'Web Bluetooth (test), DE1 id X',
			...noTimer
		});
		await driver.restoreAwaiting(1358, true);
		expect(logs.some((l) => /^Firmware: \[\+00:00\] restored: waiting for the DE1 to restart on build 1358/.test(l))).toBe(
			true
		);
	});

	it.skipIf(!FIRMWARE_DIR)(
		'flashes a real image end to end against a simulated DE1 (skipped: needs CREMA_FIRMWARE_DIR with de1nightly.dat; no firmware is committed)',
		async () => {
			const image = new Uint8Array(readFileSync(`${FIRMWARE_DIR}/de1nightly.dat`));
			const bridge = readyBridge();
			const bank = new Uint8Array(image.length);
			let verifyRequested = false;
			let clock = 100;
			let tick: () => void = () => undefined;
			const linkRef: { current: { lost(): void; restored?(): void } | null } = { current: null };
			const io: FlashIo = {
				async write(target, data) {
					if (target === WriteTarget.De1FwMapRequest) verifyRequested = data[2] === 0;
					if (target === WriteTarget.De1MmrWrite) {
						expect(data[0]).toBe(0x10);
						const off = (data[1] << 16) | (data[2] << 8) | data[3];
						bank.set(data.subarray(4), off);
					}
					if (target === WriteTarget.De1MmrRequest) {
						bridge.on_notification(NotificationSource.De1MmrRead, mmr(0x800010, 1358), clock);
					}
					clock += 1;
				},
				readFwMap: async () =>
					Uint8Array.from(
						verifyRequested ? [0, 0, 0, 1, 0xff, 0xff, 0xfd] : [0, 0, 0, 1, 0xff, 0xff, 0xff]
					),
				subscribeFwMap: async () => undefined,
				setExclusive: () => undefined,
				setFwMapListener: () => undefined,
				addLinkListener: (l) => {
					linkRef.current = l;
					return () => undefined;
				}
			};
			const driver = new FlashDriver(realCore(bridge), io, {
				onStatus: () => undefined,
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
				if (
					driver.current?.phase === FlashPhase.AwaitingPowerCycle &&
					!driver.current.machineRestarted
				) {
					expect(driver.current.locksWrites).toBe(true);
					bridge.de1_link_lost();
					linkRef.current?.lost();
					clock += 40_000;
					await driver.linkRestored();
				}
			}
			expect(driver.current?.phase).toBe(FlashPhase.Succeeded);
			expect(bank).toEqual(image);
		},
		120_000
	);
});
