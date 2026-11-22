//! Assembling a [`StoredShot`] from the core's own [`ShotRecord`] (F10).
//!
//! The core records every shot — the DE1 samples with their frame numbers,
//! and the scale / volume / resistance overlays — and knows how it ran: the
//! profile and Quick Controls the DE1 held, the stop switches, why it
//! stopped, the settled yield. It also holds what Brew had selected
//! ([`ShotSelections`]: the bag, the profile, the grinder, Brew's grind),
//! which the shells hand in as it changes, and freezes a copy on its own
//! shot-start event, whoever started the shot (the app or the group head;
//! REVIEW-CORE H3). At completion a shell hands in only what the core can't
//! know ([`ShotCaptureContext`]: the wall clock, the id, the barista, tags,
//! the machine), and [`assemble_stored_shot`] applies one rule set for both
//! shells:
//!
//! - **As-run values.** With the profile the DE1 held, the stored profile is
//!   the *effective* one ([`effective_crema_profile`]: brew temperature
//!   shifted, pre-infusion rescaled), and the dose, yield target, brew
//!   temperature and pre-infusion are its values — the Quick Controls
//!   override, else the profile's own. Without it (a profile another app
//!   loaded) the shot keeps the profile's name only and no Quick Controls.
//! - **Profile id.** The library id of the profile the DE1 held, else the
//!   frozen [`ShotSelections::profile_id`] when the shot's name is that
//!   profile's; stored next to the name snapshot (save-boundaries review F4).
//! - **Grinder.** The active grinder record's id and its name as a snapshot
//!   (#488, review F4), frozen at shot start (review F9), so switching
//!   grinder mid-shot can't mislabel it. Not the settings `grinder_model`,
//!   and not the bag's old stored grinder: the grinder is equipment picked
//!   on Brew. History never changes when the grinder does.
//! - **Grind.** Brew's grind at shot start, as text
//!   ([`crate::BrewGrindView::value`]): what Brew showed is what the shot
//!   records, a text grind included.
//! - **Bag snapshot.** The bag at shot start, without the bag's legacy
//!   `grinder_setting` / `grinder` (REVIEW-FINAL S2): a new row never carries
//!   them, so the old-row fallbacks in History and the exports read nothing
//!   for it.
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

/// What Brew has selected, for the next shot (REVIEW-CORE H3). The shells
/// hand it to the core (`CremaCore::set_shot_selections`) whenever a part
/// changes, the way they hand in the desired profile; the core freezes a
/// copy on its own shot-start event and the shot records that copy. On a
/// remote, the hub's selections (its mirror records the shot it watched);
/// for a replay, the capture's.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ShotSelections {
    /// The bag on Brew, as a snapshot ([`ShotBean::snapshot_of`]), or `None`.
    /// The shot's bag, and so the bag a recorded shot debits. Its legacy
    /// `grinder_setting` / `grinder` are never recorded.
    pub bean: Option<ShotBean>,
    /// The library id of the active profile, or `None`. The fallback for
    /// [`StoredShot::profile_id`] when the core doesn't hold the profile it
    /// ran; stamped only when the shot's name is this profile's
    /// ([`Self::profile_name`]), so a profile another app loaded never gets
    /// an id.
    pub profile_id: Option<String>,
    /// The active profile's name: the last fallback name when the core knows
    /// neither the profile it ran nor a title (a remote's mirror core and a
    /// replay's shadow core saw no upload).
    pub profile_name: Option<String>,
    /// The active grinder's id ([`crate::grinder::active_grinder`]), stamped
    /// as [`StoredShot::grinder_id`]. `None` or blank = no grinder.
    pub grinder_id: Option<String>,
    /// The active grinder's name ([`crate::grinder::Grinder::name`]): the
    /// shot's name snapshot ([`StoredShot::grinder_model`]). `None` or blank
    /// = no grinder. Not the settings `grinder_model`.
    pub grinder_name: Option<String>,
    /// Brew's grind as text ([`crate::BrewGrindView::value`]), recorded as is
    /// (trimmed), a grind that isn't a plain number included. `None` or blank
    /// = no grind.
    pub grind: Option<String>,
}

