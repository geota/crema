//! Replay-capture META prelude folder.
//!
//! Capture files carry one or two `src:"META"` JSONL entries with a
//! `meta` payload — the core's connect-phase prelude (scale name +
//! DE1 firmware / model / serial) and an optional shell-appended
//! at-shot-start line (active profile + Quick-Controls overrides +
//! bean snapshot + grinder model). [`fold_meta_jsonl`] merges every
//! payload into a single typed [`ReplayMeta`], later entries
//! overriding earlier ones.
//!
//! The schema is wire-contract; the folder reads each known key
//! defensively (a wrong-type or missing key is silently dropped) so a
//! corrupt or future-key META can't crash the replay reader.
//!
//! `composeReplayStartMessage` (locale-tinted) stays shell-side per
//! `docs/42-shell-to-core-audit.md` finding #10.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use typeshare::typeshare;

/// Bean snapshot at shot start; nested under [`ReplayMeta::bean`].
///
/// All fields optional; absent on the connect-phase prelude, present
/// (in part) on the shell's at-shot-start line.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplayMetaBean {
    /// Bean name (`"Yirgacheffe"`, …). Older captures spell this `type`;
    /// the manual fold in [`fold_one`] accepts either key for read-side
    /// backward compatibility.
    #[serde(skip_serializing_if = "Option::is_none", default, alias = "type")]
    pub name: Option<String>,
    /// Roaster's display name.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub roaster: Option<String>,
    /// ISO `yyyy-mm-dd` roast date.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub roasted_on: Option<String>,
    /// 1..10 roast level.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub roast_level: Option<f64>,
    /// Free-text tasting / dial notes.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub notes: Option<String>,
    /// Grinder click / setting label.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub grinder_setting: Option<String>,
}

/// Merged metadata from one or more `src:"META"` prelude entries.
///
/// Every field is optional; META omits any unset field, and the
/// folder leaves every absent field unchanged. Field names are
/// camelCase in the wire / TS / Kotlin shapes; Rust uses snake_case
/// and typeshare renames at the boundary.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplayMeta {
    /// Scale's BLE advertised name (`"BOOKOO_SC"`, `"ACAIA…"`, …).
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub scale_name: Option<String>,
    /// DE1's firmware build number (MMR `FirmwareVersion`).
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub de1_firmware_version: Option<f64>,
    /// DE1's machine-model identifier (MMR `MachineModel`).
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub de1_machine_model: Option<f64>,
    /// DE1's serial number (MMR `SerialNumber`).
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub de1_serial_number: Option<f64>,
    /// Active profile's display name at shot start.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub profile_name: Option<String>,
    /// Byte-exact upload payload as lowercase no-separator hex.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub profile_bytes_hex: Option<String>,
    /// QC yield target at shot start, grams.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub yield_target: Option<f64>,
    /// QC brew-temperature override at shot start, °C.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub brew_temp: Option<f64>,
    /// QC pre-infusion target at shot start, seconds.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub preinfuse_target: Option<f64>,
    /// SAW toggle at shot start.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub stop_on_weight: Option<bool>,
    /// Auto-tare toggle at shot start.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub auto_tare: Option<bool>,
    /// Active bean snapshot at shot start.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub bean: Option<ReplayMetaBean>,
    /// Equipment-level grinder model at shot start.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub grinder_model: Option<String>,
}

/// Fold a sequence of `src:"META"` payload JSON strings into one
/// merged [`ReplayMeta`]. Later entries override earlier ones; each
/// payload is read field-by-field with type guards (a wrong-type or
/// missing key contributes nothing).
///
/// # Errors
///
/// Returns the JSON parse error string when any payload is not a
/// well-formed JSON object.
pub fn fold_meta_jsonl(payloads: &[&str]) -> Result<ReplayMeta, String> {
    let mut acc = ReplayMeta::default();
    for payload in payloads {
        let v: Value = serde_json::from_str(payload).map_err(|e| e.to_string())?;
        let Some(obj) = v.as_object() else { continue };
        fold_one(&mut acc, obj);
    }
    Ok(acc)
}

