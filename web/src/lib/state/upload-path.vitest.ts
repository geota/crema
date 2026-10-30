/**
 * The profile-upload path through the real app wiring (cohort review
 * 2026-10-28, #2 N1 / N2): a real wasm core inside a real `CremaApp`, with only
 * the DE1's GATT write replaced. Round 1's tests ticked the core by hand and so
 * hid that no shell ticked it; these drive time with fake timers and let the
 * app's own `CoreTicker` and write executor do the rest.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CremaCore } from '#lib/core/index.js';
import type { CoreOutput } from '#lib/core/crema-core.js';
import { cremaProfileFromWire } from '#lib/wasm/de1_wasm.js';

type WriteFn = (
	target: string,
	data: Uint8Array,
	guard?: () => boolean,
	settled?: (written: boolean) => void
) => Promise<boolean>;

interface Harness {
	app: {
		state: { current: { eventLog: readonly { message?: string; text?: string }[] | readonly string[] } };
		setDesiredProfile(profile: unknown, qc?: object): Promise<unknown>;
	};
	core: CremaCore;
	onState(state: string): void;
	feed(output: CoreOutput): void;
	log(): string[];
}

/** A two-step classic profile as a library `CremaProfile` (the only shape the
 *  shell can upload), adapted from the core's wire shape by the core. */
function twoStepProfile(title: string): unknown {
	return JSON.parse(cremaProfileFromWire(JSON.stringify(twoStepWireProfile(title))));
}

/** A two-step classic profile in the core's wire shape. */
function twoStepWireProfile(title: string) {
	const step = (name: string) => ({
		name,
		pump: 'pressure',
		target: 9,
		temperature_c: 92,
		temp_sensor: 'coffee',
		transition: 'fast',
		duration_seconds: 20,
		exit: null,
		volume_limit_ml: 0,
		limiter: null,
		weight: null
	});
	return {
		id: '',
		title,
		notes: '',
		steps: [step('a'), step('b')],
		preinfuse_step_count: 1,
		minimum_pressure: 0,
		maximum_flow: 6,
		max_total_volume_ml: 0,
		target_weight: 0,
		dose: 0,
		author: '',
		beverage_type: 'espresso',
		tank_temperature: 0,
		version: '2'
	};
}

/** The MachineModel `ReadFromMMR` reply (model 1 = DE1). */
function modelReply(model: number): Uint8Array {
	const packet = new Uint8Array(20);
	packet.set([0x80, 0x00, 0x0c], 1);
	new DataView(packet.buffer).setUint32(4, model, true);
	return packet;
}

async function harness(write: WriteFn): Promise<Harness> {
	const { loadCore } = await import('#lib/core/index.js');
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { De1Manager } = await import('#lib/ble/index.js');
	vi.spyOn(De1Manager.prototype, 'writeCharacteristic').mockImplementation(
		write as never
	);
	const core = await loadCore();
	core.reset();
	// The bridge outlives one test, and a reset keeps the desired profile
	// (user intent): start each test with none.
	await core.setDesiredProfile(null, {}, 0);
	const app = new CremaApp(core, null);
	const internals = app as unknown as {
		de1: { callbacks: { onState(s: string): void } };
		applyCoreOutput(o: CoreOutput): void;
	};
	return {
		app: app as unknown as Harness['app'],
		core,
		onState: (s) => internals.de1.callbacks.onState(s),
		feed: (o) => internals.applyCoreOutput(o),
		log: () =>
			(app.state.current.eventLog as readonly unknown[]).map((e) =>
				typeof e === 'string' ? e : JSON.stringify(e)
			)
	};
}

/** Tell the core the machine model, as the connect sweep's reply would. */
async function knowModel(h: Harness): Promise<void> {
	h.feed(await h.core.onNotification('De1MmrRead', modelReply(1), performance.now()));
}

/** A settled, idle connection: the model and an Idle state reported, then
 *  `ready` (the app's connect sweep settles the core's profile sync). */
async function connect(h: Harness): Promise<void> {
	await knowModel(h);
	h.feed(await h.core.onNotification('De1State', new Uint8Array([2, 0]), performance.now()));
	h.onState('ready');
	await vi.advanceTimersByTimeAsync(0);
}

/** Make a profile titled `title` the desired one: the core's sync uploads it. */
async function upload(h: Harness, title: string): Promise<void> {
	await h.app.setDesiredProfile(twoStepProfile(title), {});
}

/** Note a profile write that reached the DE1: `H<count>` / `F<FrameToWrite>`. */
function record(written: string[], target: string, data: Uint8Array): void {
	if (target === 'De1ProfileHeader') written.push(`H${data[1]}`);
	else if (target === 'De1ProfileFrame') written.push(`F${data[0]}`);
}

