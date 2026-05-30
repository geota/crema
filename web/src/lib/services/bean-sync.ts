/**
 * `$lib/services/bean-sync` — the `BeanSync` service (docs/53 §1.3, §2.4 PR 3.6,
 * T-13).
 *
 * The Effect-native home for the bean/roaster Visualizer *mutations* currently
 * scattered through `bean/visualizer-sync.ts`'s `runSync` (the inline
 * `POST /coffee_bags`, `PATCH /coffee_bags/{id}`, `POST /roasters`) plus the
 * `deleteRemoteBean` / `deleteRemoteRoaster` helpers. Same shape as `ShotSync`:
 * each method funnels through `TokenVault.withFreshToken(token =>
 * HttpClient.request(...))`, and the premium gate (402/403) maps to
 * `VisualizerPremiumGatedError` rather than today's status-code probe.
 *
 * Behavior mirrors the old module: a bag with a `visualizerId` PATCHes (keeping
 * its id), a fresh bag POSTs and binds the returned id; roasters create-only
 * (the old `runSync` never PATCHed an existing roaster — it skipped it) but the
 * primitive will PATCH when given an id so the future orchestrator can choose.
 * A 404 on delete is treated as success (already gone), like `ShotSync`.
 *
 * NOT wired into production — `bean/visualizer-sync.ts`'s `runSync` stays the
 * live implementation until a later facade swap; `BeanSyncLive` only needs to
 * compose into `AppLayer`. Store-coupled (`$lib/bean`), so not node:test-able.
 */

import { Context, Effect, Layer } from 'effect';
import { HttpClient } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	TokenRefreshFailedError,
	VisualizerNotFoundError,
	VisualizerPremiumGatedError
} from '../effect/errors.ts';
import {
	BeanUploadResultSchema,
	RoasterUploadResultSchema,
	decodeResponse
} from '../effect/schema/visualizer.ts';
import type { Bean, Roaster } from '$lib/bean';
import {
	bagBodyToWriteRequest,
	beanToWire,
	roasterBodyToWriteRequest,
	roasterToWire
} from '$lib/bean/visualizer-sync';

/** Visualizer API base. */
const API_BASE = 'https://visualizer.coffee/api';

/** The closed error union every `call`-backed method can surface. */
type VisualizerCallError =
	| NetworkError
	| HttpStatusError
	| NotAuthenticatedError
	| TokenRefreshFailedError
	| VisualizerPremiumGatedError
	| VisualizerNotFoundError;

interface FetchOptions {
	method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
	/** Decoded JSON body — stringified before it rides the wire. */
	body?: unknown;
}

export class BeanSync extends Context.Tag('crema/BeanSync')<
	BeanSync,
	{
		/**
		 * Create or update a bag on Visualizer. PATCHes when `bean.visualizerId`
		 * is set (keeping the id), otherwise POSTs and returns the new remote id.
		 * `remoteRoasterId` is the bag's roaster's Visualizer id (or null).
		 */
		readonly uploadBean: (
			bean: Bean,
			remoteRoasterId: string | null
		) => Effect.Effect<{ visualizerId: string }, VisualizerCallError | ResponseDecodeError>;
		/** Create (or update, when it carries an id) a roaster on Visualizer. */
		readonly uploadRoaster: (
			roaster: Roaster
		) => Effect.Effect<{ visualizerId: string }, VisualizerCallError | ResponseDecodeError>;
		/** Delete a remote bag by id. A 404 is treated as success (already gone). */
		readonly deleteBean: (
			visualizerId: string
		) => Effect.Effect<void, Exclude<VisualizerCallError, VisualizerNotFoundError>>;
		/** Delete a remote roaster by id. A 404 is treated as success. */
		readonly deleteRoaster: (
			visualizerId: string
		) => Effect.Effect<void, Exclude<VisualizerCallError, VisualizerNotFoundError>>;
	}
>() {}

