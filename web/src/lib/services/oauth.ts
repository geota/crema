/**
 * `#lib/services/oauth.js` — the OAuth/PKCE service (docs/53 §1.3, T-10).
 *
 * Effect-native reimplementation of the Authorization-Code + PKCE flow on top
 * of {@link HttpClient}. The token requests are rebuilt here so the service
 * genuinely consumes HttpClient (unified network handling) and exposes precise
 * typed errors. The *pure* pieces of `visualizer/oauth.ts` are reused verbatim,
 * not re-derived:
 *  - the PKCE crypto helpers (pinned RFC 7636 fixture) — `generateCodeVerifier`,
 *    `codeChallengeFromVerifier`, `randomState`,
 *  - the wire→TokenSet conversion `tokenSetFromWire` (the `expires_in → expiresAt`
 *    math, scope/type defaults, Doorkeeper rotation),
 *  - the sessionStorage PKCE stash/take helpers.
 *
 * The exact form-encoded request bodies are copied verbatim from the prior
 * `visualizer/oauth.ts` so the wire bytes are unchanged.
 */

import { Context, Effect, Layer, Schema } from 'effect';
import {
	HttpStatusError,
	NetworkError,
	OAuthNotConfiguredError,
	OAuthStateMismatchError,
	ResponseDecodeError,
	TokenRefreshFailedError
} from '../effect/errors.ts';
import { TokenWireSchema } from '../effect/schema/visualizer.ts';
import { HttpClient } from './http-client.ts';
import {
	AUTHORIZE_URL,
	OAUTH_SCOPE,
	REVOKE_URL,
	TOKEN_URL,
	clientId,
	codeChallengeFromVerifier,
	generateCodeVerifier,
	isConfigured,
	randomState,
	redirectUri,
	stashPkceState,
	takePkceState,
	tokenSetFromWire,
	type StartLoginOptions,
	type TokenSet
} from '../visualizer/oauth.ts';

/**
 * A token request that hangs (no response, no reset) fails as a retryable
 * `NetworkError` after this long instead of stalling every caller queued
 * behind the single-flight refresh (review 03 P3-5).
 */
const TOKEN_TIMEOUT_MS = 30_000;

const FORM_HEADERS = {
	'Content-Type': 'application/x-www-form-urlencoded',
	Accept: 'application/json'
} as const;

export class OAuth extends Context.Service<
	OAuth,
	{
		/** Begin the PKCE redirect; fails fast when no client_id is configured. */
		readonly startLogin: (opts?: StartLoginOptions) => Effect.Effect<void, OAuthNotConfiguredError>;
		/** Trade an authorization code for a TokenSet (CSRF state validated). */
		readonly exchangeCode: (
			code: string,
			state: string
		) => Effect.Effect<
			TokenSet,
			| OAuthNotConfiguredError
			| OAuthStateMismatchError
			| HttpStatusError
			| NetworkError
			| ResponseDecodeError
		>;
		/**
		 * Refresh an access token. Fails with `TokenRefreshFailedError` ONLY when
		 * the token endpoint definitively rejects the grant (see
		 * {@link classifyRefreshFailure}); every transient or indeterminate failure
		 * surfaces as `NetworkError` / a retryable `HttpStatusError`, so callers
		 * keep the stored session and try again later.
		 */
		readonly refreshToken: (
			refreshToken: string
		) => Effect.Effect<TokenSet, TokenRefreshFailedError | NetworkError | HttpStatusError>;
		/** Best-effort token revocation; never fails. */
		readonly revokeToken: (token: string) => Effect.Effect<void>;
	}
>()('crema/OAuth') {}

/** Token-endpoint statuses worth retrying as-is (the shared retry policy reads the status). */
const RETRYABLE_REFRESH_STATUS = (status: number) => status >= 500 || status === 408 || status === 429;

/** The OAuth `error` code of a token-endpoint error body, or null when it is not one. */
function oauthErrorCode(body: string | undefined): string | null {
	if (!body) return null;
	try {
		const json: unknown = JSON.parse(body);
		if (typeof json === 'object' && json !== null && 'error' in json && typeof json.error === 'string') {
			return json.error;
		}
	} catch {
		// Not JSON (a proxy or captive-portal page): not a definitive answer.
	}
	return null;
}

/**
 * Sort a failed refresh into the one outcome that ends the session and the
 * ones that must keep it (review 03 P1-1).
 *
 * - **Definitive** (`TokenRefreshFailedError`): the token endpoint answered
 *   HTTP 400 or 401 with the OAuth `invalid_grant` error. Visualizer runs
 *   Doorkeeper 5.9 with refresh-token rotation; Doorkeeper answers a revoked,
 *   expired, unknown or already-rotated refresh token with exactly that
 *   (RFC 6749 §5.2). Also a build with no OAuth client id, which can never
 *   refresh.
 * - **Retryable status** (`HttpStatusError`): 5xx, 408 and 429 pass through so
 *   the shared retry policy backs off by status.
 * - **Everything else** (`NetworkError`): a transport failure or timeout, a
 *   2xx body that does not decode (captive portals answer 200 with HTML), or
 *   any other status without `invalid_grant` (a proxy 403, `invalid_client`).
 *   None of these proves the refresh token is dead, so the session stays.
 */
