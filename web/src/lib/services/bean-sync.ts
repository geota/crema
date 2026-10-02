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
 * `BeanSyncLive` is the production implementation, composed into `AppLayer`.
 * Store-coupled (`$lib/bean`), so not node:test-able.
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
	beanFromWire,
	coffeeBagWriteRequest,
	readSyncSettings,
	roasterFromWire,
	roasterWriteRequest,
	writeSyncSettings,
	type SyncLogEntry,
	type SyncResult
} from '$lib/bean/visualizer-sync';
import {
	beanSyncScope as wasmBeanSyncScope,
	mergePulledRoaster as wasmMergePulledRoaster,
	planBeanPush as wasmPlanBeanPush,
	planRoasterPush as wasmPlanRoasterPush,
	planRoasterLinkPatches as wasmPlanRoasterLinkPatches,
	reconcileBeans as wasmReconcileBeans,
	reconcileRoasters as wasmReconcileRoasters,
	resolveRoasterCatalogueLink as wasmResolveRoasterCatalogueLink
} from '$lib/wasm/de1_wasm';
import type {
	BeanPushItem,
	BeanSyncScope,
	RoasterLinkPatch,
	RoasterPushItem
} from '$lib/core/crema-core';
import { readSyncConfig, updateSyncConfig } from '$lib/visualizer/sync-config';
import type { components } from '$lib/visualizer/openapi';
import { CATALOGUE_PAGE_SIZE, parseCataloguePage, type CataloguePage } from '$lib/bean/catalogue';

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

// ── Pull-reconcile kernel (CORE4) ────────────────────────────────────────
//
// The id → signature → name matching for the pull legs moved to
// `de1_domain::visualizer_sync::reconcile_{roasters,beans}` (siblings of the
// shot reconcile planner) so every shell matches identically. These thin
// wrappers marshal JSON across the wasm boundary; the shell still owns the
// HTTP, the store mutations, the premium-gating + the push legs. The action
// wire shapes mirror the Rust enums (lowercase `kind`, camelCase `localId`).

type RoasterReconcileAction =
	| { kind: 'update'; localId: string; remote: RoasterWire }
	| { kind: 'bind'; localId: string; remote: RoasterWire }
	| { kind: 'add'; remote: RoasterWire };

type BeanReconcileAction =
	| { kind: 'replace'; localId: string; remote: Bean }
	| { kind: 'add'; remote: Bean };

/** Reconcile a remote roaster pull against the local directory (CORE4). */
function reconcileRoasters(local: Roaster[], remote: RoasterWire[]): RoasterReconcileAction[] {
	return JSON.parse(
		wasmReconcileRoasters(JSON.stringify({ local, remote }))
	) as RoasterReconcileAction[];
}

/** Reconcile decoded remote beans against the local library (CORE4). The
 *  `roasterNames` map (local roaster id → name) lets the kernel fold the
 *  roaster name into each bean's signature, exactly as the TS did via
 *  `library.getRoaster(id)?.name`. */
function reconcileBeans(
	local: Bean[],
	remote: Bean[],
	roasterNames: Record<string, string>,
	lastSyncAt: number | null
): BeanReconcileAction[] {
	return JSON.parse(
		wasmReconcileBeans(JSON.stringify({ local, remote, roasterNames, lastSyncAt }))
	) as BeanReconcileAction[];
}

/** Fold a reconciled remote roaster into its local row (core `merge_pulled_roaster`):
 *  `refresh` = an `update` (take the remote fields), else a `bind`. */
function mergePulledRoaster(
	local: Roaster,
	remote: RoasterWire,
	refresh: boolean,
	lastSyncAt: number | null
): Roaster {
	return JSON.parse(
		wasmMergePulledRoaster(
			JSON.stringify(local),
			JSON.stringify(remote),
			refresh,
			lastSyncAt ?? undefined
		)
	) as Roaster;
}

