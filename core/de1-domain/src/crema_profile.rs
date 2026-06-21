//! The shell-facing **editable** profile model and its mapping to / from the
//! wire [`Profile`].
//!
//! The profile *editor* works on a **segment-based** model: an ordered list of
//! pressure / flow segments plus library + recipe metadata. That is
//! [`CremaProfile`]. The DE1 / community-v2 wire format is the **step-based**
//! [`Profile`] (a list of [`ProfileStep`]). [`from_wire`] adapts a wire profile
//! into a [`CremaProfile`] (synthesising the library metadata the wire format
//! has no field for — roast, tags, defaults); [`to_wire`] maps the other way
//! (used before uploading to the DE1 or exporting v2 JSON).
//!
//! This logic used to live as TypeScript in the web shell
//! (`$lib/profiles/model.ts`) and would have been re-implemented again in Kotlin
//! for Android. It now lives here so every shell shares one byte-identical
//! adapter — the same consolidation already done for the bean / shot /
//! Visualizer-wire converters ([`crate::visualizer_wire`], [`crate::bean_coerce`]).
//! JSON-in / JSON-out at the boundary; the shells keep a thin interface that
//! mirrors the JSON shape, exactly as they already do for the wire [`Profile`].

use crate::profile::{
    BeverageType, Compare, ExitCondition, ExitMetric, Limiter, Profile, ProfileStep, Pump,
    TempSensor, Transition,
};
use crate::profile_roast::roast_from_profile;

/// Roast level a recipe is tuned for — a Profiles-page library filter facet
/// (Light / Med / Dark). Recipe-suitability metadata, **not** bean identity
/// (the bag you are pulling lives in [`crate::bean`]). Lowercase wire spelling
/// to match the web's `Roast` union and the [`roast_from_profile`] classifier.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Roast {
    /// Light roast.
    Light,
    /// Medium roast.
    Medium,
    /// Dark roast.
    Dark,
}

impl Roast {
    /// Parse the lowercase classifier string ([`roast_from_profile`]) into a
    /// [`Roast`], or `None` for any other value.
    fn from_classifier(s: &str) -> Option<Self> {
        match s {
            "light" => Some(Self::Light),
            "medium" => Some(Self::Medium),
            "dark" => Some(Self::Dark),
            _ => None,
        }
    }
}

/// Whether a profile is a fixed built-in (read-only) or a user-owned custom
/// profile. Lowercase wire spelling to match the web's `source` union.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProfileSource {
    /// A fixed built-in profile — read-only (editing duplicates it to a custom).
    Builtin,
    /// A user-owned custom profile.
    Custom,
}

/// One segment of the editable pressure / flow profile — the editor's segment
/// shape, carrying the extra fields needed to round-trip a wire [`ProfileStep`]
/// losslessly. Reuses the wire enums ([`Pump`], [`Transition`], [`TempSensor`])
/// and the [`ExitCondition`] / [`Limiter`] structs, so the mapping is a field
/// shuffle rather than a remap. `camelCase` on the wire to match the shells'
/// editor interface.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileSegment {
    /// Stable id, unique within the profile (`s1`, `s2`, … or `seg:<uuid>` for
    /// editor-added segments).
    pub id: String,
    /// Human-readable segment name.
    #[serde(default)]
    pub name: String,
    /// Pressure- or flow-priority.
    pub mode: Pump,
    /// Target value — bar (pressure) or ml/s (flow), per `mode`.
    pub target: f32,
    /// How the segment ramps to its target.
    pub ramp: Transition,
    /// Segment duration, seconds. Kept as a float — the DE1 protocol carries
    /// 0.1 s frame durations, so a 6.5 s preinfusion must round-trip faithfully.
    pub time: f32,
    /// Structured early-exit condition, or `None`.
    pub exit: Option<ExitCondition>,
    /// Target temperature, °C (the canonical unit).
    pub temp: f32,
    /// Which temperature sensor the segment regulates.
    pub temp_sensor: TempSensor,
    /// Per-segment dispensed-volume limit, ml, 0–1023 (0 = no limit).
    #[serde(default)]
    pub volume_limit_ml: u16,
    /// Advanced max-flow-or-pressure limiter, or `None`.
    pub limiter: Option<Limiter>,
}

