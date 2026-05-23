/**
 * `$lib/bean` — the bean library: bags of coffee and the roasters that produced
 * them. Mirrors `de1_domain::bean`; types are shareable across shells via
 * `#[typeshare]`. Persistence and CRUD live in the shell store.
 *
 * See `docs/28-bean-roaster-library.md` and
 * `docs/32-bean-library-implementation.md` for the design and the shipped /
 * deferred scope.
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
	roastFreshness,
	ROAST_PILL_LEVEL
} from './model';

export {
	BeanLibraryStore,
	getBeanLibraryStore,
	getBeanStore
} from './store.svelte';
