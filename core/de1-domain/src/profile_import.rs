//! Import legacy DE1-app profile formats into Crema's [`Profile`].
//!
//! Crema's [`Profile`] is the *advanced* multi-step model with serde JSON. The
//! legacy DE1 tablet app ships three on-disk profile formats this module can
//! ingest:
//!
//! - **v2 JSON** ([`import_v2_json`]) — the modern format. A JSON object with a
//!   `steps` array; each step already carries pump/transition/exit/limiter
//!   fields. Maps step-for-step. This is the primary, highest-value path.
//! - **Legacy Tcl-dict** ([`import_legacy_tcl`]) — the original flat
//!   `key value` Tcl-dictionary format (the `*.tcl` files in the legacy
//!   `profiles/` directory). An *advanced* (`settings_2c`) Tcl profile carries
//!   a pre-expanded `advanced_shot` list; a *simple pressure* (`settings_2a`)
//!   or *simple flow* (`settings_2b`) one carries only a handful of scalar
//!   settings, which this module expands the same way the legacy app's
//!   `pressure_to_advanced_list` / `flow_to_advanced_list` do.
//!
//! All functions are sans-IO: they take the already-loaded profile text and
//! return a [`Profile`] or an [`ImportError`]. Reading the file from disk is
//! the shell's job.

use crate::error::ImportError;
use crate::profile::{
    Compare, ExitCondition, ExitMetric, Limiter, Profile, ProfileStep, Pump, TempSensor, Transition,
};

/// The DE1 frame-index limit; a profile may hold at most this many steps.
const MAX_STEPS: usize = 32;

// ---------------------------------------------------------------------------
// v2 JSON import
// ---------------------------------------------------------------------------

/// One step of a legacy v2 JSON profile, as it appears on disk.
///
/// The numeric fields are strings in some exported profiles and bare numbers
/// in others, so they are parsed leniently via [`Scalar`].
#[derive(serde::Deserialize)]
struct V2Step {
    #[serde(default)]
    name: String,
    #[serde(default)]
    temperature: Scalar,
    #[serde(default)]
    sensor: Option<String>,
    #[serde(default)]
    pump: Option<String>,
    #[serde(default)]
    transition: Option<String>,
    #[serde(default)]
    pressure: Scalar,
    #[serde(default)]
    flow: Scalar,
    #[serde(default)]
    seconds: Scalar,
    #[serde(default)]
    volume: Scalar,
    #[serde(default)]
    exit: Option<V2Exit>,
    #[serde(default)]
    limiter: Option<V2Limiter>,
}

/// The early-exit object of a legacy v2 JSON step.
#[derive(serde::Deserialize)]
struct V2Exit {
    /// `"pressure"` or `"flow"`.
    #[serde(rename = "type")]
    kind: String,
    /// `"over"` or `"under"`.
    condition: String,
    /// The threshold (bar or mL/s).
    #[serde(default)]
    value: Scalar,
}

/// The advanced limiter object of a legacy v2 JSON step.
#[derive(serde::Deserialize)]
struct V2Limiter {
    #[serde(default)]
    value: Scalar,
    #[serde(default)]
    range: Scalar,
}

/// A whole legacy v2 JSON profile.
#[derive(serde::Deserialize)]
struct V2Profile {
    #[serde(default)]
    title: String,
    #[serde(default)]
    notes: String,
    #[serde(default)]
    steps: Vec<V2Step>,
    #[serde(default)]
    target_weight: Scalar,
    #[serde(default)]
    target_volume: Scalar,
    /// Number of leading steps that count as preinfusion (volume-tracking
    /// starts after them).
    #[serde(default)]
    target_volume_count_start: Scalar,
}

/// Import a legacy **v2 JSON** profile into a Crema [`Profile`].
///
/// # Errors
///
/// - [`ImportError::Json`] if `json` is not valid v2 JSON.
/// - [`ImportError::NoSteps`] if the profile has no steps.
/// - [`ImportError::TooManySteps`] if it has more than 32.
pub fn import_v2_json(json: &str) -> Result<Profile, ImportError> {
    let raw: V2Profile =
        serde_json::from_str(json).map_err(|e| ImportError::Json(e.to_string()))?;

    let steps = raw
        .steps
        .iter()
        .map(v2_step_to_profile_step)
        .collect::<Result<Vec<_>, _>>()?;

    finish_profile(
        raw.title,
        raw.notes,
        steps,
        raw.target_volume_count_start.as_u16(),
        raw.target_weight.as_f32(),
        raw.target_volume.as_f32(),
    )
}

/// Convert one parsed v2 JSON step into a [`ProfileStep`].
fn v2_step_to_profile_step(step: &V2Step) -> Result<ProfileStep, ImportError> {
    let pump = parse_pump(step.pump.as_deref())?;
    let target = match pump {
        Pump::Pressure => step.pressure.as_f32(),
        Pump::Flow => step.flow.as_f32(),
    };

    let exit = match &step.exit {
        Some(e) => Some(parse_exit(&e.kind, &e.condition, e.value.as_f32())?),
        None => None,
    };

    let limiter = step.limiter.as_ref().and_then(|l| {
        // The legacy app emits a limiter only when its value is non-negative;
        // a zero/absent value means "no limiter".
        let value = l.value.as_f32();
        (value > 0.0).then_some(Limiter {
            value,
            range: l.range.as_f32(),
        })
    });

    Ok(ProfileStep {
        name: step.name.clone(),
        pump,
        target,
        temperature_c: step.temperature.as_f32(),
        temp_sensor: parse_sensor(step.sensor.as_deref()),
        transition: parse_transition(step.transition.as_deref()),
        duration_seconds: step.seconds.as_f32(),
        exit,
        volume_limit_ml: clamp_volume(step.volume.as_f32()),
        limiter,
    })
}

// ---------------------------------------------------------------------------
// Legacy Tcl-dict import
// ---------------------------------------------------------------------------

