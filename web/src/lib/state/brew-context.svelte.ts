/**
 * `$lib/state/brew-context` — the read-side seam for the brew dashboard
 * and its children.
 *
 * Joins the four stores the dashboard reads from (`BeanLibraryStore`,
 * `ProfileStore`, `SettingsStore`, `ActiveShotStore`) plus the shell's
 * `CremaUiState` snapshot, and exposes the resolved properties the UI
 * actually consumes: `activeBean`, `activeProfile`, `dose`,
 * `grinderModel`, `brewParams`, `inFlight`.
 *
 * In-flight → live fallback policy: when a shot is in flight (live or
 * replay) the in-flight `ActiveShot` snapshot wins so the brew page
 * doesn't retroactively retitle / re-photo a shot in progress when the
 * user happens to select a different bean mid-pour. With no shot in
 * flight, the live store values pass through. The policy lives here so
 * 10+ consumers don't each implement their own `activeShot?.X ?? live.X`
 * fallback.
 */

import { getBeanStore, type Bean, type BeanLibraryStore } from '$lib/bean';
import { getProfileStore, type CremaProfile, type ProfileStore } from '$lib/profiles';
import { getSettingsStore, type SettingsStore } from '$lib/settings';
import {
	getActiveShotStore,
	type ActiveShotBrewParams,
	type ActiveShotStore
} from './active-shot.svelte';
import { getCremaUiState, type CremaUiState } from './ui-state.svelte';

/**
 * Unified read-side view of the brew page's bean. Joins the two underlying
 * sources (a live `Bean` row from the library, and the in-flight `ShotBean`
 * snapshot frozen at shot start / parsed from replay META) into a single
 * shape components render against.
 *
 * `source` carries the underlying library `Bean` only when this view was
 * derived from a live library row — components that need write access
 * (BeanContextCard's inline edit, quick-add) gate on it being non-null.
 * Replay snapshots and live shots whose library row has since been
 * archived/deleted return `null` there.
 */
export interface DisplayBean {
	readonly name: string;
	readonly roasterName: string | null;
	readonly roastedOn: string | null;
	readonly roastLevel: number | null;
	readonly grinder: string;
	/** Bag size in grams; `null` when the snapshot doesn't carry one. */
	readonly bagSize: number | null;
	/** Grams remaining; `null` when the snapshot doesn't carry one. */
	readonly remaining: number | null;
	/** Free-form tags at shot time. */
	readonly tags: readonly string[];
	/** The underlying library `Bean` when editable; `null` for snapshot-only views. */
	readonly source: Bean | null;
}

/**
 * Joined view of the brew page's read paths — wired by the orchestrator
 * Constructed lazily on first access via {@link getBrewContext}; reads
 * through the shared store singletons (`getBeanStore`, `getProfileStore`,
 * `getSettingsStore`, `getActiveShotStore`, `getCremaUiState`).
 *
 * Getters propagate Svelte 5 signal subscriptions through their reads,
 * so `$derived(brew.activeBean)` in a component re-runs whenever any
 * upstream store's reactive state changes.
 */
export class BrewContext {
	constructor(
		private readonly beans: BeanLibraryStore,
		private readonly profiles: ProfileStore,
		private readonly settings: SettingsStore,
		private readonly active: ActiveShotStore,
		private readonly state: CremaUiState
	) {}

	/** True while a shot (live or replay) is in flight. */
	get inFlight(): boolean {
		return this.active.isActive;
	}

	/** True while a replay is feeding the in-flight shot's bytes. */
	get isReplay(): boolean {
		return this.active.isReplay;
	}

