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
 * hands it to the wasm export. docs/26 audit #6.
 */

import { export_v2_json_shot } from '$lib/wasm/de1_wasm';
import type { Profile, RustStoredShot } from '$lib/core';
import type { StoredShot } from './model';

/**
 * Emit a {@link StoredShot} as a pretty-printed community-v2
 * `.shot.json` string suitable for download / sharing.
 *
 * The bean is encoded into the Rust core's flat `metadata.beans`
 * label (`"roaster · type"`) — the symmetric inverse of the shell's
 * `beanFromImported` splitter. roastedOn / roastLevel do not survive
 * the Rust `StoredShot` shape (the core does not yet model a typed
 * bean — docs/26 audit #5 is the pending push); a future v3 round-
 * trip will preserve them once that lands.
 */
export function exportStoredShotAsV2Json(shot: StoredShot): string {
	return export_v2_json_shot(JSON.stringify(toRustStoredShot(shot)));
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
			grinder_setting: null,
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
				}
			}))
		}
	};
}

/**
 * The flat `"roaster · type"` label the Rust core stores for a bean.
 * The shell's structured {@link ShotBean} is richer than the core's
 * `metadata.beans` (roastedOn / roastLevel don't survive); on the
 * import side the shell splits this label back via `beanFromImported`,
 * so brand + type round-trip through the v2 doc.
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
