use std::collections::HashMap;

use super::tests_support::Rng;
use super::*;

const NOW: i64 = 1_800_000_000_000;

fn row(id: &str, clock: i64, updated_at: i64) -> ListRow {
    ListRow {
        id: id.to_owned(),
        clock,
        updated_at,
    }
}

fn known(id: &str, version: Option<i64>) -> KnownRow {
    KnownRow {
        remote_id: id.to_owned(),
        remote_version: version,
        has_series: true,
        ..KnownRow::default()
    }
}

fn kinds(plan: &PagePlan) -> Vec<String> {
    plan.rows
        .iter()
        .map(|r| match r {
            RowPlan::Skip { why, .. } => format!("skip {why:?}"),
            RowPlan::FetchDetail { candidates, .. } if candidates.is_empty() => "fetch".to_owned(),
            RowPlan::FetchDetail { candidates, .. } => format!("fetch {}", candidates[0].local_id),
        })
        .collect()
}

// ── plan_page ─────────────────────────────────────────────────────────────

#[test]
fn a_page_is_planned_in_the_designs_order() {
    let input = PageInput {
        rows: vec![
            row("gone", 1, 10),
            row("bare", 1, 10),
            row("same", 1, 10),
            row("old", 1, 10),
            row("new", 1, 10),
        ],
        known: vec![
            KnownRow {
                tombstoned: true,
                has_series: false,
                ..known("gone", Some(10))
            },
            KnownRow {
                has_series: false,
                ..known("bare", Some(10))
            },
            known("same", Some(10)),
            known("old", None),
        ],
        legacy_cursor: Some(10),
        ..PageInput::default()
    };
    assert_eq!(
        kinds(&plan_page(&input)),
        [
            "skip Tombstoned",
            "fetch",
            "skip UpToDate",
            "skip LegacyCursor",
            "fetch"
        ]
    );
}

#[test]
fn lost_curve_heals_at_same_version() {
    let input = PageInput {
        rows: vec![row("a", 1, 10)],
        known: vec![KnownRow {
            has_series: false,
            ..known("a", Some(10))
        }],
        ..PageInput::default()
    };
    assert_eq!(kinds(&plan_page(&input)), ["fetch"]);
    let brew = PageInput {
        known: vec![KnownRow {
            has_series: false,
            is_brew: true,
            ..known("a", Some(10))
        }],
        ..input
    };
    assert_eq!(
        kinds(&plan_page(&brew)),
        ["skip UpToDate"],
        "a brew log has no curve to heal"
    );
}

#[test]
fn cap_refusal_skips_and_continues() {
    let input = PageInput {
        rows: vec![row("n1", 1, 30), row("known", 1, 20), row("n2", 1, 10)],
        known: vec![known("known", Some(5))],
        free_slots: Some(1),
        ..PageInput::default()
    };
    let plan = plan_page(&input);
    assert_eq!(
        kinds(&plan),
        ["fetch", "fetch", "skip HistoryFull"],
        "updates still apply past a refusal"
    );
    assert_eq!(plan.free_slots, Some(0));
}

#[test]
fn an_upload_key_candidate_is_fetched_with_its_candidates_and_needs_no_slot() {
    let shot = KeyCandidate {
        local_id: "l1".to_owned(),
        completed_at_ms: 1_700_000_030_000,
        duration_ms: 30_000,
        tombstone: false,
    };
    let input = PageInput {
        rows: vec![
            row("r1", 1_700_000_000, 50),
            row("legacy", 1_700_000_030, 50),
        ],
        candidates: vec![
            shot.clone(),
            KeyCandidate {
                tombstone: true,
                ..shot.clone()
            },
        ],
        free_slots: Some(0),
        ..PageInput::default()
    };
    assert_eq!(kinds(&plan_page(&input)), ["fetch l1", "fetch l1"]);
    let RowPlan::FetchDetail { candidates, .. } = &plan_page(&input).rows[1] else {
        panic!()
    };
    assert_eq!(
        candidates.len(),
        1,
        "the pre-#44 form doesn't bind a tombstone"
    );
}

#[test]
fn same_second_other_shot_not_bound() {
    let shot = KeyCandidate {
        local_id: "l1".to_owned(),
        completed_at_ms: 0,
        duration_ms: 30_000,
        tombstone: false,
    };
    assert_eq!(
        choose_bind(std::slice::from_ref(&shot), 31_400),
        Some(shot.clone())
    );
    assert_eq!(
        choose_bind(std::slice::from_ref(&shot), 31_600),
        None,
        "falls through to an add"
    );
}

