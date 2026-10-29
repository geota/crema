/**
 * `#lib/services/token-vault.js` — Visualizer token storage + refresh service
 * (docs/53 §1.3, Appendix A, T-11).
 *
 * The Effect-native home for what `visualizer/token-store.ts` does today:
 * persist the `TokenSet`, hand out known-fresh access tokens, and run an
 * authenticated request with a one-shot refresh-on-401. The in-memory listener
 * set (`onTokenChange`) becomes a `SubscriptionRef` (`changes`) — a single
 * source of truth whose `SubscriptionRef.changes` is a `Stream` consumers can
 * later bridge into a rune.
 *
 * Behavior matches today's `withFreshToken` exactly:
 *  - proactive refresh when the token is within 5 min of expiry,
 *  - one-shot forced refresh + retry on a 401,
 *  - clear-tokens + NotAuthenticatedError when there's no refresh token,
 *  - clear-tokens + TokenRefreshFailedError ONLY when the token endpoint
 *    definitively rejects the refresh token (`invalid_grant`); a network
 *    failure, timeout or 5xx keeps the stored set and fails retryably
 *    (`NetworkError` / `HttpStatusError`) — review 03 P1-1,
 *  - single-flight refresh: concurrent callers share one refresh, so a rotating
 *    refresh token is spent once, and a caller never clears a set another
 *    caller (or tab) stored in the meantime — review 03 P1-2,
 *  - Doorkeeper rotation: keep the old refresh token if the server omits one.
 *
 * The service is wired behind the existing Promise facade in T-16; until then
 * `visualizer/token-store.ts` remains the live implementation.
 */

import { Context, Effect, Layer, Option, Semaphore, SubscriptionRef } from 'effect';
import { readJson, writeJson } from '../utils/storage.ts';
import { decodeOr } from '../effect/schema/decode.ts';
import { TokenSetSchema } from '../effect/schema/tokens.ts';
import {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	TokenRefreshFailedError
} from '../effect/errors.ts';
import { OAuth } from './oauth.ts';
import type { TokenSet } from '../visualizer/oauth.ts';
import { secretBox } from '../security/secret-box.ts';

const TOKENS_KEY = 'crema.visualizer.tokens.v1';
/** Refresh proactively when the access token has < 5 minutes left. */
const EXPIRY_SAFETY_MS = 5 * 60 * 1000;
/** Web Locks name that serialises refreshes across tabs and runtimes. */
const REFRESH_LOCK = 'crema.visualizer.token-refresh';

/** Same session? (Access + refresh token; the rest is derived from them.) */
const sameTokens = (a: TokenSet, b: TokenSet) =>
	a.accessToken === b.accessToken && a.refreshToken === b.refreshToken;

/**
 * Run `eff` holding the cross-tab refresh lock when the Web Locks API exists
 * (every browser the app supports; absent under jsdom). Each tab and each
 * `TokenVault` instance (the OAuth callback builds its own) re-reads storage
 * once it holds the lock, so a refresh token another tab just rotated is not
 * spent again. A lock request that is refused (e.g. an opaque origin) falls
 * back to running unlocked; the in-process semaphore still applies.
 */
function withCrossTabLock<A, E, R>(eff: Effect.Effect<A, E, R>): Effect.Effect<A, E, R> {
	const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
	if (!locks) return eff;
	return Effect.acquireUseRelease(
		Effect.promise(
			() =>
				new Promise<() => void>((granted) => {
					locks
						.request(REFRESH_LOCK, () => new Promise<void>((release) => granted(release)))
						.catch(() => granted(() => {}));
				})
		),
		() => eff,
		(release) => Effect.sync(release)
	);
}

/**
 * Set when the last read found a stored token set it could not unwrap (the
 * secret-box key is gone — IndexedDB cleared while localStorage survived, or
 * momentarily unavailable). Cleared by the next readable load, a store or a
 * clear. In-memory only: every `getTokens` re-reads storage, so it tracks the
 * truth within a read of it.
 */
let storedButUnreadable = false;

/**
 * Is a USABLE Visualizer session stored on this device? A synchronous probe
 * for gates that run before an async read is possible — e.g. deciding which
 * destinations a shot completion will push to. A stored set that the last
 * read could not unwrap does not count: the app reads as disconnected (the
 * Settings card offers Sign in) instead of failing every push on auth.
 */
export function hasStoredVisualizerTokens(): boolean {
	return readJson<unknown>(TOKENS_KEY, null) !== null && !storedButUnreadable;
}

/** Did the last read find stored tokens it could not unwrap? (The user must sign in again.) */
export function visualizerTokensUnreadable(): boolean {
	return storedButUnreadable;
}

