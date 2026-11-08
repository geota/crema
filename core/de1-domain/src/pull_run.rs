//! # pull-run
//!
//! The Visualizer pull, decided here and run by a small loop in each shell.
//! Rows stay in the shell; the core decides per list page ([`plan_page`]),
//! per failed row ([`row_failure_action`]), per list page's count
//! ([`after_list_page`]) and at the end ([`finish_run`]).
//!
//! - **A cursor moves only on a complete run**, on the server's clock:
//!   `max(since, highest updated_at seen - 60 s)`.
//! - **Rows apply as they go**, each with its version written last, so a run
//!   that stops keeps what it applied and the next one skips it.
//! - **A failing row can't freeze the cursor for ever**: three counted days
//!   make it poison, a counted skip retried daily. A failure counts only when
//!   another detail succeeded in the same run, so an outage poisons nothing.
//! - **Pacing** ([`pace`]): 30 requests of burst, then one every 4 s, for one
//!   device; Visualizer allows 50 a minute and 200 in ten.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::sync_failure::{RETRY_AFTER_CAP_MS, rate_limit_wait_ms};
use crate::sync_run::{PartialReason, RunOutcome};
use crate::{SyncFailure, shot_upload_key};

/// Overlap kept behind the newest row seen, for commit lag.
pub const CURSOR_OVERLAP_S: i64 = 60;
/// How often a list's shrinking may restart the walk.
pub const MAX_LIST_RESTARTS: u32 = 2;
/// Transient row failures in a row that end the run.
pub const FAILURE_STREAK: u32 = 3;
/// Rate-limit waits a run sits through before it ends.
pub const MAX_RATE_WAITS: u32 = 3;
/// Counted days that make a row poison.
pub const POISON_COUNT: u32 = 3;
const DAY_MS: i64 = 24 * 60 * 60 * 1000;
/// Pacing: one request every this long, with [`PACE_BURST`] of credit.
pub const PACE_INTERVAL_MS: i64 = 4_000;
/// Pacing burst.
pub const PACE_BURST: i64 = 30;

/// A `GET /shots` list row.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ListRow {
    /// Remote id.
    pub id: String,
    /// The shot's start, unix s: the upload key of a shot Crema sent.
    #[typeshare(serialized_as = "I64")]
    pub clock: i64,
    /// Unix s.
    #[typeshare(serialized_as = "I64")]
    pub updated_at: i64,
}

/// What this device holds for a remote id.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KnownRow {
    /// The remote id.
    pub remote_id: String,
    /// `visualizerUpdatedAt`: the version last applied, unix s.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub remote_version: Option<i64>,
    #[serde(default)]
    pub has_series: bool,
    #[serde(default)]
    pub is_brew: bool,
    /// Deleted here: the remote copy stays out.
    #[serde(default)]
    pub tombstoned: bool,
}

/// An unbound local row, or unbound tombstone, a remote row may be the upload of.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct KeyCandidate {
    pub local_id: String,
    #[typeshare(serialized_as = "I64")]
    pub completed_at_ms: i64,
    #[typeshare(serialized_as = "I64")]
    pub duration_ms: i64,
    /// A tombstone binds only by today's key: the bind can delete the remote row.
    #[serde(default)]
    pub tombstone: bool,
}

/// One list page to plan.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PageInput {
    pub rows: Vec<ListRow>,
    #[serde(default)]
    pub known: Vec<KnownRow>,
    #[serde(default)]
    pub candidates: Vec<KeyCandidate>,
    /// Rows the history can still add; `None` once the cap is view-only.
    #[serde(default)]
    pub free_slots: Option<u32>,
    /// A cursor from before row versions existed (unix s): bound rows at or
    /// below it are taken as applied, with no version written.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub legacy_cursor: Option<i64>,
}

/// Why a row is passed without a detail.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SkipReason {
    /// Deleted here.
    Tombstoned,
    /// The version applied is the remote's.
    UpToDate,
    /// At or below a legacy cursor.
    LegacyCursor,
    /// New, and the history has no room. It waits, holding the cursor.
    HistoryFull,
}