export function classifyRefreshFailure(
	cause: unknown
): TokenRefreshFailedError | NetworkError | HttpStatusError {
	if (cause instanceof OAuthNotConfiguredError) return new TokenRefreshFailedError({ cause });
	if (cause instanceof HttpStatusError) {
		if ((cause.status === 400 || cause.status === 401) && oauthErrorCode(cause.body) === 'invalid_grant') {
			return new TokenRefreshFailedError({ cause });
		}
		if (RETRYABLE_REFRESH_STATUS(cause.status)) return cause;
	}
	if (cause instanceof NetworkError) return cause;
	return new NetworkError({ cause, url: TOKEN_URL });
}

export const OAuthLive = Layer.effect(
	OAuth,
	Effect.gen(function* () {
		const http = yield* HttpClient;

		/** Parse a `/oauth/token` 2xx body into a TokenSet (typed decode errors). */
		const parseTokenResponse = (res: Response) =>
			Effect.gen(function* () {
				const json = yield* Effect.tryPromise({
					try: () => res.json(),
					catch: (cause) => new ResponseDecodeError({ url: TOKEN_URL, cause })
				});
				const wire = yield* Schema.decodeUnknownEffect(TokenWireSchema)(json).pipe(
					Effect.mapError((cause) => new ResponseDecodeError({ url: TOKEN_URL, cause }))
				);
				return yield* Effect.try({
					try: () => tokenSetFromWire(wire),
					catch: (cause) => new ResponseDecodeError({ url: TOKEN_URL, cause })
				});
			});

		const startLogin = (opts?: StartLoginOptions) =>
			Effect.gen(function* () {
				if (!isConfigured()) return yield* Effect.fail(new OAuthNotConfiguredError());
				const verifier = generateCodeVerifier();
				const state = randomState();
				const challenge = yield* Effect.promise(() => codeChallengeFromVerifier(verifier));
				stashPkceState(verifier, state, opts?.returnTo);
				const params = new URLSearchParams({
					response_type: 'code',
					client_id: clientId(),
					redirect_uri: redirectUri(),
					scope: OAUTH_SCOPE,
					state,
					code_challenge: challenge,
					code_challenge_method: 'S256'
				});
				yield* Effect.sync(() => window.location.assign(`${AUTHORIZE_URL}?${params.toString()}`));
			});

		const exchangeCode = Effect.fn('OAuth.exchangeCode')(function* (code: string, state: string) {
			if (!isConfigured()) return yield* Effect.fail(new OAuthNotConfiguredError());
			// Single-use read of the PKCE verifier + the state we stashed.
			const { verifier, state: expected } = takePkceState();
			if (!verifier || !expected || expected !== state) {
				return yield* Effect.fail(new OAuthStateMismatchError({ expected, got: state }));
			}
			const body = new URLSearchParams({
				grant_type: 'authorization_code',
				code,
				redirect_uri: redirectUri(),
				client_id: clientId(),
				code_verifier: verifier
			});
			const res = yield* http.request({
				url: TOKEN_URL,
				method: 'POST',
				headers: FORM_HEADERS,
				body: body.toString()
			});
			return yield* parseTokenResponse(res);
		});

		const refreshToken = (refreshTokenValue: string) =>
			Effect.gen(function* () {
				// No client id: this build can never refresh (and must not POST an
				// empty client_id). `isConfigured` reads `import.meta.env`, absent under
				// plain node, so a throw there folds into the same typed failure.
				const configured = yield* Effect.try({
					try: () => isConfigured(),
					catch: () => new OAuthNotConfiguredError()
				});
				if (!configured) return yield* Effect.fail(new OAuthNotConfiguredError());
				const body = new URLSearchParams({
					grant_type: 'refresh_token',
					refresh_token: refreshTokenValue,
					client_id: clientId()
				}).toString();
				const res = yield* http.request({
					url: TOKEN_URL,
					method: 'POST',
					headers: FORM_HEADERS,
					body,
					timeoutMs: TOKEN_TIMEOUT_MS
				});
				return yield* parseTokenResponse(res);
			}).pipe(Effect.mapError(classifyRefreshFailure));

		const revokeToken = (token: string) =>
			Effect.gen(function* () {
				if (!isConfigured()) return;
				const body = new URLSearchParams({ token, client_id: clientId() }).toString();
				yield* http.request({ url: REVOKE_URL, method: 'POST', headers: FORM_HEADERS, body });
			}).pipe(Effect.ignore);

		return OAuth.of({ startLogin, exchangeCode, refreshToken, revokeToken });
	})
);
