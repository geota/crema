//! What a completed shot does besides its history row (REVIEW-CORE M4): one
//! rule for both shells.
//!
//! - **Only the device that records the shot acts on it.** The hub, or a
//!   device on its own, records the shot; a remote's mirror copy of the
//!   hub's shot is history only. It never debits its own copy of the hub's
//!   bag (owner decision, round 2: a bag both devices hold would be debited
//!   twice), never auto-saves the grind (review F8), and never uploads or
//!   fires the webhook (the hub does).
//! - **A replay touches nothing real.** No debit, no auto-save, no webhook.
//!   It still uploads, as a live shot would, so a replay demo exercises the
//!   upload path.
//! - **The dose was ground.** An aborted pull, held behind its Undo, is
//!   debited at once, and its webhook fires; its auto-save and uploads wait
//!   for the Undo, which keeps it.
//! - **A cleaning run is nothing.** No row, no debit, nothing else.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::shot::ShotDisposition;

/// What [`shot_effects`] reads.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ShotEffectsInput {
    /// The core's classification of the completion (`ShotCompleted`'s
    /// `disposition`).
    pub disposition: ShotDisposition,
    /// This device recorded the shot: the hub, or a device on its own. A
    /// remote watching the hub's shot passes `false`.
    pub recorded_here: bool,
    /// The shot is a replay of a capture, not a live pull.
    pub replay: bool,
}

/// What a completed shot does. "When kept": at once for a recorded shot, on
/// the Undo for a discarded aborted pull.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotEffects {
    /// Add the history row now.
    pub record: bool,
    /// Hold the row behind an Undo (an aborted pull) instead.
    pub offer_undo: bool,
    /// Debit the dose from the shot's bag (its frozen `bean.beanId`) now.
    pub debit: bool,
    /// Fire the `shotCompleted` webhook now.
    pub webhook: bool,
    /// When kept: save the shot's grind to its bag, if the device's
    /// auto-save setting is on ([`crate::bag_grind::auto_save_shot_grind`]
    /// keeps its own gates).
    pub auto_save: bool,
    /// When kept: offer the shot to the armed upload destinations (each
    /// keeps its own gates).
    pub upload: bool,
}

/// The one rule for what a completed shot does — see the module docs.
#[must_use]
pub fn shot_effects(input: &ShotEffectsInput) -> ShotEffects {
    let (record, offer_undo) = match input.disposition {
        ShotDisposition::SkipCleaning => return ShotEffects::default(),
        ShotDisposition::Record => (true, false),
        ShotDisposition::DiscardAborted => (false, true),
    };
    let real = input.recorded_here && !input.replay;
    ShotEffects {
        record,
        offer_undo,
        debit: real,
        webhook: real,
        auto_save: real,
        upload: input.recorded_here,
    }
}

/// JSON-bridged [`shot_effects`]: a [`ShotEffectsInput`] JSON in, a
/// [`ShotEffects`] JSON out.
///
/// # Errors
/// The JSON parse error string on a malformed input.
pub fn shot_effects_json(input_json: &str) -> Result<String, String> {
    let input: ShotEffectsInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&shot_effects(&input)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn effects(disposition: ShotDisposition, recorded_here: bool, replay: bool) -> ShotEffects {
        shot_effects(&ShotEffectsInput {
            disposition,
            recorded_here,
            replay,
        })
    }

    #[test]
    fn a_live_shot_on_the_recording_device_does_everything() {
        assert_eq!(
            effects(ShotDisposition::Record, true, false),
            ShotEffects {
                record: true,
                offer_undo: false,
                debit: true,
                webhook: true,
                auto_save: true,
                upload: true,
            }
        );
    }

    #[test]
    fn a_remote_records_the_hubs_shot_and_does_nothing_else() {
        // Owner decision: a remote never debits its own copy of the hub's bag.
        assert_eq!(
            effects(ShotDisposition::Record, false, false),
            ShotEffects {
                record: true,
                ..ShotEffects::default()
            }
        );
        let aborted = effects(ShotDisposition::DiscardAborted, false, false);
        assert!(aborted.offer_undo);
        assert!(!aborted.debit && !aborted.webhook && !aborted.auto_save && !aborted.upload);
    }

    #[test]
    fn a_replay_touches_no_bag_and_still_uploads() {
        let replay = effects(ShotDisposition::Record, true, true);
        assert!(replay.record && replay.upload);
        assert!(!replay.debit && !replay.auto_save && !replay.webhook);
    }

    #[test]
    fn an_aborted_pull_is_debited_now_and_kept_only_on_undo() {
        let aborted = effects(ShotDisposition::DiscardAborted, true, false);
        assert!(!aborted.record && aborted.offer_undo);
        assert!(aborted.debit && aborted.webhook, "the dose was ground");
        assert!(aborted.auto_save && aborted.upload, "on the Undo");
    }

    #[test]
    fn a_cleaning_run_does_nothing() {
        for (here, replay) in [(true, false), (false, false), (true, true)] {
            assert_eq!(
                effects(ShotDisposition::SkipCleaning, here, replay),
                ShotEffects::default()
            );
        }
    }

    #[test]
    fn the_json_bridge_reads_the_wire_names() {
        let out =
            shot_effects_json(r#"{"disposition":"DiscardAborted","recordedHere":true}"#).unwrap();
        let back: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(back["offerUndo"], true);
        assert_eq!(back["autoSave"], true);
        assert_eq!(back["record"], false);
        assert!(shot_effects_json("{").is_err());
    }
}
