/**
 * `$lib/visualizer` — top-level barrel for the Visualizer integration.
 *
 * Modules:
 *   - {@link ./oauth}            — OAuth 2.0 + PKCE redirect flow + crypto helpers
 *   - {@link ./sync-config}      — pure localStorage sync-config CRUD
 *   - {@link ./migrate-basic-auth} — one-shot legacy-credential cleanup
 *
 * Token persistence + refresh and the authed HTTP now live in the Effect
 * services (`$lib/services/{token-vault,http-client,shot-sync,bean-sync}`); the
 * shot/queue work runs on the app runtime (Option 3). The wasm-side de-dup
 * helpers live in {@link ./shot-sync-signatures}.
 */

export {
	AUTHORIZE_URL,
	TOKEN_URL,
	REVOKE_URL,
	OAUTH_SCOPES,
	OAUTH_SCOPE,
	clientId,
	isConfigured,
	redirectUri,
	startVisualizerLogin,
	exchangeCodeForToken,
	refreshAccessToken,
	revokeToken,
	takeReturnPath,
	generateCodeVerifier,
	codeChallengeFromVerifier,
	randomState,
	type TokenSet,
	type StartLoginOptions,
	type ExchangeCodeOptions
} from './oauth';

// Token storage + the authed `/me` call moved to the Effect services
// (`TokenVault` / `BeanSync.fetchAccount`) at the token-store retirement; the
// OAuth redirect/crypto helpers above stay here. `token-store.ts` + `account.ts`
// are deleted.

export { migrateLegacyBasicAuth } from './migrate-basic-auth';

// Best-effort remote (Visualizer) delete, shared by the bean + roaster delete
// split-buttons. The store stays pure-local; this fires the cloud DELETEs.
export { bestEffortRemoteDelete } from './bestEffortRemoteDelete';

// ── Shot sync + upload queue (T-16) ────────────────────────────────
// Moved to the Effect services `$lib/services/{shot-sync,upload-queue}` and the
// pure `$lib/services/queue-store`. Call sites run them on the app runtime
// (Option 3) — there is no Promise-shaped facade here. The wasm-side
// de-dup/signature helpers (`reconcileShots`, `signatureFor*`,
// `storedShotFromWire`, `WireShot`) live in `./shot-sync-signatures` and are
// imported there directly by the services.

// ── Unified sync config ────────────────────────────────────────────
export {
	appendSyncLog,
	clearSyncLog,
	DEFAULT_SYNC_CONFIG,
	directionPulls,
	directionPushes,
	onSyncConfigChange,
	readSyncConfig,
	updateSyncConfig,
	writeSyncConfig,
	type SyncDirection,
	type SyncLogEntry as VisualizerSyncLogEntry,
	type VisualizerSyncConfig
} from './sync-config';