/// Import a legacy **Tcl-dict** profile (a `*.tcl` profile file) into a Crema
/// [`Profile`].
///
/// Handles all three legacy profile types: a simple pressure profile
/// (`settings_2a`), a simple flow profile (`settings_2b`), and an advanced
/// profile (`settings_2c`). Simple profiles are expanded into a step list
/// using the same algorithm as the legacy app.
///
/// # Errors
///
/// - [`ImportError::Tcl`] if `tcl` is not a well-formed Tcl dictionary.
/// - [`ImportError::NoSteps`] if the resulting profile has no steps.
/// - [`ImportError::TooManySteps`] if it has more than 32.
pub fn import_legacy_tcl(tcl: &str) -> Result<Profile, ImportError> {
    let dict = TclDict::parse(tcl)?;

    let profile_type = dict.get("settings_profile_type").unwrap_or("settings_2a");
    let profile_type = fix_profile_type(profile_type);

    let title = dict.get("profile_title").unwrap_or("").to_string();
    let notes = dict.get("profile_notes").unwrap_or("").to_string();

    let steps = match profile_type {
        "settings_2b" => flow_to_advanced_list(&dict),
        "settings_2c" => advanced_shot_to_steps(&dict)?,
        // `settings_2a` and anything unrecognised: treat as simple pressure.
        _ => pressure_to_advanced_list(&dict),
    };

    // An advanced Tcl profile records the preinfusion count directly; a
    // converted simple profile sets it from its generated leading steps.
    let count_start = dict
        .get("final_desired_shot_volume_advanced_count_start")
        .and_then(|s| s.trim().parse::<f32>().ok())
        .map_or(0, |v| v as u16);

    finish_profile(
        title,
        notes,
        steps,
        count_start,
        dict.get_f32("final_desired_shot_weight_advanced")
            .or_else(|| dict.get_f32("final_desired_shot_weight"))
            .unwrap_or(0.0),
        dict.get_f32("final_desired_shot_volume_advanced")
            .or_else(|| dict.get_f32("final_desired_shot_volume"))
            .unwrap_or(0.0),
    )
}

/// Parse a Tcl-dict advanced profile's pre-expanded `advanced_shot` list.
fn advanced_shot_to_steps(dict: &TclDict) -> Result<Vec<ProfileStep>, ImportError> {
    let list = dict.get("advanced_shot").unwrap_or("");
    let step_dicts = TclDict::parse_list_of_dicts(list)?;
    step_dicts.iter().map(tcl_step_to_profile_step).collect()
}

/// Convert one parsed Tcl-dict step into a [`ProfileStep`].
fn tcl_step_to_profile_step(step: &TclDict) -> Result<ProfileStep, ImportError> {
    let pump = parse_pump(step.get("pump"))?;
    let target = match pump {
        Pump::Pressure => step.get_f32("pressure").unwrap_or(0.0),
        Pump::Flow => step.get_f32("flow").unwrap_or(0.0),
    };

    // The legacy step carries an exit only when `exit_if` is truthy. The
    // active condition is named by `exit_type`, with the threshold stored in
    // the matching `exit_<metric>_<dir>` field.
    let exit = if step.get("exit_if").map(is_tcl_true).unwrap_or(false) {
        match step.get("exit_type") {
            Some(kind) => Some(parse_tcl_exit(kind, step)?),
            None => None,
        }
    } else {
        None
    };

    // The limiter is present only when `max_flow_or_pressure` is set and
    // positive.
    let limiter = match (
        step.get_f32("max_flow_or_pressure"),
        step.get_f32("max_flow_or_pressure_range"),
    ) {
        (Some(value), Some(range)) if value > 0.0 => Some(Limiter { value, range }),
        _ => None,
    };

    Ok(ProfileStep {
        name: step.get("name").unwrap_or("").to_string(),
        pump,
        target,
        temperature_c: step.get_f32("temperature").unwrap_or(0.0),
        temp_sensor: parse_sensor(step.get("sensor")),
        transition: parse_transition(step.get("transition")),
        duration_seconds: step.get_f32("seconds").unwrap_or(0.0),
        exit,
        volume_limit_ml: clamp_volume(step.get_f32("volume").unwrap_or(0.0)),
        limiter,
    })
}

/// Map a legacy Tcl-dict `exit_type` plus its threshold field to an
/// [`ExitCondition`].
fn parse_tcl_exit(kind: &str, step: &TclDict) -> Result<ExitCondition, ImportError> {
    let (metric, compare, field) = match kind {
        "pressure_over" => (ExitMetric::Pressure, Compare::Over, "exit_pressure_over"),
        "pressure_under" => (ExitMetric::Pressure, Compare::Under, "exit_pressure_under"),
        "flow_over" => (ExitMetric::Flow, Compare::Over, "exit_flow_over"),
        "flow_under" => (ExitMetric::Flow, Compare::Under, "exit_flow_under"),
        other => return Err(ImportError::UnknownValue("exit_type", other.to_string())),
    };
    Ok(ExitCondition {
        metric,
        compare,
        threshold: step.get_f32(field).unwrap_or(0.0),
    })
}

// ---------------------------------------------------------------------------
// Simple -> advanced conversion
//
// Ports of the legacy `pressure_to_advanced_list` / `flow_to_advanced_list`
// procedures (`de1plus/profile.tcl`). A simple profile is a handful of scalar
// settings; the legacy app expands it into a multi-step frame list.
// ---------------------------------------------------------------------------

/// The temperatures of the four legacy conversion frames (boost, preinfusion,
/// hold, decline), honouring per-step temperatures when enabled.
struct StepTemps {
    boost: f32,
    preinfusion: f32,
    hold: f32,
    decline: f32,
}