/**
 * Read + validate the persisted token set (null when absent / invalid), then
 * unwrap the two secrets. The access + refresh tokens sit in localStorage
 * wrapped by the secret box (AES-GCM under a non-extractable IndexedDB key)
 * so a storage dump does not hand out a live session; a set written before
 * wrapping existed still reads (plaintext passes through) and is re-wrapped
 * on the next `storeTokens`. A wrapped set whose key is gone reads as null —
 * disconnected, so the user is asked to sign in again — and flips
 * {@link hasStoredVisualizerTokens} off. Exported for tests.
 */
export async function loadTokens(): Promise<TokenSet | null> {
	const raw = readJson<unknown>(TOKENS_KEY, null);
	const stored = raw === null ? null : decodeOr(TokenSetSchema, raw, null, TOKENS_KEY);
	if (stored === null) {
		storedButUnreadable = false;
		return null;
	}
	const accessToken = await secretBox.unwrap(stored.accessToken);
	const refreshToken = stored.refreshToken === null ? null : await secretBox.unwrap(stored.refreshToken);
	if (accessToken === null || (stored.refreshToken !== null && refreshToken === null)) {
		storedButUnreadable = true;
		return null;
	}
	storedButUnreadable = false;
	return { ...stored, accessToken, refreshToken };
}

async function wrapTokens(t: TokenSet): Promise<TokenSet> {
	return {
		...t,
		accessToken: await secretBox.wrap(t.accessToken),
		refreshToken: t.refreshToken === null ? null : await secretBox.wrap(t.refreshToken)
	};
}

export class TokenVault extends Context.Service<
	TokenVault,
	{
		readonly getTokens: Effect.Effect<TokenSet | null>;
		readonly storeTokens: (t: TokenSet) => Effect.Effect<void>;
		readonly clearTokens: Effect.Effect<void>;
		/**
		 * Run `req` with a known-fresh access token, refreshing first if expiring
		 * and once more on a 401. A non-401 `HttpStatusError` from `req`
		 * propagates unchanged.
		 */
		readonly withFreshToken: <A, E>(
			req: (token: string) => Effect.Effect<A, E | HttpStatusError>
		) => Effect.Effect<
			A,
			E | HttpStatusError | NetworkError | NotAuthenticatedError | TokenRefreshFailedError
		>;
		/** Live token state; `SubscriptionRef.changes` is a Stream for reactive consumers. */
		readonly changes: SubscriptionRef.SubscriptionRef<TokenSet | null>;
	}
>()('crema/TokenVault') {}

