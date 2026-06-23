/**
 * `$lib/drive/oauth` — Google OAuth 2.0 Authorization-Code + PKCE for the
 * Drive backup feature. Crema is a static client-only PWA (no server to keep a
 * secret), so this is a **public client** (PKCE, RFC 7636) — same model as the
 * Visualizer flow, whose provider-agnostic crypto helpers we reuse.
 *
 * Scope is **`drive.file`** (per-file consent): the app only ever sees the
 * backup files it created, never the user's wider Drive. That's the scope the
 * privacy policy commits to and the OAuth client is configured with.
 *
 * Flow:
 *   1. {@link startGoogleDriveLogin} stashes a fresh PKCE verifier + state in
 *      `sessionStorage` and redirects to Google's consent screen with
 *      `access_type=offline` (so we get a refresh token).
 *   2. Google bounces back to `/auth/google/callback?code=…&state=…`; that page
 *      calls {@link exchangeGoogleDriveCode}.
 *   3. The {@link DriveTokenSet} is persisted by `$lib/drive/store`.
 *   4. {@link refreshGoogleDriveToken} swaps the refresh token for a fresh
 *      access token when the REST layer hits a 401 / sees an expired token.
 *
 * client_id comes from `VITE_GOOGLE_DRIVE_CLIENT_ID` (per-environment, like the
 * Visualizer id). The user registers each origin's `/auth/google/callback` in
 * the Google Cloud "Web application" OAuth client's redirect-URI list.
 */

import {
	codeChallengeFromVerifier,
	generateCodeVerifier,
	randomState
} from '$lib/visualizer/oauth';

// ── Endpoints + scope ──────────────────────────────────────────────────
export const GOOGLE_AUTHORIZE_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
export const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
export const GOOGLE_REVOKE_URL = 'https://oauth2.googleapis.com/revoke';
/** Per-file Drive access — the app sees only the backups it creates. */
export const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';

// ── sessionStorage keys (one PKCE flight at a time) ─────────────────────
const VERIFIER_KEY = 'crema.drive.oauth.pkce.v1';
const STATE_KEY = 'crema.drive.oauth.state.v1';
const RETURN_KEY = 'crema.drive.oauth.return.v1';

/** A successful exchange — what `$lib/drive/store` persists. */
export interface DriveTokenSet {
	accessToken: string;
	/** Present only on the FIRST consent (`access_type=offline` + `prompt=consent`);
	 *  re-auth without consent omits it, so we keep the previously-stored one. */
	refreshToken: string | null;
	/** Absolute expiry, ms since epoch (best-effort from `expires_in`). */
	expiresAt: number;
	scope: string;
	tokenType: string;
}

/** Redirect URI — origin-derived so dev / prod / preview all work without env
 *  fiddling. Each origin's callback must be registered on the OAuth client. */
export function redirectUri(): string {
	return `${window.location.origin}/auth/google/callback`;
}

/** The Drive OAuth client_id (`VITE_GOOGLE_DRIVE_CLIENT_ID`), or '' if unset. */
export function clientId(): string {
	const v = import.meta.env.VITE_GOOGLE_DRIVE_CLIENT_ID;
	return typeof v === 'string' ? v : '';
}

/** Whether the build was configured with a Drive client_id. */
export function isConfigured(): boolean {
	return clientId().length > 0;
}

/** Read + clear the post-login return path (same-origin absolute paths only). */
export function takeReturnPath(fallback = '/settings'): string {
	if (typeof sessionStorage === 'undefined') return fallback;
	const v = sessionStorage.getItem(RETURN_KEY);
	if (v) sessionStorage.removeItem(RETURN_KEY);
	return v && v.startsWith('/') && !v.startsWith('//') && !v.startsWith('/\\') ? v : fallback;
}

/**
 * Begin the Authorization-Code + PKCE flow — redirects the tab to Google's
 * consent screen. `access_type=offline` + `prompt=consent` ensure a refresh
 * token so backups keep working after the ~1h access-token expiry. Throws if
 * the client_id isn't configured (caller should surface a notice first).
 */