// ── row failures and the streak ─────────────────────────────────────────

fn call(failure: SyncFailure) -> RowFailure {
    RowFailure::Call { failure }
}

#[test]
fn a_failed_detail_is_handled_by_the_table() {
    let skip = |holds, transient, poison_candidate| RowAction::Skip {
        holds,
        transient,
        poison_candidate,
    };
    assert_eq!(
        row_failure_action(call(SyncFailure::NotFound), 0, None, 0.0),
        skip(false, false, false)
    );
    assert_eq!(
        row_failure_action(RowFailure::NotJson, 0, None, 0.0),
        skip(true, true, false)
    );
    assert_eq!(
        row_failure_action(RowFailure::Unmappable, 0, None, 0.0),
        skip(true, false, true)
    );
    let busy = call(SyncFailure::Unavailable { status: 503 });
    assert_eq!(row_failure_action(busy, 0, None, 0.0), RowAction::RetryNow);
    assert_eq!(
        row_failure_action(busy, 1, None, 0.0),
        skip(true, true, true)
    );
    let limited = call(SyncFailure::RateLimited {
        retry_after_ms: None,
    });
    assert_eq!(
        row_failure_action(limited, 0, None, 0.0),
        RowAction::Wait { wait_ms: 60_000 }
    );
    assert_eq!(
        row_failure_action(limited, 1, None, 0.0),
        RowAction::Wait { wait_ms: 600_000 }
    );
    assert_eq!(
        row_failure_action(limited, 3, None, 0.0),
        RowAction::EndRun,
        "three waits at most"
    );
    for f in [SyncFailure::Offline, SyncFailure::SessionEnded] {
        assert_eq!(row_failure_action(call(f), 0, None, 0.0), RowAction::EndRun);
    }
}

fn failed(holds: bool, transient: bool, poison: bool) -> RowOutcome {
    RowOutcome::Failed {
        holds,
        transient,
        poison_candidate: poison,
    }
}

#[test]
fn captive_portal_holds_cursor() {
    let mut t = PullTally::starting(0);
    t = record_row(&t, &row("ok", 1, 10), RowOutcome::Applied);
    for id in ["p1", "p2", "p3"] {
        t = record_row(&t, &row(id, 1, 20), failed(true, true, false));
    }
    let RunOutcome::Partial { reason, left, .. } = finish_run(&t, &[], None) else {
        panic!("{t:?}")
    };
    assert_eq!(reason, PartialReason::ServerTrouble);
    assert_eq!(left, 3, "no skip passed the cursor");
}

#[test]
fn a_success_resets_the_streak() {
    let mut t = PullTally::starting(0);
    for outcome in [
        failed(true, true, true),
        failed(true, true, true),
        RowOutcome::Applied,
        failed(true, true, true),
    ] {
        t = record_row(&t, &row("x", 1, 10), outcome);
    }
    assert_eq!(t.ended, None);
}

#[test]
fn edited_row_without_series_holds_cursor() {
    let t = record_row(
        &PullTally::starting(0),
        &row("a", 1, 10),
        RowOutcome::EditedDuringFetch { has_series: false },
    );
    assert!(matches!(
        finish_run(&t, &[], None),
        RunOutcome::Partial {
            reason: PartialReason::RowsFailed,
            ..
        }
    ));
    let t = record_row(
        &PullTally::starting(0),
        &row("a", 1, 10),
        RowOutcome::EditedDuringFetch { has_series: true },
    );
    assert!(
        matches!(finish_run(&t, &[], None), RunOutcome::Complete { .. }),
        "the local copy is newer"
    );
}

#[test]
fn no_profile_shot_is_not_a_failure() {
    // A recipe the detail doesn't offer is never fetched; the row applies name-only.
    let t = record_row(
        &PullTally::starting(0),
        &row("a", 1, 100),
        RowOutcome::Applied,
    );
    assert!(matches!(
        finish_run(&t, &[], None),
        RunOutcome::Complete { applied: 1, .. }
    ));
}

