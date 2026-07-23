/**
 * `$lib/history/v2-export` — emit a Crema {@link StoredShot} as a
 * community-v2 `.shot.json` document.
 *
 * The shape, the field semantics, the `enjoyment` mapping and the
 * sample-aligned parallel arrays all live in core
 * (`de1_domain::history_export`) — symmetric with the existing
 * `import_v2_json_shot` parser, with the round-trip exercised by
 * core tests. This module is a one-step adapter: it maps the
 * shell-side {@link StoredShot} onto the Rust `StoredShot` shape and
 * hands it to the wasm export.
 *
 * Crema-only fields the community v2 schema does not model — the
 * inline-bean snapshot extras (`roastedOn` / `roastLevel` / `notes`),
 * the equipment-level `grinderModel`, shot-level `tags`, and the
 * Quick-control overrides (`yieldTarget` / `brewTemp` /
 * `preinfuseTarget` / `stopOnWeight` / `autoTare`) — ride alongside in
 * a sibling `metadata.crema.*` block — the documented escape valve
 * (shot-sync's Visualizer payload uses the same key for `localId` /
 * `signature` / `appVersion`). Visualizer's `additionalProperties:
 * true` schema accepts unknown keys so the same JSON drives both the
 * downloaded archive and the upload path.
 */

import { export_v2_json_shot } from '$lib/wasm/de1_wasm';
import type { Profile, RustStoredShot } from '$lib/core';
import { peaksOf, type StoredShot } from './model';

/**
 * Emit a {@link StoredShot} as a pretty-printed community-v2
 * `.shot.json` string suitable for download / sharing.
 *
 * The bean snapshot rides two slots: the legacy `metadata.beans`
 * carries the flat `"roaster · type"` label for v2 consumers, while
 * the Crema escape valve (`metadata.crema.bean_*`) carries the
 * richer ShotBean fields (`roastedOn`, `roastLevel`, `notes`) so a
 * Crema → Crema round-trip preserves everything the shell modeled.
 * `grinderSetting` rides the existing `metadata.grinder.setting` v2
 * slot — same wire field Visualizer's `grinder_setting` reads.
 */
export function exportStoredShotAsV2Json(shot: StoredShot): string {
	const baseJson = export_v2_json_shot(JSON.stringify(toRustStoredShot(shot)));
	const crema = cremaExtras(shot);
	if (crema === null) return baseJson;
	// The wasm exporter produces a top-level JSON object; parse + augment
	// it with the escape-valve block. The cost (one extra JSON parse +
	// stringify) is paid only on download / upload, not on every shot
	// completion. Pretty-print to match the exporter's own format so the
	// downloaded file stays human-readable.
	let doc: Record<string, unknown>;
	try {
		doc = JSON.parse(baseJson) as Record<string, unknown>;
	} catch {
		// If the wasm output somehow fails to parse, prefer the raw bytes
		// over throwing — a downloaded archive missing the crema block
		// is strictly better than no archive at all.
		return baseJson;
	}
	const existing = (doc.metadata as Record<string, unknown> | undefined) ?? {};
	doc.metadata = { ...existing, crema };
	return JSON.stringify(doc, null, 2);
}

/**
 * Build the `metadata.crema.*` block — every field optional, omitted
 * when its source is absent so the persisted shape stays minimal and
 * a re-importer can distinguish "not captured" from "explicit zero".
 * Returns `null` when the shot has nothing to add (no extras, no
 * QC snapshot, no grinder model, no tags, no bean snapshot extras) —
 * the export then matches the legacy pre-this-PR shape byte-for-byte.
 */
function cremaExtras(shot: StoredShot): Record<string, unknown> | null {
	const out: Record<string, unknown> = {};
	if (shot.grinderModel) out.grinder_model = shot.grinderModel;
	if (shot.tags && shot.tags.length > 0) out.tags = [...shot.tags];
	if (shot.yieldTarget != null) out.yield_target = shot.yieldTarget;
	if (shot.brewTempTarget != null) out.brew_temp_target = shot.brewTempTarget;
	if (shot.preinfuseTarget != null) out.preinfuse_target = shot.preinfuseTarget;
	if (shot.stopOnWeight !== undefined) out.stop_on_weight = shot.stopOnWeight;
	if (shot.autoTare !== undefined) out.auto_tare = shot.autoTare;
	const bean = shot.bean;
	if (bean) {
		if (bean.roastedOn) out.bean_roasted_on = bean.roastedOn;
		if (bean.roastLevel != null) out.bean_roast_level = bean.roastLevel;
		if (bean.notes && bean.notes.length > 0) out.bean_notes = bean.notes;
	}
	return Object.keys(out).length > 0 ? out : null;
}

