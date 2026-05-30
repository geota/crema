/**
 * `$lib/visualizer/token-store` — persistent home for the Visualizer
 * OAuth `TokenSet`, plus the `withFreshToken` helper that the bean-sync
 * layer uses to make authenticated requests.
 *
 * Storage: `localStorage`. Per the coordinator's standalone decision
 * (2026-05-24), the threat model for coffee data is low-value enough
 * that a Service Worker token broker is overkill for v1. Short-lived
 * access tokens + refresh-token rotation keep the blast radius of an
 * XSS to "hours, not weeks", and Crema ships a tight CSP that keeps the
 * XSS surface small.
 *
 * Reactivity: the store does **not** expose Svelte runes — only plain
 * read/write/clear methods. Components that need a "Are we connected?"
 * boolean should track it locally (or subscribe via {@link onTokenChange}).
 * Keeping the persisted-data layer rune-free means it stays usable from
 * non-Svelte modules like `visualizer-sync.ts`.
 */

import { Cause, Effect, Exit } from 'effect';
import { readJson, writeJson } from '$lib/utils/storage';
import { refreshAccessToken, type TokenSet } from './oauth';

/** localStorage key for the persisted token set. */
const TOKEN_KEY = 'crema.visualizer.tokens.v1';
/** Refresh proactively when the access token has < 5 minutes left. */
const REFRESH_BUFFER_MS = 5 * 60 * 1000;

// ── Persistence ────────────────────────────────────────────────────────

/** Read the persisted token set, or `null` if unset / unparseable. */
export function getStoredTokens(): TokenSet | null {
	const raw = readJson<TokenSet | null>(TOKEN_KEY, null);
	if (!raw || typeof raw !== 'object' || typeof raw.accessToken !== 'string') {
		return null;
	}
	return raw;
}

/** Write a fresh token set. Notifies any {@link onTokenChange} subscribers. */
export function storeTokens(set: TokenSet): void {
	writeJson(TOKEN_KEY, set);
	notify(set);
}

/** Clear the persisted tokens (logout / revoke). */
export function clearTokens(): void {
	if (typeof localStorage !== 'undefined') {
		localStorage.removeItem(TOKEN_KEY);
	}
	notify(null);
}

/** `true` when a usable access token is present (not necessarily fresh). */
export function isConnected(): boolean {
	return getStoredTokens() !== null;
}

// ── Subscription (for the Settings UI) ─────────────────────────────────

type Listener = (set: TokenSet | null) => void;
const listeners = new Set<Listener>();

/** Subscribe to token changes. Returns the unsubscribe handle. */
export function onTokenChange(fn: Listener): () => void {
	listeners.add(fn);
	return () => {
		listeners.delete(fn);
	};
}

function notify(set: TokenSet | null): void {
	for (const fn of listeners) fn(set);
}

// ── Fetch wrapper ──────────────────────────────────────────────────────

/** Thrown when the token-store cannot produce a working access token. */
export class NotAuthenticatedError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'NotAuthenticatedError';
	}
}

async function refreshIfNeeded(set: TokenSet): Promise<TokenSet> {
	if (set.expiresAt - Date.now() > REFRESH_BUFFER_MS) return set;
	if (!set.refreshToken) {
		// No refresh capability — surface a re-auth requirement.
		clearTokens();
		throw new NotAuthenticatedError(
			'Visualizer access token expired and there is no refresh token. Please sign in again.'
		);
	}
	try {
		const fresh = await refreshAccessToken(set.refreshToken);
		// Doorkeeper rotates refresh tokens; if the server omits one we
		// keep the old one (some configs don't rotate).
		const merged: TokenSet = {
			...fresh,
			refreshToken: fresh.refreshToken ?? set.refreshToken
		};
		storeTokens(merged);
		return merged;
	} catch (e) {
		// Refresh failed — clear state and force a re-sign-in.
		clearTokens();
		throw new NotAuthenticatedError(
			`Failed to refresh Visualizer access token: ${e instanceof Error ? e.message : String(e)}`
		);
	}
}

/**
 * Get a known-fresh access token, refreshing first if it's within the
 * 5-minute buffer of expiry. Throws {@link NotAuthenticatedError} when
 * there is no token at all, or when refresh fails — callers should
 * surface that as "please sign in to Visualizer".
 */
