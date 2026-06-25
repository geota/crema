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
	beanFreshness,
	freshnessColor,
	bagState,
	type BagState,
	ROAST_PILL_LEVEL
} from './model';

export {
	BeanLibraryStore,
	getBeanLibraryStore,
	getBeanStore
} from './store.svelte';

export { activateBean } from './activate';

export { roasterMark, roasterTone, roasterMarkTone } from './roaster-mark';
export { roasterFaviconUrl } from './roaster-favicon';

export {
	clearVisualizerPremiumCache,
	readSyncSettings,
	writeSyncSettings,
	VisualizerError,
	type SyncLogEntry,
	type SyncResult,
	type VisualizerSyncSettings
} from './visualizer-sync';

export {
	BeanImageStore,
	getBeanImageStore,
	refForBean as beanImageRefFor
} from './image-storage';

// Account identity now comes from the `BeanSync` service (it owns the authed
// `/me` call); token storage + the OAuth redirect helpers stay in `$lib/visualizer`.
export { type VisualizerAccount } from '$lib/services/bean-sync';

export {
	isConfigured as isVisualizerOauthConfigured,
	revokeToken as revokeVisualizerToken,
	startVisualizerLogin,
	OAUTH_SCOPES as VISUALIZER_OAUTH_SCOPES,
	type TokenSet as VisualizerTokenSet
} from '$lib/visualizer';