#[test]
fn the_cursor_is_the_servers_newest_less_a_minute_and_never_goes_back() {
    let mut t = PullTally::starting(10_000_000);
    t = record_row(&t, &row("a", 1, 50_000), RowOutcome::Applied);
    let RunOutcome::Complete { next_cursor_ms, .. } = finish_run(&t, &[], None) else {
        panic!()
    };
    assert_eq!(next_cursor_ms, (50_000 - 60) * 1000);
    let t = record_row(
        &PullTally::starting(100_000_000),
        &row("a", 1, 50),
        RowOutcome::Unchanged,
    );
    let RunOutcome::Complete { next_cursor_ms, .. } = finish_run(&t, &[], None) else {
        panic!()
    };
    assert_eq!(next_cursor_ms, 100_000_000);
    let RunOutcome::Complete { next_cursor_ms, .. } =
        finish_run(&PullTally::starting(7), &[], None)
    else {
        panic!()
    };
    assert_eq!(next_cursor_ms, 7, "an empty walk keeps since");
}

#[test]
fn no_reply_ends_partial_or_failed_if_nothing_was_walked() {
    assert!(matches!(
        finish_run(
            &end_run(&PullTally::starting(0), SyncFailure::Offline),
            &[],
            None
        ),
        RunOutcome::Failed {
            failure: SyncFailure::Offline
        }
    ));
    let walked = record_row(
        &PullTally::starting(0),
        &row("a", 1, 1),
        RowOutcome::Applied,
    );
    assert!(matches!(
        finish_run(&end_run(&walked, SyncFailure::Offline), &[], None),
        RunOutcome::Partial {
            reason: PartialReason::Offline,
            applied: 1,
            ..
        }
    ));
    assert!(matches!(
        finish_run(&end_run(&walked, SyncFailure::SessionEnded), &[], None),
        RunOutcome::Failed {
            failure: SyncFailure::SessionEnded
        }
    ));
}

#[test]
fn history_full_is_decided_at_the_walks_end() {
    let mut t = PullTally::starting(0);
    t = record_row(&t, &row("full", 1, 20), RowOutcome::HistoryFull);
    t = record_row(&t, &row("later", 1, 10), RowOutcome::Applied);
    assert!(matches!(
        finish_run(&t, &[], None),
        RunOutcome::Partial {
            reason: PartialReason::HistoryFull,
            applied: 1,
            left: 1,
            ..
        }
    ));
}

// ── list shifts ──────────────────────────────────────────────────────────

#[test]
fn delete_mid_walk_rewalks() {
    let (t, step) = after_list_page(&PullTally::starting(0), 100, 0);
    assert_eq!(step, ListStep::Continue);
    let (t, step) = after_list_page(&t, 99, 0);
    assert_eq!(step, ListStep::Restart, "count_fell");
    let (t, _) = after_list_page(&t, 98, 0);
    let (t, step) = after_list_page(&t, 97, 0);
    assert_eq!(step, ListStep::Stop);
    assert!(matches!(
        finish_run(&t, &[], None),
        RunOutcome::Partial {
            reason: PartialReason::ListShifted,
            ..
        }
    ));
}

#[test]
fn own_deletes_restart_uncounted() {
    let mut t = after_list_page(&PullTally::starting(0), 100, 0).0;
    for count in [99, 98, 97] {
        let (next, step) = after_list_page(&t, count, 1);
        assert_eq!(step, ListStep::Restart);
        t = next;
    }
    assert_eq!(t.restarts, 0);
    assert_eq!(t.ended, None);
}

// ── poison ───────────────────────────────────────────────────────────────

fn poison_run(entries: &[PoisonEntry], another_succeeded: bool, now: i64) -> Vec<PoisonEntry> {
    let mut t = PullTally::starting(0);
    if another_succeeded {
        t = record_row(&t, &row("good", 1, 1), RowOutcome::Applied);
    }
    t = record_row(&t, &row("bad", 5, 1), failed(true, true, true));
    settle_poison(entries, &t, &[], now)
}

#[test]
fn poison_row_stops_holding_cursor() {
    let mut entries = vec![];
    for day in 0..3 {
        entries = poison_run(&entries, true, NOW + day * DAY_MS);
    }
    assert_eq!(entries[0].count, POISON_COUNT);
    let mut t = record_row(
        &PullTally::starting(0),
        &row("good", 1, 100),
        RowOutcome::Applied,
    );
    t = record_row(&t, &row("bad", 5, 90), failed(true, true, true));
    assert!(matches!(
        finish_run(&t, &entries, None),
        RunOutcome::Complete {
            skipped_permanent: 1,
            ..
        }
    ));
}

