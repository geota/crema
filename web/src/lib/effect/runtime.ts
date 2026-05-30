/**
 * `$lib/effect/runtime` — construction of the app-wide Effect runtime.
 *
 * One `ManagedRuntime` is mounted at the app shell (`+layout.svelte`'s
 * `onMount`) and published through Svelte context, mirroring how the shared
 * `CremaApp` is published (see docs/53 §1.2, D-03). Mounting in `onMount`
 * — not module scope — keeps `localStorage` / `navigator` / wasm off the
 * `adapter-static` build-time evaluation path.
 *
 * `AppLayer` is empty for now; each service phase merges its `*Live` layer in.
 */

import { Layer, ManagedRuntime } from 'effect';
import { HttpClientLive } from '../services/http-client.ts';
import { OAuthLive } from '../services/oauth.ts';
import { TokenVaultLive } from '../services/token-vault.ts';
import { ShotSyncLive } from '../services/shot-sync.ts';
import { BeanSyncLive } from '../services/bean-sync.ts';

/**
 * The composed application layer. Services join here as they land.
 * Dependency graph: OAuth consumes HttpClient; TokenVault consumes OAuth;
 * ShotSync + BeanSync each consume HttpClient + TokenVault — every dependency
 * is provided in, leaving no open requirements.
 */
const OAuthProvided = Layer.provide(OAuthLive, HttpClientLive);
const TokenVaultProvided = Layer.provide(TokenVaultLive, OAuthProvided);
// HttpClient + TokenVault feed both visualizer-mutation services.
const HttpAndVault = Layer.merge(HttpClientLive, TokenVaultProvided);
const ShotSyncProvided = Layer.provide(ShotSyncLive, HttpAndVault);
const BeanSyncProvided = Layer.provide(BeanSyncLive, HttpAndVault);

export const AppLayer = Layer.mergeAll(
	HttpClientLive,
	OAuthProvided,
	TokenVaultProvided,
	ShotSyncProvided,
	BeanSyncProvided
);

/** The services the app runtime provides. `never` while `AppLayer` is empty. */
export type AppServices = Layer.Layer.Success<typeof AppLayer>;

/** The concrete runtime type published to routes via Svelte context. */
export type AppRuntime = ManagedRuntime.ManagedRuntime<AppServices, never>;

/** Build a fresh app runtime. Call once, at the shell, in `onMount`. */
export function createAppRuntime(): AppRuntime {
	return ManagedRuntime.make(AppLayer);
}
