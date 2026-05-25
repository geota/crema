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
import type { StoredShot } from './model';

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
	if (shot.brewTemp != null) out.brew_temp_target = shot.brewTemp;
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

/** Build the Rust-shape `StoredShot` the wasm exporter consumes. */
function toRustStoredShot(shot: StoredShot): RustStoredShot {
	return {
		format_version: 2,
		recorded_at: shot.completedAt,
		profile: profileFromName(shot.profileName, shot.finalWeight, shot.dose),
		stop_reason: null,
		metadata: {
			dose: shot.dose ?? null,
			yield_out: shot.finalWeight ?? null,
			beans: beanLabel(shot),
			// Bean-scoped grinder click setting — the v2 schema's
			// `metadata.grinder.setting` slot. The equipment-level
			// `grinderModel` (e.g. "Niche Zero") rides in
			// `metadata.crema.grinder_model` instead — the v2 schema's
			// `grinder.model` slot is left empty on the Rust side
			// because Crema's bean-scoped vs equipment-scoped split
			// doesn't map cleanly onto the legacy single `grinder` block.
			grinder_setting: shot.bean?.grinderSetting ?? null,
			notes: shot.notes && shot.notes.length > 0 ? shot.notes : null,
			rating: shot.rating > 0 ? shot.rating : null,
			tds: null,
			extraction_yield: null
		},
		record: {
			duration: msToRustDuration(shot.duration),
			samples: shot.series.map((s) => ({
				elapsed: msToRustDuration(s.elapsed),
				sample: {
					sample_time: 0,
					group_pressure: s.pressure,
					group_flow: s.flow,
					head_temp: s.temp,
					mix_temp: s.mixTemp,
					set_head_temp: s.setHeadTemp,
					// The core's `temperature.goal` collapses to one channel
					// on export; copy `setHeadTemp` into both so a round
					// trip through `import_v2_json_shot` reproduces the
					// same set_head / set_mix pair the shell carried.
					set_mix_temp: s.setHeadTemp,
					set_group_pressure: s.setGroupPressure,
					set_group_flow: s.setGroupFlow,
					frame_number: 0,
					steam_temp: s.steamTemp
				},
				// Overlay channels — forwarded onto the Rust `TimedSample`
				// so the v2 exporter can emit them into the
				// `totals.weight` / `flow.by_weight` / `totals.water_dispensed`
				// columns the spec defines. `undefined` / `null` /
				// missing values stay missing on the wire (the Rust
				// side maps absent → `0.0` only when assembling the
				// sample-aligned series). Each rider is what makes the
				// new channels ride through `buildShotPayload` →
				// `POST /shots/upload` without an explicit Visualizer
				// adapter change.
				scale_weight: s.weight ?? undefined,
				scale_flow_weight: s.weightFlow ?? undefined,
				dispensed_volume: s.dispensedVolume ?? undefined,
				resistance: s.resistance ?? undefined,
				resistance_weight: s.resistanceWeight ?? undefined
			}))
		}
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
	const roaster = bean.roaster?.trim() ?? '';
	const type = bean.type?.trim() ?? '';
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

/** Encode plain milliseconds into the Rust core's `{secs, nanos}` Duration. */
function msToRustDuration(ms: number): { secs: number; nanos: number } {
	const total = Math.max(0, ms);
	const secs = Math.floor(total / 1000);
	const nanos = Math.round((total - secs * 1000) * 1_000_000);
	return { secs, nanos };
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
