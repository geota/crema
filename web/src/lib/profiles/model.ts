/**
 * `$lib/profiles/model` — the shell's working profile model and its mapping
 * to / from the core's `Profile` JSON shape.
 *
 * The design (`profile-edit-page.jsx`) edits a **segment-based** model: a
 * profile is an ordered list of pressure / flow segments plus metadata. That
 * is the shell's working model — {@link CremaProfile}. The core, meanwhile,
 * speaks `de1_domain::Profile` (a list of `ProfileStep`s).
 *
 * The **segment↔step adapter now lives in the Rust core**
 * (`de1_domain::crema_profile`), so every shell — web today, Android next —
 * shares one byte-identical mapping instead of re-implementing it per shell.
 * {@link fromCoreProfile} / {@link toCoreProfile} / {@link defaultSegments} /
 * {@link blankProfile} are thin JSON wrappers over the wasm exports
 * (`cremaProfileFromWire` / `cremaProfileToWire` / `defaultProfileSegments` /
 * `blankCremaProfile`) — the same JSON-boundary pattern the bean / shot /
 * Visualizer-wire converters use. The interfaces below stay hand-written: they
 * mirror the core's JSON shape (a Rust test pins it), exactly as the wire
 * `Profile` interface in `$lib/core` already does.
 *
 * A `CremaProfile` additionally carries the **library** metadata the cards and
 * editor need (roast, custom tags, pinned, last-used) which the core's
 * `Profile` has no field for — those are shell-managed and live in localStorage.
 *
 * Note `roast` is **recipe-suitability** metadata (which roast a recipe is
 * tuned for — it drives the Profiles page Light/Med/Dark filter), not bean
 * identity. The bag of coffee you are actually pulling lives in `$lib/bean`.
 */

import type { Profile, SparkShape } from './core-types';
import { newProfileId } from '$lib/core';
import {
	blankCremaProfile,
	builtinCremaProfiles as wasmBuiltinCremaProfiles,
	cremaProfileFromWire,
	cremaProfileToWire,
	defaultProfileSegments
} from '$lib/wasm/de1_wasm';
import { formatRatio } from '$lib/utils/ratio';
import { relativeAgo } from '$lib/utils/relative-time';
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
 * needed to round-trip a core `ProfileStep` losslessly. Mirrors the core's
 * `de1_domain::crema_profile::ProfileSegment` JSON shape.
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
 * profiles are created / edited and persisted in localStorage. Mirrors the
 * core's `de1_domain::crema_profile::CremaProfile` JSON shape.
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
	return relativeAgo(when, asOf);
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

// ── Core ⇄ shell mapping (the adapter lives in the Rust core) ───────────────
//
// `de1_domain::crema_profile` owns the segment↔step conversion, the built-in
// metadata synthesis (roast / tags / dose-yield defaults / mean brew temp),
// and the blank / default-segment templates. These four functions are the thin
// JSON boundary over its wasm exports — JSON in, JSON out — so the web and
// Android shells share one mapping. The former TypeScript `segmentFromStep` /
// `segmentToStep` / `roastFromProfile` / `isTeaProfile` helpers moved into the
// core and were removed here.

/**
 * Adapt a core built-in {@link Profile} into a library {@link CremaProfile}.
 *
 * The core synthesises the library metadata the wire `Profile` has no field
 * for: roast is classified from the profile's title + notes, tea profiles pick
 * up a `'tea'` tag, dose / yield fall back to 18 g / 36 g, and the brew
 * temperature is the mean step temperature. The result is read-only — editing
 * a built-in duplicates it to a custom profile (see `store.ts`). `_index` is
 * unused (kept for call-site compatibility).
 */
export function fromCoreProfile(profile: Profile, _index: number): CremaProfile {
	return JSON.parse(cremaProfileFromWire(JSON.stringify(profile))) as CremaProfile;
}

/**
 * Every built-in profile adapted into a {@link CremaProfile}, in **one** core
 * call. The library store's single read at startup — one JSON boundary crossing
 * for the whole corpus instead of one per profile (`builtinProfiles().map(
 * fromCoreProfile)`).
 */
export function builtinCremaProfiles(): CremaProfile[] {
	return JSON.parse(wasmBuiltinCremaProfiles()) as CremaProfile[];
}

/**
 * Map a library {@link CremaProfile} back into a core {@link Profile} — used
 * when a profile is uploaded to the DE1 or exported as v2 JSON. The
 * library-only metadata (roast / tags / pinned / lastUsed / stopOnWeight /
 * autoTare) is dropped; it has no wire field.
 */
export function toCoreProfile(p: CremaProfile): Profile {
	return JSON.parse(cremaProfileToWire(JSON.stringify(p))) as Profile;
}

/** The default segment list for a brand-new profile (core-owned). */
export function defaultSegments(): ProfileSegment[] {
	return JSON.parse(defaultProfileSegments()) as ProfileSegment[];
}

/**
 * A fresh, empty custom profile — the starting point for `/profiles/new`.
 *
 * Seeds dose / ratio / temperature / preinfusion from the user's Settings, then
 * lets the core build the rest (default segments, the first segment's
 * preinfusion time, and a freshly-minted UUID v7 id).
 */
export function blankProfile(): CremaProfile {
	const prefs = getSettingsStore().current;
	const defaults = JSON.stringify({
		doseG: prefs.defaultDoseG,
		ratio: prefs.defaultRatio,
		brewTempC: prefs.defaultBrewTempC,
		preinfusionS: prefs.defaultPreinfusionS
	});
	return JSON.parse(blankCremaProfile(defaults)) as CremaProfile;
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