/// A profile in the shell's library — the working model behind a card and the
/// editor.
///
/// It carries both the wire-coupled recipe data (segments + metadata that
/// round-trips to [`Profile`]) and the **library metadata** the wire format has
/// no field for: `source`, `roast`, `tags`, `pinned`, `last_used`,
/// `stop_on_weight`, `auto_tare`, and the derived `brew_temp`. That library
/// metadata is shell-managed (persisted in localStorage / Room); the core just
/// models the canonical shape and synthesises sensible values in [`from_wire`].
/// `camelCase` on the wire to match the shells' interface.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CremaProfile {
    /// Stable UUID v7. Built-ins carry pre-generated ids from
    /// `profiles/builtin.json`; custom profiles mint a fresh id via
    /// [`crate::new_profile_id`].
    pub id: String,
    /// Whether this is a read-only built-in or a user-owned custom profile.
    pub source: ProfileSource,
    /// Display name (the wire [`Profile::title`]).
    #[serde(default)]
    pub name: String,
    /// Free-text notes.
    #[serde(default)]
    pub notes: String,
    /// Roast level the recipe is tuned for, or `None` when not clearly known.
    pub roast: Option<Roast>,
    /// Custom user tags (plus a synthesised `"Built-in"` / `"tea"` on import).
    #[serde(default)]
    pub tags: Vec<String>,
    /// Pinned to the Quick Controls favorites strip.
    #[serde(default)]
    pub pinned: bool,
    /// Human "last used" instant (ISO-8601), or `None` until loaded on Brew.
    pub last_used: Option<String>,
    /// Dose target, grams.
    pub dose: f32,
    /// Yield target, grams (round-trips as the wire `target_weight`).
    pub yield_out: f32,
    /// Brew temperature, °C — a display default (the mean step temperature on
    /// import); per-segment temps are the real control.
    #[serde(default)]
    pub brew_temp: f32,
    /// End the shot when the scale reaches the yield target. Shell behaviour
    /// metadata — not written to the wire profile, so its absence must never
    /// block the wire conversion: a profile that omits it (an older save, a
    /// foreign import) still converts, defaulting to off.
    #[serde(default)]
    pub stop_on_weight: bool,
    /// Zero the scale automatically when the shot begins. Shell behaviour
    /// metadata — not written to the wire profile; absent → on (the core's
    /// own default), and never blocks the wire conversion.
    #[serde(default = "default_true")]
    pub auto_tare: bool,
    /// How many leading segments count as preinfusion (wire
    /// `preinfuse_step_count`).
    #[serde(default)]
    pub preinfuse_step_count: u8,
    /// Whole-shot dispensed-volume limit, ml, 0–1023 (0 = no limit).
    #[serde(default)]
    pub max_total_volume_ml: u16,
    /// The ordered pressure / flow segments.
    pub segments: Vec<ProfileSegment>,
    /// Profile author — free text. Round-trips through the wire `author`.
    #[serde(default)]
    pub author: String,
    /// What kind of beverage this profile produces. The shells model this as
    /// nullable, so a profile can omit the key OR carry `"beverageType": null`;
    /// both fall back to `Espresso` (the [`BeverageType`] default) rather than
    /// failing the whole shot start.
    #[serde(default, deserialize_with = "null_or_absent_default")]
    pub beverage_type: BeverageType,
    /// Target tank temperature, °C (0 = no override).
    #[serde(default)]
    pub tank_temperature_c: f32,
}

