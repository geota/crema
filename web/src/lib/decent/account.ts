/**
 * `$lib/decent/account` — the linked Decent Espresso account + the
 * "upload shots to Decent" preferences (geota/crema#84).
 *
 * Decent's shot history (decentespresso.com → My account → shot
 * history + charts) accepts shots uploaded by the owner's apps: de1app's
 * `shot_upload` plugin and decaid's `shot-upload.reaplugin` both POST a
 * decaid-format ShotRecord to `/support/api/shot_upload`. Crema does the
 * same — see `./api` for the wire contract; the payload is built by the
 * core (`decentShotRecordJson`, via `./shot-record`) — so a Crema shot
 * lands in the same account, next to the ones the tablet uploaded.
 *
 * Persistence mirrors the Visualizer sync-config: one localStorage key,
 * plain functions, an in-tab change subscription so the Settings card
 * refreshes when the upload path mutates the record. The stored `token`
 * is the server-issued credential `login_test` returns for a valid
 * email + password — the password itself is never persisted (same
 * discipline as de1app / decaid: the account's password never lives on
 * the device) — and it sits in storage WRAPPED by the secret box
 * (`$lib/security/secret-box`), so read it through
 * {@link getDecentCredentials}, never off the state record. Decent cannot
 * revoke a token per device: signing out only forgets it here, and a lost
 * device means changing the Decent password.
 */

import { readJson, writeJson } from '$lib/utils/storage';
import { isWrapped, secretBox } from '$lib/security/secret-box';
import type { DecentCredentials } from './api';

const ACCOUNT_KEY = 'crema.decent.v1';

/** The outcome of the most recent upload attempt, for the Settings card. */
export interface DecentLastUpload {
	at: number;
	ok: boolean;
	/** Short human-readable note ("uploaded", "rejected (HTTP 422)", …). */
	message: string;
	/** The uploaded shot's web view when the upload succeeded. */
	url?: string;
}

export interface DecentAccountState {
	/** Account email once linked; `null` = not linked. */
	email: string | null;
	/**
	 * Server-issued credential (from `login_test`), wrapped at rest;
	 * `null` = not linked. Opaque here — {@link getDecentCredentials}
	 * unwraps it for the upload path.
	 */
	token: string | null;
	/** DE1 serials registered on the account (`/support/api/sn`); refreshed on link. */
	serials: string[];
	/**
	 * Upload each finished shot as it completes. Linking the account turns
	 * this on (de1app: enabling the plugin is the affirmative choice, and
	 * with no linked account it no-ops); the toggle stays for opting out.
	 */
	autoUpload: boolean;
	/**
	 * The user must sign in again: a request came back 401 / `0`, or the
	 * stored token can no longer be unwrapped (the secret-box key was lost —
	 * IndexedDB cleared while localStorage survived).
	 */
	needsReauth: boolean;
	lastUpload: DecentLastUpload | null;
	/**
	 * Shots Decent refused for good (a 4xx rejection). Left out of the
	 * backlog so a catch-up does not re-send them every time; a manual
	 * upload of the shot clears its entry and tries again.
	 */
	rejectedShotIds: string[];
	/**
	 * Automatic uploads that failed on the network (offline, 5xx, …) after
	 * the in-call retries. Retried when the connection comes back and on
	 * the next launch (`retryPendingDecentUploads`); cleared on success or a
	 * permanent rejection.
	 */
	retryShotIds: string[];
}

export const DEFAULT_DECENT_ACCOUNT: DecentAccountState = {
	email: null,
	token: null,
	serials: [],
	autoUpload: false,
	needsReauth: false,
	lastUpload: null,
	rejectedShotIds: [],
	retryShotIds: []
};

function stringList(v: unknown): string[] {
	return Array.isArray(v) ? v.map(String) : [];
}

let rewrapping = false;
let migration: Promise<void> = Promise.resolve();

