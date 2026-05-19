/**
 * `$lib/history/model` — the shot-history record shape.
 *
 * The core does not persist finished shots — `Event::ShotCompleted` only
 * reports a duration and a sample count, nothing it stores. So the web shell
 * keeps its own history: when a shot completes, the orchestrator snapshots the
 * buffered `shotTelemetry` series (the same `TelemetrySample[]` the live chart
 * plots) plus a little derived metadata into a {@link ShotRecord}, and the
 * {@link HistoryStore} persists it to `localStorage`.
 *
 * A record is therefore *self-contained*: the History page can redraw a stored
 * shot's curve from `series` alone — no device, no re-fetch — exactly as the
 * live `LiveChart` draws an in-progress one.
 */

import type { TelemetrySample } from '$lib/state';

/**
 * A snapshot of the current bean at the moment a shot was pulled. Stored on
 * the {@link ShotRecord} so a later change to the current bean cannot rewrite
 * history — days-off-roast is derived from `roastedOn` against the shot's own
 * `completedAt`. Optional: a shot recorded before this field existed (or with
 * no bean logged) simply has none.
 */
export interface ShotBean {
	/** The roastery when the shot was pulled (Visualizer `bean.brand`). */
	readonly roaster: string;
	/** The coffee itself when the shot was pulled (Visualizer `bean.type`). */
	readonly type: string;
	/** ISO `yyyy-mm-dd` roast date when the shot was pulled, or `null`. */
	readonly roastedOn: string | null;
	/** Roast level on the 1..10 scale when the shot was pulled, or `null`. */
	readonly roastLevel: number | null;
}

/** A short id for a stored shot — `crypto.randomUUID` if present. */
export function shotId(): string {
	const rnd =
		typeof crypto !== 'undefined' && 'randomUUID' in crypto
			? crypto.randomUUID()
			: Math.random().toString(36).slice(2) + Date.now().toString(36);
	return `shot:${rnd}`;
}

/**
 * One finished shot, persisted by the shell. Everything needed to render a
 * History `ShotRow` and `ShotDetail` without a device present.
 */
export interface ShotRecord {
	/** Stable id — `shot:<uuid>`. */
	readonly id: string;
	/** Unix epoch milliseconds the shot completed. */
	readonly completedAt: number;
	/** The active profile's name when the shot was pulled, or `null`. */
	readonly profileName: string | null;
	/** Total shot duration, milliseconds (the `ShotCompleted` duration). */
	readonly durationMs: number;
	/**
	 * The brew dose, grams — the active profile's dose at the time of the
	 * shot. Optional: a shot recorded before this field existed (or with no
	 * active profile) simply has none, and {@link ratioLabel} falls back.
	 */
	readonly doseG?: number | null;
	/** Peak scale weight reached during the shot, grams, or `null`. */
	readonly peakWeightG: number | null;
	/** Final scale weight at shot end, grams, or `null` if no scale. */
	readonly finalWeightG: number | null;
	/** Peak group pressure reached, bar. */
	readonly peakPressure: number;
	/** Peak group-head temperature reached, °C. */
	readonly peakTemp: number;
	/** The buffered telemetry series — what the curve chart redraws from. */
	readonly series: readonly TelemetrySample[];
	/**
	 * A snapshot of the current bean when the shot was pulled, or `null` when
	 * no bean was logged. Pre-existing records have no `bean` — treat it as
	 * optional everywhere it is read.
	 */
	readonly bean?: ShotBean | null;
	/** User star rating 0–5; `0` means unrated. Editable. */
	rating: number;
	/** User tasting notes. Editable. */
	notes: string;
}

/**
 * A `1:N` ratio label from final weight ÷ the recorded brew dose, or `1:—`.
 *
 * Uses the shot's own `doseG` — captured at `ShotCompleted` time from the
 * active profile. A pre-existing record (or one pulled with no active
 * profile) has no `doseG`, so it falls back to the shell-wide 18 g default.
 */
export function ratioLabel(record: ShotRecord): string {
	const yieldG = record.finalWeightG ?? record.peakWeightG;
	if (yieldG == null || yieldG <= 0) return '1:—';
	const doseG = record.doseG != null && record.doseG > 0 ? record.doseG : 18;
	return `1:${(yieldG / doseG).toFixed(2)}`;
}

/** A star string `★★★★☆` for a 0–5 rating. */
export function stars(rating: number): string {
	const n = Math.max(0, Math.min(5, Math.round(rating)));
	return '★'.repeat(n) + '☆'.repeat(5 - n);
}
