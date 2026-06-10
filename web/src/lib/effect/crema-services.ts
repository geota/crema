/**
 * `$lib/effect/crema-services` — the `Promise`-shaped facade over the Visualizer
 * sync services, the deep seam between the Svelte component world and the Effect
 * service world (architecture review #1).
 *
 * Components used to cross that seam by hand at every call site:
 *
 * ```ts
 * const runtime = appCtx().runtime;
 * if (!runtime) return;
 * await runtimePromise(runtime, ShotSync.pipe(Effect.flatMap((s) => s.uploadUnsyncedShots(h))));
 * ```
 *
 * — five Effect concepts (the runtime handle, the null guard, `runtimePromise`,
 * the `Context.Tag`, the `pipe`/`flatMap`) to make one call. {@link createCremaServices}
 * binds the app runtime once and exposes each operation as a plain `async`
 * method, so callers write `await services.shots.uploadUnsynced(h)` and never
 * import `effect` at all. The Effect machinery lives behind this one interface.
 *
 * `bridge.ts` (`runtimePromise`) is the low-level boundary primitive; this is the
 * high-level, named surface built on it. The facade is created at the shell
 * (alongside the runtime) and published through Svelte context — see
 * `app-context.ts` (`CremaAppContext.services`).
 *
 * Out of scope by design: the OAuth callback (`/auth/visualizer/callback`) drives
 * its own short-lived runtime before the shell mounts, and `app.svelte.ts`'s fold
 * uses `runtime.runSync` for load-bearing ordering — neither is a component
 * reaching for a service, so neither routes through here.
 */

import { Effect, Fiber, Stream } from 'effect';
import type { AppRuntime, AppServices } from './runtime.ts';
import { runtimePromise } from './bridge.ts';
import { TokenVault } from '../services/token-vault.ts';
import { ShotSync, type PullOptions, type ShotPatch } from '../services/shot-sync.ts';
import { BeanSync, type ConnectionTestResult, type VisualizerAccount } from '../services/bean-sync.ts';
import { UploadQueue, type DrainResult, type EnqueueInput } from '../services/upload-queue.ts';
import type { TokenSet } from '$lib/visualizer/oauth';
import type { SyncResult } from '$lib/bean/visualizer-sync';
import type { HistoryStore } from '$lib/history/store.svelte';
import type { BeanLibraryStore } from '$lib/bean/store.svelte';

/**
 * The component-facing surface of the Visualizer sync services. Every method is
 * a plain `Promise` bound to the app runtime; no caller needs to know Effect.
 */
export interface CremaServices {
	/** Visualizer auth (the `TokenVault`). */
	readonly tokens: {
		/** `true` when a token set is stored — the "connected to Visualizer" gate. */
		isConnected(): Promise<boolean>;
		/** The stored token set, or `null`. (For the disconnect-revoke path.) */
		getTokens(): Promise<TokenSet | null>;
		/** Forget the stored tokens (local sign-out). */
		clearTokens(): Promise<void>;
		/**
		 * Subscribe to Visualizer connection changes (SV1). `cb(connected)` fires
		 * once immediately with the current state, then on every token change
		 * (sign-in / sign-out / refresh) from ANY surface — so a component's
		 * `connected` gate stays coherent instead of going stale after a sign-out
		 * elsewhere. Returns an unsubscribe; call it from the component's teardown
		 * (e.g. an `$effect` cleanup).
		 */
		onConnectionChange(cb: (connected: boolean) => void): () => void;
	};
	/** Bean / roaster sync + account (the `BeanSync` service). */
	readonly beans: {
		/** The signed-in user's `/me` profile. */
		fetchAccount(): Promise<VisualizerAccount>;
		/** Verify the connection + probe the premium tier. */
		testConnection(): Promise<ConnectionTestResult>;
		/** Best-effort remote bag delete (a 404 is success). */
		deleteBean(visualizerId: string): Promise<void>;
		/** Best-effort remote roaster delete (a 404 is success). */
		deleteRoaster(visualizerId: string): Promise<void>;
		/** The full bidirectional bean/roaster sync. Never rejects. */
		runSync(library: BeanLibraryStore): Promise<SyncResult>;
	};
	/** Shot sync (the `ShotSync` service). */
	readonly shots: {
		/** Pull shots updated since `sinceMs` and reconcile them into `history`. */
		pullAndReconcile(
			history: HistoryStore,
			sinceMs: number,
			opts?: PullOptions
		): Promise<{ pulled: number; truncated: boolean }>;
		/** Upload every local shot lacking a `visualizerId`. */
		uploadUnsynced(history: HistoryStore): Promise<void>;
		/** PATCH an already-uploaded shot (rating / notes / privacy / …). */
		patch(visualizerId: string, patch: ShotPatch): Promise<void>;
		/** Push a local shot's edited fields to its uploaded copy (no-op when unsynced). */
		patchEdited(history: HistoryStore, id: string): Promise<void>;
	};
	/** The persistent retry queue (the `UploadQueue` service). */
	readonly queue: {
		/** Register a sync op (set-like on entity+id+op). */
		enqueue(input: EnqueueInput): Promise<void>;
		/** Drain every ready entry once. */
		drain(): Promise<DrainResult>;
	};
}

