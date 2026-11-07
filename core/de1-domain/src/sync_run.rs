//! # sync-run
//!
//! How a sync run ends, and what the user sees for it. Every run ends in one
//! [`RunOutcome`], and each service keeps one [`SyncHealth`] from it, so nothing
//! is skipped without being counted and a background problem has a lasting place
//! in Settings rather than a repeat notice.
//!
//! The cursor rule lives in the type: only [`RunOutcome::Complete`] carries a
//! next cursor, so a run that stopped short can't move it, and every row it
//! applied stays applied.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::sync_failure::{AccountBlockReason, SyncFailure};

/// Why a run stopped before the end of its work, with the rows it applied kept.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PartialReason {
    /// The rate limit outlasted the run's waits.
    RateLimited,
    /// The network went away.
    Offline,
    /// Three transient failures in a row: an outage or a captive portal.
    ServerTrouble,
    /// The run reached its page limit.
    PageCap,
    /// Rows failed, and the run walked on past them.
    RowsFailed,
    /// The list kept shrinking under the walk.
    ListShifted,
    /// The history has no free slot for rows this run found.
    HistoryFull,
}

/// Why a run didn't start.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum BlockReason {
    /// The service's own state, or a store it needs, is still loading.
    Loading,
    /// A data store is damaged, so automatic work leaves it alone.
    StoreDamaged,
    /// The service's own state was damaged and kept aside: its marks, cursor and
    /// tombstones went with it.
    OwnStateLost,
    /// A store can't be read at all.
    StoreUnavailable,
    /// A restore was interrupted and still needs an answer.
    RestoreLeftover,
    /// The signed-in account isn't known yet, so nothing is sent to it.
    IdentityUnknown,
}

/// How a sync run ended.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum RunOutcome {
    /// Everything was walked. The only outcome that moves the cursor.
    Complete {
        /// Where the next run starts.
        #[typeshare(serialized_as = "I64")]
        next_cursor_ms: i64,
        /// Rows changed by this run.
        applied: u32,
        /// Rows already up to date.
        unchanged: u32,
        /// Rows counted and passed for good.
        skipped_permanent: u32,
    },
    /// The run stopped early. What it applied stays; the cursor holds.
    Partial {
        /// Why it stopped.
        reason: PartialReason,
        /// Rows changed by this run.
        applied: u32,
        /// Rows still to do, as far as the run knows.
        left: u32,
        /// Rows counted and passed for good.
        skipped_permanent: u32,
        /// How long to wait before the next run, ms, when something said so.
        #[typeshare(serialized_as = "Option<I64>")]
        resume_after_ms: Option<u64>,
    },
    /// The run achieved nothing.
    Failed {
        /// What stopped it.
        failure: SyncFailure,
    },
    /// The run didn't start.
    Blocked {
        /// What stopped it.
        why: BlockReason,
    },
}

/// Why a service is waiting by itself.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum WaitReason {
    /// No network.
    Offline,
    /// The server asked it to slow down.
    RateLimited,
}

/// What a service needs from the user. Each has an action in Settings.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum AttentionReason {
    /// The session is over: reconnect. The one that also raises a notice.
    Reconnect,
    /// The run stopped with rows left: Continue.
    RowsLeft,
    /// The server kept failing, or something else answered for it: Continue.
    ServerTrouble,
    /// Rows were counted and passed for good.
    RowsSkipped,
    /// The history has no free slot.
    HistoryFull,
    /// Drive has no room left.
    StorageFull,
    /// The free plan's daily cap was reached.
    Quota,
    /// The account needs Premium.
    NeedsPremium,
    /// The Decent account doesn't have this machine.
    NotOnAccount,
    /// The account itself is refused.
    AccountBlocked {
        /// Why.
        reason: AccountBlockReason,
    },
    /// The server refused the work, and retrying won't change that.
    Refused,
    /// A run couldn't start.
    Blocked {
        /// What stopped it.
        why: BlockReason,
    },
}

/// The one lasting status a service keeps, shown in Settings.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum SyncHealth {
    /// The last run completed.
    Ok {
        /// When, unix ms.
        #[typeshare(serialized_as = "I64")]
        at_ms: i64,
    },
    /// It will try again by itself. A Settings line, no notice.
    Waiting {
        /// When, unix ms, if anything said.
        #[typeshare(serialized_as = "Option<I64>")]
        until_ms: Option<i64>,
        /// Why.
        why: WaitReason,
    },
    /// It needs the user. A Settings line with its action.
    NeedsAttention {
        /// What it needs.
        why: AttentionReason,
        /// How many rows that leaves, or the cap for [`AttentionReason::Quota`].
        count: u32,
    },
}

