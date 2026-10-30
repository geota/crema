//! Assembling a [`StoredShot`] from the core's own [`ShotRecord`] (F10).
//!
//! The core records every shot — the DE1 samples with their frame numbers,
//! and the scale / volume / resistance overlays — and knows how it ran: the
//! profile and Quick Controls the DE1 held, the stop switches, why it
//! stopped, the settled yield. A shell hands in only what the core can't
//! know ([`ShotCaptureContext`]: the wall clock, the bean snapshot, the
//! grinder, the grind dial, the barista, tags, the machine), and
//! [`assemble_stored_shot`] applies one rule set for both shells:
//!
//! - **As-run values.** With the profile the DE1 held, the stored profile is
//!   the *effective* one ([`effective_crema_profile`]: brew temperature
//!   shifted, pre-infusion rescaled), and the dose, yield target, brew
//!   temperature and pre-infusion are its values — the Quick Controls
//!   override, else the profile's own. Without it (a profile another app
//!   loaded) the shot keeps the profile's name only and no Quick Controls.
//! - **Grinder.** The bean's grinder, else the Settings grinder, frozen at
//!   capture — history never changes when Settings do.
//! - **Measured values.** The settled final weight, the stop reason and the
//!   stop switches come from the core.
//! - **Full series.** The stored record keeps every sample (up to
//!   [`crate::MAX_SHOT_SAMPLES`]); thinning is for display only, so a
//!   re-upload reproduces the original telemetry.
//! - **No telemetry, no shot.**

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::ShotBean;
use crate::crema_profile::{CremaProfile, to_wire};
use crate::effective_profile::{BrewOverrides, effective_crema_profile, preinfusion_seconds};
use crate::history::{ShotMachine, StoredShot};
use crate::shot::ShotRecord;
use crate::stop::StopReason;

/// What a shell knows about a finished shot that the core doesn't — handed
/// to the core's `take_stored_shot` on `Event::ShotCompleted`.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotCaptureContext {
    /// When the shot completed, Unix epoch ms — the core has no wall clock.
    #[typeshare(serialized_as = "I64")]
    pub completed_at: u64,
    /// The id to store the shot under, or `None` to mint a fresh one (a
    /// shell that showed the id already, e.g. Android's "Last shot" card).
    #[serde(default)]
    pub id: Option<String>,
    /// The bean snapshot the shot was pulled with, or `None`.
    #[serde(default)]
    pub bean: Option<ShotBean>,
    /// The Settings grinder model, the fallback when the bean has none.
    #[serde(default)]
    pub settings_grinder: Option<String>,
    /// The grind dial at shot start, or `None` when it carried no value.
    #[serde(default)]
    pub grind_dial: Option<f32>,
    /// The barista, or `None`.
    #[serde(default)]
    pub barista: Option<String>,
    /// The shot's tags at capture (a copy of the bean's tags today). `None`
    /// (or `null` on the wire) = no tags.
    #[serde(default)]
    pub tags: Option<Vec<String>>,
    /// The DE1 the shot ran on, or `None` when it isn't known.
    #[serde(default)]
    pub machine: Option<ShotMachine>,
}

/// The profile and Quick Controls the DE1 held when the shot started.
#[derive(Debug, Clone, PartialEq)]
pub struct AsRunShot {
    /// The library profile.
    pub profile: CremaProfile,
    /// The Quick Controls overrides it was uploaded with.
    pub qc: BrewOverrides,
}

/// What the core measured and decided for one finished shot.
#[derive(Debug, Clone, PartialEq)]
pub struct CompletedShot {
    /// The recorded telemetry, overlays included.
    pub record: ShotRecord,
    /// The profile the DE1 held, when the core knows it.
    pub as_run: Option<AsRunShot>,
    /// The last profile title the core saw — the name-only fallback.
    pub profile_title: Option<String>,
    /// Why the core stopped the shot, if it did.
    pub stop_reason: Option<StopReason>,
    /// The settled final weight, grams.
    pub final_weight: Option<f32>,
    /// Whether stop-on-weight was armed.
    pub stop_on_weight: bool,
    /// Whether auto-tare was armed.
    pub auto_tare: bool,
}

/// The grind dial as stored text: a whole number without decimals, else one
/// decimal (`15`, `15.5`) — the web history's formatting.
fn grind_text(dial: f32) -> Option<String> {
    if !dial.is_finite() {
        return None;
    }
    if dial.fract() == 0.0 {
        Some(format!("{dial:.0}"))
    } else {
        Some(format!("{dial:.1}"))
    }
}

fn non_blank(s: Option<&str>) -> Option<String> {
    s.map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}

