/**
 * `$lib/history/v2-export` — emit a Crema {@link StoredShot} as a
 * community-v2 `.shot.json` document (the reaprime / modern-de1app
 * contract).
 *
 * The shape mirrors the fixtures under
 * `reaprime/test/fixtures/de1app/history_v2/*.json` and is the
 * inverse of the Rust `de1_domain::import_v2_json_shot` parser. A
 * round-trip through Crema (import .shot.json → edit → export
 * .shot.json) preserves every field the v2 schema carries; fields
 * Crema's shell doesn't store (raw `flow.by_weight` from a paired
 * scale's flow channel, `goal` curves the firmware reports separately
 * from `setHeadTemp`, etc.) emit empty arrays so consumers that
 * expect the key get a present-but-empty value.
 *
 * Crema-only fields (the in-memory `id`, the `peakWeight` we derive at
 * record-time, etc.) do NOT appear in the export — `id` is recomputable
 * on re-import via `recorded_at` and `peakWeight` is recomputable from
 * `totals.weight`. Keeping the export to the v2 contract means any
 * other DE1 app that reads the file gets what it expects.
 *
 * docs/22 §5.1 (import) + task #64 (this export).
 */

import type { StoredShot } from './model';

/**
 * Emit a {@link StoredShot} as a pretty-printed community-v2
 * `.shot.json` string suitable for download / sharing.
 */
export function exportStoredShotAsV2Json(shot: StoredShot): string {
	return JSON.stringify(buildV2Document(shot), null, 2);
}

interface V2Document {
	version: number;
	clock: number;
	date: string;
	elapsed: number[];
	pressure: { pressure: number[]; goal: number[] };
	flow: {
		flow: number[];
		by_weight: number[];
		by_weight_raw: number[];
		goal: number[];
	};
	temperature: { basket: number[]; mix: number[]; goal: number[] };
	totals: { weight: number[]; water_dispensed: number[] };
	state_change: number[];
	profile: V2Profile;
	meta: V2Meta;
	app: { app_name: string; app_version: string };
}

interface V2Profile {
	version: string;
	title: string;
	notes: string;
	author: string;
	beverage_type: 'espresso' | 'pourover' | 'manual' | 'cleaning' | 'calibrate';
	steps: unknown[];
	target_volume: number;
	target_weight: number;
	target_volume_count_start: number;
	tank_temperature: number;
}

interface V2Meta {
	bean: {
		brand: string;
		type: string;
		notes: string;
		roast_level: string;
		roast_date: string;
	} | null;
	shot: {
		enjoyment: number | null;
		notes: string;
		tds: number | null;
		ey: number | null;
	};
	grinder: { model: string; setting: string } | null;
	in: number | null;
	out: number | null;
	time: number;
}

function buildV2Document(shot: StoredShot): V2Document {
	const series = shot.series;
	// `clock` is Unix seconds in the v2 contract — Crema stores
	// `completedAt` as Unix ms.
	const clock = Math.round(shot.completedAt / 1000);
	// `elapsed` is in seconds in v2; Crema's `TelemetrySample.elapsed`
	// is ms.
	const elapsed = series.map((s) => s.elapsed / 1000);

	// Parallel series, one entry per sample. Every key the v2 contract
	// names emits a same-length array so consumers can index by sample
	// index without length checks. Channels Crema doesn't capture
	// (scale-by-weight flow, the raw scale flow before smoothing, etc.)
	// emit zeros — preserves the shape, signals "no data."
	const pressure = series.map((s) => s.pressure);
	const pressureGoal = series.map((s) => s.setGroupPressure);
	const flow = series.map((s) => s.flow);
	const flowGoal = series.map((s) => s.setGroupFlow);
	// scale-side derived flow channels — only meaningful when a scale
	// was paired during the shot. Crema doesn't currently snapshot
	// `flow_by_weight` per sample, so emit a zeros placeholder. v2
	// readers tolerate empty/zero series.
	const zeros = series.map(() => 0);
	const basket = series.map((s) => s.temp);
	const mix = series.map((s) => s.mixTemp);
	// Crema records a single `setHeadTemp` goal per sample — that
	// matches the legacy `temperature.goal` array (one channel).
	const tempGoal = series.map((s) => s.setHeadTemp);
	const weightSeries = series.map((s) => s.weight ?? 0);

	const ratingToEnjoyment =
		shot.rating > 0 ? Math.round((shot.rating - 1) * 25) : null;

	const bean = shot.bean
		? {
				brand: shot.bean.roaster,
				type: shot.bean.type,
				notes: '',
				roast_level:
					shot.bean.roastLevel != null ? String(shot.bean.roastLevel) : '',
				roast_date: shot.bean.roastedOn ?? ''
			}
		: null;

	const profile: V2Profile = {
		version: '2',
		title: shot.profileName ?? 'Unknown profile',
		notes: '',
		author: '',
		beverage_type: 'espresso',
		// Crema's `StoredShot` doesn't persist the profile's steps — only
		// the active profile's *name* at shot time (and the dose). Emit
		// the steps array empty so the v2 schema's required key is
		// present; importers that need step detail can pair the export
		// with a separate profile JSON.
		steps: [],
		target_volume: 0,
		target_weight: shot.finalWeight ?? 0,
		target_volume_count_start: 0,
		tank_temperature: 0
	};

	const meta: V2Meta = {
		bean,
		shot: {
			enjoyment: ratingToEnjoyment,
			notes: shot.notes,
			tds: null,
			ey: null
		},
		// Crema doesn't persist per-shot grinder model / setting today
		// (the workflow-context model from docs/22 §6.2 is a planned
		// addition). Emit null until that lands so v2 readers don't
		// receive bogus blank strings.
		grinder: null,
		in: shot.dose ?? null,
		out: shot.finalWeight ?? null,
		time: shot.duration / 1000
	};

	return {
		version: 2,
		clock,
		date: new Date(shot.completedAt).toString(),
		elapsed,
		pressure: { pressure, goal: pressureGoal },
		flow: { flow, by_weight: zeros, by_weight_raw: zeros, goal: flowGoal },
		temperature: { basket, mix, goal: tempGoal },
		totals: { weight: weightSeries, water_dispensed: zeros },
		// Phase boundaries aren't captured at sample-level in Crema's
		// shell — the legacy log records them as a sparse array. Empty
		// is the safe default.
		state_change: [],
		profile,
		meta,
		app: { app_name: 'crema', app_version: '0.1.0' }
	};
}