/** Build the Rust-shape `StoredShot` the wasm exporter consumes. The
   wire format is camelCase — the Rust struct tree (StoredShot,
   ShotMetadata, ShotRecord, TimedSample, ShotSample) all carry
   `#[serde(rename_all = "camelCase")]`. Only the embedded `Profile`
   keeps snake_case (its struct has no rename_all). */
function toRustStoredShot(shot: StoredShot): RustStoredShot {
	const peaks = peaksOf(shot);
	const finalYield = peaks.finalWeight ?? peaks.peakWeight ?? null;
	return {
		formatVersion: shot.formatVersion,
		completedAt: shot.completedAt,
		// Prefer the recipe snapshotted at capture (#12) so the export carries the
		// real steps; fall back to a name-only stub for legacy / pulled shots that
		// never captured one.
		profile:
			(shot.profile as Profile | null) ??
			profileFromName(shot.profileName, finalYield, shot.metadata.dose ?? null),
		stopReason: shot.stopReason ?? null,
		metadata: {
			dose: shot.metadata.dose ?? null,
			yieldOut: shot.metadata.yieldOut ?? finalYield,
			beans: beanLabel(shot),
			// Bean-scoped grinder click setting — the v2 schema's
			// `metadata.grinder.setting` slot. The equipment-level
			// `grinderModel` (e.g. "Niche Zero") rides in
			// `metadata.crema.grinder_model` instead.
			grinderSetting: shot.metadata.grinderSetting ?? shot.bean?.grinderSetting ?? null,
			notes:
				shot.metadata.notes && shot.metadata.notes.length > 0
					? shot.metadata.notes
					: null,
			rating:
				shot.metadata.rating != null && shot.metadata.rating > 0
					? shot.metadata.rating
					: null,
			tds: shot.metadata.tds ?? null,
			extractionYield: shot.metadata.extractionYield ?? null
		},
		// The shell's `StoredShot.record` is already the Rust wire shape
		// (camelCase, ms `elapsed`, nested `sample`). No conversion needed.
		record: {
			duration: Math.max(0, Math.round(shot.record.duration)),
			samples: shot.record.samples.map((s) => ({
				...s,
				elapsed: Math.max(0, Math.round(s.elapsed))
			}))
		},
		// The structured snapshot + equipment grinder feed the exporter's
		// `meta.bean` and `app.data.settings.*` journal fields (issue #44 —
		// the upload document itself now carries the bean info Visualizer
		// captures at parse time; the flat label above stays for legacy v2
		// consumers). `tags: null` normalises to absent — the Rust `Vec`
		// field defaults on a missing key but rejects an explicit null.
		bean: shot.bean ? { ...shot.bean, tags: shot.bean.tags ?? undefined } : null,
		grinderModel: shot.grinderModel ?? null
	};
}

/**
 * The flat `"roaster · type"` label the Rust core stores for a bean.
 * The shell's structured {@link ShotBean} is richer than the core's
 * `metadata.beans` — `roastedOn` / `roastLevel` / `notes` ride in the
 * sibling `metadata.crema.*` block instead. On the import side the
 * shell splits this label back via `beanFromImported` (paired with
 * the extras block) so brand + type + the extras all round-trip.
 */
function beanLabel(shot: StoredShot): string | null {
	const bean = shot.bean;
	if (!bean) return null;
	const roaster = bean.roasterName?.trim() ?? '';
	const type = bean.name?.trim() ?? '';
	if (roaster && type) return `${roaster} · ${type}`;
	if (roaster) return roaster;
	if (type) return type;
	return null;
}

/**
 * Build a stub Rust `Profile` from just the recorded `profileName` —
 * the shell's `StoredShot` doesn't persist the step list, so the
 * exporter's `profile` slot carries title + target_weight + dose only,
 * matching the legacy shell exporter's behaviour for an unknown step
 * list.
 */