/// What to do with one list row.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum RowPlan {
    Skip {
        id: String,
        why: SkipReason,
    },
    /// Fetch the detail. [`choose_bind`] picks among `candidates` with its duration.
    FetchDetail {
        id: String,
        candidates: Vec<KeyCandidate>,
    },
}

/// A planned page.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PagePlan {
    pub rows: Vec<RowPlan>,
    /// The slots left after the rows this page will add.
    pub free_slots: Option<u32>,
}

/// Whether list row `clock` (unix s) can be the upload of `c`.
fn key_matches(c: &KeyCandidate, clock: i64) -> bool {
    clock == shot_upload_key(c.completed_at_ms, c.duration_ms)
        || (!c.tombstone && clock == c.completed_at_ms / 1000)
}

/// Plan a list page, in the design's order: a bound tombstone skips; a bound
/// non-brew row with no series fetches even at the same version, so a lost
/// curve heals; an equal version skips; a bound row at or below a legacy cursor
/// skips; an upload-key candidate fetches, carrying it; an unknown row with no
/// free slot skips, holding the cursor, with no detail spent; otherwise fetch.
#[must_use]
pub fn plan_page(input: &PageInput) -> PagePlan {
    let mut slots = input.free_slots;
    let rows = input
        .rows
        .iter()
        .map(|row| {
            let skip = |why| RowPlan::Skip {
                id: row.id.clone(),
                why,
            };
            if let Some(known) = input.known.iter().find(|k| k.remote_id == row.id) {
                if known.tombstoned {
                    return skip(SkipReason::Tombstoned);
                }
                if !known.is_brew && !known.has_series {
                    return RowPlan::FetchDetail {
                        id: row.id.clone(),
                        candidates: vec![],
                    };
                }
                if known.remote_version == Some(row.updated_at) {
                    return skip(SkipReason::UpToDate);
                }
                if known.remote_version.is_none()
                    && input.legacy_cursor.is_some_and(|c| row.updated_at <= c)
                {
                    return skip(SkipReason::LegacyCursor);
                }
                return RowPlan::FetchDetail {
                    id: row.id.clone(),
                    candidates: vec![],
                };
            }
            let candidates: Vec<KeyCandidate> = input
                .candidates
                .iter()
                .filter(|c| key_matches(c, row.clock))
                .cloned()
                .collect();
            if !candidates.is_empty() {
                return RowPlan::FetchDetail {
                    id: row.id.clone(),
                    candidates,
                };
            }
            match slots {
                Some(0) => skip(SkipReason::HistoryFull),
                Some(n) => {
                    slots = Some(n - 1);
                    RowPlan::FetchDetail {
                        id: row.id.clone(),
                        candidates: vec![],
                    }
                }
                None => RowPlan::FetchDetail {
                    id: row.id.clone(),
                    candidates: vec![],
                },
            }
        })
        .collect();
    PagePlan {
        rows,
        free_slots: slots,
    }
}

/// The candidate a fetched row binds to: its duration within the slack, still
/// unbound when the shell applies it. `None` falls through to an add.
#[must_use]
pub fn choose_bind(candidates: &[KeyCandidate], detail_duration_ms: i64) -> Option<KeyCandidate> {
    candidates
        .iter()
        .find(|c| {
            (c.duration_ms - detail_duration_ms).abs()
                <= crate::visualizer_sync::UPLOAD_KEY_DURATION_SLACK_MS
        })
        .cloned()
}

/// Why a detail failed.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum RowFailure {
    /// A 2xx that isn't JSON or doesn't parse: a captive portal looks like this.
    NotJson,
    /// Valid JSON that can't be mapped onto a shot.
    Unmappable,
    /// What the classification said about any other reply.
    Call { failure: SyncFailure },
}

/// What to do about a failed detail.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum RowAction {
    /// Try the same request again now.
    RetryNow,
    /// Sit the rate limit out, then retry the same request. The run stays Waiting.
    Wait {
        #[typeshare(serialized_as = "I64")]
        wait_ms: u64,
    },
    /// Pass the row, counted. `holds`: the cursor can't move past it.
    /// `transient`: it counts towards the failure streak. `poison_candidate`:
    /// repeated, it may become poison.
    Skip {
        holds: bool,
        transient: bool,
        poison_candidate: bool,
    },
    /// Stop the walk.
    EndRun,
}

