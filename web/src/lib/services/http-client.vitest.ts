/**
 * `$lib/services/http-client.test` — node:test suite for the HttpClient service
 * (docs/53 T-09). Stubs the global `fetch` and asserts the typed-error mapping.
 *
 * Run: `cd web && node --experimental-strip-types --experimental-detect-module
 *   --test src/lib/services/http-client.test.ts`
 */

import assert from 'node:assert/strict';
import { afterEach, describe, it } from 'vitest';
import { Cause, Effect, Exit } from 'effect';
import { HttpClient, HttpClientLive, type HttpRequest } from './http-client.ts';

const realFetch = globalThis.fetch;
afterEach(() => {
	globalThis.fetch = realFetch;
});

function run(req: HttpRequest) {
	return Effect.runPromiseExit(
		HttpClient.pipe(
			Effect.flatMap((c) => c.request(req)),
			Effect.provide(HttpClientLive)
		)
	);
}

/** The `_tag` of the failure, or null when the exit succeeded. */
function failTag(exit: Exit.Exit<unknown, { readonly _tag: string }>): string | null {
	if (!Exit.isFailure(exit)) return null;
	const f = Cause.failureOption(exit.cause);
	return f._tag === 'Some' ? f.value._tag : null;
}

describe('HttpClient.request', () => {
	it('returns the Response on a 2xx', async () => {
		globalThis.fetch = (async () => new Response('ok', { status: 200 })) as typeof fetch;
		const exit = await run({ url: 'https://x/api' });
		assert.ok(Exit.isSuccess(exit));
		assert.equal(exit.value.status, 200);
	});

	it('fails with HttpStatusError on a non-2xx, capturing status + body', async () => {
		globalThis.fetch = (async () =>
			new Response('nope', { status: 404 })) as typeof fetch;
		const exit = await run({ url: 'https://x/api' });
		assert.equal(failTag(exit), 'HttpStatusError');
		if (Exit.isFailure(exit)) {
			const f = Cause.failureOption(exit.cause);
			assert.ok(f._tag === 'Some');
			const err = f.value as { status: number; body?: string };
			assert.equal(err.status, 404);
			assert.equal(err.body, 'nope');
		}
	});

	it('fails with NetworkError when fetch rejects', async () => {
		globalThis.fetch = (async () => {
			throw new Error('offline');
		}) as typeof fetch;
		assert.equal(failTag(await run({ url: 'https://x/api' })), 'NetworkError');
	});

	it('fails with NetworkError when the request exceeds timeoutMs', async () => {
		// A fetch that never settles — the opt-in timeout must fire.
		globalThis.fetch = (() => new Promise(() => {})) as typeof fetch;
		assert.equal(failTag(await run({ url: 'https://x/api', timeoutMs: 10 })), 'NetworkError');
	});
});