function profileFromName(
	name: string | null,
	finalWeight: number | null,
	dose: number | null | undefined
): Profile | null {
	if (!name) return null;
	return {
		// A profile reconstructed from a shot's metadata carries no
		// stable ID — it is a degraded view, not a stored profile, and
		// the v2 community contract has no `id` field anyway.
		id: '',
		title: name,
		notes: '',
		steps: [],
		preinfuse_step_count: 0,
		minimum_pressure: 0,
		maximum_flow: 0,
		max_total_volume_ml: 0,
		target_weight: finalWeight ?? 0,
		dose: dose ?? 0,
		author: '',
		beverage_type: 'espresso',
		tank_temperature: 0,
		version: '2'
	};
}

/**
 * Extract the `metadata.crema.*` block from a `.shot.json` doc text,
 * returning a typed {@link import('./store.svelte').ImportExtras}-ish
 * structure for `HistoryStore.addImported` to consume. Returns `null`
 * for legacy / pre-this-PR exports that omit the block, or when the
 * source is not parseable JSON.
 *
 * This is the inverse of {@link cremaExtras}: read each known key
 * defensively (the wire is JSON, so the source could come from any
 * tool), with a strict type check per slot.
 */
export function extractCremaExtras(rawJson: string): {
	grinderModel?: string | null;
	tags?: readonly string[];
	yieldTarget?: number | null;
	brewTemp?: number | null;
	preinfuseTarget?: number | null;
	stopOnWeight?: boolean;
	autoTare?: boolean;
	bean?: {
		roastedOn?: string | null;
		roastLevel?: number | null;
		notes?: string;
	} | null;
} | null {
	let doc: unknown;
	try {
		doc = JSON.parse(rawJson);
	} catch {
		return null;
	}
	if (typeof doc !== 'object' || doc === null) return null;
	const metadata = (doc as Record<string, unknown>).metadata;
	if (typeof metadata !== 'object' || metadata === null) return null;
	const crema = (metadata as Record<string, unknown>).crema;
	if (typeof crema !== 'object' || crema === null) return null;
	const c = crema as Record<string, unknown>;
	const out: {
		grinderModel?: string | null;
		tags?: string[];
		yieldTarget?: number | null;
		brewTemp?: number | null;
		preinfuseTarget?: number | null;
		stopOnWeight?: boolean;
		autoTare?: boolean;
		bean?: {
			roastedOn?: string | null;
			roastLevel?: number | null;
			notes?: string;
		} | null;
	} = {};
	if (typeof c.grinder_model === 'string' && c.grinder_model.length > 0) {
		out.grinderModel = c.grinder_model;
	}
	if (Array.isArray(c.tags)) {
		out.tags = c.tags.filter((t): t is string => typeof t === 'string');
	}
	if (typeof c.yield_target === 'number' && Number.isFinite(c.yield_target)) {
		out.yieldTarget = c.yield_target;
	}
	if (typeof c.brew_temp_target === 'number' && Number.isFinite(c.brew_temp_target)) {
		out.brewTemp = c.brew_temp_target;
	}
	if (
		typeof c.preinfuse_target === 'number'
		&& Number.isFinite(c.preinfuse_target)
	) {
		out.preinfuseTarget = c.preinfuse_target;
	}
	if (typeof c.stop_on_weight === 'boolean') out.stopOnWeight = c.stop_on_weight;
	if (typeof c.auto_tare === 'boolean') out.autoTare = c.auto_tare;
	const bean: {
		roastedOn?: string | null;
		roastLevel?: number | null;
		notes?: string;
	} = {};
	let beanHasField = false;
	if (typeof c.bean_roasted_on === 'string') {
		bean.roastedOn = c.bean_roasted_on;
		beanHasField = true;
	}
	if (typeof c.bean_roast_level === 'number' && Number.isFinite(c.bean_roast_level)) {
		bean.roastLevel = c.bean_roast_level;
		beanHasField = true;
	}
	if (typeof c.bean_notes === 'string' && c.bean_notes.length > 0) {
		bean.notes = c.bean_notes;
		beanHasField = true;
	}
	if (beanHasField) out.bean = bean;
	return out;
}
