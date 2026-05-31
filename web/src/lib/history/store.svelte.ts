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

import type { RustStoredShot, RustTimedSample } from '$lib/core';
import type { TelemetrySample } from '$lib/state';
import { toWire } from './telemetry-wire';
import { readJson, writeJson } from '$lib/utils/storage';
import {
	shotId,
	snapshotFromBean,
	type ShotBean,
	type ShotMetadata,
	type StoredShot
} from './model';
import type { Bean, Roaster } from '$lib/bean';

/** Current persisted-shot schema version — matches Rust's `STORED_SHOT_FORMAT_VERSION`. */
const STORED_SHOT_FORMAT_VERSION = 3;

// `toWire` (the TelemetrySample → RustTimedSample mapper) lives in
// `./telemetry-wire` alongside its inverse `fromWire`. A round-trip
// test pins the two as a forward/inverse pair so a new sample channel
// can't drift between record and read paths.

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
	/**
	 * Quick-control overrides — the `BrewParams` user dialed in for this
	 * shot, snapshotted at completion time so a later dial change cannot
	 * rewrite history. Each field optional: a headless caller (replay /
	 * scripted shot) may have no live Quick Sheet, and a legacy completion
	 * before this snapshot existed simply leaves them absent. The history
	 * store mirrors them onto {@link StoredShot.yieldTarget} et al.
	 *
	 * Why not just read from the active profile? The QC dial decouples a
	 * shot's user intent from the profile's bundled defaults (e.g. "this
	 * shot uses Best Practice but I'm aiming for 40 g not the profile's
	 * default 36"). The profile default lives in the embedded profile slot
	 * via `exportStoredShotAsV2Json`; these are the user's *current*
	 * overrides, sibling to the live QC fingerprint.
	 */
	yieldTarget?: number | null;
	brewTemp?: number | null;
	preinfuseTarget?: number | null;
	stopOnWeight?: boolean;
	autoTare?: boolean;
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
		const samples = series.map(toWire);
		const metadata: ShotMetadata = {
			dose: completion.dose ?? null,
			rating: null,
			notes: null
		};
		const record: StoredShot = {
			formatVersion: STORED_SHOT_FORMAT_VERSION,
			id: shotId(),
			completedAt: Date.now(),
			profileName: completion.profileName,
			metadata,
			record: {
				duration: completion.duration,
				samples
			},
			bean: completion.bean,
			grinderModel: completion.grinderModel,
			yieldTarget: completion.yieldTarget ?? null,
			brewTempTarget: completion.brewTemp ?? null,
			preinfuseTarget: completion.preinfuseTarget ?? null,
			stopOnWeight: completion.stopOnWeight,
			autoTare: completion.autoTare,
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
		const target = this.shots[idx];
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...target, metadata: { ...target.metadata, notes } },
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
		const target = this.shots[idx];
		this.shots = [
			...this.shots.slice(0, idx),
			{ ...target, metadata: { ...target.metadata, rating: clamped } },
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
	 * Backfill telemetry onto a shot that was pulled as a metadata-only
	 * stub (empty `record.samples`) before telemetry import existed.
	 * Called by the sync reconciler when a later pull carries the curve
	 * for an already-local shot, so old stubs gain a graph on the next
	 * Sync without a manual delete + re-pull.
	 *
	 * No-op when the incoming series is empty or the local shot already
	 * has samples — never clobber a locally-recorded curve with a pulled
	 * one.
	 */
	backfillTelemetry(id: string, samples: readonly RustTimedSample[], durationMs: number): void {
		if (samples.length === 0) return;
		const idx = this.shots.findIndex((s) => s.id === id);
		if (idx < 0) return;
		const target = this.shots[idx];
		if (target.record.samples.length > 0) return;
		this.shots = [
			...this.shots.slice(0, idx),
			{
				...target,
				record: {
					duration: durationMs || target.record.duration,
					samples: [...samples]
				}
			},
			...this.shots.slice(idx + 1)
		];
		this.persist();
	}

	/**
	 * Adopt a shot the user imported from a legacy de1app `.shot` or
	 * modern `.shot.json` file. The Rust core does the
	 * parsing — the shell maps the Rust-shape `StoredShot` onto Crema's
	 * own `StoredShot` and prepends it like a freshly-recorded shot.
	 *
	 * `extras` carries the Crema-only fields that ride in
	 * `metadata.crema.*` of the exported `.shot.json` and are not part
	 * of the v2 schema the Rust core models — `grinderModel`, `tags`,
	 * inline-bean snapshot extras (`roastedOn`, `roastLevel`, `notes`),
	 * and the QC-override snapshot (`yieldTarget` / `brewTemp` /
	 * `preinfuseTarget` / `stopOnWeight` / `autoTare`). The shell's
	 * `importShotFile` extracts the block before handing the JSON to
	 * the wasm parser; legacy `.shot` (TCL) imports and pre-this-PR
	 * `.shot.json` files have no extras and pass `undefined`.
	 *
	 * `null` if the imported record carries no telemetry samples (the
	 * UI treats this as a "couldn't parse" error and toasts).
	 */
	addImported(imported: RustStoredShot, extras?: ImportExtras): StoredShot | null {
		if (imported.record.samples.length === 0) return null;
		const bean = beanFromImported(
			imported.metadata.beans ?? null,
			imported.metadata.grinderSetting ?? null,
			extras?.bean ?? null
		);
		const record: StoredShot = {
			formatVersion: STORED_SHOT_FORMAT_VERSION,
			id: shotId(),
			completedAt: imported.completedAt,
			profileName: imported.profile?.title ?? null,
			profile: imported.profile ?? null,
			metadata: {
				...imported.metadata,
				notes: imported.metadata.notes ?? null,
				rating: imported.metadata.rating ?? null
			},
			record: imported.record,
			bean,
			grinderModel: extras?.grinderModel ?? null,
			yieldTarget: extras?.yieldTarget ?? null,
			brewTempTarget: extras?.brewTemp ?? null,
			preinfuseTarget: extras?.preinfuseTarget ?? null,
			stopOnWeight: extras?.stopOnWeight,
			autoTare: extras?.autoTare,
			tags: extras?.tags ? [...extras.tags] : []
		};
		this.shots = [record, ...this.shots].slice(0, MAX_RECORDS);
		this.persist();
		return record;
	}
}

