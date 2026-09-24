/**
 * `$lib/decent/api` — the Decent Espresso support API, as the de1app
 * `shot_upload` plugin and decaid's `DecentAccountService` speak it.
 *
 * Three calls, all HTTP Basic:
 *
 *   GET  /support/api/login_test          Basic email:PASSWORD → token text
 *   GET  /support/api/sn?onlyespressomachines=1
 *                                         Basic email:token    → "serial [sku]" lines
 *   POST /support/api/shot_upload[?replace=1]
 *                                         Basic email:token, JSON ShotRecord → {id,…}
 *
 * `login_test` answers `0` for a bad login and a token string (the
 * one-way "encrypted password" de1app stores) for a good one; every later
 * call authenticates with that token in the password slot. Reading the
 * replies (status + body → token / machines / id / auth / reject / retry)
 * is core's job (`de1_domain::decent_wire`, shared with Android); this
 * module only does the HTTP and maps the classified reply onto errors. The server
 * verifies the account and that the shot's machine serial is registered
 * to it before storing the shot. The API sends `access-control-allow-origin:
 * *`, so the browser calls it directly — no proxy.
 */

import { Data } from 'effect';
import {
	decentLoginTokenJson,
	decentMachinesJson,
	decentShotViewUrl as coreDecentShotViewUrl,
	decentUploadReplyJson
} from '$lib/wasm/de1_wasm';
import type {
	DecentLoginReply,
	DecentMachine,
	DecentMachinesReply,
	DecentUploadReply
} from '$lib/core/crema-core';

export type { DecentMachine } from '$lib/core/crema-core';

export const DECENT_BASE = 'https://decentespresso.com';
/** The account's shot-history page — where an uploaded shot with no public URL is found. */
export const DECENT_HISTORY_URL = `${DECENT_BASE}/support/espressomachine`;

/**
 * The uploaded shot's public page (`/shot/<serial>/<id>`) — the link Decent's
 * own "copy link" button hands out, viewable without an account, so it
 * doubles as the share link. `null` unless both the serial and a real server
 * id are known (a legacy `uploaded:` placeholder is not one). No account-page
 * fallback: a caller that wants one uses {@link DECENT_HISTORY_URL}.
 */
export function decentShotViewUrl(
	serial: string | null | undefined,
	decentId: string | null | undefined
): string | null {
	return coreDecentShotViewUrl(serial ?? undefined, decentId ?? undefined) ?? null;
}

/** The linked account's credentials (`token` from {@link decentLogin}). */
export interface DecentCredentials {
	email: string;
	token: string;
}

/** The stored token stopped working (HTTP 401) — the user must re-link. */
export class DecentAuthError extends Data.TaggedError('DecentAuthError')<{ readonly status: number }> {
	get message(): string {
		return 'Decent account login no longer works — sign in again.';
	}
}
/** The server refused this shot for good (4xx other than auth / 408 / 429). */
export class DecentRejectedError extends Data.TaggedError('DecentRejectedError')<{
	readonly status: number;
	readonly body: string;
}> {
	get message(): string {
		return `Decent rejected the shot (HTTP ${this.status})${this.body ? `: ${this.body}` : ''}`;
	}
}
/** Transport failure or a 5xx / 408 / 429 — worth retrying later. */
export class DecentNetworkError extends Data.TaggedError('DecentNetworkError')<{
	readonly status: number | null;
	readonly detail: string;
}> {
	get message(): string {
		return this.status === null
			? `Couldn't reach decentespresso.com: ${this.detail}`
			: `decentespresso.com answered HTTP ${this.status}`;
	}
}
export type DecentApiError = DecentAuthError | DecentRejectedError | DecentNetworkError;

export function isDecentRecoverable(e: DecentApiError): boolean {
	return e._tag === 'DecentNetworkError';
}

/** Is `e` one of this module's typed failures? (Anything else is a bug, not an API answer.) */
export function isDecentApiError(e: unknown): e is DecentApiError {
	return e instanceof DecentAuthError || e instanceof DecentRejectedError || e instanceof DecentNetworkError;
}

/** Minimal fetch surface so tests can stub the network. */
export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

