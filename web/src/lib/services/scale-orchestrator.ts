/**
 * `$lib/services/scale-orchestrator` — the scale connect sequence as an Effect
 * program (docs/53 §2.5 PR 4.3, T-19). The sibling of `de1-orchestrator`.
 *
 * Lifts `scale.ts`'s `connect()` try/catch into one `Effect.gen` with
 * step-tagged `ScaleConnectStepFailed`s. Unlike the DE1, the scale's GATT UUIDs
 * are core-reported (not hardcoded): the program connects → identifies the scale
 * with the core (codec selection) → fetches the per-model UUIDs → hands them
 * back via `onUuidsResolved` (so the manager sets its sink before the
 * subscribes) → subscribes to weight (+ the distinct command characteristic) →
 * fires the baseline settings query. A core that doesn't recognise the scale, or
 * reports no UUIDs, fails the program with the matching step.
 *
 * Not a Layer service (transport + core are per-connect values); mockable at the
 * transport seam — see `scale-orchestrator.vitest.ts`.
 */

import { Effect } from 'effect';
import type { CoreOutput, CremaCore, ScaleUuids } from '$lib/core';
import type { De1Transport } from '$lib/ble/de1-transport';
import { ScaleConnectStepFailed } from '../effect/errors.ts';

type CoreSlice = Pick<CremaCore, 'connectScale' | 'scaleUuids' | 'queryScaleSettings'>;

export interface ScaleConnectDeps {
	readonly device: De1Transport;
	readonly core: CoreSlice;
	/** The scale's advertised BLE name — used to identify the codec + surfaced. */
	readonly advertisedName: string;
	readonly onStatus: (line: string) => void;
	readonly onCoreOutput: (output: CoreOutput) => void;
	/** The core identified the scale — the manager reads capabilities + renders gated UI. */
	readonly onScaleIdentified: (advertisedName: string) => void;
	/**
	 * The core reported the scale's GATT UUIDs. The manager stores them + installs
	 * its notification sink HERE — before the program subscribes, so the sink's
	 * UUID→source mapping is ready when the first weight HVN arrives.
	 */
	readonly onUuidsResolved: (uuids: ScaleUuids) => void;
}

export const scaleConnectProgram = (d: ScaleConnectDeps): Effect.Effect<void, ScaleConnectStepFailed> =>
	Effect.gen(function* () {
		const fatal = <A>(step: string, thunk: () => Promise<A>) =>
			Effect.tryPromise({ try: thunk, catch: (cause) => new ScaleConnectStepFailed({ step, cause }) });

		d.onStatus(`Connecting to ${d.advertisedName}…`);
		yield* fatal('GATT connect', () => d.device.connectGatt());

		// Discovered GATT services help the core identify the scale when the
		// advertised name is ambiguous (Acaia generation) or mixed-case.
		// Best-effort — an empty list falls back to a name-only match.
		const services = yield* Effect.promise(
			(): Promise<readonly string[]> => d.device.discoveredServiceUuids?.() ?? Promise.resolve([])
		);

		// Identify the scale with the core so it picks the right codec.
		const label = yield* fatal('scale identification', () =>
			d.core.connectScale(d.advertisedName, services)
		);
		if (label === undefined) {
			return yield* new ScaleConnectStepFailed({
				step: 'scale identification',
				cause: `Core did not recognise scale '${d.advertisedName}'`
			});
		}
		d.onStatus(`Core recognised scale: ${label}`);

		// The core now knows the scale's GATT UUIDs — fetch them.
		const uuids = yield* fatal('scale UUIDs', () => d.core.scaleUuids());
		if (uuids === undefined) {
			return yield* new ScaleConnectStepFailed({
				step: 'scale UUIDs',
				cause: 'Core reported no scale UUIDs after connect'
			});
		}
		// Manager installs its sink now (needs the UUIDs), before we subscribe.
		d.onUuidsResolved(uuids);

		d.onStatus('Subscribing to scale weight…');
		yield* fatal('weight subscription', () =>
			d.device.startNotifications(uuids.service, uuids.weight_notify)
		);
		// Subscribe to the command characteristic ONLY when the core says it also
		// NOTIFYs (the Bookoo's `ff12` serial / settings responses). A write-only
		// command char (e.g. the Decent's `36f5`) rejects startNotifications and
		// fails the connect — gate on the capability so we never try. Writes still
		// go to command_write regardless.
		if (
			uuids.command_notifies &&
			uuids.command_write.toLowerCase() !== uuids.weight_notify.toLowerCase()
		) {
			yield* fatal('command subscription', () =>
				d.device.startNotifications(uuids.service, uuids.command_write)
			);
		}
		// A third stream for scales with an on-scale button characteristic
		// (Skale II EF82) — de1app subscribes to it too (bluetooth.tcl:221).
		if (uuids.button_notify) {
			yield* fatal('button subscription', () =>
				d.device.startNotifications(uuids.service, uuids.button_notify!)
			);
		}

		// Capability-gated config UI hangs off this.
		d.onScaleIdentified(d.advertisedName);

		// Baseline settings query so the scale's `03 0e` response lands
		// (empty for a weight-only scale).
		const query = yield* fatal('scale settings query', () => d.core.queryScaleSettings());
		d.onCoreOutput(query);
	});
