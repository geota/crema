//! The espresso [`Profile`] model and its assembly into protocol packets.

use de1_protocol::{
    ExtensionFrame, FrameFlags, SHOT_FRAME_LEN, SHOT_HEADER_LEN, ShotFrame, ShotHeader, ShotTail,
};

use crate::error::DomainError;

/// The most steps a profile may have — the DE1's frame-index limit.
const MAX_STEPS: usize = 32;

/// Which quantity a step holds at its target. Serializes as lowercase
/// (`"pressure"` / `"flow"`) to match the community v2 JSON contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Pump {
    /// Pressure priority — the step targets a pressure in bar.
    Pressure,
    /// Flow priority — the step targets a flow rate in ml/s.
    Flow,
}

/// How a step moves to its target value. Serializes as lowercase
/// `"fast"` / `"smooth"` to match the community v2 profile JSON contract
/// shared with the legacy de1app TCL and reaprime.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Transition {
    /// Jump straight to the target.
    Fast,
    /// Ramp smoothly to the target.
    Smooth,
}

/// Which temperature a step regulates. Variant names match the community
/// v2 JSON contract used by the legacy de1app TCL + reaprime. The enum
/// describes "what the step's temperature target represents," not which
/// sensor it reads:
/// - `Coffee` — regulate temperature of the coffee at the basket; the
///   firmware holds `head_temp` to the step's target.
/// - `Water` — regulate temperature of the water exiting the group; the
///   firmware holds `mix_temp` to the step's target. Flips the
///   wire-format `target_mix_temp` flag bit on each frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TempSensor {
    /// Regulate temperature of the coffee at the basket.
    Coffee,
    /// Regulate temperature of the water exiting the group.
    Water,
}

/// What kind of beverage a profile produces. Mirrors reaprime's
/// `BeverageType` enum and the community v2 JSON contract's
/// `beverage_type` field. Defaults to `Espresso` on import — both
/// reaprime and Crema fall back lenient here (not strict) because the
/// field is metadata, not execution.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BeverageType {
    /// Standard espresso shot. The default for absent / unknown values.
    #[default]
    Espresso,
    /// A calibration routine (flow / pressure cal profiles).
    Calibrate,
    /// A cleaning / backflush routine.
    Cleaning,
    /// Hand-controlled (manual) profile — the firmware honours user
    /// adjustments mid-shot.
    Manual,
    /// Pour-over style profile (long, low-pressure).
    Pourover,
}

/// The metric an exit condition watches. Lowercase wire spelling
/// (`"pressure"` / `"flow"`) per the v2 contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ExitMetric {
    /// Watch pressure (bar).
    Pressure,
    /// Watch flow (ml/s).
    Flow,
}

/// The direction of an exit comparison. Lowercase wire spelling
/// (`"over"` / `"under"`) per the v2 contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Compare {
    /// Exit when the metric rises above the threshold.
    Over,
    /// Exit when the metric falls below the threshold.
    Under,
}

/// An early-exit condition: leave the step before its duration elapses.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ExitCondition {
    /// Which metric to watch.
    pub metric: ExitMetric,
    /// Whether to exit above or below the threshold.
    pub compare: Compare,
    /// The threshold value (bar or ml/s, per `metric`).
    pub threshold: f32,
}

/// An advanced limiter capping the step's non-priority quantity.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Limiter {
    /// The limit — flow if the step is pressure-priority, or vice versa.
    pub value: f32,
    /// Tolerance band around the limit.
    pub range: f32,
}

/// One step of an espresso [`Profile`].
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ProfileStep {
    /// Human-readable step name (e.g. "preinfusion").
    pub name: String,
    /// Whether the step holds pressure or flow.
    pub pump: Pump,
    /// Target value — bar (pressure) or ml/s (flow), per `pump`.
    pub target: f32,
    /// Target temperature, °C.
    pub temperature_c: f32,
    /// Which temperature sensor the step regulates.
    pub temp_sensor: TempSensor,
    /// How the step transitions to its target.
    pub transition: Transition,
    /// Maximum step duration, seconds.
    pub duration_seconds: f32,
    /// Optional early-exit condition.
    pub exit: Option<ExitCondition>,
    /// Per-step dispensed-volume limit, ml, range 0–1023 (0 = no limit).
    pub volume_limit_ml: u16,
    /// Optional advanced max-flow-or-pressure limiter.
    pub limiter: Option<Limiter>,
    /// Per-step target weight in grams (None = no per-step weight
    /// target). Metadata only — the DE1 protocol has no per-step
    /// weight-target field, so this never reaches `assemble`; it
    /// round-trips through v2 JSON only. Mirrors reaprime's
    /// `ProfileStep.weight` (nullable in v2).
    ///
    /// `#[serde(default)]` so profiles serialized before this field
    /// existed still deserialize.
    #[serde(default)]
    pub weight: Option<f32>,
}

