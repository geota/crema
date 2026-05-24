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
 *
 * Peak / final metrics ride on `Event::ShotCompleted` itself: the core's
 * `ShotMetricsAccumulator` tracks them in real time, so the live path no
 * longer re-iterates the buffered series for them.
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
	/** Peak group pressure observed during the shot, bar — from the core. */
	peakPressure: number | null;
	/** Peak group-head temperature observed during the shot, °C — from the core. */
	peakTemp: number | null;
	/** Peak scale weight observed during the shot, grams — from the core; `null` when no scale. */
	peakWeight: number | null;
	/** Final scale weight at shot end, grams — from the core; `null` when no scale. */
	finalWeight: number | null;
}

/** The reactive shot-history library. One instance per app — {@link getHistoryStore}. */
export class HistoryStore {
	/** The recorded shots, newest first. Loaded from localStorage. */
	private shots = $state.raw<StoredShot[]>(loadShots());

	/**
	 * The user-visible history, newest first. Tombstones (`deletedAt`
	 * set) are filtered out — they only exist for the sync layer to push
	 * a remote DELETE. Reactive: a new record re-renders the list.
	 */
	get all(): StoredShot[] {
		return this.shots.filter((s) => s.deletedAt == null);
	}

	/**
	 * Every stored shot including soft-deleted tombstones. The sync layer
	 * reads this so DELETEs can propagate; the UI must use {@link all}.
	 */
	get rawAll(): StoredShot[] {
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
	 * Record a finished shot. The peak / final metrics ride on the
	 * `Event::ShotCompleted` payload — the core's `ShotMetricsAccumulator`
	 * tracks them in real time — so the shell no longer re-iterates the
	 * buffered series for them here. Prepends the new record (newest first),
	 * caps the list and persists.
	 *
	 * A series with no samples — a shot that ended before any telemetry — is
	 * dropped: there is nothing to draw and nothing useful to keep. Returns the
	 * newly-created record (so the caller can key per-shot side effects like
	 * the IndexedDB capture store), or `null` when the shot was dropped.
	 */
	record(completion: ShotCompletion): StoredShot | null {
		const series = completion.series;
		if (series.length === 0) return null;

		const record: StoredShot = {
			id: shotId(),
			completedAt: Date.now(),
			profileName: completion.profileName,
			duration: completion.duration,
			dose: completion.dose,
			peakWeight: completion.peakWeight,
			finalWeight: completion.finalWeight,
			// `peakPressure` / `peakTemp` are non-nullable on `StoredShot`;
			// fall back to `0` when no telemetry arrived (matches the empty-
			// series behaviour of the previous re-derivation).
			peakPressure: completion.peakPressure ?? 0,
			peakTemp: completion.peakTemp ?? 0,
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

	/**
	 * Soft-delete a recorded shot by id and persist. The row stays in
	 * storage with `deletedAt` stamped so the next Visualizer sync can
	 * push a DELETE for `visualizerId` and garbage-collect the
	 * tombstone after the remote acknowledges. The History UI filters
	 * tombstones out via {@link all}.
	 *
	 * If the shot has no `visualizerId` (never uploaded), the row is
	 * removed outright — there is nothing to propagate to a remote.
	 */
	delete(id: string): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const target = this.shots[idx];
		if (!target.visualizerId) {
			// Never synced — hard delete is safe.
			this.shots = this.shots.filter((s) => s.id !== id);
		} else {
			this.shots = [
				...this.shots.slice(0, idx),
				{ ...target, deletedAt: Date.now() },
				...this.shots.slice(idx + 1)
			];
		}
		this.persist();
	}

	/**
	 * Hard-remove a shot from storage. Called by the sync layer once
	 * the remote DELETE has succeeded for a tombstoned row, so the
	 * tombstone doesn't bloat localStorage forever.
	 */
	purgeTombstone(id: string): void {
		this.shots = this.shots.filter((s) => s.id !== id);
		this.persist();
	}

	/**
	 * Stamp the Visualizer remote id onto a local shot — called by the
	 * sync layer once an upload returns the remote `id`. Persists, so a
	 * reload sees the binding.
	 */
	bindVisualizerId(id: string, visualizerId: string): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...this.shots[idx], visualizerId },
			...this.shots.slice(idx + 1)
		];
		this.persist();
	}

	/**
	 * Insert a shot pulled from Visualizer. Caller has already mapped
	 * the wire row onto a `StoredShot` shape (with `visualizerId` set)
	 * and verified de-dup. Prepends newest-first and persists.
	 */
	insertPulled(shot: StoredShot): void {
		this.shots = [shot, ...this.shots].slice(0, MAX_RECORDS);
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

/**
 * Read the persisted history and coerce each row through a defensive
 * normaliser so legacy records without `visualizerId` / `deletedAt`
 * still parse cleanly (the fields are optional on the type; absent
 * keys leave the rows in their "never synced, never deleted" default).
 */
function loadShots(): StoredShot[] {
	const raw = readJson<unknown[]>(HISTORY_KEY, []);
	if (!Array.isArray(raw)) return [];
	const out: StoredShot[] = [];
	for (const item of raw) {
		if (typeof item !== 'object' || item === null) continue;
		const obj = item as Record<string, unknown>;
		if (typeof obj.id !== 'string') continue;
		// Trust the shape for the existing fields — they have been
		// stable through v2 — and only normalise the new sync fields
		// so missing keys become `null`, not `undefined`.
		out.push({
			...(obj as unknown as StoredShot),
			visualizerId: typeof obj.visualizerId === 'string' ? obj.visualizerId : null,
			deletedAt: typeof obj.deletedAt === 'number' ? obj.deletedAt : null
		});
	}
	return out;
}

/** The process-wide singleton — one history shared by every route. */
let store: HistoryStore | undefined;

/** Get the shared {@link HistoryStore}, creating it on first call. */
export function getHistoryStore(): HistoryStore {
	if (!store) store = new HistoryStore();
	return store;
}
