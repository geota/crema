/**
 * The one owned shot start (cohort review 2026-10-29, 05 F2) through the real
 * app wiring: a real wasm core inside a real `CremaApp`, with only the DE1's
 * GATT write replaced. The DE1's state reports go back through the core, whose
 * events drive the start the way they do live; time is fake.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CremaCore } from '#lib/core/index.js';
import type { CoreOutput } from '#lib/core/crema-core.js';
import { MachineState } from '#lib/core/crema-core.js';
/** The core's `StartEnd::NotEntered` sentence. */
const NOT_STARTED_NOTICE = 'The machine didn’t start the shot — start it on the machine';
import { toast } from '#lib/components/shared/toast.svelte.js';

// DE1 state bytes (de1_protocol::MachineState).
const IDLE = 2;
const ESPRESSO = 4;
const STEAM = 5;
const FLUSH = 15; // HotWaterRinse
const POURING = 5;

interface App {
	state: { current: { shotStarting: boolean; eventLog: readonly unknown[] } };
	startShot(): Promise<void>;
	stopShot(): Promise<void>;
	requestMachineState(state: MachineState): Promise<void>;
}

interface Harness {
	app: App;
	core: CremaCore;
	/** Every DE1 RequestedState byte written, in order. */
	requested: number[];
	/** The DE1 reports `state` / `substate`. */
	report(state: number, substate?: number): Promise<void>;
	onState(state: string): void;
	/** Apply a core output the way the app does. */
	apply(out: CoreOutput): void;
}

async function harness(opts: {
	preFlush: boolean;
	autoPurge?: boolean;
	stopOnWeight?: boolean;
	requireScale?: boolean;
}): Promise<Harness> {
	const { loadCore } = await import('#lib/core/index.js');
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { De1Manager } = await import('#lib/ble/index.js');
	const { getProfileStore } = await import('#lib/profiles/index.js');
	const { getSettingsStore } = await import('#lib/settings/index.js');
	const requested: number[] = [];
	vi.spyOn(De1Manager.prototype, 'writeCharacteristic').mockImplementation(
		async (target: string, data: Uint8Array) => {
			if (target === 'De1RequestedState') requested.push(data[0]);
			return true;
		}
	);
	const core = await loadCore();
	core.reset();
	// The bridge outlives one test; a reset keeps the desired profile.
	await core.setDesiredProfile(null, {}, 0);
	const app = new CremaApp(core, null);
	const internals = app as unknown as {
		de1: { callbacks: { onState(s: string): void } };
		applyCoreOutput(o: CoreOutput): void;
	};
	const profiles = getProfileStore();
	await profiles.ensureLoaded();
	const profile = profiles.all.find((p) => p.segments.length <= 20);
	if (!profile) throw new Error('no built-in profile');
	profiles.setActive(profile.id);
	const settings = getSettingsStore();
	settings.set('stopOnWeight', opts.stopOnWeight ?? false);
	settings.set('requireScale', opts.requireScale ?? false);
	settings.set('groupFlushBeforeShot', opts.preFlush);
	settings.set('autoPurgeAfterSteam', opts.autoPurge ?? false);
	const h: Harness = {
		app: app as unknown as App,
		core,
		requested,
		report: async (state, substate = 0) => {
			internals.applyCoreOutput(
				await core.onNotification('De1State', new Uint8Array([state, substate]), performance.now())
			);
			await vi.advanceTimersByTimeAsync(0);
		},
		onState: (s) => internals.de1.callbacks.onState(s),
		apply: (out) => internals.applyCoreOutput(out)
	};
	// The machine model (an upload waits for it), an Idle machine and a
	// settled connection: the core's profile sync uploads the active profile,
	// so the DE1 already holds it when a test starts a shot.
	const model = new Uint8Array(20);
	model.set([0x80, 0x00, 0x0c], 1);
	new DataView(model.buffer).setUint32(4, 1, true);
	internals.applyCoreOutput(await core.onNotification('De1MmrRead', model, performance.now()));
	h.onState('ready');
	await h.report(IDLE);
	internals.applyCoreOutput((await core.setDesiredProfile(profile, {}, performance.now())).output);
	await vi.advanceTimersByTimeAsync(10);
	if (!(await core.profileSyncStatus()).lastPushed) throw new Error('the profile did not land');
	// Past the firmware's profile-download guard, which the core now holds
	// state requests through (F16).
	await vi.advanceTimersByTimeAsync(600);
	requested.length = 0;
	return h;
}

/** Let the app's awaits settle. */
const settle = () => vi.advanceTimersByTimeAsync(0);

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