impl ProfileStep {
    /// Map this step to a protocol [`ShotFrame`] at the given frame index.
    fn to_shot_frame(&self, index: u8) -> ShotFrame {
        ShotFrame {
            index,
            flags: FrameFlags {
                flow_priority: matches!(self.pump, Pump::Flow),
                do_compare: self.exit.is_some(),
                compare_greater: matches!(
                    self.exit,
                    Some(ExitCondition {
                        compare: Compare::Over,
                        ..
                    })
                ),
                compare_flow: matches!(
                    self.exit,
                    Some(ExitCondition {
                        metric: ExitMetric::Flow,
                        ..
                    })
                ),
                target_mix_temp: matches!(self.temp_sensor, TempSensor::Water),
                interpolate: matches!(self.transition, Transition::Smooth),
                // The legacy app always sets IgnoreLimit: the header limits are
                // advisory, and the per-step limiter is the real control.
                ignore_limit: true,
            },
            set_value: self.target,
            temperature: self.temperature_c,
            duration_seconds: self.duration_seconds,
            trigger_value: self.exit.map_or(0.0, |exit| exit.threshold),
            max_volume_ml: self.volume_limit_ml,
        }
    }
}

/// An espresso profile — an ordered recipe of [`ProfileStep`]s plus the
/// machine-level parameters needed to run it.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Profile {
    /// Stable profile ID — a UUID v7 string. Built-in profiles carry
    /// pre-generated IDs in `profiles/builtin.json` (filled by the
    /// `gen-builtin-ids` binary). Custom profiles get a fresh ID from
    /// [`new_profile_id`](crate::new_profile_id) via the shell.
    ///
    /// `#[serde(default)]` so profiles imported from the community v2
    /// `.json` format (which has no `id` field) still deserialize —
    /// the shell mints a fresh ID for an imported profile before
    /// persisting it.
    #[serde(default)]
    pub id: String,
    /// Profile title.
    pub title: String,
    /// Free-text notes.
    pub notes: String,
    /// The ordered steps. Must number 1–32 to [`assemble`](Profile::assemble).
    pub steps: Vec<ProfileStep>,
    /// How many leading steps count as preinfusion.
    pub preinfuse_step_count: u8,
    /// Minimum pressure for flow-priority steps, bar.
    pub minimum_pressure: f32,
    /// Maximum flow for pressure-priority steps, ml/s.
    pub maximum_flow: f32,
    /// Whole-shot dispensed-volume limit, ml, range 0–1023 (0 = no limit).
    pub max_total_volume_ml: u16,
    /// Desired final shot weight in grams (0.0 = no target).
    ///
    /// App-side metadata only — the DE1 protocol has no target-weight frame
    /// field, so this never reaches [`assemble`](Profile::assemble); it exists
    /// purely so a profile round-trips faithfully through JSON. Mirrors the
    /// legacy DE1-app `final_desired_shot_weight`.
    ///
    /// `#[serde(default)]` so profiles serialized before this field existed
    /// (e.g. the embedded `builtin.json`) still deserialize.
    #[serde(default)]
    pub target_weight: f32,
    /// Recommended dry coffee dose in grams (0.0 = unspecified).
    ///
    /// App-side metadata only — like [`target_weight`](Self::target_weight) the
    /// DE1 protocol has no dose field, so this never reaches
    /// [`assemble`](Profile::assemble). It exists so a profile round-trips
    /// faithfully through JSON and a profile card can show its real dose
    /// instead of a hardcoded default. Mirrors the legacy DE1-app
    /// `profile_grinder_dose_weight`.
    ///
    /// `#[serde(default)]` so profiles serialized before this field existed
    /// (e.g. the embedded `builtin.json`) still deserialize.
    #[serde(default)]
    pub dose: f32,
    /// Profile author / creator. Free text, optional.
    ///
    /// Matches reaprime's `Profile.author` + the community v2 JSON
    /// `author` field. App-side metadata only — has no protocol effect.
    /// `#[serde(default)]` so older serialized profiles still load.
    #[serde(default)]
    pub author: String,
    /// What kind of beverage this profile produces. See [`BeverageType`].
    /// `#[serde(default)]` → `Espresso` for older serialized profiles.
    #[serde(default)]
    pub beverage_type: BeverageType,
    /// Target tank temperature, °C. Some advanced profiles change the
    /// tank setpoint mid-shot; most are 0.0 (no override).
    ///
    /// Matches reaprime's `Profile.tankTemperature` + the community v2
    /// `tank_temperature` field. App-side metadata only — the DE1
    /// protocol has no per-profile tank-temp frame field.
    #[serde(default)]
    pub tank_temperature: f32,
    /// Community v2 profile schema version. Reaprime serializes this as
    /// the **string** `"2"` (not a number) per the v2 spec; Crema
    /// matches. Default `"2"` for profiles serialized before this field
    /// existed.
    #[serde(default = "default_profile_version")]
    pub version: String,
}