	/**
	 * The bean the brew page should render, as a unified {@link DisplayBean}
	 * view. The in-flight snapshot wins over the live library selection
	 * — including the replay case, where {@link ActiveShotData.bean}
	 * carries `beanId: null` by design (the snapshot is the source of
	 * truth, no library row exists).
	 *
	 * Returns `null` when no shot is in flight AND no library bean is
	 * active. Callers needing write access to the underlying record
	 * (BeanContextCard's inline edit / quick-add) read the optional
	 * `source` field — it's `null` for replay snapshots so edit affordances
	 * naturally hide.
	 */
	get activeBean(): DisplayBean | null {
		const snap = this.active.bean;
		if (snap !== null) {
			// In flight (live or replay). The snapshot is authoritative;
			// resolve the underlying library row only to back the editable
			// `source` field (live shot path). Replay's `beanId` is null
			// by design, so `source` stays null and editor affordances
			// suppress without a separate replay branch.
			const libRow = snap.beanId ? this.beans.getBean(snap.beanId) : null;
			return {
				name: snap.name,
				roasterName: snap.roasterName ?? null,
				roastedOn: snap.roastedOn ?? null,
				roastLevel: snap.roastLevel ?? null,
				grinder: snap.grinderSetting ?? libRow?.grinder ?? '',
				bagSize: libRow?.bagSize ?? null,
				remaining: libRow?.remaining ?? null,
				tags: snap.tags ?? [],
				source: libRow
			};
		}
		const live = this.beans.activeBean;
		if (live === null) return null;
		const roaster = live.roasterId ? this.beans.getRoaster(live.roasterId) : null;
		return {
			name: live.name,
			roasterName: roaster?.name ?? null,
			roastedOn: live.roastedOn,
			roastLevel: live.roastLevel,
			grinder: live.grinder,
			bagSize: live.bagSize,
			remaining: live.remaining,
			tags: live.tags,
			source: live
		};
	}

	/** The library's currently-selected bean, ignoring any in-flight shot. */
	get liveActiveBean(): Bean | null {
		return this.beans.activeBean;
	}

	/** The in-flight shot's bean snapshot (frozen at shot start), or `null`. */
	get activeShotBean() {
		return this.active.bean;
	}

	/**
	 * The active profile — in-flight name resolves through the library,
	 * else the library's `activeId`. `undefined` when no profile is set
	 * or the in-flight name doesn't match any profile in the library
	 * (e.g. a replay of a shot whose profile was since deleted).
	 */
	get activeProfile(): CremaProfile | undefined {
		const inFlightName = this.active.profileName;
		if (inFlightName) {
			const match = this.profiles.all.find((p) => p.name === inFlightName);
			if (match) return match;
		}
		return this.profiles.activeId
			? this.profiles.get(this.profiles.activeId)
			: undefined;
	}

	/**
	 * The shot's brew dose, grams. In-flight snapshot wins; falls back
	 * to the active profile's `dose` field. `null` when neither path
	 * resolves.
	 */
	get dose(): number | null {
		return this.active.dose ?? this.activeProfile?.dose ?? null;
	}

	/**
	 * The grinder model resolved at read time — equipment cascade:
	 * in-flight snapshot wins, then the active bean's own grinder
	 * label, then the settings-page default. `null` when none of the
	 * three carries a value.
	 */
	get grinderModel(): string | null {
		const inFlight = this.active.grinderModel?.trim();
		if (inFlight) return inFlight;
		const fromBean = this.beans.activeBean?.grinder?.trim();
		if (fromBean) return fromBean;
		return this.settings.current.grinderModel?.trim() || null;
	}

	/**
	 * The Quick-Sheet brew-params snapshot taken at shot start (yield
	 * target / brew temp / preinfuse target / stop-on-weight / auto-tare).
	 * `null` when no shot is in flight.
	 */
	get brewParams(): ActiveShotBrewParams | null {
		return this.active.brewParams;
	}

	/**
	 * The DE1's currently-loaded profile title — what the firmware
	 * actually has, surfaced from the snapshot. Distinct from
	 * {@link activeProfile} which is the library's selection: a freshly
	 * connected DE1 might have a different profile already loaded that
	 * the shell hasn't re-uploaded. `null` until the connect-time
	 * profile-header read has surfaced one.
	 */
	get loadedProfileName(): string | null {
		return this.state.current.activeProfileName;
	}

	/**
	 * The profile name the brew page should display, with the full
	 * fallback chain: in-flight snapshot wins (replay needs this — the
	 * user's loaded profile is unrelated to the replayed shot), then
	 * the DE1's currently-loaded profile from the snapshot, then the
	 * library's active profile name, then a placeholder string.
	 *
	 * The placeholder is intentional: returning a string (not `null`)
	 * means components don't have to invent their own "no profile"
	 * label.
	 */
	get displayProfileName(): string {
		return (
			this.active.profileName ??
			this.state.current.activeProfileName ??
			this.activeProfile?.name ??
			'No profile selected'
		);
	}
}

let context: BrewContext | undefined;

/**
 * Singleton accessor. Constructs the context lazily on first call using
 * the shared store + state singletons — no separate bind step. Safe to
 * call from any component at module-load time.
 */
export function getBrewContext(): BrewContext {
	if (!context) {
		context = new BrewContext(
			getBeanStore(),
			getProfileStore(),
			getSettingsStore(),
			getActiveShotStore(),
			getCremaUiState()
		);
	}
	return context;
}
