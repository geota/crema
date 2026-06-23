/**
 * `$lib/drive/store` — the reactive Google Drive auth state + token lifecycle
 * for the backup feature. One process-wide singleton ({@link getDriveAuthStore}).
 *
 * Holds the persisted {@link DriveTokenSet} and hands out a *valid* access
 * token on demand ({@link DriveAuthStore.accessToken}), transparently swapping
 * the refresh token for a fresh access token when the stored one is near
 * expiry. A failed refresh signs out so the UI re-prompts.
 *
 * The token is the only Drive secret on-device; it is EXCLUDED from the local
 * backup bundle (like the Visualizer token) — restore re-auths.
 */

import { readJson, writeJson } from '$lib/utils/storage';
import {
	isConfigured,
	refreshGoogleDriveToken,
	startGoogleDriveLogin,
	type DriveTokenSet
} from './oauth';

const TOKEN_KEY = 'crema.drive.token.v1';
/** Refresh this far before the hard expiry so an in-flight upload won't 401. */
const EXPIRY_SKEW_MS = 60_000;

export class DriveAuthStore {
	private token = $state.raw<DriveTokenSet | null>(
		readJson<DriveTokenSet | null>(TOKEN_KEY, null)
	);

	/** Whether this build has a Drive client_id configured. */
	get configured(): boolean {
		return isConfigured();
	}

	/** Whether a (possibly-expired but refreshable) Drive token is held. */
	get connected(): boolean {
		return this.token !== null;
	}

	/** Adopt + persist a freshly-exchanged token (called from the callback). */
	set(token: DriveTokenSet): void {
		this.token = token;
		writeJson(TOKEN_KEY, token);
	}

	/** Begin the OAuth redirect (navigates away). */
	signIn(returnTo = '/settings'): Promise<void> {
		return startGoogleDriveLogin(returnTo);
	}

	/** Forget the token locally (does not revoke server-side). */
	signOut(): void {
		this.token = null;
		writeJson(TOKEN_KEY, null);
	}

	/**
	 * A valid access token for a Drive REST call — refreshes transparently when
	 * the stored one is within the expiry skew. Signs out + rethrows when the
	 * refresh is rejected, so the caller can prompt a re-sign-in.
	 */
	async accessToken(): Promise<string> {
		const t = this.token;
		if (!t) throw new Error('Not signed in to Google Drive.');
		if (Date.now() < t.expiresAt - EXPIRY_SKEW_MS) return t.accessToken;
		try {
			const refreshed = await refreshGoogleDriveToken(t.refreshToken);
			this.set(refreshed);
			return refreshed.accessToken;
		} catch (err) {
			this.signOut();
			throw err;
		}
	}
}

let store: DriveAuthStore | undefined;

/** Get the shared {@link DriveAuthStore}, creating it on first call. */
export function getDriveAuthStore(): DriveAuthStore {
	if (!store) store = new DriveAuthStore();
	return store;
}