/// Serde default for [`CremaProfile::auto_tare`] — matches the core's own
/// `auto_tare` default (on), so a profile that predates the field still arrives
/// with auto-tare enabled rather than silently off.
fn default_true() -> bool {
    true
}

/// Deserialize a field that may be absent OR explicitly `null`, falling back to
/// `T::default()` in either case. Paired with `#[serde(default)]` (which covers
/// the absent key), this also turns a present `null` into the default instead of
/// an error — the shells persist nullable enum fields, and neither shape must
/// break the wire conversion.
fn null_or_absent_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Default + serde::Deserialize<'de>,
{
    use serde::Deserialize;
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

/// The user's brew defaults, passed in from the shell's settings store so a
/// fresh profile starts from the user's dialled-in numbers. The core has no
/// settings store, so the shell supplies these to [`blank_profile`].
#[derive(Debug, Clone, Copy, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewDefaults {
    /// Default dose, grams.
    pub dose_g: f32,
    /// Default yield-to-dose ratio (the x in 1:x).
    pub ratio: f32,
    /// Default brew temperature, °C.
    pub brew_temp_c: f32,
    /// Default pre-infusion seconds (the first segment's `time`).
    pub preinfusion_s: f32,
}

impl Default for BrewDefaults {
    /// The out-of-box brew defaults — a balanced 1:2 espresso at 93 °C with an
    /// 8 s pre-infusion, the same numbers both shells used to hardcode. This is
    /// the one source so a tweak here reaches every shell at once.
    fn default() -> Self {
        Self {
            dose_g: 18.0,
            ratio: 2.0,
            brew_temp_c: 93.0,
            preinfusion_s: 8.0,
        }
    }
}

/// The out-of-box [`BrewDefaults`] as a camelCase JSON object
/// (`doseG` / `ratio` / `brewTempC` / `preinfusionS`) — the seed values a
/// shell's settings store starts from. Both shells read this instead of
/// hardcoding `18` / `2.0` / `93` / `8`, so the defaults live in one place.
pub fn default_brew_defaults_json() -> String {
    // Infallible: a fixed struct of finite f32s always serialises.
    serde_json::to_string(&BrewDefaults::default()).unwrap_or_default()
}

// ── Core ⇄ shell mapping ────────────────────────────────────────────────────

/// Map a wire [`ProfileStep`] into an editable [`ProfileSegment`].
fn segment_from_step(step: &ProfileStep, index: usize) -> ProfileSegment {
    ProfileSegment {
        id: format!("s{}", index + 1),
        name: if step.name.is_empty() {
            format!("Step {}", index + 1)
        } else {
            step.name.clone()
        },
        mode: step.pump,
        target: step.target,
        ramp: step.transition,
        time: step.duration_seconds,
        exit: step.exit,
        temp: step.temperature_c,
        temp_sensor: step.temp_sensor,
        volume_limit_ml: step.volume_limit_ml,
        limiter: step.limiter,
    }
}

/// Map an editable [`ProfileSegment`] back into a wire [`ProfileStep`].
fn segment_to_step(seg: &ProfileSegment) -> ProfileStep {
    ProfileStep {
        name: seg.name.clone(),
        pump: seg.mode,
        target: seg.target,
        temperature_c: seg.temp,
        temp_sensor: seg.temp_sensor,
        transition: seg.ramp,
        duration_seconds: seg.time,
        exit: seg.exit,
        volume_limit_ml: seg.volume_limit_ml,
        limiter: seg.limiter,
        // The editor model has no per-step weight target yet (v2 JSON-only field).
        weight: None,
    }
}

/// Built-in profiles whose title marks them as tea — the wire `Profile` carries
/// no `beverage_type` distinction in the built-in corpus, so tea is detected
/// from the title prefix (the one reliable signal the corpus exposes).
fn is_tea_profile(title: &str) -> bool {
    let t = title.to_lowercase();
    t.starts_with("tea/") || t.starts_with("tea portafilter/")
}

