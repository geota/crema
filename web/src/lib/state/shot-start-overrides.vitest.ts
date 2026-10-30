/**
 * The shot start uploads the dialled profile (F6 validation): a real wasm core
 * in a real `CremaApp` with a runtime, the DE1's GATT write replaced. A start
 * with Quick Controls overrides must put them in the frames it uploads, and
 * what the core then holds as pushed is those bytes' fingerprint. A shell that
 * drops the overrides at start fails here. The dial's own push (F7) is
 * checked too: debounced, it puts the overridden profile on the DE1 so a
 * group-head start runs it.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect, Layer, ManagedRuntime } from 'effect';
import type { CoreOutput } from '#lib/core/crema-core.js';

const IDLE = 2;

/** The MachineModel `ReadFromMMR` reply (model 1 = DE1). */
function modelReply(): Uint8Array {
	const packet = new Uint8Array(20);
	packet.set([0x80, 0x00, 0x0c], 1);
	new DataView(packet.buffer).setUint32(4, 1, true);
	return packet;
}

beforeEach(() => {
	localStorage.clear();
	vi.useFakeTimers({
		toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance']
	});
});

afterEach(() => {
	vi.useRealTimers();
	vi.restoreAllMocks();
});

describe('the shot start uploads the dialled profile', () => {
	it('the start carries the brew-temp override into the frames and commits the core fingerprint', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { De1Manager } = await import('#lib/ble/index.js');
		const { getProfileStore, brewTempRange, profileFingerprint } = await import(
			'#lib/profiles/index.js'
		);
		const { getSettingsStore } = await import('#lib/settings/index.js');
		const { ProfileSyncLive } = await import('#lib/services/profile-sync.js');
		const { UploadQueue } = await import('#lib/services/upload-queue.js');
		const { Webhooks } = await import('#lib/services/webhooks.js');

		const frames: Uint8Array[] = [];
		vi.spyOn(De1Manager.prototype, 'writeCharacteristic').mockImplementation(
			async (target: string, data: Uint8Array, guard?: () => boolean, settled?: (ok: boolean) => void) => {
				if (guard && !guard()) return false;
				if (target === 'De1ProfileFrame') frames.push(new Uint8Array(data));
				settled?.(true);
				return true;
			}
		);
		// The services the app reaches: the real profile-sync coordinator, inert
		// upload-queue / webhooks.
		const layer = Layer.mergeAll(
			ProfileSyncLive,
			Layer.succeed(
				UploadQueue,
				UploadQueue.of({
					enqueue: () => Effect.void,
					drain: Effect.die('unused'),
					armLifecycle: Effect.void
				} as never)
			),
			Layer.succeed(
				Webhooks,
				Webhooks.of({ fire: () => Effect.void, sendTest: () => Effect.die('unused') } as never)
			)
		);
		const runtime = ManagedRuntime.make(layer);
		const core = await loadCore();
		core.reset();
		await core.setDesiredProfile(null, {}, 0);
		const app = new CremaApp(core, runtime as never);
		const internals = app as unknown as {
			de1: { callbacks: { onState(s: string): void } };
			applyCoreOutput(o: CoreOutput): void;
		};
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const profile = profiles.all.find(
			(p) => p.segments.length >= 2 && p.segments.length <= 20 && brewTempRange(p).max >= brewTempRange(p).anchor + 2
		);
		if (!profile) throw new Error('no suitable built-in profile');
		profiles.setActive(profile.id);
		getSettingsStore().set('stopOnWeight', false);
		getSettingsStore().set('groupFlushBeforeShot', false);
		internals.applyCoreOutput(await core.onNotification('De1MmrRead', modelReply(), performance.now()));
		internals.de1.callbacks.onState('ready');
		internals.applyCoreOutput(
			await core.onNotification('De1State', new Uint8Array([IDLE, 0]), performance.now())
		);
		await vi.advanceTimersByTimeAsync(10);
		frames.length = 0;

		const anchor = brewTempRange(profile).anchor;
		const qc = { brewTemp: anchor + 2 };
		void app.startShot(qc);
		await vi.advanceTimersByTimeAsync(1_000);

		// ShotFrame byte 3: the temperature in half degrees. Frame 0 is the
		// first step, the anchor: dialled +2 °C.
		const first = frames.find((f) => f[0] === 0);
		expect(first, 'the start uploaded the profile').toBeDefined();
		expect(first![3]).toBe(Math.round((anchor + 2) * 2));
		// What the core pushed is the dialled profile's fingerprint.
		const pushed = (await core.profileSyncStatus()).lastPushed;
		expect(pushed).toBe(profileFingerprint(profile, qc));
		expect(pushed).not.toBe(profileFingerprint(profile, {}));
		await runtime.dispose();
	});
});

describe('a failed start leaves no dial snapshot behind', () => {
	it('the snapshot set for a start that fails is not recorded onto a later shot', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { getProfileStore } = await import('#lib/profiles/index.js');
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		profiles.setActive(null);
		app.setBrewParamsSnapshot({
			yieldTarget: 40,
			brewTemp: 95,
			preinfuseTarget: 6,
			stopOnWeight: true,
			autoTare: true
		});
		await expect(app.startShot({ brewTemp: 95 })).rejects.toThrow();
		expect((app as unknown as { brewParamsAtShotStart: unknown }).brewParamsAtShotStart).toBeNull();
	});
});

