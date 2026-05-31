/**
 * `$lib/services/visualizer-call` — the single authenticated entry point to the
 * Visualizer cloud service (see `web/CONTEXT.md` → **visualizerCall**).
 *
 * Every Visualizer request funnels through {@link visualizerCall}. It:
 *  - attaches a fresh access token via `TokenVault.withFreshToken` (proactive
 *    refresh + one-shot 401 retry);
 *  - maps `HttpClient`'s `HttpStatusError` onto the Visualizer taxonomy
 *    (402/403 → premium-gated, 404 → not-found, everything else stays an
 *    `HttpStatusError`; 401 is handled upstream by `TokenVault`);
 *  - returns the parsed JSON body for a 2xx response, `null` for 204, and a
 *    `NetworkError` for a malformed 2xx body (so it stays recoverable).
 *
 * It is a **free `Effect`**: its requirements (`HttpClient | TokenVault`) ride the
 * `R` channel, so any program that already provides those — `ShotSync`,
 * `BeanSync` — can `yield*` it without a new layer. Those services discharge
 * `R` to `never` by providing their captured `http` / `vault` (their methods stay
 * `R = never`, which is what keeps `UploadQueue` able to call them without itself
 * holding `HttpClient` / `TokenVault`).
 *
 * This module owns the taxonomy and its policy: the {@link VisualizerCallError}
 * union, {@link isRecoverable} (which failures are worth a time-based retry), and
 * {@link describeVisualizerError} (the human-readable summary for the sync log).
 * Single-caller predicates (e.g. `ShotSync`'s `isAuthError` / `shouldAbortLoop`)
 * stay local to their one caller — only what's genuinely shared lives here.
 */

import { Effect } from 'effect';
import { HttpClient } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	TokenRefreshFailedError,
	VisualizerNotFoundError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';

/** Visualizer API base. */
export const API_BASE = 'https://visualizer.coffee/api';

/** The closed error union every {@link visualizerCall} can surface. */
export type VisualizerCallError =
	| NetworkError
	| HttpStatusError
	| NotAuthenticatedError
	| TokenRefreshFailedError
	| VisualizerPremiumGatedError
	| VisualizerNotFoundError;

/** A single Visualizer request. */
export interface VisualizerCallOptions {
	method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	/** Decoded JSON body — stringified before it rides the wire. */
	body?: unknown;
}

/**
 * Perform one authenticated Visualizer request. Succeeds with the parsed JSON
 * body (or `null` for 204); fails with a {@link VisualizerCallError}.
 *
 * The caller is responsible for decoding the `unknown` body (via the response
 * schemas) and for any endpoint-specific error handling (e.g. swallowing a 404
 * on delete with `Effect.catchTag('VisualizerNotFoundError', …)`).
 */
export const visualizerCall = (
	path: string,
	opts: VisualizerCallOptions = {}
): Effect.Effect<unknown, VisualizerCallError, HttpClient | TokenVault> =>
	Effect.gen(function* () {
		const http = yield* HttpClient;
		const vault = yield* TokenVault;
		return yield* vault
			.withFreshToken((token) => {
				const headers: Record<string, string> = {
					Authorization: `Bearer ${token}`,
					Accept: 'application/json'
				};
				if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
				return http.request({
					url: `${API_BASE}${path}`,
					method: opts.method ?? 'GET',
					headers,
					body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined
				});
			})
			.pipe(
				Effect.catchTag(
					'HttpStatusError',
					(
						err
					): Effect.Effect<never, VisualizerPremiumGatedError | VisualizerNotFoundError | HttpStatusError> => {
						if (err.status === 402 || err.status === 403) {
							return Effect.fail(new VisualizerPremiumGatedError({ endpoint: path }));
						}
						if (err.status === 404) {
							return Effect.fail(new VisualizerNotFoundError({ visualizerId: path }));
						}
						return Effect.fail(err);
					}
				),
				Effect.flatMap((res) =>
					res.status === 204
						? Effect.succeed(null)
						: // A malformed 2xx body is effectively a transport anomaly; surface it
							// as a `NetworkError` so it stays recoverable (matches the old module,
							// where a raw `res.json()` rejection was a non-VisualizerError →
							// treated as recoverable on upload, swallowed on a detail pull).
							Effect.tryPromise({
								try: () => res.json(),
								catch: (cause) => new NetworkError({ cause, url: `${API_BASE}${path}` })
							})
				)
			);
	});

/**
 * A `recoverable` failure is worth a time-based retry through the `UploadQueue`:
 * a transport failure, or a transient 5xx / 408 / aborted-or-blocked (status 0)
 * response. Auth / premium / not-found / decode failures need user action, not
 * time, so they're terminal.
 *
 * (`status === 0` is the canonical resolution of a pre-consolidation drift: the
 * old `UploadQueue` counted it recoverable, `ShotSync` did not. A status-0
 * `HttpStatusError` is a transport-blocked response, indistinguishable from a
 * `NetworkError` — and nearly unreachable on these CORS calls anyway, since
 * `HttpClient` maps opaque responses to success and fetch rejections to
 * `NetworkError`.)
 */
export function isRecoverable(e: VisualizerCallError | ResponseDecodeError): boolean {
	return (
		e._tag === 'NetworkError' ||
		(e._tag === 'HttpStatusError' &&
			(e.status === 0 || e.status === 408 || (e.status >= 500 && e.status < 600)))
	);
}

/**
 * Human-readable summary of a call failure for the sync log / console warn
 * (display only). The canonical wording across the sync surfaces — "row" rather
 * than "shot" so it reads sensibly for shots, bags, and roasters alike.
 */
export function describeVisualizerError(e: VisualizerCallError | ResponseDecodeError): string {
	switch (e._tag) {
		case 'HttpStatusError':
			return `HTTP ${e.status}: ${e.body ?? ''}`.trim();
		case 'NetworkError':
			return `Network error: ${e.cause instanceof Error ? e.cause.message : String(e.cause)}`;
		case 'VisualizerPremiumGatedError':
			return 'Premium subscription required for writes.';
		case 'VisualizerNotFoundError':
			return `Visualizer row not found: ${e.visualizerId}`;
		case 'NotAuthenticatedError':
			return 'Sign in to Visualizer first.';
		case 'TokenRefreshFailedError':
			return 'Visualizer rejected the access token. Please sign in again.';
		case 'ResponseDecodeError':
			return 'Unexpected Visualizer response.';
	}
}
