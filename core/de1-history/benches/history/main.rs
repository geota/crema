//! PLAN B1, native: the store on seeded, real-shaped databases of 5,000,
//! 20,000 and 100,000 rows, measured against the budget table, with the
//! longest single transaction of each kind of maintenance (S15), the database
//! size, and the candidate sort indexes (SPEC §4) measured with and without.
//!
//! Plain `#[ignore]` timing tests, no criterion. Run them in release, one at
//! a time:
//!
//! ```sh
//! cd core
//! cargo test --release -p de1-history --bench history -- --ignored --nocapture --test-threads=1
//! ```
//!
//! Each test prints a Markdown table. `HISTORY_BENCH_OUT=<file>` also appends
//! it to that file (CI passes `$GITHUB_STEP_SUMMARY`), and
//! `HISTORY_BENCH_STRICT=1` fails a test whose numbers are over budget. The
//! budgets are the reference device's, so a desktop or CI runner that goes
//! over one is a real regression; one under them says little about a phone.
//!
//! Latency rows give the median of seven runs after a warm-up, with the worst
//! in the detail; transaction rows give the longest single transaction seen.

mod seed;

use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use de1_history::store::{JournalMode, Maintained, OpenOptions, Role, RowIn, Store};
use de1_history::{Anchor, Dir, HistoryQuery, Live, Sort, SortKey, sql};
use rusqlite::backup::{Backup, StepResult};
use serde_json::{Value, json};

/// Runs of each latency measure, after one warm-up.
const REPS: usize = 7;
/// A page of the list (SPEC §7).
const PAGE: u32 = 64;
/// The full diff's read batch (SPEC §11 stage 1 step 3), and the walk's and
/// restore's batch here.
const BATCH: usize = 500;
/// Batch sizes tried for the FTS rebuild, and for the derive rewrite.
const FTS_SWEEP: [u32; 4] = [500, 1_000, 2_000, 5_000];
const DERIVE_SWEEP: [u32; 4] = [100, 200, 500, 1_000];
/// The online backup's pages a step, as `Store::copy_to` takes them.
const BACKUP_PAGES: i32 = 256;
/// Searches of 3 or more characters: common, rare, accented, CJK, a bag tag
/// the row only has through the live library, and no match.
const SEARCHES: [&str; 8] = [
    "sour",
    "guji",
    "café",
    "niche",
    "hoffmann",
    "苦いが",
    "fruity",
    "zzqx",
];

// The budget table (PLAN B1), in ms, at 5,000, 20,000 and 100,000 rows.
const B_OPEN: [f64; 3] = [300.0, 400.0, 800.0];
const B_FIRST: [f64; 3] = [30.0, 60.0, 150.0];
const B_NEXT: [f64; 3] = [10.0, 10.0, 20.0];
const B_OFFSET: [f64; 3] = [30.0, 80.0, 300.0];
const B_SEARCH: [f64; 3] = [30.0, 60.0, 150.0];
const B_SCAN: [f64; 3] = [50.0, 150.0, 600.0];
const B_STATS: [f64; 3] = [20.0, 60.0, 250.0];
const B_FACETS: [f64; 3] = [30.0, 80.0, 300.0];
const B_INSERT: [f64; 3] = [10.0, 10.0, 15.0];
const B_EDIT: [f64; 3] = [10.0, 10.0, 15.0];
const B_BULK: [f64; 3] = [2_000.0, 6_000.0, 30_000.0];
const B_TX: [f64; 3] = [50.0, 50.0, 100.0];

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1e3
}

fn fmt_ms(v: f64) -> String {
    if v >= 1_000.0 {
        format!("{:.2} s", v / 1e3)
    } else if v >= 10.0 {
        format!("{v:.1} ms")
    } else {
        format!("{v:.2} ms")
    }
}

fn fmt_bytes(b: u64) -> String {
    #[allow(clippy::cast_precision_loss)]
    let b = b as f64;
    if b >= 1024.0 * 1024.0 {
        format!("{:.1} MiB", b / 1024.0 / 1024.0)
    } else {
        format!("{:.1} KiB", b / 1024.0)
    }
}

fn timed<T>(f: impl FnOnce() -> T) -> (T, Duration) {
    let t = Instant::now();
    let out = f();
    (out, t.elapsed())
}

/// One warm-up, then `REPS` runs: the median and the worst.
fn repeat(mut f: impl FnMut()) -> (Duration, Duration) {
    f();
    let mut runs: Vec<Duration> = (0..REPS).map(|_| timed(&mut f).1).collect();
    runs.sort();
    (runs[REPS / 2], runs[REPS - 1])
}

/// The longest and the total of a run of steps.
#[derive(Default, Clone)]
struct Steps {
    longest: Duration,
    total: Duration,
    n: usize,
    all: Vec<Duration>,
}

impl Steps {
    fn add(&mut self, d: Duration) {
        self.longest = self.longest.max(d);
        self.total += d;
        self.n += 1;
        self.all.push(d);
    }

    fn median(&self) -> Duration {
        self.quantile(50)
    }

    /// The `pct`th percentile step.
    fn quantile(&self, pct: usize) -> Duration {
        let mut all = self.all.clone();
        all.sort();
        let i = (all.len() * pct / 100).min(all.len().saturating_sub(1));
        all.get(i).copied().unwrap_or_default()
    }

    /// Steps longer than `cap`.
    fn over(&self, cap: Duration) -> usize {
        self.all.iter().filter(|d| **d > cap).count()
    }
}

/// One line of the report.
struct Line {
    measure: String,
    budget_ms: Option<f64>,
    ms: Option<f64>,
    detail: String,
}

