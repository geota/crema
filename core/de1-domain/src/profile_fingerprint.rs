//! # profile-fingerprint
//!
//! djb2 hash of an "effective profile" — the library {@link CremaProfile}
//! merged with the user's per-shot Quick Controls overrides — used to
//! decide whether the DE1 already has the right bytes loaded.
//!
//! Ported from the web shell's `web/src/lib/profiles/fingerprint.ts` so
//! both shells reach for the same algorithm via this crate. The shell
//! ships its profile shape as JSON; this function parses it, builds a
//! canonical key, and djb2-hashes the serialized key.
//!
//! Sans-IO and deterministic: takes JSON-as-string in, returns the hash
//! hex digest. Two distinct shells calling the same Rust fn on equivalent
//! input get the same hash by construction.
//!
//! ## Cache compatibility
//!
//! The legacy TS hash used `JSON.stringify` on a JS object literal; this
//! Rust impl uses `serde_json::to_string` on a Serialize struct. The
//! field order and key names match, but float formatting differs (TS
//! emits `36` for integer-valued doubles; Rust serde_json emits `36.0`).
//! As a result, the first load after the migration produces a different
//! hash from the cached one and triggers exactly one redundant upload —
//! the cache self-heals on the upload's `ProfileUploadCompleted` fold.
//! See `docs/42-shell-to-core-audit.md` finding #6.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// The fingerprint-relevant subset of a shell `CremaProfile`. Mirrors the
/// camelCase JSON shape the web shell ships.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShellProfile {
    id: String,
    dose: f64,
    yield_out: f64,
    brew_temp: f64,
    preinfuse_step_count: u32,
    max_total_volume_ml: u32,
    tank_temperature_c: f64,
    beverage_type: String,
    /// Segments stay as raw JSON — their fingerprint contribution is
    /// their serialized form, so passing them through untouched preserves
    /// the existing per-segment hash sensitivity.
    segments: Vec<Value>,
}

/// Per-shot Quick Controls overrides; every field optional. Mirrors the
/// shell's `ProfileFingerprintQc`.
#[derive(Debug, Deserialize, Default)]
struct ShellQc {
    #[serde(default)]
    dose: Option<f64>,
    #[serde(default, rename = "yield")]
    yield_: Option<f64>,
    #[serde(default, rename = "brewTemp")]
    brew_temp: Option<f64>,
    #[serde(default)]
    preinf: Option<f64>,
}

/// The canonical key that gets serialized + hashed. Field order matches
/// the legacy TS key declaration order; renames pin the JSON key names.
#[derive(Debug, Serialize)]
struct FingerprintKey<'a> {
    id: &'a str,
    dose: f64,
    #[serde(rename = "yield")]
    yield_: f64,
    #[serde(rename = "brewTemp")]
    brew_temp: f64,
    /// Omitted from the serialized form when `None`, matching JS
    /// `JSON.stringify` which drops `undefined` object values.
    #[serde(skip_serializing_if = "Option::is_none")]
    preinf: Option<f64>,
    #[serde(rename = "preinfuseStepCount")]
    preinfuse_step_count: u32,
    #[serde(rename = "maxTotalVolumeMl")]
    max_total_volume_ml: u32,
    #[serde(rename = "tankTemperatureC")]
    tank_temperature_c: f64,
    #[serde(rename = "beverageType")]
    beverage_type: &'a str,
    segments: &'a [Value],
}

/// Compute the effective-profile fingerprint. `qc_json` is optional —
/// pass `None` (or `"null"` / `"{}"`) for no overrides.
///
/// # Errors
///
/// Returns the JSON parse error string when either payload fails to
/// deserialise. The serialization step is infallible for the shapes we
/// build, but its error is propagated for completeness.
pub fn profile_fingerprint(profile_json: &str, qc_json: Option<&str>) -> Result<String, String> {
    let p: ShellProfile = serde_json::from_str(profile_json).map_err(|e| e.to_string())?;
    let qc: ShellQc = match qc_json {
        Some(s) if !s.is_empty() => serde_json::from_str(s).map_err(|e| e.to_string())?,
        _ => ShellQc::default(),
    };
    let key = FingerprintKey {
        id: &p.id,
        dose: qc.dose.unwrap_or(p.dose),
        yield_: qc.yield_.unwrap_or(p.yield_out),
        brew_temp: qc.brew_temp.unwrap_or(p.brew_temp),
        preinf: qc.preinf,
        preinfuse_step_count: p.preinfuse_step_count,
        max_total_volume_ml: p.max_total_volume_ml,
        tank_temperature_c: p.tank_temperature_c,
        beverage_type: &p.beverage_type,
        segments: &p.segments,
    };
    let s = serde_json::to_string(&key).map_err(|e| e.to_string())?;
    Ok(djb2_base36(&s))
}