/// Adapt a wire [`Profile`] into a library [`CremaProfile`], synthesising the
/// library metadata the wire format has no field for: roast is classified from
/// the title + notes ([`roast_from_profile`]), tea profiles pick up a `"tea"`
/// tag, dose / yield fall back to a neutral 18 g / 36 g, and the brew
/// temperature is the mean step temperature rounded to the nearest 0.5 °C.
#[must_use]
pub fn from_wire(profile: &Profile) -> CremaProfile {
    let segments = profile
        .steps
        .iter()
        .enumerate()
        .map(|(i, s)| segment_from_step(s, i))
        .collect();

    let temps: Vec<f32> = profile
        .steps
        .iter()
        .map(|s| s.temperature_c)
        .filter(|t| *t > 0.0)
        .collect();
    // `temps.len()` is a small frame count, so the f32 cast for the mean is
    // lossless in practice — the same benign-cast `#[allow]` the rest of the
    // crate uses (e.g. `maintenance.rs`, `volume.rs`).
    #[allow(clippy::cast_precision_loss)]
    let mean_temp = if temps.is_empty() {
        92.0
    } else {
        temps.iter().sum::<f32>() / temps.len() as f32
    };

    let mut tags = vec!["Built-in".to_string()];
    if is_tea_profile(&profile.title) {
        tags.push("tea".to_string());
    }

    CremaProfile {
        id: profile.id.clone(),
        source: ProfileSource::Builtin,
        name: profile.title.clone(),
        notes: profile.notes.clone(),
        roast: roast_from_profile(&profile.title, &profile.notes).and_then(Roast::from_classifier),
        tags,
        pinned: false,
        last_used: None,
        // The profile's own `dose` (the legacy `profile_grinder_dose_weight`),
        // falling back to a neutral 18 g for a profile that carries none.
        dose: if profile.dose > 0.0 { profile.dose } else { 18.0 },
        // The DE1 `target_weight`, falling back to a neutral 36 g.
        yield_out: if profile.target_weight > 0.0 {
            profile.target_weight
        } else {
            36.0
        },
        brew_temp: (mean_temp * 2.0).round() / 2.0,
        stop_on_weight: true,
        auto_tare: true,
        preinfuse_step_count: profile.preinfuse_step_count,
        max_total_volume_ml: profile.max_total_volume_ml,
        segments,
        author: profile.author.clone(),
        beverage_type: profile.beverage_type,
        tank_temperature_c: profile.tank_temperature,
    }
}

/// Map a library [`CremaProfile`] back into a wire [`Profile`] — used before
/// uploading to the DE1 or exporting v2 JSON. The library-only metadata
/// (`roast` / `tags` / `pinned` / `last_used` / `stop_on_weight` / `auto_tare`)
/// is dropped; it has no wire field.
#[must_use]
pub fn to_wire(p: &CremaProfile) -> Profile {
    Profile {
        id: p.id.clone(),
        title: p.name.clone(),
        notes: p.notes.clone(),
        steps: p.segments.iter().map(segment_to_step).collect(),
        // A profile can never count more leading preinfusion steps than it has.
        preinfuse_step_count: p
            .preinfuse_step_count
            .min(u8::try_from(p.segments.len()).unwrap_or(u8::MAX)),
        // The DE1 ShotHeader pressure/flow limits are vestigial — the legacy app
        // always sets the per-frame IgnoreLimit flag, so the per-step limiter is
        // the real control. Emit the universal 0; not exposed in the editor.
        minimum_pressure: 0.0,
        maximum_flow: 0.0,
        max_total_volume_ml: p.max_total_volume_ml,
        target_weight: p.yield_out,
        dose: p.dose,
        author: p.author.clone(),
        beverage_type: p.beverage_type,
        tank_temperature: p.tank_temperature_c,
        version: "2".to_string(),
    }
}

