/**
 * `$lib/effect/bridge` — interop primitives between the Effect world and the
 * `Promise`-shaped public boundary that Svelte components consume (docs/53 D-04).
 *
 * The boundary stays `Promise`-shaped: services are `Effect`s internally, but
 * every method exposed to component code is `async`. `runtimePromise` is the
 * single seam used to cross that boundary.
 */

import { Cause, Effect, Exit } from 'effect';
import type { AppRuntime, AppServices } from './runtime';

/**
 * Run an `Effect` on the app runtime and resolve/reject as a `Promise`.
 *
 * Unlike `runtime.runPromise`, which rejects with Effect's `FiberFailure`
 * wrapper, this rejects with the **original** failure value via
 * `Cause.squash`. That keeps legacy call-site `instanceof` checks working
 * during the migration (risk R-05) — components see the same error shapes
 * they always have until each error becomes a `Data.TaggedError`.
 */
export async function runtimePromise<A, E, R extends AppServices>(
	runtime: AppRuntime,
	effect: Effect.Effect<A, E, R>
): Promise<A> {
	const exit = await runtime.runPromiseExit(effect);
	if (Exit.isSuccess(exit)) return exit.value;
	throw Cause.squash(exit.cause);
}
