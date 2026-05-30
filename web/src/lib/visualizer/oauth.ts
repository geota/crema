/**
 * `$lib/visualizer/oauth` — OAuth 2.0 Authorization-Code + PKCE flow for
 * Crema talking to Visualizer (Doorkeeper on the Rails side).
 *
 * Crema is a static, client-only PWA — there is no server that could keep
 * a client secret, so we register the Doorkeeper Application as a
 * **public client** (`confidential = false`) and rely on PKCE (RFC 7636)
 * to bind the authorization code to this browser tab.
 *
 * Flow at a glance:
 *   1.  {@link startVisualizerLogin} generates a random `code_verifier` +
 *       `state`, stashes both in `sessionStorage` (cleared on tab close),
 *       derives the `code_challenge` (S256), and redirects the tab to
 *       `/oauth/authorize`.
 *   2.  Visualizer bounces back to `/auth/visualizer/callback?code=…&state=…`.
 *       The callback page reads the verifier + state out of
 *       `sessionStorage`, verifies `state`, and POSTs to `/oauth/token`
 *       (no `client_secret`; we send the `code_verifier` instead).
 *   3.  The returned `TokenSet` is handed to {@link token-store} for
 *       longer-term persistence (localStorage).
 *
 * No HTTP-Basic side here — there is only one Visualizer SaaS instance
 * and we standardize on OAuth (coordinator direction, 2026-05-24).
 *
 * @see https://github.com/doorkeeper-gem/doorkeeper/wiki/Using-PKCE-flow
 * @see https://apidocs.visualizer.coffee/  §Authentication
 * @see RFC 7636 (PKCE)
 */

// ── Hard-coded endpoints ───────────────────────────────────────────────
//
// One Visualizer SaaS. No self-hosting story. The only env-var knob is the
// client_id (`VITE_VISUALIZER_CLIENT_ID`), which is per-environment (dev
// vs prod can have separate Doorkeeper applications).

/** Visualizer authorization endpoint (Doorkeeper). */
export const AUTHORIZE_URL = 'https://visualizer.coffee/oauth/authorize';
/** Visualizer token endpoint (Doorkeeper). */
export const TOKEN_URL = 'https://visualizer.coffee/oauth/token';
/** Visualizer token-revocation endpoint (Doorkeeper). */
export const REVOKE_URL = 'https://visualizer.coffee/oauth/revoke';

/**
 * Scopes Crema requests from Visualizer. Per the OpenAPI spec (1.8.2):
 *   - `read`   — read account metadata, shots, roasters, bags.
 *   - `upload` — upload shot files (ingestion-only; lower-risk).
 *   - `write`  — update/delete shots and manage roasters/bags (premium).
 *
 * We request `read + upload + write` because the bean-sync path needs
 * full write access to roasters and bags. The server will downgrade
 * silently (and our premium-gating logic in `visualizer-sync.ts` handles
 * the 403 path when the user is on the free tier).
 */
export const OAUTH_SCOPES: readonly string[] = ['read', 'write', 'upload'];

/** Joined-by-space form expected on the wire. */
export const OAUTH_SCOPE = OAUTH_SCOPES.join(' ');

/** `sessionStorage` key for the PKCE verifier (one in flight at a time). */
const VERIFIER_KEY = 'crema.visualizer.oauth.pkce.v1';
/** `sessionStorage` key for the CSRF state. */
const STATE_KEY = 'crema.visualizer.oauth.state.v1';
/** `sessionStorage` key for the post-login return path. */
const RETURN_KEY = 'crema.visualizer.oauth.return.v1';

// ── Public token shape ─────────────────────────────────────────────────

/** A successful exchange — what {@link token-store} persists. */
export interface TokenSet {
	accessToken: string;
	/** Doorkeeper returns one when the application allows offline access. */
	refreshToken: string | null;
	/** Absolute expiry timestamp in ms-since-epoch (best-effort). */
	expiresAt: number;
	/** Space-separated scope string the server actually granted. */
	scope: string;
	/** Always `"Bearer"` for the codes Doorkeeper hands out. */
	tokenType: string;
}

// ── Crypto helpers (PKCE) ──────────────────────────────────────────────

