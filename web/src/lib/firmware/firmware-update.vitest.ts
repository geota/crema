/**
 * `$lib/firmware/firmware-update` — the flow's gating: the alpha warning must
 * be acknowledged, the default build is the newest above the installed one,
 * a CORS-blocked download falls back to the manual file, a bad file never
 * reaches the confirm step, blockers stop the start, cancel and exit are
 * confirmed, the power-cycle marker survives a reload, the shell hears when
 * the flash lets go of the link, and the flow can't close while it runs.
 */

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import {
	FlashPhase,
	type FlashBlocker,
	type FlashStatus,
	type FlashStep
} from '$lib/core/crema-core';
import { heldBannerText, waitingText } from './flash-copy';
import {
	AWAITING_KEY,
	markerFor,
	FirmwareUpdateController,
	type FirmwareUpdateDeps,
	type MarkerStore
} from './firmware-update.svelte';
import type { FlashIo } from './flash-driver';

beforeAll(initTestWasm);

const noStep = async (): Promise<FlashStep> => ({ actions: [] });

function status(phase: FlashPhase, extra: Partial<FlashStatus> = {}): FlashStatus {
	return {
		phase,
		artifact: {} as never,
		targetBuild: 1358,
		chunksTotal: 10,
		chunksAcked: 3,
		percent: 30,
		etaIsEstimate: false,
		elapsedSeconds: 60,
		canCancel: true,
		locksWrites: true,
		eraseSettling: false,
		canExit: false,
		verifyUnconfirmed: false,
		machineRestarted: false,
		linkUp: false,
		...extra
	};
}

function memoryStore(): MarkerStore & { data: Map<string, string> } {
	const data = new Map<string, string>();
	return {
		data,
		getItem: (k) => data.get(k) ?? null,
		setItem: (k, v) => void data.set(k, v),
		removeItem: (k) => void data.delete(k)
	};
}

function deps(over: {
	installed?: number | null;
	blockers?: FlashBlocker[];
	fetchFn?: typeof fetch;
	status?: FlashStep;
	cancel?: FlashStep;
	store?: MarkerStore | null;
	restore?: FlashStep;
	restoreHold?: FlashStep;
	onRelease?: () => void;
	machineId?: string | null;
	linkUp?: boolean;
	wallNow?: number;
	linkRestored?: () => FlashStep;
}): FirmwareUpdateDeps {
	const io: FlashIo = {
		write: async () => undefined,
		readFwMap: async () => new Uint8Array(7),
		subscribeFwMap: async () => undefined,
		setExclusive: () => undefined,
		setFwMapListener: () => undefined,
		addLinkListener: () => () => undefined
	};
	return {
		core: {
			firmwareFlashBlockers: vi.fn(async () => over.blockers ?? []),
			firmwareFlashStart: vi.fn(noStep),
			firmwareFlashOnWriteAck: noStep,
			firmwareFlashOnWriteFailed: noStep,
			firmwareFlashOnFwMap: noStep,
			firmwareFlashOnReadFailed: noStep,
			firmwareFlashTick: noStep,
			firmwareFlashLinkRestored: vi.fn(async () => over.linkRestored?.() ?? { actions: [] }),
			firmwareFlashCancel: vi.fn(async () => over.cancel ?? { actions: [] }),
			firmwareFlashDismiss: async () => true,
			firmwareFlashExit: vi.fn(async () => true),
			firmwareFlashStatus: async () => over.status ?? { actions: [] },
			firmwareFlashRestoreAwaiting: vi.fn(async () => over.restore ?? { actions: [] }),
			firmwareFlashRestoreEraseHold: vi.fn(async () => over.restoreHold ?? { actions: [] })
		},
		io,
		conditions: async () => ({ connected: true, ready: true, guidedBrewActive: false, powerOk: true }),
		installedBuild: () => over.installed ?? 1333,
		log: () => undefined,
		keepAlive: { set: () => undefined },
		store: over.store === undefined ? null : over.store,
		onRelease: over.onRelease,
		machineId: () => over.machineId ?? null,
		linkUp: () => over.linkUp ?? false,
		wallNow: () => over.wallNow ?? 1_000_000,
		fetchFn:
			over.fetchFn ??
			((async () => {
				throw new TypeError('Failed to fetch');
			}) as unknown as typeof fetch)
	};
}

