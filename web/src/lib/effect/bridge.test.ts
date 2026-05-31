/**
 * `$lib/effect/bridge.test` — node:test for `runtimePromise`, the Promise-shaped
 * boundary every Svelte call site crosses (docs/53 D-04, R-05).
 *
 * `bridge.ts` only imports `effect` at runtime (its `runtime.ts` import is
 * `import type`, erased), so unlike the store-coupled services it IS
 * node:test-able. The contract under test is the one the whole migration leans
 * on: `runtimePromise` must reject with the **original** failure value
 * (`Cause.squash`), NOT Effect's `FiberFailure` wrapper — that's what keeps the
 * swapped call sites' `instanceof` / `_tag` probes working (e.g. the recoverable
 * classifier in `shot-persistence`, the history-page catch).
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/effect/bridge.test.ts`
 */

import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Effect, Layer, ManagedRuntime } from 'effect';
import { runtimePromise } from './bridge.ts';
import { HttpStatusError, NetworkError } from './errors.ts';
import type { AppRuntime } from './runtime.ts';

// A bare runtime is enough — these effects need no services. The cast documents
// that the real AppRuntime carries the full service set; runtimePromise's body
// only uses `runPromiseExit`, which every ManagedRuntime exposes.
const runtime = ManagedRuntime.make(Layer.empty) as unknown as AppRuntime;

describe('runtimePromise', () => {
	it('resolves with the success value', async () => {
		const out = await runtimePromise(runtime, Effect.succeed(42));
		assert.equal(out, 42);
	});

	it('rejects with the original Data.TaggedError instance (not a FiberFailure)', async () => {
		const err = new HttpStatusError({ status: 402, url: '/x', body: 'nope' });
		await assert.rejects(
			() => runtimePromise(runtime, Effect.fail(err)),
			(thrown: unknown) => {
				// The squash must hand back the SAME tagged instance, so both the
				// `instanceof` and the `_tag` discriminant survive the boundary.
				assert.ok(thrown instanceof HttpStatusError);
				assert.equal((thrown as HttpStatusError)._tag, 'HttpStatusError');
				assert.equal((thrown as HttpStatusError).status, 402);
				return true;
			}
		);
	});

	it('preserves the tag through a piped/mapped failure', async () => {
		const program = Effect.succeed(1).pipe(
			Effect.flatMap(() => Effect.fail(new NetworkError({ cause: new Error('offline'), url: '/y' })))
		);
		await assert.rejects(
			() => runtimePromise(runtime, program),
			(thrown: unknown) => {
				assert.ok(thrown instanceof NetworkError);
				assert.equal((thrown as NetworkError)._tag, 'NetworkError');
				return true;
			}
		);
	});

	it('surfaces a defect (thrown error) as the thrown value', async () => {
		const boom = new Error('boom');
		await assert.rejects(
			() => runtimePromise(runtime, Effect.sync(() => {
				throw boom;
			})),
			(thrown: unknown) => thrown === boom
		);
	});
});