function basicAuth(user: string, secret: string): string {
	// btoa needs Latin-1; the email/token are ASCII in practice, but encode
	// UTF-8 defensively so an accented address doesn't throw.
	const bytes = new TextEncoder().encode(`${user}:${secret}`);
	let bin = '';
	for (const b of bytes) bin += String.fromCharCode(b);
	return `Basic ${btoa(bin)}`;
}

async function call(
	fetchFn: FetchLike,
	path: string,
	user: string,
	secret: string,
	init: RequestInit = {}
): Promise<{ status: number; body: string }> {
	try {
		const res = await fetchFn(`${DECENT_BASE}${path}`, {
			...init,
			headers: { ...(init.headers ?? {}), Authorization: basicAuth(user, secret) }
		});
		// Inside the try: a connection dropped mid-body is a transport failure too.
		return { status: res.status, body: await res.text() };
	} catch (e) {
		throw new DecentNetworkError({ status: null, detail: e instanceof Error ? e.message : String(e) });
	}
}

/** The core classifiers return JSON of the generated reply unions. */
function parseReply<T>(json: string): T {
	return JSON.parse(json) as T;
}

function loginReply(status: number, body: string): DecentLoginReply {
	return parseReply<DecentLoginReply>(decentLoginTokenJson(status, body));
}

/**
 * Exchange email + password for the account token. Resolves `null` when
 * the server rejects the login; throws a {@link DecentNetworkError} on
 * transport failure or a retryable status. The password is used for this
 * one request and never stored.
 */
export async function decentLogin(
	email: string,
	password: string,
	fetchFn: FetchLike = fetch
): Promise<string | null> {
	const { status, body } = await call(fetchFn, '/support/api/login_test', email, password);
	const reply = loginReply(status, body);
	switch (reply.type) {
		case 'Token':
			return reply.content.token;
		case 'Rejected':
			return null;
		case 'Retry':
			throw new DecentNetworkError({ status: reply.content.status, detail: reply.content.detail });
	}
}

/** Does the stored token still work? `false` when the server refuses it. */
export async function decentVerify(creds: DecentCredentials, fetchFn: FetchLike = fetch): Promise<boolean> {
	const { status, body } = await call(fetchFn, '/support/api/login_test', creds.email, creds.token);
	const reply = loginReply(status, body);
	if (reply.type === 'Retry') {
		throw new DecentNetworkError({ status: reply.content.status, detail: reply.content.detail });
	}
	return reply.type === 'Token';
}

/** The DE1s registered on the account (de-duplicated by serial, server order). */
export async function decentFetchMachines(
	creds: DecentCredentials,
	fetchFn: FetchLike = fetch
): Promise<DecentMachine[]> {
	const { status, body } = await call(
		fetchFn,
		'/support/api/sn?onlyespressomachines=1&withskus=1',
		creds.email,
		creds.token
	);
	const reply = parseReply<DecentMachinesReply>(decentMachinesJson(status, body));
	switch (reply.type) {
		case 'Machines':
			return reply.content.machines;
		case 'Auth':
			throw new DecentAuthError({ status });
		case 'Retry':
			throw new DecentNetworkError({ status: reply.content.status, detail: reply.content.detail });
	}
}

/** What the server hands back for a stored shot. */
export interface DecentUploadResult {
	/** The stored shot's id, when the server returned one. */
	id: string | null;
}

/**
 * POST one ShotRecord. `replace` re-uploads over the server's copy (decaid
 * sends `?replace=1` when a shot's annotations changed after upload). A 2xx
 * `0` answer is an auth failure, not an upload (core's classification).
 */
export async function decentUploadShot(
	creds: DecentCredentials,
	recordJson: string,
	opts: { replace?: boolean } = {},
	fetchFn: FetchLike = fetch
): Promise<DecentUploadResult> {
	const path = `/support/api/shot_upload${opts.replace ? '?replace=1' : ''}`;
	const { status, body } = await call(fetchFn, path, creds.email, creds.token, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json; charset=utf-8' },
		body: recordJson
	});
	const reply = parseReply<DecentUploadReply>(decentUploadReplyJson(status, body));
	switch (reply.type) {
		case 'Uploaded':
			return { id: reply.content.id ?? null };
		case 'Auth':
			throw new DecentAuthError({ status });
		case 'Rejected':
			throw new DecentRejectedError({ status: reply.content.status, body: reply.content.body });
		case 'Retry':
			throw new DecentNetworkError({ status: reply.content.status, detail: reply.content.detail });
	}
}
