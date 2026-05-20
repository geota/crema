/**
 * `$lib/history/store` — the shot-history store.
 *
 * Backs the `/history` library: the list of past shots, their stored curves,
 * and the editable tasting-notes / star rating. Crema's web shell is a static,
 * client-only PWA — there is no server — so `localStorage` is the store, the
 * same choice (and the shared `$lib/utils/storage` helpers) the profile
 * library makes.
 *
 * Records arrive one way: the orchestrator calls {@link HistoryStore.record}
 * from its `ShotCompleted` handler with a snapshot of the just-finished shot
 * (see `lib/state/app.svelte.ts`). The page only ever *reads* the list and
 * edits notes / ratings — it never fabricates shots, so an untouched install
 * shows a genuine empty state.
 *
 * The store is a Svelte 5 `$state` class; obtain the singleton with
 * {@link getHistoryStore}. It loads synchronously from `localStorage`.
 */

import type { TelemetrySample } from '$lib/state';
import { readJson, writeJson } from '$lib/utils/storage';
import { shotId, type ShotBean, type ShotRecord } from './model';

/**
 * localStorage key for the recorded shots (a `ShotRecord[]`, newest first).
 *
 * Bumped to `v2` when the in-record field names dropped their unit suffixes
 * (`durationMs` → `duration`, `peakWeightG` → `peakWeight`, etc.); v1 records
 * are simply ignored on first read rather than migrated.
 */
const HISTORY_KEY = 'crema.history.v2';

/**
 * Cap on the stored history. A `ShotRecord` carries its full telemetry series
 * (~1500 samples of four small numbers), so a few hundred shots is already a
 * few MB — past this the oldest records are dropped to stay within the
 * localStorage quota.
 */
const MAX_RECORDS = 300;

/**
 * The inputs the orchestrator hands to {@link HistoryStore.record} when a shot
 * completes — everything that is *not* derivable from the series itself.
 */
export interface ShotCompletion {
	/** Total shot duration, milliseconds (from `Event::ShotCompleted`). */
	duration: number;
	/** The active profile's name at the time of the shot, or `null`. */
	profileName: string | null;
	/** The active profile's brew dose (grams) at the time of the shot, or `null`. */
	dose: number | null;
	/** The buffered telemetry series snapshotted at shot completion. */
	series: readonly TelemetrySample[];
	/** A snapshot of the current bean at shot completion, or `null`. */
	bean: ShotBean | null;
}

/** The reactive shot-history library. One instance per app — {@link getHistoryStore}. */
export class HistoryStore {
	/** The recorded shots, newest first. Loaded from localStorage. */
	private shots = $state<ShotRecord[]>(readJson<ShotRecord[]>(HISTORY_KEY, []));

	/** The full history, newest first. Reactive: a new record re-renders the list. */
	get all(): ShotRecord[] {
		return this.shots;
	}

	/** Look up one shot by id, or `undefined` if it is not in the history. */
	get(id: string): ShotRecord | undefined {
		return this.shots.find((s) => s.id === id);
	}

	/** Persist the shot list to localStorage. */
	private persist(): void {
		writeJson(HISTORY_KEY, this.shots);
	}

	/**
	 * Record a finished shot. Derives the peak / final metrics from the series,
	 * prepends the new record (newest first), caps the list and persists.
	 *
	 * A series with no samples — a shot that ended before any telemetry — is
	 * dropped: there is nothing to draw and nothing useful to keep. Returns the
	 * newly-created record (so the caller can key per-shot side effects like
	 * the IndexedDB capture store), or `null` when the shot was dropped.
	 */
	record(completion: ShotCompletion): ShotRecord | null {
		const series = completion.series;
		if (series.length === 0) return null;

		let peakWeight: number | null = null;
		let finalWeight: number | null = null;
		let peakPressure = 0;
		let peakTemp = 0;
		for (const s of series) {
			if (s.weight != null) {
				if (peakWeight == null || s.weight > peakWeight) peakWeight = s.weight;
				finalWeight = s.weight;
			}
			if (s.pressure > peakPressure) peakPressure = s.pressure;
			if (s.temp > peakTemp) peakTemp = s.temp;
		}

		const record: ShotRecord = {
			id: shotId(),
			completedAt: Date.now(),
			profileName: completion.profileName,
			duration: completion.duration,
			dose: completion.dose,
			peakWeight,
			finalWeight,
			peakPressure,
			peakTemp,
			series: [...series],
			bean: completion.bean,
			rating: 0,
			notes: ''
		};
		this.shots = [record, ...this.shots].slice(0, MAX_RECORDS);
		this.persist();
		return record;
	}

	/** Update a shot's tasting notes and persist. */
	setNotes(id: string, notes: string): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...this.shots[idx], notes },
			...this.shots.slice(idx + 1)
		];
		this.persist();
	}

	/** Update a shot's star rating (0–5) and persist. */
	setRating(id: string, rating: number): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const clamped = Math.max(0, Math.min(5, Math.round(rating)));
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...this.shots[idx], rating: clamped },
			...this.shots.slice(idx + 1)
		];
		this.persist();
	}

	/** Delete a recorded shot by id and persist. */
	delete(id: string): void {
		this.shots = this.shots.filter((s) => s.id !== id);
		this.persist();
	}
}

/** The process-wide singleton — one history shared by every route. */
let store: HistoryStore | undefined;

/** Get the shared {@link HistoryStore}, creating it on first call. */
export function getHistoryStore(): HistoryStore {
	if (!store) store = new HistoryStore();
	return store;
}
