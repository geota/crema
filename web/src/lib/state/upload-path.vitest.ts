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

type WriteFn = (
	target: string,
	data: Uint8Array,
	guard?: () => boolean,
	settled?: (written: boolean) => void
) => Promise<boolean>;

interface Harness {
	app: {
		state: { current: { eventLog: readonly { message?: string; text?: string }[] | readonly string[] } };
		uploadProfile(profile: unknown): Promise<void>;
	};
	core: CremaCore;
	onState(state: string): void;
	feed(output: CoreOutput): void;
	log(): string[];
}

/** A two-step classic profile in the core's wire shape. */
function twoStepProfile(title: string) {
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
		await knowModel(h);
		await h.app.uploadProfile(twoStepProfile('Stall'));
		await vi.advanceTimersByTimeAsync(7_000);
		expect(failedWith(h, 'AckTimeout'), 'no ticker before the link is up').toBe(false);

		h.onState('ready');
		await vi.advanceTimersByTimeAsync(2_000);
		expect(failedWith(h, 'AckTimeout')).toBe(true);
		expect(await h.core.profileUploadInProgress()).toBe(false);

		// A dropped link stops the clock.
		h.onState('disconnected');
		await h.app.uploadProfile(twoStepProfile('Stall again'));
		await vi.advanceTimersByTimeAsync(7_000);
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
		h.onState('ready');
		await knowModel(h);
		await h.app.uploadProfile(twoStepProfile('A'));
		// A's header and first frame land; the rest of A is still queued.
		await q.release(2);
		await h.app.uploadProfile(twoStepProfile('B'));
		await q.release(100);
		await vi.advanceTimersByTimeAsync(10);

		expect(h.log().some((l) => l.includes('Profile uploaded: B'))).toBe(true);
		expect(failedWith(h, 'HeaderNotAcked')).toBe(false);
		expect(failedWith(h, 'UnexpectedAck')).toBe(false);
		// A: header + frame 0 only. B: header (2 frames), both frames, the
		// two default-limiter extensions, the tail.
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
		h.onState('ready');
		await knowModel(h);
		await h.app.uploadProfile(twoStepProfile('Fail'));
		await vi.advanceTimersByTimeAsync(10);
		expect(failedWith(h, 'WriteFailed')).toBe(true);
		expect(written, 'nothing after the failed frame').toEqual(['H2', 'F0']);
	});

	it('a failure report that lands after a newer upload started leaves the newer one running (V3 #1)', async () => {
		// Upload A's first frame fails. Before the executor reports it, upload
		// B starts (a shot start). The report names A, so B is not failed and
		// completes; the old unnamed report failed B and refused the shot.
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
					h.feed(await h.core.uploadProfile(twoStepProfile('B'), performance.now()));
					return false;
				}
				record(written, target, data);
				settled?.(true);
				return true;
			});
			chain = op;
			return op;
		});
		h.onState('ready');
		await knowModel(h);
		// The event log outlives one app; read only this test's entries.
		const idOf = (l: string) => (JSON.parse(l) as { id?: number }).id ?? 0;
		const startId = Math.max(0, ...h.log().map(idOf));
		const fresh = () => h.log().filter((l) => idOf(l) > startId);
		await h.app.uploadProfile(twoStepProfile('A'));
		await vi.advanceTimersByTimeAsync(10);
		expect(fresh().some((l) => l.includes('Profile write failed'))).toBe(true);
		expect(fresh().some((l) => l.includes('Profile upload failed: WriteFailed'))).toBe(false);
		expect(fresh().some((l) => l.includes('Profile uploaded: B'))).toBe(true);
		expect(written).toEqual(['H2', 'H2', 'F0', 'F1', 'F32', 'F33', 'F2']);
	});
});
