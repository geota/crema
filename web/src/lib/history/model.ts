/**
 * `$lib/history/model` — the shot-history record shape.
 *
 * The core does not persist finished shots — `Event::ShotCompleted` only
 * reports a duration and a sample count, nothing it stores. So the web shell
 * keeps its own history: when a shot completes, the orchestrator snapshots the
 * buffered `shotTelemetry` series (the same `TelemetrySample[]` the live chart
 * plots) plus a little derived metadata into a {@link StoredShot}, and the
 * {@link HistoryStore} persists it to `localStorage`.
 *
 * A stored shot is therefore *self-contained*: the History page can redraw it
 * from `series` alone — no device, no re-fetch — exactly as the live
 * `LiveChart` draws an in-progress one.
 *
 * Named to mirror Rust's `de1_domain::StoredShot`. The Rust `ShotRecord` is
 * the raw telemetry sub-struct; the persisted top-level entry is called
 * `StoredShot` on both halves of the split.
 */

import type { TelemetrySample } from '$lib/state';
import { filenameStamp } from '$lib/utils/download';
import { formatRatio } from '$lib/utils/ratio';

/**
 * A snapshot of the current bean at the moment a shot was pulled. Stored on
 * the {@link StoredShot} so a later change to the current bean cannot rewrite
 * history — days-off-roast is derived from `roastedOn` against the shot's own
 * `completedAt`. Optional: a shot recorded before this field existed (or with
 * no bean logged) simply has none.
 */
export interface ShotBean {
	/**
	 * FK back into the bean library, when the shot was pulled with a library
	 * bag selected. `null` or missing for shots pulled before the library
	 * existed, or imported from a `.shot` file. The History UI uses this to
	 * resolve a click-through to the bean detail; an archived / deleted bag
	 * falls back to the snapshot strings.
	 */
	readonly beanId?: string | null;
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
 * History `ShotRow` and `ShotDetail` without a device present. Named to
 * mirror Rust's `de1_domain::StoredShot`.
 */
export interface StoredShot {
	/** Stable id — `shot:<uuid>`. */
	readonly id: string;
	/** Unix epoch milliseconds the shot completed. */
	readonly completedAt: number;
	/** The active profile's name when the shot was pulled, or `null`. */
	readonly profileName: string | null;
	/** Total shot duration, milliseconds (the `ShotCompleted` duration). */
	readonly duration: number;
	/**
	 * The brew dose, grams — the active profile's dose at the time of the
	 * shot. Optional: a shot recorded before this field existed (or with no
	 * active profile) simply has none, and {@link ratioLabel} falls back.
	 */
	readonly dose?: number | null;
	/** Peak scale weight reached during the shot, grams, or `null`. */
	readonly peakWeight: number | null;
	/** Final scale weight at shot end, grams, or `null` if no scale. */
	readonly finalWeight: number | null;
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
	/**
	 * Shot-level free-form tags (e.g. `["daily-driver", "comp"]`) —
	 * surfaced from / pushed to Visualizer's `tags` (detail) / `tag_list`
	 * (PATCH) field. Mutable metadata, NOT part of the de-dup signature
	 * (`signatureForShot`) — a re-tag doesn't change shot identity.
	 *
	 * Optional on the type so legacy localStorage records without the
	 * field deserialise cleanly; the history-store loader normalises
	 * missing / non-array values to `[]` at load time. The bean library's
	 * `Bean.tags` (`docs/28`) is the analogous frozen-at-completion
	 * snapshot — the on-upload PATCH unions the two (shot.tags first,
	 * bean.tags second, dedup case-sensitive preserving first occurrence)
	 * per `shot-sync.ts`.
	 */
	tags?: string[];
	/**
	 * Visualizer `shot.id` once uploaded — the sync identity that
	 * persists across local-id changes (docs/36 §3). `null` until the
	 * shot has been pushed; the upload round-trip writes it back so
	 * subsequent syncs PATCH the same row instead of POSTing a
	 * duplicate. Optional on the type so legacy localStorage records
	 * without the field deserialise cleanly.
	 */
	visualizerId?: string | null;
	/**
	 * Unix epoch ms when this shot was soft-deleted, or `null` when
	 * active. The History UI filters tombstones out; the next sync push
	 * DELETEs the remote row, then garbage-collects the tombstone (per
	 * docs/36 §3 soft-delete). Optional so legacy records deserialise.
	 */
	deletedAt?: number | null;
}

/**
 * A `1:N` ratio label from final weight ÷ the recorded brew dose, or `1:—`.
 *
 * Uses the shot's own `dose` (grams) — captured at `ShotCompleted` time from
 * the active profile. A pre-existing record (or one pulled with no active
 * profile) has no `dose`, so it falls back to the shell-wide 18 g default.
 *
 * The arithmetic + format come from the shared `$lib/utils/ratio.formatRatio`
 * helper, which delegates to `de1_domain::brew_ratio` via the wasm bridge so
 * every shell produces the same number. This wrapper just resolves the
 * shot-level fields and the 18 g dose fallback.
 */
export function ratioLabel(record: StoredShot): string {
	const yieldOut = record.finalWeight ?? record.peakWeight;
	const dose = record.dose != null && record.dose > 0 ? record.dose : 18;
	return formatRatio(dose, yieldOut);
}

/** A star string `★★★★☆` for a 0–5 rating. */
export function stars(rating: number): string {
	const n = Math.max(0, Math.min(5, Math.round(rating)));
	return '★'.repeat(n) + '☆'.repeat(5 - n);
}

/** A timestamped `.shot.json` filename for a v2-JSON-exported shot. */
export function shotFilename(record: StoredShot): string {
	const d = new Date(record.completedAt);
	const seconds = String(d.getSeconds()).padStart(2, '0');
	return `${filenameStamp(d)}${seconds}.shot.json`;
}