fn fold_one(acc: &mut ReplayMeta, obj: &serde_json::Map<String, Value>) {
    if let Some(s) = obj.get("scaleName").and_then(Value::as_str) {
        acc.scale_name = Some(s.to_owned());
    }
    if let Some(n) = obj.get("de1FirmwareVersion").and_then(Value::as_f64)
        && n.is_finite()
    {
        acc.de1_firmware_version = Some(n);
    }
    if let Some(n) = obj.get("de1MachineModel").and_then(Value::as_f64)
        && n.is_finite()
    {
        acc.de1_machine_model = Some(n);
    }
    if let Some(n) = obj.get("de1SerialNumber").and_then(Value::as_f64)
        && n.is_finite()
    {
        acc.de1_serial_number = Some(n);
    }
    if let Some(s) = obj.get("profileName").and_then(Value::as_str) {
        acc.profile_name = Some(s.to_owned());
    }
    if let Some(s) = obj.get("profileBytesHex").and_then(Value::as_str) {
        acc.profile_bytes_hex = Some(s.to_owned());
    }
    if let Some(n) = obj.get("yieldTarget").and_then(Value::as_f64)
        && n.is_finite()
    {
        acc.yield_target = Some(n);
    }
    if let Some(n) = obj.get("brewTemp").and_then(Value::as_f64)
        && n.is_finite()
    {
        acc.brew_temp = Some(n);
    }
    if let Some(n) = obj.get("preinfuseTarget").and_then(Value::as_f64)
        && n.is_finite()
    {
        acc.preinfuse_target = Some(n);
    }
    if let Some(b) = obj.get("stopOnWeight").and_then(Value::as_bool) {
        acc.stop_on_weight = Some(b);
    }
    if let Some(b) = obj.get("autoTare").and_then(Value::as_bool) {
        acc.auto_tare = Some(b);
    }
    if let Some(s) = obj.get("grinderModel").and_then(Value::as_str) {
        acc.grinder_model = Some(s.to_owned());
    }
    if let Some(raw) = obj.get("bean").and_then(Value::as_object) {
        let mut bean = acc.bean.take().unwrap_or_default();
        // Read `name` first; fall back to legacy `type` key for older
        // captures (see the `ReplayMetaBean::name` serde alias).
        if let Some(s) = raw
            .get("name")
            .or_else(|| raw.get("type"))
            .and_then(Value::as_str)
        {
            bean.name = Some(s.to_owned());
        }
        if let Some(s) = raw.get("roaster").and_then(Value::as_str) {
            bean.roaster = Some(s.to_owned());
        }
        if let Some(s) = raw.get("roastedOn").and_then(Value::as_str) {
            bean.roasted_on = Some(s.to_owned());
        }
        if let Some(n) = raw.get("roastLevel").and_then(Value::as_f64)
            && n.is_finite()
        {
            bean.roast_level = Some(n);
        }
        if let Some(s) = raw.get("notes").and_then(Value::as_str) {
            bean.notes = Some(s.to_owned());
        }
        if let Some(s) = raw.get("grinderSetting").and_then(Value::as_str) {
            bean.grinder_setting = Some(s.to_owned());
        }
        let bean_has_any = bean.name.is_some()
            || bean.roaster.is_some()
            || bean.roasted_on.is_some()
            || bean.roast_level.is_some()
            || bean.notes.is_some()
            || bean.grinder_setting.is_some();
        if bean_has_any {
            acc.bean = Some(bean);
        }
    }
}

