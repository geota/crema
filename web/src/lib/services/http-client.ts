/**
 * `$lib/services/http-client` — the Visualizer HTTP client service (docs/53
 * §1.3, T-09).
 *
 * Wraps `fetch` and turns its two failure modes into typed errors:
 *  - the fetch promise rejecting (offline, DNS, CORS) → `NetworkError`
 *  - a non-2xx response → `HttpStatusError` (carrying status + body so callers
 *    can branch: 401 → refresh, 402/403 → premium-gated, …)
 *
 * No retries here (those live in `UploadQueue`). The fetch is wired to the
 * fiber's `AbortSignal`, so an interrupt or timeout actually cancels the
 * in-flight request — mirroring today's `AbortSignal.timeout(…)` usage.
 *
 * Timeout is **opt-in per request** (`timeoutMs`), not a global default: the
 * only caller that times out today is the webhook fan-out (5 s). Shot uploads
 * currently have no timeout, and imposing one here would be a silent behavior
 * change when this client is wired into ShotSync (T-12).
 */

import { Context, Effect, Layer } from 'effect';
import { HttpStatusError, NetworkError } from '../effect/errors.ts';

/** A single HTTP request. Mirrors the slice of `fetch` the app actually uses. */
export interface HttpRequest {
	readonly url: string;
	readonly method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	readonly headers?: Record<string, string>;
	readonly body?: string;
	/** When set, the request fails with `NetworkError` after this many ms. */
	readonly timeoutMs?: number;
	/**
	 * `fetch` request mode. Defaults to the browser default (`cors`). Set
	 * `'no-cors'` for fire-and-forget cross-origin POSTs (webhooks): the
	 * response comes back *opaque* (unreadable status/body) but a successful
	 * dispatch resolves rather than failing — see the opaque handling below.
	 */
	readonly mode?: RequestMode;
}

export class HttpClient extends Context.Tag('crema/HttpClient')<
	HttpClient,
	{
		/**
		 * Perform `req`. Succeeds with the 2xx `Response` (body unread, so the
		 * caller decodes it). Fails with `HttpStatusError` on a non-2xx response
		 * or `NetworkError` on a transport failure / timeout.
		 */
		readonly request: (req: HttpRequest) => Effect.Effect<Response, NetworkError | HttpStatusError>;
	}
>() {}

const request = Effect.fn('HttpClient.request')(function* (req: HttpRequest) {
	const doFetch = Effect.tryPromise({
		try: (signal) =>
			fetch(req.url, {
				method: req.method ?? 'GET',
				headers: req.headers,
				body: req.body,
				mode: req.mode,
				signal
			}),
		catch: (cause) => new NetworkError({ cause, url: req.url })
	});

	const res = yield* (req.timeoutMs === undefined
		? doFetch
		: doFetch.pipe(
				Effect.timeoutFail({
					duration: req.timeoutMs,
					onTimeout: () =>
						new NetworkError({
							cause: new Error(`Request timed out after ${req.timeoutMs}ms`),
							url: req.url
						})
				})
			));

	// `res.ok` covers normal 2xx; an opaque response (from a `no-cors` request)
	// has `ok === false` / `status === 0` but represents a successful dispatch —
	// the body/status are deliberately unreadable, so treat it as success.
	if (res.ok || res.type === 'opaque') return res;

	// Non-2xx: capture the body text (best-effort) for the typed error so
	// callers don't have to re-read a consumed stream.
	const body = yield* Effect.promise(() => res.text().catch(() => ''));
	return yield* new HttpStatusError({ status: res.status, url: req.url, body });
});

/** Live implementation backed by the global `fetch`. */
export const HttpClientLive = Layer.succeed(HttpClient, HttpClient.of({ request }));
