/**
 * `$lib/profiles/model` — the shell's working profile model and its mapping
 * to / from the core's `Profile` JSON shape.
 *
 * The design (`profile-edit-page.jsx`) edits a **segment-based** model: a
 * profile is an ordered list of pressure / flow segments plus metadata. That
 * is the shell's working model — {@link CremaProfile}. The core, meanwhile,
 * speaks `de1_domain::Profile` (a list of `ProfileStep`s). {@link
 * fromCoreProfile} adapts a built-in `Profile` into a `CremaProfile`;
 * {@link toCoreProfile} maps the other way (used when a profile would be
 * uploaded to the DE1 — see the `// TODO` in `store.ts`).
 *
 * A `CremaProfile` additionally carries the **library** metadata the cards and
 * editor need (roast, custom tags, pinned, last-used) which the core's
 * `Profile` has no field for — those are shell-only and live in localStorage.
 *
 * Note `roast` is **recipe-suitability** metadata (which roast a recipe is
 * tuned for — it drives the Profiles page Light/Med/Dark filter), not bean
 * identity. The bag of coffee you are actually pulling lives in `$lib/bean`.
 */

import type { Profile, ProfileStep, SparkShape } from './core-types';
import { newProfileId } from '$lib/core';
import { roastFromProfile as wasmRoastFromProfile } from '$lib/wasm/de1_wasm';
import { formatRatio } from '$lib/utils/ratio';
import { getSettingsStore } from '$lib/settings/store.svelte';

/** Roast level — a fixed library filter facet. */
export type Roast = 'light' | 'medium' | 'dark';

/** How a segment ramps to its target — the design's `ramp` field. */
export type SegmentRamp = 'smooth' | 'fast';

/** Whether a segment targets a pressure (bar) or a flow (ml/s). */
export type SegmentMode = 'pressure' | 'flow';

/** Which metric an exit condition watches. */
export type ExitMetric = 'pressure' | 'flow';

/** The direction of an exit comparison. */
export type ExitCompare = 'over' | 'under';

/**
 * Which temperature sensor a segment regulates. Lowercase wire spelling
 * per the community v2 JSON contract: `'coffee'` = the basket (head
 * temp); `'water'` = the water exiting the group (mix temp).
 */
export type TempSensor = 'coffee' | 'water';

/**
 * A structured early-exit condition on a segment — leave the segment before
 * its duration elapses. Maps to the core's `ExitCondition`.
 */
export interface SegmentExit {
	/** Which metric to watch. */
	metric: ExitMetric;
	/** Exit when the metric rises above (`over`) or falls below (`under`). */
	compare: ExitCompare;
	/** The threshold value (bar or ml/s, per `metric`). */
	threshold: number;
}

/**
 * An advanced limiter capping the segment's non-priority quantity. Maps to the
 * core's `Limiter` (which assembles into an extension frame).
 */
export interface SegmentLimiter {
	/** The limit — flow if the segment is pressure-priority, or vice versa. */
	value: number;
	/** Tolerance band around the limit. */
	range: number;
}

/**
 * One segment of the working pressure profile — the design's segment shape
 * (`DEFAULT_SEGMENTS` in `profile-edit-page.jsx`), with the extra fields
 * needed to round-trip a core `ProfileStep` losslessly.
 */
export interface ProfileSegment {
	/** Stable id, unique within the profile. */
	id: string;
	/** Human-readable segment name. */
	name: string;
	/** Pressure- or flow-priority. */
	mode: SegmentMode;
	/** Target value — bar (pressure) or ml/s (flow). */
	target: number;
	/** How the segment ramps to its target. */
	ramp: SegmentRamp;
	/** Segment duration, seconds. */
	time: number;
	/** Structured early-exit condition, or null when the segment has none. */
	exit: SegmentExit | null;
	/** Target temperature — round-trips the core step. Celsius is the canonical unit (see docs/25 §7). */
	temp: number;
	/** Which temperature sensor the segment regulates. */
	tempSensor: TempSensor;
	/** Per-segment dispensed-volume limit, ml, range 0–1023 (0 = no limit). */
	volumeLimitMl: number;
	/** Advanced max-flow-or-pressure limiter, or null when unused. */
	limiter: SegmentLimiter | null;
}

/**
 * A profile in the shell's library — the working model behind a card and the
 * editor. Built-in profiles are adapted into this shape (read-only); custom
 * profiles are created / edited and persisted in localStorage.
 */
