/**
 * `$lib/state/history-context` — joined per-shot views for the history
 * readers (`ShotDetail`, `ShotRow`, `CompareOverlay`, `StaticShotChart`,
 * `BeanPicker`).
 *
 * Each consumer needs a `StoredShot` joined against the live bean
 * library (for current tags / display-name fallback) and the profile
 * library (to resolve `profileName` back to a `CremaProfile` for the
 * embedded profile slot). The join was inlined at every reader; this
 * context exposes it once.
 *
 * The shape is a singleton factory: callers ask `history.viewFor(shot)`
 * and receive a typed view. Per-shot — not per-app — because the
 * underlying data is the shot itself.
 */

import type { Bean, BeanLibraryStore } from '$lib/bean';
import type { CremaProfile, ProfileStore } from '$lib/profiles';
import type { StoredShot } from '$lib/history';

/**
 * One shot's joined view. Live fields (`liveBean`, `resolvedProfile`)
 * are looked up on every access — Svelte 5 propagates the upstream
 * store subscriptions through these getters.
 */
export interface ShotView {
	readonly shot: StoredShot;
	/**
	 * The library row matching `shot.bean.beanId`, or `null` when the
	 * bean has been deleted (or the shot was pulled with no bean
	 * snapshot). Use this for current tags / current display name when
	 * the live row should win over the frozen snapshot — e.g. the
	 * Visualizer bag-link button reads `liveBean.visualizerId`.
	 */
	readonly liveBean: Bean | null;
	/**
	 * The profile library row matching `shot.profileName`, or `null`
	 * when no profile in the library has that name (e.g. a pulled
	 * remote shot whose profile was since deleted). Used by ShotDetail
	 * to render the embedded profile preview.
	 */
	readonly resolvedProfile: CremaProfile | null;
	/**
	 * Display label for the shot — the frozen `shot.bean.name + roaster`
	 * if available, else the resolved profile name, else `null`. The
	 * shot's snapshot wins over the live bean's current name (snapshot
	 * semantics — a bean rename does not retroactively retitle history).
	 */
	readonly displayName: string | null;
}

/**
 * Per-app singleton; constructed at boot. Hand it a `StoredShot` to
 * get back a {@link ShotView} that joins the live bean + profile
 * libraries against the shot's frozen snapshot.
 */
export class HistoryContext {
	constructor(
		private readonly beans: BeanLibraryStore,
		private readonly profiles: ProfileStore
	) {}

	/** Build the joined view for `shot`. Cheap — no caching; getters re-fetch each read. */
	viewFor(shot: StoredShot): ShotView {
		const beans = this.beans;
		const profiles = this.profiles;
		return {
			shot,
			get liveBean() {
				return shot.bean?.beanId ? (beans.getBean(shot.bean.beanId) ?? null) : null;
			},
			get resolvedProfile() {
				if (!shot.profileName) return null;
				return profiles.all.find((p) => p.name === shot.profileName) ?? null;
			},
			get displayName() {
				const beanName = shot.bean?.name?.trim();
				const roaster = shot.bean?.roasterName?.trim();
				if (beanName && roaster) return `${roaster} · ${beanName}`;
				if (beanName) return beanName;
				return shot.profileName?.trim() || null;
			}
		};
	}
}

let context: HistoryContext | undefined;
let deps: { beans: BeanLibraryStore; profiles: ProfileStore } | undefined;

/** Singleton accessor — throws if {@link bindHistoryContext} hasn't been called yet. */
export function getHistoryContext(): HistoryContext {
	if (!context) {
		if (!deps) {
			throw new Error(
				'HistoryContext used before bindHistoryContext — wire it in the orchestrator first.'
			);
		}
		context = new HistoryContext(deps.beans, deps.profiles);
	}
	return context;
}

/** Wire the orchestrator's store refs into the singleton. Called once at boot. */
export function bindHistoryContext(args: {
	beans: BeanLibraryStore;
	profiles: ProfileStore;
}): void {
	deps = args;
}
