/**
 * `$lib/services/de1-orchestrator` — the DE1 connect sequence as an Effect
 * program (docs/53 §2.5 PR 4.1, T-17).
 *
 * Lifts `de1.ts`'s 250-line `connect()` `try/catch` (with its `let step = …`
 * error-attribution pattern) into a single `Effect.gen`. Each fatal step is an
 * `Effect.tryPromise` whose `catch` stamps the step name onto a
 * `De1ConnectStepFailed` — so a failure is attributed precisely (which is how
 * the user learns a non-DE1 device was selected) without the manual mutable
 * `step` variable. The non-fatal connect-time reads (version / state /
 * shot-settings) + the MMR sweep are `Effect.catchAll(log)`-wrapped so a single
 * failure logs-and-continues exactly as before; the sweep runs serially
 * (`concurrency: 1`) in declaration order, matching the legacy register order.
 *
 * Deliberately NOT a `Context.Tag`/Layer service: the transport + core are
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

import { Effect } from 'effect';
import type { CoreOutput, CremaCore, NotificationSource } from '$lib/core';
import { MmrRegister } from '$lib/core/crema-core';
import { De1Uuids } from '$lib/ble/de1-uuids';
import type { De1Transport } from '$lib/ble/de1-transport';
import { describeError } from '$lib/utils/error';
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
 * The five fatal NOTIFY subscriptions, in subscribe order. A failure here is the
 * structural tell the selected device is not a DE1 (its GATT lacks A000 / the
 * characteristic), so each is attributed to its own step.
 *
 * `char` is a thunk, not a string: the `De1Uuids.*` getters resolve lazily from
 * wasm (see `de1-uuids.ts`), so reading them here at module scope would fire the
 * wasm call at import time — before `loadCore()` runs, the very race this fixes.
 * The thunk defers each read to connect time, inside `de1ConnectProgram` below.
 */
const SUBSCRIBE_STEPS: ReadonlyArray<{ step: string; char: () => string; ok: string }> = [
	{ step: 'StateInfo characteristic A00E', char: () => De1Uuids.STATE_INFO, ok: 'StateInfo A00E subscribed ✓' },
	{ step: 'ShotSample characteristic A00D', char: () => De1Uuids.SHOT_SAMPLE, ok: 'ShotSample A00D subscribed ✓' },
	{ step: 'WaterLevels characteristic A011', char: () => De1Uuids.WATER_LEVELS, ok: 'WaterLevels A011 subscribed ✓' },
	{ step: 'MMR_READ characteristic A005', char: () => De1Uuids.MMR_READ, ok: 'MMR_READ A005 subscribed ✓' },
	{ step: 'ShotSettings characteristic A00B', char: () => De1Uuids.SHOT_SETTINGS, ok: 'ShotSettings A00B subscribed ✓' }
];

/**
 * Connect-time MMR sweep (mirrors the legacy `later_new_de1_connection_setup`).
 * `FirmwareVersion` is dispatched first (separately) so the firmware-update
 * check has its build number; the rest run serially in declaration order. All
 * reads are best-effort — each consumer falls back to "—" until the value lands.
 */
const CONNECT_MMR_SWEEP: ReadonlyArray<MmrRegister> = [
	MmrRegister.CpuBoardVersion,
	MmrRegister.MachineModel,
	MmrRegister.GhcInfo,
	MmrRegister.RefillKit,
	MmrRegister.SerialNumber,
	MmrRegister.HeaterVoltage,
	MmrRegister.FlushTemp,
	MmrRegister.CalibrationFlowMultiplier
];

export const de1ConnectProgram = (d: De1ConnectDeps): Effect.Effect<void, De1ConnectStepFailed> =>
	Effect.gen(function* () {
		/** A fatal transport step — failure carries the step name for attribution. */
		const fatal = (step: string, thunk: () => Promise<unknown>) =>
			Effect.tryPromise({ try: thunk, catch: (cause) => new De1ConnectStepFailed({ step, cause }) });

		/** A non-fatal connect-time one-shot read: read → core → fold → log. A
		 *  failure logs-and-continues (the connection is already verified). */
		const readSeed = (source: NotificationSource, char: string, label: string) =>
			Effect.gen(function* () {
				const bytes = yield* Effect.tryPromise(() => d.device.readCharacteristic(De1Uuids.SERVICE, char));
				const output = yield* Effect.tryPromise(() =>
					d.core.onNotification(source, bytes, performance.now())
				);
				d.onCoreOutput(output);
				d.onStatus(`DE1 ${label} read ✓`);
			}).pipe(
				Effect.catchAll((e) =>
					Effect.sync(() => d.onStatus(`DE1 ${label} read skipped: ${describeError(e)}`))
				)
			);

		/** A non-fatal MMR read: dispatch → fold. Logs on skip; optional success line. */
		const readMmr = (reg: MmrRegister, ok?: string) =>
			Effect.gen(function* () {
				const out = yield* Effect.tryPromise(() => d.core.readMmr(reg));
				d.onCoreOutput(out);
				if (ok) d.onStatus(ok);
			}).pipe(
				Effect.catchAll((e) =>
					Effect.sync(() => d.onStatus(`DE1 MMR read ${reg} skipped: ${describeError(e)}`))
				)
			);

		// 1) GATT connect (fatal).
		yield* fatal('GATT connect', () => d.device.connectGatt());
		d.onStatus('GATT connected');

		// 2) The five NOTIFY subscriptions, in order (fatal — structural DE1 proof).
		for (const s of SUBSCRIBE_STEPS) {
			yield* fatal(s.step, () => d.device.startNotifications(De1Uuids.SERVICE, s.char()));
			d.onStatus(s.ok);
		}

		// 3) All five GATT objects resolved — verified DE1.
		d.onGattVerified();
		d.onStatus('DE1 GATT verified ✓ — A000 + StateInfo/ShotSample/WaterLevels resolved');

		// 4) Connect-time one-shot reads to seed the dashboard (all non-fatal).
		yield* readSeed('De1Version', De1Uuids.VERSION, 'firmware version');
		yield* readSeed('De1State', De1Uuids.STATE_INFO, 'machine state');
		yield* readSeed('De1ShotSettings', De1Uuids.SHOT_SETTINGS, 'shot-settings');

		// 5) FirmwareVersion MMR (build number for the update check), then the sweep
		//    serially in declaration order. All best-effort.
		yield* readMmr(MmrRegister.FirmwareVersion, 'DE1 firmware-build MMR read dispatched ✓');
		yield* Effect.forEach(CONNECT_MMR_SWEEP, (reg) => readMmr(reg), {
			concurrency: 1,
			discard: true
		});

		d.onStatus('Ready — receiving DE1 notifications');
	});
