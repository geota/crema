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
 * The algorithm lives in Rust (`de1_domain::profile_fingerprint`) so the web
 * + Android shells share one byte-identical implementation. This module is a
 * thin adapter that hands the shell shape over the wasm boundary.
 */

import { profileFingerprint as wasmProfileFingerprint } from '$lib/wasm/de1_wasm';
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
 * Compute the effective-profile fingerprint by handing the shell shape
 * over to the core's `profile_fingerprint` wasm export. The Rust
 * algorithm is the canonical one (see
 * `core/de1-domain/src/profile_fingerprint.rs`); both shells go through
 * this single implementation.
 *
 * Note: the legacy in-shell hash and this wasm hash do not produce
 * identical digests for the same input (float formatting differs
 * between JS `JSON.stringify` and Rust `serde_json`). The first call
 * after the migration produces a different hash from the cached one;
 * the orchestrator treats the mismatch as a normal "need to upload",
 * triggers exactly one redundant upload, and the cache self-heals.
 */
export function profileFingerprint(
	profile: CremaProfile,
	qc: ProfileFingerprintQc = {}
): string {
	return wasmProfileFingerprint(JSON.stringify(profile), JSON.stringify(qc));
}