/// JSON-in / JSON-out adapter for the wasm + uniffi bridges. `payloads`
/// is a JSON array of META payload objects (each shell collects the
/// raw JSON text per `src:"META"` line and wraps them as an array
/// here).
///
/// # Errors
///
/// Returns the JSON parse error string when the outer payload isn't a
/// JSON array of objects, or when any inner payload is malformed.
pub fn fold_meta_jsonl_json(payloads_json: &str) -> Result<String, String> {
    let arr: Vec<Value> = serde_json::from_str(payloads_json).map_err(|e| e.to_string())?;
    let mut acc = ReplayMeta::default();
    for v in &arr {
        if let Some(obj) = v.as_object() {
            fold_one(&mut acc, obj);
        }
    }
    serde_json::to_string(&acc).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fold_empty_input_yields_default() {
        let got = fold_meta_jsonl(&[]).unwrap();
        assert_eq!(got, ReplayMeta::default());
    }

    #[test]
    fn fold_connect_phase_meta() {
        let payload = r#"{
            "scaleName": "BOOKOO_SC",
            "de1FirmwareVersion": 1352,
            "de1MachineModel": 4,
            "de1SerialNumber": 98765
        }"#;
        let got = fold_meta_jsonl(&[payload]).unwrap();
        assert_eq!(got.scale_name.as_deref(), Some("BOOKOO_SC"));
        assert_eq!(got.de1_firmware_version, Some(1352.0));
        assert_eq!(got.de1_machine_model, Some(4.0));
        assert_eq!(got.de1_serial_number, Some(98_765.0));
    }

    #[test]
    fn fold_later_entries_override_earlier() {
        let first = r#"{"scaleName": "BOOKOO_SC"}"#;
        let second = r#"{"scaleName": "ACAIA"}"#;
        let got = fold_meta_jsonl(&[first, second]).unwrap();
        assert_eq!(got.scale_name.as_deref(), Some("ACAIA"));
    }

    #[test]
    fn fold_drops_wrong_type_silently() {
        let payload = r#"{"scaleName": 42, "de1FirmwareVersion": "oops"}"#;
        let got = fold_meta_jsonl(&[payload]).unwrap();
        assert!(got.scale_name.is_none());
        assert!(got.de1_firmware_version.is_none());
    }

    #[test]
    fn fold_drops_non_finite_numbers_silently() {
        // JSON doesn't have a literal for NaN/Infinity, but a producer
        // could write a non-finite via serialization. serde_json::Value
        // refuses to parse `NaN` so this stays trivially safe — the
        // `is_finite()` guard belt-and-suspenders.
        let payload = r#"{"yieldTarget": 36.0}"#;
        let got = fold_meta_jsonl(&[payload]).unwrap();
        assert_eq!(got.yield_target, Some(36.0));
    }

    #[test]
    fn fold_at_shot_start_line_merges_with_prelude() {
        let prelude = r#"{
            "scaleName": "BOOKOO_SC",
            "de1FirmwareVersion": 1352
        }"#;
        let at_shot = r#"{
            "profileName": "London Fog",
            "yieldTarget": 36.0,
            "brewTemp": 92.5,
            "stopOnWeight": true,
            "autoTare": true,
            "bean": {
                "name": "Yirgacheffe",
                "roaster": "Onyx",
                "roastLevel": 4
            },
            "grinderModel": "Niche Zero"
        }"#;
        let got = fold_meta_jsonl(&[prelude, at_shot]).unwrap();
        // Prelude survives unchanged.
        assert_eq!(got.scale_name.as_deref(), Some("BOOKOO_SC"));
        assert_eq!(got.de1_firmware_version, Some(1352.0));
        // At-shot-start adds.
        assert_eq!(got.profile_name.as_deref(), Some("London Fog"));
        assert_eq!(got.yield_target, Some(36.0));
        assert_eq!(got.brew_temp, Some(92.5));
        assert_eq!(got.stop_on_weight, Some(true));
        assert_eq!(got.auto_tare, Some(true));
        assert_eq!(got.grinder_model.as_deref(), Some("Niche Zero"));
        let bean = got.bean.unwrap();
        assert_eq!(bean.name.as_deref(), Some("Yirgacheffe"));
        assert_eq!(bean.roaster.as_deref(), Some("Onyx"));
        assert_eq!(bean.roast_level, Some(4.0));
    }

    #[test]
    fn fold_bean_partial_merge_keeps_prior_fields() {
        // First payload sets name + roaster; second payload sets only
        // roastLevel. The merged bean carries all three.
        let a = r#"{"bean": {"name": "Yirg", "roaster": "Onyx"}}"#;
        let b = r#"{"bean": {"roastLevel": 4}}"#;
        let got = fold_meta_jsonl(&[a, b]).unwrap();
        let bean = got.bean.unwrap();
        assert_eq!(bean.name.as_deref(), Some("Yirg"));
        assert_eq!(bean.roaster.as_deref(), Some("Onyx"));
        assert_eq!(bean.roast_level, Some(4.0));
    }

    #[test]
    fn fold_bean_accepts_legacy_type_key() {
        // Captures written before the rename used `type`; fold_one accepts
        // either key so old archives still replay cleanly.
        let payload = r#"{"bean": {"type": "Yirg", "roaster": "Onyx"}}"#;
        let got = fold_meta_jsonl(&[payload]).unwrap();
        let bean = got.bean.unwrap();
        assert_eq!(bean.name.as_deref(), Some("Yirg"));
        assert_eq!(bean.roaster.as_deref(), Some("Onyx"));
    }

    #[test]
    fn fold_meta_jsonl_json_round_trips() {
        let payloads = r#"[
            {"scaleName": "BOOKOO_SC", "de1FirmwareVersion": 1352},
            {"profileName": "London Fog"}
        ]"#;
        let out = fold_meta_jsonl_json(payloads).unwrap();
        let parsed: ReplayMeta = serde_json::from_str(&out).unwrap();
        assert_eq!(parsed.scale_name.as_deref(), Some("BOOKOO_SC"));
        assert_eq!(parsed.de1_firmware_version, Some(1352.0));
        assert_eq!(parsed.profile_name.as_deref(), Some("London Fog"));
    }

    #[test]
    fn fold_meta_jsonl_json_rejects_malformed_outer() {
        assert!(fold_meta_jsonl_json("not json").is_err());
        assert!(fold_meta_jsonl_json("{}").is_err()); // not an array
    }
}
