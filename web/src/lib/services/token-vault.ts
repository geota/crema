/**
 * `$lib/services/token-vault` — Visualizer token storage + refresh service
 * (docs/53 §1.3, Appendix A, T-11).
 *
 * The Effect-native home for what `visualizer/token-store.ts` does today:
 * persist the `TokenSet`, hand out known-fresh access tokens, and run an
 * authenticated request with a one-shot refresh-on-401. The in-memory listener
 * set (`onTokenChange`) becomes a `SubscriptionRef` (`changes`) — a single
 * source of truth whose `.changes` is a `Stream` consumers can later bridge
 * into a rune.
 *
 * Behavior matches today's `withFreshToken` exactly:
 *  - proactive refresh when the token is within 5 min of expiry,
 *  - one-shot forced refresh + retry on a 401,
 *  - clear-tokens + NotAuthenticatedError when there's no refresh token,
 *  - clear-tokens + TokenRefreshFailedError when a refresh fails,
 *  - Doorkeeper rotation: keep the old refresh token if the server omits one.
 *
 * The service is wired behind the existing Promise facade in T-16; until then
 * `visualizer/token-store.ts` remains the live implementation.
 */

import { Context, Effect, Layer, Ref, SubscriptionRef } from 'effect';
import { readJson, writeJson } from '../utils/storage.ts';
import { decodeOr } from '../effect/schema/decode.ts';
import { TokenSetSchema } from '../effect/schema/tokens.ts';
import { HttpStatusError, NotAuthenticatedError, TokenRefreshFailedError } from '../effect/errors.ts';
import { OAuth } from './oauth.ts';
import type { TokenSet } from '../visualizer/oauth.ts';

const TOKENS_KEY = 'crema.visualizer.tokens.v1';
/** Refresh proactively when the access token has < 5 minutes left. */
const EXPIRY_SAFETY_MS = 5 * 60 * 1000;

/** Read + validate the persisted token set (null when absent / invalid). */
function loadTokens(): TokenSet | null {
	const raw = readJson<unknown>(TOKENS_KEY, null);
	return raw === null ? null : decodeOr(TokenSetSchema, raw, null, TOKENS_KEY);
}

export class TokenVault extends Context.Tag('crema/TokenVault')<
	TokenVault,
	{
		readonly getTokens: Effect.Effect<TokenSet | null>;
		readonly storeTokens: (t: TokenSet) => Effect.Effect<void>;
		readonly clearTokens: Effect.Effect<void>;
		/**
		 * Run `req` with a known-fresh access token, refreshing first if expiring
		 * and once more on a 401. A non-401 `HttpStatusError` from `req`
		 * propagates unchanged.
		 */
		readonly withFreshToken: <A, E>(
			req: (token: string) => Effect.Effect<A, E | HttpStatusError>
		) => Effect.Effect<A, E | HttpStatusError | NotAuthenticatedError | TokenRefreshFailedError>;
		/** Live token state; `.changes` is a Stream for reactive consumers. */
		readonly changes: SubscriptionRef.SubscriptionRef<TokenSet | null>;
	}
>() {}

export const TokenVaultLive = Layer.effect(
	TokenVault,
	Effect.gen(function* () {
		const oauth = yield* OAuth;
		const ref = yield* SubscriptionRef.make<TokenSet | null>(loadTokens());

		/**
		 * The persisted token set is the source of truth. During the T-16 phase
		 * the old `visualizer/token-store.ts` still owns sign-in / sign-out and the
		 * bean-pull auth (option A), sharing this exact localStorage key — so a
		 * sign-in routed through it must be visible here immediately, with no stale
		 * in-memory cache. We therefore read localStorage fresh on every
		 * `getTokens` (matching token-store's own cache-free reads) and only fall
		 * back to the `SubscriptionRef` when localStorage is unavailable (node:test,
		 * where the tests seed state via `storeTokens`). The ref stays the reactive
		 * mirror that `.changes` exposes (writes go through `storeTokens` /
		 * `clearTokens`); once token-store is deleted next phase the ref can become
		 * the sole source again.
		 */
		const getTokens: Effect.Effect<TokenSet | null> = Effect.suspend(() =>
			typeof localStorage !== 'undefined' ? Effect.sync(loadTokens) : Ref.get(ref)
		);

		const storeTokens = (t: TokenSet) =>
			Effect.zipRight(
				Effect.sync(() => writeJson(TOKENS_KEY, t)),
				Ref.set(ref, t)
			);

		const clearTokens = Effect.zipRight(
			Effect.sync(() => {
				if (typeof localStorage !== 'undefined') localStorage.removeItem(TOKENS_KEY);
			}),
			Ref.set(ref, null)
		);

		const isExpiringSoon = (t: TokenSet) => t.expiresAt - Date.now() <= EXPIRY_SAFETY_MS;

		/** Force a refresh + persist; clear + fail on no-refresh-token or failure. */
		const refreshOnce = (
			t: TokenSet
		): Effect.Effect<TokenSet, NotAuthenticatedError | TokenRefreshFailedError> => {
			if (!t.refreshToken) {
				return clearTokens.pipe(Effect.zipRight(Effect.fail(new NotAuthenticatedError())));
			}
			const rt = t.refreshToken;
			return oauth.refreshToken(rt).pipe(
				// Doorkeeper rotates refresh tokens; keep the old one if omitted.
				Effect.map((fresh): TokenSet => ({ ...fresh, refreshToken: fresh.refreshToken ?? rt })),
				Effect.tap(storeTokens),
				Effect.catchAll((cause) =>
					clearTokens.pipe(
						Effect.zipRight(
							Effect.fail(
								cause instanceof TokenRefreshFailedError
									? cause
									: new TokenRefreshFailedError({ cause })
							)
						)
					)
				)
			);
		};

		/** A known-fresh token: refresh first if it's within the expiry buffer. */
		const fresh = Effect.gen(function* () {
			const tokens = yield* getTokens;
			if (tokens === null) return yield* Effect.fail(new NotAuthenticatedError());
			return isExpiringSoon(tokens) ? yield* refreshOnce(tokens) : tokens;
		});

		const withFreshToken = <A, E>(req: (token: string) => Effect.Effect<A, E | HttpStatusError>) =>
			Effect.gen(function* () {
				const tokens = yield* fresh;
				return yield* req(tokens.accessToken).pipe(
					// `instanceof` narrows the generic `E | HttpStatusError` union
					// (catchTag alone leaves a phantom `{ _tag }` member).
					Effect.catchTag('HttpStatusError', (err) =>
						err instanceof HttpStatusError && err.status === 401
							? refreshOnce(tokens).pipe(Effect.flatMap((t) => req(t.accessToken)))
							: Effect.fail(err)
					)
				);
			});

		return TokenVault.of({ getTokens, storeTokens, clearTokens, withFreshToken, changes: ref });
	})
);