/// The default `Profile::version` — always the string `"2"` matching the
/// community v2 contract. Free function (not `Default::default`) so the
/// `#[serde(default = …)]` attribute can reference it.
fn default_profile_version() -> String {
    "2".to_string()
}

impl Profile {
    /// Assemble this profile into the protocol packets the DE1 expects.
    ///
    /// # Errors
    ///
    /// - [`DomainError::NoSteps`] if the profile has no steps.
    /// - [`DomainError::TooManySteps`] if it has more than 32.
    pub fn assemble(&self) -> Result<AssembledProfile, DomainError> {
        let count = self.steps.len();
        if count == 0 {
            return Err(DomainError::NoSteps);
        }
        if count > MAX_STEPS {
            return Err(DomainError::TooManySteps { count });
        }
        // `count` is 1..=MAX_STEPS (32), checked above, so this fits a u8.
        let frame_count = u8::try_from(count).unwrap_or(u8::MAX);

        let header = ShotHeader {
            frame_count,
            preinfuse_frame_count: self.preinfuse_step_count,
            minimum_pressure: self.minimum_pressure,
            maximum_flow: self.maximum_flow,
        };

        let mut frames = Vec::with_capacity(count);
        let mut extension_frames = Vec::new();
        // `0u8..` zipped with at most 32 steps never overflows the index.
        for (index, step) in (0u8..).zip(&self.steps) {
            frames.push(step.to_shot_frame(index));
            if let Some(limiter) = step.limiter {
                extension_frames.push(ExtensionFrame {
                    index,
                    max_flow_or_pressure: limiter.value,
                    max_fop_range: limiter.range,
                });
            }
        }

        Ok(AssembledProfile {
            header,
            frames,
            extension_frames,
            tail: ShotTail {
                frame_count,
                max_total_volume_ml: self.max_total_volume_ml,
            },
        })
    }
}

/// A [`Profile`] assembled into protocol packets, ready to upload.
#[derive(Debug, Clone, PartialEq)]
pub struct AssembledProfile {
    /// The profile header.
    pub header: ShotHeader,
    /// The normal frames, one per step, in order.
    pub frames: Vec<ShotFrame>,
    /// Extension frames, one per step that has a limiter.
    pub extension_frames: Vec<ExtensionFrame>,
    /// The closing tail frame.
    pub tail: ShotTail,
}

impl AssembledProfile {
    /// The encoded `HeaderWrite` packet.
    pub fn header_packet(&self) -> [u8; SHOT_HEADER_LEN] {
        self.header.encode()
    }