export interface CremaProfile {
	/**
	 * Stable UUID v7 (RFC 9562, 2024) — timestamp-prefixed, sortable,
	 * 36-character dashed form. Built-ins carry pre-generated IDs that
	 * ship in `core/de1-domain/profiles/builtin.json`; custom profiles
	 * mint a fresh ID via `newProfileId()` from the Rust core (the wasm
	 * `de1_domain::new_profile_id` bridge). Source distinction lives on
	 * the {@link source} field — there is no longer any prefix on the id.
	 */
	id: string;
	/** Whether this profile is a fixed built-in (read-only) or user-owned. */
	source: 'builtin' | 'custom';
	/** Display name (serif). */
	name: string;
	/** Free-text notes. */
	notes: string;
	/** Roast level the recipe is tuned for, or `null` when not clearly known. */
	roast: Roast | null;
	/** Custom user tags (Guest, Daily, names…). */
	tags: string[];
	/** Pinned to the Quick Controls favorites strip. */
	pinned: boolean;
	/** A human "last used" label — `null` until the profile is loaded on Brew. */
	lastUsed: string | null;
	/** Dose target, grams. */
	dose: number;
	/** Yield target, grams. */
	yieldOut: number;
	/** Brew temperature, °C. */
	brewTemp: number;
	/** End the shot when the scale reaches the yield target. */
	stopOnWeight: boolean;
	/** Zero the scale automatically when the shot begins. */
	autoTare: boolean;
	/** How many leading segments count as preinfusion — core `preinfuse_step_count`. */
	preinfuseStepCount: number;
	/** Whole-shot dispensed-volume limit, ml, 0–1023 (0 = no limit). */
	maxTotalVolumeMl: number;
	/** The ordered pressure / flow segments. */
	segments: ProfileSegment[];
	/**
	 * Profile author — free text. Round-trips through the v2 JSON
	 * `author` field. Optional (legacy + Crema-native profiles can be
	 * anonymous).
	 */
	author: string;
	/**
	 * What kind of beverage this profile produces — drives whether Crema
	 * surfaces the profile in the espresso list, the cleaning list, etc.
	 * Defaults to `'espresso'`.
	 */
	beverageType: BeverageType;
	/**
	 * Target tank temperature, °C (0 = no override). Most profiles leave
	 * this at 0; only advanced profiles change the tank setpoint
	 * mid-shot.
	 */
	tankTemperatureC: number;
}

/**
 * What kind of beverage a profile produces — the shell-side mirror of
 * the core's `BeverageType`. Same lowercase wire spellings.
 */
export type BeverageType = 'espresso' | 'calibrate' | 'cleaning' | 'manual' | 'pourover';

/**
 * A prefixed short id — used by segment ids (`seg:<uuid>` for
 * editor-added segments). Profile ids themselves no longer go through
 * here: they come from {@link newProfileId} (the wasm bridge over
 * `de1_domain::new_profile_id`) for custom profiles, and from the
 * built-in JSON for built-ins. The kept-around `uid()` lets the
 * segment scheme — which IS scoped within a profile, not the global
 * URL space — stay as-is.
 */
export function uid(prefix: string): string {
	return `${prefix}:${newProfileId()}`;
}

// Re-export `newProfileId` so existing callers in this module + the
// profiles barrel keep one stable name to import. The implementation
// lives in the Rust core (`de1_domain::new_profile_id`) and is exposed
// through the wasm bridge — same UUID v7 scheme on every shell.
export { newProfileId };

/**
 * The brew ratio label, e.g. `1:2.4`, derived from yield ÷ dose.
 *
 * Delegates to the shared `formatRatio` helper in `$lib/utils/ratio`
 * (which uses `de1_domain::brew_ratio` via the wasm bridge), so the
 * Profiles and History screens read identical ratios from the same
 * inputs and any future shell picks up the same arithmetic.
 */
export function ratioLabel(p: Pick<CremaProfile, 'dose' | 'yieldOut'>): string {
	return formatRatio(p.dose, p.yieldOut);
}

/**
 * Format a profile's `lastUsed` value into a relative label, e.g.
 * `just now` / `3h ago` / `2d ago` / `never used`.
 *
 * `lastUsed` holds an ISO-8601 instant (stamped by `ProfileStore.setActive`).
 * `null` → `never used`. A non-parseable string — e.g. a legacy `'just now'`
 * label persisted before this field stored timestamps — is returned as-is.
 */