describe('FirmwareUpdateController', () => {
	it('opens on the alpha warning and needs "I understand"', async () => {
		const flow = new FirmwareUpdateController(deps({}));
		await flow.open();
		expect(flow.step).toBe('warning');
		flow.acceptWarning();
		expect(flow.step).toBe('warning');
		flow.acknowledged = true;
		flow.acceptWarning();
		expect(flow.step).toBe('choose');
	});

	it('defaults to the newest build above the installed one', async () => {
		const a = new FirmwareUpdateController(deps({ installed: 1333 }));
		await a.open();
		expect(a.selected?.build).toBe(1358);
		const b = new FirmwareUpdateController(deps({ installed: 1358 }));
		await b.open();
		expect(b.selected?.build).toBe(1358);
	});

	it('a CORS-blocked download offers the manual file, and a bad file is refused', async () => {
		const flow = new FirmwareUpdateController(deps({}));
		await flow.open();
		await flow.fetchImage();
		expect(flow.step).toBe('source');
		expect(flow.manualNeeded).toBe(true);
		await flow.useFile(new Blob([new Uint8Array(4096).fill(7)]));
		expect(flow.sourceProblem).toContain("isn't DE1 firmware");
		expect(flow.step).toBe('source');
		expect(flow.image).toBeNull();
	});

	it('Back during a download wins over the late result (audit L5)', async () => {
		let resolve: (r: Response) => void = () => undefined;
		const flow = new FirmwareUpdateController(
			deps({
				fetchFn: (() => new Promise<Response>((r) => (resolve = r))) as unknown as typeof fetch
			})
		);
		await flow.open();
		const pending = flow.fetchImage();
		flow.backToChoose();
		resolve(new Response(new Uint8Array(16)));
		await pending;
		expect(flow.step).toBe('choose');
		expect(flow.sourceProblem).toBeNull();
	});

	it('blockers keep the flow on the confirm step', async () => {
		const d = deps({ blockers: [{ type: 'LowPower' }] });
		const flow = new FirmwareUpdateController(d);
		await flow.open();
		flow.image = new Uint8Array(16);
		await flow.startFlash();
		expect(flow.step).toBe('confirm');
		expect(flow.blockers).toEqual([{ type: 'LowPower' }]);
		expect(d.core.firmwareFlashStart).not.toHaveBeenCalled();
	});

	it('reopens on the running flash and refuses to close while it runs', async () => {
		const flow = new FirmwareUpdateController(
			deps({ status: { actions: [], status: status(FlashPhase.Uploading) } })
		);
		await flow.open();
		expect(flow.step).toBe('flash');
		expect(flow.running).toBe(true);
		await flow.close();
		expect(flow.step).toBe('flash');
		flow.dispose();
	});

	it('cancel asks first; Keep updating backs out; Stop cancels', async () => {
		const d = deps({
			status: { actions: [], status: status(FlashPhase.Uploading) },
			cancel: { actions: [], status: status(FlashPhase.Cancelled, { locksWrites: false }) }
		});
		const flow = new FirmwareUpdateController(d);
		await flow.open();
		flow.requestCancel();
		expect(flow.confirmingCancel).toBe(true);
		flow.keepUpdating();
		expect(flow.confirmingCancel).toBe(false);
		expect(d.core.firmwareFlashCancel).not.toHaveBeenCalled();
		flow.requestCancel();
		await flow.confirmCancel();
		expect(d.core.firmwareFlashCancel).toHaveBeenCalledTimes(1);
		expect(flow.status?.phase).toBe(FlashPhase.Cancelled);
		flow.dispose();
	});

	it('no cancel once verify started', async () => {
		const flow = new FirmwareUpdateController(
			deps({ status: { actions: [], status: status(FlashPhase.Verifying, { canCancel: false }) } })
		);
		await flow.open();
		flow.requestCancel();
		expect(flow.confirmingCancel).toBe(false);
		flow.dispose();
	});

	it('the power-cycle step hides rather than closes, and exit asks first', async () => {
		const store = memoryStore();
		const d = deps({
			store,
			status: {
				actions: [],
				status: status(FlashPhase.AwaitingPowerCycle, { canCancel: false, canExit: true })
			}
		});
		const flow = new FirmwareUpdateController(d);
		await flow.open();
		expect(flow.holdsLink).toBe(true);
		expect(JSON.parse(store.data.get(AWAITING_KEY) ?? 'null')).toEqual({
			build: 1358,
			unconfirmed: false,
			machine: null
		});
		await flow.close();
		expect(flow.step).toBe('closed');
		expect(flow.status?.phase).toBe(FlashPhase.AwaitingPowerCycle); // still held
		await flow.open();
		flow.requestExit();
		expect(flow.confirmingExit).toBe(true);
		await flow.confirmExit();
		expect(d.core.firmwareFlashExit).toHaveBeenCalledTimes(1);
		expect(flow.step).toBe('closed');
		expect(store.data.has(AWAITING_KEY)).toBe(false);
		flow.dispose();
	});

	it('a persisted power-cycle marker is restored into the core after a reload and shown', async () => {
		const store = memoryStore();
		store.setItem(AWAITING_KEY, JSON.stringify({ build: 1358, unconfirmed: true }));
		const awaiting = status(FlashPhase.AwaitingPowerCycle, { canExit: true });
		const d = deps({
			store,
			restore: { actions: [], status: awaiting },
			status: { actions: [], status: awaiting }
		});
		const flow = new FirmwareUpdateController(d);
		await flow.restore();
		expect(d.core.firmwareFlashRestoreAwaiting).toHaveBeenCalledWith(1358, true, expect.any(Number));
		expect(flow.holdsLink).toBe(true);
		expect(flow.step).toBe('flash'); // visible at once (audit r2 M-B)
		expect(d.core.firmwareFlashLinkRestored).not.toHaveBeenCalled(); // not connected yet
		// Any later connect path reads the build (audit r2 M-A).
		await flow.onLinkUp('dev-1');
		expect(d.core.firmwareFlashLinkRestored).toHaveBeenCalledTimes(1);
		flow.dispose();
	});

	it('a restore while already connected reads the build at once', async () => {
		const store = memoryStore();
		store.setItem(AWAITING_KEY, JSON.stringify({ build: 1358, unconfirmed: false, machine: 'dev-1' }));
		const awaiting = status(FlashPhase.AwaitingPowerCycle, { canExit: true });
		const d = deps({
			store,
			linkUp: true,
			machineId: 'dev-1',
			restore: { actions: [], status: awaiting },
			status: { actions: [], status: awaiting }
		});
		const flow = new FirmwareUpdateController(d);
		await flow.restore();
		expect(d.core.firmwareFlashLinkRestored).toHaveBeenCalledTimes(1);
		flow.dispose();
	});

	it('a restored lock for another DE1 is dropped on connect (audit r2 LOW 7)', async () => {
		const store = memoryStore();
		store.setItem(AWAITING_KEY, JSON.stringify({ build: 1358, unconfirmed: false, machine: 'dev-A' }));
		const awaiting = status(FlashPhase.AwaitingPowerCycle, { canExit: true });
		const d = deps({
			store,
			restore: { actions: [], status: awaiting },
			status: { actions: [], status: awaiting }
		});
		const flow = new FirmwareUpdateController(d);
		await flow.restore();
		await flow.onLinkUp('dev-B');
		expect(d.core.firmwareFlashExit).toHaveBeenCalledTimes(1);
		expect(d.core.firmwareFlashLinkRestored).not.toHaveBeenCalled();
		expect(flow.step).toBe('closed');
		expect(store.data.has(AWAITING_KEY)).toBe(false);
		flow.dispose();
	});

	it('an erase hold is restored from its deadline; an expired one is dropped', async () => {
		const store = memoryStore();
		store.setItem(AWAITING_KEY, JSON.stringify({ build: 1358, unconfirmed: false, eraseHoldUntil: 1_030_000 }));
		const held = status(FlashPhase.Failed, { eraseSettling: true, eraseHoldMsLeft: 30_000 });
		const d = deps({
			store,
			restoreHold: { actions: [], status: held },
			status: { actions: [], status: held }
		});
		const flow = new FirmwareUpdateController(d);
		await flow.restore();
		expect(d.core.firmwareFlashRestoreEraseHold).toHaveBeenCalledWith(1358, 30_000, expect.any(Number));
		expect(flow.holdsLink).toBe(true);
		flow.dispose();
		const stale = memoryStore();
		stale.setItem(AWAITING_KEY, JSON.stringify({ build: 1358, unconfirmed: false, eraseHoldUntil: 900_000 }));
		const d2 = deps({ store: stale });
		await new FirmwareUpdateController(d2).restore();
		expect(d2.core.firmwareFlashRestoreEraseHold).not.toHaveBeenCalled();
		expect(stale.data.has(AWAITING_KEY)).toBe(false);
	});

	it('the marker covers verify, the erase hold and the power-cycle wait (audit r2 LOW 2)', () => {
		expect(markerFor(status(FlashPhase.Verifying, { canCancel: false }), 'dev', 0)).toEqual({
			build: 1358,
			unconfirmed: true,
			machine: 'dev'
		});
		expect(markerFor(status(FlashPhase.Erasing, { eraseHoldMsLeft: 45_000 }), 'dev', 100_000)).toEqual({
			build: 1358,
			unconfirmed: false,
			eraseHoldUntil: 145_000,
			machine: 'dev'
		});
		expect(markerFor(status(FlashPhase.Uploading), 'dev', 0)).toBeNull();
		expect(markerFor(status(FlashPhase.Succeeded, { locksWrites: false }), 'dev', 0)).toBeNull();
		expect(markerFor(null, 'dev', 0)).toBeNull();
	});

	it('the banner and waiting copy follow the status', () => {
		const awaiting = status(FlashPhase.AwaitingPowerCycle);
		expect(heldBannerText(awaiting)).toContain('waiting for the DE1 to restart');
		expect(heldBannerText(status(FlashPhase.Failed, { eraseSettling: true }))).toContain(
			'may still be erasing'
		);
		expect(waitingText({ ...awaiting, machineRestarted: true, linkUp: true })).toContain(
			'Reading its build'
		);
		expect(waitingText({ ...awaiting, machineRestarted: true })).toContain('offline');
		expect(waitingText({ ...awaiting, restartedOnBuild: 1333, verifyUnconfirmed: true })).toContain(
			"didn't take"
		);
	});

	it('the shell hears when the flash lets go of the link', async () => {
		const onRelease = vi.fn();
		const d = deps({
			onRelease,
			status: { actions: [], status: status(FlashPhase.Uploading) },
			cancel: { actions: [], status: status(FlashPhase.Cancelled, { locksWrites: false }) }
		});
		const flow = new FirmwareUpdateController(d);
		await flow.open();
		expect(onRelease).not.toHaveBeenCalled();
		await flow.confirmCancel();
		expect(onRelease).toHaveBeenCalledTimes(1);
		flow.dispose();
	});

	it('retry waits for an interrupted erase to settle', async () => {
		const flow = new FirmwareUpdateController(
			deps({
				status: {
					actions: [],
					status: status(FlashPhase.Failed, { eraseSettling: true, locksWrites: true })
				}
			})
		);
		await flow.open();
		flow.image = new Uint8Array(16);
		await flow.retry();
		expect(flow.step).toBe('flash');
		flow.dispose();
	});

	it.skipIf(!process.env.CREMA_FIRMWARE_DIR)(
		'a real downloaded file goes to confirm (skipped: needs CREMA_FIRMWARE_DIR; no firmware is committed)',
		async () => {
			const { readFile } = await import('node:fs/promises');
			const bytes = new Uint8Array(await readFile(`${process.env.CREMA_FIRMWARE_DIR}/de1nightly.dat`));
			const flow = new FirmwareUpdateController(deps({}));
			await flow.open();
			await flow.fetchImage();
			await flow.useFile(new Blob([bytes]));
			expect(flow.sourceProblem).toBeNull();
			expect(flow.step).toBe('confirm');
		}
	);
});
