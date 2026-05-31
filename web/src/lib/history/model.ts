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

import type { Bean, Roaster } from '$lib/bean';
import type {
	RustShotMetadata,
	RustStoredShot,
	RustTimedSample
} from '$lib/core';
import { peaksForShot as wasmPeaksForShot } from '$lib/wasm/de1_wasm';
import type { TelemetrySample } from '$lib/state';
import { filenameStamp } from '$lib/utils/download';
import { formatRatio } from '$lib/utils/ratio';
import { fromWire } from './telemetry-wire';

/**
 * A snapshot of the current bean at the moment a shot was pulled. Stored on
 * the {@link StoredShot} so a later change to the current bean cannot rewrite
 * history — days-off-roast is derived from `roastedOn` against the shot's own
 * `completedAt`. Optional: a shot recorded before this field existed (or with
 * no bean logged) simply has none.
 *
 * **Snapshot semantics.** Every field here is the bean's
 * *frozen-at-completion* value — the shot's content source of truth.
 * The bean library's live row (looked up via {@link beanId}) is used
 * ONLY as a link pointer (e.g. wiring `coffee_bag_id` on Visualizer —
 * the live `Bean.visualizerId` is the link, never snapshotted into
 * history). Reading live-bean content for the shot would retroactively
 * rewrite history — renaming a bag would silently rewrite every past
 * shot's display name, the exact failure mode Beanconqueror has, which
 * is why Crema picked snapshots.
 *
 * `visualizerId` is deliberately NOT carried here — Visualizer ids are
 * stable once assigned, so reading the live bean for the `coffee_bag_id`
 * link is correct (and is the one place upload-time live-bean lookup is
 * legitimate). Snapshotting it would leak the link into the snapshot
 * layer for no gain.
 *
 * `tags` ARE carried here — a frozen snapshot of the bean's tags at
 * shot-completion time (see the `tags` field below + {@link snapshotFromBean}),
 * distinct from the mutable {@link StoredShot.tags} on the shot row, which the
 * user can re-tag freely without rewriting this snapshot. The shot row's own
 * tag copy is taken in `app.svelte.ts`'s `ShotCompleted` handler.
 */
export interface ShotBean {
	/**
	 * FK back into the bean library, when the shot was pulled with a library
	 * bag selected. `null` or missing for shots pulled before the library
	 * existed, or imported from a `.shot` file. The History UI uses this to
	 * resolve a click-through to the bean detail; an archived / deleted bag
	 * falls back to the snapshot strings.
	 *
	 * Also the live-lookup key for {@link
	 * $lib/visualizer/shot-sync.resolveCoffeeBagId} (which reads the live
	 * `Bean.visualizerId` as the upload's `coffee_bag_id` link pointer)
	 * and for the /history tag-filter live-fallback.
	 */
	readonly beanId?: string | null;
	/** The bean / coffee name at the time of the shot (Visualizer `bean_type`). */
	readonly name: string;
	/** The roastery at the time of the shot (Visualizer `bean_brand`). */
	readonly roasterName?: string | null;
	/** ISO `yyyy-mm-dd` roast date when the shot was pulled, or `null`. */
	readonly roastedOn?: string | null;
	/** Roast level on the 1..10 scale when the shot was pulled, or `null`. */
	readonly roastLevel?: number | null;
	/** The bean's tags at shot-completion time. */
	readonly tags?: string[];
	/**
	 * The bean's free-form notes at shot-completion time. Optional —
	 * legacy snapshots without this field deserialise cleanly.
	 * Wires to Visualizer's `bean_notes` on shot upload.
	 */
	readonly notes?: string;
	/**
	 * The bean's grinder click setting at shot-completion time
	 * (e.g. `"2.5"`, `"40"`). Wires to Visualizer's `grinder_setting`
	 * on shot upload.
	 */
	readonly grinderSetting?: string;
}

/**
 * Snapshot a live {@link Bean} (+ its resolved roaster) into the
 * {@link ShotBean} content shape. The ONE canonical helper used at both
 * shot-completion time (`app.svelte.ts`'s `ShotCompleted` handler) and
 * retroactive bean-rebind time (`HistoryStore.setBean`) so the snapshot
 * shape can never drift between the two call sites.
 *
 * Returns `null` when `bean` is null/undefined — the call sites then
 * persist `shot.bean = null` (a bean-less shot).
 *
 * Optional fields (`notes`, `grinderSetting`) are omitted from the
 * literal when their source is empty so the persisted record stays
 * minimal (matches the original capture-site assembly).
 */
export function snapshotFromBean(
	bean: Bean | null | undefined,
	roaster: Roaster | null | undefined
): ShotBean | null {
	if (!bean) return null;
	return {
		beanId: bean.id,
		name: bean.name.trim(),
		roasterName: roaster?.name.trim() ?? null,
		roastedOn: bean.roastedOn,
		roastLevel: bean.roastLevel,
		tags: [...bean.tags],
		...(bean.notes ? { notes: bean.notes } : {}),
		...(bean.grinderSetting ? { grinderSetting: bean.grinderSetting } : {})
	};
}