export function relativeLastUsed(
	lastUsed: string | null,
	asOf: number = Date.now()
): string {
	if (lastUsed == null) return 'never used';
	const when = Date.parse(lastUsed);
	if (Number.isNaN(when)) return lastUsed;
	const deltaMs = Math.max(0, asOf - when);
	const min = Math.floor(deltaMs / 60_000);
	if (min < 1) return 'just now';
	if (min < 60) return `${min}m ago`;
	const hours = Math.floor(min / 60);
	if (hours < 24) return `${hours}h ago`;
	const days = Math.floor(hours / 24);
	if (days < 7) return `${days}d ago`;
	const weeks = Math.floor(days / 7);
	if (weeks < 5) return `${weeks}w ago`;
	const months = Math.floor(days / 30);
	if (months < 12) return `${months}mo ago`;
	return `${Math.floor(days / 365)}y ago`;
}

/** Total shot time across all segments, seconds. */
export function totalTime(segments: readonly ProfileSegment[]): number {
	return segments.reduce((a, s) => a + s.time, 0);
}

/** The leading pre-infusion seconds — the first segment's time, design parity. */
export function preinfuseSeconds(segments: readonly ProfileSegment[]): number {
	return segments.length > 0 ? Math.round(segments[0].time) : 0;
}

/**
 * Classify a profile's segment shape into one of {@link SparkShape}'s named
 * curves, so the card / chip thumbnail picks a sensible silhouette. A rough
 * heuristic over the segment targets — purely cosmetic.
 */
export function sparkShape(segments: readonly ProfileSegment[]): SparkShape {
	if (segments.length === 0) return 'classic';
	const peak = Math.max(...segments.map((s) => s.target));
	const first = segments[0];
	const last = segments[segments.length - 1];
	// A long, gentle opening segment reads as a bloom.
	if (first.time >= 10 && first.target <= 4) return 'blooming';
	// Ends well below its peak → a declining profile.
	if (last.target <= peak * 0.7) return 'decline';
	// Low peak pressure overall → a turbo / cold-style pull.
	if (peak <= 6) return first.time >= 12 ? 'cold' : 'turbo';
	// A pronounced early peak that then eases → Rao-style.
	if (segments.length >= 3 && segments[1].target >= peak * 0.95) return 'rao';
	return 'classic';
}

// ── Core ⇄ shell mapping ────────────────────────────────────────────────

/** Map a core `ProfileStep` into a working {@link ProfileSegment}. */
function segmentFromStep(step: ProfileStep, index: number): ProfileSegment {
	// The shell + core enums now share the same lowercase wire spelling
	// (v2 community contract — `'pressure'` / `'over'` / `'coffee'` /
	// …), so the exit fields pass straight through without remapping.
	const exit: SegmentExit | null = step.exit
		? {
				metric: step.exit.metric,
				compare: step.exit.compare,
				threshold: step.exit.threshold
			}
		: null;
	const limiter: SegmentLimiter | null = step.limiter
		? { value: step.limiter.value, range: step.limiter.range }
		: null;
	return {
		id: `s${index + 1}`,
		name: step.name || `Step ${index + 1}`,
		mode: step.pump,
		target: step.target,
		ramp: step.transition,
		// Keep the float — the DE1 protocol carries 0.1 s frame durations, so a
		// 6.5 s preinfusion must round-trip faithfully; rounding here truncated
		// every sub-second step.
		time: step.duration_seconds,
		exit,
		temp: step.temperature_c,
		tempSensor: step.temp_sensor,
		volumeLimitMl: step.volume_limit_ml,
		limiter
	};
}

/**
 * Map a working {@link ProfileSegment} back into a core `ProfileStep`.
 * The shell + core types now share lowercase enum strings (v2 contract),
 * so the segment fields pass straight through with no remapping.
 */
export function segmentToStep(seg: ProfileSegment): ProfileStep {
	return {
		name: seg.name,
		pump: seg.mode,
		target: seg.target,
		temperature_c: seg.temp,
		temp_sensor: seg.tempSensor,
		transition: seg.ramp,
		duration_seconds: seg.time,
		exit: seg.exit
			? {
					metric: seg.exit.metric,
					compare: seg.exit.compare,
					threshold: seg.exit.threshold
				}
			: null,
		volume_limit_ml: seg.volumeLimitMl,
		limiter: seg.limiter ? { value: seg.limiter.value, range: seg.limiter.range } : null,
		// No per-step weight target until §4.1b adds the editor row;
		// preserves whatever the segment already carries (currently
		// none in CremaProfile).
		weight: null
	};
}