/// The rule for a failed detail, its `attempt` (0-based) made: 404 is a counted
/// skip; a 5xx or 408 retries once in place, then skips, holding; non-JSON is
/// transient and holds; mappable-but-wrong JSON is a poison candidate; a 429
/// waits (60 s, then ten minutes), three times at most; no reply or an ended
/// session ends the run. `jitter_unit` is the shell's random number in `0..1`.
#[must_use]
pub fn row_failure_action(
    failure: RowFailure,
    attempt: u32,
    retry_after_ms: Option<u64>,
    jitter_unit: f64,
) -> RowAction {
    let skip = |holds, transient, poison_candidate| RowAction::Skip {
        holds,
        transient,
        poison_candidate,
    };
    match failure {
        RowFailure::NotJson => skip(true, true, false),
        RowFailure::Unmappable => skip(true, false, true),
        RowFailure::Call { failure } => match failure {
            SyncFailure::NotFound => skip(false, false, false),
            SyncFailure::Unavailable { .. } if attempt == 0 => RowAction::RetryNow,
            SyncFailure::Unavailable { .. } => skip(true, true, true),
            SyncFailure::RateLimited {
                retry_after_ms: header,
            } if attempt < MAX_RATE_WAITS => RowAction::Wait {
                wait_ms: rate_limit_wait_ms(header.or(retry_after_ms), attempt, jitter_unit),
            },
            SyncFailure::RateLimited { .. }
            | SyncFailure::Offline
            | SyncFailure::SessionEnded
            | SyncFailure::AccessRefused
            | SyncFailure::AccountBlocked { .. } => RowAction::EndRun,
            _ => skip(false, false, false),
        },
    }
}

/// A row that failed this run and may hold the cursor.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FailedRow {
    pub id: String,
    #[typeshare(serialized_as = "I64")]
    pub clock: i64,
    pub holds: bool,
    pub poison_candidate: bool,
}

/// What a run has done so far.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PullTally {
    /// Where the run started, unix ms (0 = from the start).
    #[typeshare(serialized_as = "I64")]
    pub since_ms: i64,
    /// The newest `updated_at` seen, unix s.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub highest_seen: Option<i64>,
    #[serde(default)]
    pub applied: u32,
    #[serde(default)]
    pub unchanged: u32,
    #[serde(default)]
    pub skipped_permanent: u32,
    /// New rows the history had no room for.
    #[serde(default)]
    pub history_full: u32,
    /// Rows edited during their fetch that had no series, so their backfill waits.
    #[serde(default)]
    pub edited_without_series: u32,
    #[serde(default)]
    pub failed: Vec<FailedRow>,
    /// Transient failures in a row.
    #[serde(default)]
    pub streak: u32,
    /// A detail succeeded: the condition for a failure to count towards poison.
    #[serde(default)]
    pub detail_succeeded: bool,
    /// Rows looked at.
    #[serde(default)]
    pub walked: u32,
    #[serde(default)]
    pub last_count: Option<u32>,
    #[serde(default)]
    pub restarts: u32,
    /// Why the walk stopped early.
    #[serde(default)]
    pub ended: Option<RunEnd>,
}

/// Why a walk stopped early.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum RunEnd {
    Partial { reason: PartialReason },
    Failed { failure: SyncFailure },
}

/// How a row went.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum RowOutcome {
    /// The merge changed it.
    Applied,
    /// Nothing to change, or a skip that needs no detail.
    Unchanged,
    /// The plan passed it for want of a slot.
    HistoryFull,
    /// It changed here while the detail was fetched: no version written.
    EditedDuringFetch { has_series: bool },
    /// The detail failed, and [`row_failure_action`] said to pass it.
    Failed {
        holds: bool,
        transient: bool,
        poison_candidate: bool,
    },
}