const failedWith = (h: Harness, kind: string) =>
	h.log().some((l) => l.includes(`Profile upload failed: ${kind}`));

beforeEach(() => {
	vi.useFakeTimers({
		toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'performance']
	});
});

afterEach(() => {
	vi.useRealTimers();
	vi.restoreAllMocks();
});

describe('the app ticks the core while the DE1 is connected (#2 N1)', () => {
	it('a stalled upload fails on the ack watchdog with no hand-fed tick', async () => {
		// The tail write never returns (a wedged write): no ack, no failure —
		// only the core's on_tick watchdog can end the upload.
		const h = await harness(async (target, data, guard) => {
			if (guard && !guard()) return false;
			if (target === 'De1ProfileFrame' && data[0] === 2) return new Promise<boolean>(() => {});
			return true;
		});
		await connect(h);
		await upload(h, 'Stall');
		await vi.advanceTimersByTimeAsync(6_000);
		expect(failedWith(h, 'AckTimeout')).toBe(true);
		expect(await h.core.profileUploadInProgress()).toBe(false);
		// The core's retry (3 s later) runs on the same clock.
		await vi.advanceTimersByTimeAsync(3_000);
		expect(await h.core.profileUploadInProgress()).toBe(true);

		// A dropped link stops the clock: the stalled retry is never failed.
		h.onState('disconnected');
		await vi.advanceTimersByTimeAsync(60_000);
		expect(await h.core.profileUploadInProgress()).toBe(true);
	});
});

describe('profile-write generations in the app executor (#2 N2)', () => {
	/** A serial GATT queue whose writes run only once released. */
	function gatedQueue() {
		const written: string[] = [];
		let released = 0;
		let started = 0;
		const waiters: (() => void)[] = [];
		let chain: Promise<unknown> = Promise.resolve();
		// Like the transport's queue: the guard and `settled` run inside the
		// queued step.
		const write: WriteFn = (target, data, guard, settled) => {
			// Only profile writes are held; the connect sweep's go through.
			if (!target.startsWith('De1Profile')) return Promise.resolve(true);
			const index = started++;
			const op = chain.then(async () => {
				if (index >= released) await new Promise<void>((r) => waiters.push(r));
				if (guard && !guard()) return false;
				record(written, target, data);
				settled?.(true);
				return true;
			});
			chain = op;
			return op;
		};
		const release = async (n: number) => {
			released += n;
			while (waiters.length > 0) waiters.shift()?.();
			await vi.advanceTimersByTimeAsync(0);
		};
		return { write, written, release };
	}

	it("a superseded upload's queued writes never reach the DE1 or ack into its successor", async () => {
		const q = gatedQueue();
		const h = await harness(q.write);
		await connect(h);
		await upload(h, 'A');
		// A's header and first frame land; the rest of A is still queued.
		await q.release(2);
		// A is cut short (cancelled); the core's retry 3 s later starts a new
		// upload of it behind A's queued writes.
		h.feed(await h.core.cancelProfileUpload());
		await vi.advanceTimersByTimeAsync(3_000);
		await q.release(100);
		await vi.advanceTimersByTimeAsync(10);

		expect(h.log().some((l) => l.includes('Profile uploaded: A'))).toBe(true);
		expect(failedWith(h, 'HeaderNotAcked')).toBe(false);
		expect(failedWith(h, 'UnexpectedAck')).toBe(false);
		// The first A: header + frame 0 only. The retry: header (2 frames),
		// both frames, the two default-limiter extensions, the tail.
		expect(q.written).toEqual(['H2', 'F0', 'H2', 'F0', 'F1', 'F32', 'F33', 'F2']);
	});

	it('the first failed write stops the batch and fails the upload at once', async () => {
		const written: string[] = [];
		let chain: Promise<unknown> = Promise.resolve();
		const h = await harness((target, data, guard, settled) => {
			const op = chain.then(async () => {
				if (guard && !guard()) return false;
				record(written, target, data);
				const ok = !(target === 'De1ProfileFrame' && data[0] === 0);
				settled?.(ok);
				return ok;
			});
			chain = op;
			return op;
		});
		await connect(h);
		await upload(h, 'Fail');
		await vi.advanceTimersByTimeAsync(10);
		expect(failedWith(h, 'WriteFailed')).toBe(true);
		expect(written, 'nothing after the failed frame').toEqual(['H2', 'F0']);
	});

	it('a failure report that lands after a newer upload started leaves the newer one running (V3 #1)', async () => {
		// Upload A's first frame fails. Before the executor reports it, A is
		// cut short and the core's retry B starts. The report names A, so B is
		// not failed and completes; an unnamed report would fail B.
		const written: string[] = [];
		let chain: Promise<unknown> = Promise.resolve();
		let startedB = false;
		// The write runs only after `harness` returned, so `h` is set by then.
		const h: Harness = await harness((target, data, guard, settled) => {
			const op = chain.then(async () => {
				if (guard && !guard()) return false;
				const failA = !startedB && target === 'De1ProfileFrame' && data[0] === 0;
				if (failA) {
					settled?.(false);
					// The newer upload starts between the failure and its report.
					startedB = true;
					h.feed(await h.core.cancelProfileUpload());
					h.feed(await h.core.onTick(performance.now() + 3_000));
					return false;
				}
				record(written, target, data);
				settled?.(true);
				return true;
			});
			chain = op;
			return op;
		});
		await connect(h);
		// The event log outlives one app; read only this test's entries.
		const idOf = (l: string) => (JSON.parse(l) as { id?: number }).id ?? 0;
		const startId = Math.max(0, ...h.log().map(idOf));
		const fresh = () => h.log().filter((l) => idOf(l) > startId);
		await upload(h, 'A');
		await vi.advanceTimersByTimeAsync(10);
		expect(fresh().some((l) => l.includes('Profile write failed'))).toBe(true);
		expect(fresh().some((l) => l.includes('Profile upload failed: WriteFailed'))).toBe(false);
		expect(fresh().some((l) => l.includes('Profile uploaded: A'))).toBe(true);
		expect(written).toEqual(['H2', 'H2', 'F0', 'F1', 'F32', 'F33', 'F2']);
	});
});

