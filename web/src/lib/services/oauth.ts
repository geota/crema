/**
 * `$lib/services/oauth` — the OAuth/PKCE service (docs/53 §1.3, T-10).
 *
 * This is a thin Effect facade over the battle-tested Promise functions in
 * `visualizer/oauth.ts`. Those functions — and especially the pure PKCE crypto
 * helpers with their pinned RFC 7636 fixture — stay exactly as they are; this
 * service only re-presents the flow controllers (`startLogin`, `exchangeCode`,
 * `refreshToken`, `revokeToken`) as `Effect`s so `TokenVault` (T-11) and the
 * eventual boundary facade (T-16) can compose them.
 *
 * Deviation from the plan: the service does NOT consume `HttpClient`.
 * Reimplementing the auth token exchange on top of HttpClient would re-derive
 * auth-critical request construction for marginal gain; wrapping the existing,
 * working functions preserves sign-in behavior bit-for-bit. The HttpClient
 * integration can be revisited if the typed-error fidelity on `exchangeCode`
 * is later wanted.
 */

import { Context, Effect, Layer } from 'effect';
import { OAuthNotConfiguredError, TokenRefreshFailedError } from '../effect/errors.ts';
import {
	exchangeCodeForToken,
	isConfigured,
	refreshAccessToken,
	revokeToken as revokeTokenImpl,
	startVisualizerLogin,
	type StartLoginOptions,
	type TokenSet
} from '../visualizer/oauth.ts';

export class OAuth extends Context.Tag('crema/OAuth')<
	OAuth,
	{
		/**
		 * Begin the Authorization-Code + PKCE redirect. Fails fast with
		 * `OAuthNotConfiguredError` when no client_id is configured (rather than
		 * redirecting to an obviously-broken URL); otherwise navigates the tab
		 * away and never resolves.
		 */
		readonly startLogin: (opts?: StartLoginOptions) => Effect.Effect<void, OAuthNotConfiguredError>;
		/**
		 * Trade an authorization code for a `TokenSet`. The error stays opaque
		 * (`unknown`) — it surfaces the same `Error` the callback page already
		 * catches; flattened back to that instance at the `runtimePromise`
		 * boundary.
		 */
		readonly exchangeCode: (code: string, state: string) => Effect.Effect<TokenSet, unknown>;
		/** Refresh an access token. Fails with `TokenRefreshFailedError`. */
		readonly refreshToken: (refreshToken: string) => Effect.Effect<TokenSet, TokenRefreshFailedError>;
		/** Best-effort token revocation; never fails (mirrors the impl). */
		readonly revokeToken: (token: string) => Effect.Effect<void>;
	}
>() {}

export const OAuthLive = Layer.succeed(
	OAuth,
	OAuth.of({
		startLogin: (opts) =>
			isConfigured()
				? Effect.promise(() => startVisualizerLogin(opts))
				: Effect.fail(new OAuthNotConfiguredError()),
		exchangeCode: Effect.fn('OAuth.exchangeCode')(function* (code: string, state: string) {
			return yield* Effect.tryPromise({
				try: () => exchangeCodeForToken({ code, state }),
				catch: (cause) => cause
			});
		}),
		refreshToken: Effect.fn('OAuth.refreshToken')(function* (refreshToken: string) {
			return yield* Effect.tryPromise({
				try: () => refreshAccessToken(refreshToken),
				catch: (cause) => new TokenRefreshFailedError({ cause })
			});
		}),
		revokeToken: (token) => Effect.promise(() => revokeTokenImpl(token))
	})
);