/**
 * Built-in profiles whose title is the leaf of a `Tea/` or `Tea portafilter/`
 * group. The core's `Profile` JSON carries **no** `beverage_type` field (it is
 * only `title` / `notes` / `steps` / …), so tea is detected from the title
 * prefix — the one reliable signal the built-in corpus actually exposes.
 */
function isTeaProfile(title: string): boolean {
	const t = title.toLowerCase();
	return t.startsWith('tea/') || t.startsWith('tea portafilter/');
}

/**
 * Classify a built-in profile's roast suitability by reading its **title and
 * notes** together. Tea / cleaning / calibration / pour-over / steam profiles
 * have no roast (`null`); espresso profiles resolve from an explicit roast
 * phrase (most-specific first), a titled `default-*` slug, or `null` when the
 * notes never commit to one. Per-title overrides win. Roast stays **optional**:
 * `null` means "no roast clearly known", never "medium by default".
 *
 * Delegates to `de1_domain::roast_from_profile` via wasm (CORE3) — the phrase
 * table + overrides + tea/utility prefixes now live in the core so the
 * Profiles-page roast filter agrees across shells.
 */
function roastFromProfile(profile: Profile): Roast | null {
	return (wasmRoastFromProfile(profile.title, profile.notes) ?? null) as Roast | null;
}

/**
 * Adapt a core built-in {@link Profile} into a library {@link CremaProfile}.
 *
 * The core `Profile` has no library metadata (roast, tags, dose…), so those
 * are synthesised: roast is classified from the profile's title **and** notes
 * ({@link roastFromProfile}) and otherwise left unset (`null`), tea profiles
 * pick up a `'tea'` tag, dose / yield default to a sane 18 g / 36 g, and the
 * brew temperature is the profile's mean step temperature. The result is
 * read-only — editing a built-in duplicates it to a custom profile (see
 * `store.ts`).
 */
export function fromCoreProfile(profile: Profile, _index: number): CremaProfile {
	const segments = profile.steps.map(segmentFromStep);
	const temps = profile.steps.map((s) => s.temperature_c).filter((t) => t > 0);
	const meanTemp =
		temps.length > 0 ? temps.reduce((a, t) => a + t, 0) / temps.length : 92;
	const tags = ['Built-in'];
	if (isTeaProfile(profile.title)) tags.push('tea');
	return {
		// Pre-generated UUID v7 from `core/de1-domain/profiles/builtin.json`
		// (filled once by the `gen-builtin-ids` Rust binary). Stable across
		// rebuilds — reorderings in `builtin.json` no longer shuffle ids out
		// from under pinned / last-used state.
		id: profile.id,
		source: 'builtin',
		name: profile.title,
		notes: profile.notes,
		roast: roastFromProfile(profile),
		tags,
		pinned: false,
		lastUsed: null,
		// The dose is the profile's own `dose` (the legacy
		// `profile_grinder_dose_weight`); fall back to a neutral 18 g for a
		// profile that carries none.
		dose: profile.dose > 0 ? profile.dose : 18,
		// The yield target is the profile's DE1 `target_weight`; fall back to a
		// neutral default for a profile that carries none.
		yieldOut: profile.target_weight > 0 ? profile.target_weight : 36,
		brewTemp: Math.round(meanTemp * 2) / 2,
		stopOnWeight: true,
		autoTare: true,
		preinfuseStepCount: profile.preinfuse_step_count,
		maxTotalVolumeMl: profile.max_total_volume_ml,
		segments,
		// New v2 fields. #[serde(default)] on the Rust
		// side means built-in profiles loaded before this field existed
		// still deserialize — they arrive as empty / Espresso / 0 / "2".
		author: profile.author ?? '',
		beverageType: profile.beverage_type ?? 'espresso',
		tankTemperatureC: profile.tank_temperature ?? 0
	};
}

/**
 * Map a library {@link CremaProfile} back into a core {@link Profile} — used
 * when a profile would be uploaded to the DE1 (the upload itself is not yet
 * wired; see the `// TODO` in `store.ts`).
 */
