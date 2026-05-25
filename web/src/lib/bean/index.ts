/**
 * `$lib/bean` — the bean library: bags of coffee and the roasters that produced
 * them. Mirrors `de1_domain::bean`; types are shareable across shells via
 * `#[typeshare]`. Persistence and CRUD live in the shell store.
 */

export {
	type Bean,
	type BeanMix,
	type BeanOrigin,
	type Freshness,
	type LegacyCurrentBean,
	type Roaster,
	blankBean,
	blankOrigin,
	blankRoaster,
	beanDisplaySummary,
	coerceBean,
	coerceRoaster,
	daysOffRoast,
	migrateLegacyCurrentBean,
	mintBeanId,
	mintRoasterId,
	roastBand,
	roastBand5,
	roastFreshness,
	bagState,
	type BagState,
	ROAST_PILL_LEVEL
} from './model';

export {
	BeanLibraryStore,
	getBeanLibraryStore,
	getBeanStore
} from './store.svelte';

export { roasterMark, roasterTone, roasterMarkTone } from './roaster-mark';
export { roasterFaviconUrl } from './roaster-favicon';

export {
	deleteRemoteBean,
	deleteRemoteRoaster,
	readSyncSettings,
	runSync,
	testConnection,
	writeSyncSettings,
	VisualizerError,
	type SyncLogEntry,
	type SyncResult,
	type VisualizerSyncSettings
} from './visualizer-sync';

export {
	clearTokens as clearVisualizerTokens,
	fetchAccount as fetchVisualizerAccount,
	isConfigured as isVisualizerOauthConfigured,
	isConnected as isVisualizerConnected,
	onTokenChange as onVisualizerTokenChange,
	revokeToken as revokeVisualizerToken,
	startVisualizerLogin,
	getStoredTokens as getStoredVisualizerTokens,
	OAUTH_SCOPES as VISUALIZER_OAUTH_SCOPES,
	type TokenSet as VisualizerTokenSet,
	type VisualizerAccount
} from '$lib/visualizer';