/// Resolve the per-frame temperatures and the lengths of the two preinfusion
/// frames. Shared by both simple-conversion algorithms.
fn resolve_conversion(dict: &TclDict) -> (StepTemps, f32, f32) {
    let preinfusion_time = dict.get_f32("preinfusion_time").unwrap_or(0.0);
    let steps_enabled = dict
        .get("espresso_temperature_steps_enabled")
        .map(is_tcl_true)
        .unwrap_or(false);

    let (first_len, second_len, temps) = if steps_enabled {
        let bump = dict.get_f32("temp_bump_time_seconds").unwrap_or(2.0);
        let second = (preinfusion_time - bump).max(0.0);
        let temps = StepTemps {
            boost: dict.get_f32("espresso_temperature_0").unwrap_or(0.0),
            preinfusion: dict.get_f32("espresso_temperature_1").unwrap_or(0.0),
            hold: dict.get_f32("espresso_temperature_2").unwrap_or(0.0),
            decline: dict.get_f32("espresso_temperature_3").unwrap_or(0.0),
        };
        (bump, second, temps)
    } else {
        // No per-step temperatures: every frame uses the single temperature.
        let t = dict.get_f32("espresso_temperature").unwrap_or(0.0);
        let temps = StepTemps {
            boost: t,
            preinfusion: t,
            hold: t,
            decline: t,
        };
        (0.0, preinfusion_time, temps)
    };

    (temps, first_len, second_len)
}

/// Build a flow-priority preinfusion frame that exits when pressure rises past
/// `stop_pressure`.
fn preinfusion_frame(
    name: &str,
    temperature_c: f32,
    flow_rate: f32,
    seconds: f32,
    stop_pressure: f32,
) -> ProfileStep {
    ProfileStep {
        name: name.to_string(),
        pump: Pump::Flow,
        target: flow_rate,
        temperature_c,
        temp_sensor: TempSensor::Basket,
        transition: Transition::Fast,
        duration_seconds: seconds,
        exit: Some(ExitCondition {
            metric: ExitMetric::Pressure,
            compare: Compare::Over,
            threshold: stop_pressure,
        }),
        volume_limit_ml: 0,
        limiter: None,
    }
}

/// Port of the legacy `pressure_to_advanced_list`: expand a simple **pressure**
/// profile into an advanced step list.
fn pressure_to_advanced_list(dict: &TclDict) -> Vec<ProfileStep> {
    let (temps, first_len, second_len) = resolve_conversion(dict);
    let flow_rate = dict.get_f32("preinfusion_flow_rate").unwrap_or(4.0);
    let stop_pressure = dict.get_f32("preinfusion_stop_pressure").unwrap_or(0.0);
    let pressure = dict.get_f32("espresso_pressure").unwrap_or(0.0);
    let pressure_end = dict.get_f32("pressure_end").unwrap_or(0.0);
    let max_flow = max_value(dict, "maximum_flow", "maximum_flow_range_default");

    let mut steps = Vec::new();

    if first_len > 0.0 {
        steps.push(preinfusion_frame(
            "preinfusion temp boost",
            temps.boost,
            flow_rate,
            first_len,
            stop_pressure,
        ));
    }
    if second_len > 0.0 {
        steps.push(preinfusion_frame(
            "preinfusion",
            temps.preinfusion,
            flow_rate,
            second_len,
            stop_pressure,
        ));
    }

    // The hold (rise-and-hold) stage. A hold longer than 3 s is preceded by a
    // 3-second un-limited forced rise; that 3 s is then subtracted.
    let mut hold_time = dict.get_f32("espresso_hold_time").unwrap_or(0.0);
    if hold_time > 0.0 {
        if hold_time > 3.0 {
            steps.push(pressure_frame(
                "forced rise without limit",
                temps.hold,
                pressure,
                3.0,
                Transition::Fast,
                None,
            ));
            hold_time -= 3.0;
        }
        steps.push(pressure_frame(
            "rise and hold",
            temps.hold,
            pressure,
            hold_time,
            Transition::Fast,
            max_flow,
        ));
    }

    // The decline stage. If the hold stage did not already force the pressure
    // up, the decline is preceded by its own 3-second forced rise.
    let mut decline_time = dict.get_f32("espresso_decline_time").unwrap_or(0.0);
    if decline_time > 0.0 {
        if hold_time < 3.0 && decline_time > 3.0 {
            steps.push(pressure_frame(
                "forced rise without limit",
                temps.decline,
                pressure,
                3.0,
                Transition::Fast,
                None,
            ));
            decline_time -= 3.0;
        }
        steps.push(pressure_frame(
            "decline",
            temps.decline,
            pressure_end,
            decline_time,
            Transition::Smooth,
            max_flow,
        ));
    }

    if steps.is_empty() {
        steps.push(empty_frame());
    }
    steps
}

/// Port of the legacy `flow_to_advanced_list`: expand a simple **flow** profile
/// into an advanced step list.
fn flow_to_advanced_list(dict: &TclDict) -> Vec<ProfileStep> {
    let (temps, first_len, second_len) = resolve_conversion(dict);
    let flow_rate = dict.get_f32("preinfusion_flow_rate").unwrap_or(4.0);
    let stop_pressure = dict.get_f32("preinfusion_stop_pressure").unwrap_or(0.0);
    let hold_flow = dict.get_f32("flow_profile_hold").unwrap_or(0.0);
    let decline_flow = dict.get_f32("flow_profile_decline").unwrap_or(0.0);
    let hold_time = dict.get_f32("espresso_hold_time").unwrap_or(0.0);
    let decline_time = dict.get_f32("espresso_decline_time").unwrap_or(0.0);
    let max_pressure = max_value(dict, "maximum_pressure", "maximum_pressure_range_default");

    let mut steps = Vec::new();

    if first_len > 0.0 {
        steps.push(preinfusion_frame(
            "preinfusion boost",
            temps.boost,
            flow_rate,
            first_len,
            stop_pressure,
        ));
    }
    if second_len > 0.0 {
        steps.push(preinfusion_frame(
            "preinfusion",
            temps.preinfusion,
            flow_rate,
            second_len,
            stop_pressure,
        ));
    }

    // The legacy procedure gates *both* the hold and decline frames on
    // `espresso_hold_time > 0` — the decline frame's own time is its
    // `seconds`, but the frame itself only appears when the hold time is set.
    if hold_time > 0.0 {
        steps.push(flow_frame(
            "hold",
            temps.hold,
            hold_flow,
            hold_time,
            Transition::Fast,
            max_pressure,
        ));
        steps.push(flow_frame(
            "decline",
            temps.decline,
            decline_flow,
            decline_time,
            Transition::Smooth,
            max_pressure,
        ));
    }

    if steps.is_empty() {
        steps.push(empty_frame());
    }
    steps
}

