/**
 * `$lib/ble/connect-exit` — interpret a BLE connect program's `Exit`.
 *
 * `De1Manager` and `ScaleManager` run their connect sequences as Effect programs
 * (`de1ConnectProgram` / `scaleConnectProgram`) that fail with a step-tagged
 * error — `De1ConnectStepFailed` / `ScaleConnectStepFailed`, both carrying
 * `{ step, cause }`. Both managers then need the same forensic read of the
 * resulting `Exit`: on failure, recover which step failed (falling back to
 * `'connect'` + the raw cause for a defect / non-tagged failure) so the status
 * line can name it.
 *
 * That read was copied verbatim into both managers. It's a pure function of the
 * `Exit` — no device, no runtime, no `this` — so it lives here and is unit-tested
 * directly, which also gives the managers' otherwise browser-only failure path
 * its first automated coverage. The lifecycle around it (gesture `requestDevice`,
 * listeners, `setSink`) stays in each manager.
 */

import { Cause, Exit } from 'effect';

/** The shared shape of a connect program's tagged failure. */
interface ConnectStepFailure {
	readonly step: string;
	readonly cause: unknown;
}

/** The interpreted outcome of a connect program's `Exit`. */
export type ConnectOutcome =
	| { readonly ok: true }
	| { readonly ok: false; readonly step: string; readonly cause: unknown };

/**
 * Interpret a connect program's `Exit`. Success → `{ ok: true }`. Failure →
 * `{ ok: false, step, cause }`, reading the step-tagged `{ step, cause }` when the
 * cause carries a tagged failure, else falling back to step `'connect'` and the
 * whole `Cause` (a defect / interruption is not a tagged step failure).
 */
export function parseConnectExit(exit: Exit.Exit<void, ConnectStepFailure>): ConnectOutcome {
	if (Exit.isSuccess(exit)) return { ok: true };
	const failure = Cause.failureOption(exit.cause);
	return failure._tag === 'Some'
		? { ok: false, step: failure.value.step, cause: failure.value.cause }
		: { ok: false, step: 'connect', cause: exit.cause };
}
