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
import { ResponseDecodeError, VisualizerNotFoundError } from '../effect/errors.ts';
import {
	API_BASE,
	describeVisualizerError,
	visualizerCall,
	type VisualizerCallError,
	type VisualizerCallOptions
} from './visualizer-call.ts';
import {
	BeanUploadResultSchema,
	RoasterUploadResultSchema,
	VisualizerAccountSchema,
	decodeResponse
} from '../effect/schema/visualizer.ts';
import type { Bean, Roaster } from '$lib/bean';
import type { BeanLibraryStore } from '$lib/bean/store.svelte';
import {
	bagBodyToWriteRequest,
	beanFromWire,
	beanToWire,
	readSyncSettings,
	roasterBodyToWriteRequest,
	roasterFromWire,
	roasterToWire,
	writeSyncSettings,
	type SyncLogEntry,
	type SyncResult
} from '$lib/bean/visualizer-sync';
import { signatureForBean, signatureForRoaster } from '$lib/visualizer/shot-sync-signatures';
import { updateSyncConfig } from '$lib/visualizer/sync-config';
import type { components } from '$lib/visualizer/openapi';

/** Crema-side projection of the Visualizer `/me` response (camel-cased). */
export interface VisualizerAccount {
	id: string;
	name: string;
	public: boolean;
	avatarUrl: string;
}

/** Outcome of {@link BeanSync.testConnection} — surfaced inline in Settings. */
export type ConnectionTestResult =
	| { readonly ok: true; readonly premium: boolean | null }
	| { readonly ok: false; readonly error: string };

type RoasterListResponse = components['schemas']['RoasterListResponse'];
type CoffeeBagListResponse = components['schemas']['CoffeeBagListResponse'];
/** The raw wire shapes `beanFromWire` / `roasterFromWire` accept (local to
 *  `visualizer-sync`, so we borrow them via the converters' parameter types). */
type BagWire = Parameters<typeof beanFromWire>[0];
type RoasterWire = Parameters<typeof roasterFromWire>[0];

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
		/**
		 * The full bidirectional bean/roaster sync (replaces
		 * `bean/visualizer-sync.ts` `runSync`): pull every remote roaster + bag and
		 * reconcile (remote-wins LWW, bind by visualizer-id → signature → name),
		 * then push every local create/update. Premium-gated writes downshift the
		 * run to read-only on the first 402/403. Mutates `library` in place and
		 * returns the aggregate {@link SyncResult}. Never fails (errors land in the
		 * result's `log` / `error`), so the boundary stays a plain `Promise`.
		 */
		readonly runSync: (library: BeanLibraryStore) => Effect.Effect<SyncResult>;
		/** Fetch the signed-in user's `/me` profile (replaces `visualizer/account.ts`). */
		readonly fetchAccount: Effect.Effect<VisualizerAccount, VisualizerCallError | ResponseDecodeError>;
		/**
		 * Verify the connection + probe the premium tier (replaces
		 * `visualizer-sync.ts` `testConnection`): a read to catch auth errors, then
		 * a sentinel `POST /roasters` whose status is the one authoritative premium
		 * signal. Caches the result into both sync stores. Never fails.
		 */
		readonly testConnection: Effect.Effect<ConnectionTestResult>;
	}
>() {}