describe('the dial is part of the desired profile (F7, validation P2)', () => {
	it('f7_dial a dial change pushes the overridden profile after 0.5 s, and re-activation keeps it', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { De1Manager } = await import('#lib/ble/index.js');
		const { getProfileStore, brewTempRange, profileFingerprint } = await import(
			'#lib/profiles/index.js'
		);
		const frames: Uint8Array[] = [];
		vi.spyOn(De1Manager.prototype, 'writeCharacteristic').mockImplementation(
			async (target: string, data: Uint8Array, guard?: () => boolean, settled?: (ok: boolean) => void) => {
				if (guard && !guard()) return false;
				if (target === 'De1ProfileFrame') frames.push(new Uint8Array(data));
				settled?.(true);
				return true;
			}
		);
		const core = await loadCore();
		core.reset();
		await core.setDesiredProfile(null, {}, 0);
		const app = new CremaApp(core, null);
		const internals = app as unknown as {
			de1: { callbacks: { onState(s: string): void } };
			applyCoreOutput(o: CoreOutput): void;
		};
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const profile = profiles.all.find(
			(p) => p.segments.length >= 2 && p.segments.length <= 20 && brewTempRange(p).max >= brewTempRange(p).anchor + 2
		);
		if (!profile) throw new Error('no suitable built-in profile');
		internals.applyCoreOutput(await core.onNotification('De1MmrRead', modelReply(), performance.now()));
		internals.de1.callbacks.onState('ready');
		internals.applyCoreOutput(
			await core.onNotification('De1State', new Uint8Array([IDLE, 0]), performance.now())
		);
		await app.setDesiredProfile(profile);
		await vi.advanceTimersByTimeAsync(10);
		expect((await core.profileSyncStatus()).lastPushed).toBe(profileFingerprint(profile, {}));
		frames.length = 0;

		// The dial moves +2 °C (several steps): nothing goes out while it moves.
		const anchor = brewTempRange(profile).anchor;
		app.setBrewOverrides(profile, { brewTemp: anchor + 1 });
		await vi.advanceTimersByTimeAsync(200);
		const qc = { brewTemp: anchor + 2 };
		app.setBrewOverrides(profile, qc);
		await vi.advanceTimersByTimeAsync(400);
		expect(frames, 'debounced: nothing before the dial rests 0.5 s').toEqual([]);
		await vi.advanceTimersByTimeAsync(200);
		const first = frames.find((f) => f[0] === 0);
		expect(first, 'the dialled profile went out').toBeDefined();
		expect(first![3]).toBe(Math.round((anchor + 2) * 2));
		expect((await core.profileSyncStatus()).lastPushed).toBe(profileFingerprint(profile, qc));

		// Re-activating the same profile (or the boot push) keeps the dial: no
		// bare upload over it.
		frames.length = 0;
		await app.setDesiredProfile(profile);
		await vi.advanceTimersByTimeAsync(10);
		expect(frames).toEqual([]);
		expect((await core.profileSyncStatus()).desired).toBe(profileFingerprint(profile, qc));
		// No Brew screen was involved: the overrides live at app level.
		expect(app.brewOverridesFor(profile)).toEqual(qc);

		// Another profile becomes active: the dial goes, and it stays gone
		// when the first profile comes back.
		const other = profiles.all.find((p) => p.id !== profile.id && p.segments.length <= 20)!;
		await app.setDesiredProfile(other);
		expect(app.brewOverridesFor(profile)).toEqual({});
		await app.setDesiredProfile(profile);
		expect((await core.profileSyncStatus()).desired).toBe(profileFingerprint(profile, {}));
	});
});

describe('a group-head shot records the dial (F7 validation P2)', () => {
	it('a shot started at the machine after a dial change records the dialled profile', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const { getActiveShotStore } = await import('#lib/state/active-shot.svelte.js');
		const { getProfileStore, brewTempRange, preinfusionDial } = await import(
			'#lib/profiles/index.js'
		);
		const { getSettingsStore } = await import('#lib/settings/index.js');
		const core = await loadCore();
		core.reset();
		await core.setDesiredProfile(null, {}, 0);
		const app = new CremaApp(core, null);
		const internals = app as unknown as { applyCoreOutput(o: CoreOutput): void };
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		const profile = profiles.all.find(
			(p) =>
				p.segments.length >= 2 &&
				p.segments.length <= 20 &&
				brewTempRange(p).max >= brewTempRange(p).anchor + 2 &&
				preinfusionDial(p).adjustable
		);
		if (!profile) throw new Error('no suitable built-in profile');
		profiles.setActive(profile.id);
		getSettingsStore().set('stopOnWeight', false);
		await app.setDesiredProfile(profile);

		// The dial moves, rests, and goes to the core; no Coffee tap follows.
		const anchor = brewTempRange(profile).anchor;
		const preinf = preinfusionDial(profile).seconds + 2;
		const qc = { brewTemp: anchor + 2, preinf, yield: profile.yieldOut + 4 };
		app.setBrewOverrides(profile, qc);
		await vi.advanceTimersByTimeAsync(600);

		// The shot starts at the group head.
		getActiveShotStore().clear();
		internals.applyCoreOutput({ events: [{ type: 'ShotStarted' }], commands: [] } as CoreOutput);
		const recorded = getActiveShotStore().brewParams;
		expect(recorded, 'the group-head shot has a dial snapshot').not.toBeNull();
		expect(recorded!.brewTemp).toBeCloseTo(anchor + 2, 5);
		expect(recorded!.preinfuseTarget).toBeCloseTo(preinf, 5);
		expect(recorded!.yieldTarget).toBeCloseTo(profile.yieldOut + 4, 5);
		expect(recorded!.stopOnWeight).toBe(false);
		// History's as-run profile is the core's since F10 (the profile the DE1
		// held): `a_shot_records_the_profile_and_overrides_the_de1_ran`.
		getActiveShotStore().clear();
	});
});