#[test]
fn a_failure_counts_once_a_day() {
    let mut entries = vec![];
    for run in 0..5 {
        entries = poison_run(&entries, true, NOW + run * 60_000);
    }
    assert_eq!(entries[0].count, 1);
}

#[test]
fn outage_poisons_nothing() {
    let mut entries = vec![];
    for day in 0..10 {
        entries = poison_run(&entries, false, NOW + day * DAY_MS);
    }
    assert!(entries.is_empty());
}

#[test]
fn a_row_seen_again_after_a_restart_holds_once() {
    let bad = row("bad", 5, 90);
    let mut t = record_row(&PullTally::starting(0), &bad, failed(true, true, false));
    t = record_row(&t, &bad, failed(true, true, false));
    assert_eq!(t.failed.len(), 1, "the rewalk replaced it");
    assert!(matches!(
        finish_run(&t, &[], None),
        RunOutcome::Partial { left: 1, .. }
    ));
    t = record_row(&t, &bad, RowOutcome::Applied);
    assert!(t.failed.is_empty(), "it went through on the rewalk");
    assert!(matches!(
        finish_run(&t, &[], None),
        RunOutcome::Complete { .. }
    ));
}

/// The only changed row fails: with no other detail, a known-good probe is
/// the evidence that the server works.
#[test]
fn a_quiet_device_poisons_with_a_probe() {
    let alone = |probe_ok: Option<bool>| {
        let t = record_row(
            &PullTally::starting(0),
            &row("bad", 5, 90),
            failed(true, true, true),
        );
        assert!(health_probe_due(&t));
        probe_ok.map_or(t.clone(), |ok| record_health_probe(&t, ok))
    };
    let mut entries = vec![];
    for day in 0..3 {
        entries = settle_poison(&entries, &alone(None), &[], NOW + day * DAY_MS);
    }
    assert!(entries.is_empty(), "no probe, nothing counts");
    for day in 0..3 {
        entries = settle_poison(&entries, &alone(Some(false)), &[], NOW + day * DAY_MS);
    }
    assert!(entries.is_empty(), "a failed probe is an outage");
    for day in 0..3 {
        entries = settle_poison(&entries, &alone(Some(true)), &[], NOW + day * DAY_MS);
    }
    assert_eq!(entries[0].count, POISON_COUNT);
    assert!(matches!(
        finish_run(&alone(Some(true)), &entries, None),
        RunOutcome::Complete {
            skipped_permanent: 1,
            ..
        }
    ));
    let good = record_row(
        &PullTally::starting(0),
        &row("good", 1, 100),
        RowOutcome::Applied,
    );
    assert!(!health_probe_due(&good), "nothing failed");
    assert!(
        !health_probe_due(&record_row(
            &good,
            &row("bad", 5, 90),
            failed(true, true, true)
        )),
        "another detail went through"
    );
}

#[test]
fn poison_retries_daily() {
    let mut entries = vec![];
    for day in 0..3 {
        entries = poison_run(&entries, true, NOW + day * DAY_MS);
    }
    let last = NOW + 2 * DAY_MS;
    assert!(poison_due(&entries, last).is_empty());
    assert_eq!(poison_due(&entries, last + DAY_MS), ["bad"]);
    let t = PullTally::starting(0);
    assert!(
        settle_poison(&entries, &t, &["bad".to_owned()], last + DAY_MS).is_empty(),
        "a success clears it"
    );
}

// ── pacing ──────────────────────────────────────────────────────────────

/// Take as many slots as the pacer allows and count them per window.
fn burst(mut bucket: PaceBucket, start: i64, until: i64) -> Vec<i64> {
    let mut now = start;
    let mut sent = vec![];
    while now < until {
        let (next, wait) = pace(bucket, now);
        bucket = next;
        now += i64::try_from(wait).unwrap();
        if now < until {
            sent.push(now);
        }
    }
    sent
}

fn most_in(sent: &[i64], window: i64) -> usize {
    sent.iter()
        .map(|t| {
            sent.iter()
                .filter(|s| **s >= *t && **s < t + window)
                .count()
        })
        .max()
        .unwrap_or(0)
}

