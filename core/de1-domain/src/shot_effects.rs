//! What a completed shot does besides its history row (REVIEW-CORE M4): one
//! rule for both shells.
//!
//! - **Only the device that records the shot acts on it.** The hub, or a
//!   device on its own, records the shot; a remote's mirror copy of the
//!   hub's shot is history only. It never debits its own copy of the hub's
//!   bag (owner decision, round 2: a bag both devices hold would be debited
//!   twice), never auto-saves the grind (review F8), and never uploads or
//!   fires the webhook (the hub does).
//! - **A replay touches nothing real** (owner decision, #488 batch 6). It
//!   is recorded in History, marked as a replay
//!   ([`StoredShot::replay`](crate::StoredShot::replay)), and never debits,
//!   auto-saves, fires the webhook or uploads: a capture of a shot the user
//!   already has would put a second copy on Visualizer.
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
        upload: real,
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

// ── The bag a shot debits (review MF1) ──────────────────────────────────────

/// A bag's weight as stored on this device, for [`shot_debit`] and
/// [`shot_bag_move`].
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BagWeight {
    /// Grams left (`Bean::remaining`).
    pub remaining: f32,
    /// The declared size, 0 for none (`Bean::bag_size`).
    pub bag_size: f32,
}

/// What [`shot_debit`] reads: a completed shot's bag, dose and effects.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ShotDebitInput {
    /// The row names a bag (its frozen `bean.beanId`).
    pub has_bag: bool,
    /// That bag as stored here; `None` when this library doesn't hold it.
    pub bag: Option<BagWeight>,
    /// The dose the row records, grams; `None` or non-positive = no dose.
    pub dose_g: Option<f32>,
    /// [`ShotEffects::debit`] for this shot.
    pub debit: bool,
}

/// What a completed shot does to its bag, and what its row records.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotDebit {
    /// The bag's new `remaining`; `None` = leave the bag as it is (nothing
    /// to persist, `updated_at` untouched).
    pub remaining: Option<f32>,
    /// The row's [`StoredShot::bag_debit`](crate::StoredShot::bag_debit):
    /// the grams taken, `Some(0)` for a row with a bag that wasn't debited
    /// (a remote's mirror shot, a replay, an empty or unheld bag), `None`
    /// only for a row with no bag.
    pub bag_debit: Option<f32>,
    /// The debit emptied the bag (the shell's bag-empty prompt).
    pub emptied: bool,
}

/// A completed shot's debit (review MF1): the history contract for
/// `bag_debit` in one place, so no shell writes `None` for a shot that has a
/// bag but wasn't debited (a later move would credit a dose never taken).
#[must_use]
pub fn shot_debit(input: &ShotDebitInput) -> ShotDebit {
    if !input.has_bag {
        return ShotDebit::default();
    }
    let dose = input.dose_g.unwrap_or(0.0);
    match input.bag.filter(|_| input.debit) {
        Some(bag) => {
            let remaining = crate::debit_remaining(bag.remaining, dose);
            ShotDebit {
                remaining,
                bag_debit: Some(crate::debit_taken(bag.remaining, dose)),
                emptied: remaining == Some(0.0),
            }
        }
        None => ShotDebit {
            bag_debit: Some(0.0),
            ..ShotDebit::default()
        },
    }
}

/// What [`shot_bag_move`] reads: a logged shot moved to another bag (or to
/// none) in History.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ShotBagMoveInput {
    /// The row's bag id now; `None` = no bag.
    pub from_bag_id: Option<String>,
    /// That bag as stored here; `None` when this library doesn't hold it.
    pub from_bag: Option<BagWeight>,
    /// The bag the user names; `None` = no bag.
    pub to_bag_id: Option<String>,
    /// That bag as stored here; `None` when this library doesn't hold it.
    pub to_bag: Option<BagWeight>,
    /// The row's `bag_debit` ([`StoredShot::bag_debit`](crate::StoredShot::bag_debit)).
    pub bag_debit: Option<f32>,
    /// The row's dose, grams; `None` or non-positive = no dose.
    pub dose_g: Option<f32>,
    /// This device is a remote: it never debits its own copy of a bag
    /// (owner decision, round 2), so the new bag isn't debited here either.
    pub remote: bool,
    /// The row is a replay ([`StoredShot::replay`](crate::StoredShot::replay)):
    /// it never debits.
    pub replay: bool,
    /// The row is a remote's mirror copy of the hub's shot: recorded
    /// elsewhere, never debited here. Its `bag_debit` of `None` (what Android
    /// wrote for these rows from #506 until `shot_debit`) then means nothing
    /// was taken, not "a row from before the field". `false` for a row this
    /// device recorded, so a legacy local row keeps the dose rule.
    pub mirror_row: bool,
}

