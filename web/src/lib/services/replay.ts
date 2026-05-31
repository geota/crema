/**
 * `$lib/services/replay` — the capture-replay engine as an Effect program
 * (docs/53 §2.9 PR 8.1, T-28).
 *
 * Lifts `state/app.svelte.ts`'s `replayCapture` body (the shadow-core swap +
 * the paced event loop) into one `Effect`. Three concrete wins over the old
 * `try/finally` + `AbortController` + hand-rolled cancellable `delay`:
 *
 *  - **leak-proof shadow swap** — `Effect.acquireUseRelease(beginReplay, …,
 *    endReplay + ActiveShot.clear)` guarantees the live core is restored on
 *    success, defect, OR interrupt, replacing the `finally`;
 *  - **natively interruptible pacing** — `Effect.sleep` is interrupted for free
 *    when the fiber is, retiring `replay/driver.ts`'s `delay(ms, signal)` and
 *    its manual `AbortSignal` wiring;
 *  - **`Fiber.interrupt` cancellation** — `cancelReplay` interrupts the fiber
 *    (the old `AbortController.abort()`); `Effect.onInterrupt` paints the
 *    `cancelled` UI state.
 *
 * Like the de1/scale orchestrators (D-07), this is NOT a `Context.Tag`/Layer
 * service — the core + the parsed capture are per-replay values, and every
 * store / UI touch is injected so the program is `R = never`. That lets it run
 * on Effect's *default* runtime (`Effect.runFork`), so offline replay keeps
 * working on browsers where the Web-Bluetooth-gated `AppRuntime` never mounts.
 *
 * Mockable: a test injects a fake core slice + spy callbacks and drives pacing
 * / interruption with `TestClock`. See `replay.vitest.ts`.
 */

import { Cause, Duration, Effect } from 'effect';
import type { CoreOutput, CremaCore } from '$lib/core';
import type { ParsedCapture, ReplayMeta } from '$lib/replay';
import { guessScaleFromFirstWeightPacket as wasmGuessScaleFromFirstWeightPacket } from '$lib/wasm/de1_wasm';
import { describeError } from '$lib/utils/error';
import type { ActiveShotBrewParams, ActiveShotData } from '$lib/state/active-shot.svelte';
import type { UiSnapshot } from '$lib/state/ui-state.svelte';
import type { ShotBean } from '$lib/history';

/**
 * The largest inter-event wait the replay will honour, in ms. A capture can
 * record a long idle stretch between two sessions; clamping the gap keeps the
 * replay moving while still preserving real-time pacing within a shot. (Was
 * `replay/driver.ts:MAX_GAP_MS`.)
 */
export const MAX_GAP_MS = 1000;

/** The core surface the replay program drives. */
type ReplayCoreSlice = Pick<
	CremaCore,
	'beginReplay' | 'endReplay' | 'onNotification' | 'connectScale'
>;

/**
 * Everything the program needs that isn't pure — the core, the parsed file, and
 * the orchestrator-owned UI / store side effects. All injected so the program
 * stays `R = never` and the seam is mockable.
 */
export interface ReplayCaptureDeps {
	readonly core: ReplayCoreSlice;
	readonly parsed: ParsedCapture;
	readonly fileName: string;
	/** Playback speed multiplier — `1` is real time. Defaults to `1`. */
	readonly speed?: number;
	/** The "cleared DE1 readout" patch applied at replay start (owned by the
	 *  orchestrator, shared with connect/disconnect — passed in to avoid an
	 *  `app → services → app` import cycle). */
	readonly clearedReadout: Partial<UiSnapshot>;
	readonly applyOutput: (output: CoreOutput) => void;
	readonly patch: (snapshot: Partial<UiSnapshot>) => void;
	readonly setActiveShot: (data: ActiveShotData) => void;
	readonly clearActiveShot: () => void;
	/** Drop the telemetry wall-clock anchor — replayed timestamps must not
	 *  integrate the gap since the last live sample. */
	readonly resetTelemetryAnchor: () => void;
	/** The current `replay.done` count, for the cancelled/error message. */
	readonly currentDone: () => number;
}

/**
 * Best-effort scale identification from the first SCALE_WEIGHT payload. The
 * signature table lives in core (`de1_scale::Scale::guess_from_first_weight_packet`);
 * this adapter hands the bytes to the wasm export so both shells share one path.
 */
