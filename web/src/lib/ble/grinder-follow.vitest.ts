/**
 * Follow mode on web (spec `.scratch/grinder-ble/SPEC.md` §5, owner
 * decisions 2–4; G3): a scripted Motto 80 drives the real `CremaApp` and the
 * real wasm core. The app gives the core the grinder's record link, Brew's
 * grind follows a steady collar, "Pause following" gives
 * the grind back to the user, a bag pick onto the followed grinder names the
 * collar's target instead of moving Brew's grind, and its Undo puts back the
 * grind Brew had (review #554 S3).
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect } from 'effect';
import { flushSync } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CremaCore, CoreOutput } from '#lib/core/index.js';
import { FollowStatus } from '#lib/core/crema-core.js';
import type { BleDevice } from './transport';
import { getBeanStore } from '#lib/bean/index.js';
import { blankBean } from '#lib/bean/model.js';
import { activateBean } from '#lib/bean/activate.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getGrinderLinkStore } from '#lib/grinder/connected-link.svelte.js';
import { getGrinderFollow } from '#lib/grinder/follow.svelte.js';
import { getBrewGrind, resetBrewGrindForTest } from '#lib/brew/grind-session.svelte.js';
import { getToasts } from '#lib/components/shared/toast.svelte.js';

const NOTIFY = '4d543830-0003-4b80-8f00-424f4f4b4f4f';
const MOTTO = 'bookoo-motto-80';

/** One Motto message as 20-byte BLE frames. */
function frames(json: string, seq = 7): Uint8Array[] {
	const body = new TextEncoder().encode(json);
	const out: Uint8Array[] = [];
	for (let offset = 0; offset < body.length; offset += 12) {
		const chunk = body.slice(offset, offset + 12);
		const f = new Uint8Array(8 + chunk.length);
		f.set([0xa5, 0x01, seq & 0xff, seq >> 8, body.length & 0xff, body.length >> 8, offset & 0xff, offset >> 8]);
		f.set(chunk, 8);
		out.push(f);
	}
	return out;
}

const period = (gap: number, state = 'IDLE', total = 120) =>
	JSON.stringify({
		broadcast: {
			periodInfo: { devState: state, bladeGap: gap, grindRpm: 700, feedingRpm: 50, totalGrinds: total, selectPreset: -1 }
		}
	});