#[test]
fn pace_stays_within_visualizers_limits() {
    let sent = burst(PaceBucket::default(), NOW, NOW + 3_600_000);
    assert!(most_in(&sent, 60_000) <= 45, "{}", most_in(&sent, 60_000));
    assert!(
        most_in(&sent, 600_000) <= 180,
        "{}",
        most_in(&sent, 600_000)
    );
    assert_eq!(
        most_in(&sent, 60_000),
        44,
        "a full burst, then one each 4 s, as the design's simulation saw"
    );
}

#[test]
fn relaunch_keeps_pace() {
    let mut bucket = PaceBucket::default();
    for _ in 0..30 {
        bucket = pace(bucket, NOW).0;
    }
    // Persisted and read back by a relaunch a second later: no fresh burst.
    let (_, wait) = pace(bucket, NOW + 1_000);
    assert!(wait > 0);
}

#[test]
fn a_clock_going_back_counts_as_no_time() {
    let mut bucket = PaceBucket::default();
    for _ in 0..30 {
        bucket = pace(bucket, NOW).0;
    }
    let (back, wait) = pace(bucket, NOW - 3_600_000);
    assert!(wait >= u64::try_from(PACE_INTERVAL_MS).unwrap() - 1);
    assert_eq!(back.last_now_ms, NOW);
    // Back an hour and forward again must not hand the burst back.
    let (_, wait) = pace(back, NOW);
    assert!(wait >= u64::try_from(PACE_INTERVAL_MS).unwrap());
}

#[test]
fn a_cooldown_holds_and_is_clamped() {
    let cooled = note_rate_limit(PaceBucket::default(), NOW, 600_000);
    assert_eq!(pace(cooled, NOW).1, 600_000);
    let far = PaceBucket {
        cooldown_until_ms: Some(NOW + 30 * DAY_MS),
        ..PaceBucket::default()
    };
    assert_eq!(pace(far, NOW).1, RETRY_AFTER_CAP_MS);
}

// ── A fake Visualizer, and runs against it ───────────────────────────────

/// Visualizer's fixed windows from the first counted request; a rejected
/// request counts and doesn't extend the window.
#[derive(Clone)]
struct Window {
    start: i64,
    count: u32,
    limit: u32,
    len: i64,
}

impl Window {
    fn admit(&mut self, now: i64) -> bool {
        if self.count == 0 || now >= self.start + self.len {
            self.start = now;
            self.count = 0;
        }
        self.count += 1;
        self.count <= self.limit
    }
}

#[derive(Clone)]
struct FakeRow {
    id: String,
    updated_at: i64,
}

struct FakeVisualizer {
    rows: Vec<FakeRow>,
    windows: [Window; 2],
    /// Details that always answer 503.
    failing: Vec<String>,
    /// Chance in 100 of a 503, a 200 HTML page, or no reply, per request.
    flaky: u64,
    next_id: u32,
    rng: Rng,
}

enum Reply<T> {
    Ok(T),
    Fail(RowFailure),
}

impl FakeVisualizer {
    fn new(rows: u32, seed: u64) -> Self {
        let mut v = Self {
            rows: vec![],
            windows: [
                Window {
                    start: 0,
                    count: 0,
                    limit: 50,
                    len: 60_000,
                },
                Window {
                    start: 0,
                    count: 0,
                    limit: 200,
                    len: 600_000,
                },
            ],
            failing: vec![],
            flaky: 0,
            next_id: 0,
            rng: Rng::new(seed),
        };
        for _ in 0..rows {
            v.add(NOW / 1000 - 86_400);
        }
        v
    }

    fn add(&mut self, at: i64) {
        self.next_id += 1;
        self.rows.push(FakeRow {
            id: format!("v{}", self.next_id),
            updated_at: at,
        });
    }

    fn admit(&mut self, now: i64) -> Option<RowFailure> {
        // Every window counts the request, rejected or not.
        let admitted: Vec<bool> = self.windows.iter_mut().map(|w| w.admit(now)).collect();
        if !admitted.iter().all(|a| *a) {
            return Some(call(SyncFailure::RateLimited {
                retry_after_ms: None,
            }));
        }
        let roll = self.rng.below(100);
        (roll < self.flaky).then(|| match self.rng.below(3) {
            0 => call(SyncFailure::Unavailable { status: 503 }),
            1 => RowFailure::NotJson,
            _ => call(SyncFailure::Offline),
        })
    }

