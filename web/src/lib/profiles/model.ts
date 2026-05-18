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
 * editor need (bean, roast, custom tags, pinned, last-used) which the core's
 * `Profile` has no field for — those are shell-only and live in localStorage.
 */

import type { Profile, ProfileStep, SparkShape } from './core-types';

/** Roast level — a fixed library filter facet. */
export type Roast = 'light' | 'medium' | 'dark';

/** How a segment ramps to its target — the design's `ramp` field. */
export type SegmentRamp = 'smooth' | 'fast';

/** Whether a segment targets a pressure (bar) or a flow (mL/s). */
export type SegmentMode = 'pressure' | 'flow';

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
	/** Human-readable early-exit description, or null. */
	exitAt: string | null;
	/** Target temperature, °C — round-trips the core step. */
	temperatureC: number;
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
	/** Bean / origin. */
	bean: string;
	/** Free-text notes. */
	notes: string;
	/** Roast level, or `null` when no roast is clearly known. */
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
	let exitAt: string | null = null;
	if (step.exit) {
		const unit = step.exit.metric === 'Pressure' ? 'bar' : 'ml/s';
		const op = step.exit.compare === 'Over' ? '>' : '<';
		const metric = step.exit.metric === 'Pressure' ? 'pressure' : 'flow';
		exitAt = `${metric} ${op} ${step.exit.threshold} ${unit}`;
	}
	return {
		id: `s${index + 1}`,
		name: step.name || `Step ${index + 1}`,
		mode: step.pump === 'Flow' ? 'flow' : 'pressure',
		target: step.target,
		ramp: step.transition === 'Smooth' ? 'smooth' : 'fast',
		time: Math.round(step.duration_seconds),
		exitAt,
		temperatureC: step.temperature_c
	};
}

/** Map a working {@link ProfileSegment} back into a core `ProfileStep`. */
export function segmentToStep(seg: ProfileSegment): ProfileStep {
	return {
		name: seg.name,
		pump: seg.mode === 'flow' ? 'Flow' : 'Pressure',
		target: seg.target,
		temperature_c: seg.temperatureC,
		temp_sensor: 'Basket',
		transition: seg.ramp === 'smooth' ? 'Smooth' : 'Fast',
		duration_seconds: seg.time,
		// The shell edits the human exit label only; the structured exit
		// condition is dropped on the round-trip (built-ins keep theirs in
		// `core-types`-land, custom profiles do not set machine exit gates yet).
		exit: null,
		volume_limit_ml: 0,
		limiter: null
	};
}

/**
 * Infer a roast for an adapted built-in **only** from an explicit roast word
 * in its name (case-insensitive). If the name carries no clear roast word the
 * roast is left unset (`null`) — roast is never guessed from anything else.
 */
function roastFromName(name: string): Roast | null {
	const n = name.toLowerCase();
	// `very dark` is covered by the `dark` substring; `french` / `italian`
	// roasts are conventionally dark.
	if (/\b(dark|french|italian)\b/.test(n)) return 'dark';
	if (/\b(medium|med)\b/.test(n)) return 'medium';
	if (/\b(light|blonde|nordic)\b/.test(n)) return 'light';
	return null;
}

/**
 * Adapt a core built-in {@link Profile} into a library {@link CremaProfile}.
 *
 * The core `Profile` has no library metadata (bean, roast, tags, dose…), so
 * those are synthesised: bean is left blank, roast is inferred only from an
 * explicit roast word in the profile name and otherwise left unset (`null`),
 * dose / yield default to a sane 18 g / 36 g, and the brew temperature is the
 * profile's mean step temperature. The result is read-only — editing a
 * built-in duplicates it to a custom profile (see `store.ts`).
 */
export function fromCoreProfile(profile: Profile, index: number): CremaProfile {
	const segments = profile.steps.map(segmentFromStep);
	const temps = profile.steps.map((s) => s.temperature_c).filter((t) => t > 0);
	const meanTemp =
		temps.length > 0 ? temps.reduce((a, t) => a + t, 0) / temps.length : 92;
	return {
		id: `builtin:${index}`,
		source: 'builtin',
		name: profile.title,
		bean: '',
		notes: profile.notes,
		roast: roastFromName(profile.title),
		tags: ['Built-in'],
		pinned: false,
		lastUsed: null,
		dose: 18,
		yieldG: 36,
		brewTemp: Math.round(meanTemp * 2) / 2,
		stopOnWeight: true,
		autoTare: true,
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
		preinfuse_step_count: p.segments.length > 0 ? 1 : 0,
		minimum_pressure: 1,
		maximum_flow: 6,
		max_total_volume_ml: 0
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
			exitAt: 'flow > 4 ml/s',
			temperatureC: 92
		},
		{
			id: 's2',
			name: 'Ramp',
			mode: 'pressure',
			target: 9.0,
			ramp: 'smooth',
			time: 4,
			exitAt: null,
			temperatureC: 92
		},
		{
			id: 's3',
			name: 'Hold',
			mode: 'pressure',
			target: 9.0,
			ramp: 'fast',
			time: 12,
			exitAt: null,
			temperatureC: 92
		},
		{
			id: 's4',
			name: 'Decline',
			mode: 'pressure',
			target: 6.0,
			ramp: 'smooth',
			time: 8,
			exitAt: 'weight ≥ target',
			temperatureC: 92
		}
	];
}

/** A fresh, empty custom profile — the starting point for `/profiles/new`. */
export function blankProfile(): CremaProfile {
	return {
		id: uid('custom'),
		source: 'custom',
		name: '',
		bean: '',
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