/**
 * Map the Rust `record.samples` array onto Crema's `TelemetrySample`
 * shape. Sample-time / steam-temp survive verbatim; the goal series
 * folds into the `setHead*` channels so the LiveChart's dashed-goal
 * overlay works on imports too.
 */
function mapTimedSamples(
	samples: readonly RustStoredShot['record']['samples'][number][]
): TelemetrySample[] {
	return samples.map((t) => {
		// Overlay channels — the Rust importer carries them when the
		// source `.shot.json` recorded them; pre-PR exports emit zeros
		// (the v2 schema requires same-length columns). A literal `0`
		// from a no-scale shot is indistinguishable from "no value" on
		// the wire, so we treat a sub-floor / sub-noise sample as a gap
		// for the resistance fallback path — the chart auto-switch
		// uses per-sample `resistanceWeight ?? resistance`, and an
		// absent value cleanly defers to the DE1-flow sibling.
		const sw = t.scaleWeight;
		const sfw = t.scaleFlowWeight;
		const dv = t.dispensedVolume;
		const r = t.resistance;
		const rw = t.resistanceWeight;
		return {
			elapsed: t.elapsed,
			pressure: t.sample.groupPressure,
			flow: t.sample.groupFlow,
			temp: t.sample.headTemp,
			mixTemp: t.sample.mixTemp,
			steamTemp: t.sample.steamTemp,
			// Imported weight: present whenever the source file recorded
			// a non-trivial scale series; a uniformly-zero placeholder
			// (no scale paired) leaves the channel `null` so the chart
			// renders a gap rather than a flat zero.
			weight: sw != null && sw > 0 ? sw : null,
			weightFlow: sfw != null && sfw > 0 ? sfw : null,
			dispensedVolume: dv != null && dv > 0 ? dv : 0,
			// Re-derive the DE1-flow resistance per-sample when the
			// imported value is missing — legacy `.shot` files without
			// the field, or pre-PR v2 exports.
			resistance:
				r != null && r > 0
					? r
					: t.sample.groupFlow > 0.05
						? t.sample.groupPressure / (t.sample.groupFlow * t.sample.groupFlow)
						: null,
			resistanceWeight: rw != null && rw > 0 ? rw : null,
			// The legacy log records one `temperature_goal` per sample —
			// fan it into `setHeadTemp` (the chart's dashed overlay channel)
			// per `applyEvent` in ui-state.svelte.ts.
			setHeadTemp: t.sample.setHeadTemp,
			setGroupPressure: t.sample.setGroupPressure,
			setGroupFlow: t.sample.setGroupFlow
		};
	});
}

/**
 * Split the Rust importer's combined `"brand · type"` string back into
 * a `ShotBean` so the History card / detail panel can display it. The
 * legacy log carries no roast-date or roast-level on the bean side, so
 * those stay null when no structured `extras` ride alongside.
 *
 * Post-this-PR `.shot.json` exports carry the richer ShotBean snapshot
 * (roastedOn, roastLevel, notes) under `metadata.crema.bean_*`; when
 * the caller has parsed those out they ride here so the import keeps
 * the same shape the live capture produced. `grinderSetting` comes
 * from the v2 schema's `metadata.grinder.setting` slot.
 */