/// djb2 with the legacy TS `.toString(36)` rendering — base-36 (digits +
/// lowercase letters), no padding, no prefix. Sibling of
/// `visualizer_sync::djb2` (which renders base-16); kept inline here so
/// the two surfaces stay independent.
fn djb2_base36(s: &str) -> String {
    let mut h: u32 = 5381;
    for c in s.chars() {
        let code = c as u32;
        h = h.wrapping_shl(5).wrapping_add(h).wrapping_add(code);
    }
    if h == 0 {
        return "0".to_owned();
    }
    const ALPHABET: &[u8; 36] = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let mut buf = Vec::with_capacity(7);
    let mut x = h;
    while x > 0 {
        buf.push(ALPHABET[(x % 36) as usize]);
        x /= 36;
    }
    buf.reverse();
    String::from_utf8(buf).expect("ALPHABET is ASCII")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn minimal_profile_json() -> &'static str {
        r#"{
            "id": "pf-1",
            "dose": 18.0,
            "yieldOut": 36.0,
            "brewTemp": 92.0,
            "preinfuseStepCount": 1,
            "maxTotalVolumeMl": 0,
            "tankTemperatureC": 0.0,
            "beverageType": "espresso",
            "segments": []
        }"#
    }

    #[test]
    fn fingerprint_is_stable_for_identical_inputs() {
        let a = profile_fingerprint(minimal_profile_json(), None).unwrap();
        let b = profile_fingerprint(minimal_profile_json(), None).unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn fingerprint_changes_when_yield_changes() {
        let base = profile_fingerprint(minimal_profile_json(), None).unwrap();
        let qc = r#"{"yield": 38.0}"#;
        let bumped = profile_fingerprint(minimal_profile_json(), Some(qc)).unwrap();
        assert_ne!(base, bumped);
    }

    #[test]
    fn fingerprint_changes_when_segments_change() {
        let base = profile_fingerprint(minimal_profile_json(), None).unwrap();
        let with_segment = r#"{
            "id": "pf-1",
            "dose": 18.0,
            "yieldOut": 36.0,
            "brewTemp": 92.0,
            "preinfuseStepCount": 1,
            "maxTotalVolumeMl": 0,
            "tankTemperatureC": 0.0,
            "beverageType": "espresso",
            "segments": [{"name": "preinf", "mode": "pressure", "target": 4.0}]
        }"#;
        let with = profile_fingerprint(with_segment, None).unwrap();
        assert_ne!(base, with);
    }

    #[test]
    fn qc_dose_overrides_profile_dose() {
        let qc_18 = profile_fingerprint(minimal_profile_json(), Some(r#"{"dose": 18.0}"#)).unwrap();
        let qc_20 = profile_fingerprint(minimal_profile_json(), Some(r#"{"dose": 20.0}"#)).unwrap();
        let no_qc = profile_fingerprint(minimal_profile_json(), None).unwrap();
        // qc_18 matches no_qc (qc.dose == profile.dose)
        assert_eq!(qc_18, no_qc);
        // qc_20 differs (qc wins over profile)
        assert_ne!(qc_18, qc_20);
    }

    #[test]
    fn preinf_qc_is_omitted_when_none() {
        // The omission matters: a `preinf: null` key in the canonical
        // form would hash differently from no `preinf` key at all.
        let no_qc = profile_fingerprint(minimal_profile_json(), None).unwrap();
        let empty_qc = profile_fingerprint(minimal_profile_json(), Some("{}")).unwrap();
        assert_eq!(no_qc, empty_qc);
    }

    #[test]
    fn djb2_base36_of_empty_string_is_the_seed() {
        // djb2's seed is 5381; in base 36 that's "45h". Pinning the
        // empty-input case is the cheapest "did the algorithm drift?"
        // check.
        assert_eq!(djb2_base36(""), "45h");
    }

    #[test]
    fn fingerprint_known_value_pins_the_algorithm() {
        // Pin the algorithm: any drift (struct field order, hash impl,
        // rendering) breaks this assertion. Treat divergence as a
        // signal, not a rewrite-target — the cache invalidation note
        // in the module docstring covers the one-shot consequence.
        let got = profile_fingerprint(minimal_profile_json(), None).unwrap();
        // Recorded 2026-05-26 from this implementation. A change here
        // means the canonical form drifted; intentional change → bump
        // this string + note the cache-invalidation impact in the PR.
        assert_eq!(got, "1xwseiv");
    }
}
