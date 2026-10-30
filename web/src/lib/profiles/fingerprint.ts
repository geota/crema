/**
 * `#lib/profiles/fingerprint` — the effective profile: a library
 * {@link CremaProfile} with the user's per-shot Quick Controls overrides
 * applied, and its fingerprint.
 *
 * Every rule lives in the core (`de1_domain::effective_profile`), so the web and
 * Android shells apply the same one:
 *
 *  - brew temperature is an **absolute** target; every step shifts by the
 *    difference from the profile's own brew temperature, clamped so no step
 *    leaves the core's temperature bounds ({@link brewTempRange});
 *  - pre-infusion is the total time of the leading pre-infusion segments
 *    ({@link preinfusionSeconds}), rescaled by an override;
 *  - an override equal to the profile's own value is no override
 *    ({@link normalizeBrewOverrides}).
 *
 * The fingerprint ({@link profileFingerprint}) is the hash of the effective
 * profile — exactly the bytes `CremaCore.uploadCremaProfile` writes — compared
 * against the cached "what's on the DE1" fingerprint
 * (`UiSnapshot.activeProfileFingerprint`) on connect and at shot start.
 */

import {
	brewTempRange as wasmBrewTempRange,
	effectiveCremaProfile as wasmEffectiveCremaProfile,
	effectiveProfileFingerprint as wasmEffectiveProfileFingerprint,
	normalizeBrewOverrides as wasmNormalizeBrewOverrides,
	preinfusionDial as wasmPreinfusionDial,
	preinfusionSeconds as wasmPreinfusionSeconds,
	withDerivedBrewTemp as wasmWithDerivedBrewTemp
} from '#lib/wasm/de1_wasm.js';
import type { BrewOverrides, BrewTempRange, PreinfusionDial } from '#lib/core/crema-core.js';
import type { CremaProfile } from './model';

/**
 * The per-shot Quick Controls overrides — absolute values, each optional
 * (absent = the profile's own value). The core's `BrewOverrides`.
 */
export type ProfileFingerprintOverrides = BrewOverrides;

export type { BrewTempRange, PreinfusionDial };

/**
 * The fingerprint of the effective profile (`profile` with `qc` applied by the
 * core) — the hash of exactly the bytes an upload of `(profile, qc)` writes.
 */
export function profileFingerprint(
	profile: CremaProfile,
	qc: ProfileFingerprintOverrides = {}
): string {
	return wasmEffectiveProfileFingerprint(JSON.stringify(profile), JSON.stringify(qc));
}

/**
 * The effective profile itself — what an upload of `(profile, qc)` sends, and
 * what "Save preset" saves (the overrides baked into the segments).
 */
export function effectiveCremaProfile(
	profile: CremaProfile,
	qc: ProfileFingerprintOverrides
): CremaProfile {
	return JSON.parse(
		wasmEffectiveCremaProfile(JSON.stringify(profile), JSON.stringify(qc))
	) as CremaProfile;
}

/**
 * `qc` after the core's override lifecycle rule: the brew temperature clamped
 * into the profile's range, every value equal to the profile's own dropped.
 */
export function normalizeBrewOverrides(
	profile: CremaProfile,
	qc: ProfileFingerprintOverrides
): ProfileFingerprintOverrides {
	return JSON.parse(
		wasmNormalizeBrewOverrides(JSON.stringify(profile), JSON.stringify(qc))
	) as ProfileFingerprintOverrides;
}

/** The brew-temperature dial's range and anchor for `profile` (core-owned). */
export function brewTempRange(profile: CremaProfile): BrewTempRange {
	return JSON.parse(wasmBrewTempRange(JSON.stringify(profile))) as BrewTempRange;
}

/** The profile's pre-infusion seconds: the total of its leading pre-infusion segments. */
export function preinfusionSeconds(profile: CremaProfile): number {
	return wasmPreinfusionSeconds(JSON.stringify(profile));
}

/**
 * The pre-infusion dial for `profile` (core-owned): its range (always holding
 * the profile's own value), the profile's seconds, and whether it can be
 * dialled at all — a profile with no pre-infusion segment can't.
 */
export function preinfusionDial(profile: CremaProfile): PreinfusionDial {
	return JSON.parse(wasmPreinfusionDial(JSON.stringify(profile))) as PreinfusionDial;
}

/**
 * `profile` with its top-level `brewTemp` derived from the first step's
 * temperature — the brew-temp dial's anchor — so the cards, the dial and the
 * editor show the same number. Applied on load and save. A profile the core
 * can't read is returned as it is.
 */
export function withDerivedBrewTemp(profile: CremaProfile): CremaProfile {
	try {
		return JSON.parse(wasmWithDerivedBrewTemp(JSON.stringify(profile))) as CremaProfile;
	} catch {
		return profile;
	}
}