export const TokenVaultLive = Layer.effect(
	TokenVault,
	Effect.gen(function* () {
		const oauth = yield* OAuth;
		const ref = yield* SubscriptionRef.make<TokenSet | null>(
			typeof localStorage !== 'undefined' ? yield* Effect.promise(loadTokens) : null
		);

		/**
		 * The persisted token set is the source of truth. During the T-16 phase
		 * the old `visualizer/token-store.ts` still owns sign-in / sign-out and the
		 * bean-pull auth (option A), sharing this exact localStorage key — so a
		 * sign-in routed through it must be visible here immediately, with no stale
		 * in-memory cache. We therefore read localStorage fresh on every
		 * `getTokens` (matching token-store's own cache-free reads) and only fall
		 * back to the `SubscriptionRef` when localStorage is unavailable (node:test,
		 * where the tests seed state via `storeTokens`). The ref stays the reactive
		 * mirror that `SubscriptionRef.changes` exposes (writes go through
		 * `storeTokens` / `clearTokens`); once token-store is deleted next phase the
		 * ref can become the sole source again.
		 */
		const getTokens: Effect.Effect<TokenSet | null> = Effect.suspend(() => {
			if (typeof localStorage === 'undefined') return SubscriptionRef.get(ref);
			const rawBefore = localStorage.getItem(TOKENS_KEY);
			return Effect.promise(loadTokens).pipe(Effect.tap((loaded) => syncRef(rawBefore, loaded)));
		});

		/**
		 * Bring the reactive mirror in line with what `getTokens` just read
		 * (review 03 P3-4). Storage can change behind this vault: the OAuth
		 * callback stores the new session through its own runtime, and another
		 * tab can refresh or sign out. Without this, `changes` keeps emitting the
		 * stale state and the shell's connection gate shows "disconnected" after
		 * a sign-in. Skipped when the stored item changed while it was being
		 * unwrapped: a `storeTokens` / `clearTokens` in between already set the
		 * ref to something newer than `loaded`. The check runs inside the ref's
		 * own update, so it is atomic with those writes' ref updates.
		 */
		const syncRef = (rawBefore: string | null, loaded: TokenSet | null) =>
			SubscriptionRef.updateSome(ref, (current) => {
				if (localStorage.getItem(TOKENS_KEY) !== rawBefore) return Option.none();
				const same =
					current === null || loaded === null ? current === loaded : sameTokens(current, loaded);
				return same ? Option.none() : Option.some(loaded);
			});

		const storeTokens = (t: TokenSet) =>
			Effect.andThen(
				Effect.promise(async () => {
					writeJson(TOKENS_KEY, await wrapTokens(t));
					storedButUnreadable = false;
				}),
				SubscriptionRef.set(ref, t)
			);

		const clearTokens = Effect.andThen(
			Effect.sync(() => {
				if (typeof localStorage !== 'undefined') localStorage.removeItem(TOKENS_KEY);
				storedButUnreadable = false;
			}),
			SubscriptionRef.set(ref, null)
		);

		const isExpiringSoon = (t: TokenSet) => t.expiresAt - Date.now() <= EXPIRY_SAFETY_MS;

		/** One refresh at a time per vault; {@link withCrossTabLock} extends it across tabs. */
		const refreshLock = yield* Semaphore.make(1);

		/**
		 * Refresh the STORED set and persist the result. Runs under the refresh
		 * lock. Only a definitive grant rejection clears the tokens, and only
		 * when the stored refresh token is still the one that was rejected —
		 * if another tab or a fresh sign-in stored a newer set meanwhile, that
		 * set wins. Transient failures propagate and leave storage untouched.
		 */
		const refreshStored = (
			t: TokenSet
		): Effect.Effect<
			TokenSet,
			NotAuthenticatedError | TokenRefreshFailedError | NetworkError | HttpStatusError
		> => {
			if (!t.refreshToken) {
				return clearTokens.pipe(Effect.andThen(Effect.fail(new NotAuthenticatedError())));
			}
			const rt = t.refreshToken;
			return oauth.refreshToken(rt).pipe(
				// Doorkeeper rotates refresh tokens; keep the old one if omitted.
				Effect.map((fresh): TokenSet => ({ ...fresh, refreshToken: fresh.refreshToken ?? rt })),
				Effect.tap(storeTokens),
				Effect.catchTag('TokenRefreshFailedError', (err) =>
					Effect.gen(function* () {
						const now = yield* getTokens;
						if (now !== null && now.refreshToken !== rt) return now;
						yield* clearTokens;
						return yield* Effect.fail(err);
					})
				)
			);
		};

		/**
		 * Single-flight refresh, starting from the set the caller `observed`.
		 * Once it holds the lock it re-reads storage: if another caller already
		 * replaced that set, it uses the newer one (refreshing only if that one
		 * is itself expiring and the caller wanted a merely-fresh token). So N
		 * concurrent callers holding the same expiring set make one request.
		 * `rejected` marks the 401 path: the server refused `observed`'s access
		 * token, so any different stored set is worth a retry as it stands.
		 */
		const refreshFrom = (observed: TokenSet, rejected: boolean) =>
			refreshLock.withPermits(1)(
				withCrossTabLock(
					Effect.gen(function* () {
						const current = yield* getTokens;
						if (current === null) return yield* Effect.fail(new NotAuthenticatedError());
						if (!sameTokens(current, observed) && (rejected || !isExpiringSoon(current))) {
							return current;
						}
						return yield* refreshStored(current);
					})
				)
			);

		/** A known-fresh token: refresh first if it's within the expiry buffer. */
		const fresh = Effect.gen(function* () {
			const tokens = yield* getTokens;
			if (tokens === null) return yield* Effect.fail(new NotAuthenticatedError());
			return isExpiringSoon(tokens) ? yield* refreshFrom(tokens, false) : tokens;
		});

		const withFreshToken = <A, E>(req: (token: string) => Effect.Effect<A, E | HttpStatusError>) =>
			Effect.gen(function* () {
				const tokens = yield* fresh;
				return yield* req(tokens.accessToken).pipe(
					// `instanceof` narrows the generic `E | HttpStatusError` union
					// (catchTag alone leaves a phantom `{ _tag }` member).
					Effect.catchTag('HttpStatusError', (err) =>
						err instanceof HttpStatusError && err.status === 401
							? refreshFrom(tokens, true).pipe(Effect.flatMap((t) => req(t.accessToken)))
							: Effect.fail(err)
					)
				);
			});

		return TokenVault.of({ getTokens, storeTokens, clearTokens, withFreshToken, changes: ref });
	})
);