    /// `GET /shots?sort=updated_at&items&page&updated_after`: by offset, newest
    /// first, a page past the end answering the last page.
    fn list(
        &mut self,
        now: i64,
        page: u32,
        items: u32,
        after: Option<i64>,
    ) -> Reply<(Vec<ListRow>, u32, u32)> {
        if let Some(f) = self.admit(now) {
            return Reply::Fail(f);
        }
        let mut rows: Vec<&FakeRow> = self
            .rows
            .iter()
            .filter(|r| after.is_none_or(|a| r.updated_at > a))
            .collect();
        rows.sort_by_key(|r| std::cmp::Reverse(r.updated_at));
        let count = u32::try_from(rows.len()).unwrap();
        let pages = count.div_ceil(items).max(1);
        let page = page.min(pages);
        let start = ((page - 1) * items) as usize;
        let slice = rows
            .iter()
            .skip(start)
            .take(items as usize)
            .map(|r| ListRow {
                id: r.id.clone(),
                clock: 0,
                updated_at: r.updated_at,
            })
            .collect();
        Reply::Ok((slice, count, pages))
    }

    fn detail(&mut self, now: i64, id: &str) -> Reply<()> {
        if let Some(f) = self.admit(now) {
            return Reply::Fail(f);
        }
        if self.failing.iter().any(|f| f == id) {
            return Reply::Fail(call(SyncFailure::Unavailable { status: 503 }));
        }
        if self.rows.iter().any(|r| r.id == id) {
            Reply::Ok(())
        } else {
            Reply::Fail(call(SyncFailure::NotFound))
        }
    }

    /// Someone edits or deletes rows meanwhile.
    fn churn(&mut self, now: i64, edits: u64, deletes: u64) {
        let s = now / 1000;
        if !self.rows.is_empty() && self.rng.below(100) < edits {
            let i = self.rng.index(self.rows.len());
            self.rows[i].updated_at = s;
        }
        if !self.rows.is_empty() && self.rng.below(100) < deletes {
            let i = self.rng.index(self.rows.len());
            self.rows.remove(i);
        }
    }
}

/// What a device keeps between runs.
#[derive(Default)]
struct Device {
    cursor_ms: i64,
    versions: HashMap<String, i64>,
    poison: Vec<PoisonEntry>,
    bucket: PaceBucket,
    requests: u32,
}

struct Faults {
    edits: u64,
    deletes: u64,
    /// The process dies after this many requests.
    kill_after: Option<u32>,
}

const QUIET: Faults = Faults {
    edits: 0,
    deletes: 0,
    kill_after: None,
};

