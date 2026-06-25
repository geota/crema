/**
 * `$lib/services/oauth` — the OAuth/PKCE service (docs/53 §1.3, T-10).
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

const FORM_HEADERS = {
	'Content-Type': 'application/x-www-form-urlencoded',
	Accept: 'application/json'
} as const;

export class OAuth extends Context.Tag('crema/OAuth')<
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
		/** Refresh an access token. Collapses every failure to one tag. */
		readonly refreshToken: (refreshToken: string) => Effect.Effect<TokenSet, TokenRefreshFailedError>;
		/** Best-effort token revocation; never fails. */
		readonly revokeToken: (token: string) => Effect.Effect<void>;
	}
>() {}

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
				const wire = yield* Schema.decodeUnknown(TokenWireSchema)(json).pipe(
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
				// clientId() is read inside Effect.try so a not-configured throw
				// becomes a typed failure (not a defect), and folds into the catch.
				const body = yield* Effect.try({
					try: () =>
						new URLSearchParams({
							grant_type: 'refresh_token',
							refresh_token: refreshTokenValue,
							client_id: clientId()
						}).toString(),
					catch: (cause) => cause
				});
				const res = yield* http.request({
					url: TOKEN_URL,
					method: 'POST',
					headers: FORM_HEADERS,
					body
				});
				return yield* parseTokenResponse(res);
			}).pipe(Effect.catchAll((cause) => Effect.fail(new TokenRefreshFailedError({ cause }))));

		const revokeToken = (token: string) =>
			Effect.gen(function* () {
				if (!isConfigured()) return;
				const body = new URLSearchParams({ token, client_id: clientId() }).toString();
				yield* http.request({ url: REVOKE_URL, method: 'POST', headers: FORM_HEADERS, body });
			}).pipe(Effect.ignore);

		return OAuth.of({ startLogin, exchangeCode, refreshToken, revokeToken });
	})
);