function guessScaleAdvertisedName(firstWeightBytes: Uint8Array): string | undefined {
	return wasmGuessScaleFromFirstWeightPacket(firstWeightBytes) ?? undefined;
}

/**
 * The scale to identify before the events fire. Preference order: the explicit
 * META prelude (Crema captures prepend one with the scale's advertised name) →
 * the first SCALE_WEIGHT payload's header bytes (legacy fallback for captures
 * pre-dating META). `undefined` → replay proceeds with no scale (empty weights).
 */
function scaleNameToConnect(parsed: ParsedCapture): string | undefined {
	if (parsed.meta.scaleName) return parsed.meta.scaleName;
	const firstScale = parsed.events.find((e) => e.source === 'ScaleWeight');
	return firstScale ? guessScaleAdvertisedName(firstScale.data) : undefined;
}

/**
 * Adapt a `ReplayMeta` into the `ActiveShotData` the brew UI + history
 * record-builder consume. The replay's bean snapshot is a flat subset of
 * `ShotBean` (no `beanId` — the bean isn't necessarily in this device's
 * library), so History click-through to the bean falls back gracefully.
 * `source: 'replay'` lets the commit's live-only side effects skip themselves.
 */
export function replayMetaToActiveShot(meta: ReplayMeta): ActiveShotData {
	const bean = meta.bean;
	const beanSnapshot: ShotBean | null = bean
		? {
				beanId: null,
				name: bean.name ?? '',
				roasterName: bean.roaster ?? null,
				roastedOn: bean.roastedOn ?? null,
				roastLevel: bean.roastLevel ?? null,
				notes: bean.notes,
				grinderSetting: bean.grinderSetting
			}
		: null;
	const hasBrewParams =
		meta.yieldTarget != null ||
		meta.brewTemp != null ||
		meta.preinfuseTarget != null ||
		meta.stopOnWeight !== undefined ||
		meta.autoTare !== undefined;
	const brewParams: ActiveShotBrewParams | null = hasBrewParams
		? {
				yieldTarget: meta.yieldTarget ?? 0,
				brewTemp: meta.brewTemp ?? 0,
				preinfuseTarget: meta.preinfuseTarget ?? 0,
				stopOnWeight: meta.stopOnWeight ?? false,
				autoTare: meta.autoTare ?? false
			}
		: null;
	return {
		bean: beanSnapshot,
		profileName: meta.profileName ?? null,
		dose: null,
		brewParams,
		grinderModel: meta.grinderModel ?? null,
		source: 'replay'
	};
}

/**
 * Compose the user-facing "now replaying" message, folding the parsed
 * {@link ReplayMeta} into a short suffix (active profile, yield, grinder…) so an
 * analyst sees the context without inspecting the file. Legacy captures without
 * the at-shot-start META line produce the bare message.
 */
export function composeReplayStartMessage(
	fileName: string,
	eventCount: number,
	meta: ReplayMeta
): string {
	const parts: string[] = [];
	if (meta.profileName) parts.push(`Profile ${meta.profileName}`);
	if (meta.yieldTarget != null) parts.push(`${meta.yieldTarget} g target`);
	if (meta.brewTemp != null) parts.push(`${meta.brewTemp} °C`);
	if (meta.grinderModel) parts.push(meta.grinderModel);
	const beanLabel =
		meta.bean?.roaster && meta.bean.name
			? `${meta.bean.roaster} · ${meta.bean.name}`
			: (meta.bean?.name ?? meta.bean?.roaster ?? null);
	if (beanLabel) parts.push(beanLabel);
	const suffix = parts.length > 0 ? ` (${parts.join(' · ')})` : '';
	return `Replaying ${eventCount} events from ${fileName}…${suffix}`;
}

/**
 * Replay a parsed capture through the core. The caller forks this on a runtime
 * (`Effect.runFork`) and keeps the fiber for {@link cancelReplay} → `Fiber.interrupt`.
 * Never fails: cancellation paints `cancelled`, a defect paints `error`, and the
 * shadow core is always restored (`acquireUseRelease` release).
 *
 * The pre-flight checks (file-size cap, parse, empty) stay at the call site —
 * they don't touch the shadow core and report their own `error` state.
 */