describe('profile writes without an upload id (round-3 clean check C3 #3)', () => {
	it('are logged, and their acks are not fed to the core', async () => {
		const h = await harness(async (_target, _data, guard, settled) => {
			if (guard && !guard()) return false;
			settled?.(true);
			return true;
		});
		const acks = vi.spyOn(h.core, 'onProfileWriteAck');
		h.onState('ready');
		// A header write with no `ProfileUploadStarted` beside it: the core
		// never emits this, so the shell says so instead of guessing an id.
		const header = { type: 'WriteCharacteristic', content: { target: 'De1ProfileHeader', data: [1, 1, 1, 0, 0] } };
		h.feed({ events: [], commands: [header] } as unknown as CoreOutput);
		await vi.advanceTimersByTimeAsync(10);
		expect(h.log().some((l) => l.includes('Profile writes without an upload id'))).toBe(true);
		expect(acks).not.toHaveBeenCalled();
	});
});

describe('a core rebuilt after a panic (round-3 clean check C2 P3-1)', () => {
	/**
	 * The bridge's reset count, under the test's control: the rebuilt core
	 * numbers uploads from 1 again, which a test cannot make the real bridge
	 * do, so these check the shell drops everything of the old batches.
	 */
	async function recoverable() {
		const { loadCore } = await import('#lib/core/index.js');
		const core = await loadCore();
		let resets = 0;
		vi.spyOn(core, 'panicRecoveries').mockImplementation(() => resets);
		return {
			core,
			/** Count a reset and let the app notice it, as its next core call would. */
			reset: (h: Harness) => {
				resets += 1;
				h.feed({ events: [], commands: [] });
			}
		};
	}

	it("an open upload's queued writes and acks never reach the rebuilt core", async () => {
		const r = await recoverable();
		const written: string[] = [];
		let released = 0;
		let started = 0;
		const waiters: (() => void)[] = [];
		let chain: Promise<unknown> = Promise.resolve();
		const h = await harness((target, data, guard, settled) => {
			if (!target.startsWith('De1Profile')) return Promise.resolve(true);
			const index = started++;
			const op = chain.then(async () => {
				if (index >= released) await new Promise<void>((res) => waiters.push(res));
				if (guard && !guard()) return false;
				record(written, target, data);
				settled?.(true);
				return true;
			});
			chain = op;
			return op;
		});
		const acks = vi.spyOn(r.core, 'onProfileWriteAck');
		await connect(h);
		await upload(h, 'A');
		released = 1; // A's header lands and is acked
		while (waiters.length > 0) waiters.shift()?.();
		await vi.advanceTimersByTimeAsync(0);
		expect(acks).toHaveBeenCalledTimes(1);

		r.reset(h);
		released = 100;
		while (waiters.length > 0) waiters.shift()?.();
		await vi.advanceTimersByTimeAsync(10);
		expect(written, "nothing of A after the rebuild").toEqual(['H2']);
		expect(acks, 'no ack of A reaches the rebuilt core').toHaveBeenCalledTimes(1);
	});

	it('a failure settled before the rebuild is not reported to the rebuilt core', async () => {
		// The window C2 P3-1 describes: A's write fails, the core is rebuilt
		// before the report goes out, and the report would name the fresh
		// core's first upload.
		const r = await recoverable();
		let chain: Promise<unknown> = Promise.resolve();
		let failedOnce = false;
		const h: Harness = await harness((target, data, guard, settled) => {
			const op = chain.then(async () => {
				if (guard && !guard()) return false;
				if (!failedOnce && target === 'De1ProfileFrame' && data[0] === 0) {
					failedOnce = true;
					settled?.(false);
					r.reset(h);
					return false;
				}
				settled?.(true);
				return true;
			});
			chain = op;
			return op;
		});
		const reports = vi.spyOn(r.core, 'profileWriteFailed');
		await connect(h);
		await upload(h, 'A');
		await vi.advanceTimersByTimeAsync(10);
		expect(failedOnce).toBe(true);
		expect(reports).not.toHaveBeenCalled();
		expect(h.log().some((l) => l.includes('Core reset after an internal error'))).toBe(true);
	});
});

