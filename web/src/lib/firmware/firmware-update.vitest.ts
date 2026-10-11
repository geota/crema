/**
 * `$lib/firmware/firmware-update` — the flow's gating: the alpha warning must
 * be acknowledged, the default build is the newest above the installed one,
 * a CORS-blocked download falls back to the manual file, a bad file never
 * reaches the confirm step, blockers stop the start, and the flow cannot be
 * closed while a flash runs.
 */

import { beforeAll, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '$lib/testing/test-init';
import { FlashPhase, type FlashBlocker, type FlashStep } from '$lib/core/crema-core';
import { FirmwareUpdateController, type FirmwareUpdateDeps } from './firmware-update.svelte';
import type { FlashIo } from './flash-driver';

beforeAll(initTestWasm);

const noStep = async (): Promise<FlashStep> => ({ actions: [] });

function deps(over: {
	installed?: number | null;
	blockers?: FlashBlocker[];
	fetchFn?: typeof fetch;
	status?: FlashStep;
}): FirmwareUpdateDeps {
	const io: FlashIo = {
		write: async () => undefined,
		readFwMap: async () => new Uint8Array(7),
		subscribeFwMap: async () => undefined,
		readBuild: async () => undefined,
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
			firmwareFlashLinkRestored: noStep,
			firmwareFlashCancel: noStep,
			firmwareFlashDismiss: async () => true,
			firmwareFlashStatus: async () => over.status ?? { actions: [] }
		},
		io,
		conditions: async () => ({ connected: true, ready: true, guidedBrewActive: false, powerOk: true }),
		installedBuild: () => over.installed ?? 1333,
		log: () => undefined,
		keepAlive: { set: () => undefined },
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
		const running: FlashStep = {
			actions: [],
			status: {
				phase: FlashPhase.Uploading,
				artifact: {} as never,
				targetBuild: 1358,
				chunksTotal: 10,
				chunksAcked: 3,
				percent: 30,
				etaIsEstimate: false,
				elapsedSeconds: 60,
				canCancel: true,
				locksWrites: true,
				verifyUnconfirmed: false,
				machineRestarted: false
			}
		};
		const flow = new FirmwareUpdateController(deps({ status: running }));
		await flow.open();
		expect(flow.step).toBe('flash');
		expect(flow.running).toBe(true);
		await flow.close();
		expect(flow.step).toBe('flash');
		flow.dispose();
	});

	it('a real downloaded file goes to confirm (needs CREMA_FIRMWARE_DIR)', async () => {
		const dir = process.env.CREMA_FIRMWARE_DIR;
		if (!dir) return;
		const { readFile } = await import('node:fs/promises');
		const bytes = new Uint8Array(await readFile(`${dir}/de1nightly.dat`));
		const flow = new FirmwareUpdateController(deps({}));
		await flow.open();
		await flow.fetchImage();
		await flow.useFile(new Blob([bytes]));
		expect(flow.sourceProblem).toBeNull();
		expect(flow.step).toBe('confirm');
	});
});