    /// The encoded `FrameWrite` packets in upload order: normal frames, then
    /// extension frames, then the tail (protocol §5.6).
    pub fn frame_packets(&self) -> Vec<[u8; SHOT_FRAME_LEN]> {
        let mut packets = Vec::with_capacity(self.frames.len() + self.extension_frames.len() + 1);
        packets.extend(self.frames.iter().map(ShotFrame::encode));
        packets.extend(self.extension_frames.iter().map(ExtensionFrame::encode));
        packets.push(self.tail.encode());
        packets
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A minimal pressure step, for tests to tweak.
    fn step(name: &str, pump: Pump) -> ProfileStep {
        ProfileStep {
            name: name.to_string(),
            pump,
            target: 9.0,
            temperature_c: 92.0,
            temp_sensor: TempSensor::Coffee,
            transition: Transition::Fast,
            duration_seconds: 20.0,
            exit: None,
            volume_limit_ml: 0,
            limiter: None,
            weight: None,
        }
    }

    /// A profile wrapping the given steps.
    fn profile(steps: Vec<ProfileStep>) -> Profile {
        Profile {
            id: String::new(),
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
    fn assembles_header_and_tail_with_the_step_count() {
        let assembled = profile(vec![step("a", Pump::Pressure), step("b", Pump::Pressure)])
            .assemble()
            .unwrap();
        assert_eq!(assembled.header.frame_count, 2);
        assert_eq!(assembled.tail.frame_count, 2);
        assert_eq!(assembled.frames.len(), 2);
    }

    #[test]
    fn a_flow_step_sets_flow_priority() {
        let assembled = profile(vec![step("flow", Pump::Flow)]).assemble().unwrap();
        assert!(assembled.frames[0].flags.flow_priority);
        assert_eq!(assembled.frames[0].set_value, 9.0);
    }

    #[test]
    fn a_pressure_step_does_not_set_flow_priority() {
        let assembled = profile(vec![step("pressure", Pump::Pressure)])
            .assemble()
            .unwrap();
        assert!(!assembled.frames[0].flags.flow_priority);
    }

    #[test]
    fn an_exit_condition_maps_to_compare_flags_and_trigger() {
        let mut s = step("pour", Pump::Pressure);
        s.exit = Some(ExitCondition {
            metric: ExitMetric::Flow,
            compare: Compare::Over,
            threshold: 2.5,
        });
        let frame = profile(vec![s]).assemble().unwrap().frames.remove(0);
        assert!(frame.flags.do_compare);
        assert!(frame.flags.compare_greater);
        assert!(frame.flags.compare_flow);
        assert_eq!(frame.trigger_value, 2.5);
    }

    #[test]
    fn a_step_without_an_exit_has_no_compare_flag() {
        let frame = profile(vec![step("a", Pump::Pressure)])
            .assemble()
            .unwrap()
            .frames
            .remove(0);
        assert!(!frame.flags.do_compare);
        assert_eq!(frame.trigger_value, 0.0);
    }

    #[test]
    fn the_mix_sensor_sets_target_mix_temp() {
        let mut s = step("a", Pump::Pressure);
        s.temp_sensor = TempSensor::Water;
        assert!(
            profile(vec![s]).assemble().unwrap().frames[0]
                .flags
                .target_mix_temp
        );
    }

    #[test]
    fn a_limiter_produces_an_extension_frame() {
        let mut s = step("limited", Pump::Pressure);
        s.limiter = Some(Limiter {
            value: 8.0,
            range: 0.5,
        });
        let assembled = profile(vec![s]).assemble().unwrap();
        assert_eq!(assembled.extension_frames.len(), 1);
        assert_eq!(assembled.extension_frames[0].index, 0);
        assert_eq!(assembled.extension_frames[0].max_flow_or_pressure, 8.0);
    }

    #[test]
    fn frame_packets_are_ordered_frames_then_extensions_then_tail() {
        let mut s = step("a", Pump::Pressure);
        s.limiter = Some(Limiter {
            value: 8.0,
            range: 0.5,
        });
        let assembled = profile(vec![s, step("b", Pump::Pressure)])
            .assemble()
            .unwrap();
        // 2 normal frames + 1 extension frame + 1 tail
        assert_eq!(assembled.frame_packets().len(), 4);
        assert_eq!(assembled.header_packet().len(), SHOT_HEADER_LEN);
    }

    #[test]
    fn an_empty_profile_is_rejected() {
        assert_eq!(profile(vec![]).assemble(), Err(DomainError::NoSteps));
    }

    #[test]
    fn a_profile_with_too_many_steps_is_rejected() {
        let steps = (0..33).map(|_| step("x", Pump::Pressure)).collect();
        assert_eq!(
            profile(steps).assemble(),
            Err(DomainError::TooManySteps { count: 33 })
        );
    }

    // ── docs/16 §7.2 gaps ────────────────────────────────────────────────

    #[test]
    fn every_exit_metric_compare_pair_maps_to_the_right_flag_bits() {
        // `metric` decides `DC_CompF` (true = flow); `compare` decides `DC_GT`
        // (true = `>`). `DoCompare` is always set when an exit is present.
        for (metric, expect_compare_flow) in
            [(ExitMetric::Flow, true), (ExitMetric::Pressure, false)]
        {
            for (compare, expect_greater) in [(Compare::Over, true), (Compare::Under, false)] {
                let mut s = step("pour", Pump::Pressure);
                s.exit = Some(ExitCondition {
                    metric,
                    compare,
                    threshold: 1.0,
                });
                let frame = profile(vec![s]).assemble().unwrap().frames.remove(0);
                assert!(
                    frame.flags.do_compare,
                    "DoCompare must be set whenever an exit is present"
                );
                assert_eq!(
                    frame.flags.compare_flow, expect_compare_flow,
                    "{metric:?} should yield compare_flow={expect_compare_flow}"
                );
                assert_eq!(
                    frame.flags.compare_greater, expect_greater,
                    "{compare:?} should yield compare_greater={expect_greater}"
                );
            }
        }
    }

    #[test]
    fn a_pressure_step_with_a_limiter_carries_a_flow_limit() {
        // Per the legacy semantic: a profile step's limiter caps the *other*
        // quantity from `pump`. A pressure-priority step is limited by flow.
        let mut s = step("pressurized", Pump::Pressure);
        s.limiter = Some(Limiter {
            value: 3.5,
            range: 0.2,
        });
        let assembled = profile(vec![s]).assemble().unwrap();
        assert_eq!(assembled.extension_frames.len(), 1);
        assert_eq!(assembled.extension_frames[0].max_flow_or_pressure, 3.5);
        assert!(
            !assembled.frames[0].flags.flow_priority,
            "the step itself stays pressure-priority"
        );
    }

    #[test]
    fn a_flow_step_with_a_limiter_carries_a_pressure_limit() {
        // Symmetric to the pressure case: a flow-priority step's limiter is
        // a pressure ceiling.
        let mut s = step("flowing", Pump::Flow);
        s.limiter = Some(Limiter {
            value: 9.5,
            range: 0.3,
        });
        let assembled = profile(vec![s]).assemble().unwrap();
        assert_eq!(assembled.extension_frames.len(), 1);
        assert_eq!(assembled.extension_frames[0].max_flow_or_pressure, 9.5);
        assert!(assembled.frames[0].flags.flow_priority);
    }

    #[test]
    fn every_normal_frame_has_ignore_limit_set() {
        // Mirrors the legacy `de1_packed_shot` which sets `IgnoreLimit`
        // unconditionally (`binary.tcl:914`). Pinning this prevents a future
        // refactor from accidentally dropping the bit.
        let assembled = profile(vec![
            step("a", Pump::Pressure),
            step("b", Pump::Flow),
            step("c", Pump::Pressure),
        ])
        .assemble()
        .unwrap();
        for frame in &assembled.frames {
            assert!(
                frame.flags.ignore_limit,
                "IgnoreLimit must be set on every normal frame"
            );
        }
    }

    #[test]
    fn a_thirty_two_step_profile_assembles_without_overflow() {
        let steps = (0..32).map(|_| step("x", Pump::Pressure)).collect();
        let assembled = profile(steps).assemble().unwrap();
        assert_eq!(assembled.header.frame_count, 32);
        assert_eq!(assembled.tail.frame_count, 32);
        assert_eq!(assembled.frames.len(), 32);
        // Frame indices go 0..=31, fitting a u8 without wrap.
        for (i, frame) in assembled.frames.iter().enumerate() {
            assert_eq!(frame.index as usize, i);
        }
    }

    #[test]
    fn frame_packets_byte_order_is_frames_then_extensions_then_tail() {
        // Two steps, both with limiters → 2 frames + 2 extensions + 1 tail.
        // Assert the first byte of each packet to pin the order:
        // bytes 0,1 are the two frame indices (0, 1); bytes 2,3 are the
        // extension indices on the wire (32, 33); byte 4 is the tail's
        // FrameToWrite (== frame_count == 2).
        let mut a = step("a", Pump::Pressure);
        a.limiter = Some(Limiter {
            value: 8.0,
            range: 0.5,
        });
        let mut b = step("b", Pump::Flow);
        b.limiter = Some(Limiter {
            value: 9.0,
            range: 0.5,
        });
        let assembled = profile(vec![a, b]).assemble().unwrap();
        let packets = assembled.frame_packets();
        assert_eq!(packets.len(), 5);
        assert_eq!(packets[0][0], 0, "normal frame 0 first");
        assert_eq!(packets[1][0], 1, "normal frame 1 second");
        assert_eq!(packets[2][0], 32, "extension index 0 (= +32) third");
        assert_eq!(packets[3][0], 33, "extension index 1 (= +32) fourth");
        assert_eq!(packets[4][0], 2, "tail's FrameToWrite == frame_count last");
    }

    #[test]
    fn a_profile_round_trips_through_json_and_assembles() {
        // The bridge serializes a profile to JSON and deserializes it back;
        // pin that round-trip and then confirm the result still assembles
        // identically.
        let original = profile(vec![
            step("a", Pump::Pressure),
            step("b", Pump::Flow),
        ]);
        let json = serde_json::to_string(&original).unwrap();
        let restored: Profile = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.assemble(), original.assemble());
    }
}