/// What a run that ended in `outcome` at `now_ms` leaves the service showing.
#[must_use]
pub fn sync_health_after_run(outcome: &RunOutcome, now_ms: i64) -> SyncHealth {
    let until = |wait_ms: Option<u64>| {
        wait_ms.map(|ms| now_ms.saturating_add(i64::try_from(ms).unwrap_or(i64::MAX)))
    };
    match *outcome {
        RunOutcome::Complete {
            skipped_permanent: 0,
            ..
        } => SyncHealth::Ok { at_ms: now_ms },
        RunOutcome::Complete {
            skipped_permanent, ..
        } => SyncHealth::NeedsAttention {
            why: AttentionReason::RowsSkipped,
            count: skipped_permanent,
        },
        RunOutcome::Partial {
            reason,
            left,
            resume_after_ms,
            ..
        } => {
            let attention = |why| SyncHealth::NeedsAttention { why, count: left };
            let waiting = |why| SyncHealth::Waiting {
                until_ms: until(resume_after_ms),
                why,
            };
            match reason {
                PartialReason::RateLimited => waiting(WaitReason::RateLimited),
                PartialReason::Offline => waiting(WaitReason::Offline),
                PartialReason::ServerTrouble => attention(AttentionReason::ServerTrouble),
                PartialReason::HistoryFull => attention(AttentionReason::HistoryFull),
                PartialReason::PageCap | PartialReason::RowsFailed | PartialReason::ListShifted => {
                    attention(AttentionReason::RowsLeft)
                }
            }
        }
        RunOutcome::Failed { failure } => health_after_failure(failure, until),
        RunOutcome::Blocked { why } => SyncHealth::NeedsAttention {
            why: AttentionReason::Blocked { why },
            count: 0,
        },
    }
}

/// [`sync_health_after_run`] for a run that achieved nothing. `until` turns a
/// wait into a time.
fn health_after_failure(
    failure: SyncFailure,
    until: impl Fn(Option<u64>) -> Option<i64>,
) -> SyncHealth {
    let attention = |why| SyncHealth::NeedsAttention { why, count: 0 };
    match failure {
        SyncFailure::Offline => SyncHealth::Waiting {
            until_ms: None,
            why: WaitReason::Offline,
        },
        SyncFailure::RateLimited { retry_after_ms } => SyncHealth::Waiting {
            until_ms: until(retry_after_ms),
            why: WaitReason::RateLimited,
        },
        SyncFailure::Unavailable { .. } => attention(AttentionReason::ServerTrouble),
        SyncFailure::AccessRefused | SyncFailure::SessionEnded => {
            attention(AttentionReason::Reconnect)
        }
        SyncFailure::Quota { limit } => SyncHealth::NeedsAttention {
            why: AttentionReason::Quota,
            count: limit,
        },
        SyncFailure::NeedsPremium => attention(AttentionReason::NeedsPremium),
        SyncFailure::NotOnAccount => attention(AttentionReason::NotOnAccount),
        SyncFailure::AccountBlocked { reason } => {
            attention(AttentionReason::AccountBlocked { reason })
        }
        SyncFailure::StorageFull => attention(AttentionReason::StorageFull),
        SyncFailure::Forbidden | SyncFailure::NotFound | SyncFailure::Rejected { .. } => {
            attention(AttentionReason::Refused)
        }
    }
}

/// Whether moving from `previous` to `next` raises the one reconnect notice.
/// Automatic work notifies only on this transition, so a repeat of the same dead
/// session stays a Settings line.
#[must_use]
pub fn reconnect_notice_due(previous: Option<&SyncHealth>, next: &SyncHealth) -> bool {
    let needs_reconnect = |health: &SyncHealth| {
        matches!(
            health,
            SyncHealth::NeedsAttention {
                why: AttentionReason::Reconnect,
                ..
            }
        )
    };
    needs_reconnect(next) && !previous.is_some_and(needs_reconnect)
}

/// [`sync_health_after_run`] for the bridges: the outcome and the health as JSON.
///
/// # Errors
/// When `outcome_json` isn't a [`RunOutcome`].
pub fn sync_health_after_run_json(outcome_json: &str, now_ms: i64) -> Result<String, String> {
    let outcome: RunOutcome = serde_json::from_str(outcome_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&sync_health_after_run(&outcome, now_ms)).map_err(|e| e.to_string())
}

