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
import { shotId, snapshotFromBean, type ShotBean, type StoredShot } from './model';
import type { Bean, Roaster } from '$lib/bean';

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
	/**
	 * Initial shot-level tags at completion time. The orchestrator
	 * copies the active bean's `Bean.tags` into this slot at
	 * `ShotCompleted` so bean tags become shot tags — one source of
	 * truth, no later live-bean rewrite of history. Defaults to `[]`
	 * for shots pulled with no bean (or a tag-less bean).
	 */
	tags?: readonly string[];
	/**
	 * The equipment-level grinder model at shot-completion time —
	 * `settings.prefs.grinderModel.trim()` or `null` when the user
	 * hasn't set a default. Frozen on the shot so a later settings
	 * change can't rewrite history.
	 */
	grinderModel: string | null;
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
			grinderModel: completion.grinderModel,
			rating: 0,
			notes: '',
			tags: completion.tags ? [...completion.tags] : []
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

	/**
	 * Update a shot's equipment-level grinder-model override and persist.
	 * Pass `null` (or call with an empty string after trimming) to clear
	 * the override — the upload-time cascade then falls back to the
	 * settings default. Mirrors {@link setNotes}: a thin replace-and-
	 * persist, no validation beyond trim-to-null at the caller.
	 */
	setGrinderModel(id: string, grinderModel: string | null): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...this.shots[idx], grinderModel },
			...this.shots.slice(idx + 1)
		];
		this.persist();
	}

	/**
	 * Rebind (or clear) the bean snapshot on a shot. Mirrors {@link
	 * setRating} / {@link setNotes} / {@link setTags}: a thin replace-
	 * and-persist over the persisted row.
	 *
	 * Used by the shot-detail panel's "Change bean" / "Assign bean"
	 * affordance to backfill a bean onto a shot that completed without
	 * one (or correct a wrong one). The caller is responsible for
	 * producing the snapshot via {@link snapshotFromBean} so the shape
	 * matches what `app.svelte.ts` writes at completion time —
	 * `setBeanFromLive` below is the convenience that does that.
	 *
	 * Also re-snapshots `shot.tags` from the new bean's tags IF the
	 * shot has no user-set shot-level tags yet (`shot.tags.length === 0`).
	 * A shot that already carries user tags is left alone — the user's
	 * curation wins over the bean tag copy. Clearing the bean (`null`)
	 * does NOT clear `shot.tags` — bean removal shouldn't erase the
	 * user's curation either.
	 */
	setBeanFromLive(
		id: string,
		bean: Bean | null | undefined,
		roaster: Roaster | null | undefined
	): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const snapshot = snapshotFromBean(bean, roaster);
		const target = this.shots[idx];
		const existingTags = target.tags ?? [];
		const nextTags =
			existingTags.length === 0 && bean?.tags && bean.tags.length > 0
				? [...bean.tags]
				: existingTags;
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...target, bean: snapshot, tags: nextTags },
			...this.shots.slice(idx + 1)
		];
		this.persist();
	}

	/**
	 * Replace a shot's tag list and persist. Tags are normalised at the
	 * call site (trim + dedup case-sensitive); this setter is a thin
	 * pass-through so the sync layer can drop a remote-sourced tag list
	 * onto the local row during reconcile (LWW), the same way
	 * {@link setRating} / {@link setNotes} flow editable annotations.
	 */
	setTags(id: string, tags: string[]): void {
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...this.shots[idx], tags: [...tags] },
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
			// Imported legacy `.shot.json` files don't carry the
			// equipment-level grinder model — Crema's pre-#81 records
			// don't either. Leaving this `null` lets the upload-time
			// cascade fall back to the current settings default.
			grinderModel: null,
			rating: imported.metadata.rating ?? 0,
			notes: imported.metadata.notes ?? '',
			tags: []
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
 * Coerce a stored `bean` field on a persisted shot into a {@link ShotBean}
 * or `null`. Legacy records (pre-bean-snapshot) carry no `bean`; v1 records
 * carry only `{ roaster, type, roastedOn, roastLevel }`; post-inline-bean
 * records also carry `notes`, `grinderSetting`. Each extra field is read
 * defensively so a stored-shape regression cannot crash the History route.
 *
 * Older records may still carry persisted `tags` / `visualizerId` on the
 * snapshot from before the bean→shot tag copy and the coffee-bag-id live
 * lookup landed. We deliberately DROP those on read — `tags` now lives on
 * the shot row (`StoredShot.tags`), and the visualizer id is resolved
 * live via `resolveCoffeeBagId`. Leaving the old keys in memory would
 * tempt callers to read them again and re-introduce the snapshot-vs-live
 * leak we're closing here.
 */
function coerceShotBean(raw: unknown): ShotBean | null {
	if (typeof raw !== 'object' || raw === null) return null;
	const obj = raw as Record<string, unknown>;
	// Roaster + type are the bean's identity strings — both required (default
	// to '' so a partial record still reads); roastedOn / roastLevel are
	// nullable per the original v1 contract. Every other field is optional
	// and ShotBean's `readonly` modifier doesn't prevent assembling the
	// object literal in pieces, so build a plain mutable scratch object
	// and return it as `ShotBean`.
	const out: Record<string, unknown> = {
		roaster: typeof obj.roaster === 'string' ? obj.roaster : '',
		type: typeof obj.type === 'string' ? obj.type : '',
		roastedOn: typeof obj.roastedOn === 'string' ? obj.roastedOn : null,
		roastLevel: typeof obj.roastLevel === 'number' ? obj.roastLevel : null
	};
	if (typeof obj.beanId === 'string' || obj.beanId === null) out.beanId = obj.beanId;
	if (typeof obj.notes === 'string' && obj.notes.length > 0) out.notes = obj.notes;
	if (typeof obj.grinderSetting === 'string' && obj.grinderSetting.length > 0) {
		out.grinderSetting = obj.grinderSetting;
	}
	return out as unknown as ShotBean;
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
		// Defensive normalisation for the optional sync fields and the
		// post-v2 `tags` field — older records have none of them.
		// `tags` falls back to `[]`; non-string entries are filtered so a
		// corrupted store doesn't taint the in-memory list.
		const tagsRaw = obj.tags;
		const tags = Array.isArray(tagsRaw)
			? tagsRaw.filter((t): t is string => typeof t === 'string')
			: [];
		// `bean` is the inline-bean snapshot (notes, grinderSetting). Older
		// records without these fields still parse — `coerceShotBean` reads
		// each one defensively and omits it when absent.
		const bean = coerceShotBean(obj.bean ?? null);
		// `grinderModel` (added in #81) is the equipment-level snapshot.
		// Legacy records pre-#81 have no field at all — leave it `null`
		// so the upload-time cascade re-reads the current settings
		// default. A persisted empty string is normalised to `null` too,
		// so "no override" is one canonical shape.
		const grinderModelRaw = obj.grinderModel;
		const grinderModel =
			typeof grinderModelRaw === 'string' && grinderModelRaw.length > 0
				? grinderModelRaw
				: null;
		out.push({
			...(obj as unknown as StoredShot),
			bean,
			grinderModel,
			tags,
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