export function replayCaptureProgram(d: ReplayCaptureDeps): Effect.Effect<void> {
	const total = d.parsed.events.length;
	const speed = d.speed && d.speed > 0 ? d.speed : 1;

	const replayState = (
		phase: 'running' | 'done' | 'cancelled' | 'error',
		done: number,
		message: string
	) => ({ replay: { phase, fileName: d.fileName, done, total, message } });

	// The paced event loop + the start-of-replay store/UI setup. Runs once the
	// shadow core is installed (acquire). `onNotification` rejections surface as
	// defects → caught below into the `error` state; interruption → `cancelled`.
	const use = Effect.gen(function* () {
		// Populate ActiveShot from the META BEFORE any events fire (the brew UI
		// follows ActiveShot; the replay's ShotStarted fold then leaves it alone),
		// and drop the telemetry anchor.
		yield* Effect.sync(() => {
			d.setActiveShot(replayMetaToActiveShot(d.parsed.meta));
			d.resetTelemetryAnchor();
		});

		// Identify the scale so SCALE_WEIGHT bytes decode into ScaleReading events.
		// Non-fatal: a failure just leaves the weight series empty.
		const scaleName = scaleNameToConnect(d.parsed);
		if (scaleName) {
			yield* Effect.tryPromise(() => d.core.connectScale(scaleName)).pipe(Effect.ignore);
		}

		// Replay starts from a clean session — clear the readout + fingerprint
		// cache (so a follow-on live shot re-uploads defensively) and announce.
		yield* Effect.sync(() =>
			d.patch({
				...d.clearedReadout,
				activeProfileFingerprint: null,
				...replayState('running', 0, composeReplayStartMessage(d.fileName, total, d.parsed.meta))
			})
		);

		// Paced loop: wait the (clamped, speed-scaled) gap, report progress, feed
		// the bytes through the core exactly as a live notification, fold output.
		let prevT: number | undefined;
		for (let i = 0; i < total; i++) {
			const ev = d.parsed.events[i];
			if (prevT !== undefined) {
				const wait = Math.min(Math.max(0, ev.t - prevT), MAX_GAP_MS) / speed;
				if (wait > 0) yield* Effect.sleep(Duration.millis(wait));
			}
			prevT = ev.t;
			yield* Effect.sync(() =>
				d.patch(replayState('running', i + 1, `Replaying ${d.fileName} — ${i + 1} / ${total}`))
			);
			const output = yield* Effect.promise(() => d.core.onNotification(ev.source, ev.data, ev.t));
			yield* Effect.sync(() => d.applyOutput(output));
		}

		yield* Effect.sync(() =>
			d.patch(replayState('done', total, `Replay finished — ${total} events from ${d.fileName}.`))
		);
	}).pipe(
		// Cancellation (Fiber.interrupt) → cancelled state.
		Effect.onInterrupt(() =>
			Effect.sync(() =>
				d.patch(
					replayState(
						'cancelled',
						d.currentDone(),
						`Replay cancelled — ${d.currentDone()} / ${total} events.`
					)
				)
			)
		),
		// Any defect (e.g. an onNotification rejection) → error state.
		Effect.catchAllDefect((defect) =>
			Effect.sync(() =>
				d.patch(replayState('error', d.currentDone(), `Replay failed: ${describeError(defect)}`))
			)
		)
	);

	// Shadow-core swap: beginReplay installs a fresh core; the release restores
	// the live one + clears ActiveShot — always, even on interrupt/defect.
	return Effect.acquireUseRelease(
		Effect.promise(() => d.core.beginReplay()),
		() => use,
		() =>
			Effect.sync(() => d.clearActiveShot()).pipe(
				Effect.zipRight(
					Effect.promise(() => d.core.endReplay()).pipe(
						// Errors here would only mask the real outcome — swallow defensively.
						Effect.catchAllDefect((e) =>
							Effect.sync(() => console.error('[replay] endReplay failed', e))
						)
					)
				)
			)
	).pipe(
		// If beginReplay itself fails (release never ran), still report it.
		Effect.catchAllCause((cause) =>
			Cause.isInterruptedOnly(cause)
				? Effect.void
				: Effect.sync(() =>
						d.patch(
							replayState('error', d.currentDone(), `Replay failed: ${describeError(Cause.squash(cause))}`)
						)
					)
		)
	);
}