/// The report for one size.
struct Report {
    rows: usize,
    tier: Option<usize>,
    lines: Vec<Line>,
    extra: String,
}

impl Report {
    fn new(rows: usize) -> Report {
        let tier = match rows {
            5_000 => Some(0),
            20_000 => Some(1),
            100_000 => Some(2),
            _ => None,
        };
        Report {
            rows,
            tier,
            lines: Vec::new(),
            extra: String::new(),
        }
    }

    fn line(
        &mut self,
        measure: impl Into<String>,
        budget: Option<[f64; 3]>,
        d: Duration,
        detail: impl Into<String>,
    ) {
        let budget_ms = budget.and_then(|b| self.tier.map(|t| b[t]));
        self.lines.push(Line {
            measure: measure.into(),
            budget_ms,
            ms: Some(ms(d)),
            detail: detail.into(),
        });
    }

    fn note(&mut self, measure: impl Into<String>, detail: impl Into<String>) {
        self.lines.push(Line {
            measure: measure.into(),
            budget_ms: None,
            ms: None,
            detail: detail.into(),
        });
    }

    fn over(&self) -> Vec<&Line> {
        self.lines
            .iter()
            .filter(|l| matches!((l.ms, l.budget_ms), (Some(v), Some(b)) if v > b))
            .collect()
    }

    fn markdown(&self, env: &str) -> String {
        let mut s = String::new();
        let _ = writeln!(s, "\n### History store, native, {} rows\n", self.rows);
        let _ = writeln!(s, "{env}\n");
        let _ = writeln!(s, "| Measure | Budget | Native | | Detail |");
        let _ = writeln!(s, "| --- | --- | --- | --- | --- |");
        for l in &self.lines {
            let budget = l.budget_ms.map_or_else(|| "report".into(), fmt_ms);
            let value = l.ms.map_or_else(String::new, fmt_ms);
            let verdict = match (l.ms, l.budget_ms) {
                (Some(v), Some(b)) if v > b => "OVER",
                (Some(_), Some(_)) => "ok",
                _ => "",
            };
            let _ = writeln!(
                s,
                "| {} | {budget} | {value} | {verdict} | {} |",
                l.measure, l.detail
            );
        }
        s.push_str(&self.extra);
        s
    }
}

fn sort_name(s: Sort) -> String {
    let key = match s.key {
        SortKey::Date => "date",
        SortKey::Rating => "rating",
        SortKey::Profile => "profile",
        SortKey::Bean => "bean",
        SortKey::Yield => "yield",
        SortKey::Time => "time",
    };
    let dir = match s.dir {
        Dir::Asc => "asc",
        Dir::Desc => "desc",
    };
    format!("{key} {dir}")
}

fn all_sorts() -> Vec<Sort> {
    SortKey::ALL
        .iter()
        .flat_map(|&key| [Dir::Desc, Dir::Asc].map(|dir| Sort { key, dir }))
        .collect()
}

fn sorted(sort: Sort) -> HistoryQuery {
    HistoryQuery {
        sort,
        ..HistoryQuery::default()
    }
}

fn live() -> Live {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/live.json");
    serde_json::from_str(&std::fs::read_to_string(path).expect("live.json")).expect("a library")
}

fn open(path: &Path, role: Role) -> Store {
    Store::open(path, OpenOptions::new(role)).expect("opens")
}

fn reopen(store: Store, path: &Path, role: Role) -> Store {
    store.close().expect("closes");
    open(path, role)
}

fn int(store: &Store, sql: &str) -> i64 {
    store
        .raw()
        .query_row(sql, [], |r| r.get(0))
        .expect("an integer")
}

fn rows_in(rows: &[String]) -> Vec<RowIn> {
    rows.iter().map(|r| RowIn::new(r.clone())).collect()
}

fn id_of(row: &str) -> String {
    let v: Value = serde_json::from_str(row).expect("JSON");
    v["id"].as_str().expect("an id").to_owned()
}

/// `row` with `f` applied.
fn edited(row: &str, f: impl FnOnce(&mut Value)) -> String {
    let mut v: Value = serde_json::from_str(row).expect("JSON");
    f(&mut v);
    v.to_string()
}

/// The full diff (SPEC §11 stage 1 step 3): `rows` in batches, then the
/// removal by absence with every id, a step at a time, keeping what the index
/// wrote since `since`.
fn full_diff(index: &mut Store, rows: &[String]) -> (Steps, Steps) {
    let since = index.write_seq();
    let mut batches = Steps::default();
    for chunk in rows.chunks(BATCH) {
        let chunk = rows_in(chunk);
        batches.add(timed(|| index.diff_batch(&chunk).expect("a diff batch")).1);
    }
    let ids: Vec<String> = rows.iter().map(|r| id_of(r)).collect();
    let mut rm = Steps::default();
    rm.add(timed(|| index.remove_absent_begin(&ids, since).expect("begins")).1);
    loop {
        let (step, d) = timed(|| index.remove_absent_next().expect("a step"));
        rm.add(d);
        if step.done {
            return (batches, rm);
        }
    }
}

/// Incremental merge steps until one moves nothing (at most 10,000).
fn merges(store: &mut Store) -> Steps {
    let mut s = Steps::default();
    for _ in 0..10_000 {
        let (step, d) = timed(|| store.fts_merge_step().expect("merges"));
        s.add(d);
        if step != (Maintained::Merged { more: true }) {
            break;
        }
    }
    s
}

/// A whole FTS rebuild at `batch` rows: the start, the batches, and the swap
/// (the last batch with the drop and rename).
struct Rebuild {
    start: Duration,
    batches: Steps,
    swap: Duration,
}