export const BeanSyncLive = Layer.effect(
	BeanSync,
	Effect.gen(function* () {
		const http = yield* HttpClient;
		const vault = yield* TokenVault;

		/**
		 * Single authenticated HTTP entry point — funnels through
		 * `TokenVault.withFreshToken` (401 → refresh-once) and maps `HttpClient`'s
		 * `HttpStatusError` back onto the Visualizer taxonomy (402/403 → premium,
		 * 404 → not-found). Returns parsed JSON for 2xx + body; `null` for 204.
		 */
		const call = (path: string, opts: FetchOptions = {}): Effect.Effect<unknown, VisualizerCallError> =>
			vault
				.withFreshToken((token) => {
					const headers: Record<string, string> = {
						Authorization: `Bearer ${token}`,
						Accept: 'application/json'
					};
					if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
					return http.request({
						url: `${API_BASE}${path}`,
						method: opts.method ?? 'GET',
						headers,
						body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined
					});
				})
				.pipe(
					Effect.catchTag(
						'HttpStatusError',
						(
							err
						): Effect.Effect<never, VisualizerPremiumGatedError | VisualizerNotFoundError | HttpStatusError> => {
							if (err.status === 402 || err.status === 403) {
								return Effect.fail(new VisualizerPremiumGatedError({ endpoint: path }));
							}
							if (err.status === 404) {
								return Effect.fail(new VisualizerNotFoundError({ visualizerId: path }));
							}
							return Effect.fail(err);
						}
					),
					Effect.flatMap((res) =>
						res.status === 204
							? Effect.succeed(null)
							: // A malformed 2xx body is effectively a transport anomaly; surface
								// it as NetworkError (recoverable), matching the old module's raw
								// res.json() rejection path.
								Effect.tryPromise({
									try: () => res.json(),
									catch: (cause) => new NetworkError({ cause, url: `${API_BASE}${path}` })
								})
					)
				);

		const uploadBean = Effect.fn('BeanSync.uploadBean')(function* (
			bean: Bean,
			remoteRoasterId: string | null
		) {
			const body = bagBodyToWriteRequest(beanToWire(bean, remoteRoasterId));
			if (bean.visualizerId) {
				// Update — keeps the existing remote id (the PATCH body has no id we read).
				yield* call(`/coffee_bags/${bean.visualizerId}`, { method: 'PATCH', body });
				return { visualizerId: bean.visualizerId };
			}
			const raw = yield* call('/coffee_bags', { method: 'POST', body });
			const result = decodeResponse(BeanUploadResultSchema, raw, 'POST /coffee_bags');
			if (!result || !result.id) {
				return yield* new ResponseDecodeError({
					url: `${API_BASE}/coffee_bags`,
					cause: 'Visualizer accepted the bag but returned no id.'
				});
			}
			return { visualizerId: result.id };
		});

		const uploadRoaster = Effect.fn('BeanSync.uploadRoaster')(function* (roaster: Roaster) {
			const body = roasterBodyToWriteRequest(roasterToWire(roaster));
			if (roaster.visualizerId) {
				yield* call(`/roasters/${roaster.visualizerId}`, { method: 'PATCH', body });
				return { visualizerId: roaster.visualizerId };
			}
			const raw = yield* call('/roasters', { method: 'POST', body });
			const result = decodeResponse(RoasterUploadResultSchema, raw, 'POST /roasters');
			if (!result || !result.id) {
				return yield* new ResponseDecodeError({
					url: `${API_BASE}/roasters`,
					cause: 'Visualizer accepted the roaster but returned no id.'
				});
			}
			return { visualizerId: result.id };
		});

		const deleteBean = Effect.fn('BeanSync.deleteBean')(function* (visualizerId: string) {
			yield* call(`/coffee_bags/${visualizerId}`, { method: 'DELETE' }).pipe(
				Effect.catchTag('VisualizerNotFoundError', () => Effect.void)
			);
		});

		const deleteRoaster = Effect.fn('BeanSync.deleteRoaster')(function* (visualizerId: string) {
			yield* call(`/roasters/${visualizerId}`, { method: 'DELETE' }).pipe(
				Effect.catchTag('VisualizerNotFoundError', () => Effect.void)
			);
		});

		return BeanSync.of({ uploadBean, uploadRoaster, deleteBean, deleteRoaster });
	})
);