/**
 * Bind the app `runtime` to the {@link CremaServices} surface. Call once, at the
 * shell, right after the runtime is created; publish the result via context.
 */
export function createCremaServices(runtime: AppRuntime): CremaServices {
	/** Run one service effect on the bound runtime, resolving as a `Promise`. */
	const run = <A, E, R extends AppServices>(eff: Effect.Effect<A, E, R>): Promise<A> =>
		runtimePromise(runtime, eff);

	return {
		tokens: {
			isConnected: () => run(Effect.flatMap(TokenVault, (v) => v.getTokens)).then((t) => t !== null),
			getTokens: () => run(Effect.flatMap(TokenVault, (v) => v.getTokens)),
			clearTokens: () => run(Effect.flatMap(TokenVault, (v) => v.clearTokens)),
			onConnectionChange: (cb) => {
				// `TokenVault.changes` is a `SubscriptionRef`; its `.changes` Stream
				// emits the current token state immediately, then every subsequent
				// write (`storeTokens` / `clearTokens`). Fork a consumer on the app
				// runtime that maps each emission to a connected boolean; the returned
				// unsubscribe interrupts that fiber.
				const fiber = runtime.runFork(
					Effect.flatMap(TokenVault, (v) =>
						Stream.runForEach(v.changes.changes, (t) => Effect.sync(() => cb(t !== null)))
					)
				);
				return () => {
					runtime.runFork(Fiber.interrupt(fiber));
				};
			}
		},
		beans: {
			fetchAccount: () => run(Effect.flatMap(BeanSync, (b) => b.fetchAccount)),
			testConnection: () => run(Effect.flatMap(BeanSync, (b) => b.testConnection)),
			deleteBean: (visualizerId) => run(Effect.flatMap(BeanSync, (b) => b.deleteBean(visualizerId))),
			deleteRoaster: (visualizerId) => run(Effect.flatMap(BeanSync, (b) => b.deleteRoaster(visualizerId))),
			runSync: (library) => run(Effect.flatMap(BeanSync, (b) => b.runSync(library)))
		},
		shots: {
			pullAndReconcile: (history, sinceMs, opts) =>
				run(Effect.flatMap(ShotSync, (s) => s.pullAndReconcileShots(history, sinceMs, opts))),
			uploadUnsynced: (history) => run(Effect.flatMap(ShotSync, (s) => s.uploadUnsyncedShots(history))),
			patch: (visualizerId, patch) =>
				run(Effect.flatMap(ShotSync, (s) => s.patchShot(visualizerId, patch))),
			patchEdited: (history, id) =>
				run(Effect.flatMap(ShotSync, (s) => s.patchEditedShot(history, id)))
		},
		queue: {
			enqueue: (input) => run(Effect.flatMap(UploadQueue, (q) => q.enqueue(input))),
			drain: () => run(Effect.flatMap(UploadQueue, (q) => q.drain))
		}
	};
}