/** A roaster's catalogue link for a write (core `resolve_roaster_catalogue_link`). */
function resolveRoasterCatalogueLink(roaster: Roaster, beans: Bean[]): string | null {
	return wasmResolveRoasterCatalogueLink(JSON.stringify({ roaster, beans })) ?? null;
}

/** The bean push leg's work list (core `plan_bean_push`), minus `skipIds`. */
function planBeanPush(beans: Bean[], lastSyncAt: number | null, skipIds: string[]): BeanPushItem[] {
	return JSON.parse(
		wasmPlanBeanPush(JSON.stringify({ beans, lastSyncAt, skipIds }))
	) as BeanPushItem[];
}

/** The roaster push leg's work list (core `plan_roaster_push`), minus `skipIds`. */
function planRoasterPush(
	roasters: Roaster[],
	lastSyncAt: number | null,
	skipIds: string[]
): RoasterPushItem[] {
	return JSON.parse(
		wasmPlanRoasterPush(JSON.stringify({ roasters, lastSyncAt, skipIds }))
	) as RoasterPushItem[];
}

/** Which legs run for the beans / roasters directions (core `bean_sync_scope`). */
export function beanSyncScope(beansDirection: string, roastersDirection: string): BeanSyncScope {
	return JSON.parse(wasmBeanSyncScope(beansDirection, roastersDirection)) as BeanSyncScope;
}

/** The catalogue link-PATCH leg's work list (core `plan_roaster_link_patches`). */
function planRoasterLinkPatches(
	roasters: Roaster[],
	beans: Bean[],
	unlinkedRemoteIds: string[]
): RoasterLinkPatch[] {
	return JSON.parse(
		wasmPlanRoasterLinkPatches(JSON.stringify({ roasters, beans, unlinkedRemoteIds }))
	) as RoasterLinkPatch[];
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
		/**
		 * Search the Visualizer canonical catalogue —
		 * `GET /canonical_coffee_bags?q=…&items=…` (open to free and Premium
		 * accounts). The body is parsed by the core
		 * (`de1_domain::parse_catalogue_coffee_bags`).
		 */
		readonly searchCatalogue: (
			query: string
		) => Effect.Effect<CataloguePage, VisualizerCallError>;
		/** Fetch the signed-in user's `/me` profile (replaces `visualizer/account.ts`). */
		readonly fetchAccount: Effect.Effect<VisualizerAccount, VisualizerCallError | ResponseDecodeError>;
		/**
		 * Re-run the premium probe (the same sentinel `POST /roasters` as
		 * {@link testConnection}) and cache a conclusive result — the bean-sync
		 * `premium` flag (`writeSyncSettings`, mirrored into the sync-config)
		 * plus `premiumCheckedAt`. An inconclusive probe (`null`) or a signed-out
		 * vault leaves the cache untouched. Runs on Visualizer sign-in. Never fails.
		 */
		readonly refreshPremium: Effect.Effect<boolean | null>;
		/**
		 * {@link refreshPremium} at most once per {@link PREMIUM_REFRESH_INTERVAL_MS}
		 * (24 h, off `premiumCheckedAt`); otherwise a no-op returning the cached
		 * flag. Runs at app start. Never fails.
		 */
		readonly refreshPremiumIfStale: Effect.Effect<boolean | null>;
		/**
		 * Verify the connection + probe the premium tier (replaces
		 * `visualizer-sync.ts` `testConnection`): a read to catch auth errors, then
		 * a sentinel `POST /roasters` whose status is the one authoritative premium
		 * signal. Caches the result into both sync stores. Never fails.
		 */
		readonly testConnection: Effect.Effect<ConnectionTestResult>;
	}
>() {}

