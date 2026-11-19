/**
 * `#lib/ble/scale-flash-pause.js` — scale Bluetooth stands down while a DE1
 * firmware flash runs (Decenza 270b89df). The pause is driven by the flash
 * driver's keep-alive, as the app wires it, so these runs go through the
 * driver with a scripted core: start → the scale stands down and its
 * connects are held; succeeded / failed / cancelled → it comes back; a flash
 * restored after a reload pauses it until the restored hold ends.
 */

import { describe, expect, it, vi } from 'vitest';
import { FlashPhase, WriteTarget, type FlashAction, type FlashShellConditions, type FlashStatus, type FlashStep } from '#lib/core/crema-core.js';
import { FlashDriver, type FlashCore, type FlashIo } from '#lib/firmware/flash-driver.js';
import type { CremaCore } from '#lib/core/index.js';
import { ScaleFlashPause } from './scale-flash-pause';
import { ScaleManager } from './scale';

const OK: FlashShellConditions = { connected: true, ready: true, guidedBrewActive: false, powerOk: true };

function status(phase: FlashPhase, locksWrites = true): FlashStatus {
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
		locksWrites,
		eraseSettling: false,
		canExit: false,
		verifyUnconfirmed: false,
		machineRestarted: false,
		linkUp: true,
		identityUnknown: false
	};
}

const write = (data: number[]): FlashAction => ({ type: 'Write', content: { target: WriteTarget.De1MmrWrite, data } });
const step = (actions: FlashAction[], s: FlashStatus | null): FlashStep => ({ actions, status: s ?? undefined });

/** A scripted core: each method returns its next queued step (none = no flash). */
function scriptedCore(script: Partial<Record<keyof FlashCore, FlashStep[]>>): FlashCore {
	const next = (name: keyof FlashCore) => async (): Promise<FlashStep> => script[name]?.shift() ?? step([], null);
	return {
		firmwareFlashStart: next('firmwareFlashStart'),
		firmwareFlashOnWriteAck: next('firmwareFlashOnWriteAck'),
		firmwareFlashOnWriteFailed: next('firmwareFlashOnWriteFailed'),
		firmwareFlashOnFwMap: next('firmwareFlashOnFwMap'),
		firmwareFlashOnReadFailed: next('firmwareFlashOnReadFailed'),
		firmwareFlashTick: next('firmwareFlashTick'),
		firmwareFlashLinkRestored: next('firmwareFlashLinkRestored'),
		firmwareFlashBindMachine: async () => undefined,
		firmwareFlashCancel: next('firmwareFlashCancel'),
		firmwareFlashDismiss: async () => true,
		firmwareFlashExit: async () => true,
		firmwareFlashStatus: next('firmwareFlashStatus'),
		firmwareFlashRestoreAwaiting: next('firmwareFlashRestoreAwaiting'),
		firmwareFlashRestoreEraseHold: next('firmwareFlashRestoreEraseHold'),
		firmwareFlashRestoreVerifying: next('firmwareFlashRestoreVerifying')
	} satisfies FlashCore;
}

function fakeIo(write: FlashIo['write'] = async () => new Promise((r) => setTimeout(r, 1))): FlashIo {
	return {
		write,
		readFwMap: async () => Uint8Array.from([0, 0, 0, 1, 0xff, 0xff, 0xfd]),
		subscribeFwMap: async () => undefined,
		setExclusive: () => undefined,
		setFwMapListener: () => undefined,
		addLinkListener: () => () => undefined
	};
}

/** The pause, its recorded scale side, and a driver whose keep-alive feeds it. */
function harness(core: FlashCore, io: FlashIo, wanted = true) {
	const events: string[] = [];
	const pause = new ScaleFlashPause({
		scaleWanted: () => wanted,
		standDown: () => events.push('standDown'),
		bringBack: () => events.push('bringBack'),
		log: () => undefined
	});
	let tick: () => void = () => undefined;
	const driver = new FlashDriver(core, io, {
		onStatus: () => undefined,
		keepAlive: { set: (active) => pause.onFlashActive(active) },
		setInterval: (fn) => {
			tick = fn;
			return 1;
		},
		clearInterval: () => undefined
	});
	return { pause, events, driver, tick: () => tick() };
}

const settle = (ms = 20) => new Promise((r) => setTimeout(r, ms));

