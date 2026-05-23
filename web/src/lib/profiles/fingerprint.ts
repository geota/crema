/**
 * `$lib/profiles/fingerprint` — a stable, cheap hash of an "effective profile"
 * (a library {@link CremaProfile} merged with the user's per-shot Quick
 * Controls overrides) used to decide whether the DE1 already has the right
 * bytes loaded.
 *
 * Compared against a cached "what's currently on the DE1" fingerprint
 * (`UiSnapshot.activeProfileFingerprint`, set on `ProfileUploadCompleted` and
 * cleared on disconnect) at two checkpoints:
 *
 *  - On `de1State === 'ready'`, the orchestrator's `ensureLoadedMatches()`
 *    auto-uploads the active profile when the cached fingerprint is `null` or
 *    differs from the current intent.
 *  - On `startShot()`, the same comparison runs again — a user dial change to
 *    dose / yield / brew temp / pre-infusion that doesn't actually change the
 *    upload bytes (no override) is a no-op; one that does triggers an upload +
 *    `ProfileUploadCompleted` await before the Espresso state request lands.
 *
 * The hash is djb2 over a stable JSON serialization — no crypto, no
 * collision-resistance claim, just a cheap stable comparator. The same
 * shell-side function feeds both the connect-time and shot-start checks so
 * the two checkpoints can never disagree.
 */

import type { CremaProfile } from './model';

/**
 * The subset of {@link BrewParams} that genuinely affects the upload bytes
 * — anything else (steam time, hot-water volume, the chart toggles, …) the
 * profile-upload path doesn't carry.
 *
 * Kept narrow on purpose: only fields whose change would produce different
 * bytes on the DE1 belong here. Mode toggles (`doseGrindMode` etc.) are pure
 * UI affordance and never reach the wire.
 */
export interface ProfileFingerprintQc {
	/** Per-shot dose override (g) — wins over the profile's own dose. */
	dose?: number;
	/** Per-shot yield override (g) — wins over the profile's own yield. */
	yield?: number;
	/** Per-shot brew-temperature override (°C) — overrides every step's temp. */
	brewTemp?: number;
	/**
	 * Per-shot pre-infusion override (s) — caps the leading preinfusion
	 * segments' duration. The shot-frame upload doesn't tag fields as
	 * "from profile" vs "from override", so a UI change here counts as
	 * upload-affecting even if the user merely confirmed the profile's
	 * own value.
	 */
	preinf?: number;
}

/**
 * djb2 string hash — Bernstein's classic, terse, no allocation per byte.
 * Returns a 32-bit unsigned integer rendered as base-36 for compact display
 * in logs. Stable across runs; **not** cryptographic.
 */
function djb2(s: string): string {
	// Seed 5381 is djb2's canonical magic constant.
	let h = 5381;
	for (let i = 0; i < s.length; i++) {
		// `(h << 5) + h` is `h * 33` without the multiply; `>>> 0` keeps
		// the running value as an unsigned 32-bit int.
		h = ((h << 5) + h + s.charCodeAt(i)) >>> 0;
	}
	return h.toString(36);
}

/**
 * Compute the effective-profile fingerprint — a djb2 of a stable
 * serialization of the fields that influence the upload bytes.
 *
 * Per-shot Quick Controls overrides (when present) take precedence over
 * the profile's own values, so two dial changes that converge on the
 * same final numbers produce identical fingerprints. The id is folded
 * in last so two distinct profiles with identical numbers (rare but
 * possible — e.g. a `(copy)`) still hash apart.
 *
 * The segment list is serialised verbatim — its shape is *the* upload
 * payload, so the hash must change the moment any segment field does
 * (target, mode, ramp, time, exit, limiter, temperature, sensor,
 * volume limit). `JSON.stringify` produces deterministic output for
 * Crema's plain-object segment shape (no Maps / Sets / undefined
 * fields), so a stable serialisation drops out for free.
 */
export function profileFingerprint(
	profile: CremaProfile,
	qc: ProfileFingerprintQc = {}
): string {
	const key = {
		id: profile.id,
		dose: qc.dose ?? profile.dose,
		yield: qc.yield ?? profile.yieldOut,
		brewTemp: qc.brewTemp ?? profile.brewTemp,
		preinf: qc.preinf,
		preinfuseStepCount: profile.preinfuseStepCount,
		maxTotalVolumeMl: profile.maxTotalVolumeMl,
		tankTemperatureC: profile.tankTemperatureC,
		beverageType: profile.beverageType,
		segments: profile.segments
	};
	return djb2(JSON.stringify(key));
}