export function toCoreProfile(p: CremaProfile): Profile {
	return {
		// Round-trip the CremaProfile's id back into the core `Profile`.
		// Useful for `uploadProfile` (the core never reads it) and any
		// future v2-export path that carries it (the current
		// `exportV2JsonProfile` strips it — the v2 contract has no
		// `id` field).
		id: p.id,
		title: p.name,
		notes: p.notes,
		steps: p.segments.map(segmentToStep),
		// Clamp the preinfuse count to the actual segment range — a profile can
		// never count more leading preinfusion steps than it has segments.
		preinfuse_step_count: Math.min(p.preinfuseStepCount, p.segments.length),
		// The DE1 ShotHeader pressure/flow limits are vestigial — the legacy app
		// always sets the per-frame IgnoreLimit flag, so the per-step limiter is
		// the real control. Emit the universal 0; not exposed in the editor.
		minimum_pressure: 0,
		maximum_flow: 0,
		max_total_volume_ml: p.maxTotalVolumeMl,
		// The yield target round-trips as the DE1 profile's `target_weight`.
		target_weight: p.yieldOut,
		// The dose round-trips as the DE1 profile's `dose` field.
		dose: p.dose,
		// New v2 fields.
		author: p.author,
		beverage_type: p.beverageType,
		tank_temperature: p.tankTemperatureC,
		version: '2'
	};
}

/** The default segment list for a brand-new profile — the design's `DEFAULT_SEGMENTS`. */
export function defaultSegments(): ProfileSegment[] {
	return [
		{
			id: 's1',
			name: 'Pre-infusion',
			mode: 'pressure',
			target: 4.0,
			ramp: 'smooth',
			time: 8,
			exit: { metric: 'flow', compare: 'over', threshold: 4 },
			temp: 92,
			tempSensor: 'coffee',
			volumeLimitMl: 0,
			limiter: null
		},
		{
			id: 's2',
			name: 'Ramp',
			mode: 'pressure',
			target: 9.0,
			ramp: 'smooth',
			time: 4,
			exit: null,
			temp: 92,
			tempSensor: 'coffee',
			volumeLimitMl: 0,
			limiter: null
		},
		{
			id: 's3',
			name: 'Hold',
			mode: 'pressure',
			target: 9.0,
			ramp: 'fast',
			time: 12,
			exit: null,
			temp: 92,
			tempSensor: 'coffee',
			volumeLimitMl: 0,
			limiter: null
		},
		{
			id: 's4',
			name: 'Decline',
			mode: 'pressure',
			target: 6.0,
			ramp: 'smooth',
			time: 8,
			exit: null,
			temp: 92,
			tempSensor: 'coffee',
			volumeLimitMl: 0,
			limiter: null
		}
	];
}

/** A fresh, empty custom profile — the starting point for `/profiles/new`. */
export function blankProfile(): CremaProfile {
	// Pull the user's brew defaults from the Settings store so a new profile
	// starts from the dose / ratio / temperature / preinfusion the user has
	// dialled in, rather than the historical hardcoded 18 g / 1:2 / 93 °C / 8 s.
	const prefs = getSettingsStore().current;
	const segments = defaultSegments();
	// `defaultPreinfusionS` is the *seconds* of the first preinfusion segment —
	// it is NOT the `preinfuseStepCount` (a count of leading segments).
	if (segments.length > 0) {
		segments[0] = { ...segments[0], time: prefs.defaultPreinfusionS };
	}
	return {
		id: newProfileId(),
		source: 'custom',
		name: '',
		notes: '',
		roast: null,
		tags: [],
		pinned: false,
		lastUsed: null,
		dose: prefs.defaultDoseG,
		yieldOut: prefs.defaultDoseG * prefs.defaultRatio,
		brewTemp: prefs.defaultBrewTempC,
		stopOnWeight: true,
		autoTare: true,
		preinfuseStepCount: 1,
		maxTotalVolumeMl: 0,
		segments,
		author: '',
		beverageType: 'espresso',
		tankTemperatureC: 0
	};
}

/**
 * Duplicate a profile into a new **custom** profile — used both for the
 * "Duplicate" action and when a built-in is edited (built-ins are read-only).
 * The copy gets a fresh id, a `(copy)` name suffix, and is owned by the user.
 */
export function duplicateProfile(p: CremaProfile): CremaProfile {
	return {
		...p,
		id: newProfileId(),
		source: 'custom',
		name: `${p.name} (copy)`,
		pinned: false,
		lastUsed: null,
		// Deep-copy the segments so edits to the copy never touch the original.
		segments: p.segments.map((s) => ({ ...s }))
	};
}