describe('scale Bluetooth during a firmware flash (Decenza 270b89df)', () => {
	it('stands down when the flash starts, holds connects, and comes back once it is verified', async () => {
		const h = harness(
			scriptedCore({
				firmwareFlashStart: [step([write([1])], status(FlashPhase.Uploading))],
				firmwareFlashOnWriteAck: [step([], status(FlashPhase.Verifying))],
				firmwareFlashTick: [step([], status(FlashPhase.AwaitingPowerCycle))]
			}),
			fakeIo()
		);
		await h.driver.start(new Uint8Array(32), OK);
		await settle();
		expect(h.events).toEqual(['standDown']);
		expect(h.pause.paused).toBe(true);
		// A tap, a launch reconnect or a visibility kick mid-flash is held.
		expect(h.pause.holdConnect()).toBe(true);
		h.tick();
		await settle();
		expect(h.pause.paused).toBe(false);
		expect(h.events).toEqual(['standDown', 'bringBack']);
		expect(h.pause.holdConnect()).toBe(false);
	});

	it('comes back when the flash fails', async () => {
		const h = harness(
			scriptedCore({
				firmwareFlashStart: [step([write([1])], status(FlashPhase.Uploading))],
				firmwareFlashOnWriteFailed: [step([], status(FlashPhase.Failed, false))]
			}),
			fakeIo(async () => {
				throw new Error('GATT 133');
			})
		);
		await h.driver.start(new Uint8Array(32), OK);
		await settle();
		expect(h.pause.paused).toBe(false);
		expect(h.events).toEqual(['standDown', 'bringBack']);
	});

	it('comes back when the flash is cancelled', async () => {
		let release: () => void = () => undefined;
		const h = harness(
			scriptedCore({
				firmwareFlashStart: [step([write([1])], status(FlashPhase.Uploading))],
				firmwareFlashCancel: [step([], status(FlashPhase.Cancelled, false))]
			}),
			fakeIo(() => new Promise<void>((r) => (release = r)))
		);
		await h.driver.start(new Uint8Array(32), OK);
		await settle(5);
		expect(h.pause.paused).toBe(true);
		const cancelled = h.driver.cancel();
		release();
		await cancelled;
		await settle();
		expect(h.pause.paused).toBe(false);
		expect(h.events).toEqual(['standDown', 'bringBack']);
	});

	it('a flash restored after a reload pauses the scale until the restored hold ends', async () => {
		const h = harness(
			scriptedCore({
				firmwareFlashRestoreEraseHold: [step([], status(FlashPhase.Erasing))],
				firmwareFlashStatus: [step([], status(FlashPhase.Erasing))],
				firmwareFlashTick: [step([], status(FlashPhase.AwaitingPowerCycle))]
			}),
			fakeIo()
		);
		await h.driver.restoreEraseHold(1358, 5_000);
		expect(h.pause.paused).toBe(true);
		h.tick();
		await settle();
		expect(h.pause.paused).toBe(false);
		expect(h.events).toEqual(['standDown', 'bringBack']);
	});

	it('a scale nobody wanted stays away, unless a connect was asked for mid-flash', async () => {
		const run = async (askMidFlash: boolean) => {
			const h = harness(
				scriptedCore({
					firmwareFlashStart: [step([write([1])], status(FlashPhase.Uploading))],
					firmwareFlashOnWriteAck: [step([], status(FlashPhase.Failed, false))]
				}),
				fakeIo(() => new Promise<void>((r) => setTimeout(r, 10))),
				false
			);
			await h.driver.start(new Uint8Array(32), OK);
			if (askMidFlash) h.pause.holdConnect();
			await settle(40);
			return h.events;
		};
		expect(await run(false)).toEqual(['standDown']);
		expect(await run(true)).toEqual(['standDown', 'bringBack']);
	});
});

describe('ScaleManager held for a firmware flash', () => {
	it('refuses both connect paths and ignores kicks until released', async () => {
		const onState = vi.fn();
		const onStatus = vi.fn();
		const scale = new ScaleManager({} as CremaCore, {
			onState,
			onStatus,
			onCoreOutput: () => undefined,
			onIdentified: () => undefined,
			onWriteFailed: () => undefined,
			onReconnected: () => undefined
		} as unknown as ConstructorParameters<typeof ScaleManager>[1]);
		scale.heldForFirmwareFlash = true;
		await scale.connect();
		await scale.connectKnown({ id: 'x', name: 'BOOKOO_SC' } as BluetoothDevice);
		expect(scale.kick('foreground')).toBe('ignored-idle');
		expect(onState).not.toHaveBeenCalled();
		expect(onStatus).toHaveBeenCalledWith('Scale waits for the firmware update');
	});
});
