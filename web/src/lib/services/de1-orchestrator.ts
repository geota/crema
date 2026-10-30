/**
 * `#lib/services/de1-orchestrator.js` — the DE1 connect sequence as an Effect
 * program (docs/53 §2.5 PR 4.1, T-17).
 *
 * Lifts `de1.ts`'s 250-line `connect()` `try/catch` (with its `let step = …`
 * error-attribution pattern) into a single `Effect.gen`. Each fatal step is an
 * `Effect.tryPromise` whose `catch` stamps the step name onto a
 * `De1ConnectStepFailed` — so a failure is attributed precisely (which is how
 * the user learns a non-DE1 device was selected) without the manual mutable
 * `step` variable. The non-fatal connect-time reads (version / state /
 * shot-settings) + the MMR sweep are `Effect.catch(log)`-wrapped so a single
 * failure logs-and-continues exactly as before; the sweep runs serially
 * (`concurrency: 1`) in declaration order, matching the legacy register order.
 *
 * Deliberately NOT a `Context.Service`/Layer service: the transport + core are
 * per-connect runtime values, not layer dependencies. `de1.ts`'s `De1Manager`
 * keeps the gesture-bound `requestDevice`, the device lifecycle listeners, and
 * the notification `setSink` (it mutates the manager's diagnostics counts), then
 * runs this program on the app runtime for the GATT → subscribe → read → MMR
 * sequence.
 *
 * Mockable at the transport seam (D-07 keeps Web Bluetooth itself in
 * `transport.ts`): a test injects a fake `De1Transport` + core slice and asserts
 * step order, the `De1ConnectStepFailed{step}` attribution, the serial
 * best-effort sweep, and non-fatal-read swallowing. See `de1-orchestrator.vitest.ts`.
 */

import { Effect, Exit } from 'effect';
import type { CoreOutput, CremaCore, NotificationSource } from '#lib/core/index.js';
import type { De1Subscription, MmrRegister } from '#lib/core/crema-core.js';
import {
	De1Uuids,
	connectIdentityRegisters,
	de1NotificationSource,
	de1SeedReads,
	de1Subscriptions
} from '#lib/ble/de1-uuids.js';
import type { De1Transport } from '#lib/ble/de1-transport.js';
import { describeError } from '#lib/utils/error.js';
import { De1ConnectStepFailed } from '../effect/errors.ts';

/** The slice of `CremaCore` the connect program touches. */
type CoreSlice = Pick<CremaCore, 'onNotification' | 'readMmr'>;

export interface De1ConnectDeps {
	readonly device: De1Transport;
	readonly core: CoreSlice;
	/** Human-readable status line for the UI / event log. */
	readonly onStatus: (line: string) => void;
	/** A raw `CoreOutput` from a connect-time read, to fold into state. */
	readonly onCoreOutput: (output: CoreOutput) => void;
	/** Signal that all five GATT objects resolved — the device is a verified DE1. */
	readonly onGattVerified: () => void;
}

/**
 * The connect-time subscriptions and identity reads come from the core
 * (`de1Subscriptions`, `connectIdentityRegisters`), the tables Android reads
 * too. Read at connect time, never at module scope: they resolve from wasm
 * (see `de1-uuids.ts`).
 *
 * A required subscription failing is the structural tell the selected device
 * is not a DE1 (its GATT lacks A000 / the characteristic), so each is
 * attributed to its own step. The identity reads are best-effort — each
 * consumer falls back to "—" until the value lands.
 */
const subscribeStep = (s: De1Subscription): string => `${s.name} characteristic ${s.short}`;

/** The step name a hollow link fails at — see {@link de1SeedProgram}. */
export const STATE_SEED_STEP = 'machine state read';

/** Pause before the one StateInfo retry, ms. */
const STATE_SEED_RETRY_DELAY_MS = 500;

/**
 * The connect-time one-shot reads that seed the dashboard — the DE1 answers
 * these on a direct GATT read but does not notify them at connect, so
 * subscribing alone leaves the rows blank. Runs on every connect path: the
 * first connect (inside {@link de1ConnectProgram}) and a transport-level
 * reconnect (which replays subscriptions, not reads).
 *
 * Version and ShotSettings are best-effort: a failure logs and continues.
 * **StateInfo is required.** The DE1 answers it even while asleep, so a link
 * that can't produce it in two tries is hollow — up at the GATT level, dead
 * for data (a connect at the edge of range). Failing the program here is what
 * keeps the UI from reporting `ready` over a machine that isn't answering;
 * the manager then drops the link for the reconnect loop. Android's
 * `seedReads` applies the same rule.
 */