/// [`reconnect_notice_due`] for the bridges. A `previous` that can't be read counts
/// as none, and a `next` that can't be read raises nothing.
#[must_use]
pub fn reconnect_notice_due_json(previous: Option<&str>, next: &str) -> bool {
    let previous = previous.and_then(|json| serde_json::from_str(json).ok());
    serde_json::from_str(next).is_ok_and(|next| reconnect_notice_due(previous.as_ref(), &next))
}

/// What a confirmed `/me` means for what this device holds.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AccountChange {
    /// No account was on record: note this one and carry on. A user signed out
    /// at upgrade with no cached account lands here, which can't be checked.
    First,
    /// The account this device already synced with.
    Same,
    /// Another account: the cursor, marks and queue belong to the old one, and
    /// nothing goes up until the user says so.
    Changed,
}

/// Compare the account on record (`syncAccountId`) with the one `/me` named.
#[must_use]
pub fn account_change_action(stored: Option<&str>, me: &str) -> AccountChange {
    match stored {
        None | Some("") => AccountChange::First,
        Some(id) if id == me => AccountChange::Same,
        Some(_) => AccountChange::Changed,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    fn partial(reason: PartialReason, left: u32, resume_after_ms: Option<u64>) -> RunOutcome {
        RunOutcome::Partial {
            reason,
            applied: 3,
            left,
            skipped_permanent: 0,
            resume_after_ms,
        }
    }

    fn attention(why: AttentionReason, count: u32) -> SyncHealth {
        SyncHealth::NeedsAttention { why, count }
    }

    fn after_failure(failure: SyncFailure) -> SyncHealth {
        sync_health_after_run(&RunOutcome::Failed { failure }, NOW)
    }

    fn complete(skipped_permanent: u32) -> RunOutcome {
        RunOutcome::Complete {
            next_cursor_ms: NOW,
            applied: 3,
            unchanged: 1,
            skipped_permanent,
        }
    }

    #[test]
    fn a_complete_run_is_ok_unless_it_passed_rows() {
        assert_eq!(
            sync_health_after_run(&complete(0), NOW),
            SyncHealth::Ok { at_ms: NOW }
        );
        assert_eq!(
            sync_health_after_run(&complete(2), NOW),
            attention(AttentionReason::RowsSkipped, 2),
            "nothing is skipped without being counted"
        );
    }

    #[test]
    fn a_rate_limit_or_no_network_waits_by_itself() {
        assert_eq!(
            sync_health_after_run(&partial(PartialReason::RateLimited, 9, Some(60_000)), NOW),
            SyncHealth::Waiting {
                until_ms: Some(NOW + 60_000),
                why: WaitReason::RateLimited
            }
        );
        assert_eq!(
            sync_health_after_run(&partial(PartialReason::Offline, 9, None), NOW),
            SyncHealth::Waiting {
                until_ms: None,
                why: WaitReason::Offline
            }
        );
        assert_eq!(
            after_failure(SyncFailure::Offline),
            SyncHealth::Waiting {
                until_ms: None,
                why: WaitReason::Offline
            }
        );
        assert_eq!(
            after_failure(SyncFailure::RateLimited {
                retry_after_ms: Some(1_000)
            }),
            SyncHealth::Waiting {
                until_ms: Some(NOW + 1_000),
                why: WaitReason::RateLimited
            }
        );
    }

    #[test]
    fn a_partial_run_with_rows_left_asks_to_continue() {
        for reason in [
            PartialReason::PageCap,
            PartialReason::RowsFailed,
            PartialReason::ListShifted,
        ] {
            assert_eq!(
                sync_health_after_run(&partial(reason, 7, None), NOW),
                attention(AttentionReason::RowsLeft, 7),
                "{reason:?}"
            );
        }
        assert_eq!(
            sync_health_after_run(&partial(PartialReason::ServerTrouble, 7, None), NOW),
            attention(AttentionReason::ServerTrouble, 7)
        );
        assert_eq!(
            sync_health_after_run(&partial(PartialReason::HistoryFull, 7, None), NOW),
            attention(AttentionReason::HistoryFull, 7)
        );
    }

    #[test]
    fn a_failed_run_names_what_it_needs() {
        assert_eq!(
            after_failure(SyncFailure::SessionEnded),
            attention(AttentionReason::Reconnect, 0)
        );
        assert_eq!(
            after_failure(SyncFailure::Unavailable { status: 503 }),
            attention(AttentionReason::ServerTrouble, 0)
        );
        assert_eq!(
            after_failure(SyncFailure::StorageFull),
            attention(AttentionReason::StorageFull, 0)
        );
        assert_eq!(
            after_failure(SyncFailure::Quota { limit: 30 }),
            attention(AttentionReason::Quota, 30)
        );
        assert_eq!(
            after_failure(SyncFailure::NeedsPremium),
            attention(AttentionReason::NeedsPremium, 0)
        );
        assert_eq!(
            after_failure(SyncFailure::NotOnAccount),
            attention(AttentionReason::NotOnAccount, 0)
        );
        let reason = AccountBlockReason::Unconfirmed;
        assert_eq!(
            after_failure(SyncFailure::AccountBlocked { reason }),
            attention(AttentionReason::AccountBlocked { reason }, 0)
        );
        for failure in [
            SyncFailure::Forbidden,
            SyncFailure::NotFound,
            SyncFailure::Rejected { status: 400 },
        ] {
            assert_eq!(
                after_failure(failure),
                attention(AttentionReason::Refused, 0)
            );
        }
    }

    #[test]
    fn a_blocked_run_says_what_stopped_it() {
        let why = BlockReason::RestoreLeftover;
        assert_eq!(
            sync_health_after_run(&RunOutcome::Blocked { why }, NOW),
            attention(AttentionReason::Blocked { why }, 0)
        );
    }

    #[test]
    fn a_huge_wait_saturates_rather_than_wraps() {
        assert_eq!(
            sync_health_after_run(&partial(PartialReason::RateLimited, 1, Some(u64::MAX)), NOW),
            SyncHealth::Waiting {
                until_ms: Some(i64::MAX),
                why: WaitReason::RateLimited
            }
        );
    }

    #[test]
    fn invalid_grant_ends_session_once() {
        let dead = attention(AttentionReason::Reconnect, 0);
        let ok = SyncHealth::Ok { at_ms: NOW };
        assert!(
            reconnect_notice_due(None, &dead),
            "the first run after an update"
        );
        assert!(reconnect_notice_due(Some(&ok), &dead));
        assert!(
            !reconnect_notice_due(Some(&dead), &dead),
            "one notice, not one a run"
        );
        assert!(!reconnect_notice_due(Some(&dead), &ok));
        assert!(!reconnect_notice_due(Some(&ok), &ok));
    }

    #[test]
    fn the_account_on_record_decides_what_a_sign_in_means() {
        assert_eq!(account_change_action(None, "u1"), AccountChange::First);
        assert_eq!(account_change_action(Some(""), "u1"), AccountChange::First);
        assert_eq!(account_change_action(Some("u1"), "u1"), AccountChange::Same);
        assert_eq!(
            account_change_action(Some("u1"), "u2"),
            AccountChange::Changed
        );
    }

    #[test]
    fn the_health_bridge_reads_and_writes_json() {
        let outcome = serde_json::to_string(&RunOutcome::Failed {
            failure: SyncFailure::SessionEnded,
        })
        .unwrap();
        let health = sync_health_after_run_json(&outcome, NOW).unwrap();
        assert_eq!(
            serde_json::from_str::<SyncHealth>(&health).unwrap(),
            attention(AttentionReason::Reconnect, 0)
        );
        assert!(sync_health_after_run_json("nope", NOW).is_err());
        assert!(reconnect_notice_due_json(None, &health));
        assert!(reconnect_notice_due_json(Some("nope"), &health));
        assert!(!reconnect_notice_due_json(Some(&health), &health));
        assert!(!reconnect_notice_due_json(None, "nope"));
    }

    /// The shells read these, so the shape is the contract.
    #[test]
    fn outcomes_and_health_keep_their_json_shape() {
        assert_eq!(
            serde_json::to_string(&complete(0)).unwrap(),
            r#"{"kind":"Complete","details":{"next_cursor_ms":1800000000000,"applied":3,"unchanged":1,"skipped_permanent":0}}"#
        );
        assert_eq!(
            serde_json::to_string(&RunOutcome::Failed {
                failure: SyncFailure::SessionEnded
            })
            .unwrap(),
            r#"{"kind":"Failed","details":{"failure":{"kind":"SessionEnded"}}}"#
        );
        assert_eq!(
            serde_json::to_string(&attention(
                AttentionReason::Blocked {
                    why: BlockReason::IdentityUnknown
                },
                0
            ))
            .unwrap(),
            r#"{"kind":"NeedsAttention","details":{"why":{"kind":"Blocked","details":{"why":"IdentityUnknown"}},"count":0}}"#
        );
        let health = SyncHealth::Waiting {
            until_ms: None,
            why: WaitReason::Offline,
        };
        assert_eq!(
            serde_json::from_str::<SyncHealth>(&serde_json::to_string(&health).unwrap()).unwrap(),
            health
        );
    }
}