/// A logged shot's bag move, planned.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotBagMove {
    /// The move changes the row: false when the bag is the same (nothing to
    /// do, nothing to persist).
    pub moves: bool,
    /// The old bag's new `remaining` (the grams the row took go back);
    /// `None` = leave it.
    pub from_remaining: Option<f32>,
    /// The new bag's new `remaining`; `None` = leave it.
    pub to_remaining: Option<f32>,
    /// The row's new `bag_debit`, written with the new bag.
    pub bag_debit: Option<f32>,
    /// The debit emptied the new bag (the shell's bag-empty prompt).
    pub to_emptied: bool,
}

/// Move a logged shot to another bag, or to none (review MF1): the grounds
/// came from the bag the user now names, so the debit moves with the
/// attribution.
///
/// - The old bag gets back **only what the row took**
///   ([`crate::credit_shot_debit`]): a recorded `bag_debit` says how much, so
///   a row that was never debited (`Some(0)`: a mirror shot, a replay)
///   credits nothing, and neither does a remote's mirror row with `None`
///   ([`ShotBagMoveInput::mirror_row`]). A row this device recorded before
///   the field (`None`) took its dose if the bag still holds grams.
/// - The new bag is debited by the dose **only where a recorded shot would
///   debit it** ([`shot_effects`]: not on a remote, not for a replay); the
///   row records what it took ([`shot_debit`]), 0 when nothing.
/// - Moving to no bag records `None`.
#[must_use]
pub fn shot_bag_move(input: &ShotBagMoveInput) -> ShotBagMove {
    let blank = |id: &Option<String>| {
        id.as_deref()
            .filter(|s| !s.trim().is_empty())
            .map(str::to_owned)
    };
    let (from, to) = (blank(&input.from_bag_id), blank(&input.to_bag_id));
    if from == to {
        return ShotBagMove::default();
    }
    let dose = input.dose_g.unwrap_or(0.0);
    // A mirror row took nothing here unless it says otherwise.
    let taken = if input.mirror_row {
        Some(input.bag_debit.unwrap_or(0.0))
    } else {
        input.bag_debit
    };
    let from_remaining = from
        .as_ref()
        .and(input.from_bag)
        .and_then(|bag| crate::credit_shot_debit(bag.remaining, bag.bag_size, taken, dose));
    let debit = shot_effects(&ShotEffectsInput {
        disposition: ShotDisposition::Record,
        recorded_here: !input.remote,
        replay: input.replay,
    })
    .debit;
    let to_debit = shot_debit(&ShotDebitInput {
        has_bag: to.is_some(),
        bag: input.to_bag,
        dose_g: input.dose_g,
        debit,
    });
    ShotBagMove {
        moves: true,
        from_remaining,
        to_remaining: to_debit.remaining,
        bag_debit: to_debit.bag_debit,
        to_emptied: to_debit.emptied,
    }
}

/// JSON-bridged [`shot_debit`]: a [`ShotDebitInput`] JSON in, a
/// [`ShotDebit`] JSON out.
///
/// # Errors
/// The JSON parse error string on a malformed input.
pub fn shot_debit_json(input_json: &str) -> Result<String, String> {
    let input: ShotDebitInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&shot_debit(&input)).map_err(|e| e.to_string())
}