/**
 * Mint a stable id for a stored shot. Routes through the Rust core's
 * UUID v7 minter (`newShotId` in wasm — see `de1_domain::new_shot_id`)
 * once the wasm bundle is loaded, so shot ids match the same
 * lexicographically-sortable, time-prefixed scheme as profile ids.
 * Falls back to `crypto.randomUUID` during boot / SSR / tests where
 * the wasm core hasn't initialised yet.
 */
export function shotId(): string {
	if (typeof window !== 'undefined') {
		const cached = (window as { __cremaWasmCore?: { newShotId?: () => string } })
			.__cremaWasmCore;
		if (cached?.newShotId) {
			try {
				return cached.newShotId();
			} catch {
				// Fall through to the crypto fallback below.
			}
		}
	}
	const rnd =
		typeof crypto !== 'undefined' && 'randomUUID' in crypto
			? crypto.randomUUID()
			: Math.random().toString(36).slice(2) + Date.now().toString(36);
	return `shot:${rnd}`;
}

/**
 * Summary peaks over a shot's telemetry — derived on demand by
 * {@link peaksOf}. Mirrors Rust's `ShotPeaks` (the canonical
 * derivation lives in `de1_domain::shot::ShotRecord::peaks`); the
 * shell calls it via wasm so every shell consumes the identical
 * numbers.
 */
export interface ShotPeaks {
	/** Peak scale weight reached during the shot, grams, or `null`. */
	readonly peakWeight: number | null;
	/** Final scale weight at shot end, grams, or `null` if no scale. */
	readonly finalWeight: number | null;
	/** Peak group pressure reached, bar. */
	readonly peakPressure: number;
	/** Peak group-head temperature reached, °C. */
	readonly peakTemp: number;
}

/**
 * The shell-side `ShotMetadata` — mirrors Rust's `ShotMetadata`
 * camelCase wire shape so the shell and Rust hold the same record on
 * read and write.
 */
export interface ShotMetadata {
	/** Dry coffee dose, grams. */
	dose?: number | null;
	/** Weight in the cup, grams. */
	yieldOut?: number | null;
	/** Bean / roaster description (legacy single-line "brand · type"). */
	beans?: string | null;
	/** Grinder setting used. */
	grinderSetting?: string | null;
	/** Free-form tasting notes. Editable. */
	notes?: string | null;
	/** Personal rating, 0..5. `0` means unrated. Editable. */
	rating?: number | null;
	/** Total dissolved solids in the beverage, percent. */
	tds?: number | null;
	/** Extraction yield, percent. */
	extractionYield?: number | null;
}

/**
 * One finished shot, persisted by the shell.
 *
 * Storage / wire shape — matches Rust's `de1_domain::StoredShot` field
 * for field (camelCase serde) so the same JSON round-trips through the
 * core's Crema JSONL exporter without translation. Pre-computed
 * derived metrics (peaks) are NOT stored here — derive them via
 * {@link peaksOf}. Chart-friendly flat samples come from
 * {@link flatSamplesOf}.
 *
 * Records persisted before this PR carried a denormalized shape
 * (`peakWeight` / `series` / top-level `dose`/`rating`/`notes`); the
 * history-store loader upcasts them in place on first read.
 */
export interface StoredShot {
	/** Schema version — `3` post-unification. */
	readonly formatVersion: number;
	/** Stable id — `shot:<uuid-v7>`. */
	readonly id: string;
	/** Unix epoch milliseconds the shot completed. */
	readonly completedAt: number;
	/** The active profile's name at the time of the shot, or `null`. */
	readonly profileName: string | null;
	/**
	 * The active profile, if known — full `Profile` JSON. Optional;
	 * shots recorded without a live profile only carry `profileName`.
	 */
	readonly profile?: unknown | null;
	/** Why the shot stopped, if an AutoStop drove it. */
	readonly stopReason?: unknown | null;
	/** Barista journal metadata (dose / yield / rating / notes …). */
	metadata: ShotMetadata;
	/**
	 * The recorded telemetry. `duration` is shot length in ms; `samples`
	 * is the wire-shape sample series the Rust core emits — flat
	 * `elapsed` (ms) + a nested DE1 `sample` + scale overlays.
	 */
	readonly record: {
		readonly duration: number;
		readonly samples: readonly RustTimedSample[];
	};
	/** Frozen-at-completion bean snapshot, or `null` if none was logged. */
	bean?: ShotBean | null;
	/**
	 * Equipment-level grinder model at completion time (e.g. "Niche
	 * Zero"). `null` means no value; the upload-time cascade falls
	 * back to the settings default. Editable post-completion.
	 */
	grinderModel?: string | null;
	/** Shot-level free-form tags. Mutable. */
	tags?: string[];
	/** Yield target the user dialed in, grams (stop-on-weight target). */
	yieldTarget?: number | null;
	/** Brew water temperature the user dialed in, °C. */
	brewTempTarget?: number | null;
	/** Pre-infuse duration the user dialed in, seconds. */
	preinfuseTarget?: number | null;
	/** Whether stop-on-weight was armed for this shot. */
	stopOnWeight?: boolean;
	/** Whether auto-tare was armed for this shot. */
	autoTare?: boolean;
	/** Visualizer `shot.id` once uploaded; `null` until pushed. */
	visualizerId?: string | null;
	/** Unix epoch ms when this shot was soft-deleted, or `null`. */
	deletedAt?: number | null;
}