function beanFromImported(
	label: string | null,
	grinderSetting: string | null,
	extras: ImportBeanExtras | null
): ShotBean | null {
	const hasExtras = extras !== null && (extras.roastedOn !== undefined
		|| extras.roastLevel !== undefined
		|| extras.notes !== undefined);
	if (!label && !hasExtras && !grinderSetting) return null;
	let roaster = '';
	let type = '';
	if (label) {
		const trimmed = label.trim();
		if (trimmed) {
			const parts = trimmed.split('·').map((p) => p.trim()).filter(Boolean);
			roaster = parts[0] ?? trimmed;
			type = parts[1] ?? '';
		}
	}
	const out: Record<string, unknown> = {
		name: type,
		roasterName: roaster.length > 0 ? roaster : null,
		roastedOn: extras?.roastedOn ?? null,
		roastLevel: extras?.roastLevel ?? null
	};
	if (extras?.notes && extras.notes.length > 0) out.notes = extras.notes;
	if (grinderSetting && grinderSetting.length > 0) out.grinderSetting = grinderSetting;
	return out as unknown as ShotBean;
}

/**
 * Structured `metadata.crema.bean_*` slots a Crema-exported `.shot.json`
 * carries — the ShotBean fields the v2 schema's bean object can't model
 * (`roastedOn` / `roastLevel`) plus `notes`. All optional; `undefined`
 * means "absent in the source file", distinct from a parsed `null`
 * (which means "explicitly empty").
 */
export interface ImportBeanExtras {
	readonly roastedOn?: string | null;
	readonly roastLevel?: number | null;
	readonly notes?: string;
}

/**
 * The full `metadata.crema.*` block of a Crema-exported `.shot.json`,
 * extracted by `importShotFile` before the wasm parser strips it. Each
 * field optional; absent means "not in the source" and downstream
 * fall-backs apply (legacy / pre-PR `.shot.json` exports omit the whole
 * block).
 */
export interface ImportExtras {
	readonly grinderModel?: string | null;
	readonly tags?: readonly string[];
	readonly yieldTarget?: number | null;
	readonly brewTemp?: number | null;
	readonly preinfuseTarget?: number | null;
	readonly stopOnWeight?: boolean;
	readonly autoTare?: boolean;
	readonly bean?: ImportBeanExtras | null;
}

/**
 * Coerce a stored `bean` field on a persisted shot into a {@link ShotBean}
 * or `null`. Legacy records (pre-bean-snapshot) carry no `bean`; v1 records
 * carry only `{ roaster, type, roastedOn, roastLevel }`; post-inline-bean
 * records also carry `notes`, `grinderSetting`. Each extra field is read
 * defensively so a stored-shape regression cannot crash the History route.
 *
 * Older records may still carry a persisted `visualizerId` on the snapshot
 * from before the coffee-bag-id live lookup landed. We deliberately DROP that
 * on read — the visualizer id is resolved live via `resolveCoffeeBagId`, and
 * leaving the stale key in memory would tempt callers to read it again and
 * re-introduce the snapshot-vs-live leak we're closing here. The snapshot's
 * `tags`, by contrast, ARE kept — they're the bean's tags frozen at shot time
 * (see {@link ShotBean.tags}), distinct from the mutable `StoredShot.tags`.
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
	// Accept both the new wire-shape field names (`name`/`roasterName`)
	// and the legacy v2 shape (`type`/`roaster`) so localStorage records
	// written before Task #21 upcast cleanly on first load. The legacy
	// keys are dropped from `out` so re-saved rows are clean.
	const legacyType = typeof obj.type === 'string' ? obj.type : null;
	const legacyRoaster = typeof obj.roaster === 'string' ? obj.roaster : null;
	const name = typeof obj.name === 'string' ? obj.name : (legacyType ?? '');
	const roasterName =
		typeof obj.roasterName === 'string' ? obj.roasterName : legacyRoaster;
	const out: Record<string, unknown> = {
		name,
		roasterName,
		roastedOn: typeof obj.roastedOn === 'string' ? obj.roastedOn : null,
		roastLevel: typeof obj.roastLevel === 'number' ? obj.roastLevel : null
	};
	if (typeof obj.beanId === 'string' || obj.beanId === null) out.beanId = obj.beanId;
	if (Array.isArray(obj.tags)) {
		out.tags = obj.tags.filter((t): t is string => typeof t === 'string');
	}
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
		const migrated = coerceStoredShot(obj);
		if (migrated) out.push(migrated);
	}
	return out;
}

/**
 * Upcast a persisted shot row to the v3 wire shape.
 *
 * v3 records pass through untouched (already `{ formatVersion: 3,
 * metadata, record, … }`). v2 records (denormalized, with top-level
 * `dose` / `rating` / `notes` / `series` / `duration` / `peakWeight`
 * / `brewTemp`) are folded into the wire shape:
 *
 *  - `dose` / `rating` / `notes` move under `metadata`.
 *  - `series` (`TelemetrySample[]`) becomes `record.samples`
 *    (`RustTimedSample[]`) via `toWire` from `./telemetry-wire`.
 *  - `duration` moves under `record.duration`.
 *  - `brewTemp` renames to `brewTempTarget`.
 *  - The pre-derived peaks are dropped — re-derived on demand by
 *    {@link peaksOf}.
 *
 * Returns `null` only when the record is too corrupt to recover.
 */