export const de1SeedProgram = (
	d: Pick<De1ConnectDeps, 'device' | 'core' | 'onStatus' | 'onCoreOutput'>
): Effect.Effect<void, De1ConnectStepFailed> =>
	Effect.gen(function* () {
		const readOnce = (source: NotificationSource, char: string) =>
			Effect.tryPromise(async () => {
				const bytes = await d.device.readCharacteristic(De1Uuids.SERVICE, char);
				return d.core.onNotification(source, bytes, performance.now());
			});

		/** A non-fatal read: read → core → fold → log. A failure logs-and-continues. */
		const readSeed = (source: NotificationSource, char: string, label: string) =>
			Effect.gen(function* () {
				const output = yield* readOnce(source, char);
				d.onCoreOutput(output);
				d.onStatus(`DE1 ${label} read ✓`);
			}).pipe(
				Effect.catch((e) =>
					Effect.sync(() => d.onStatus(`DE1 ${label} read skipped: ${describeError(e)}`))
				)
			);

		/** The required read: one retry, then the program fails at {@link STATE_SEED_STEP}. */
		const readStateSeed = (source: NotificationSource, char: string) => Effect.gen(function* () {
			const first = yield* Effect.exit(readOnce(source, char));
			let output: CoreOutput;
			if (Exit.isSuccess(first)) {
				output = first.value;
			} else {
				d.onStatus('DE1 machine state read failed — retrying once');
				yield* Effect.sleep(STATE_SEED_RETRY_DELAY_MS);
				output = yield* readOnce(source, char).pipe(
					Effect.mapError((cause) => new De1ConnectStepFailed({ step: STATE_SEED_STEP, cause }))
				);
			}
			d.onCoreOutput(output);
			d.onStatus('DE1 machine state read ✓');
		});

		// The core's seed table: one required read (StateInfo), the rest best-effort.
		for (const seed of de1SeedReads()) {
			const source = de1NotificationSource(seed.characteristic);
			if (source === null) continue;
			if (seed.required) yield* readStateSeed(source, seed.characteristic);
			else yield* readSeed(source, seed.characteristic, seed.label);
		}
	});

/**
 * The machine-identity reads: the FirmwareVersion MMR (build number for the
 * update check), then the rest of the core's identity table serially (CPU board, model,
 * GHC, refill kit, serial, heater voltage, flush temp, flow multiplier). All
 * best-effort; each read is dispatched and its reply lands later as an
 * `MmrValue`.
 *
 * Step 5 of {@link de1ConnectProgram}, and run again by the manager after the
 * first healthy re-seed when a first connect handed off before reaching it (a
 * hollow first connect, or a link that dropped as the program finished):
 * without it that session never learns the model (Bengle frame encoding), the
 * serial (stolen-machine check) or the firmware build (cohort review
 * 2026-10-28, #3 P1).
 */
export const de1MachineInfoProgram = (
	d: Pick<De1ConnectDeps, 'core' | 'onStatus' | 'onCoreOutput'>
): Effect.Effect<void> =>
	Effect.gen(function* () {
		/** A non-fatal MMR read: dispatch → fold. Logs on skip; optional success line. */
		const readMmr = (reg: MmrRegister, ok?: string) =>
			Effect.gen(function* () {
				const out = yield* Effect.tryPromise(() => d.core.readMmr(reg));
				d.onCoreOutput(out);
				if (ok) d.onStatus(ok);
			}).pipe(
				Effect.catch((e) =>
					Effect.sync(() => d.onStatus(`DE1 MMR read ${reg} skipped: ${describeError(e)}`))
				)
			);
		// The core's identity table, firmware build first, serially in order.
		const [first, ...rest] = connectIdentityRegisters();
		if (first !== undefined) {
			yield* readMmr(first, 'DE1 firmware-build MMR read dispatched ✓');
		}
		yield* Effect.forEach(rest, (reg) => readMmr(reg), {
			concurrency: 1,
			discard: true
		});
	});

export const de1ConnectProgram = (d: De1ConnectDeps): Effect.Effect<void, De1ConnectStepFailed> =>
	Effect.gen(function* () {
		/** A fatal transport step — failure carries the step name for attribution. */
		const fatal = (step: string, thunk: () => Promise<unknown>) =>
			Effect.tryPromise({ try: thunk, catch: (cause) => new De1ConnectStepFailed({ step, cause }) });

		// 1) GATT connect (fatal).
		yield* fatal('GATT connect', () => d.device.connectGatt());
		d.onStatus('GATT connected');

		// 2) The required NOTIFY subscriptions, in the core's order (fatal —
		//    structural DE1 proof).
		const subscriptions = de1Subscriptions();
		for (const s of subscriptions.filter((x) => x.required)) {
			yield* fatal(subscribeStep(s), () =>
				d.device.startNotifications(De1Uuids.SERVICE, s.characteristic)
			);
			d.onStatus(`${s.name} ${s.short} subscribed ✓`);
		}

		// 3) All required GATT objects resolved — verified DE1.
		d.onGattVerified();
		d.onStatus('DE1 GATT verified ✓ — A000 + StateInfo/ShotSample/WaterLevels resolved');

		// 3b) The best-effort NOTIFYs (Calibration, Version) — NOT part of the
		//     structural DE1 proof, so a hiccup must not fail a verified link.
		//     Without Calibration the DE1's replies to `readCalibration` can't be
		//     delivered and the Settings → Calibration panel stays blank (audit F4).
		for (const s of subscriptions.filter((x) => !x.required)) {
			yield* Effect.gen(function* () {
				yield* Effect.tryPromise(() =>
					d.device.startNotifications(De1Uuids.SERVICE, s.characteristic)
				);
				d.onStatus(`${s.name} ${s.short} subscribed ✓`);
			}).pipe(
				Effect.catch((e) =>
					Effect.sync(() =>
						d.onStatus(`DE1 ${s.name} ${s.short} subscribe skipped: ${describeError(e)}`)
					)
				)
			);
		}

		// 4) Connect-time one-shot reads to seed the dashboard. StateInfo is
		//    required — a link that can't answer it is hollow (see de1SeedProgram).
		yield* de1SeedProgram(d);

		// 5) FirmwareVersion MMR (build number for the update check), then the sweep
		//    serially in declaration order. All best-effort.
		yield* de1MachineInfoProgram(d);

		d.onStatus('Ready — receiving DE1 notifications');
	});