impl PullTally {
    /// A run starting at cursor `since_ms`.
    #[must_use]
    pub fn starting(since_ms: i64) -> Self {
        Self {
            since_ms,
            ..Self::default()
        }
    }
}

/// Note list row `row` with how it went. A transient failure counts towards
/// the streak and three end the run as `ServerTrouble`; anything else resets it.
/// A row seen again after a restart replaces what it did before, so it holds
/// once at most, and not at all once it went through.
#[must_use]
pub fn record_row(tally: &PullTally, row: &ListRow, outcome: RowOutcome) -> PullTally {
    let mut t = tally.clone();
    t.failed.retain(|f| f.id != row.id);
    t.walked += 1;
    t.highest_seen = Some(
        t.highest_seen
            .map_or(row.updated_at, |h| h.max(row.updated_at)),
    );
    match outcome {
        RowOutcome::Applied => {
            t.applied += 1;
            t.detail_succeeded = true;
            t.streak = 0;
        }
        RowOutcome::Unchanged => {
            t.unchanged += 1;
            t.streak = 0;
        }
        RowOutcome::HistoryFull => t.history_full += 1,
        RowOutcome::EditedDuringFetch { has_series } => {
            t.detail_succeeded = true;
            t.streak = 0;
            if !has_series {
                t.edited_without_series += 1;
            }
        }
        RowOutcome::Failed {
            holds,
            transient,
            poison_candidate,
        } => {
            if holds || poison_candidate {
                t.failed.push(FailedRow {
                    id: row.id.clone(),
                    clock: row.clock,
                    holds,
                    poison_candidate,
                });
            } else {
                t.skipped_permanent += 1;
            }
            if transient {
                t.streak += 1;
                if t.streak >= FAILURE_STREAK && t.ended.is_none() {
                    t.ended = Some(RunEnd::Partial {
                        reason: PartialReason::ServerTrouble,
                    });
                }
            } else {
                t.streak = 0;
            }
        }
    }
    t
}

/// Note a detail the run had to give up on with the reply `failure`
/// ([`RowAction::EndRun`]), or a list page it couldn't get: no reply ends it
/// `Partial(Offline)` and server trouble `Partial(ServerTrouble)`, or `Failed`
/// if nothing was walked yet; a lasting rate limit `Partial(RateLimited)`;
/// anything else `Failed`.
#[must_use]
pub fn end_run(tally: &PullTally, failure: SyncFailure) -> PullTally {
    let mut t = tally.clone();
    t.ended = Some(match failure {
        SyncFailure::Offline if t.walked > 0 => RunEnd::Partial {
            reason: PartialReason::Offline,
        },
        SyncFailure::Unavailable { .. } if t.walked > 0 => RunEnd::Partial {
            reason: PartialReason::ServerTrouble,
        },
        SyncFailure::RateLimited { .. } => RunEnd::Partial {
            reason: PartialReason::RateLimited,
        },
        failure => RunEnd::Failed { failure },
    });
    t
}

/// What to do after a list page.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ListStep {
    Continue,
    /// Start again from page 1: applied rows skip, so it costs list requests only.
    Restart,
    /// It kept shrinking: end `Partial(ListShifted)`.
    Stop,
}

/// Note a list page's `count`, with `own_deletes` this device sent since the
/// last page. A skip needs a net upward shift, so every skip shows as a fall in
/// the count; a fall restarts the walk, twice at most. A fall our own deletes
/// explain restarts too, but doesn't count against the two.
#[must_use]
pub fn after_list_page(tally: &PullTally, count: u32, own_deletes: u32) -> (PullTally, ListStep) {
    let mut t = tally.clone();
    let previous = t.last_count.replace(count);
    let Some(previous) = previous.filter(|p| count < *p) else {
        return (t, ListStep::Continue);
    };
    if previous - count <= own_deletes {
        return (t, ListStep::Restart);
    }
    t.restarts += 1;
    if t.restarts > MAX_LIST_RESTARTS {
        t.ended = Some(RunEnd::Partial {
            reason: PartialReason::ListShifted,
        });
        return (t, ListStep::Stop);
    }
    (t, ListStep::Restart)
}