describe('one owned shot start in the app (05 F2)', () => {
	it('a second start during the pre-flush is ignored and the flush releases one shot', async () => {
		const h = await harness({ preFlush: true });
		const first = h.app.startShot();
		await settle();
		expect(h.requested).toEqual([FLUSH]);
		expect(h.app.state.current.shotStarting).toBe(true);
		await h.report(FLUSH);
		await h.app.startShot(); // ignored: returns at once
		expect(h.requested).toEqual([FLUSH]);
		// The flush ends on its own.
		await vi.advanceTimersByTimeAsync(4_000);
		await h.report(IDLE);
		expect(h.requested).toEqual([FLUSH, ESPRESSO]);
		expect(h.app.state.current.shotStarting, 'held until Espresso').toBe(true);
		await h.report(ESPRESSO, POURING);
		await first;
		expect(h.app.state.current.shotStarting).toBe(false);
		// A start mid-shot is ignored, and nothing fires late.
		await h.app.startShot();
		await vi.advanceTimersByTimeAsync(60_000);
		expect(h.requested, 'one Espresso, no Idle bounce mid-shot').toEqual([FLUSH, ESPRESSO]);
	});

	it('a second start before the machine reports Espresso is ignored', async () => {
		const h = await harness({ preFlush: false });
		const first = h.app.startShot();
		await settle();
		expect(h.requested).toEqual([ESPRESSO]);
		await h.app.startShot();
		await settle();
		expect(h.requested).toEqual([ESPRESSO]);
		await h.report(ESPRESSO, POURING);
		await first;
		// The core drops a stale Espresso request mid-shot: no Idle bounce.
		await h.app.requestMachineState(MachineState.Espresso);
		await settle();
		expect(h.requested).toEqual([ESPRESSO]);
	});

	it('Stop during the pre-flush cancels the start: no Espresso', async () => {
		const h = await harness({ preFlush: true });
		const start = h.app.startShot();
		await settle();
		await h.report(FLUSH);
		await vi.advanceTimersByTimeAsync(1_000);
		await h.app.stopShot();
		await settle();
		expect(h.app.state.current.shotStarting, 'Coffee is back at once').toBe(false);
		await h.report(IDLE); // the flush ends because of the Stop
		await start;
		await vi.advanceTimersByTimeAsync(60_000);
		expect(h.requested).toEqual([FLUSH, IDLE]);
	});

	it('another mode during the pre-flush cancels the start', async () => {
		const h = await harness({ preFlush: true });
		const start = h.app.startShot();
		await settle();
		await h.report(FLUSH);
		await h.app.requestMachineState(MachineState.Steam);
		await settle();
		await h.report(STEAM);
		await start;
		await vi.advanceTimersByTimeAsync(60_000);
		expect(h.requested).toEqual([FLUSH, IDLE, STEAM]);
	});

	it('a flush that leaves for another state on the machine does not start the shot', async () => {
		const h = await harness({ preFlush: true });
		const start = h.app.startShot();
		await settle();
		await h.report(FLUSH);
		await h.report(STEAM); // the group-head Steam button mid-flush
		await start;
		await vi.advanceTimersByTimeAsync(60_000);
		expect(h.requested).toEqual([FLUSH]);
		expect(h.app.state.current.shotStarting).toBe(false);
	});

	it('the flush ceiling still starts the shot when the completion is missed', async () => {
		const h = await harness({ preFlush: true });
		const start = h.app.startShot();
		await settle();
		await h.report(FLUSH);
		await vi.advanceTimersByTimeAsync(29_000);
		expect(h.requested).toEqual([FLUSH]);
		await vi.advanceTimersByTimeAsync(1_001);
		// The machine still reads the flush, so the core bounces through Idle.
		expect(h.requested).toEqual([FLUSH, IDLE, ESPRESSO]);
		await h.report(ESPRESSO, POURING);
		await start;
	});

	it('a disconnect during the pre-flush cancels the start', async () => {
		const h = await harness({ preFlush: true });
		const start = h.app.startShot();
		await settle();
		await h.report(FLUSH);
		// The DE1 manager tells the core the link dropped, then reports it.
		h.apply(await h.core.de1LinkLost());
		h.onState('disconnected');
		await start;
		await vi.advanceTimersByTimeAsync(60_000);
		expect(h.requested).toEqual([FLUSH]);
		expect(h.app.state.current.shotStarting).toBe(false);
	});
});