/// Build a pressure-priority frame, optionally limited.
fn pressure_frame(
    name: &str,
    temperature_c: f32,
    pressure: f32,
    seconds: f32,
    transition: Transition,
    limiter: Option<Limiter>,
) -> ProfileStep {
    ProfileStep {
        name: name.to_string(),
        pump: Pump::Pressure,
        target: pressure,
        temperature_c,
        temp_sensor: TempSensor::Basket,
        transition,
        duration_seconds: seconds,
        exit: None,
        volume_limit_ml: 0,
        limiter,
    }
}

/// Build a flow-priority frame, optionally limited.
fn flow_frame(
    name: &str,
    temperature_c: f32,
    flow: f32,
    seconds: f32,
    transition: Transition,
    limiter: Option<Limiter>,
) -> ProfileStep {
    ProfileStep {
        name: name.to_string(),
        pump: Pump::Flow,
        target: flow,
        temperature_c,
        temp_sensor: TempSensor::Basket,
        transition,
        duration_seconds: seconds,
        exit: None,
        volume_limit_ml: 0,
        limiter,
    }
}

/// The placeholder frame both legacy conversions emit for a profile with no
/// active stages.
fn empty_frame() -> ProfileStep {
    ProfileStep {
        name: "empty".to_string(),
        pump: Pump::Flow,
        target: 0.0,
        temperature_c: 90.0,
        temp_sensor: TempSensor::Basket,
        transition: Transition::Smooth,
        duration_seconds: 0.0,
        exit: None,
        volume_limit_ml: 0,
        limiter: None,
    }
}

