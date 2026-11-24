/**
 * `#lib/services/grinder-orchestrator.js` — the connected grinder's connect
 * sequence as an Effect program, the sibling of `scale-orchestrator`
 * (spec `.scratch/grinder-ble/SPEC.md` §2.6).
 *
 * connect GATT → discover the services → the core identifies the grinder
 * (a fresh driver) → its GATT UUIDs → the manager installs its sink → subscribe
 * to the notify characteristic → the handshake: the core's `WriteGrinder`
 * commands, written in order, with response. The core then waits up to 5 s for
 * the first reading (`GrinderReady`, else `GrinderLinkDead`).
 *
 * A reconnect runs {@link grinderHandshakeProgram} alone: the transport has
 * replayed the subscription, and the core needs a new driver and handshake
 * (the driver keeps nothing across connects).
 *
 * The shell owns no protocol: the UUIDs, the handshake bytes and whether they
 * go with response all come from the core.
 */

import { Effect } from 'effect';
import type { CoreOutput, CremaCore, GrinderUuids } from '#lib/core/index.js';
import type { BleDevice } from '#lib/ble/transport.js';
import { GrinderConnectStepFailed } from '../effect/errors.ts';
import { grinderShownName } from '#lib/wasm/de1_wasm.js';

/**
 * Web Bluetooth can't tell the negotiated MTU, so the handshake goes in
 * 20-byte packets (the core's `max_packet` when the MTU is unknown, §2.6.3).
 */
export const WEB_MAX_PACKET = 20;

type CoreSlice = Pick<CremaCore, 'connectGrinder' | 'grinderUuids' | 'grinderSubscribed'>;

/** The transport calls the program makes (`BleDevice`; a fake in tests). */
export type GrinderTransport = Pick<
	BleDevice,
	'connectGatt' | 'discoveredServiceUuids' | 'startNotifications' | 'write'
>;

export interface GrinderHandshakeDeps {
	readonly device: GrinderTransport;
	readonly core: CoreSlice;
	/** The advertised name: the core identifies the model from it. */
	readonly advertisedName: string;
	/** The GATT services discovered on the first connect. */
	readonly services: readonly string[];
	/** The notification clock (`performance.now()`), for the first-reading deadline. */
	readonly now: () => number;
	readonly onStatus: (line: string) => void;
	/** Events the core returned with the handshake (none today). */
	readonly onCoreOutput: (output: CoreOutput) => void;
}

export interface GrinderConnectDeps extends Omit<GrinderHandshakeDeps, 'services'> {
	/** The core identified the grinder: its model id. */
	readonly onIdentified: (model: string, services: readonly string[]) => void;
	/**
	 * The core reported the grinder's GATT UUIDs: the manager installs its sink
	 * here, before the subscribe, so the first notification is routed.
	 */
	readonly onUuidsResolved: (uuids: GrinderUuids) => void;
}

const step =
	(name: string) =>
	<A>(thunk: () => Promise<A>) =>
		Effect.tryPromise({ try: thunk, catch: (cause) => new GrinderConnectStepFailed({ step: name, cause }) });

/**
 * A fresh driver and its handshake: identify again, then write the core's
 * connect writes in order. After a reconnect the subscription is already back.
 */
export const grinderHandshakeProgram = (
	d: GrinderHandshakeDeps
): Effect.Effect<void, GrinderConnectStepFailed> =>
	Effect.gen(function* () {
		const model = yield* step('grinder identification')(() =>
			d.core.connectGrinder(d.advertisedName, d.services)
		);
		if (model === undefined) {
			return yield* new GrinderConnectStepFailed({
				step: 'grinder identification',
				cause: `Not a grinder Crema knows: '${grinderShownName(d.advertisedName)}'`
			});
		}
		const uuids = yield* step('grinder UUIDs')(() => d.core.grinderUuids());
		if (uuids === undefined) {
			return yield* new GrinderConnectStepFailed({
				step: 'grinder UUIDs',
				cause: 'The core reported no grinder UUIDs'
			});
		}
		yield* writeHandshake(d, uuids);
	});

/** Ask the core for the handshake and write it, in order. */
const writeHandshake = (
	d: Pick<GrinderHandshakeDeps, 'device' | 'core' | 'now' | 'onStatus' | 'onCoreOutput'>,
	uuids: GrinderUuids
): Effect.Effect<void, GrinderConnectStepFailed> =>
	Effect.gen(function* () {
		const out = yield* step('handshake')(() => d.core.grinderSubscribed(WEB_MAX_PACKET, d.now()));
		const writes = out.commands.flatMap((c) =>
			c.type === 'WriteGrinder' ? [new Uint8Array(c.content.data)] : []
		);
		if (writes.length > 0) d.onStatus('Saying hello to the grinder…');
		for (const data of writes) {
			yield* step('handshake')(() =>
				d.device.write(uuids.service, uuids.write, data, uuids.write_with_response)
			);
		}
		// The handshake's commands went out above; only its events are passed on.
		if (out.events.length > 0) d.onCoreOutput({ ...out, commands: [] });
	});

export const grinderConnectProgram = (
	d: GrinderConnectDeps
): Effect.Effect<void, GrinderConnectStepFailed> =>
	Effect.gen(function* () {
		// The model prefix only: the name's suffix may derive from the serial.
		d.onStatus(`Connecting to ${grinderShownName(d.advertisedName)}…`);
		yield* step('GATT connect')(() => d.device.connectGatt());
		const services = yield* Effect.promise(
			(): Promise<readonly string[]> => d.device.discoveredServiceUuids?.() ?? Promise.resolve([])
		);
		const model = yield* step('grinder identification')(() =>
			d.core.connectGrinder(d.advertisedName, services)
		);
		if (model === undefined) {
			return yield* new GrinderConnectStepFailed({
				step: 'grinder identification',
				cause: `Not a grinder Crema knows: '${grinderShownName(d.advertisedName)}'`
			});
		}
		d.onIdentified(model, services);
		const uuids = yield* step('grinder UUIDs')(() => d.core.grinderUuids());
		if (uuids === undefined) {
			return yield* new GrinderConnectStepFailed({
				step: 'grinder UUIDs',
				cause: 'The core reported no grinder UUIDs'
			});
		}
		// A service or characteristic that isn't there fails the subscribe:
		// "This doesn't look like a …" (§2.6.2) comes from that step name.
		d.onUuidsResolved(uuids);
		d.onStatus('Subscribing to the grinder…');
		yield* step('subscription')(() => d.device.startNotifications(uuids.service, uuids.notify));
		yield* writeHandshake(d, uuids);
	});