fn rebuild(store: &mut Store, batch: Option<u32>, mut during: impl FnMut(&mut Store)) -> Rebuild {
    if let Some(batch) = batch {
        store.set_batch_rows(batch);
    }
    let (_, start) = timed(|| store.start_fts_rebuild().expect("starts"));
    let mut batches = Steps::default();
    let mut first = true;
    loop {
        if first {
            // Halfway would be fairer to the scan, but the scan reads every
            // row whatever the cursor: the cost doesn't move.
            during(store);
            first = false;
        }
        let (step, d) = timed(|| store.maintain().expect("maintains"));
        match step {
            Maintained::FtsBatch { .. } => batches.add(d),
            Maintained::FtsSwapped => {
                return Rebuild {
                    start,
                    batches,
                    swap: d,
                };
            }
            other => panic!("{other:?}"),
        }
    }
}

/// A derive rewrite of every row at `batch` rows: the columns are planted
/// stale (a sort key off, `derive_v` 0), the store reopened, and maintenance
/// run until it is done. Every row's update rewrites its search text too.
fn derive_rewrite(store: Store, path: &Path, batch: u32) -> (Store, Steps) {
    store
        .raw()
        .execute_batch(
            "UPDATE shot SET derive_v = 0, sort_rating = -1; \
             UPDATE meta SET value = '0' WHERE key = 'derive_v';",
        )
        .expect("plants stale columns");
    let mut store = reopen(store, path, Role::Authority);
    assert!(store.health().derive_pending);
    store.set_batch_rows(batch);
    let mut s = Steps::default();
    loop {
        let (step, d) = timed(|| store.maintain().expect("maintains"));
        match step {
            Maintained::Derived { .. } => s.add(d),
            Maintained::DeriveDone => break,
            other => panic!("{other:?}"),
        }
    }
    (store, s)
}

/// count + the first page of `q`.
fn first_page(store: &mut Store, q: &HistoryQuery) {
    store.count(q).expect("counts");
    store.page(q, &Anchor::Start, PAGE).expect("a page");
}

/// The latency of each list operation under `q`: count + first page, the
/// next page from the top and from the middle, and an offset jump to the
/// end.
struct ListCosts {
    first: (Duration, Duration),
    next: (Duration, Duration),
    next_deep: (Duration, Duration),
    offset_end: (Duration, Duration),
    index_of: (Duration, Duration),
}

fn list_costs(store: &mut Store, q: &HistoryQuery) -> ListCosts {
    let n = store.count(q).expect("counts");
    let first = repeat(|| first_page(store, q));
    let top = store.page(q, &Anchor::Start, PAGE).expect("a page");
    let after = Anchor::After(top.last.expect("a cursor"));
    let next = repeat(|| {
        store.page(q, &after, PAGE).expect("a page");
    });
    let mid = store.page(q, &Anchor::Offset(n / 2), PAGE).expect("a page");
    let deep = Anchor::After(mid.last.expect("a cursor"));
    let deep_id = mid.rows[0].id.clone();
    let next_deep = repeat(|| {
        store.page(q, &deep, PAGE).expect("a page");
    });
    let end = Anchor::Offset(n.saturating_sub(PAGE));
    let offset_end = repeat(|| {
        store.page(q, &end, PAGE).expect("a page");
    });
    let index_of = repeat(|| {
        store.index_of(q, &deep_id).expect("a position");
    });
    ListCosts {
        first,
        next,
        next_deep,
        offset_end,
        index_of,
    }
}

/// `(name, bytes)` per table and index, from `dbstat`.
fn sizes(store: &Store) -> Vec<(String, u64)> {
    let mut st = store
        .raw()
        .prepare("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC")
        .expect("dbstat");
    st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?)))
        .expect("runs")
        .map(|r| {
            let (n, b) = r.expect("a row");
            (n, u64::try_from(b).unwrap_or(0))
        })
        .collect()
}

/// The database size line: the file, and what its pages hold.
fn size_line(r: &mut Report, store: &Store, path: &Path, what: &str) {
    store
        .raw()
        .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()))
        .expect("checkpoints");
    let file = std::fs::metadata(path).expect("the file").len();
    let page = u64::try_from(int(store, "PRAGMA page_size")).unwrap_or(4096);
    let free = u64::try_from(int(store, "PRAGMA freelist_count")).unwrap_or(0) * page;
    let mut shot = 0;
    let mut id_index = 0;
    let mut indexes = 0;
    let mut sort_indexes = 0;
    let mut fts = 0;
    let mut rest = 0;
    for (name, b) in sizes(store) {
        match name.as_str() {
            "shot" => shot += b,
            "sqlite_autoindex_shot_1" => id_index += b,
            n if n.starts_with("shot_fts") => fts += b,
            n if n.starts_with("shot_sort_") => sort_indexes += b,
            n if n.starts_with("shot_by_") || n.starts_with("shot_tag") => indexes += b,
            _ => rest += b,
        }
    }
    let rows = u64::try_from(r.rows).unwrap_or(1).max(1);
    let detail = format!(
        "{} ({} B/row): `shot` {}, its id index {}, the sort indexes {}, other indexes and tags {}, FTS {}, the rest {}, free pages {}",
        fmt_bytes(file),
        file / rows,
        fmt_bytes(shot),
        fmt_bytes(id_index),
        fmt_bytes(sort_indexes),
        fmt_bytes(indexes),
        fmt_bytes(fts),
        fmt_bytes(rest),
        fmt_bytes(free),
    );
    r.note(format!("Database size, {what}"), detail);
}