/// A row that keeps failing.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PoisonEntry {
    pub id: String,
    pub count: u32,
    #[typeshare(serialized_as = "I64")]
    pub last_counted_at_ms: i64,
    #[typeshare(serialized_as = "I64")]
    pub clock: i64,
    /// When a poison row is next tried, alone.
    #[typeshare(serialized_as = "I64")]
    pub retry_at_ms: i64,
}

/// The `rowFailures` after a run. A failure counts only if another detail
/// succeeded in the run, and at most once a day, so restarts, repeated taps
/// and outages don't add up. A row that succeeded leaves the list.
#[must_use]
pub fn settle_poison(
    entries: &[PoisonEntry],
    tally: &PullTally,
    succeeded: &[String],
    now_ms: i64,
) -> Vec<PoisonEntry> {
    let mut out: Vec<PoisonEntry> = entries
        .iter()
        .filter(|e| !succeeded.contains(&e.id))
        .cloned()
        .collect();
    if !tally.detail_succeeded {
        return out;
    }
    for row in tally.failed.iter().filter(|r| r.poison_candidate) {
        match out.iter_mut().find(|e| e.id == row.id) {
            Some(e) if now_ms - e.last_counted_at_ms >= DAY_MS => {
                e.count += 1;
                e.last_counted_at_ms = now_ms;
                e.retry_at_ms = now_ms + DAY_MS;
            }
            Some(_) => {}
            None => out.push(PoisonEntry {
                id: row.id.clone(),
                count: 1,
                last_counted_at_ms: now_ms,
                clock: row.clock,
                retry_at_ms: now_ms + DAY_MS,
            }),
        }
    }
    out
}

/// Whether the run should fetch one known-good row's detail before it ends: a
/// row failed in a way that may be poison, and nothing else succeeded, so there
/// is no evidence yet that the server works. On a quiet device the failing row
/// may be the only one changed, and without this it would hold the cursor for
/// good. One request, made only in that case.
#[must_use]
pub fn health_probe_due(tally: &PullTally) -> bool {
    tally.ended.is_none()
        && !tally.detail_succeeded
        && tally.failed.iter().any(|r| r.poison_candidate)
}

/// The known-good detail fetched for [`health_probe_due`] answered: the server
/// works, so this run's failures count towards poison. A failed probe changes
/// nothing, and nothing counts.
#[must_use]
pub fn record_health_probe(tally: &PullTally, succeeded: bool) -> PullTally {
    let mut t = tally.clone();
    t.detail_succeeded |= succeeded;
    t
}

/// Poison rows due their daily try, one request each, at run start: the
/// cursor may have passed them.
#[must_use]
pub fn poison_due(entries: &[PoisonEntry], now_ms: i64) -> Vec<String> {
    entries
        .iter()
        .filter(|e| e.count >= POISON_COUNT && e.retry_at_ms <= now_ms)
        .map(|e| e.id.clone())
        .collect()
}

/// How the run ended. Only `Complete` carries a next cursor, and it is the
/// server's clock: `max(since, newest seen - 60 s)`; an empty walk keeps
/// `since`. A row holding the cursor makes it `Partial`, unless it is poison
/// in `poison`, which is a counted skip. Rows the history refused end it
/// `HistoryFull`, decided here, at the walk's end.
#[must_use]
pub fn finish_run(
    tally: &PullTally,
    poison: &[PoisonEntry],
    resume_after_ms: Option<u64>,
) -> RunOutcome {
    let is_poison = |id: &str| poison.iter().any(|e| e.id == id && e.count >= POISON_COUNT);
    let holding = tally.failed.iter().filter(|r| !is_poison(&r.id)).count();
    let poisoned = tally.failed.len() - holding;
    let skipped_permanent = tally.skipped_permanent + u32::try_from(poisoned).unwrap_or(u32::MAX);
    let left = u32::try_from(holding).unwrap_or(u32::MAX)
        + tally.history_full
        + tally.edited_without_series;
    let partial = |reason| RunOutcome::Partial {
        reason,
        applied: tally.applied,
        left,
        skipped_permanent,
        resume_after_ms,
    };
    match tally.ended {
        Some(RunEnd::Failed { failure }) => RunOutcome::Failed { failure },
        Some(RunEnd::Partial { reason }) => partial(reason),
        None if holding > 0 || tally.edited_without_series > 0 => {
            partial(PartialReason::RowsFailed)
        }
        None if tally.history_full > 0 => partial(PartialReason::HistoryFull),
        None => RunOutcome::Complete {
            next_cursor_ms: tally.highest_seen.map_or(tally.since_ms, |h| {
                tally.since_ms.max((h - CURSOR_OVERLAP_S) * 1000)
            }),
            applied: tally.applied,
            unchanged: tally.unchanged,
            skipped_permanent,
        },
    }
}