/** URL-safe base64 (RFC 4648 §5), no padding. */
function base64UrlEncode(bytes: Uint8Array): string {
	let s = '';
	for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
	return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Generate a 64-character URL-safe code verifier per RFC 7636.
 *
 * RFC 7636 §4.1 allows 43..128 characters from
 * `ALPHA / DIGIT / "-" / "." / "_" / "~"`. We pick 64 chars (well above
 * the 43 minimum, comfortably under the 128 ceiling, and a round-ish
 * power-friendly size) and feed them out of `crypto.getRandomValues`
 * mapped to URL-safe base64.
 */
export function generateCodeVerifier(): string {
	// 48 random bytes → 64 base64-url chars (4 chars per 3 bytes).
	const bytes = new Uint8Array(48);
	crypto.getRandomValues(bytes);
	return base64UrlEncode(bytes);
}

/**
 * Derive the S256 code challenge for a verifier per RFC 7636 §4.2:
 * URL-safe-base64( SHA-256(ascii(verifier)) ).
 */
export async function codeChallengeFromVerifier(verifier: string): Promise<string> {
	const bytes = new TextEncoder().encode(verifier);
	const digest = await crypto.subtle.digest('SHA-256', bytes);
	return base64UrlEncode(new Uint8Array(digest));
}

/**
 * Generate a random CSRF state token. Default 16 bytes → ~22 chars; plenty
 * of entropy to defeat a guessing attacker for a value that lives for the
 * length of a single redirect round-trip.
 */
export function randomState(bytes: number = 16): string {
	const buf = new Uint8Array(bytes);
	crypto.getRandomValues(buf);
	return base64UrlEncode(buf);
}

// ── Flow controller ────────────────────────────────────────────────────

/**
 * The redirect URI Visualizer should bounce the browser back to after the
 * user grants (or denies) access. Derived from `window.location.origin`
 * at runtime so it works in dev (`http://localhost:5173/...`), prod
 * (`https://crema.coffee/...`), and a Cloudflare-Pages preview alike —
 * no env-var fiddling. The user must register **each** origin's callback
 * URL in the Doorkeeper application's "Redirect URI" list.
 */
export function redirectUri(): string {
	return `${window.location.origin}/auth/visualizer/callback`;
}

/**
 * The configured Doorkeeper application client_id. Read from the build-time
 * env var `VITE_VISUALIZER_CLIENT_ID`. Returns the empty string if the env
 * var is missing — callers should surface a "not configured" message in
 * that case rather than send an obviously broken request.
 */
export function clientId(): string {
	const v = import.meta.env.VITE_VISUALIZER_CLIENT_ID;
	return typeof v === 'string' ? v : '';
}

/** Whether the build was configured with a Doorkeeper client_id. */
export function isConfigured(): boolean {
	return clientId().length > 0;
}

export interface StartLoginOptions {
	/** Optional path to return to after the callback (e.g. `'/settings'`). */
	returnTo?: string;
}

/**
 * Kick off the Authorization-Code + PKCE flow. Generates fresh verifier
 * and state, stashes them in `sessionStorage`, and redirects the tab to
 * `/oauth/authorize`. Never returns under normal circumstances (the
 * browser navigates away); a thrown error means the call was made before
 * the env var was configured or before `crypto.subtle` was available.
 */
export async function startVisualizerLogin(
	opts: StartLoginOptions = {}
): Promise<void> {
	const cid = clientId();
	if (!cid) {
		throw new Error(
			'Visualizer OAuth client_id is not configured. Set VITE_VISUALIZER_CLIENT_ID.'
		);
	}
	const verifier = generateCodeVerifier();
	const state = randomState();
	const challenge = await codeChallengeFromVerifier(verifier);

	sessionStorage.setItem(VERIFIER_KEY, verifier);
	sessionStorage.setItem(STATE_KEY, state);
	if (opts.returnTo) sessionStorage.setItem(RETURN_KEY, opts.returnTo);
	else sessionStorage.removeItem(RETURN_KEY);

	const params = new URLSearchParams({
		response_type: 'code',
		client_id: cid,
		redirect_uri: redirectUri(),
		scope: OAUTH_SCOPE,
		state,
		code_challenge: challenge,
		code_challenge_method: 'S256'
	});

	window.location.assign(`${AUTHORIZE_URL}?${params.toString()}`);
}

/**
 * Stash the PKCE verifier + CSRF state for the redirect round-trip. SSR-safe
 * (no-op without `sessionStorage`). Exported for the Effect `OAuth` service's
 * `startLogin`.
 */
export function stashPkceState(verifier: string, state: string, returnTo?: string): void {
	if (typeof sessionStorage === 'undefined') return;
	sessionStorage.setItem(VERIFIER_KEY, verifier);
	sessionStorage.setItem(STATE_KEY, state);
	if (returnTo) sessionStorage.setItem(RETURN_KEY, returnTo);
	else sessionStorage.removeItem(RETURN_KEY);
}

/**
 * Read + clear (single-use) the PKCE verifier and CSRF state. SSR-safe —
 * returns nulls without `sessionStorage`. Exported for the Effect `OAuth`
 * service's `exchangeCode`.
 */
export function takePkceState(): { verifier: string | null; state: string | null } {
	if (typeof sessionStorage === 'undefined') return { verifier: null, state: null };
	const verifier = sessionStorage.getItem(VERIFIER_KEY);
	const state = sessionStorage.getItem(STATE_KEY);
	sessionStorage.removeItem(VERIFIER_KEY);
	sessionStorage.removeItem(STATE_KEY);
	return { verifier, state };
}

/** Read + clear the return path the caller stashed before redirecting. */
export function takeReturnPath(fallback: string = '/settings'): string {
	const v = sessionStorage.getItem(RETURN_KEY);
	if (v) sessionStorage.removeItem(RETURN_KEY);
	return v && v.startsWith('/') ? v : fallback;
}

// ── Token exchange ─────────────────────────────────────────────────────

export interface TokenWire {
	access_token?: string;
	token_type?: string;
	expires_in?: number;
	refresh_token?: string;
	scope?: string;
	error?: string;
	error_description?: string;
}

/**
 * Convert a `/oauth/token` wire response into a {@link TokenSet}. Pure; throws
 * when `access_token` is absent (the server returned an error body). Exported
 * so the Effect `OAuth` service can reuse the exact same `expires_in → expiresAt`
 * math and scope/type defaults rather than re-deriving them.
 */
export function tokenSetFromWire(wire: TokenWire): TokenSet {
	if (!wire.access_token) {
		const msg = wire.error_description ?? wire.error ?? 'Missing access_token.';
		throw new Error(`Visualizer token endpoint: ${msg}`);
	}
	// `expires_in` is seconds-from-now; we store an absolute deadline so
	// `withFreshToken` can compare against `Date.now()` directly. Default
	// to a 1-hour window if the server omits it (Doorkeeper's default).
	const ttlMs = (wire.expires_in ?? 3600) * 1000;
	return {
		accessToken: wire.access_token,
		refreshToken: wire.refresh_token ?? null,
		expiresAt: Date.now() + ttlMs,
		scope: wire.scope ?? OAUTH_SCOPE,
		tokenType: wire.token_type ?? 'Bearer'
	};
}

async function postToken(body: URLSearchParams): Promise<TokenSet> {
	let res: Response;
	try {
		res = await fetch(TOKEN_URL, {
			method: 'POST',
			headers: {
				'Content-Type': 'application/x-www-form-urlencoded',
				Accept: 'application/json'
			},
			body
		});
	} catch (e) {
		throw new Error(`Network error talking to ${TOKEN_URL}: ${e instanceof Error ? e.message : String(e)}`);
	}
	let json: TokenWire;
	try {
		json = (await res.json()) as TokenWire;
	} catch {
		throw new Error(`Visualizer token endpoint returned non-JSON (HTTP ${res.status}).`);
	}
	if (!res.ok) {
		const msg = json.error_description ?? json.error ?? `HTTP ${res.status}`;
		throw new Error(`Visualizer token endpoint: ${msg}`);
	}
	return tokenSetFromWire(json);
}

export interface ExchangeCodeOptions {
	code: string;
	state: string;
}

/**
 * Trade an authorization code for a {@link TokenSet}. Verifies the CSRF
 * state matches what {@link startVisualizerLogin} stashed, reads + clears
 * the PKCE verifier, then POSTs to `/oauth/token` with
 * `grant_type=authorization_code`.
 *
 * **Public client** — no `client_secret` is sent; PKCE binds the code to
 * this browser via the `code_verifier`.
 */
export async function exchangeCodeForToken(
	opts: ExchangeCodeOptions
): Promise<TokenSet> {
	const cid = clientId();
	if (!cid) throw new Error('Visualizer OAuth client_id is not configured.');

	const verifier = sessionStorage.getItem(VERIFIER_KEY);
	const expectedState = sessionStorage.getItem(STATE_KEY);
	// Clear immediately — verifier is single-use, and we don't want a
	// rogue rerun to replay the exchange.
	sessionStorage.removeItem(VERIFIER_KEY);
	sessionStorage.removeItem(STATE_KEY);

	if (!verifier) {
		throw new Error(
			'No PKCE verifier found in this tab. Start the sign-in again.'
		);
	}
	if (!expectedState || expectedState !== opts.state) {
		throw new Error('OAuth state mismatch — possible CSRF. Start sign-in again.');
	}

	const body = new URLSearchParams({
		grant_type: 'authorization_code',
		code: opts.code,
		redirect_uri: redirectUri(),
		client_id: cid,
		code_verifier: verifier
	});
	return postToken(body);
}

/**
 * Use a refresh token to obtain a fresh access token. Doorkeeper rotates
 * refresh tokens by default, so we replace the stored refresh token on
 * success too.
 */
export async function refreshAccessToken(
	currentRefreshToken: string
): Promise<TokenSet> {
	const cid = clientId();
	if (!cid) throw new Error('Visualizer OAuth client_id is not configured.');
	const body = new URLSearchParams({
		grant_type: 'refresh_token',
		refresh_token: currentRefreshToken,
		client_id: cid
	});
	return postToken(body);
}

/**
 * Tell Visualizer to invalidate the token immediately. Best-effort —
 * if the network or the server rejects the call we still clear the
 * local copy. Per RFC 7009, a 200 response is returned regardless of
 * whether the token was already invalid.
 */
export async function revokeToken(token: string): Promise<void> {
	const cid = clientId();
	if (!cid) return;
	const body = new URLSearchParams({
		token,
		client_id: cid
	});
	try {
		await fetch(REVOKE_URL, {
			method: 'POST',
			headers: {
				'Content-Type': 'application/x-www-form-urlencoded',
				Accept: 'application/json'
			},
			body
		});
	} catch {
		// Swallow — we'll still clear local state.
	}
}