/// What a shell knows about a finished shot that the core doesn't — handed
/// to the core's `take_stored_shot` on `Event::ShotCompleted`. What Brew had
/// selected is not here: the core froze it at shot start
/// ([`ShotSelections`]), so a completion-time value can't reach the shot.
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
    /// What Brew had selected when the shot started (frozen by the core).
    pub selections: ShotSelections,
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
    let sel = shot.selections;
    let mut s = StoredShot::new(ctx.completed_at, shot.record);
    if let Some(id) = ctx.id.filter(|id| !id.is_empty()) {
        s.id = id;
    }
    if let Some(as_run) = &shot.as_run {
        let effective = effective_crema_profile(&as_run.profile, &as_run.qc);
        s.profile_name = non_blank(Some(&effective.name));
        s.profile_id =
            non_blank(Some(&as_run.profile.id)).or_else(|| non_blank(sel.profile_id.as_deref()));
        s.metadata.dose = Some(effective.dose).filter(|d| *d > 0.0);
        s.yield_target = Some(effective.yield_out).filter(|y| *y > 0.0);
        s.brew_temp_target = Some(effective.brew_temp).filter(|t| t.is_finite());
        s.preinfuse_target = Some(preinfusion_seconds(&effective));
        s.profile = Some(to_wire(&effective));
    } else {
        s.profile_name = non_blank(shot.profile_title.as_deref())
            .or_else(|| non_blank(sel.profile_name.as_deref()));
        // The selected id only when the name is its profile's: a title the
        // DE1 reported may be a profile another app loaded.
        if s.profile_name.is_some() && s.profile_name == non_blank(sel.profile_name.as_deref()) {
            s.profile_id = non_blank(sel.profile_id.as_deref());
        }
    }
    // An estimated stop is stored as `Weight`, which older builds parse,
    // flagged `stop_estimated` (`DESIGN.md` §3.5).
    s.stop_estimated = shot.stop_reason == Some(StopReason::EstimatedWeight);
    s.stop_reason = shot.stop_reason.map(|reason| match reason {
        StopReason::EstimatedWeight => StopReason::Weight,
        other => other,
    });
    s.stop_on_weight = Some(shot.stop_on_weight);
    s.auto_tare = Some(shot.auto_tare);
    s.metadata.yield_out = shot.final_weight.filter(|w| w.is_finite() && *w > 0.0);
    s.metadata.grinder_setting = non_blank(sel.grind.as_deref());
    s.metadata.barista = non_blank(ctx.barista.as_deref());
    s.grinder_model = non_blank(sel.grinder_name.as_deref());
    s.grinder_id = non_blank(sel.grinder_id.as_deref());
    s.tags = ctx.tags.unwrap_or_default();
    s.bean = sel.bean.map(|bean| ShotBean {
        grinder_setting: None,
        grinder: None,
        ..bean
    });
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