/// The default segment list for a brand-new profile.
#[must_use]
pub fn default_segments() -> Vec<ProfileSegment> {
    vec![
        ProfileSegment {
            id: "s1".to_string(),
            name: "Pre-infusion".to_string(),
            mode: Pump::Pressure,
            target: 4.0,
            ramp: Transition::Smooth,
            time: 8.0,
            exit: Some(ExitCondition {
                metric: ExitMetric::Flow,
                compare: Compare::Over,
                threshold: 4.0,
            }),
            temp: 92.0,
            temp_sensor: TempSensor::Coffee,
            volume_limit_ml: 0,
            limiter: None,
        },
        ProfileSegment {
            id: "s2".to_string(),
            name: "Ramp".to_string(),
            mode: Pump::Pressure,
            target: 9.0,
            ramp: Transition::Smooth,
            time: 4.0,
            exit: None,
            temp: 92.0,
            temp_sensor: TempSensor::Coffee,
            volume_limit_ml: 0,
            limiter: None,
        },
        ProfileSegment {
            id: "s3".to_string(),
            name: "Hold".to_string(),
            mode: Pump::Pressure,
            target: 9.0,
            ramp: Transition::Fast,
            time: 12.0,
            exit: None,
            temp: 92.0,
            temp_sensor: TempSensor::Coffee,
            volume_limit_ml: 0,
            limiter: None,
        },
        ProfileSegment {
            id: "s4".to_string(),
            name: "Decline".to_string(),
            mode: Pump::Pressure,
            target: 6.0,
            ramp: Transition::Smooth,
            time: 8.0,
            exit: None,
            temp: 92.0,
            temp_sensor: TempSensor::Coffee,
            volume_limit_ml: 0,
            limiter: None,
        },
    ]
}

/// A fresh, empty custom profile seeded from the user's [`BrewDefaults`].
#[must_use]
pub fn blank_profile(defaults: &BrewDefaults) -> CremaProfile {
    let mut segments = default_segments();
    // `preinfusion_s` is the *seconds* of the first preinfusion segment — NOT
    // the `preinfuse_step_count` (a count of leading segments).
    if let Some(first) = segments.first_mut() {
        first.time = defaults.preinfusion_s;
    }
    CremaProfile {
        id: crate::new_profile_id(),
        source: ProfileSource::Custom,
        name: String::new(),
        notes: String::new(),
        roast: None,
        tags: Vec::new(),
        pinned: false,
        last_used: None,
        dose: defaults.dose_g,
        yield_out: defaults.dose_g * defaults.ratio,
        brew_temp: defaults.brew_temp_c,
        stop_on_weight: true,
        auto_tare: true,
        preinfuse_step_count: 1,
        max_total_volume_ml: 0,
        segments,
        author: String::new(),
        beverage_type: BeverageType::Espresso,
        tank_temperature_c: 0.0,
    }
}

// ── JSON facades (the FFI / wasm boundary) ──────────────────────────────────

/// JSON facade for [`to_wire`]: a [`CremaProfile`] JSON in, a wire [`Profile`]
/// JSON out.
///
/// # Errors
///
/// The JSON (de)serialisation error string on malformed input.
pub fn crema_profile_to_wire_json(crema_json: &str) -> Result<String, String> {
    let p: CremaProfile = serde_json::from_str(crema_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&to_wire(&p)).map_err(|e| e.to_string())
}

/// JSON facade for [`from_wire`]: a wire [`Profile`] JSON in, a [`CremaProfile`]
/// JSON out.
///
/// # Errors
///
/// The JSON (de)serialisation error string on malformed input.
pub fn crema_profile_from_wire_json(wire_json: &str) -> Result<String, String> {
    let p: Profile = serde_json::from_str(wire_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&from_wire(&p)).map_err(|e| e.to_string())
}