describe('PR #197 review fixes', () => {
	it('a Stop before the library loads ends the start quietly (#3)', async () => {
		const h = await harness({ preFlush: false });
		const { getProfileStore } = await import('#lib/profiles/index.js');
		getProfileStore().setActive(null); // would throw NoActiveProfileError
		const start = h.app.startShot();
		void h.app.stopShot(); // before the ensureLoaded await resumes
		await expect(start).resolves.toBeUndefined();
		await vi.advanceTimersByTimeAsync(10_000);
		expect(h.requested).toEqual([IDLE]);
	});

	it('a machine that ignores the Espresso request says so (#8)', async () => {
		const info = vi.spyOn(toast, 'info');
		const h = await harness({ preFlush: false });
		const start = h.app.startShot();
		await settle();
		expect(h.requested).toEqual([ESPRESSO]);
		await vi.advanceTimersByTimeAsync(5_001);
		await start;
		expect(info).toHaveBeenCalledWith(NOT_STARTED_NOTICE, 7000);
		expect(h.app.state.current.shotStarting).toBe(false);
	});

	it('no not-started notice when the shot starts (#8)', async () => {
		const info = vi.spyOn(toast, 'info');
		const h = await harness({ preFlush: false });
		const start = h.app.startShot();
		await settle();
		await h.report(ESPRESSO, POURING);
		await start;
		await vi.advanceTimersByTimeAsync(10_000);
		expect(info).not.toHaveBeenCalledWith(NOT_STARTED_NOTICE, 7000);
	});

	it('with no start, the post-steam purge fires (control for #11)', async () => {
		const h = await harness({ preFlush: false, autoPurge: true });
		await h.report(STEAM);
		await vi.advanceTimersByTimeAsync(10_000);
		await h.report(IDLE);
		await vi.advanceTimersByTimeAsync(2_000);
		expect(h.requested).toEqual([FLUSH]);
	});

	it('a start during the post-steam purge defer wins the group (#11)', async () => {
		const h = await harness({ preFlush: true, autoPurge: true });
		await h.report(STEAM);
		await vi.advanceTimersByTimeAsync(10_000);
		await h.report(IDLE); // steam released: a purge is scheduled for 1.5 s
		const start = h.app.startShot();
		await settle();
		expect(h.requested).toEqual([FLUSH]); // the start's own flush
		await vi.advanceTimersByTimeAsync(2_000);
		expect(h.requested, 'the purge stood down').toEqual([FLUSH]);
		await h.report(FLUSH);
		await vi.advanceTimersByTimeAsync(4_000);
		await h.report(IDLE);
		await h.report(ESPRESSO, POURING);
		await start;
		expect(h.requested).toEqual([FLUSH, ESPRESSO]);
	});
});

describe('the core runs the start (F16b, web)', () => {
	it('require-scale refuses a shot that stops on weight: nothing written, Connect offered', async () => {
		const action = vi.spyOn(toast, 'action');
		const h = await harness({ preFlush: false, stopOnWeight: true, requireScale: true });
		const { ScaleRequiredError } = await import('#lib/state/app.svelte.js');
		// A Quick Controls yield of 0 falls back to the profile's yield, as
		// the stop-at-weight does: the check runs (it used to be skipped).
		await expect(h.app.startShot({ yield: 0 } as never)).rejects.toBeInstanceOf(ScaleRequiredError);
		expect(h.requested).toEqual([]);
		expect(action.mock.calls.some(([, label]) => label === 'Connect')).toBe(true);
		expect(h.app.state.current.shotStarting).toBe(false);
	});

	it('without require-scale the start runs with a non-blocking notice', async () => {
		const action = vi.spyOn(toast, 'action');
		const h = await harness({ preFlush: false, stopOnWeight: true });
		const start = h.app.startShot();
		await settle();
		expect(action.mock.calls.some(([m]) => String(m).startsWith('No scale connected'))).toBe(true);
		expect(h.requested).toEqual([ESPRESSO]);
		await h.report(ESPRESSO, POURING);
		await start;
	});

	it('a shot started on the machine shows the skipped checks until the shot ends', async () => {
		const action = vi.spyOn(toast, 'action');
		const h = await harness({ preFlush: true });
		await settle();
		await h.report(ESPRESSO, POURING);
		const banner = action.mock.calls.find(([m]) => String(m).includes('pre-shot flush'));
		expect(banner, 'the group-head banner').toBeDefined();
		expect(banner![3], 'pinned until the shot ends or a tap').toBe(0);
		expect(h.requested, 'it writes nothing').toEqual([]);
	});

	it('an app start shows no group-head banner', async () => {
		const action = vi.spyOn(toast, 'action');
		const h = await harness({ preFlush: false });
		const start = h.app.startShot();
		await settle();
		await h.report(ESPRESSO, POURING);
		await start;
		expect(action.mock.calls.some(([m]) => String(m).includes('Started on the machine'))).toBe(false);
	});
});

describe('one start implementation (AC-F16.8, web)', () => {
	it('no shell code runs its own start sequence, purge or guard', async () => {
		const { readdirSync, readFileSync, statSync } = await import('node:fs');
		const path = await import('node:path');
		const src = path.resolve('src');
		const files = (dir: string): string[] =>
			readdirSync(dir).flatMap((name) => {
				const full = path.join(dir, name);
				if (statSync(full).isDirectory()) return name === 'wasm' ? [] : files(full);
				if (!/\.(ts|svelte)$/.test(name) || /\.(vitest|test)\.ts$/.test(name) || name === 'crema-core.ts') return [];
				return [full];
			});
		const offenders = files(src).filter((f) =>
			/StartSequence|StartToken|scheduleAutoPurge|rinsePending|profileDownloadGuard|PRESHOT_FLUSH_TIMEOUT_MS/.test(
				readFileSync(f, 'utf-8')
			)
		);
		expect(offenders.map((f) => path.relative(src, f))).toEqual([]);
		const app = readFileSync(path.join(src, 'lib/state/app.svelte.ts'), 'utf-8');
		expect(app).toMatch(/this\.core\.startShot\(/);
	});
});
