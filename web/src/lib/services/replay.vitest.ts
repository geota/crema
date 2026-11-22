/**
 * `#lib/services/replay.vitest.js` — vitest for the replay engine (docs/53 T-28).
 *
 * Mocks at the core seam (a fake `CremaCore` slice + spy callbacks) and asserts
 * the orchestration Effect adds: the shadow-core swap is leak-proof (endReplay +
 * ActiveShot.clear always run — on success, on a feed error, and on interrupt),
 * events feed in order, the scale-identify precedence holds, and `Fiber.interrupt`
 * paints the `cancelled` state. Pacing runs at a high `speed` (sub-ms real waits)
 * for the terminal paths; cancellation uses a `Deferred` barrier + interrupt so
 * it's deterministic without mixing `TestClock` with the core's real promises.
 * Run: `cd web && pnpm test:vitest` (or `vitest run replay`).
 */

import { describe, expect, it, vi } from 'vitest';
import { Effect, Fiber } from 'effect';
import { replayCaptureProgram, type ReplayCaptureDeps } from './replay.ts';
import type { CoreOutput, CremaCore } from '#lib/core/index.js';
import type { CaptureEvent, ParsedCapture, ReplayMeta } from '#lib/replay/index.js';

const EMPTY_OUTPUT = {} as CoreOutput;

type CoreSlice = Pick<CremaCore, 'beginReplay' | 'endReplay' | 'onNotification' | 'connectScale'>;

function mkCore(over: Partial<CoreSlice> = {}): CoreSlice {
	return {
		beginReplay: vi.fn(() => Promise.resolve()),
		endReplay: vi.fn(() => Promise.resolve()),
		onNotification: vi.fn(() => Promise.resolve(EMPTY_OUTPUT)),
		connectScale: vi.fn(() => Promise.resolve<string | undefined>(undefined)),
		...over
	};
}

function mkEvents(ts: number[]): CaptureEvent[] {
	return ts.map((t) => ({ t, source: 'De1State', data: new Uint8Array([t & 0xff]) }));
}

function mkParsed(events: CaptureEvent[], meta: ReplayMeta = {}): ParsedCapture {
	return { events, meta, linesRead: events.length, skipped: 0 };
}

// No return annotation — let TS infer the precise Mock types so the spies stay
// both assignable to ReplayCaptureDeps and assertable.
function mkDeps(parsed: ParsedCapture, core: CoreSlice, over: Partial<ReplayCaptureDeps> = {}) {
	return {
		core,
		parsed,
		fileName: 'cap.jsonl',
		speed: 100, // shrink the (real) inter-event waits to sub-ms
		clearedReadout: { machineState: null },
		applyOutput: vi.fn<(o: CoreOutput) => void>(),
		patch: vi.fn<(s: Record<string, unknown>) => void>(),
		setActiveShot: vi.fn(),
		setShotSelections: vi.fn(),
		clearActiveShot: vi.fn(),
		currentDone: vi.fn(() => 0),
		...over
	} satisfies ReplayCaptureDeps;
}

/** Phase of the last patch that carried a `replay` field. */
function lastReplayPhase(patch: ReturnType<typeof vi.fn>): string | undefined {
	const calls = patch.mock.calls as Array<[Record<string, { phase?: string } | undefined>]>;
	for (let i = calls.length - 1; i >= 0; i--) {
		const replay = calls[i][0]?.replay;
		if (replay) return replay.phase;
	}
	return undefined;
}

describe('replayCaptureProgram — happy path', () => {
	it('swaps in the shadow core, feeds events in order, then restores', async () => {
		const core = mkCore();
		const deps = mkDeps(mkParsed(mkEvents([0, 100, 300])), core);
		await Effect.runPromise(replayCaptureProgram(deps));

		expect(core.beginReplay).toHaveBeenCalledOnce();
		expect(deps.setActiveShot).toHaveBeenCalledOnce();

		// Three feeds, in file order.
		const fedBytes = (core.onNotification as ReturnType<typeof vi.fn>).mock.calls.map(
			(c) => (c[1] as Uint8Array)[0]
		);
		expect(fedBytes).toEqual([0, 100, 300 & 0xff]);
		expect(deps.applyOutput).toHaveBeenCalledTimes(3);

		// Terminal state + leak-proof restore.
		expect(lastReplayPhase(deps.patch as ReturnType<typeof vi.fn>)).toBe('done');
		expect(deps.clearActiveShot).toHaveBeenCalledOnce();
		expect(core.endReplay).toHaveBeenCalledOnce();
	});

	it('identifies the scale from the META prelude (precedence over byte-guess)', async () => {
		const core = mkCore();
		const deps = mkDeps(mkParsed(mkEvents([0, 50]), { scaleName: 'BOOKOO_SC' }), core);
		await Effect.runPromise(replayCaptureProgram(deps));
		expect(core.connectScale).toHaveBeenCalledWith('BOOKOO_SC', []);
	});

	it('treats a connectScale failure as non-fatal (replay still completes)', async () => {
		const core = mkCore({ connectScale: vi.fn(() => Promise.reject(new Error('no decoder'))) });
		const deps = mkDeps(mkParsed(mkEvents([0, 50]), { scaleName: 'BOOKOO_SC' }), core);
		await Effect.runPromise(replayCaptureProgram(deps));
		expect(lastReplayPhase(deps.patch as ReturnType<typeof vi.fn>)).toBe('done');
		expect(core.endReplay).toHaveBeenCalledOnce();
	});
});