/// One run, as a shell's loop does it: pace, list, plan, fetch, apply, record.
fn run(v: &mut FakeVisualizer, d: &mut Device, now: &mut i64, f: &Faults) -> Option<RunOutcome> {
    let mut t = PullTally::starting(d.cursor_ms);
    let mut succeeded = vec![];
    let mut sent = 0u32;
    let mut page = 1;
    let after = (d.cursor_ms > 0).then_some(d.cursor_ms / 1000 - 1);
    // One request with its pacing and 429 waits; None when the run must end.
    macro_rules! request {
        ($call:expr) => {{
            let mut attempt = 0;
            loop {
                if f.kill_after.is_some_and(|k| sent >= k) {
                    return None;
                }
                let (bucket, wait) = pace(d.bucket, *now);
                d.bucket = bucket;
                *now += i64::try_from(wait).unwrap();
                v.churn(*now, f.edits, f.deletes);
                sent += 1;
                d.requests += 1;
                match $call {
                    Reply::Ok(x) => break Ok(x),
                    Reply::Fail(failure) => match row_failure_action(failure, attempt, None, 0.5) {
                        RowAction::Wait { wait_ms } => {
                            d.bucket = note_rate_limit(d.bucket, *now, wait_ms);
                            attempt += 1;
                        }
                        RowAction::RetryNow => attempt += 1,
                        action => break Err((failure, action)),
                    },
                }
            }
        }};
    }
    'walk: loop {
        let (rows, count, pages) = match request!(v.list(*now, page, 100, after)) {
            Ok(x) => x,
            Err((failure, _)) => {
                let failure = match failure {
                    RowFailure::Call { failure } => failure,
                    _ => SyncFailure::Unavailable { status: 200 },
                };
                t = end_run(&t, failure);
                break 'walk;
            }
        };
        let (next, step) = after_list_page(&t, count, 0);
        t = next;
        match step {
            ListStep::Restart => {
                page = 1;
                continue;
            }
            ListStep::Stop => break,
            ListStep::Continue => {}
        }
        let known: Vec<KnownRow> = rows
            .iter()
            .filter_map(|r| d.versions.get(&r.id).map(|ver| known(&r.id, Some(*ver))))
            .collect();
        let plan = plan_page(&PageInput {
            rows: rows.clone(),
            known,
            ..PageInput::default()
        });
        for (planned, list_row) in plan.rows.iter().zip(&rows) {
            let RowPlan::FetchDetail { id, .. } = planned else {
                t = record_row(&t, list_row, RowOutcome::Unchanged);
                continue;
            };
            match request!(v.detail(*now, id)) {
                Ok(()) => {
                    d.versions.insert(id.clone(), list_row.updated_at);
                    succeeded.push(id.clone());
                    t = record_row(&t, list_row, RowOutcome::Applied);
                }
                Err((
                    failure,
                    RowAction::Skip {
                        holds,
                        transient,
                        poison_candidate,
                    },
                )) => {
                    let _ = failure;
                    t = record_row(
                        &t,
                        list_row,
                        RowOutcome::Failed {
                            holds,
                            transient,
                            poison_candidate,
                        },
                    );
                }
                Err((failure, _)) => {
                    let failure = match failure {
                        RowFailure::Call { failure } => failure,
                        _ => SyncFailure::Unavailable { status: 200 },
                    };
                    t = end_run(&t, failure);
                    break 'walk;
                }
            }
            if t.ended.is_some() {
                break 'walk;
            }
        }
        if page >= pages || rows.is_empty() {
            break;
        }
        page += 1;
    }
    d.poison = settle_poison(&d.poison, &t, &succeeded, *now);
    let outcome = finish_run(&t, &d.poison, None);
    if let RunOutcome::Complete { next_cursor_ms, .. } = outcome {
        d.cursor_ms = next_cursor_ms;
    }
    Some(outcome)
}

fn unapplied(v: &FakeVisualizer, d: &Device) -> Vec<String> {
    v.rows
        .iter()
        .filter(|r| d.versions.get(&r.id) != Some(&r.updated_at))
        .filter(|r| {
            !d.poison
                .iter()
                .any(|p| p.id == r.id && p.count >= POISON_COUNT)
        })
        .map(|r| r.id.clone())
        .collect()
}

#[test]
fn pull_40_shots_gets_40() {
    let mut v = FakeVisualizer::new(40, 1);
    let mut d = Device::default();
    let mut now = NOW;
    assert!(matches!(
        run(&mut v, &mut d, &mut now, &QUIET),
        Some(RunOutcome::Complete { applied: 40, .. })
    ));
    assert!(unapplied(&v, &d).is_empty());
    assert!(
        now - NOW < 4 * 60_000,
        "paced, and under four minutes: {} s",
        (now - NOW) / 1000
    );
}

#[test]
fn pull_503_row_holds_cursor() {
    let mut v = FakeVisualizer::new(10, 2);
    v.failing.push("v3".to_owned());
    let mut d = Device::default();
    let mut now = NOW;
    assert!(matches!(
        run(&mut v, &mut d, &mut now, &QUIET),
        Some(RunOutcome::Partial {
            reason: PartialReason::RowsFailed,
            applied: 9,
            left: 1,
            ..
        })
    ));
    assert_eq!(d.cursor_ms, 0, "held");
    v.failing.clear();
    let before = d.requests;
    assert!(matches!(
        run(&mut v, &mut d, &mut now, &QUIET),
        Some(RunOutcome::Complete { applied: 1, .. })
    ));
    assert_eq!(
        d.requests - before,
        2,
        "one list page and that row's detail"
    );
}