/// New shots to insert, each newer than the last.
fn new_shots(rows: &[String], tag: &str, k: usize) -> Vec<String> {
    (0..k)
        .map(|i| {
            edited(&rows[i * 7 % rows.len()], |v| {
                v["id"] = json!(format!("shot:bench-{tag}-{i:04}"));
                v["completedAt"] = json!(1_800_000_000_000_i64 + i64::try_from(i).unwrap_or(0));
            })
        })
        .collect()
}

/// Single-row writes: their median and worst.
fn single_writes(store: &mut Store, rows: &[String]) -> (Duration, Duration) {
    let mut d: Vec<Duration> = rows
        .iter()
        .map(|r| {
            let row = [RowIn::new(r.clone())];
            timed(|| store.upsert(&row).expect("writes")).1
        })
        .collect();
    d.sort();
    (d[d.len() / 2], d[d.len() - 1])
}

fn worst<T>(xs: &[(String, T)], f: impl Fn(&T) -> Duration) -> (&str, Duration) {
    xs.iter()
        .map(|(n, c)| (n.as_str(), f(c)))
        .max_by_key(|(_, d)| *d)
        .expect("one")
}

/// `d` over `rows` rows, in µs a row.
fn per_row(d: Duration, rows: usize) -> String {
    #[allow(clippy::cast_precision_loss)]
    let us = d.as_secs_f64() * 1e6 / rows.max(1) as f64;
    format!("{us:.0} µs a row")
}

fn mw(p: (Duration, Duration)) -> String {
    format!("{} / {}", fmt_ms(ms(p.0)), fmt_ms(ms(p.1)))
}

/// `EXPLAIN QUERY PLAN` of `st`, its details joined by ` | `.
fn plan(store: &Store, st: &sql::Stmt) -> String {
    let params: Vec<rusqlite::types::Value> = st
        .params
        .iter()
        .map(|p| match p {
            sql::Param::Int(i) => rusqlite::types::Value::Integer(*i),
            sql::Param::Real(r) => rusqlite::types::Value::Real(*r),
            sql::Param::Text(t) => rusqlite::types::Value::Text(t.clone()),
        })
        .collect();
    let mut q = store
        .raw()
        .prepare(&format!("EXPLAIN QUERY PLAN {}", st.sql))
        .expect("explains");
    q.query_map(rusqlite::params_from_iter(params), |r| {
        r.get::<_, String>(3)
    })
    .expect("runs")
    .map(|r| r.expect("a step"))
    .collect::<Vec<_>>()
    .join(" | ")
}

/// The plan's top-level steps only: a subquery's own sort (a `UNION`'s
/// merge) is not the statement's.
fn top_plan(store: &Store, st: &sql::Stmt) -> String {
    let params: Vec<rusqlite::types::Value> = st
        .params
        .iter()
        .map(|p| match p {
            sql::Param::Int(i) => rusqlite::types::Value::Integer(*i),
            sql::Param::Real(r) => rusqlite::types::Value::Real(*r),
            sql::Param::Text(t) => rusqlite::types::Value::Text(t.clone()),
        })
        .collect();
    let mut q = store
        .raw()
        .prepare(&format!("EXPLAIN QUERY PLAN {}", st.sql))
        .expect("explains");
    q.query_map(rusqlite::params_from_iter(params), |r| {
        Ok((r.get::<_, i64>(1)?, r.get::<_, String>(3)?))
    })
    .expect("runs")
    .map(|r| r.expect("a step"))
    .filter(|(parent, _)| *parent == 0)
    .map(|(_, d)| d)
    .collect::<Vec<_>>()
    .join(" | ")
}

/// The two planner traps B1 found, held by assertion on every run:
/// - `index_of` under any sort counts a range of its sort index, so it costs
///   about what it costs under the date sort;
/// - a search that matches nothing costs about the same under any sort: its
///   count reads only the matches, and its page walks the sort index probing
///   the match set, never reading every row.
///
/// The plans are checked too, so a regression names its statement.
fn planner_traps(store: &mut Store, costs: &[(String, ListCosts)], x: &mut String) {
    let path = sql::TextPath::Fts;
    let mid_id = {
        let q = HistoryQuery::default();
        let n = store.count(&q).expect("counts");
        store
            .page(&q, &Anchor::Offset(n / 2), 1)
            .expect("a page")
            .rows[0]
            .id
            .clone()
    };
    let mut failures = Vec::new();
    for sort in all_sorts() {
        let name = sort_name(sort);
        let q = sorted(sort);
        let page = sql::page(&q, sql::Seek::Start, PAGE, path)
            .expect("builds")
            .stmt;
        let p = top_plan(store, &page);
        if p.contains("TEMP B-TREE") {
            failures.push(format!(
                "{name}: the page sorts instead of walking an index: {p}"
            ));
        }
        let io = plan(store, &sql::index_of(&q, &mid_id, path));
        if !io.contains("SEARCH s USING INDEX") {
            failures.push(format!(
                "{name}: `index_of` doesn't count an index range: {io}"
            ));
        }
        let none = HistoryQuery {
            text: "zzqx".into(),
            ..sorted(sort)
        };
        let c = plan(store, &sql::count(&none, path));
        if !c.contains("SEARCH s USING INTEGER PRIMARY KEY")
            || ["SCAN s ", "SCAN s_m", "SCAN s_b"]
                .iter()
                .any(|scan| format!("{c} ").contains(scan))
        {
            failures.push(format!(
                "{name}: a search count reads more than its matches: {c}"
            ));
        }
        let sp = top_plan(
            store,
            &sql::page(&none, sql::Seek::Start, PAGE, path)
                .expect("builds")
                .stmt,
        );
        if sp.contains("TEMP B-TREE") {
            failures.push(format!("{name}: a search page sorts its matches: {sp}"));
        }
    }
    // The costs, relative to the date sort, so any machine can hold them.
    let date_io = costs[0].1.index_of.0;
    let (w, io) = worst(costs, |c| c.index_of.0);
    let io_cap = date_io * 4 + Duration::from_millis(2);
    if io > io_cap {
        failures.push(format!(
            "`index_of` under {w} took {}, over 4 times the date sort's {} + 2 ms",
            fmt_ms(ms(io)),
            fmt_ms(ms(date_io))
        ));
    }
    let none: Vec<(String, (Duration, Duration))> = all_sorts()
        .into_iter()
        .map(|sort| {
            let q = HistoryQuery {
                text: "zzqx".into(),
                ..sorted(sort)
            };
            (sort_name(sort), repeat(|| first_page(store, &q)))
        })
        .collect();
    let date_none = none[0].1.0;
    let (wn, vn) = worst(&none, |c| c.0);
    let none_cap = date_none * 3 + Duration::from_millis(2);
    if vn > none_cap {
        failures.push(format!(
            "a search with no match under {wn} took {}, over 3 times the date sort's {} + 2 ms",
            fmt_ms(ms(vn)),
            fmt_ms(ms(date_none))
        ));
    }
    let _ = writeln!(
        x,
        "\nPlanner traps: `index_of` worst {} ({w}), date desc {}; a search with no match, count + first page, worst {} ({wn}), date desc {}. Plans checked for every sort: pages and search pages walk an index (no temp sort), `index_of` counts an index range, a search count reads only its matches.",
        fmt_ms(ms(io)),
        fmt_ms(ms(date_io)),
        fmt_ms(ms(vn)),
        fmt_ms(ms(date_none)),
    );
    assert!(
        failures.is_empty(),
        "planner traps:\n{}",
        failures.join("\n")
    );
}