/// Build the [`StoredShot`] for a finished shot — see the module docs for
/// the rules. `None` when the record holds no telemetry.
#[must_use]
pub fn assemble_stored_shot(shot: CompletedShot, ctx: ShotCaptureContext) -> Option<StoredShot> {
    if shot.record.samples.is_empty() {
        return None;
    }
    let mut s = StoredShot::new(ctx.completed_at, shot.record);
    if let Some(id) = ctx.id.filter(|id| !id.is_empty()) {
        s.id = id;
    }
    if let Some(as_run) = &shot.as_run {
        let effective = effective_crema_profile(&as_run.profile, &as_run.qc);
        s.profile_name = non_blank(Some(&effective.name));
        s.metadata.dose = Some(effective.dose).filter(|d| *d > 0.0);
        s.yield_target = Some(effective.yield_out).filter(|y| *y > 0.0);
        s.brew_temp_target = Some(effective.brew_temp).filter(|t| t.is_finite());
        s.preinfuse_target = Some(preinfusion_seconds(&effective));
        s.profile = Some(to_wire(&effective));
    } else {
        s.profile_name = non_blank(shot.profile_title.as_deref());
    }
    s.stop_reason = shot.stop_reason;
    s.stop_on_weight = Some(shot.stop_on_weight);
    s.auto_tare = Some(shot.auto_tare);
    s.metadata.yield_out = shot.final_weight.filter(|w| w.is_finite() && *w > 0.0);
    s.metadata.grinder_setting = ctx.grind_dial.and_then(grind_text);
    s.metadata.barista = non_blank(ctx.barista.as_deref());
    s.grinder_model = ctx
        .bean
        .as_ref()
        .and_then(|b| non_blank(b.grinder.as_deref()))
        .or_else(|| non_blank(ctx.settings_grinder.as_deref()));
    s.tags = ctx.tags.unwrap_or_default();
    s.bean = ctx.bean;
    s.machine = ctx.machine;
    Some(s)
}

/// [`assemble_stored_shot`]'s context as JSON (the FFI / wasm boundary).
///
/// # Errors
///
/// The JSON error string on a malformed context.
pub fn parse_shot_capture_context(json: &str) -> Result<ShotCaptureContext, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shot::TimedSample;
    use de1_protocol::ShotSample;
    use std::time::Duration;

    fn sample(ms: u32, frame: u8) -> TimedSample {
        TimedSample {
            elapsed: Duration::from_millis(u64::from(ms)),
            sample: ShotSample {
                sample_time: 0,
                group_pressure: 9.0,
                group_flow: 2.0,
                mix_temp: 92.0,
                head_temp: 93.0,
                set_mix_temp: 92.0,
                set_head_temp: 93.0,
                set_group_pressure: 9.0,
                set_group_flow: 0.0,
                frame_number: frame,
                steam_temp: 140.0,
            },
            scale_weight: Some(f32::from(u16::try_from(ms).unwrap()) / 1000.0),
            scale_flow_weight: Some(1.5),
            dispensed_volume: Some(f32::from(u16::try_from(ms).unwrap()) / 500.0),
            resistance: Some(2.25),
            resistance_weight: Some(4.0),
        }
    }

    fn completed(as_run: Option<AsRunShot>) -> CompletedShot {
        CompletedShot {
            record: ShotRecord {
                duration: Duration::from_secs(30),
                samples: vec![sample(250, 0), sample(500, 2)],
            },
            as_run,
            profile_title: Some("Foreign".into()),
            stop_reason: None,
            final_weight: Some(36.4),
            stop_on_weight: true,
            auto_tare: false,
        }
    }

    fn profile() -> CremaProfile {
        let mut p = crate::blank_profile(&crate::crema_profile::BrewDefaults::default());
        p.name = "Test".into();
        p.dose = 18.0;
        p.yield_out = 36.0;
        p
    }

    #[test]
    fn the_effective_values_come_from_the_profile_the_de1_held() {
        let qc = BrewOverrides {
            dose: Some(20.0),
            yield_g: Some(40.0),
            brew_temp: None,
            preinf: None,
        };
        let shot = assemble_stored_shot(
            completed(Some(AsRunShot {
                profile: profile(),
                qc,
            })),
            ShotCaptureContext::default(),
        )
        .expect("a shot");
        assert_eq!(shot.profile_name.as_deref(), Some("Test"));
        assert_eq!(
            shot.metadata.dose,
            Some(20.0),
            "the QC dose dial, not the profile's"
        );
        assert_eq!(shot.yield_target, Some(40.0));
        assert!(shot.profile.is_some());
        assert!(shot.brew_temp_target.is_some());
        assert!(shot.preinfuse_target.is_some());
    }

    #[test]
    fn a_foreign_profile_keeps_its_name_and_no_overrides() {
        let shot = assemble_stored_shot(completed(None), ShotCaptureContext::default()).unwrap();
        assert_eq!(shot.profile_name.as_deref(), Some("Foreign"));
        assert!(shot.profile.is_none());
        assert_eq!(shot.metadata.dose, None);
        assert_eq!(shot.yield_target, None);
        assert_eq!(shot.brew_temp_target, None);
        assert_eq!(shot.preinfuse_target, None);
        // The stop switches and measurements are the core's either way.
        assert_eq!(shot.stop_on_weight, Some(true));
        assert_eq!(shot.metadata.yield_out, Some(36.4));
    }

    #[test]
    fn the_grind_dial_is_stored_like_the_web_history() {
        assert_eq!(grind_text(15.0).as_deref(), Some("15"));
        assert_eq!(grind_text(15.5).as_deref(), Some("15.5"));
        assert_eq!(grind_text(f32::NAN), None);
    }

    #[test]
    fn the_context_round_trips_as_json() {
        let ctx = ShotCaptureContext {
            completed_at: 1_700_000_000_000,
            id: Some("shot:1".into()),
            grind_dial: Some(2.5),
            tags: Some(vec!["dial-in".into()]),
            ..ShotCaptureContext::default()
        };
        let json = serde_json::to_string(&ctx).unwrap();
        assert!(json.contains("\"completedAt\":1700000000000"));
        assert_eq!(parse_shot_capture_context(&json).unwrap(), ctx);
        // Only completedAt is required, and a `null` (Kotlin's default) reads
        // as absent.
        let minimal =
            parse_shot_capture_context(r#"{"completedAt":1,"tags":null,"bean":null}"#).unwrap();
        assert_eq!(minimal.completed_at, 1);
        assert_eq!(minimal.tags, None);
    }
}