#[test]
fn pull_120_429_page_2() {
    let mut v = FakeVisualizer::new(120, 3);
    let mut d = Device::default();
    let mut now = NOW;
    // Another device spent most of the window first.
    v.windows[1].count = 190;
    v.windows[1].start = NOW;
    v.windows[0].start = NOW;
    let outcome = run(&mut v, &mut d, &mut now, &QUIET).unwrap();
    assert!(
        !d.versions.is_empty(),
        "the rows before the limit stay applied: {outcome:?}"
    );
    let first = d.versions.len();
    while !matches!(
        run(&mut v, &mut d, &mut now, &QUIET),
        Some(RunOutcome::Complete { .. })
    ) {}
    assert!(d.versions.len() >= first);
    assert!(unapplied(&v, &d).is_empty());
}

#[test]
fn first_run_repairs_old_cursor() {
    // An old cursor passed rows #328 never applied: a walk from 0 with the old
    // cursor as the legacy line fetches those, and lists the rest only.
    let mut v = FakeVisualizer::new(30, 4);
    let missed: Vec<String> = v.rows.iter().take(5).map(|r| r.id.clone()).collect();
    let legacy = v.rows[0].updated_at;
    let known: Vec<KnownRow> = v
        .rows
        .iter()
        .filter(|r| !missed.contains(&r.id))
        .map(|r| KnownRow {
            remote_version: None,
            ..known(&r.id, None)
        })
        .collect();
    let rows = v.list(NOW, 1, 100, None);
    let Reply::Ok((rows, _, _)) = rows else {
        panic!()
    };
    let plan = plan_page(&PageInput {
        rows,
        known,
        legacy_cursor: Some(legacy),
        ..PageInput::default()
    });
    let fetched = plan
        .rows
        .iter()
        .filter(|r| matches!(r, RowPlan::FetchDetail { .. }))
        .count();
    assert_eq!(fetched, 5);
}

/// Property 1: whatever happens to a run (edits, deletes, 429s, 5xx, HTML,
/// no reply, kills), once things go quiet every remote row is applied, and a
/// complete run's cursor never passes a row that isn't.
#[test]
fn every_row_arrives_and_the_cursor_never_passes_one() {
    for seed in 0..60 {
        let mut rng = Rng::new(seed);
        let mut v = FakeVisualizer::new(u32::try_from(rng.below(150)).unwrap(), seed);
        let mut d = Device::default();
        let mut now = NOW;
        for _ in 0..rng.below(6) {
            v.flaky = rng.below(25);
            let faults = Faults {
                edits: rng.below(30),
                deletes: rng.below(10),
                kill_after: (rng.below(3) == 0).then(|| u32::try_from(rng.below(80)).unwrap()),
            };
            for _ in 0..rng.below(5) {
                v.add(now / 1000);
            }
            if let Some(RunOutcome::Complete { next_cursor_ms, .. }) =
                run(&mut v, &mut d, &mut now, &faults)
            {
                for r in &v.rows {
                    // The next run lists `updated_after = cursor_s - 1`
                    // (strict): every row it hasn't applied must be in it.
                    if d.versions.get(&r.id) != Some(&r.updated_at) {
                        assert!(
                            r.updated_at * 1000 > next_cursor_ms - 1000,
                            "seed {seed}: {} passed",
                            r.id
                        );
                    }
                }
            }
            now += i64::try_from(rng.below(3_600_000)).unwrap();
        }
        v.flaky = 0;
        let mut quiet = 0;
        while !matches!(
            run(&mut v, &mut d, &mut now, &QUIET),
            Some(RunOutcome::Complete { .. })
        ) {
            quiet += 1;
            assert!(quiet < 30, "seed {seed}: no complete run");
        }
        assert!(
            unapplied(&v, &d).is_empty(),
            "seed {seed}: {:?}",
            unapplied(&v, &d)
        );
    }
}

/// Property 5: no transient failure pattern alone produces a poison row.
#[test]
fn no_transient_pattern_poisons_a_row() {
    for seed in 0..40 {
        let mut v = FakeVisualizer::new(20, seed);
        v.flaky = 60;
        let mut d = Device::default();
        let mut now = NOW;
        for day in 0..5 {
            now = NOW + day * DAY_MS;
            let _ = run(&mut v, &mut d, &mut now, &QUIET);
        }
        v.flaky = 0;
        while !matches!(
            run(&mut v, &mut d, &mut now, &QUIET),
            Some(RunOutcome::Complete { .. })
        ) {}
        assert!(
            d.poison.iter().all(|p| p.count < POISON_COUNT),
            "seed {seed}: {:?}",
            d.poison
        );
        assert!(unapplied(&v, &d).is_empty());
    }
}