/// [`ShotSelections`] as JSON (the FFI / wasm boundary).
///
/// # Errors
///
/// The JSON error string on malformed selections.
pub fn parse_shot_selections(json: &str) -> Result<ShotSelections, String> {
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
            selections: ShotSelections::default(),
        }
    }

    fn with(sel: ShotSelections, mut shot: CompletedShot) -> CompletedShot {
        shot.selections = sel;
        shot
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
    fn the_selected_profile_name_is_the_last_fallback() {
        let mut nameless = completed(None);
        nameless.profile_title = None;
        let sel = ShotSelections {
            profile_name: Some("From the capture".into()),
            ..ShotSelections::default()
        };
        let shot = assemble_stored_shot(with(sel.clone(), nameless), ShotCaptureContext::default())
            .unwrap();
        assert_eq!(shot.profile_name.as_deref(), Some("From the capture"));
        // The core's own title wins over it.
        let shot = assemble_stored_shot(with(sel, completed(None)), ShotCaptureContext::default())
            .unwrap();
        assert_eq!(shot.profile_name.as_deref(), Some("Foreign"));
    }

    #[test]
    fn the_profile_id_is_the_held_profiles_else_the_selected_one_for_its_name() {
        let held = profile();
        let shot = assemble_stored_shot(
            with(
                ShotSelections {
                    profile_id: Some("profile:other".into()),
                    ..ShotSelections::default()
                },
                completed(Some(AsRunShot {
                    profile: held.clone(),
                    qc: BrewOverrides::default(),
                })),
            ),
            ShotCaptureContext::default(),
        )
        .unwrap();
        assert_eq!(shot.profile_id.as_deref(), Some(held.id.as_str()));
        assert_eq!(shot.profile_name.as_deref(), Some("Test"));

        // A replay: the core held nothing, the selections name the profile.
        let mut nameless = completed(None);
        nameless.profile_title = None;
        let sel = ShotSelections {
            profile_name: Some("From the capture".into()),
            profile_id: Some("profile:capture".into()),
            ..ShotSelections::default()
        };
        let shot = assemble_stored_shot(with(sel.clone(), nameless), ShotCaptureContext::default())
            .unwrap();
        assert_eq!(shot.profile_id.as_deref(), Some("profile:capture"));

        // A title another app loaded never takes the selected id.
        let shot = assemble_stored_shot(with(sel, completed(None)), ShotCaptureContext::default())
            .unwrap();
        assert_eq!(shot.profile_name.as_deref(), Some("Foreign"));
        assert_eq!(shot.profile_id, None);
        let shot = assemble_stored_shot(completed(None), ShotCaptureContext::default()).unwrap();
        assert_eq!(shot.profile_id, None);
    }

    fn grind_of(grind: Option<&str>) -> Option<String> {
        let sel = ShotSelections {
            grind: grind.map(str::to_owned),
            ..ShotSelections::default()
        };
        assemble_stored_shot(with(sel, completed(None)), ShotCaptureContext::default())
            .unwrap()
            .metadata
            .grinder_setting
    }

    #[test]
    fn brews_grind_is_recorded_as_shown() {
        assert_eq!(grind_of(Some(" 2.1.3 ")).as_deref(), Some("2.1.3"));
        // A fine step is never rounded (REVIEW-FINAL M2).
        assert_eq!(grind_of(Some("2.25")).as_deref(), Some("2.25"));
        assert_eq!(grind_of(Some("0")).as_deref(), Some("0"));
        assert_eq!(grind_of(Some("4⅓")).as_deref(), Some("4⅓"));
        assert_eq!(grind_of(Some(" ")), None);
        assert_eq!(grind_of(None), None);
    }

    #[test]
    fn the_grinder_is_the_selected_record() {
        let sel = ShotSelections {
            grinder_id: Some(" grinder:ek43 ".into()),
            grinder_name: Some(" EK43 ".into()),
            ..ShotSelections::default()
        };
        let shot = assemble_stored_shot(with(sel, completed(None)), ShotCaptureContext::default())
            .unwrap();
        assert_eq!(shot.grinder_id.as_deref(), Some("grinder:ek43"));
        assert_eq!(shot.grinder_model.as_deref(), Some("EK43"));
        // No grinder selected: none recorded (no settings fallback).
        let shot = assemble_stored_shot(completed(None), ShotCaptureContext::default()).unwrap();
        assert_eq!(shot.grinder_id, None);
        assert_eq!(shot.grinder_model, None);
    }

    #[test]
    fn a_new_rows_bag_snapshot_has_no_legacy_grind_or_grinder() {
        // REVIEW-FINAL S2: the bag's frozen `grinder_setting` / `grinder`
        // never reach a new row, so nothing falls back to them for it.
        let sel = ShotSelections {
            bean: Some(ShotBean {
                bean_id: Some("bean:1".into()),
                name: "Gesha".into(),
                grinder_setting: Some("14".into()),
                grinder: Some("Niche Zero".into()),
                notes: Some("floral".into()),
                ..ShotBean::default()
            }),
            ..ShotSelections::default()
        };
        let shot = assemble_stored_shot(with(sel, completed(None)), ShotCaptureContext::default())
            .unwrap();
        let bean = shot.bean.expect("the bag");
        assert_eq!(bean.bean_id.as_deref(), Some("bean:1"));
        assert_eq!(bean.notes.as_deref(), Some("floral"));
        assert_eq!(bean.grinder_setting, None);
        assert_eq!(bean.grinder, None);
        assert_eq!(shot.metadata.grinder_setting, None, "no grind on Brew");
        assert_eq!(shot.grinder_model, None);
    }

    #[test]
    fn the_context_round_trips_as_json() {
        let ctx = ShotCaptureContext {
            completed_at: 1_700_000_000_000,
            id: Some("shot:1".into()),
            tags: Some(vec!["dial-in".into()]),
            ..ShotCaptureContext::default()
        };
        let json = serde_json::to_string(&ctx).unwrap();
        assert!(json.contains("\"completedAt\":1700000000000"));
        assert_eq!(parse_shot_capture_context(&json).unwrap(), ctx);
        // Only completedAt is required, and a `null` (Kotlin's default) reads
        // as absent.
        let minimal =
            parse_shot_capture_context(r#"{"completedAt":1,"tags":null,"machine":null}"#).unwrap();
        assert_eq!(minimal.completed_at, 1);
        assert_eq!(minimal.tags, None);
    }
}