export async function startGoogleDriveLogin(returnTo = '/settings'): Promise<void> {
	const cid = clientId();
	if (!cid) {
		throw new Error('Drive OAuth client_id is not configured. Set VITE_GOOGLE_DRIVE_CLIENT_ID.');
	}
	const verifier = generateCodeVerifier();
	const state = randomState();
	const challenge = await codeChallengeFromVerifier(verifier);

	sessionStorage.setItem(VERIFIER_KEY, verifier);
	sessionStorage.setItem(STATE_KEY, state);
	sessionStorage.setItem(RETURN_KEY, returnTo);

	const params = new URLSearchParams({
		response_type: 'code',
		client_id: cid,
		redirect_uri: redirectUri(),
		scope: DRIVE_SCOPE,
		state,
		code_challenge: challenge,
		code_challenge_method: 'S256',
		access_type: 'offline',
		// Force the consent prompt so a re-auth still returns a refresh token
		// (Google omits it on silent re-auth, which would leave us unable to
		// refresh once the first one is lost).
		prompt: 'consent',
		include_granted_scopes: 'true'
	});
	window.location.assign(`${GOOGLE_AUTHORIZE_URL}?${params.toString()}`);
}

interface TokenWire {
	access_token?: string;
	token_type?: string;
	expires_in?: number;
	refresh_token?: string;
	scope?: string;
	error?: string;
	error_description?: string;
}

/** Wire → {@link DriveTokenSet}. Throws on an error body (no access_token). */
function tokenSetFromWire(wire: TokenWire, fallbackRefresh: string | null): DriveTokenSet {
	if (!wire.access_token) {
		const msg = wire.error_description ?? wire.error ?? 'Missing access_token.';
		throw new Error(`Google token endpoint: ${msg}`);
	}
	const expiresInMs = (wire.expires_in ?? 3600) * 1000;
	return {
		accessToken: wire.access_token,
		refreshToken: wire.refresh_token ?? fallbackRefresh,
		expiresAt: Date.now() + expiresInMs,
		scope: wire.scope ?? DRIVE_SCOPE,
		tokenType: wire.token_type ?? 'Bearer'
	};
}

/**
 * Exchange the `?code=…&state=…` from the callback for a {@link DriveTokenSet}.
 * Verifies the CSRF state + consumes the single-use PKCE verifier.
 *
 * @throws on state mismatch, a missing verifier, or a token-endpoint error.
 */
export async function exchangeGoogleDriveCode(code: string, state: string): Promise<DriveTokenSet> {
	const verifier = sessionStorage.getItem(VERIFIER_KEY);
	const expectedState = sessionStorage.getItem(STATE_KEY);
	sessionStorage.removeItem(VERIFIER_KEY);
	sessionStorage.removeItem(STATE_KEY);
	if (!verifier) throw new Error('No PKCE verifier in this tab — restart the sign-in.');
	if (!expectedState || expectedState !== state) {
		throw new Error('OAuth state mismatch — sign-in could not be verified.');
	}
	const body = new URLSearchParams({
		grant_type: 'authorization_code',
		code,
		client_id: clientId(),
		redirect_uri: redirectUri(),
		code_verifier: verifier
	});
	const res = await fetch(GOOGLE_TOKEN_URL, {
		method: 'POST',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
		body
	});
	return tokenSetFromWire((await res.json()) as TokenWire, null);
}

/**
 * Swap a refresh token for a fresh access token (the REST layer calls this when
 * a token is within the expiry skew or a request 401s). Google keeps the same
 * refresh token, so we carry it forward.
 *
 * @throws if there's no refresh token, or the refresh is rejected (the user
 *   must re-consent).
 */
export async function refreshGoogleDriveToken(refreshToken: string | null): Promise<DriveTokenSet> {
	if (!refreshToken) throw new Error('No refresh token — sign in to Google Drive again.');
	const body = new URLSearchParams({
		grant_type: 'refresh_token',
		refresh_token: refreshToken,
		client_id: clientId()
	});
	const res = await fetch(GOOGLE_TOKEN_URL, {
		method: 'POST',
		headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
		body
	});
	return tokenSetFromWire((await res.json()) as TokenWire, refreshToken);
}