/** How often the app-start premium re-probe may run ({@link BeanSync.refreshPremiumIfStale}). */
export const PREMIUM_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000;

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
			const body = coffeeBagWriteRequest(bean, remoteRoasterId);
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
			const body = roasterWriteRequest(roaster);
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

		const searchCatalogue = Effect.fn('BeanSync.searchCatalogue')(function* (query: string) {
			const params = new URLSearchParams({ q: query, items: String(CATALOGUE_PAGE_SIZE) });
			const raw = yield* call(`/canonical_coffee_bags?${params.toString()}`);
			return parseCataloguePage(raw);
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

			/**
			 * The roaster with its catalogue link resolved for a write: its own
			 * `catalogueRoasterId`, else the catalogue roaster id of a bean filed
			 * under it that was picked from the catalogue.
			 */
			const withCatalogueLink = (roaster: Roaster): Roaster => {
				const link = resolveRoasterCatalogueLink(roaster, library.beans);
				return link && link !== roaster.catalogueRoasterId
					? { ...roaster, catalogueRoasterId: link }
					: roaster;
			};

			// Which legs run (core `bean_sync_scope`): `backup` pushes only, `pull`
			// pulls only and never writes remote, `two-way` both, `off` neither.
			const syncConfig = readSyncConfig();
			const scope = beanSyncScope(syncConfig.direction.beans, syncConfig.direction.roasters);
			const lastSyncAt = settings.lastSyncAt;

			const program = Effect.gen(function* () {
				// Rows this run took from the remote (added roasters, every applied
				// bag) — they already match it, so the push legs skip them (core
				// `plan_*_push` `skipIds`).
				const pulledRoasterIds: string[] = [];
				const pulledBeanIds: string[] = [];
				// Remote → local roaster ids for the bag decode. Seeded from the
				// already-bound roasters so a beans-only pull still files bags.
				const remoteRoasterIdToLocal = new Map<string, string>();
				for (const r of library.roasters) {
					if (r.visualizerId) remoteRoasterIdToLocal.set(r.visualizerId, r.id);
				}
				// Bound roasters whose remote row has no catalogue link yet — the
				// link-PATCH leg (2b) fills them in when Crema has one.
				const remoteUnlinked = new Set<string>();

				// 1) Pull remote roasters → reconcile in the core kernel (CORE4),
				//    apply the action list shell-side.
				if (scope.pullRoasters) {
					const remoteRoasters = (yield* pullPaged<RoasterListResponse['data'][number]>(
						'/roasters'
					)) as RoasterWire[];
					for (const action of reconcileRoasters(library.roasters, remoteRoasters)) {
						const wire = action.remote;
						// The kernel only emits actions for remotes that carry an id.
						const remoteId = wire.id as string;
						if (!wire.canonical_roaster_id) remoteUnlinked.add(remoteId);
						if (action.kind === 'update' || action.kind === 'bind') {
							// The merge lives in the core (`merge_pulled_roaster`): an
							// `update` takes the remote fields unless the local was edited
							// since the last sync, a `bind` only the binding; `updatedAt` is
							// kept (a pull is not a local edit). `canonical_roaster_id` is
							// Visualizer's CATALOGUE link (→ `catalogueRoasterId`); the local
							// dedup pointer (`canonicalRoasterId`) is never touched by a pull.
							const local = library.getRoaster(action.localId);
							if (local) {
								library.replaceRoaster(
									mergePulledRoaster(local, wire, action.kind === 'update', lastSyncAt)
								);
							}
							// Not added to `pulledRoasterIds`: a bound row the merge left
							// alone isn't dirty (the push plan skips it anyway), and one
							// edited here since the last sync kept its edit — that must push.
							remoteRoasterIdToLocal.set(remoteId, action.localId);
							// A refresh of an already-bound row is silent; a new binding logs.
							if (action.kind === 'bind') {
								log.push({ direction: 'pull', kind: 'roaster', id: action.localId, name: wire.name, at: Date.now() });
							}
						} else {
							const fresh = roasterFromWire(wire);
							library.replaceRoaster(fresh);
							remoteRoasterIdToLocal.set(remoteId, fresh.id);
							pulledRoasterIds.push(fresh.id);
							result.pulled += 1;
							log.push({ direction: 'pull', kind: 'roaster', id: fresh.id, name: wire.name, at: Date.now() });
						}
					}
				}

				// 2) Push local roasters (premium-gated): unbound → POST, bound and
				//    edited since the last sync → PATCH (core `plan_roaster_push`).
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
				const pushedRoasterIds = new Set<string>();
				if (scope.pushRoasters) {
					for (const item of planRoasterPush(library.roasters, lastSyncAt, pulledRoasterIds)) {
						const local = library.getRoaster(item.localId);
						if (!local) continue;
						if (premiumLocked) {
							if (item.create) {
								result.skipped += 1;
								log.push({ direction: 'skip', kind: 'roaster', id: local.id, name: local.name, at: Date.now(), error: 'premium required' });
							}
							continue;
						}
						// EF2: the push routes through `uploadRoaster` (POST without an id,
						// PATCH with one), carrying the resolved catalogue link.
						const res = yield* Effect.either(uploadRoaster(withCatalogueLink(local)));
						if (res._tag === 'Right') {
							if (item.create) {
								library.updateRoaster(local.id, { visualizerId: res.right.visualizerId });
								remoteRoasterIdToLocal.set(res.right.visualizerId, local.id);
							}
							pushedRoasterIds.add(local.id);
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

					// 2b) Link already-synced roasters to the catalogue: a bound roaster
					//     whose remote row has no `canonical_roaster_id` but which Crema
					//     knows a catalogue id for gets a roaster PATCH carrying the link.
					for (const patch of planRoasterLinkPatches(library.roasters, library.beans, [
						...remoteUnlinked
					])) {
						if (premiumLocked) break;
						if (pushedRoasterIds.has(patch.localId)) continue;
						const local = library.getRoaster(patch.localId);
						if (!local) continue;
						const linked = { ...local, catalogueRoasterId: patch.catalogueRoasterId };
						const res = yield* Effect.either(uploadRoaster(linked));
						if (res._tag === 'Right') {
							if (!local.catalogueRoasterId) {
								// Keeps `updatedAt`: the link now matches the remote.
								library.replaceRoaster(linked);
							}
							result.pushed += 1;
							log.push({ direction: 'push', kind: 'roaster', id: local.id, name: local.name, at: Date.now() });
						} else {
							if (res.left._tag === 'VisualizerPremiumGatedError') {
								premiumLocked = true;
								writeSyncSettings({ premium: false });
								logPremiumBannerOnce();
							}
							log.push({ direction: 'skip', kind: 'roaster', id: local.id, name: local.name, at: Date.now(), error: describeVisualizerError(res.left) });
						}
					}
				}

				// 3) Pull remote bags → decode (beanFromWire) → reconcile in the
				//    core kernel against the last-sync baseline, apply shell-side.
				if (scope.pullBeans) {
					const remoteBags = (yield* pullPaged<CoffeeBagListResponse['data'][number]>(
						'/coffee_bags'
					)) as BagWire[];
					const decodedBags = remoteBags.map((wire) =>
						beanFromWire(wire, (rid) => (rid ? (remoteRoasterIdToLocal.get(rid) ?? null) : null))
					);
					const roasterNames: Record<string, string> = {};
					for (const r of library.roasters) roasterNames[r.id] = r.name;
					for (const action of reconcileBeans(library.beans, decodedBags, roasterNames, lastSyncAt)) {
						const decoded = action.remote;
						// Stored as the core hands it back — a replace keeps the local
						// id + `updatedAt`, so the pull doesn't read as a local edit.
						library.replaceBean(decoded);
						if (action.kind === 'replace') {
							pulledBeanIds.push(action.localId);
							log.push({ direction: 'pull', kind: 'bean', id: action.localId, name: decoded.name, at: Date.now() });
						} else {
							pulledBeanIds.push(decoded.id);
							result.pulled += 1;
							log.push({ direction: 'pull', kind: 'bean', id: decoded.id, name: decoded.name, at: Date.now() });
						}
					}
				}

				// 4) Push local bags (premium-gated): the core's `plan_bean_push`
				//    (unbound → create, edited since the last sync → update), minus
				//    what step 3 just pulled.
				if (scope.pushBeans) {
					for (const item of planBeanPush(library.beans, lastSyncAt, pulledBeanIds)) {
						const local = library.getBean(item.localId);
						if (!local) continue;
						if (premiumLocked) {
							if (item.create) {
								result.skipped += 1;
								log.push({ direction: 'skip', kind: 'bean', id: local.id, name: local.name, at: Date.now(), error: 'premium required' });
							}
							continue;
						}
						const remoteRoasterId = local.roasterId
							? (library.getRoaster(local.roasterId)?.visualizerId ?? null)
							: null;
						// EF2: `uploadBean` builds `coffeeBagWriteRequest(local, remoteRoasterId)`
						// and PATCHes (id present) or POSTs (id absent).
						const res = yield* Effect.either(uploadBean(local, remoteRoasterId));
						if (res._tag === 'Right') {
							if (item.create) library.updateBean(local.id, { visualizerId: res.right.visualizerId });
							result.pushed += 1;
							log.push({ direction: 'push', kind: 'bean', id: local.id, name: local.name, at: Date.now() });
						} else if (res.left._tag === 'VisualizerPremiumGatedError') {
							premiumLocked = true;
							writeSyncSettings({ premium: false });
							logPremiumBannerOnce();
							log.push({ direction: 'skip', kind: 'bean', id: local.id, name: local.name, at: Date.now(), error: item.create ? 'premium required' : describeVisualizerError(res.left) });
						} else {
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

		/**
		 * Cache a probe result into both stores (matches the old impl), stamping
		 * `premiumCheckedAt` when it was conclusive.
		 */
		const storePremium = (premium: boolean | null): void => {
			writeSyncSettings(premium === null ? { premium } : { premium, premiumCheckedAt: Date.now() });
			updateSyncConfig({ premium });
		};

		const testConnection: Effect.Effect<ConnectionTestResult> = Effect.gen(function* () {
			const tokens = yield* vault.getTokens;
			if (tokens === null) return { ok: false, error: 'Sign in to Visualizer first.' };
			const check = yield* Effect.either(call('/coffee_bags?items=1'));
			if (check._tag === 'Left') return { ok: false, error: describeVisualizerError(check.left) };
			const premium = yield* probePremium;
			// Mirror into both caches so every UI surface agrees (matches the old impl).
			storePremium(premium);
			return { ok: true, premium };
		});

		const refreshPremium: Effect.Effect<boolean | null> = Effect.gen(function* () {
			const tokens = yield* vault.getTokens;
			if (tokens === null) return null;
			const premium = yield* probePremium;
			// Inconclusive (network blip, 5xx…) keeps the cached flag: never let a
			// transient failure downgrade a known tier.
			if (premium !== null) storePremium(premium);
			return premium;
		});

		const refreshPremiumIfStale: Effect.Effect<boolean | null> = Effect.suspend(() => {
			const { premium, premiumCheckedAt } = readSyncSettings();
			const fresh =
				premiumCheckedAt !== null && Date.now() - premiumCheckedAt < PREMIUM_REFRESH_INTERVAL_MS;
			return fresh ? Effect.succeed(premium) : refreshPremium;
		});

		return BeanSync.of({
			uploadBean,
			uploadRoaster,
			searchCatalogue,
			deleteBean,
			deleteRoaster,
			runSync,
			fetchAccount,
			refreshPremium,
			refreshPremiumIfStale,
			testConnection
		});
	})
);
