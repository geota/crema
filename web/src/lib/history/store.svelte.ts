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

import type { RustStoredShot } from '$lib/core';
import type { TelemetrySample } from '$lib/state';
import { readJson, writeJson } from '$lib/utils/storage';
import { shotId, type ShotBean, type StoredShot } from './model';

/**
 * localStorage key for the recorded shots (a `StoredShot[]`, newest first).
 *
 * Bumped to `v2` when the in-record field names dropped their unit suffixes
 * (`durationMs` → `duration`, `peakWeightG` → `peakWeight`, etc.); v1 records
 * are simply ignored on first read rather than migrated.
 */
const HISTORY_KEY = 'crema.history.v2';

/**
 * Cap on the stored history. A `StoredShot` carries its full telemetry series
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
	private shots = $state<StoredShot[]>(readJson<StoredShot[]>(HISTORY_KEY, []));

	/** The full history, newest first. Reactive: a new record re-renders the list. */
	get all(): StoredShot[] {
		return this.shots;
	}

	/** Look up one shot by id, or `undefined` if it is not in the history. */
	get(id: string): StoredShot | undefined {
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
	record(completion: ShotCompletion): StoredShot | null {
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

		const record: StoredShot = {
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

	/**
	 * Adopt a shot the user imported from a legacy de1app `.shot` or
	 * modern `.shot.json` file (docs/22 §5.1). The Rust core does the
	 * parsing — the shell maps the Rust-shape `StoredShot` onto Crema's
	 * own `StoredShot` and prepends it like a freshly-recorded shot.
	 *
	 * `null` if the imported record carries no telemetry samples (the
	 * UI treats this as a "couldn't parse" error and toasts).
	 */
	addImported(imported: RustStoredShot): StoredShot | null {
		const series = mapTimedSamples(imported.record.samples);
		if (series.length === 0) return null;

		let peakWeight: number | null = null;
		let peakPressure = 0;
		let peakTemp = 0;
		for (const s of series) {
			if (s.weight != null && (peakWeight == null || s.weight > peakWeight)) {
				peakWeight = s.weight;
			}
			if (s.pressure > peakPressure) peakPressure = s.pressure;
			if (s.temp > peakTemp) peakTemp = s.temp;
		}

		const finalWeight = imported.metadata.yield_out ?? peakWeight;
		const bean = beanFromImported(imported.metadata.beans ?? null);
		const record: StoredShot = {
			id: shotId(),
			completedAt: imported.recorded_at,
			profileName: imported.profile?.title ?? null,
			duration: durationToMs(imported.record.duration),
			dose: imported.metadata.dose ?? null,
			peakWeight,
			finalWeight,
			peakPressure,
			peakTemp,
			series,
			bean,
			rating: imported.metadata.rating ?? 0,
			notes: imported.metadata.notes ?? ''
		};
		this.shots = [record, ...this.shots].slice(0, MAX_RECORDS);
		this.persist();
		return record;
	}
}

/**
 * Translate a Rust `Duration { secs, nanos }` into plain milliseconds —
 * Crema's TS shot records store every elapsed value as `ms` for parity
 * with `performance.now()` math.
 */
function durationToMs(d: { secs: number; nanos: number }): number {
	return d.secs * 1000 + d.nanos / 1_000_000;
}

/**
 * Map the Rust `record.samples` array onto Crema's `TelemetrySample`
 * shape. Sample-time / steam-temp survive verbatim; the goal series
 * folds into the `setHead*` channels so the LiveChart's dashed-goal
 * overlay works on imports too.
 */
function mapTimedSamples(
	samples: readonly { elapsed: { secs: number; nanos: number }; sample: RustStoredShot['record']['samples'][number]['sample'] }[]
): TelemetrySample[] {
	return samples.map((t) => ({
		elapsed: durationToMs(t.elapsed),
		pressure: t.sample.group_pressure,
		flow: t.sample.group_flow,
		temp: t.sample.head_temp,
		mixTemp: t.sample.mix_temp,
		steamTemp: t.sample.steam_temp,
		// Legacy logs have no scale weight; the importer leaves the
		// channel `null` so the chart's weight series renders blank.
		weight: null,
		// Resistance is derived (pressure / flow²); `null` near zero
		// flow. The chart re-derives at render time so we can skip
		// here and leave it null.
		resistance:
			t.sample.group_flow > 0.05
				? t.sample.group_pressure / (t.sample.group_flow * t.sample.group_flow)
				: null,
		// The legacy log records one `temperature_goal` per sample —
		// fan it into `setHeadTemp` (the chart's dashed overlay channel)
		// per `applyEvent` in ui-state.svelte.ts.
		setHeadTemp: t.sample.set_head_temp,
		setGroupPressure: t.sample.set_group_pressure,
		setGroupFlow: t.sample.set_group_flow
	}));
}

/**
 * Split the Rust importer's combined `"brand · type"` string back into
 * a `ShotBean` so the History card / detail panel can display it. The
 * legacy log carries no roast-date or roast-level on the bean side, so
 * those stay null.
 */
function beanFromImported(label: string | null): ShotBean | null {
	if (!label) return null;
	const trimmed = label.trim();
	if (!trimmed) return null;
	const parts = trimmed.split('·').map((p) => p.trim()).filter(Boolean);
	const roaster = parts[0] ?? trimmed;
	const type = parts[1] ?? '';
	return { roaster, type, roastedOn: null, roastLevel: null };
}

/** The process-wide singleton — one history shared by every route. */
let store: HistoryStore | undefined;

/** Get the shared {@link HistoryStore}, creating it on first call. */
export function getHistoryStore(): HistoryStore {
	if (!store) store = new HistoryStore();
	return store;
}
