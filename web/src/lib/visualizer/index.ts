/**
 * `$lib/visualizer` — top-level barrel for the Visualizer integration.
 *
 * Modules:
 *   - {@link ./oauth}        — OAuth 2.0 + PKCE redirect flow
 *   - {@link ./token-store}  — localStorage persistence + `withFreshToken`
 *   - {@link ./account}      — the `/api/me` helper
 *
 * The bean-sync code in `$lib/bean/visualizer-sync` consumes
 * `withFreshToken` and stays unaware of the redirect dance.
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

export {
	getStoredTokens,
	storeTokens,
	clearTokens,
	isConnected,
	onTokenChange,
	getFreshAccessToken,
	withFreshToken,
	NotAuthenticatedError
} from './token-store';

export { fetchAccount, type VisualizerAccount } from './account';

export { migrateLegacyBasicAuth } from './migrate-basic-auth';

// ── Shot sync ──────────────────────────────────────────────────────
export {
	applyShotReconciliation,
	deleteShot,
	patchShot,
	pullAllShotsSince,
	pullAndReconcileShots,
	pullShots,
	reconcileShots,
	signatureForBean,
	signatureForRoaster,
	signatureForShot,
	storedShotFromWire,
	uploadShot,
	uploadUnsyncedShots,
	type PullProgress,
	type ReconcileAction,
	type WireShot
} from './shot-sync';

// ── Upload queue ───────────────────────────────────────────────────
export {
	armQueueLifecycle,
	clearQueue,
	dequeue,
	drainQueue,
	enqueue,
	getQueue,
	isPending,
	type DrainResult,
	type QueueEntry
} from './upload-queue';

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