/// JSON facade for [`blank_profile`]: a [`BrewDefaults`] JSON in, a fresh
/// [`CremaProfile`] JSON out.
///
/// # Errors
///
/// The JSON (de)serialisation error string on malformed input.
pub fn blank_crema_profile_json(defaults_json: &str) -> Result<String, String> {
    let d: BrewDefaults = serde_json::from_str(defaults_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&blank_profile(&d)).map_err(|e| e.to_string())
}

/// JSON facade for [`default_segments`]. Infallible — the fixed data always
/// serialises (falls back to `"[]"` only to keep the signature panic-free).
#[must_use]
pub fn default_segments_json() -> String {
    serde_json::to_string(&default_segments()).unwrap_or_else(|_| "[]".to_string())
}

/// Every built-in profile adapted into a [`CremaProfile`], as a JSON array —
/// the library store's single read at startup (replaces a per-profile
/// `from_wire` loop in the shell).
#[must_use]
pub fn builtin_crema_profiles_json() -> String {
    let list: Vec<CremaProfile> = crate::builtin_profiles().iter().map(from_wire).collect();
    serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `f32` equality without tripping clippy's `float_cmp`; the test values are
    /// exactly representable, so the epsilon is just hygiene.
    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-6
    }

    #[test]
    fn default_brew_defaults_json_carries_the_camel_case_seed() {
        let v: serde_json::Value =
            serde_json::from_str(&default_brew_defaults_json()).expect("valid JSON");
        // The exact keys + values both shells used to hardcode.
        assert_eq!(v["doseG"], 18.0);
        assert_eq!(v["ratio"], 2.0);
        assert_eq!(v["brewTempC"], 93.0);
        assert_eq!(v["preinfusionS"], 8.0);
        assert_eq!(v.as_object().map(|o| o.len()), Some(4));
        // Round-trips back into the struct blank_profile consumes.
        let d: BrewDefaults =
            serde_json::from_str(&default_brew_defaults_json()).expect("round-trips");
        assert!(close(d.dose_g, 18.0) && close(d.ratio, 2.0));
    }

    fn wire_step(name: &str, target: f32) -> ProfileStep {
        ProfileStep {
            name: name.to_string(),
            pump: Pump::Pressure,
            target,
            temperature_c: 92.0,
            temp_sensor: TempSensor::Coffee,
            transition: Transition::Smooth,
            duration_seconds: 6.0,
            exit: None,
            volume_limit_ml: 0,
            limiter: None,
            weight: None,
        }
    }

    fn wire_profile(steps: Vec<ProfileStep>) -> Profile {
        Profile {
            id: "test-id".to_string(),
            title: "Test".to_string(),
            notes: String::new(),
            steps,
            preinfuse_step_count: 1,
            minimum_pressure: 1.0,
            maximum_flow: 6.0,
            max_total_volume_ml: 0,
            target_weight: 0.0,
            dose: 0.0,
            author: String::new(),
            beverage_type: BeverageType::Espresso,
            tank_temperature: 0.0,
            version: "2".to_string(),
        }
    }

    #[test]
    fn segments_round_trip_through_wire_steps() {
        let mut s = wire_step("preinfuse", 4.0);
        s.exit = Some(ExitCondition {
            metric: ExitMetric::Flow,
            compare: Compare::Over,
            threshold: 4.0,
        });
        let original = wire_profile(vec![s, wire_step("pour", 9.0)]);
        let back = to_wire(&from_wire(&original));
        assert_eq!(back.steps, original.steps);
    }

    #[test]
    fn to_wire_emits_vestigial_zeros_and_version_two() {
        let wire = to_wire(&from_wire(&wire_profile(vec![wire_step("a", 9.0)])));
        assert!(close(wire.minimum_pressure, 0.0));
        assert!(close(wire.maximum_flow, 0.0));
        assert_eq!(wire.version, "2");
        assert!(wire.steps.iter().all(|s| s.weight.is_none()));
    }

    #[test]
    fn from_wire_synthesises_library_metadata_and_defaults() {
        let p = from_wire(&wire_profile(vec![wire_step("a", 9.0)]));
        assert_eq!(p.source, ProfileSource::Builtin);
        assert!(close(p.dose, 18.0)); // 0 → 18
        assert!(close(p.yield_out, 36.0)); // 0 → 36
        assert!(p.tags.contains(&"Built-in".to_string()));
        assert!(p.stop_on_weight);
        assert!(p.auto_tare);
        assert!(!p.pinned);
        assert!(p.last_used.is_none());
    }

    #[test]
    fn from_wire_preserves_explicit_dose_and_yield() {
        let mut wp = wire_profile(vec![wire_step("a", 9.0)]);
        wp.dose = 20.0;
        wp.target_weight = 42.0;
        let p = from_wire(&wp);
        assert!(close(p.dose, 20.0));
        assert!(close(p.yield_out, 42.0));
    }

    #[test]
    fn from_wire_means_step_temperatures_to_the_half_degree() {
        let mut a = wire_step("a", 9.0);
        a.temperature_c = 91.0;
        let mut b = wire_step("b", 9.0);
        b.temperature_c = 94.2; // mean 92.6 → 92.5
        let p = from_wire(&wire_profile(vec![a, b]));
        assert!(close(p.brew_temp, 92.5));
    }

    #[test]
    fn blank_profile_seeds_from_defaults() {
        let d = BrewDefaults {
            dose_g: 18.0,
            ratio: 2.0,
            brew_temp_c: 93.0,
            preinfusion_s: 6.0,
        };
        let p = blank_profile(&d);
        assert_eq!(p.source, ProfileSource::Custom);
        assert!(close(p.dose, 18.0));
        assert!(close(p.yield_out, 36.0)); // dose * ratio
        assert!(close(p.brew_temp, 93.0));
        assert_eq!(p.preinfuse_step_count, 1);
        assert_eq!(p.segments.len(), 4);
        assert!(close(p.segments[0].time, 6.0)); // preinfusion_s applied
        assert!(!p.id.is_empty());
    }

    #[test]
    fn default_segments_lead_with_a_preinfusion_exit() {
        let segs = default_segments();
        assert_eq!(segs.len(), 4);
        assert_eq!(segs[0].name, "Pre-infusion");
        let exit = segs[0].exit.expect("preinfusion has an exit");
        assert_eq!(exit.metric, ExitMetric::Flow);
        assert_eq!(exit.compare, Compare::Over);
    }

    #[test]
    fn json_facades_round_trip_segments() {
        let wire_json =
            serde_json::to_string(&wire_profile(vec![wire_step("a", 9.0), wire_step("b", 6.0)]))
                .unwrap();
        let crema_json = crema_profile_from_wire_json(&wire_json).unwrap();
        let back_wire_json = crema_profile_to_wire_json(&crema_json).unwrap();
        let back: Profile = serde_json::from_str(&back_wire_json).unwrap();
        let original: Profile = serde_json::from_str(&wire_json).unwrap();
        assert_eq!(back.steps, original.steps);
    }

    #[test]
    fn blank_crema_profile_json_parses_camelcase_defaults() {
        let out =
            blank_crema_profile_json(r#"{"doseG":18.0,"ratio":2.0,"brewTempC":93.0,"preinfusionS":8.0}"#)
                .unwrap();
        let p: CremaProfile = serde_json::from_str(&out).unwrap();
        assert!(close(p.segments[0].time, 8.0));
        assert!(close(p.yield_out, 36.0));
    }

    #[test]
    fn builtin_crema_profiles_json_adapts_the_whole_corpus() {
        let json = builtin_crema_profiles_json();
        let list: Vec<CremaProfile> = serde_json::from_str(&json).unwrap();
        assert_eq!(list.len(), crate::BUILTIN_PROFILE_COUNT);
        assert!(list.iter().all(|p| p.source == ProfileSource::Builtin));
    }

    #[test]
    fn segment_wire_json_uses_camelcase_and_lowercase_enums() {
        // Pin the JSON shape the shells' hand-written interface mirrors.
        let segs = default_segments();
        let json = serde_json::to_string(&segs[0]).unwrap();
        assert!(json.contains("\"tempSensor\":\"coffee\""));
        assert!(json.contains("\"volumeLimitMl\":0"));
        assert!(json.contains("\"mode\":\"pressure\""));
        assert!(json.contains("\"ramp\":\"smooth\""));
        assert!(json.contains("\"metric\":\"flow\""));
        assert!(json.contains("\"compare\":\"over\""));
    }

    #[test]
    fn conversion_survives_absent_shell_metadata_fields() {
        // A complete, healthy CremaProfile JSON converts.
        let full = from_wire(&wire_profile(vec![wire_step("a", 9.0)]));
        let full_json = serde_json::to_string(&full).unwrap();
        assert!(crema_profile_to_wire_json(&full_json).is_ok());

        // Every metadata / cosmetic field carries `#[serde(default)]` so a
        // profile that omits it (an older save, a foreign import) still converts
        // — otherwise the shell reports "could not convert" and the shot never
        // starts. Identity + execution fields (id, source, dose, yieldOut,
        // segments) stay required. Repro + regression guard for the real-device
        // 2026-06-20 bug (originally beverageType; the web audit later proved
        // author + tankTemperatureC hit the same wall via old localStorage).
        let defaultable = [
            "name",
            "notes",
            "tags",
            "pinned",
            "brewTemp",
            "stopOnWeight",
            "autoTare",
            "preinfuseStepCount",
            "maxTotalVolumeMl",
            "author",
            "beverageType",
            "tankTemperatureC",
        ];
        for field in defaultable {
            let mut v: serde_json::Value = serde_json::from_str(&full_json).unwrap();
            v.as_object_mut().unwrap().remove(field);
            let stripped = serde_json::to_string(&v).unwrap();
            assert!(
                crema_profile_to_wire_json(&stripped).is_ok(),
                "a profile missing `{field}` must still convert to the wire format"
            );
        }

        // The worst case — an old profile missing ALL defaultable fields at once
        // (plus the already-tolerant Option fields), reduced to identity +
        // execution only — must still convert.
        let mut v: serde_json::Value = serde_json::from_str(&full_json).unwrap();
        {
            let obj = v.as_object_mut().unwrap();
            for field in defaultable {
                obj.remove(field);
            }
            obj.remove("roast");
            obj.remove("lastUsed");
        }
        let minimal = serde_json::to_string(&v).unwrap();
        assert!(
            crema_profile_to_wire_json(&minimal).is_ok(),
            "a profile reduced to identity + execution fields must still convert"
        );

        // The defaults land where expected: auto-tare ON (matches the core's own
        // default + the web shell), stop-on-weight OFF, beverageType → espresso,
        // text → empty, tank temp → 0.
        let cp: CremaProfile = serde_json::from_str(&minimal).unwrap();
        assert!(cp.auto_tare, "auto_tare defaults on (core/web parity)");
        assert!(!cp.stop_on_weight, "stop_on_weight defaults off");
        assert_eq!(cp.beverage_type, BeverageType::Espresso);
        assert_eq!(cp.author, "");
        assert!(close(cp.tank_temperature_c, 0.0));

        // The shells model beverageType as nullable, so it can also arrive as an
        // explicit null (not just absent). That must convert too.
        let mut v: serde_json::Value = serde_json::from_str(&full_json).unwrap();
        v.as_object_mut()
            .unwrap()
            .insert("beverageType".into(), serde_json::Value::Null);
        let nulled = serde_json::to_string(&v).unwrap();
        assert!(
            crema_profile_to_wire_json(&nulled).is_ok(),
            "a profile with an explicit null beverageType must still convert"
        );
    }
}
