/**
 * `$lib/drive/oauth` — Google Drive authorization via the Google Identity
 * Services (GIS) **token model** (`google.accounts.oauth2`).
 *
 * Crema's web shell is a static, client-only PWA — there is no server to hold a
 * client secret, and Google's "Web application" client is *confidential* (its
 * token-exchange endpoint requires the secret, which a static bundle can't keep).
 * GIS's token model is Google's answer for browser apps: a popup (or silent)
 * flow that returns an access token straight to JavaScript using ONLY the public
 * `client_id`. Security is the OAuth client's **Authorized JavaScript origins**
 * allowlist — Google refuses token requests from any origin not on it. No secret
 * lives anywhere in the bundle.
 *
 * Scope is `drive.file` (the app only ever sees the backups it created). Tokens
 * are short-lived and there is **no refresh token** in this model — the store
 * re-requests one on demand (silently after the first consent, while the user's
 * Google session is valid). Nothing is persisted long-term.
 *
 * The native Android shell keeps its own auth-code + PKCE + refresh flow (a true
 * public *Android* OAuth client, also secret-less) — this module is web-only.
 */

/** Per-file Drive access — the app sees only the backups it creates. */
export const DRIVE_SCOPE = 'https://www.googleapis.com/auth/drive.file';

/** The GIS client library. */
const GIS_SRC = 'https://accounts.google.com/gsi/client';

/** A short-lived Drive access token (the GIS token model issues no refresh token). */
export interface DriveToken {
	accessToken: string;
	/** Absolute expiry, ms since epoch (derived from `expires_in`). */
	expiresAt: number;
	scope: string;
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

// ── Minimal GIS typings (the library ships no bundled types) ────────────────
interface GisTokenResponse {
	access_token?: string;
	expires_in?: number;
	scope?: string;
	token_type?: string;
	error?: string;
	error_description?: string;
}
interface GisTokenClient {
	requestAccessToken(overrides?: { prompt?: string }): void;
}
interface GisOAuth2 {
	initTokenClient(config: {
		client_id: string;
		scope: string;
		callback: (resp: GisTokenResponse) => void;
		error_callback?: (err: { type?: string }) => void;
	}): GisTokenClient;
	revoke(accessToken: string, done?: () => void): void;
}
declare global {
	interface Window {
		google?: { accounts?: { oauth2?: GisOAuth2 } };
	}
}

let gisLoad: Promise<GisOAuth2> | undefined;

/** Inject + await the GIS client library once; resolves with `google.accounts.oauth2`. */
function loadGis(): Promise<GisOAuth2> {
	if (typeof window === 'undefined' || typeof document === 'undefined') {
		return Promise.reject(new Error('Google Drive sign-in needs a browser.'));
	}
	const ready = window.google?.accounts?.oauth2;
	if (ready) return Promise.resolve(ready);
	if (gisLoad) return gisLoad;
	gisLoad = new Promise<GisOAuth2>((resolve, reject) => {
		const s = document.createElement('script');
		s.src = GIS_SRC;
		s.async = true;
		s.defer = true;
		s.onload = () => {
			const oauth2 = window.google?.accounts?.oauth2;
			if (oauth2) resolve(oauth2);
			else reject(new Error('Google Identity Services loaded without the OAuth2 API.'));
		};
		s.onerror = () => {
			gisLoad = undefined; // let a later attempt retry the load
			reject(new Error('Could not load Google Identity Services.'));
		};
		document.head.appendChild(s);
	});
	return gisLoad;
}

/**
 * Request a Drive access token via the GIS token model. `'consent'` forces the
 * consent popup (the explicit "Connect"); `''` re-grants silently after the
 * first authorization (used to refresh before a backup). MUST be called from a
 * user gesture (button click) so the popup isn't blocked.
 *
 * @throws if the client_id isn't configured, the user cancels the popup, or GIS
 *   returns an error.
 */
export async function requestDriveToken(prompt: 'consent' | ''): Promise<DriveToken> {
	const cid = clientId();
	if (!cid) {
		throw new Error('Drive OAuth client_id is not configured (VITE_GOOGLE_DRIVE_CLIENT_ID).');
	}
	const oauth2 = await loadGis();
	return new Promise<DriveToken>((resolve, reject) => {
		const client = oauth2.initTokenClient({
			client_id: cid,
			scope: DRIVE_SCOPE,
			callback: (resp) => {
				if (resp.error || !resp.access_token) {
					reject(new Error(resp.error_description || resp.error || 'No access token returned.'));
					return;
				}
				resolve({
					accessToken: resp.access_token,
					expiresAt: Date.now() + (resp.expires_in ?? 3600) * 1000,
					scope: resp.scope ?? DRIVE_SCOPE
				});
			},
			error_callback: (err) => {
				reject(
					new Error(
						err?.type === 'popup_closed'
							? 'Google sign-in was cancelled.'
							: err?.type === 'popup_failed_to_open'
								? 'Google sign-in popup was blocked — allow popups and retry.'
								: 'Google sign-in failed.'
					)
				);
			}
		});
		client.requestAccessToken({ prompt });
	});
}

/** Revoke an access token (best-effort — clears the grant server-side). */
export async function revokeDriveToken(accessToken: string): Promise<void> {
	const oauth2 = await loadGis().catch(() => undefined);
	if (!oauth2) return;
	await new Promise<void>((resolve) => oauth2.revoke(accessToken, () => resolve()));
}
