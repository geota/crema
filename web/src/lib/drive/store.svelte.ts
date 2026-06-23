/**
 * `$lib/drive/store` — the reactive Google Drive auth state for the backup
 * feature. One process-wide singleton ({@link getDriveAuthStore}).
 *
 * Uses the GIS **token model** (see `$lib/drive/oauth`): no client secret, no
 * refresh token, no redirect. {@link DriveAuthStore.connect} opens the consent
 * popup; {@link DriveAuthStore.accessToken} hands out a valid token, silently
 * re-granting one (no consent screen after the first authorization) when the
 * held one is missing or near expiry.
 *
 * The token is short-lived and session-scoped — kept in `sessionStorage` (GIS's
 * recommendation, NOT `localStorage`), so it's gone on tab close and re-acquired
 * silently on the next backup. It is EXCLUDED from the local backup bundle.
 */

import { isConfigured, requestDriveToken, revokeDriveToken, type DriveToken } from './oauth';

const TOKEN_KEY = 'crema.drive.token.v1';
/** Re-grant this far before the hard expiry so an in-flight upload won't 401. */
const EXPIRY_SKEW_MS = 60_000;

function readToken(): DriveToken | null {
	if (typeof sessionStorage === 'undefined') return null;
	try {
		const v = sessionStorage.getItem(TOKEN_KEY);
		return v ? (JSON.parse(v) as DriveToken) : null;
	} catch {
		return null;
	}
}

function writeToken(token: DriveToken | null): void {
	if (typeof sessionStorage === 'undefined') return;
	if (token) sessionStorage.setItem(TOKEN_KEY, JSON.stringify(token));
	else sessionStorage.removeItem(TOKEN_KEY);
}

export class DriveAuthStore {
	private token = $state.raw<DriveToken | null>(readToken());

	/** Whether this build has a Drive client_id configured. */
	get configured(): boolean {
		return isConfigured();
	}

	/** Whether a still-valid Drive token is held this session. */
	get connected(): boolean {
		return this.token !== null && Date.now() < this.token.expiresAt;
	}

	/** Open the GIS consent popup and store the resulting access token. Must be
	 *  called from a user gesture (button click). */
	async connect(): Promise<void> {
		const token = await requestDriveToken('consent');
		this.token = token;
		writeToken(token);
	}

	/** Forget the token locally + revoke the grant (best-effort). */
	signOut(): void {
		const t = this.token;
		this.token = null;
		writeToken(null);
		if (t) void revokeDriveToken(t.accessToken);
	}

	/**
	 * A valid access token for a Drive REST call. Re-grants silently (no consent
	 * screen after the first authorization) when the held token is missing or
	 * within the expiry skew. Must be reached from a user gesture so the (rare)
	 * fallback popup isn't blocked; throws if the grant needs interaction the
	 * user declines, so the caller can prompt a reconnect.
	 */
	async accessToken(): Promise<string> {
		const t = this.token;
		if (t && Date.now() < t.expiresAt - EXPIRY_SKEW_MS) return t.accessToken;
		const fresh = await requestDriveToken('');
		this.token = fresh;
		writeToken(fresh);
		return fresh.accessToken;
	}
}

let store: DriveAuthStore | undefined;

/** Get the shared {@link DriveAuthStore}, creating it on first call. */
export function getDriveAuthStore(): DriveAuthStore {
	if (!store) store = new DriveAuthStore();
	return store;
}