#[allow(clippy::too_many_lines)]
fn run(n: usize) {
    let d = tempfile::tempdir().expect("a temp dir");
    let index_path = d.path().join("history-index.db");
    let flip_path = d.path().join("history.db");
    let live = live();
    let (rows, gen_time) = timed(|| {
        seed::rows(n)
            .iter()
            .map(Value::to_string)
            .collect::<Vec<String>>()
    });
    // As shots arrive and as the P3 store gives them: oldest first.
    let arrival: Vec<String> = rows.iter().rev().cloned().collect();
    let mean_row = rows.iter().map(String::len).sum::<usize>() / n.max(1);
    let mut r = Report::new(n);

    // ── Stage 1: the index from empty, then a full diff over it ────────
    let mut index =
        Store::open(&index_path, OpenOptions::new(Role::Index).create(1)).expect("creates");
    index.set_live(&live).expect("a library");
    let (build, build_rm) = full_diff(&mut index, &arrival);
    let build_merges = merges(&mut index);
    r.line(
        "Stage-1 rebuild (empty index, full diff)",
        Some(B_BULK),
        build.total + build_rm.total,
        format!(
            "{} batches of {BATCH}, then {} removal steps",
            build.n,
            build_rm.n - 1
        ),
    );
    // The P3 store a boot diffs against: 1% of rows edited, 1% gone.
    let p3: Vec<String> = arrival
        .iter()
        .enumerate()
        .filter(|(i, _)| i % 100 != 50)
        .map(|(i, row)| {
            if i % 100 == 0 {
                edited(row, |v| {
                    v["metadata"]["notes"] = json!("edited since: go coarser")
                })
            } else {
                row.clone()
            }
        })
        .collect();
    let (diff, diff_rm) = full_diff(&mut index, &p3);
    let diff_merges = merges(&mut index);
    r.line(
        "Full diff (1% edited, 1% gone)",
        Some(B_BULK),
        diff.total + diff_rm.total,
        format!(
            "batches {}, removal by absence {} in {} steps",
            fmt_ms(ms(diff.total)),
            fmt_ms(ms(diff_rm.total)),
            diff_rm.n - 1
        ),
    );

    // ── Stage 2: the flip's copy (SPEC §11 step 5) ──────────────────────
    let (_, copy) = timed(|| {
        index
            .copy_to(&flip_path, None, JournalMode::Wal)
            .expect("copies");
    });
    // The same copy a step at a time, for its longest step.
    let mut backup_steps = Steps::default();
    {
        let mut dst = rusqlite::Connection::open(d.path().join("history-steps.db")).expect("opens");
        let b = Backup::new(index.raw(), &mut dst).expect("a backup");
        loop {
            let (step, t) = timed(|| b.step(BACKUP_PAGES).expect("a step"));
            backup_steps.add(t);
            match step {
                StepResult::Done => break,
                StepResult::More => {}
                other => panic!("{other:?}"),
            }
        }
    }
    let mut flip = open(&flip_path, Role::Index);
    let ids: Vec<String> = p3.iter().map(|r| id_of(r)).collect();
    let ids_json = serde_json::to_string(&ids).expect("JSON");
    let (_, step5) = timed(|| {
        let c = flip.raw();
        c.execute_batch("BEGIN IMMEDIATE").expect("begins");
        c.execute("UPDATE meta SET value = 'authority' WHERE key = 'role'", [])
            .expect("a role");
        c.execute(
            "INSERT INTO meta (key, value) VALUES ('migration', ?1) \
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            [r#"{"state":"copying"}"#],
        )
        .expect("a migration record");
        c.execute(
            "INSERT INTO migration_source (id) SELECT DISTINCT value FROM json_each(?1)",
            [&ids_json],
        )
        .expect("the source ids");
        c.execute_batch("COMMIT").expect("commits");
    });
    let flip_fts = rebuild(&mut flip, None, |_| {});
    let flip_merges = merges(&mut flip);
    let flip_total = copy + step5 + flip_fts.start + flip_fts.batches.total + flip_fts.swap;
    r.line(
        "Flip copy (backup, step 5, FTS rebuild)",
        Some(B_BULK),
        flip_total,
        format!(
            "backup {}, step 5 {}, FTS rebuild {}",
            fmt_ms(ms(copy)),
            fmt_ms(ms(step5)),
            fmt_ms(ms(flip_fts.start + flip_fts.batches.total + flip_fts.swap))
        ),
    );
    let mut store = reopen(flip, &flip_path, Role::Authority);
    let live_rows = u32::try_from(p3.len()).expect("fits");
    assert_eq!(
        store.count(&HistoryQuery::default()).expect("counts"),
        live_rows
    );

    // ── Reads ───────────────────────────────────────────────────────────
    let date = HistoryQuery::default();
    let mut store_slot = Some(store);
    let open_paint = repeat(|| {
        let s = store_slot.take().expect("a store");
        s.close().expect("closes");
        let mut s = open(&flip_path, Role::Authority);
        first_page(&mut s, &date);
        store_slot = Some(s);
    });
    let store = store_slot.take().expect("a store");
    store.close().expect("closes");
    let (s, quick) = timed(|| {
        Store::open(
            &flip_path,
            OpenOptions {
                quick_check: true,
                ..OpenOptions::new(Role::Authority)
            },
        )
        .expect("opens")
    });
    let mut store = s;
    r.line(
        "Open to first page (open, count, page of 64)",
        Some(B_OPEN),
        open_paint.0,
        format!(
            "worst {}; the shell's paint not included",
            fmt_ms(ms(open_paint.1))
        ),
    );

    let costs: Vec<(String, ListCosts)> = all_sorts()
        .into_iter()
        .map(|s| (sort_name(s), list_costs(&mut store, &sorted(s))))
        .collect();
    let (w, v) = worst(&costs, |c| c.first.0);
    r.line(
        "`count` + first page, any sort",
        Some(B_FIRST),
        v,
        format!(
            "worst sort {w}; date desc {}",
            fmt_ms(ms(costs[0].1.first.0))
        ),
    );
    let (w, v) = worst(&costs, |c| c.next.0.max(c.next_deep.0));
    r.line(
        "Next page (keyset)",
        Some(B_NEXT),
        v,
        format!(
            "worst sort {w}, from the top or the middle; date desc {}",
            fmt_ms(ms(costs[0].1.next.0))
        ),
    );
    let (w, v) = worst(&costs, |c| c.offset_end.0);
    r.line(
        "Offset jump to the end",
        Some(B_OFFSET),
        v,
        format!(
            "worst sort {w}; date desc {}",
            fmt_ms(ms(costs[0].1.offset_end.0))
        ),
    );
    let (w, v) = worst(&costs, |c| c.index_of.0);
    r.line(
        "`index_of` a row in the middle",
        None,
        v,
        format!("worst sort {w}; deep links and scroll memory"),
    );

    let search = |store: &mut Store| -> Vec<(String, (Duration, Duration))> {
        let mut out = Vec::new();
        for text in SEARCHES {
            for sort in [
                Sort::default(),
                Sort {
                    key: SortKey::Rating,
                    dir: Dir::Desc,
                },
            ] {
                let q = HistoryQuery {
                    text: text.into(),
                    sort,
                    ..HistoryQuery::default()
                };
                out.push((
                    format!("\"{text}\" by {}", sort_name(sort)),
                    repeat(|| first_page(store, &q)),
                ));
            }
        }
        out
    };
    let fts_search = search(&mut store);
    let (w, v) = worst(&fts_search, |c| c.0);
    r.line(
        "Search, 3+ characters (count + first page)",
        Some(B_SEARCH),
        v,
        format!("worst {w}"),
    );

    let (stats, stats_worst) = repeat(|| {
        store.stats(&date).expect("stats");
    });
    r.line(
        "Stats over all rows",
        Some(B_STATS),
        stats,
        format!("worst {}", fmt_ms(ms(stats_worst))),
    );
    let (facets, facets_worst) = repeat(|| {
        store.facets(&date).expect("facets");
    });
    r.line(
        "Facets",
        Some(B_FACETS),
        facets,
        format!("worst {}", fmt_ms(ms(facets_worst))),
    );

    let inserts = new_shots(&rows, "a", 21);
    let ins = single_writes(&mut store, &inserts);
    r.line(
        "Insert one shot (row + FTS)",
        Some(B_INSERT),
        ins.0,
        format!("median of 21; worst {}", fmt_ms(ms(ins.1))),
    );
    let edits: Vec<String> = (0..21)
        .map(|i| {
            edited(&rows[(i * 997) % rows.len()], |v| {
                let star = v["metadata"]["rating"].as_i64().unwrap_or(0);
                v["metadata"]["rating"] = json!(if star == 5 { 4 } else { 5 });
            })
        })
        .collect();
    let ed = single_writes(&mut store, &edits);
    r.line(
        "Edit (star tap)",
        Some(B_EDIT),
        ed.0,
        format!("median of 21; worst {}", fmt_ms(ms(ed.1))),
    );
    let gone: Vec<String> = inserts.iter().take(5).map(|r| id_of(r)).collect();
    let mut del = Steps::default();
    for id in &gone {
        del.add(timed(|| store.delete(std::slice::from_ref(id)).expect("deletes")).1);
    }
    r.line("Delete one shot", None, del.longest, "worst of 5");
    size_line(&mut r, &store, &flip_path, "after the flip");
    size_line(&mut r, &index, &index_path, "the stage-1 index");
    index.close().expect("closes");

    // ── Maintenance (S15) ────────────────────────────────────────────────
    let mut scan = Vec::new();
    let mut fts_sweep = Vec::new();
    for (i, batch) in FTS_SWEEP.into_iter().enumerate() {
        let rb = rebuild(&mut store, Some(batch), |s| {
            if i == 0 {
                scan = search(s);
            }
        });
        let m = merges(&mut store);
        fts_sweep.push((batch, rb, m));
    }
    let (w, v) = worst(&scan, |c| c.0);
    r.line(
        "Any search during an FTS rebuild (scan)",
        Some(B_SCAN),
        v,
        format!("worst {w}"),
    );
    let mut derive_sweep = Vec::new();
    for batch in DERIVE_SWEEP {
        let (s, steps) = derive_rewrite(store, &flip_path, batch);
        store = s;
        let m = merges(&mut store);
        derive_sweep.push((batch, steps, m));
    }
    let (_, integrity) = timed(|| store.fts_integrity_check().expect("checks"));

    let (walk, walk_begin) = timed(|| store.export_begin().expect("begins"));
    let mut walk_steps = Steps::default();
    loop {
        let (got, t) = timed(|| {
            store
                .export_next(walk.token, u32::try_from(BATCH).expect("fits"))
                .expect("a step")
        });
        walk_steps.add(t);
        if got.is_empty() {
            break;
        }
    }
    let (_, walk_end) = timed(|| store.export_end(walk.token).expect("ends"));

    let mut more_live = live.clone();
    more_live.beans.push(de1_history::LiveBean {
        bean_id: "bean:h".into(),
        name: "Bench Natural".into(),
        roaster: Some("Bench".into()),
        tags: vec!["funky".into()],
    });
    let (_, set_live) = timed(|| store.set_live(&more_live).expect("a library"));

    let begin = store.restore_begin().expect("begins");
    let mut restore = Steps::default();
    for chunk in arrival.chunks(BATCH).take(4) {
        let chunk: Vec<RowIn> = chunk
            .iter()
            .map(|row| {
                RowIn::new(edited(row, |v| {
                    v["metadata"]["notes"] = json!("restored from a backup")
                }))
            })
            .collect();
        restore.add(timed(|| store.restore_batch(&chunk).expect("a batch")).1);
    }
    let (wipe_ids, wipe_read) = timed(|| {
        store
            .ids_created_before(begin, 0, u32::try_from(BATCH).expect("fits"))
            .expect("ids")
    });
    // The shell would take the backup's ids away first; the delete's cost is
    // the same for any 500 rows.
    let wipe_ids: Vec<String> = wipe_ids.into_iter().map(|r| r.id).collect();
    let (_, wipe_delete) = timed(|| store.delete(&wipe_ids).expect("deletes"));

    let at = |sweep: &[(u32, Duration)], rows: u32| {
        sweep
            .iter()
            .find(|(b, _)| *b == rows)
            .map_or(Duration::ZERO, |(_, d)| *d)
    };
    let fts_def = de1_history::store::FTS_BATCH_ROWS;
    let derive_def = de1_history::store::DERIVE_BATCH_ROWS;
    let fts_batch_at: Vec<(u32, Duration)> = fts_sweep
        .iter()
        .map(|(b, rb, _)| (*b, rb.batches.longest))
        .collect();
    let derive_at: Vec<(u32, Duration)> = derive_sweep
        .iter()
        .map(|(b, s, _)| (*b, s.longest))
        .collect();
    let swap = fts_sweep
        .iter()
        .map(|(_, rb, _)| rb.swap)
        .max()
        .unwrap_or_default();
    let merge_longest = fts_sweep
        .iter()
        .map(|(_, _, m)| m.longest)
        .chain(derive_sweep.iter().map(|(_, _, m)| m.longest))
        .chain([
            build_merges.longest,
            diff_merges.longest,
            flip_merges.longest,
        ])
        .max()
        .unwrap_or_default();
    let tx = Some(B_TX);
    r.line(
        format!("Longest tx: FTS rebuild batch ({fts_def} rows)"),
        tx,
        at(&fts_batch_at, fts_def),
        format!(
            "{}; per batch size below",
            per_row(at(&fts_batch_at, fts_def), fts_def as usize)
        ),
    );
    r.line(
        "Longest tx: FTS swap (last batch, drop, rename)",
        tx,
        swap,
        "worst over the sweep",
    );
    r.line(
        "Longest tx: FTS start (drop and create the new table)",
        tx,
        fts_sweep
            .iter()
            .map(|(_, rb, _)| rb.start)
            .max()
            .unwrap_or_default(),
        "",
    );
    r.line(
        "Longest tx: FTS merge step ('merge', 500)",
        tx,
        merge_longest,
        "after the builds, the diff and each rebuild",
    );
    r.line(
        format!("Longest tx: derive rewrite batch ({derive_def} rows)"),
        tx,
        at(&derive_at, derive_def),
        format!(
            "every row's columns and search text rewritten; {}",
            per_row(at(&derive_at, derive_def), derive_def as usize)
        ),
    );
    r.line(
        format!("Longest tx: diff batch ({BATCH} new rows)"),
        tx,
        build.longest,
        format!("the stage-1 build; {}", per_row(build.longest, BATCH)),
    );
    r.line(
        format!("Longest tx: diff batch ({BATCH} rows, 1% edited)"),
        tx,
        diff.longest,
        "the boot's full diff",
    );
    r.line(
        "Longest tx: removal by absence step (≤500 deletes, ≤5,000 rows read)",
        tx,
        diff_rm.longest.max(build_rm.longest),
        format!(
            "{} steps in the full diff, {} in all",
            diff_rm.n,
            fmt_ms(ms(diff_rm.total))
        ),
    );
    r.line(
        format!("Longest tx: flip backup step ({BACKUP_PAGES} pages)"),
        tx,
        backup_steps.longest,
        format!(
            "{} steps, {} in all",
            backup_steps.n,
            fmt_ms(ms(backup_steps.total))
        ),
    );
    r.line(
        "Longest tx: flip step 5 (role, every id into `migration_source`)",
        tx,
        step5,
        "",
    );
    r.line("Longest tx: walk begin (the walk set)", tx, walk_begin, "");
    r.line(
        format!("Longest tx: walk step ({BATCH} rows)"),
        tx,
        walk_steps.longest,
        format!("{} steps", walk_steps.n),
    );
    r.line("Longest tx: walk end", tx, walk_end, "");
    r.line(
        format!("Longest tx: restore batch ({BATCH} changed rows)"),
        tx,
        restore.longest,
        per_row(restore.longest, BATCH),
    );
    r.line(
        format!("Longest tx: restore wipe step ({BATCH} ids read, then deleted)"),
        tx,
        wipe_read + wipe_delete,
        format!(
            "read {}, delete {}",
            fmt_ms(ms(wipe_read)),
            fmt_ms(ms(wipe_delete))
        ),
    );
    r.line("Longest tx: `set_live`", tx, set_live, "");
    r.line(
        "Longest read: `quick_check` at open",
        tx,
        quick,
        "after an unclean close, a corrupt error, or weekly",
    );
    r.line(
        "Longest read: FTS `integrity-check`",
        tx,
        integrity,
        "one statement, only when no shot runs; web defers it (REVIEW-439 note 6)",
    );

    // ── The batch-size sweep ─────────────────────────────────────────────
    let mut x = String::new();
    let _ = writeln!(
        x,
        "\nMaintenance batch sizes ({n} rows): a step's median and longest, and the whole job\n"
    );
    let _ = writeln!(
        x,
        "| FTS batch | Batch, median / longest | Swap | Rebuild total |"
    );
    let _ = writeln!(x, "| --- | --- | --- | --- |");
    for (b, rb, _) in &fts_sweep {
        let _ = writeln!(
            x,
            "| {b} | {} | {} | {} |",
            mw((rb.batches.median(), rb.batches.longest)),
            fmt_ms(ms(rb.swap)),
            fmt_ms(ms(rb.start + rb.batches.total + rb.swap)),
        );
    }
    let _ = writeln!(
        x,
        "\n| Derive batch | Batch, median / longest | 95th percentile | Batches over 50 ms | Rewrite total |"
    );
    let _ = writeln!(x, "| --- | --- | --- | --- | --- |");
    for (b, ds, _) in &derive_sweep {
        let _ = writeln!(
            x,
            "| {b} | {} | {} | {} of {} | {} |",
            mw((ds.median(), ds.longest)),
            fmt_ms(ms(ds.quantile(95))),
            ds.over(Duration::from_millis(50)),
            ds.n,
            fmt_ms(ms(ds.total)),
        );
    }

    // ── Per sort, and the planner traps (B1) ─────────────────────────────
    let costs: Vec<(String, ListCosts)> = all_sorts()
        .into_iter()
        .map(|s| (sort_name(s), list_costs(&mut store, &sorted(s))))
        .collect();
    let _ = writeln!(
        x,
        "\nPer sort ({n} rows), median / worst, with the sort indexes\n"
    );
    let _ = writeln!(
        x,
        "| Sort | count + first page | next page (top) | next page (middle) | offset to the end | `index_of` middle |"
    );
    let _ = writeln!(x, "| --- | --- | --- | --- | --- | --- |");
    for (name, c) in &costs {
        let _ = writeln!(
            x,
            "| {name} | {} | {} | {} | {} | {} |",
            mw(c.first),
            mw(c.next),
            mw(c.next_deep),
            mw(c.offset_end),
            mw(c.index_of),
        );
    }
    planner_traps(&mut store, &costs, &mut x);
    r.extra = x;
    store.close().expect("closes");

    let version: String = rusqlite::Connection::open_in_memory()
        .and_then(|c| c.query_row("SELECT sqlite_version()", [], |r| r.get(0)))
        .unwrap_or_default();
    let env = format!(
        "SQLite {version}, {} {}, {} build, journal WAL, synchronous FULL; rows of {mean_row} B mean, generated in {}; the stage-1 merges took {} steps",
        std::env::consts::OS,
        std::env::consts::ARCH,
        if cfg!(debug_assertions) {
            "debug"
        } else {
            "release"
        },
        fmt_ms(ms(gen_time)),
        build_merges.n,
    );
    let md = r.markdown(&env);
    println!("{md}");
    if let Ok(out) = std::env::var("HISTORY_BENCH_OUT") {
        use std::io::Write as _;
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(PathBuf::from(out))
            .expect("the output file");
        f.write_all(md.as_bytes()).expect("writes");
    }
    if std::env::var("HISTORY_BENCH_STRICT").is_ok_and(|v| v == "1") {
        let over: Vec<String> = r.over().iter().map(|l| l.measure.clone()).collect();
        assert!(over.is_empty(), "over budget: {over:?}");
    }
}

#[test]
#[ignore = "a benchmark: run in release with --ignored"]
fn native_5k() {
    run(5_000);
}

#[test]
#[ignore = "a benchmark: run in release with --ignored"]
fn native_20k() {
    run(20_000);
}

#[test]
#[ignore = "a benchmark: run in release with --ignored"]
fn native_100k() {
    run(100_000);
}