/// Resolve a legacy `maximum_flow` / `maximum_pressure` limiter: it applies
/// only when set and non-zero. `range_key` names the matching tolerance field.
fn max_value(dict: &TclDict, value_key: &str, range_key: &str) -> Option<Limiter> {
    match dict.get_f32(value_key) {
        Some(value) if value != 0.0 => Some(Limiter {
            value,
            range: dict.get_f32(range_key).unwrap_or(0.6),
        }),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/// Assemble the parsed pieces into a validated [`Profile`].
fn finish_profile(
    title: String,
    notes: String,
    steps: Vec<ProfileStep>,
    preinfuse_step_count: u16,
    target_weight: f32,
    target_volume: f32,
) -> Result<Profile, ImportError> {
    if steps.is_empty() {
        return Err(ImportError::NoSteps);
    }
    if steps.len() > MAX_STEPS {
        return Err(ImportError::TooManySteps { count: steps.len() });
    }

    // `preinfuse_step_count` can never exceed the step count.
    let preinfuse = preinfuse_step_count.min(steps.len() as u16);
    // `preinfuse` is now <= 32, so this cast cannot truncate.
    let preinfuse_step_count = preinfuse as u8;

    // Crema's whole-shot volume limit comes from the legacy target volume; a
    // target weight has no protocol-level field, so it is not carried here.
    let _ = target_weight;
    let max_total_volume_ml = clamp_volume(target_volume);

    Ok(Profile {
        title,
        notes,
        steps,
        preinfuse_step_count,
        // Crema's header limits are advisory (the per-step limiter is the real
        // control); the legacy app uploads neutral values here.
        minimum_pressure: 0.0,
        maximum_flow: 0.0,
        max_total_volume_ml,
    })
}

/// Parse a legacy pump field (`"pressure"` / `"flow"`).
fn parse_pump(pump: Option<&str>) -> Result<Pump, ImportError> {
    match pump.map(str::trim) {
        Some("pressure") => Ok(Pump::Pressure),
        Some("flow") => Ok(Pump::Flow),
        Some(other) => Err(ImportError::UnknownValue("pump", other.to_string())),
        None => Err(ImportError::MissingField("pump")),
    }
}

/// Parse a legacy transition field, defaulting to [`Transition::Fast`].
fn parse_transition(transition: Option<&str>) -> Transition {
    match transition.map(str::trim) {
        Some("smooth") => Transition::Smooth,
        // The legacy default for an absent transition is "fast".
        _ => Transition::Fast,
    }
}

/// Parse a legacy temperature-sensor field. The legacy `"coffee"` sensor is
/// Crema's [`TempSensor::Basket`]; `"water"` is [`TempSensor::Mix`].
fn parse_sensor(sensor: Option<&str>) -> TempSensor {
    match sensor.map(str::trim) {
        Some("water") => TempSensor::Mix,
        // "coffee" and the absent default both regulate the basket.
        _ => TempSensor::Basket,
    }
}

/// Parse a v2 JSON exit object's `type`/`condition` into an [`ExitCondition`].
fn parse_exit(kind: &str, condition: &str, threshold: f32) -> Result<ExitCondition, ImportError> {
    let metric = match kind.trim() {
        "pressure" => ExitMetric::Pressure,
        "flow" => ExitMetric::Flow,
        other => return Err(ImportError::UnknownValue("exit.type", other.to_string())),
    };
    let compare = match condition.trim() {
        "over" => Compare::Over,
        "under" => Compare::Under,
        other => {
            return Err(ImportError::UnknownValue(
                "exit.condition",
                other.to_string(),
            ));
        }
    };
    Ok(ExitCondition {
        metric,
        compare,
        threshold,
    })
}

/// Clamp a legacy volume value into Crema's `0..=1023` mL field.
fn clamp_volume(volume: f32) -> u16 {
    if volume <= 0.0 {
        0
    } else {
        (volume.round() as u32).min(1023) as u16
    }
}

/// Whether a Tcl boolean-ish value is truthy. The legacy app stores `0`/`1`.
fn is_tcl_true(value: &str) -> bool {
    matches!(value.trim(), "1" | "true" | "yes" | "on")
}

/// Normalise an old `settings_profile_type` to the current naming, mirroring
/// the legacy `fix_profile_type`.
fn fix_profile_type(profile_type: &str) -> &str {
    match profile_type.trim() {
        "settings_2" | "settings_profile_pressure" => "settings_2a",
        "settings_profile_flow" => "settings_2b",
        "settings_profile_advanced" | "settings_2c2" => "settings_2c",
        other => other,
    }
}

// ---------------------------------------------------------------------------
// Lenient numeric scalar
// ---------------------------------------------------------------------------

/// A numeric value that legacy v2 JSON may encode as either a JSON number or a
/// JSON string. Defaults to zero when absent.
#[derive(Default)]
struct Scalar(f32);

impl Scalar {
    /// The value as `f32`.
    fn as_f32(&self) -> f32 {
        self.0
    }

    /// The value rounded to a non-negative `u16` (for count-like fields).
    fn as_u16(&self) -> u16 {
        if self.0 <= 0.0 {
            0
        } else {
            (self.0.round() as u32).min(u16::MAX as u32) as u16
        }
    }
}

impl<'de> serde::Deserialize<'de> for Scalar {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        use serde::de::Error as _;

        /// A JSON value that is either a number or a string.
        #[derive(serde::Deserialize)]
        #[serde(untagged)]
        enum Raw {
            Num(f64),
            Str(String),
        }

        match Raw::deserialize(deserializer)? {
            Raw::Num(n) => Ok(Scalar(n as f32)),
            Raw::Str(s) => {
                let trimmed = s.trim();
                if trimmed.is_empty() {
                    Ok(Scalar(0.0))
                } else {
                    trimmed
                        .parse::<f32>()
                        .map(Scalar)
                        .map_err(|_| D::Error::custom(format!("not a number: {s:?}")))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Minimal Tcl dictionary / list parser
//
// A legacy `.tcl` profile is a flat Tcl dictionary: a flat sequence of
// whitespace-separated words, where a `{...}`-braced run is one word with the
// braces stripped. The `advanced_shot` value is itself a Tcl list of braced
// step dictionaries. This parser handles exactly that subset — enough for the
// profile files, no `$`/`[]` substitution (profiles contain none).
// ---------------------------------------------------------------------------

/// A parsed Tcl dictionary: an ordered list of `(key, value)` word pairs.
struct TclDict {
    pairs: Vec<(String, String)>,
}

impl TclDict {
    /// Parse a Tcl dictionary from its textual form.
    fn parse(text: &str) -> Result<Self, ImportError> {
        let words = tcl_words(text)?;
        if words.len() % 2 != 0 {
            return Err(ImportError::Tcl(
                "dictionary has an odd number of words".to_string(),
            ));
        }
        let mut pairs = Vec::with_capacity(words.len() / 2);
        let mut iter = words.into_iter();
        while let (Some(key), Some(value)) = (iter.next(), iter.next()) {
            pairs.push((key, value));
        }
        Ok(TclDict { pairs })
    }

    /// Parse a Tcl list whose every element is itself a dictionary.
    fn parse_list_of_dicts(text: &str) -> Result<Vec<TclDict>, ImportError> {
        tcl_words(text)?.iter().map(|w| TclDict::parse(w)).collect()
    }

    /// The value for `key`, or `None` if absent. The last write wins, matching
    /// Tcl's `dict` semantics.
    fn get(&self, key: &str) -> Option<&str> {
        self.pairs
            .iter()
            .rev()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    /// The value for `key` parsed as `f32`, or `None` if absent or unparsable.
    fn get_f32(&self, key: &str) -> Option<f32> {
        self.get(key).and_then(|v| {
            let t = v.trim();
            if t.is_empty() {
                None
            } else {
                t.parse::<f32>().ok()
            }
        })
    }
}

/// Split Tcl text into words: whitespace-separated, with `{...}` runs treated
/// as a single word with the outermost braces removed. Nested braces are
/// tracked so a step dictionary stays intact.
fn tcl_words(text: &str) -> Result<Vec<String>, ImportError> {
    let mut words = Vec::new();
    let mut chars = text.chars().peekable();

    loop {
        // Skip inter-word whitespace.
        while chars.peek().is_some_and(|c| c.is_whitespace()) {
            chars.next();
        }
        let Some(&first) = chars.peek() else {
            break;
        };

        if first == '{' {
            // A braced word: consume until the matching close brace.
            chars.next();
            let mut depth = 1usize;
            let mut word = String::new();
            for c in chars.by_ref() {
                match c {
                    '{' => {
                        depth += 1;
                        word.push(c);
                    }
                    '}' => {
                        depth -= 1;
                        if depth == 0 {
                            break;
                        }
                        word.push(c);
                    }
                    _ => word.push(c),
                }
            }
            if depth != 0 {
                return Err(ImportError::Tcl("unbalanced braces".to_string()));
            }
            words.push(word);
        } else if first == '"' {
            // A quoted word: consume until the closing quote.
            chars.next();
            let mut word = String::new();
            let mut closed = false;
            for c in chars.by_ref() {
                if c == '"' {
                    closed = true;
                    break;
                }
                word.push(c);
            }
            if !closed {
                return Err(ImportError::Tcl("unbalanced quote".to_string()));
            }
            words.push(word);
        } else {
            // A bare word: consume until whitespace.
            let mut word = String::new();
            while let Some(&c) = chars.peek() {
                if c.is_whitespace() {
                    break;
                }
                word.push(c);
                chars.next();
            }
            words.push(word);
        }
    }

    Ok(words)
}

#[cfg(test)]
mod tests {
    use super::*;

    // -- v2 JSON ----------------------------------------------------------

    /// A small hand-written v2 JSON advanced profile exercising pump, exit and
    /// limiter mapping.
    const V2_ADVANCED: &str = r#"{
        "title": "Test Advanced",
        "author": "tester",
        "notes": "a note",
        "beverage_type": "espresso",
        "type": "advanced",
        "version": 2,
        "target_weight": "36",
        "target_volume": "0",
        "target_volume_count_start": 1,
        "steps": [
            {
                "name": "preinfusion",
                "temperature": "92.0",
                "sensor": "coffee",
                "pump": "flow",
                "transition": "fast",
                "pressure": "1.0",
                "flow": "4.0",
                "seconds": "10.0",
                "volume": "100",
                "exit": { "type": "pressure", "condition": "over", "value": "4.0" }
            },
            {
                "name": "pour",
                "temperature": 88.5,
                "sensor": "coffee",
                "pump": "pressure",
                "transition": "smooth",
                "pressure": 9.0,
                "flow": 6.0,
                "seconds": 30,
                "volume": 0,
                "limiter": { "value": "3.5", "range": "0.6" }
            }
        ]
    }"#;

    #[test]
    fn v2_json_maps_title_and_notes() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        assert_eq!(p.title, "Test Advanced");
        assert_eq!(p.notes, "a note");
    }

    #[test]
    fn v2_json_maps_steps_step_for_step() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        assert_eq!(p.steps.len(), 2);
        assert_eq!(p.steps[0].name, "preinfusion");
        assert_eq!(p.steps[1].name, "pour");
    }

    #[test]
    fn v2_json_picks_the_target_for_the_pump_priority() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        // Flow step: target is the flow value.
        assert_eq!(p.steps[0].pump, Pump::Flow);
        assert_eq!(p.steps[0].target, 4.0);
        // Pressure step: target is the pressure value.
        assert_eq!(p.steps[1].pump, Pump::Pressure);
        assert_eq!(p.steps[1].target, 9.0);
    }

    #[test]
    fn v2_json_maps_the_exit_condition() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        let exit = p.steps[0].exit.unwrap();
        assert_eq!(exit.metric, ExitMetric::Pressure);
        assert_eq!(exit.compare, Compare::Over);
        assert_eq!(exit.threshold, 4.0);
        // The second step has no exit object.
        assert!(p.steps[1].exit.is_none());
    }

    #[test]
    fn v2_json_maps_the_limiter() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        assert!(p.steps[0].limiter.is_none());
        let limiter = p.steps[1].limiter.unwrap();
        assert_eq!(limiter.value, 3.5);
        assert_eq!(limiter.range, 0.6);
    }

    #[test]
    fn v2_json_maps_transition_and_sensor_and_temperature() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        assert_eq!(p.steps[0].transition, Transition::Fast);
        assert_eq!(p.steps[1].transition, Transition::Smooth);
        assert_eq!(p.steps[0].temp_sensor, TempSensor::Basket);
        assert_eq!(p.steps[1].temperature_c, 88.5);
    }

    #[test]
    fn v2_json_maps_per_step_volume_limit() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        assert_eq!(p.steps[0].volume_limit_ml, 100);
        assert_eq!(p.steps[1].volume_limit_ml, 0);
    }

    #[test]
    fn v2_json_carries_the_preinfusion_count() {
        let p = import_v2_json(V2_ADVANCED).unwrap();
        assert_eq!(p.preinfuse_step_count, 1);
    }

    #[test]
    fn v2_json_a_zero_limiter_is_dropped() {
        let json = r#"{
            "title": "z", "notes": "", "steps": [
                { "name": "s", "temperature": 90, "pump": "pressure",
                  "pressure": 9, "seconds": 10,
                  "limiter": { "value": "0", "range": "0.6" } }
            ]
        }"#;
        assert!(import_v2_json(json).unwrap().steps[0].limiter.is_none());
    }

    #[test]
    fn v2_json_rejects_an_empty_step_list() {
        let json = r#"{ "title": "x", "notes": "", "steps": [] }"#;
        assert_eq!(import_v2_json(json), Err(ImportError::NoSteps));
    }

    #[test]
    fn v2_json_rejects_malformed_json() {
        assert!(matches!(
            import_v2_json("{ not json"),
            Err(ImportError::Json(_))
        ));
    }

    #[test]
    fn v2_json_rejects_an_unknown_pump() {
        let json = r#"{
            "title": "x", "notes": "", "steps": [
                { "name": "s", "temperature": 90, "pump": "wishful",
                  "pressure": 9, "seconds": 10 }
            ]
        }"#;
        assert_eq!(
            import_v2_json(json),
            Err(ImportError::UnknownValue("pump", "wishful".to_string()))
        );
    }

    #[test]
    fn v2_json_assembles_into_protocol_packets() {
        // A fully imported profile must be a valid Crema profile.
        let assembled = import_v2_json(V2_ADVANCED).unwrap().assemble().unwrap();
        assert_eq!(assembled.header.frame_count, 2);
        assert_eq!(assembled.extension_frames.len(), 1);
    }

    // -- Tcl word parser --------------------------------------------------

    #[test]
    fn tcl_words_splits_bare_words() {
        assert_eq!(tcl_words("a b c").unwrap(), ["a", "b", "c"]);
    }

    #[test]
    fn tcl_words_keeps_a_braced_run_as_one_word() {
        assert_eq!(
            tcl_words("name {rise and hold} pump pressure").unwrap(),
            ["name", "rise and hold", "pump", "pressure"]
        );
    }

    #[test]
    fn tcl_words_handles_nested_braces() {
        let words = tcl_words("list {{a 1} {b 2}}").unwrap();
        assert_eq!(words, ["list", "{a 1} {b 2}"]);
    }

    #[test]
    fn tcl_words_handles_quoted_runs() {
        assert_eq!(
            tcl_words(r#"popup "" name x"#).unwrap(),
            ["popup", "", "name", "x"]
        );
    }

    #[test]
    fn tcl_words_rejects_unbalanced_braces() {
        assert!(matches!(tcl_words("a {b c"), Err(ImportError::Tcl(_))));
    }

    #[test]
    fn tcl_dict_get_returns_the_last_write() {
        let d = TclDict::parse("k 1 k 2").unwrap();
        assert_eq!(d.get("k"), Some("2"));
    }

    // -- Tcl advanced profile import --------------------------------------

    #[test]
    fn tcl_advanced_profile_imports_its_steps() {
        // A trimmed two-step advanced (settings_2c) Tcl profile.
        let tcl = r#"
advanced_shot {{exit_if 1 flow 8.0 volume 100 transition fast exit_flow_under 0 temperature 93.0 weight 0.0 name Fill pressure 3.0 pump flow sensor coffee exit_type pressure_over exit_pressure_over 3.0 max_flow_or_pressure 0 seconds 12.0 exit_pressure_under 0} {exit_if 0 flow 3.5 volume 0 transition smooth temperature 88.0 weight 0.0 name Extraction pressure 3.0 sensor coffee pump flow max_flow_or_pressure 9.5 max_flow_or_pressure_range 0.6 seconds 60.0}}
settings_profile_type settings_2c
profile_title {Trimmed Advanced}
profile_notes {a note}
final_desired_shot_volume_advanced_count_start 1
final_desired_shot_weight_advanced 36
final_desired_shot_volume_advanced 0
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        assert_eq!(p.title, "Trimmed Advanced");
        assert_eq!(p.notes, "a note");
        assert_eq!(p.steps.len(), 2);
        assert_eq!(p.steps[0].name, "Fill");
        assert_eq!(p.steps[0].pump, Pump::Flow);
        assert_eq!(p.steps[0].target, 8.0);
        assert_eq!(p.preinfuse_step_count, 1);
    }

    #[test]
    fn tcl_advanced_profile_maps_exit_and_limiter() {
        let tcl = r#"
advanced_shot {{exit_if 1 flow 8.0 volume 100 transition fast exit_flow_under 0 temperature 93.0 name Fill pressure 3.0 pump flow sensor coffee exit_type pressure_over exit_pressure_over 3.0 seconds 12.0 exit_pressure_under 0} {exit_if 0 flow 3.5 volume 0 transition smooth temperature 88.0 name Extraction pressure 3.0 sensor coffee pump flow max_flow_or_pressure 9.5 max_flow_or_pressure_range 0.6 seconds 60.0}}
settings_profile_type settings_2c
profile_title x
profile_notes {}
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        let exit = p.steps[0].exit.unwrap();
        assert_eq!(exit.metric, ExitMetric::Pressure);
        assert_eq!(exit.compare, Compare::Over);
        assert_eq!(exit.threshold, 3.0);
        let limiter = p.steps[1].limiter.unwrap();
        assert_eq!(limiter.value, 9.5);
        assert_eq!(limiter.range, 0.6);
    }

    // -- simple pressure -> advanced conversion ---------------------------

    #[test]
    fn simple_pressure_profile_expands_to_steps() {
        // A simple pressure profile with no per-step temperatures: one
        // preinfusion frame, one forced rise, hold and decline.
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_2a
profile_title {Simple Pressure}
profile_notes {}
espresso_temperature_steps_enabled 0
espresso_temperature 92.0
espresso_pressure 9.0
pressure_end 6.0
preinfusion_time 20
preinfusion_flow_rate 4.0
preinfusion_stop_pressure 4.0
espresso_hold_time 10
espresso_decline_time 30
final_desired_shot_weight 36
final_desired_shot_volume 0
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        // preinfusion, forced rise, rise-and-hold, decline.
        assert_eq!(p.steps.len(), 4);
        assert_eq!(p.steps[0].name, "preinfusion");
        assert_eq!(p.steps[0].pump, Pump::Flow);
        assert_eq!(p.steps[0].duration_seconds, 20.0);
        assert_eq!(p.steps[1].name, "forced rise without limit");
        assert_eq!(p.steps[2].name, "rise and hold");
        // 10 s hold minus the 3 s forced-rise prelude.
        assert_eq!(p.steps[2].duration_seconds, 7.0);
        assert_eq!(p.steps[3].name, "decline");
        assert_eq!(p.steps[3].pump, Pump::Pressure);
        assert_eq!(p.steps[3].target, 6.0);
        assert_eq!(p.steps[3].transition, Transition::Smooth);
    }

    #[test]
    fn simple_pressure_profile_adds_a_temp_boost_frame() {
        // With per-step temperatures the preinfusion splits into a 2-second
        // boost frame plus the remainder.
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_2a
profile_title x
profile_notes {}
espresso_temperature_steps_enabled 1
espresso_temperature_0 90.0
espresso_temperature_1 88.0
espresso_temperature_2 88.0
espresso_temperature_3 88.0
espresso_pressure 9.0
pressure_end 6.0
preinfusion_time 20
preinfusion_flow_rate 4.0
preinfusion_stop_pressure 4.0
espresso_hold_time 0
espresso_decline_time 30
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        assert_eq!(p.steps[0].name, "preinfusion temp boost");
        assert_eq!(p.steps[0].duration_seconds, 2.0);
        assert_eq!(p.steps[0].temperature_c, 90.0);
        assert_eq!(p.steps[1].name, "preinfusion");
        assert_eq!(p.steps[1].duration_seconds, 18.0);
    }

    #[test]
    fn simple_pressure_profile_emits_an_empty_frame_when_blank() {
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_2a
profile_title x
profile_notes {}
espresso_temperature_steps_enabled 0
espresso_temperature 92.0
preinfusion_time 0
espresso_hold_time 0
espresso_decline_time 0
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        assert_eq!(p.steps.len(), 1);
        assert_eq!(p.steps[0].name, "empty");
    }

    #[test]
    fn simple_pressure_profile_carries_the_maximum_flow_limiter() {
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_2a
profile_title x
profile_notes {}
espresso_temperature_steps_enabled 0
espresso_temperature 92.0
espresso_pressure 9.0
pressure_end 6.0
preinfusion_time 0
preinfusion_flow_rate 4.0
preinfusion_stop_pressure 4.0
espresso_hold_time 10
espresso_decline_time 0
maximum_flow 2.5
maximum_flow_range_default 1.0
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        // The "rise and hold" frame carries the limiter; the forced rise does not.
        let hold = p.steps.iter().find(|s| s.name == "rise and hold").unwrap();
        let limiter = hold.limiter.unwrap();
        assert_eq!(limiter.value, 2.5);
        assert_eq!(limiter.range, 1.0);
        let forced = p
            .steps
            .iter()
            .find(|s| s.name == "forced rise without limit")
            .unwrap();
        assert!(forced.limiter.is_none());
    }

    // -- simple flow -> advanced conversion -------------------------------

    #[test]
    fn simple_flow_profile_expands_to_steps() {
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_2b
profile_title {Simple Flow}
profile_notes {}
espresso_temperature_steps_enabled 0
espresso_temperature 88.0
preinfusion_time 15
preinfusion_flow_rate 4.0
preinfusion_stop_pressure 4.0
flow_profile_hold 2.0
flow_profile_decline 1.2
espresso_hold_time 8
espresso_decline_time 20
final_desired_shot_weight 36
final_desired_shot_volume 0
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        // preinfusion, hold, decline.
        assert_eq!(p.steps.len(), 3);
        assert_eq!(p.steps[0].name, "preinfusion");
        assert_eq!(p.steps[1].name, "hold");
        assert_eq!(p.steps[1].pump, Pump::Flow);
        assert_eq!(p.steps[1].target, 2.0);
        assert_eq!(p.steps[1].duration_seconds, 8.0);
        assert_eq!(p.steps[2].name, "decline");
        assert_eq!(p.steps[2].pump, Pump::Flow);
        assert_eq!(p.steps[2].target, 1.2);
        assert_eq!(p.steps[2].duration_seconds, 20.0);
        assert_eq!(p.steps[2].transition, Transition::Smooth);
    }

    #[test]
    fn simple_flow_profile_carries_the_maximum_pressure_limiter() {
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_2b
profile_title x
profile_notes {}
espresso_temperature_steps_enabled 0
espresso_temperature 88.0
preinfusion_time 0
flow_profile_hold 2.0
flow_profile_decline 1.2
espresso_hold_time 8
espresso_decline_time 20
maximum_pressure 8.6
maximum_pressure_range_default 0.9
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        let hold_limiter = p.steps[0].limiter.unwrap();
        assert_eq!(hold_limiter.value, 8.6);
        assert_eq!(hold_limiter.range, 0.9);
        assert!(p.steps[1].limiter.is_some());
    }

    #[test]
    fn old_profile_type_names_are_normalised() {
        // `settings_profile_flow` is the old alias for `settings_2b`.
        let tcl = r#"
advanced_shot {}
settings_profile_type settings_profile_flow
profile_title x
profile_notes {}
espresso_temperature_steps_enabled 0
espresso_temperature 88.0
preinfusion_time 0
flow_profile_hold 2.0
flow_profile_decline 1.2
espresso_hold_time 8
espresso_decline_time 20
"#;
        let p = import_legacy_tcl(tcl).unwrap();
        assert_eq!(p.steps[0].name, "hold");
    }

    // -- real-file fixtures ----------------------------------------------

    /// `best_practice.tcl`, copied verbatim from the legacy `profiles/`
    /// directory: a seven-step advanced (`settings_2c`) profile.
    const FIXTURE_BEST_PRACTICE: &str = include_str!("../tests/fixtures/best_practice.tcl");

    /// `default.tcl`, copied verbatim from the legacy `profiles/` directory:
    /// the stock simple pressure (`settings_2a`) profile.
    const FIXTURE_DEFAULT: &str = include_str!("../tests/fixtures/default.tcl");

    #[test]
    fn real_advanced_fixture_imports_all_seven_steps() {
        let p = import_legacy_tcl(FIXTURE_BEST_PRACTICE).unwrap();
        assert_eq!(p.title, "Adaptive v2");
        assert_eq!(p.steps.len(), 7);
        // Step order and names as written in the file.
        let names: Vec<&str> = p.steps.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(
            names,
            [
                "Prefill",
                "Fill",
                "Compressing",
                "Dripping",
                "Pressurize",
                "Extraction start",
                "Extraction",
            ]
        );
        // The "Pressurize" step is a smooth pressure step with a 3.5 limiter.
        let pressurize = &p.steps[4];
        assert_eq!(pressurize.pump, Pump::Pressure);
        assert_eq!(pressurize.target, 11.0);
        assert_eq!(pressurize.transition, Transition::Smooth);
        assert_eq!(pressurize.limiter.unwrap().value, 3.5);
        // It exits when pressure rises over 8.8 bar.
        let exit = pressurize.exit.unwrap();
        assert_eq!(exit.metric, ExitMetric::Pressure);
        assert_eq!(exit.compare, Compare::Over);
        assert_eq!(exit.threshold, 8.8);
        // The whole profile assembles into valid protocol packets.
        assert_eq!(p.assemble().unwrap().header.frame_count, 7);
        assert_eq!(p.preinfuse_step_count, 3);
    }

    #[test]
    fn real_simple_pressure_fixture_converts() {
        // `default.tcl` is a settings_2a profile with per-step temperatures,
        // 20 s preinfusion, 4 s hold, 35 s decline.
        let p = import_legacy_tcl(FIXTURE_DEFAULT).unwrap();
        assert_eq!(p.title, "Default");
        // With hold_time 4 (> 3) the hold gets its own 3 s forced rise, which
        // drops the remaining hold to 1 s; that 1 s is then < 3, so the
        // decline also gets a forced rise — matching the legacy procedure.
        let names: Vec<&str> = p.steps.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(
            names,
            [
                "preinfusion temp boost",
                "preinfusion",
                "forced rise without limit",
                "rise and hold",
                "forced rise without limit",
                "decline",
            ]
        );
        // The boost frame is 2 s at espresso_temperature_0 (90.0).
        assert_eq!(p.steps[0].duration_seconds, 2.0);
        assert_eq!(p.steps[0].temperature_c, 90.0);
        // The preinfusion remainder is 20 - 2 = 18 s.
        assert_eq!(p.steps[1].duration_seconds, 18.0);
        // The hold remainder is 4 - 3 = 1 s.
        assert_eq!(p.steps[3].duration_seconds, 1.0);
        // The decline targets pressure_end (6.0) over 35 - 3 = 32 s, smoothly.
        let decline = p.steps.last().unwrap();
        assert_eq!(decline.target, 6.0);
        assert_eq!(decline.duration_seconds, 32.0);
        assert_eq!(decline.transition, Transition::Smooth);
        // It assembles cleanly.
        assert!(p.assemble().is_ok());
    }
}