describe('the shell uploads the effective profile (AC-F6.3)', () => {
	it('the brew-temp and pre-infusion overrides change the frames, and the fingerprint is theirs', async () => {
		const { profileFingerprint, effectiveCremaProfile } = await import('#lib/profiles/index.js');
		const frames: Uint8Array[] = [];
		const h = await harness(async (target, data, guard, settled) => {
			if (guard && !guard()) return false;
			if (target === 'De1ProfileFrame') frames.push(new Uint8Array(data));
			settled?.(true);
			return true;
		});
		await connect(h);
		// Two 20 s steps at 92 °C; the first is the one pre-infusion segment.
		const profile = twoStepProfile('Dialled') as import('#lib/profiles/index.js').CremaProfile;
		const desired = (qc: object) =>
			(h.app.setDesiredProfile(profile, qc) as Promise<{ desired?: string | null }>).then(
				(status) => status.desired ?? null
			);
		const bareFp = await desired({});
		await vi.advanceTimersByTimeAsync(10);
		const bare = frames.splice(0);
		const qc = { brewTemp: 94, preinf: 12 };
		const fp = await desired(qc);
		await vi.advanceTimersByTimeAsync(10);
		const dialled = frames.splice(0);

		// ShotFrame bytes: [3] temperature (U8P1, half degrees), [4] duration
		// (F8_1_7: tenths below 12.75 s, whole seconds | 0x80 above).
		expect([bare[0][3], bare[1][3]]).toEqual([184, 184]);
		expect([dialled[0][3], dialled[1][3]]).toEqual([188, 188]);
		expect(bare[0][4]).toBe(0x80 | 20);
		expect(dialled[0][4]).toBe(120); // 12.0 s in tenths
		expect(dialled[1][4]).toBe(bare[1][4]);

		// The fingerprint is the hash of exactly those bytes' profile.
		expect(fp).toBe(profileFingerprint(profile, qc));
		expect(fp).toBe(profileFingerprint(effectiveCremaProfile(profile, qc), {}));
		expect(bareFp).toBe(profileFingerprint(profile, {}));
		expect(fp).not.toBe(bareFp);
	});
});

describe('the core sends the desired profile on every connect (AC-F7.1, web)', () => {
	it('f7_1 a connect and a reconnect each upload it; nothing is persisted (F7.6)', async () => {
		const headers: number[] = [];
		const h = await harness(async (target, data, guard, settled) => {
			if (guard && !guard()) return false;
			if (target === 'De1ProfileHeader') headers.push(data[1]);
			settled?.(true);
			return true;
		});
		// The active profile is set before the DE1 connects.
		await upload(h, 'Daily');
		await vi.advanceTimersByTimeAsync(10);
		expect(headers, 'nothing before the connection settles').toEqual([]);
		await connect(h);
		await vi.advanceTimersByTimeAsync(10);
		expect(headers, 'the connect sweep is followed by the upload').toEqual([2]);
		expect((await h.core.profileSyncStatus()).lastPushed).not.toBeNull();
		// Unchanged within the connection: not sent again.
		await upload(h, 'Daily');
		await vi.advanceTimersByTimeAsync(10);
		expect(headers).toEqual([2]);

		// The link drops (the DE1 manager tells the core), then comes back:
		// the DE1 manager re-reads the state before it reports ready.
		h.feed(await h.core.de1LinkLost());
		h.onState('reconnecting');
		await vi.advanceTimersByTimeAsync(10);
		h.feed(await h.core.onNotification('De1State', new Uint8Array([2, 0]), performance.now()));
		h.onState('ready');
		await vi.advanceTimersByTimeAsync(10);
		expect(headers, 'a reconnect re-sends').toEqual([2, 2]);

		// F7.6: no "DE1 holds X" fingerprint reaches storage.
		const keys = Object.keys(localStorage);
		expect(keys.filter((k) => /fingerprint|profile-sync/i.test(k))).toEqual([]);
	});
});