export const BeanSyncLive = Layer.effect(
	BeanSync,
	Effect.gen(function* () {
		const http = yield* HttpClient;
		const vault = yield* TokenVault;

		/**
		 * `BeanSync`'s authenticated entry point: the shared {@link visualizerCall}
		 * with this layer's captured `http` / `vault` provided, so every method
		 * built on it stays `R = never`.
		 */
		const call = (
			path: string,
			opts: VisualizerCallOptions = {}
		): Effect.Effect<unknown, VisualizerCallError> =>
			visualizerCall(path, opts).pipe(
				Effect.provideService(HttpClient, http),
				Effect.provideService(TokenVault, vault)
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

		// ── Pull helpers (paginated; `items=100` is the spec max) ──────────────
		// A 50-page safety cap mirrors the old `runSync`. The list endpoints carry
		// summaries Crema treats as thin wire bodies — the same approach the old
		// module used (a full per-row GET round-trip is a documented follow-up).
		const pullPaged = <T>(base: string) =>
			Effect.gen(function* () {
				const out: T[] = [];
				let page = 1;
				while (page <= 50) {
					const body = (yield* call(`${base}?items=100&page=${page}`)) as {
						data?: T[];
						paging?: { pages?: number };
					} | null;
					const data = body?.data ?? [];
					out.push(...data);
					const totalPages = body?.paging?.pages ?? page;
					if (page >= totalPages || data.length === 0) break;
					page += 1;
				}
				return out;
			});

		const runSync = Effect.fn('BeanSync.runSync')(function* (library: BeanLibraryStore) {
			const settings = readSyncSettings();
			const log: SyncLogEntry[] = [];
			const result: SyncResult = {
				ok: false,
				pulled: 0,
				pushed: 0,
				deleted: 0,
				skipped: 0,
				premiumLocked: settings.premium === false,
				log
			};

			// Bail (gracefully) if not signed in — mirrors the old `isConnected()`.
			const tokens = yield* vault.getTokens;
			if (tokens === null) {
				result.error = 'Sign in to Visualizer first.';
				return result;
			}

			const program = Effect.gen(function* () {
				// 1) Pull remote roasters → reconcile (id → signature → name).
				const remoteRoasters = (yield* pullPaged<RoasterListResponse['data'][number]>(
					'/roasters'
				)) as RoasterWire[];
				const remoteRoasterIdToLocal = new Map<string, string>();
				for (const wire of remoteRoasters) {
					if (!wire.id) continue;
					const wireSig = signatureForRoaster({ name: wire.name });
					const localById = library.roasters.find((r) => r.visualizerId === wire.id);
					const localBySig =
						localById ??
						library.roasters.find(
							(r) =>
								r.visualizerId === null &&
								r.deletedAt == null &&
								signatureForRoaster({ name: r.name }) === wireSig
						);
					const localByName = localBySig ?? library.findRoasterByName(wire.name);
					if (localById) {
						library.updateRoaster(localById.id, {
							name: wire.name,
							website: wire.website ?? null,
							imageUrl: wire.image_url ?? null,
							canonicalRoasterId: wire.canonical_roaster_id ?? null,
							visualizerId: wire.id
						});
						remoteRoasterIdToLocal.set(wire.id, localById.id);
					} else if (localByName) {
						library.updateRoaster(localByName.id, { visualizerId: wire.id });
						remoteRoasterIdToLocal.set(wire.id, localByName.id);
						log.push({ direction: 'pull', kind: 'roaster', id: localByName.id, name: wire.name, at: Date.now() });
					} else {
						const fresh = roasterFromWire(wire);
						library.upsertRoaster(fresh);
						remoteRoasterIdToLocal.set(wire.id, fresh.id);
						result.pulled += 1;
						log.push({ direction: 'pull', kind: 'roaster', id: fresh.id, name: wire.name, at: Date.now() });
					}
				}

				// 2) Push local roasters with no visualizer id (premium-gated).
				let premiumLocked = settings.premium === false;
				let premiumBannerLogged = settings.premium === false;
				const logPremiumBannerOnce = () => {
					if (premiumBannerLogged) return;
					premiumBannerLogged = true;
					log.push({
						direction: 'skip',
						kind: 'bean',
						id: '',
						name: 'Premium required',
						at: Date.now(),
						error:
							'Premium required — beans + roasters disabled from push. Upgrade at visualizer.coffee/premium.'
					});
				};
				for (const local of library.roasters) {
					if (local.visualizerId) continue;
					if (premiumLocked) {
						result.skipped += 1;
						log.push({ direction: 'skip', kind: 'roaster', id: local.id, name: local.name, at: Date.now(), error: 'premium required' });
						continue;
					}
					// EF2: route the push through the `uploadRoaster` service method
					// rather than re-implementing the POST wire inline. `local` has no
					// `visualizerId` here (filtered above), so `uploadRoaster` POSTs the
					// same `roasterBodyToWriteRequest(roasterToWire(local))` body and
					// returns the bound id.
					const res = yield* Effect.either(uploadRoaster(local));
					if (res._tag === 'Right') {
						library.updateRoaster(local.id, { visualizerId: res.right.visualizerId });
						remoteRoasterIdToLocal.set(res.right.visualizerId, local.id);
						result.pushed += 1;
						writeSyncSettings({ premium: true });
						log.push({ direction: 'push', kind: 'roaster', id: local.id, name: local.name, at: Date.now() });
					} else if (res.left._tag === 'VisualizerPremiumGatedError') {
						premiumLocked = true;
						writeSyncSettings({ premium: false });
						logPremiumBannerOnce();
						log.push({ direction: 'skip', kind: 'roaster', id: local.id, name: local.name, at: Date.now(), error: 'premium required' });
					} else {
						result.error = describeVisualizerError(res.left);
						log.push({ direction: 'skip', kind: 'roaster', id: local.id, name: local.name, at: Date.now(), error: result.error });
					}
				}

				// 3) Pull remote bags → reconcile (id/crema_id → signature).
				const remoteBags = (yield* pullPaged<CoffeeBagListResponse['data'][number]>(
					'/coffee_bags'
				)) as BagWire[];
				const localBeans = library.beans;
				for (const wire of remoteBags) {
					const decoded = beanFromWire(wire, (rid) =>
						rid ? (remoteRoasterIdToLocal.get(rid) ?? null) : null
					);
					const decodedSig = signatureForBean({
						name: decoded.name,
						roasterName: (decoded.roasterId && library.getRoaster(decoded.roasterId)?.name) ?? null,
						roastedOn: decoded.roastedOn
					});
					const existing =
						localBeans.find(
							(b) =>
								(decoded.visualizerId && b.visualizerId === decoded.visualizerId) ||
								b.id === decoded.id
						) ??
						localBeans.find(
							(b) =>
								b.visualizerId === null &&
								b.deletedAt == null &&
								signatureForBean({
									name: b.name,
									roasterName: (b.roasterId && library.getRoaster(b.roasterId)?.name) ?? null,
									roastedOn: b.roastedOn
								}) === decodedSig
						);
					if (existing) {
						library.replaceBean({ ...decoded, id: existing.id });
						log.push({ direction: 'pull', kind: 'bean', id: existing.id, name: decoded.name, at: Date.now() });
					} else {
						library.upsertBean(decoded);
						result.pulled += 1;
						log.push({ direction: 'pull', kind: 'bean', id: decoded.id, name: decoded.name, at: Date.now() });
					}
				}

				// 4) Push local bags: insert (no visualizerId) + update (updatedAt > lastSync).
				const lastSync = settings.lastSyncAt ?? 0;
				for (const local of library.beans) {
					if (premiumLocked) {
						if (!local.visualizerId) {
							result.skipped += 1;
							log.push({ direction: 'skip', kind: 'bean', id: local.id, name: local.name, at: Date.now(), error: 'premium required' });
						}
						continue;
					}
					const remoteRoasterId = local.roasterId
						? (library.getRoaster(local.roasterId)?.visualizerId ?? null)
						: null;
					// EF2: both legs route through `uploadBean` rather than re-implementing
					// the POST/PATCH wire. `uploadBean` builds the identical
					// `bagBodyToWriteRequest(beanToWire(local, remoteRoasterId))` body and
					// PATCHes (id present) or POSTs (id absent) by inspecting the bean.
					if (!local.visualizerId) {
						const res = yield* Effect.either(uploadBean(local, remoteRoasterId));
						if (res._tag === 'Right') {
							library.updateBean(local.id, { visualizerId: res.right.visualizerId });
							result.pushed += 1;
							log.push({ direction: 'push', kind: 'bean', id: local.id, name: local.name, at: Date.now() });
						} else if (res.left._tag === 'VisualizerPremiumGatedError') {
							premiumLocked = true;
							writeSyncSettings({ premium: false });
							logPremiumBannerOnce();
							log.push({ direction: 'skip', kind: 'bean', id: local.id, name: local.name, at: Date.now(), error: 'premium required' });
						} else {
							log.push({ direction: 'skip', kind: 'bean', id: local.id, name: local.name, at: Date.now(), error: describeVisualizerError(res.left) });
						}
					} else if (local.updatedAt > lastSync) {
						const res = yield* Effect.either(uploadBean(local, remoteRoasterId));
						if (res._tag === 'Right') {
							result.pushed += 1;
							log.push({ direction: 'push', kind: 'bean', id: local.id, name: local.name, at: Date.now() });
						} else {
							if (res.left._tag === 'VisualizerPremiumGatedError') {
								premiumLocked = true;
								writeSyncSettings({ premium: false });
								logPremiumBannerOnce();
							}
							log.push({ direction: 'skip', kind: 'bean', id: local.id, name: local.name, at: Date.now(), error: describeVisualizerError(res.left) });
						}
					}
				}

				// 5) Mark complete.
				writeSyncSettings({ lastSyncAt: Date.now(), premium: premiumLocked ? false : (settings.premium ?? true) });
				result.ok = true;
				result.premiumLocked = premiumLocked;
			});

			// The pull legs surface unrecoverable failures (auth/network) as the
			// run's `error`, matching the old top-level try/catch.
			yield* program.pipe(
				Effect.catchAll((e) =>
					Effect.sync(() => {
						result.error = describeVisualizerError(e);
					})
				)
			);
			return result;
		});

		const fetchAccount = Effect.gen(function* () {
			const raw = yield* call('/me');
			const body = decodeResponse(VisualizerAccountSchema, raw, 'GET /me');
			if (!body) {
				return yield* new ResponseDecodeError({
					url: `${API_BASE}/me`,
					cause: 'Visualizer /me returned an unexpected shape.'
				});
			}
			return { id: body.id, name: body.name, public: body.public, avatarUrl: body.avatar_url };
		});

		/**
		 * Premium probe: `POST /roasters` with a sentinel name (one of Visualizer's
		 * premium-gated endpoints), then delete it. 201→premium, 402/403→free,
		 * anything else→unknown (null). A failed cleanup is non-fatal — the next
		 * full sync's reconcile tidies it.
		 */
		const probePremium = Effect.gen(function* () {
			const sentinelName = `__crema_premium_probe_${Date.now()}`;
			const created = yield* Effect.either(
				call('/roasters', {
					method: 'POST',
					body: { roaster: { name: sentinelName, website: null, canonical_roaster_id: null } }
				})
			);
			if (created._tag === 'Left') {
				return created.left._tag === 'VisualizerPremiumGatedError' ? false : null;
			}
			const wire = created.right as { id?: string } | null;
			if (wire?.id) {
				yield* call(`/roasters/${wire.id}`, { method: 'DELETE' }).pipe(
					Effect.catchAll(() =>
						Effect.sync(() =>
							console.warn(
								`[visualizer] premium-probe sentinel ${sentinelName} not cleaned up; will be removed on next full Sync`
							)
						)
					)
				);
			}
			return true;
		});

		const testConnection: Effect.Effect<ConnectionTestResult> = Effect.gen(function* () {
			const tokens = yield* vault.getTokens;
			if (tokens === null) return { ok: false, error: 'Sign in to Visualizer first.' };
			const check = yield* Effect.either(call('/coffee_bags?items=1'));
			if (check._tag === 'Left') return { ok: false, error: describeVisualizerError(check.left) };
			const premium = yield* probePremium;
			// Mirror into both caches so every UI surface agrees (matches the old impl).
			writeSyncSettings({ premium });
			updateSyncConfig({ premium });
			return { ok: true, premium };
		});

		return BeanSync.of({
			uploadBean,
			uploadRoaster,
			deleteBean,
			deleteRoaster,
			runSync,
			fetchAccount,
			testConnection
		});
	})
);
