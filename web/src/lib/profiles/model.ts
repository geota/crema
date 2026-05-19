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

/** Roast level — a fixed library filter facet. */
export type Roast = 'light' | 'medium' | 'dark';

/** How a segment ramps to its target — the design's `ramp` field. */
export type SegmentRamp = 'smooth' | 'fast';

/** Whether a segment targets a pressure (bar) or a flow (mL/s). */
export type SegmentMode = 'pressure' | 'flow';

/** Which metric an exit condition watches. */
export type ExitMetric = 'pressure' | 'flow';

/** The direction of an exit comparison. */
export type ExitCompare = 'over' | 'under';

/** Which temperature sensor a segment regulates. */
export type TempSensor = 'basket' | 'mix';

/**
 * A structured early-exit condition on a segment — leave the segment before
 * its duration elapses. Maps to the core's `ExitCondition`.
 */
export interface SegmentExit {
	/** Which metric to watch. */
	metric: ExitMetric;
	/** Exit when the metric rises above (`over`) or falls below (`under`). */
	compare: ExitCompare;
	/** The threshold value (bar or mL/s, per `metric`). */
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
	/** Target value — bar (pressure) or mL/s (flow). */
	target: number;
	/** How the segment ramps to its target. */
	ramp: SegmentRamp;
	/** Segment duration, seconds. */
	time: number;
	/** Structured early-exit condition, or null when the segment has none. */
	exit: SegmentExit | null;
	/** Target temperature, °C — round-trips the core step. */
	temperatureC: number;
	/** Which temperature sensor the segment regulates. */
	tempSensor: TempSensor;
	/** Per-segment dispensed-volume limit, mL, range 0–1023 (0 = no limit). */
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
	/** Stable id. Built-ins are `builtin:<index>`; custom are `custom:<uuid>`. */
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
	yieldG: number;
	/** Brew temperature, °C. */
	brewTemp: number;
	/** End the shot when the scale reaches the yield target. */
	stopOnWeight: boolean;
	/** Zero the scale automatically when the shot begins. */
	autoTare: boolean;
	/** How many leading segments count as preinfusion — core `preinfuse_step_count`. */
	preinfuseStepCount: number;
	/** Whole-shot dispensed-volume limit, mL, 0–1023 (0 = no limit). */
	maxTotalVolumeMl: number;
	/** The ordered pressure / flow segments. */
	segments: ProfileSegment[];
}

/** A short id for a custom profile / segment — `crypto.randomUUID` if present. */
export function uid(prefix: string): string {
	const rnd =
		typeof crypto !== 'undefined' && 'randomUUID' in crypto
			? crypto.randomUUID()
			: Math.random().toString(36).slice(2) + Date.now().toString(36);
	return `${prefix}:${rnd}`;
}

/** The brew ratio label, e.g. `1:2.4`, derived from yield ÷ dose. */
export function ratioLabel(p: Pick<CremaProfile, 'dose' | 'yieldG'>): string {
	if (p.dose <= 0) return '1:—';
	return `1:${(p.yieldG / p.dose).toFixed(1)}`;
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
	// The core enums are PascalCase (`Pressure` / `Over` / `Mix`); the shell
	// model is lowercase — translate each on the way in, as `pump` / `transition`
	// already do.
	const exit: SegmentExit | null = step.exit
		? {
				metric: step.exit.metric === 'Pressure' ? 'pressure' : 'flow',
				compare: step.exit.compare === 'Over' ? 'over' : 'under',
				threshold: step.exit.threshold
			}
		: null;
	const limiter: SegmentLimiter | null = step.limiter
		? { value: step.limiter.value, range: step.limiter.range }
		: null;
	return {
		id: `s${index + 1}`,
		name: step.name || `Step ${index + 1}`,
		mode: step.pump === 'Flow' ? 'flow' : 'pressure',
		target: step.target,
		ramp: step.transition === 'Smooth' ? 'smooth' : 'fast',
		time: Math.round(step.duration_seconds),
		exit,
		temperatureC: step.temperature_c,
		tempSensor: step.temp_sensor === 'Mix' ? 'mix' : 'basket',
		volumeLimitMl: step.volume_limit_ml,
		limiter
	};
}