describe('replayCaptureProgram — the capture\'s selections (REVIEW-CORE H3)', () => {
	it('hands the capture\'s bag, profile, grinder and grind to the shadow core before any event', async () => {
		const order: string[] = [];
		const core = mkCore({
			beginReplay: vi.fn(() => {
				order.push('begin');
				return Promise.resolve();
			}),
			onNotification: vi.fn(() => {
				order.push('event');
				return Promise.resolve(EMPTY_OUTPUT);
			})
		});
		const meta: ReplayMeta = {
			profileName: 'Blooming',
			grinderModel: 'Niche Zero',
			bean: { name: 'Guji', roaster: 'Sey', roastLevel: 4, grinderSetting: '2.25' }
		};
		const deps = mkDeps(mkParsed(mkEvents([0, 50]), meta), core, {
			setShotSelections: vi.fn(() => {
				order.push('selections');
			})
		});
		await Effect.runPromise(replayCaptureProgram(deps));
		expect(order.slice(0, 3)).toEqual(['begin', 'selections', 'event']);
		expect(deps.setShotSelections).toHaveBeenCalledWith({
			bean: { name: 'Guji', roasterName: 'Sey', roastedOn: undefined, roastLevel: 4, notes: undefined },
			profileName: 'Blooming',
			grinderName: 'Niche Zero',
			grind: '2.25'
		});
		// The replay's bag snapshot carries no legacy grind (REVIEW-FINAL S2).
		const active = (deps.setActiveShot as ReturnType<typeof vi.fn>).mock.calls[0][0] as { bean: Record<string, unknown> };
		expect(active.bean).not.toHaveProperty('grinderSetting');
	});

	it('a capture with no META hands empty selections, so a live selection never reaches the replayed shot', async () => {
		const deps = mkDeps(mkParsed(mkEvents([0]), {}), mkCore());
		await Effect.runPromise(replayCaptureProgram(deps));
		expect(deps.setShotSelections).toHaveBeenCalledWith({
			bean: undefined,
			profileName: undefined,
			grinderName: undefined,
			grind: undefined
		});
	});
});

describe('replayCaptureProgram — feed error', () => {
	it('paints error and still restores the live core when a feed rejects', async () => {
		const core = mkCore({
			onNotification: vi.fn(() => Promise.reject(new Error('decode blew up')))
		});
		const deps = mkDeps(mkParsed(mkEvents([0, 50]), {}), core);
		await Effect.runPromise(replayCaptureProgram(deps));
		expect(lastReplayPhase(deps.patch as ReturnType<typeof vi.fn>)).toBe('error');
		// Release still ran — no leaked shadow core.
		expect(deps.clearActiveShot).toHaveBeenCalledOnce();
		expect(core.endReplay).toHaveBeenCalledOnce();
	});
});

describe('replayCaptureProgram — cancellation', () => {
	it('interrupt → cancelled state + restores the live core', async () => {
		// A huge second-gap means the program sleeps after event 0; we interrupt
		// during that sleep, the moment event 0 has been delivered.
		let firstDelivered: () => void;
		const gate = new Promise<void>((resolve) => {
			firstDelivered = resolve;
		});
		const core = mkCore();
		const deps = mkDeps(mkParsed(mkEvents([0, 60_000]), {}), core, {
			speed: 1,
			applyOutput: vi.fn(() => firstDelivered())
		});

		const fiber = Effect.runFork(replayCaptureProgram(deps));
		await gate; // event 0 fed; program now in the 60 s sleep
		await Effect.runPromise(Fiber.interrupt(fiber));

		expect(deps.applyOutput).toHaveBeenCalledTimes(1); // only event 0 made it
		expect(lastReplayPhase(deps.patch as ReturnType<typeof vi.fn>)).toBe('cancelled');
		// Leak-proof restore on interrupt too.
		expect(deps.clearActiveShot).toHaveBeenCalledOnce();
		expect(core.endReplay).toHaveBeenCalledOnce();
	});
});