/** Read the persisted state; missing fields fall back to the default. */
export function readDecentAccount(): DecentAccountState {
	const raw = readJson<Partial<DecentAccountState> | null>(ACCOUNT_KEY, null);
	if (!raw || typeof raw !== 'object') return DEFAULT_DECENT_ACCOUNT;
	const state: DecentAccountState = {
		...DEFAULT_DECENT_ACCOUNT,
		...raw,
		serials: stringList(raw.serials),
		rejectedShotIds: stringList(raw.rejectedShotIds),
		retryShotIds: stringList(raw.retryShotIds)
	};
	// A token written before wrapping existed: wrap it in place, once.
	if (state.token && !isWrapped(state.token) && !rewrapping) {
		rewrapping = true;
		const plain = state.token;
		migration = secretBox
			.wrap(plain)
			.then((wrapped) => {
				// No key (wrapping unavailable) returns the plaintext — nothing to write.
				if (!isWrapped(wrapped)) return;
				const current = readJson<Partial<DecentAccountState> | null>(ACCOUNT_KEY, null);
				if (current?.token === plain) writeJson(ACCOUNT_KEY, { ...current, token: wrapped });
			})
			.catch((e: unknown) => console.warn('[Crema] Decent token re-wrap failed:', e))
			.finally(() => (rewrapping = false));
	}
	return state;
}

/** Test hook — settles once the in-place plaintext → wrapped migration has run. */
export function decentTokenMigration(): Promise<void> {
	return migration;
}

/** Link the account: wraps the freshly issued token before it is persisted. */
export async function linkDecentAccount(input: {
	email: string;
	token: string;
	serials: string[];
}): Promise<DecentAccountState> {
	return updateDecentAccount({
		email: input.email,
		token: await secretBox.wrap(input.token),
		serials: input.serials,
		autoUpload: true,
		needsReauth: false,
		lastUpload: null,
		rejectedShotIds: [],
		retryShotIds: []
	});
}

/**
 * The linked account's credentials for a request, or `null` when not linked
 * or the wrapped token can no longer be opened. The latter flags the account
 * `needsReauth`, so the Settings card asks the user to sign in again and the
 * automatic push stops trying — instead of every push silently skipping.
 */
export async function getDecentCredentials(
	state: DecentAccountState = readDecentAccount()
): Promise<DecentCredentials | null> {
	if (!state.email || !state.token) return null;
	const token = await secretBox.unwrap(state.token);
	if (token === null) {
		if (!readDecentAccount().needsReauth) {
			updateDecentAccount({
				needsReauth: true,
				lastUpload: {
					at: Date.now(),
					ok: false,
					message: 'The saved Decent login can no longer be read on this device — sign in again.'
				}
			});
		}
		return null;
	}
	return { email: state.email, token };
}

export function writeDecentAccount(state: DecentAccountState): void {
	writeJson(ACCOUNT_KEY, state);
	for (const cb of listeners) cb(state);
}

/** Patch-merge the persisted state. */
export function updateDecentAccount(patch: Partial<DecentAccountState>): DecentAccountState {
	const next = { ...readDecentAccount(), ...patch };
	writeDecentAccount(next);
	return next;
}

/** Is an account linked (email + token present)? */
export function isDecentLinked(state: DecentAccountState = readDecentAccount()): boolean {
	return !!state.email && !!state.token;
}

/** Forget the linked account — the token, serials and last-upload note. */
export function unlinkDecentAccount(): void {
	writeDecentAccount({ ...DEFAULT_DECENT_ACCOUNT });
}

function withId(list: readonly string[], id: string): string[] {
	return list.includes(id) ? [...list] : [...list, id];
}
function withoutId(list: readonly string[], id: string): string[] {
	return list.filter((x) => x !== id);
}

/** Record where a shot's upload landed: `rejected` (permanent), `retry` (network), or neither (`clear`). */
export function setDecentShotState(shotId: string, state: 'rejected' | 'retry' | 'clear'): void {
	const cur = readDecentAccount();
	const rejectedShotIds =
		state === 'rejected' ? withId(cur.rejectedShotIds, shotId) : withoutId(cur.rejectedShotIds, shotId);
	const retryShotIds =
		state === 'retry' ? withId(cur.retryShotIds, shotId) : withoutId(cur.retryShotIds, shotId);
	if (
		rejectedShotIds.length === cur.rejectedShotIds.length &&
		retryShotIds.length === cur.retryShotIds.length
	) {
		return;
	}
	writeDecentAccount({ ...cur, rejectedShotIds, retryShotIds });
}

type Listener = (state: DecentAccountState) => void;
const listeners = new Set<Listener>();

/** In-tab change subscription — every write fans out to subscribers. */
export function onDecentAccountChange(cb: Listener): () => void {
	listeners.add(cb);
	return () => {
		listeners.delete(cb);
	};
}