/// The request pacer: a GCRA bucket of [`PACE_BURST`] with one token every
/// [`PACE_INTERVAL_MS`], plus a rate-limit cooldown. Persist it every tenth
/// request and when a run stops.
#[typeshare]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PaceBucket {
    /// The theoretical arrival time, unix ms.
    #[typeshare(serialized_as = "I64")]
    pub tat_ms: i64,
    /// The latest clock reading seen: a clock going back counts as no time.
    #[typeshare(serialized_as = "I64")]
    pub last_now_ms: i64,
    /// No request before this, after a 429.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub cooldown_until_ms: Option<i64>,
}

/// Take one request's slot: the bucket after it, and how long to wait first.
#[must_use]
pub fn pace(bucket: PaceBucket, now_ms: i64) -> (PaceBucket, u64) {
    let now = now_ms.max(bucket.last_now_ms);
    let tolerance = (PACE_BURST - 1) * PACE_INTERVAL_MS;
    // A cooldown or a schedule further out than any wait could be is a clock
    // that moved, not a wait: clamp it.
    let cooldown = bucket
        .cooldown_until_ms
        .map(|c| c.min(now + i64::try_from(RETRY_AFTER_CAP_MS).unwrap_or(i64::MAX)));
    let start = cooldown.map_or(now, |c| c.max(now));
    let tat = bucket
        .tat_ms
        .min(start + tolerance + PACE_INTERVAL_MS)
        .max(start);
    let at = start.max(tat - tolerance);
    let next = PaceBucket {
        tat_ms: tat + PACE_INTERVAL_MS,
        last_now_ms: now,
        cooldown_until_ms: cooldown.filter(|c| *c > at),
    };
    (next, u64::try_from(at - now).unwrap_or(0))
}

/// A 429 arrived: nothing goes before `now + wait_ms`, a relaunch included.
#[must_use]
pub fn note_rate_limit(bucket: PaceBucket, now_ms: i64, wait_ms: u64) -> PaceBucket {
    let until =
        now_ms.saturating_add(i64::try_from(wait_ms.min(RETRY_AFTER_CAP_MS)).unwrap_or(i64::MAX));
    PaceBucket {
        cooldown_until_ms: Some(bucket.cooldown_until_ms.map_or(until, |c| c.max(until))),
        ..bucket
    }
}

#[cfg(test)]
pub(crate) mod tests_support {
    /// A small seeded generator: the properties below run over many seeds
    /// without a property-testing crate (which `cargo deny` bans here).
    pub(crate) struct Rng(u64);

    impl Rng {
        pub(crate) fn new(seed: u64) -> Self {
            Self(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1)
        }

        pub(crate) fn next(&mut self) -> u64 {
            let mut x = self.0;
            x ^= x << 13;
            x ^= x >> 7;
            x ^= x << 17;
            self.0 = x;
            x
        }

        /// A number in `0..n`.
        pub(crate) fn below(&mut self, n: u64) -> u64 {
            if n == 0 { 0 } else { self.next() % n }
        }

        /// An index into a list of `len`.
        pub(crate) fn index(&mut self, len: usize) -> usize {
            usize::try_from(self.below(u64::try_from(len).unwrap_or(u64::MAX))).unwrap_or(0)
        }
    }
}

#[cfg(test)]
mod tests;