function coerceStoredShot(obj: Record<string, unknown>): StoredShot | null {
	const tagsRaw = obj.tags;
	const tags = Array.isArray(tagsRaw)
		? tagsRaw.filter((t): t is string => typeof t === 'string')
		: [];
	const bean = coerceShotBean(obj.bean ?? null);
	const grinderModelRaw = obj.grinderModel;
	const grinderModel =
		typeof grinderModelRaw === 'string' && grinderModelRaw.length > 0
			? grinderModelRaw
			: null;
	const yieldTarget =
		typeof obj.yieldTarget === 'number' && Number.isFinite(obj.yieldTarget)
			? obj.yieldTarget
			: null;
	// v3 wire records carry `brewTempTarget`; v2 carried `brewTemp`.
	const brewTempRaw =
		typeof obj.brewTempTarget === 'number' && Number.isFinite(obj.brewTempTarget)
			? (obj.brewTempTarget as number)
			: typeof obj.brewTemp === 'number' && Number.isFinite(obj.brewTemp)
				? (obj.brewTemp as number)
				: null;
	const preinfuseTarget =
		typeof obj.preinfuseTarget === 'number' && Number.isFinite(obj.preinfuseTarget)
			? obj.preinfuseTarget
			: null;
	const stopOnWeight = typeof obj.stopOnWeight === 'boolean' ? obj.stopOnWeight : undefined;
	const autoTare = typeof obj.autoTare === 'boolean' ? obj.autoTare : undefined;
	const visualizerId = typeof obj.visualizerId === 'string' ? obj.visualizerId : null;
	const deletedAt = typeof obj.deletedAt === 'number' ? obj.deletedAt : null;

	// v3 wire records carry `metadata` + `record`. v2 records have
	// top-level `dose` / `rating` / `notes` + `series` / `duration`.
	const isV3 =
		typeof obj.formatVersion === 'number' &&
		obj.formatVersion >= 3 &&
		typeof obj.metadata === 'object' &&
		obj.metadata !== null &&
		typeof obj.record === 'object' &&
		obj.record !== null;

	let metadata: ShotMetadata;
	let recordField: StoredShot['record'];
	if (isV3) {
		metadata = (obj.metadata ?? {}) as ShotMetadata;
		recordField = (obj.record ?? { duration: 0, samples: [] }) as StoredShot['record'];
	} else {
		// v2 migration path
		const legacySeries = Array.isArray(obj.series) ? (obj.series as TelemetrySample[]) : [];
		const samples = legacySeries.map(toWire);
		const duration =
			typeof obj.duration === 'number' && Number.isFinite(obj.duration) ? obj.duration : 0;
		recordField = { duration, samples };
		metadata = {
			dose:
				typeof obj.dose === 'number' && Number.isFinite(obj.dose) ? (obj.dose as number) : null,
			rating:
				typeof obj.rating === 'number' && Number.isFinite(obj.rating)
					? (obj.rating as number)
					: null,
			notes: typeof obj.notes === 'string' ? (obj.notes as string) : null
		};
	}

	return {
		formatVersion: STORED_SHOT_FORMAT_VERSION,
		id: obj.id as string,
		// GEN4: 0 / non-finite / negative completedAt is a corrupt record that
		// would render as a 1970 epoch row; fall back to "now" (re-save heals it).
		completedAt:
			typeof obj.completedAt === 'number' && Number.isFinite(obj.completedAt) && obj.completedAt > 0
				? (obj.completedAt as number)
				: Date.now(),
		profileName: typeof obj.profileName === 'string' ? (obj.profileName as string) : null,
		profile: (obj.profile as unknown) ?? null,
		stopReason: (obj.stopReason as unknown) ?? null,
		metadata,
		record: recordField,
		bean,
		grinderModel,
		tags,
		yieldTarget,
		brewTempTarget: brewTempRaw,
		preinfuseTarget,
		stopOnWeight,
		autoTare,
		visualizerId,
		deletedAt
	};
}

/** The process-wide singleton — one history shared by every route. */
let store: HistoryStore | undefined;

/** Get the shared {@link HistoryStore}, creating it on first call. */
export function getHistoryStore(): HistoryStore {
	if (!store) store = new HistoryStore();
	return store;
}