/// JSON-bridged [`shot_bag_move`]: a [`ShotBagMoveInput`] JSON in, a
/// [`ShotBagMove`] JSON out.
///
/// # Errors
/// The JSON parse error string on a malformed input.
pub fn shot_bag_move_json(input_json: &str) -> Result<String, String> {
    let input: ShotBagMoveInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&shot_bag_move(&input)).map_err(|e| e.to_string())
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
    fn a_replay_is_recorded_and_touches_nothing_else() {
        // Owner decision (#488 batch 6): never uploaded, never debited.
        let replay = effects(ShotDisposition::Record, true, true);
        assert_eq!(
            replay,
            ShotEffects {
                record: true,
                ..ShotEffects::default()
            }
        );
        let aborted = effects(ShotDisposition::DiscardAborted, true, true);
        assert!(aborted.offer_undo && !aborted.upload && !aborted.debit);
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

    // ── shot_debit / shot_bag_move (review MF1) ──

    fn bag(remaining: f32, bag_size: f32) -> Option<BagWeight> {
        Some(BagWeight {
            remaining,
            bag_size,
        })
    }

    #[test]
    fn a_shot_with_a_bag_that_was_not_debited_records_zero() {
        // The MF1 repro: a remote's mirror shot (debit false) on a bag the
        // remote holds records 0, not None.
        let mirror = shot_debit(&ShotDebitInput {
            has_bag: true,
            bag: bag(300.0, 340.0),
            dose_g: Some(18.0),
            debit: false,
        });
        assert_eq!(
            mirror,
            ShotDebit {
                remaining: None,
                bag_debit: Some(0.0),
                emptied: false
            }
        );
        // A bag this library doesn't hold: 0 too.
        let unheld = shot_debit(&ShotDebitInput {
            has_bag: true,
            bag: None,
            dose_g: Some(18.0),
            debit: true,
        });
        assert_eq!(unheld.bag_debit, Some(0.0));
        // No bag: None.
        let none = shot_debit(&ShotDebitInput {
            has_bag: false,
            bag: bag(300.0, 340.0),
            dose_g: Some(18.0),
            debit: true,
        });
        assert_eq!(none, ShotDebit::default());
    }

    #[test]
    fn a_debited_shot_records_what_it_took() {
        let live = shot_debit(&ShotDebitInput {
            has_bag: true,
            bag: bag(300.0, 340.0),
            dose_g: Some(18.0),
            debit: true,
        });
        assert_eq!(
            (live.remaining, live.bag_debit, live.emptied),
            (Some(282.0), Some(18.0), false)
        );
        let last = shot_debit(&ShotDebitInput {
            has_bag: true,
            bag: bag(10.0, 340.0),
            dose_g: Some(18.0),
            debit: true,
        });
        assert_eq!(
            (last.remaining, last.bag_debit, last.emptied),
            (Some(0.0), Some(10.0), true)
        );
        let empty = shot_debit(&ShotDebitInput {
            has_bag: true,
            bag: bag(0.0, 340.0),
            dose_g: Some(18.0),
            debit: true,
        });
        assert_eq!((empty.remaining, empty.bag_debit), (None, Some(0.0)));
        let no_dose = shot_debit(&ShotDebitInput {
            has_bag: true,
            bag: bag(300.0, 340.0),
            dose_g: None,
            debit: true,
        });
        assert_eq!((no_dose.remaining, no_dose.bag_debit), (None, Some(0.0)));
    }

    fn mv(bag_debit: Option<f32>, remote: bool, replay: bool) -> ShotBagMoveInput {
        ShotBagMoveInput {
            from_bag_id: Some("bean:b".into()),
            from_bag: bag(300.0, 340.0),
            to_bag_id: Some("bean:c".into()),
            to_bag: bag(200.0, 250.0),
            bag_debit,
            dose_g: Some(18.0),
            remote,
            replay,
            mirror_row: false,
        }
    }

    #[test]
    fn a_mirror_row_with_no_recorded_debit_credits_nothing() {
        // REVIEW-518: Android wrote None on a remote's mirror rows from #506.
        let mut input = mv(None, true, false);
        input.mirror_row = true;
        let plan = shot_bag_move(&input);
        assert_eq!(
            (plan.from_remaining, plan.to_remaining, plan.bag_debit),
            (None, None, Some(0.0))
        );
        // A recorded debit on a mirror row still says what it took.
        input.bag_debit = Some(5.0);
        assert_eq!(shot_bag_move(&input).from_remaining, Some(305.0));
        // A legacy row this device recorded keeps the dose rule.
        let legacy = shot_bag_move(&mv(None, true, false));
        assert_eq!(legacy.from_remaining, Some(318.0));
        assert_eq!(legacy.to_remaining, None, "a remote still never debits");
    }

    #[test]
    fn moving_a_shot_that_was_never_debited_credits_nothing() {
        // The MF1 repro on the remote: B keeps 300 g, C isn't debited.
        let plan = shot_bag_move(&mv(Some(0.0), true, false));
        assert_eq!(
            plan,
            ShotBagMove {
                moves: true,
                from_remaining: None,
                to_remaining: None,
                bag_debit: Some(0.0),
                to_emptied: false
            }
        );
        // A replay on the recording device: the same.
        let replay = shot_bag_move(&mv(Some(0.0), false, true));
        assert_eq!(
            (replay.from_remaining, replay.to_remaining, replay.bag_debit),
            (None, None, Some(0.0))
        );
    }

    #[test]
    fn moving_a_debited_shot_credits_what_it_took_and_debits_the_new_bag() {
        let plan = shot_bag_move(&mv(Some(18.0), false, false));
        assert_eq!(
            (plan.from_remaining, plan.to_remaining, plan.bag_debit),
            (Some(318.0), Some(182.0), Some(18.0))
        );
        // A partial debit goes back as it was.
        let part = shot_bag_move(&mv(Some(5.0), false, false));
        assert_eq!(part.from_remaining, Some(305.0));
        // A row from before the field took its dose from a bag with grams.
        let old = shot_bag_move(&mv(None, false, false));
        assert_eq!(old.from_remaining, Some(318.0));
    }

    #[test]
    fn moving_to_no_bag_credits_and_records_none() {
        let mut input = mv(Some(18.0), false, false);
        input.to_bag_id = None;
        input.to_bag = None;
        let plan = shot_bag_move(&input);
        assert_eq!(
            plan,
            ShotBagMove {
                moves: true,
                from_remaining: Some(318.0),
                to_remaining: None,
                bag_debit: None,
                to_emptied: false
            }
        );
        // From no bag to a bag: nothing to credit, the new bag debited.
        let mut input = mv(None, false, false);
        input.from_bag_id = None;
        input.from_bag = None;
        let plan = shot_bag_move(&input);
        assert_eq!(
            (plan.from_remaining, plan.to_remaining, plan.bag_debit),
            (None, Some(182.0), Some(18.0))
        );
    }

    #[test]
    fn the_same_bag_is_no_move_and_an_emptied_bag_says_so() {
        let mut same = mv(Some(18.0), false, false);
        same.to_bag_id = Some("bean:b".into());
        assert_eq!(shot_bag_move(&same), ShotBagMove::default());
        let mut blank = mv(None, false, false);
        blank.from_bag_id = Some(" ".into());
        blank.to_bag_id = None;
        assert!(!shot_bag_move(&blank).moves, "blank is no bag");
        let mut low = mv(Some(18.0), false, false);
        low.to_bag = bag(10.0, 250.0);
        let plan = shot_bag_move(&low);
        assert_eq!(
            (plan.to_remaining, plan.bag_debit, plan.to_emptied),
            (Some(0.0), Some(10.0), true)
        );
    }

    #[test]
    fn the_debit_bridges_read_the_wire_names() {
        let out = shot_bag_move_json(
            r#"{"fromBagId":"bean:b","fromBag":{"remaining":300,"bagSize":340},"toBagId":"bean:c","toBag":{"remaining":200},"bagDebit":0,"doseG":18,"remote":true}"#,
        )
        .unwrap();
        let back: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(back["moves"], true);
        assert_eq!(back["bagDebit"], 0.0);
        assert!(back["fromRemaining"].is_null() && back["toRemaining"].is_null());
        let out = shot_debit_json(r#"{"hasBag":true,"bag":{"remaining":300},"doseG":18}"#).unwrap();
        let back: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(back["bagDebit"], 0.0, "not debited: 0");
        assert!(shot_debit_json("{").is_err() && shot_bag_move_json("[").is_err());
    }
}
