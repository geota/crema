/**
 * `ActiveShotStore` — the in-flight shot's context.
 *
 * Holds a snapshot of "what the user (or the replay) is brewing right now"
 * for the brief window between `ShotStarted` and `ShotCompleted`. Outside
 * that window the store is `null` and the brew dashboard falls back to live
 * shell stores (the user's current bean, profile, and settings selections).
 *
 * The two populators:
 *
 *   - Live path. The orchestrator builds an `ActiveShotData` at
 *     `ShotStarted` from `BeanStore.activeBean`, the active profile,
 *     the Quick Sheet snapshot captured when the user tapped Coffee,
 *     and the grinder-model cascade. Clears at `ShotCompleted`.
 *   - Replay path. `replayCapture()` builds an `ActiveShotData` from
 *     the parsed META *before* events fire, so the brew UI immediately
 *     reflects the replayed shot's context. Clears in the `finally`.
 *
 * Both downstream consumers — the `HistoryStore.record(...)` payload
 * builder and the `buildAtShotStartMeta()` capture-slice META authoring —
 * read from this store. There is exactly one source of truth for the
 * in-flight shot's context; replay does not branch the readers.
 *
 * Pure shell state. The Rust core has no opinion on bean / grinder /
 * profile-library selections — those are shell concerns.
 *
 * See docs/49-replay-architecture-activeshot.md for the design and
 * docs/48 for the broader replay architecture.
 */

import type { ShotBean } from '$lib/history';

/**
 * The Quick-Controls subset that persists into a `StoredShot` and rides
 * along on the at-shot-start META line of a capture. Mirrors today's
 * `CremaApp.brewParamsAtShotStart` private field — kept narrow so the
 * shape stays stable across the live + replay populators.
 */
export interface ActiveShotBrewParams {
	readonly yieldTarget: number;
	readonly brewTemp: number;
	readonly preinfuseTarget: number;
	readonly stopOnWeight: boolean;
	readonly autoTare: boolean;
}

/**
 * The in-flight shot's frozen context. Every field optional because:
 *
 *   - `bean` may be absent (no library bean selected when the shot
 *     started, or a replay META that lacks the bean block).
 *   - `profileName` is null when no active profile is set or the replay
 *     META didn't carry one.
 *   - `dose` is null when the active profile carries no dose value.
 *   - `brewParams` is null on a headless shot path that doesn't go
 *     through the Quick Sheet (a manual on-machine start, or a replay
 *     META without QC fields).
 *   - `grinderModel` falls through bean.grinder → settings default → null.
 */
export interface ActiveShotData {
	readonly bean: ShotBean | null;
	readonly profileName: string | null;
	readonly dose: number | null;
	readonly brewParams: ActiveShotBrewParams | null;
	readonly grinderModel: string | null;
	readonly source: 'live' | 'replay';
}

/**
 * The reactive in-flight-shot store. Singleton per app — obtain via
 * {@link getActiveShotStore}. `current` is `null` when no shot is in
 * flight; populators call `set(...)` at shot start and `clear()` at shot
 * end (or the replay path's `finally`).
 */
export class ActiveShotStore {
	private data = $state<ActiveShotData | null>(null);

	/** The full in-flight shot context, or `null` when no shot is in flight. */
	get current(): ActiveShotData | null {
		return this.data;
	}

	/** True while a shot (live or replay) is in flight. */
	get isActive(): boolean {
		return this.data !== null;
	}

	/** True while a replay is feeding the in-flight shot's bytes. */
	get isReplay(): boolean {
		return this.data?.source === 'replay';
	}

	/** The in-flight shot's bean snapshot, or `null`. */
	get bean(): ShotBean | null {
		return this.data?.bean ?? null;
	}

	/** The in-flight shot's profile name, or `null`. */
	get profileName(): string | null {
		return this.data?.profileName ?? null;
	}

	/** The in-flight shot's dose (grams), or `null`. */
	get dose(): number | null {
		return this.data?.dose ?? null;
	}

	/** The Quick-Controls snapshot at shot start, or `null`. */
	get brewParams(): ActiveShotBrewParams | null {
		return this.data?.brewParams ?? null;
	}

	/** The equipment-level grinder model at shot start, or `null`. */
	get grinderModel(): string | null {
		return this.data?.grinderModel ?? null;
	}

	/** Set the in-flight shot. Overwrites any prior value (typically `null`). */
	set(next: ActiveShotData): void {
		this.data = next;
	}

	/** Clear the in-flight shot. No-op when already `null`. */
	clear(): void {
		this.data = null;
	}
}

let store: ActiveShotStore | undefined;
/** Singleton accessor. */
export function getActiveShotStore(): ActiveShotStore {
	if (!store) store = new ActiveShotStore();
	return store;
}