/** Map a working {@link ProfileSegment} back into a core `ProfileStep`. */
export function segmentToStep(seg: ProfileSegment): ProfileStep {
	return {
		name: seg.name,
		pump: seg.mode === 'flow' ? 'Flow' : 'Pressure',
		target: seg.target,
		temperature_c: seg.temperatureC,
		temp_sensor: seg.tempSensor === 'mix' ? 'Mix' : 'Basket',
		transition: seg.ramp === 'smooth' ? 'Smooth' : 'Fast',
		duration_seconds: seg.time,
		// The structured exit condition round-trips losslessly — the lowercase
		// shell enums map back to the core's PascalCase variants.
		exit: seg.exit
			? {
					metric: seg.exit.metric === 'pressure' ? 'Pressure' : 'Flow',
					compare: seg.exit.compare === 'over' ? 'Over' : 'Under',
					threshold: seg.exit.threshold
				}
			: null,
		volume_limit_ml: seg.volumeLimitMl,
		limiter: seg.limiter ? { value: seg.limiter.value, range: seg.limiter.range } : null
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
 * Built-in profiles whose title is the leaf of a non-coffee utility group —
 * cleaning, calibration / leak tests, or steam-only. These never have a roast.
 */
function isUtilityProfile(title: string): boolean {
	const t = title.toLowerCase();
	return (
		t.startsWith('cleaning/') ||
		t.startsWith('test/') ||
		t.startsWith('ghc/') ||
		t === 'steam only'
	);
}

/**
 * Explicit roast overrides keyed by **exact built-in title**. Used where the
 * title + notes heuristic in {@link roastFromProfile} would mis-read a profile
 * — typically because the notes mention roast comparatively ("works from
 * lighter to darker roasts") rather than prescriptively. Accuracy first.
 */
const ROAST_OVERRIDES: Record<string, Roast | null> = {
	// Adaptive / "wide grind range" profiles: notes name *both* light and dark
	// roasts to describe their span, so no single roast applies.
	'Gagné/Adaptive Shot 92C v1.0': null,
	'Easy blooming - active pressure decline': null,
	'Adaptive v2': null,
	'I got your back': null,
	// Turbo profiles target high-extraction grinders, not a roast level.
	TurboBloom: null,
	TurboTurbo: null,
	'Extractamundo Dos!': null
};

/**
 * Ordered roast phrases, most-specific first. Each maps a phrase that may
 * appear in a profile's title or notes to a roast level. "medium to dark" must
 * be tested before the bare "dark" / "medium" words so a range resolves to its
 * dominant end the same way a barista would read it.
 */
const ROAST_PHRASES: ReadonlyArray<readonly [RegExp, Roast]> = [
	// Explicit prescriptive ranges — resolve to the end the notes lead with.
	[/\bmedium[\s-]*to[\s-]*dark\b/, 'dark'],
	[/\bmedium[\s-]*dark\b/, 'dark'],
	[/\bdark[\s-]*to[\s-]*medium\b/, 'dark'],
	[/\bmedium[\s-]*to[\s-]*light\b/, 'light'],
	[/\bmedium[\s-]*light\b/, 'light'],
	[/\blight[\s-]*to[\s-]*medium\b/, 'light'],
	// Single prescriptive roast words / phrases.
	[/\b(?:very[\s-]+)?dark[\s-]+roast(?:ed|s)?\b/, 'dark'],
	[/\b(?:a\s+)?dark[\s-]+roast\b/, 'dark'],
	[/\bdark[\s-]+roasted\b/, 'dark'],
	[/\bmedium[\s-]+roast(?:ed|s)?\b/, 'medium'],
	[/\b(?:light(?:ly)?)[\s-]+roast(?:ed|s)?\b/, 'light']
];

/**
 * Classify a built-in profile's roast by reading its **title and notes**
 * together (case-insensitive). Tea / cleaning / calibration / pour-over /
 * steam profiles have no roast and resolve to `null`; espresso profiles
 * resolve from an explicit roast phrase, or `null` when the notes never
 * commit to one. An explicit {@link ROAST_OVERRIDES} entry always wins — it
 * is the escape hatch for profiles the phrase heuristic mis-reads.
 *
 * Roast stays **optional**: `null` means "no roast clearly known", never
 * "medium by default".
 */
function roastFromProfile(profile: Profile): Roast | null {
	if (profile.title in ROAST_OVERRIDES) return ROAST_OVERRIDES[profile.title];
	// Non-coffee and pour-over profiles never carry a roast.
	if (isTeaProfile(profile.title) || isUtilityProfile(profile.title)) {
		return null;
	}
	const text = `${profile.title}\n${profile.notes}`.toLowerCase();
	for (const [phrase, roast] of ROAST_PHRASES) {
		if (phrase.test(text)) return roast;
	}
	// Titled roast hints used by the A-Flow family (`default-dark`,
	// `default-very-dark`, `default-medium`) — these carry the roast in the
	// title slug, not in the notes.
	const title = profile.title.toLowerCase();
	if (/\bvery[\s-]*dark\b|\bdefault-dark\b/.test(title)) return 'dark';
	if (/\bdefault-medium\b/.test(title)) return 'medium';
	if (/\bdefault-light\b/.test(title)) return 'light';
	return null;
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
export function fromCoreProfile(profile: Profile, index: number): CremaProfile {
	const segments = profile.steps.map(segmentFromStep);
	const temps = profile.steps.map((s) => s.temperature_c).filter((t) => t > 0);
	const meanTemp =
		temps.length > 0 ? temps.reduce((a, t) => a + t, 0) / temps.length : 92;
	const tags = ['Built-in'];
	if (isTeaProfile(profile.title)) tags.push('tea');
	return {
		id: `builtin:${index}`,
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
		yieldG: profile.target_weight > 0 ? profile.target_weight : 36,
		brewTemp: Math.round(meanTemp * 2) / 2,
		stopOnWeight: true,
		autoTare: true,
		preinfuseStepCount: profile.preinfuse_step_count,
		maxTotalVolumeMl: profile.max_total_volume_ml,
		segments
	};
}

/**
 * Map a library {@link CremaProfile} back into a core {@link Profile} — used
 * when a profile would be uploaded to the DE1 (the upload itself is not yet
 * wired; see the `// TODO` in `store.ts`).
 */
export function toCoreProfile(p: CremaProfile): Profile {
	return {
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
		target_weight: p.yieldG,
		// The dose round-trips as the DE1 profile's `dose` field.
		dose: p.dose
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
			temperatureC: 92,
			tempSensor: 'basket',
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
			temperatureC: 92,
			tempSensor: 'basket',
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
			temperatureC: 92,
			tempSensor: 'basket',
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
			temperatureC: 92,
			tempSensor: 'basket',
			volumeLimitMl: 0,
			limiter: null
		}
	];
}

/** A fresh, empty custom profile — the starting point for `/profiles/new`. */
export function blankProfile(): CremaProfile {
	return {
		id: uid('custom'),
		source: 'custom',
		name: '',
		notes: '',
		roast: null,
		tags: [],
		pinned: false,
		lastUsed: null,
		dose: 18,
		yieldG: 36,
		brewTemp: 93,
		stopOnWeight: true,
		autoTare: true,
		preinfuseStepCount: 1,
		maxTotalVolumeMl: 0,
		segments: defaultSegments()
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
		id: uid('custom'),
		source: 'custom',
		name: `${p.name} (copy)`,
		pinned: false,
		lastUsed: null,
		// Deep-copy the segments so edits to the copy never touch the original.
		segments: p.segments.map((s) => ({ ...s }))
	};
}