/**
 * Derive a shot's peaks via the Rust core (`ShotRecord::peaks`).
 * Cached per-shot id + record-length so repeated reads inside one
 * render pass don't re-call wasm.
 *
 * Returns zero-shaped peaks (all `null` / `0`) when the wasm call
 * fails — defensive only; the core's parser is infallible for the
 * wire-shape inputs the shell stores.
 */
/**
 * Insertion-ordered bounded cache: a `Map` that evicts its least-recently-used
 * key once it passes `cap`, and re-inserts a key on `get` so hot entries stay
 * at the tail. Bounds the per-session peaks / flat-sample memoisation so a long
 * browse of history can't grow these module-level Maps without limit (GEN5 —
 * `flatCache` could otherwise reach tens of MB and get the mobile tab killed).
 */
class BoundedCache<V> {
	private readonly map = new Map<string, V>();
	private readonly cap: number;
	constructor(cap: number) {
		this.cap = cap;
	}
	get(key: string): V | undefined {
		const v = this.map.get(key);
		if (v !== undefined) {
			// Re-insert to mark most-recently-used (Map keeps insertion order).
			this.map.delete(key);
			this.map.set(key, v);
		}
		return v;
	}
	set(key: string, value: V): void {
		if (this.map.has(key)) {
			this.map.delete(key);
		} else if (this.map.size >= this.cap) {
			const oldest = this.map.keys().next().value;
			if (oldest !== undefined) this.map.delete(oldest);
		}
		this.map.set(key, value);
	}
}

// Peaks are tiny (4 numbers) but each is derived via a wasm call, so cache a
// generous number of shots.
const peaksCache = new BoundedCache<ShotPeaks>(512);
export function peaksOf(shot: StoredShot): ShotPeaks {
	const key = `${shot.id}:${shot.record.samples.length}`;
	const cached = peaksCache.get(key);
	if (cached) return cached;
	try {
		const json = wasmPeaksForShot(JSON.stringify(shot));
		const peaks = JSON.parse(json) as ShotPeaks;
		peaksCache.set(key, peaks);
		return peaks;
	} catch {
		const fallback: ShotPeaks = {
			peakWeight: null,
			finalWeight: null,
			peakPressure: 0,
			peakTemp: 0
		};
		return fallback;
	}
}

/**
 * Flatten the wire-shape sample series into the chart-friendly
 * {@link TelemetrySample} shape the live + history charts consume.
 *
 * Two responsibilities, kept separate:
 *  - **Cell-by-cell projection** — `fromWire` in `./telemetry-wire`
 *    handles each sample as a pure inverse of `toWire`.
 *  - **Resistance fallback** — when a stored sample has no resistance
 *    signal, compute it from the per-sample pressure / flow²
 *    (the de1app/DSx P/F² formula) so the history chart can still
 *    show a resistance trace for shots recorded before the field
 *    was carried on the wire. This is NOT inverse of `toWire`; it's
 *    a chart-side enrichment.
 *
 * Cheap (per-sample object spread); cached per shot id + record-length.
 */
// Flat samples are memory-heavy (full per-sample arrays) but cheap to recompute,
// so cap tighter than peaks — a generous viewport's worth.
const flatCache = new BoundedCache<TelemetrySample[]>(128);
export function flatSamplesOf(shot: StoredShot): TelemetrySample[] {
	const key = `${shot.id}:${shot.record.samples.length}`;
	const cached = flatCache.get(key);
	if (cached) return cached;
	const out: TelemetrySample[] = shot.record.samples.map((t) => {
		const sample = fromWire(t);
		// Chart-side enrichment: derive resistance from pressure / flow²
		// when no per-sample signal is recorded — legacy shots and any
		// pulled remote that didn't carry the resistance channel.
		if (sample.resistance === null && t.sample.groupFlow > 0.05) {
			return {
				...sample,
				resistance: t.sample.groupPressure / (t.sample.groupFlow * t.sample.groupFlow)
			};
		}
		return sample;
	});
	flatCache.set(key, out);
	return out;
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
	const peaks = peaksOf(record);
	const yieldOut = peaks.finalWeight ?? peaks.peakWeight;
	const dose =
		record.metadata.dose != null && record.metadata.dose > 0
			? record.metadata.dose
			: 18;
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