export async function getFreshAccessToken(): Promise<string> {
	const set = getStoredTokens();
	if (!set) {
		throw new NotAuthenticatedError('Not signed in to Visualizer.');
	}
	const fresh = await refreshIfNeeded(set);
	return fresh.accessToken;
}

/**
 * Run `fn` with a known-fresh access token. On a 401 (the server
 * disagrees with us about freshness — perhaps the token was revoked
 * server-side, or the clock drifted), we force a refresh and retry once.
 * A second 401 clears the tokens and re-throws.
 *
 * --- Phase 0 Effect-TS spike (docs/53, T-00) ----------------------------
 * The internals below are expressed as an `Effect` program purely to
 * measure the bundle/ergonomics cost of adopting Effect (see §2.1). The
 * public contract is unchanged: this still returns a `Promise<T>` and,
 * crucially, still *rejects with the original error instance* — the
 * `runPromiseExit` + `Cause.squash` boundary flattens Effect's `Cause`
 * back to the thrown value so callers' `instanceof NotAuthenticatedError`
 * checks (shot-sync.ts, bean/visualizer-sync.ts) keep working. The
 * token-acquisition path delegates to the unchanged `getFreshAccessToken`
 * so behavior is identical to the prior imperative version.
 */
export async function withFreshToken<T>(
	fn: (accessToken: string) => Promise<T>
): Promise<T> {
	const program = Effect.gen(function* () {
		const token = yield* Effect.tryPromise({
			try: () => getFreshAccessToken(),
			// Preserve the original error instance (NotAuthenticatedError, …).
			catch: (e) => e
		});
		return yield* Effect.tryPromise({ try: () => fn(token), catch: (e) => e }).pipe(
			Effect.catchAll((e) =>
				is401(e) ? retryAfterForcedRefresh(fn) : Effect.fail(e)
			)
		);
	});
	return runPreservingErrors(program);
}

/**
 * The 401 recovery path: force a refresh and retry `fn` exactly once.
 * Mirrors the prior imperative branch (clear-on-no-refresh-token,
 * clear-on-refresh-failure, replay `fn` with the new token).
 */
function retryAfterForcedRefresh<T>(
	fn: (accessToken: string) => Promise<T>
): Effect.Effect<T, unknown> {
	return Effect.gen(function* () {
		const set = getStoredTokens();
		if (!set?.refreshToken) {
			clearTokens();
			return yield* Effect.fail(
				new NotAuthenticatedError(
					'Visualizer rejected the access token and there is no refresh token. Please sign in again.'
				)
			);
		}
		const refreshToken = set.refreshToken;
		const refreshed = yield* Effect.tryPromise({
			try: () => refreshAccessToken(refreshToken),
			catch: (err) => {
				clearTokens();
				return new NotAuthenticatedError(
					`Failed to refresh Visualizer access token: ${err instanceof Error ? err.message : String(err)}`
				);
			}
		});
		const merged: TokenSet = {
			...refreshed,
			refreshToken: refreshed.refreshToken ?? refreshToken
		};
		storeTokens(merged);
		return yield* Effect.tryPromise({ try: () => fn(merged.accessToken), catch: (e) => e });
	});
}

/**
 * Run an Effect to a `Promise`, but reject with the *original* error value
 * rather than Effect's `FiberFailure` wrapper. `Cause.squash` extracts the
 * underlying failure (or defect) instance, so `instanceof` checks at call
 * sites behave exactly as they did before the Effect refactor.
 */
async function runPreservingErrors<T>(program: Effect.Effect<T, unknown>): Promise<T> {
	const exit = await Effect.runPromiseExit(program);
	if (Exit.isSuccess(exit)) return exit.value;
	throw Cause.squash(exit.cause);
}

/**
 * Recognise a 401-tagged error. Callers throw either a
 * `VisualizerError` (the bean-sync layer's tag) or any error whose
 * `status` matches; we peek at both shapes.
 */
function is401(e: unknown): boolean {
	if (!e || typeof e !== 'object') return false;
	const v = e as { status?: unknown; kind?: unknown };
	return v.status === 401 || v.kind === 'auth';
}