/** A scripted Motto 80 as the transport sees it. */
function fakeGrinder(id = 'grinder-A') {
	let sink: ((n: { characteristic: string; data: Uint8Array; atMs: number }) => void) | null = null;
	const device = {
		name: 'BOOKOO MT80 0011aabb',
		id,
		device: { name: 'BOOKOO MT80 0011aabb', id },
		connectionState: 'connected',
		disconnect: vi.fn(),
		setSink: (s: typeof sink) => (sink = s),
		setReconnectTimeline: () => undefined,
		onDisconnected: () => undefined,
		onStateChanged: () => undefined,
		onReconnectAttempt: () => undefined,
		onReconnected: () => undefined,
		onAdvertisement: () => undefined,
		onNotificationOverflow: () => undefined,
		connectGatt: async () => undefined,
		discoveredServiceUuids: async () => [] as string[],
		startNotifications: vi.fn(async () => undefined),
		write: vi.fn(async () => undefined),
		kick: () => 'ignored-idle',
		recycle: vi.fn()
	};
	return {
		device,
		send(json: string, atMs: number) {
			for (const data of frames(json)) sink?.({ characteristic: NOTIFY, data, atMs });
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
	getGrinderFollow().reset();
	resetBrewGrindForTest();
});

afterEach(async () => {
	await core.setGrinderFollowPaused(false);
	await core.setGrinderLink(null);
	await core.disconnectGrinder();
	vi.useRealTimers();
});

async function settle() {
	flushSync();
	await vi.advanceTimersByTimeAsync(0);
	flushSync();
	await vi.advanceTimersByTimeAsync(0);
}

/** The app with a Motto record (Brew's grinder) and a Niche, and the Motto connected. */
async function followingApp() {
	const lib = getBeanStore();
	const motto = lib.addGrinder('', MOTTO)!;
	const niche = lib.addGrinder('', 'niche-zero')!;
	lib.setActiveGrinder(motto.id);
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const runtime = {
		runPromiseExit: (effect: Effect.Effect<unknown, unknown>) => Effect.runPromiseExit(effect),
		runPromise: async () => undefined,
		runFork: () => undefined
	};
	const app = new CremaApp(core, runtime as never);
	const internals = app as unknown as {
		grinder: { attach(d: BleDevice): Promise<void> };
		applyCoreOutput(out: CoreOutput): void;
	};
	const grinder = fakeGrinder();
	await internals.grinder.attach(grinder.device as unknown as BleDevice);
	await settle();
	let now = 200;
	/** `n` readings at the Motto's 5 Hz. */
	const readings = async (gap: number, n = 6) => {
		for (let i = 0; i < n; i++) {
			grinder.send(period(gap), now);
			now += 200;
			await settle();
		}
	};
	const tick = async () => {
		internals.applyCoreOutput(await core.onTick(now));
		await settle();
	};
	return { app, motto, niche, readings, tick };
}

describe('follow mode on Brew', () => {
	it("Brew's grind follows a steady collar once the grinder is linked", async () => {
		const t = await followingApp();
		getBrewGrind().set('500');
		await t.readings(480);
		expect(getGrinderLinkStore().linkOf('grinder-A')).toBe(t.motto.id);
		expect(getGrinderFollow().view.status).toBe(FollowStatus.Following);
		expect(getGrinderFollow().followedGrinderId).toBe(t.motto.id);
		expect(getBrewGrind().value).toBe('480');
		// The collar moves: Brew follows once the new value holds.
		await t.readings(470, 3);
		expect(getBrewGrind().value).toBe('480');
		await t.readings(470, 3);
		expect(getBrewGrind().value).toBe('470');
	});

	it('"Pause following" gives the grind back to the user; resuming takes the collar again', async () => {
		const t = await followingApp();
		await t.readings(480);
		await t.app.setGrinderFollowPaused(true);
		await settle();
		expect(getGrinderFollow().view.status).toBe(FollowStatus.Paused);
		expect(getGrinderFollow().followedGrinderId).toBeNull();
		getBrewGrind().set('455');
		await t.readings(480);
		expect(getBrewGrind().value).toBe('455');
		await t.app.setGrinderFollowPaused(false);
		await t.readings(480);
		await t.tick();
		expect(getBrewGrind().value).toBe('480');
	});

	it('another grinder active: a suggestion only, never a switch (decision 4)', async () => {
		const t = await followingApp();
		getBeanStore().setActiveGrinder(t.niche.id);
		getBrewGrind().set('14');
		await settle();
		await t.readings(480);
		await t.tick();
		expect(getGrinderFollow().view.status).toBe(FollowStatus.Suggest);
		expect(getGrinderFollow().view.grinder_id).toBe(t.motto.id);
		expect(getBeanStore().activeGrinderId).toBe(t.niche.id);
		expect(getBrewGrind().value).toBe('14');
	});

	it('a bag pick onto the followed grinder names the collar target, and its Undo restores the old grind (S3)', async () => {
		const t = await followingApp();
		const lib = getBeanStore();
		lib.upsertBean({
			...blankBean('bag-guji'),
			name: 'Guji',
			grinds: [{ grinderId: t.motto.id, grinderName: t.motto.name, grind: '450', starred: true }]
		});
		// The Motto is followed while the Niche is Brew's grinder.
		lib.setActiveGrinder(t.niche.id);
		getBrewGrind().set('14');
		await settle();
		await t.readings(480);
		await t.tick();
		expect(getGrinderFollow().followedGrinderId).toBe(t.motto.id);

		await activateBean('bag-guji');
		await settle();
		const pick = getToasts().find((x) => x.message.startsWith('Guji'));
		expect(pick?.message).toBe(`Guji: ${t.motto.name} · set the collar to 450 μm`);
		expect(lib.activeGrinderId).toBe(t.motto.id);
		// The collar sets Brew's grind now, not the bag's.
		await t.readings(480);
		await t.tick();
		expect(getBrewGrind().value).toBe('480');

		// Undo puts the Niche back, and its grind (review #554 S3).
		pick?.action?.run();
		await settle();
		expect(lib.activeGrinderId).toBe(t.niche.id);
		expect(getBrewGrind().value).toBe('14');
	});
});
