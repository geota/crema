//! The pools on real OPFS (PLAN C4): headless Chrome, in a dedicated Worker,
//! through `sahpool`. M7's two pools side by side, a backup copy between
//! them, and an index pool that won't install leaving every `crema-db` file
//! byte-identical; S5's repair and capacity; S1's pause; C2's corrupt class
//! on this VFS.
//!
//! Each test uses its own repair generations, so tests share one OPFS
//! without sharing a directory, and uninstalls its pools when done.
//!
//! Run: `CHROMEDRIVER=… cargo test -p de1-history-wasm --target
//! wasm32-unknown-unknown --test browser` (`core/de1-history-wasm/README.md`).

#![cfg(all(target_family = "wasm", target_os = "unknown"))]

use de1_history_wasm::db::HistoryDb;
use de1_history_wasm::pools::{self, Which};
use de1_history_wasm::repair::{self, SLOTS_DIR};
use js_sys::Promise;
use serde_json::{Value, json};
use wasm_bindgen::JsValue;
use wasm_bindgen_futures::JsFuture;
use wasm_bindgen_test::{wasm_bindgen_test as test, wasm_bindgen_test_configure};

wasm_bindgen_test_configure!(run_in_dedicated_worker);

fn parse_js(v: &JsValue) -> Value {
    match v.as_string() {
        Some(s) => serde_json::from_str(&s).unwrap_or(Value::String(s)),
        None => Value::Null,
    }
}

async fn wait(p: Promise) -> Result<Value, Value> {
    match JsFuture::from(p).await {
        Ok(v) => Ok(parse_js(&v)),
        Err(e) => Err(parse_js(&e)),
    }
}

fn sync(r: Result<String, JsValue>) -> Value {
    match r {
        Ok(s) => serde_json::from_str(&s).expect("JSON"),
        Err(e) => panic!("{:?}", parse_js(&e)),
    }
}

fn row(id: &str, at: i64) -> Value {
    json!({ "rowJson": json!({
        "id": id, "completedAt": at, "profileName": "Turbo",
        "metadata": { "dose": 18.0, "yieldOut": 36.0, "rating": 3, "notes": "x".repeat(400) },
        "record": { "duration": 30000 }, "tags": [],
        "summary": { "v": 1, "sampleCount": 120, "finalWeight": 36.0 }
    }).to_string() })
}

fn rows(prefix: &str, n: i64) -> String {
    Value::Array((0..n).map(|i| row(&format!("{prefix}{i}"), i)).collect()).to_string()
}

async fn open(v: Value) -> HistoryDb {
    HistoryDb::opened(&v.to_string())
        .await
        .unwrap_or_else(|e| panic!("opens: {:?}", parse_js(&e)))
}

/// A new index file in `crema-index`, with `n` rows.
async fn new_index(file: &str, n: i64) -> HistoryDb {
    let db = open(
        json!({ "place": "index", "file": file, "role": "index", "create": true,
                          "generation": 1, "journal": "truncate" }),
    )
    .await;
    if n > 0 {
        wait(db.upsert(&rows("i", n))).await.expect("writes");
    }
    db
}

/// A new truth file in `crema-db` (a recovery file, the one way besides a
/// copy that one begins), with `n` rows.
async fn new_truth(file: &str, n: i64) -> HistoryDb {
    let db = HistoryDb::recovered(
        &json!({ "place": "db", "file": file, "generation": 2, "journal": "truncate",
                 "recovery": { "migrationSource": [], "lateBaseline": "{}" } })
        .to_string(),
    )
    .await
    .unwrap_or_else(|e| panic!("creates: {:?}", parse_js(&e)));
    if n > 0 {
        wait(db.upsert(&rows("t", n))).await.expect("writes");
    }
    db
}

async fn install(which: Which, generation: u32) {
    pools::install(which, generation)
        .await
        .unwrap_or_else(|e| panic!("installs {which:?}: {e:?}"));
}

/// Clears what an earlier failed test may have left installed.
fn fresh() {
    let _ = pools::uninstall(Which::Index);
    let _ = pools::uninstall(Which::Db);
}

async fn snap(dir: &str) -> Vec<(String, Vec<u8>)> {
    repair::snapshot(dir)
        .await
        .expect("reads")
        .unwrap_or_else(|| panic!("{dir} exists"))
}

/// A slot `sahpool` refuses: its name field isn't NUL-terminated (S5).
async fn plant_invalid_slot(dir: &str) {
    repair::write_new(
        &format!("{dir}/{SLOTS_DIR}"),
        "PLANTEDBADSLOT",
        &[0x41; 8192],
    )
    .await
    .expect("plants");
}

fn code(e: &Value) -> &str {
    e["code"].as_str().unwrap_or("")
}

/// Rows in `file` of `vfs`, read through a raw read-only connection.
fn raw_count(vfs: &str, file: &str) -> i64 {
    let c = rusqlite::Connection::open_with_flags_and_vfs(
        file,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
        vfs,
    )
    .expect("opens");
    c.query_row("SELECT count(*) FROM shot", [], |r| r.get(0))
        .expect("counts")
}

#[test]
async fn both_pools_install_side_by_side_and_copy_from_one_to_the_other() {
    fresh();
    install(Which::Index, 11).await;
    install(Which::Db, 11).await;
    let index = pools::info(Which::Index).expect("info");
    let db = pools::info(Which::Db).expect("info");
    assert_eq!(
        (index.vfs, index.dir.as_str()),
        ("crema-index", ".crema-history-index-11")
    );
    assert_eq!(
        (db.vfs, db.dir.as_str()),
        ("crema-db", ".crema-history-db-11")
    );
    assert!(index.capacity >= 6 && db.capacity >= 16, "{index:?} {db:?}");

    let src = new_index("history-index.db", 3).await;
    // S4 on this VFS: one connection per file.
    let second = HistoryDb::opened(
        &json!({ "place": "index", "file": "history-index.db", "role": "index",
                 "journal": "truncate" })
        .to_string(),
    )
    .await;
    assert_eq!(
        code(&parse_js(&second.err().expect("refused"))),
        "alreadyOpen"
    );
    // S1: a pool with a file open won't pause.
    let busy = pools::pause(Which::Index).expect_err("refused");
    assert_eq!(busy.code, "filesInUse");

    // The flip's copy: from one pool's VFS to the other's (SPEC §8, S4).
    wait(src.copy_to(
        &json!({ "place": "db", "file": "history.db", "journal": "truncate" }).to_string(),
    ))
    .await
    .expect("copies");
    // And a snapshot, truth onto a new file, within `crema-db`.
    let truth = new_truth("history-2.db", 2).await;
    wait(
        truth.copy_to(
            &json!({ "place": "db", "file": "history-2-snapshot.db", "journal": "truncate" })
                .to_string(),
        ),
    )
    .await
    .expect("snapshots");
    src.close().expect("closes");
    truth.close().expect("closes");

    assert_eq!(raw_count("crema-db", "/history.db"), 3);
    assert_eq!(raw_count("crema-db", "/history-2-snapshot.db"), 2);
    let index = pools::info(Which::Index).expect("info");
    let db = pools::info(Which::Db).expect("info");
    assert!(
        index.files.contains(&"/history-index.db".to_owned()),
        "{index:?}"
    );
    assert!(
        !index.files.iter().any(|f| f.starts_with("/history.db")),
        "{index:?}"
    );
    for f in ["/history.db", "/history-2.db", "/history-2-snapshot.db"] {
        assert!(db.files.contains(&f.to_owned()), "{f} in {db:?}");
    }

    // S1: closed, it pauses, and resumes with every file.
    pools::pause(Which::Index).expect("pauses");
    assert!(pools::info(Which::Index).expect("info").paused);
    pools::resume(Which::Index).await.expect("resumes");
    let again = open(
        json!({ "place": "index", "file": "history-index.db", "role": "index",
                             "journal": "truncate" }),
    )
    .await;
    assert_eq!(sync(again.count("{}")), json!(3));
    again.close().expect("closes");
    pools::uninstall(Which::Index).expect("uninstalls");
    pools::uninstall(Which::Db).expect("uninstalls");
}

#[test]
async fn an_index_pool_that_wont_install_leaves_every_db_file_byte_identical() {
    fresh();
    install(Which::Index, 12).await;
    install(Which::Db, 12).await;
    let src = new_index("history-index.db", 5).await;
    wait(src.copy_to(
        &json!({ "place": "db", "file": "history.db", "journal": "truncate" }).to_string(),
    ))
    .await
    .expect("copies");
    src.close().expect("closes");
    new_truth("history-2.db", 4).await.close().expect("closes");
    pools::uninstall(Which::Index).expect("uninstalls");
    pools::uninstall(Which::Db).expect("uninstalls");

    let db_before = snap(".crema-history-db-12").await;
    assert!(db_before.len() > 4, "{} files", db_before.len());
    plant_invalid_slot(".crema-history-index-12").await;
    let index_before = snap(".crema-history-index-12").await;

    // The Worker has `crema-db` installed when the index pool fails.
    install(Which::Db, 12).await;
    let err = pools::install(Which::Index, 12)
        .await
        .expect_err("an invalid header fails the install");
    assert_eq!(err.code, "invalidHeader", "{err:?}");
    // Every install after fails the same way: nothing was cleared.
    let err = pools::install(Which::Index, 12).await.expect_err("again");
    assert_eq!(err.code, "invalidHeader", "{err:?}");
    pools::uninstall(Which::Db).expect("uninstalls");

    assert!(
        snap(".crema-history-db-12").await == db_before,
        "a crema-db file changed"
    );
    assert!(
        snap(".crema-history-index-12").await == index_before,
        "the failed install changed the index pool"
    );
}

#[test]
async fn repair_copies_the_database_out_of_a_pool_with_an_invalid_slot() {
    fresh();
    install(Which::Db, 13).await;
    let truth = new_truth("history-2.db", 7).await;
    truth.close().expect("closes");
    pools::uninstall(Which::Db).expect("uninstalls");
    plant_invalid_slot(".crema-history-db-13").await;
    let err = pools::install(Which::Db, 13).await.expect_err("refused");
    assert_eq!(err.code, "invalidHeader");

    let source = snap(".crema-history-db-13").await;
    let report = repair::repair(Which::Db, 13, 14).await.expect("repairs");
    assert_eq!(report.from, ".crema-history-db-13");
    assert_eq!(report.to, ".crema-history-db-14");
    assert!(
        report.copied.contains(&"/history-2.db".to_owned()),
        "{report:?}"
    );
    let planted = report
        .slots
        .iter()
        .find(|s| s.opaque == "PLANTEDBADSLOT")
        .expect("reported");
    assert!(
        planted
            .skipped
            .as_deref()
            .is_some_and(|s| s.starts_with("invalid")),
        "{planted:?}"
    );
    // An invalid slot might have been anything, the database too: the
    // report says so, and the shell checks `copied` (REVIEW-444 note 2).
    assert!(report.incomplete, "{report:?}");
    assert!(
        report.problems.iter().any(|p| p.contains("PLANTEDBADSLOT")),
        "{report:?}"
    );
    // The source is unchanged: nothing deleted, nothing written.
    assert!(
        snap(".crema-history-db-13").await == source,
        "repair changed its source"
    );
    // The done file is there, holding the report (REVIEW-444 note 3).
    let target = snap(".crema-history-db-14").await;
    let done = target
        .iter()
        .find(|(p, _)| p == repair::REPAIR_DONE)
        .expect("a done file");
    let done: Value = serde_json::from_slice(&done.1).expect("JSON");
    assert_eq!(done["copied"], json!(report.copied));
    // The new generation installs and holds the database whole.
    install(Which::Db, 14).await;
    let repaired = open(
        json!({ "place": "db", "file": "history-2.db", "role": "authority",
                                "journal": "truncate", "quickCheck": true }),
    )
    .await;
    assert_eq!(sync(repaired.count("{}")), json!(7));
    assert_eq!(sync(repaired.health())["quickChecked"], json!(true));
    repaired.close().expect("closes");
    pools::uninstall(Which::Db).expect("uninstalls");
    // A target that holds anything is never written over.
    let err = repair::repair(Which::Db, 13, 14)
        .await
        .expect_err("refused");
    assert_eq!(err.code, "targetExists");
}

#[test]
async fn a_full_pool_adds_capacity_and_the_write_goes_through() {
    fresh();
    install(Which::Index, 15).await;
    let start = pools::info(Which::Index).expect("info").capacity;
    assert_eq!(start, 6);
    // Each file takes a slot and its journal another: six slots fill fast.
    let mut open_dbs = Vec::new();
    for i in 0..6 {
        let db = new_index(&format!("full-{i}.db"), 1).await;
        open_dbs.push(db);
    }
    let after = pools::info(Which::Index).expect("info");
    assert!(after.capacity > start, "{after:?}");
    assert!(after.files.len() >= 12, "{after:?}");
    for db in &open_dbs {
        assert_eq!(sync(db.count("{}")), json!(1));
    }
    for db in open_dbs {
        db.close().expect("closes");
    }
    // Capacity is never reduced: closing gives no slot back.
    assert_eq!(
        pools::info(Which::Index).expect("info").capacity,
        after.capacity
    );
    pools::uninstall(Which::Index).expect("uninstalls");
}

#[test]
async fn overwritten_bytes_are_corrupt_on_this_vfs_and_left_alone() {
    // C2's error class on `sahpool`: the slot is copied into a new
    // generation with its database header overwritten, since nothing here
    // writes over a file.
    fresh();
    install(Which::Db, 16).await;
    new_truth("history-2.db", 3).await.close().expect("closes");
    pools::uninstall(Which::Db).expect("uninstalls");
    let slots = snap(".crema-history-db-16").await;
    for (path, bytes) in &slots {
        let Some(opaque) = path.strip_prefix(&format!("{SLOTS_DIR}/")) else {
            continue;
        };
        let mut bytes = bytes.clone();
        if repair::read_header(bytes.len() as u64, &bytes[..bytes.len().min(4096)])
            == repair::Slot::Named("/history-2.db".into())
        {
            // The database's own first 100 bytes, after the slot header.
            bytes[4096..4196].fill(0x5a);
        }
        repair::write_new(&format!(".crema-history-db-17/{SLOTS_DIR}"), opaque, &bytes)
            .await
            .expect("writes");
    }
    // A first install adds the lock file and the free slots; the file under
    // test is compared from after it.
    install(Which::Db, 17).await;
    pools::uninstall(Which::Db).expect("uninstalls");
    let before = snap(".crema-history-db-17").await;
    install(Which::Db, 17).await;
    let err = HistoryDb::opened(
        &json!({ "place": "db", "file": "history-2.db", "role": "authority",
                 "journal": "truncate" })
        .to_string(),
    )
    .await
    .err()
    .expect("refused");
    assert_eq!(code(&parse_js(&err)), "corrupt");
    pools::uninstall(Which::Db).expect("uninstalls");
    assert!(
        snap(".crema-history-db-17").await == before,
        "a refused open changed the file"
    );
}

#[test]
async fn repair_never_copies_a_name_two_slots_claim_nor_its_orphaned_journal() {
    // REVIEW-444 P3: two slots claim `/history-2.db`. Neither is copied,
    // nor is its journal, and the report is incomplete.
    fresh();
    install(Which::Db, 20).await;
    let truth = new_truth("history-2.db", 3).await;
    truth.close().expect("closes");
    pools::uninstall(Which::Db).expect("uninstalls");
    let slots = snap(".crema-history-db-20").await;
    let mut journal = false;
    for (path, bytes) in &slots {
        let Some(opaque) = path.strip_prefix(&format!("{SLOTS_DIR}/")) else {
            continue;
        };
        let dir = format!(".crema-history-db-21/{SLOTS_DIR}");
        repair::write_new(&dir, opaque, bytes)
            .await
            .expect("writes");
        match repair::read_header(bytes.len() as u64, &bytes[..bytes.len().min(4096)]) {
            repair::Slot::Named(n) if n == "/history-2.db" => {
                repair::write_new(&dir, "DUPLICATEDSLOT", bytes)
                    .await
                    .expect("writes");
            }
            repair::Slot::Named(n) if n == "/history-2.db-journal" => journal = true,
            _ => {}
        }
    }
    assert!(journal, "truncate mode keeps the journal file");
    let err = pools::install(Which::Db, 21).await.expect_err("refused");
    assert_eq!(err.code, "invalidHeader", "{err:?}");
    let source = snap(".crema-history-db-21").await;
    let report = repair::repair(Which::Db, 21, 22).await.expect("repairs");
    assert!(report.incomplete, "{report:?}");
    assert!(
        report.problems.iter().any(|p| p.contains("/history-2.db")),
        "{report:?}"
    );
    assert!(
        !report.copied.iter().any(|n| n.starts_with("/history-2.db")),
        "{report:?}"
    );
    let orphan = report
        .slots
        .iter()
        .find(|s| s.name.as_deref() == Some("/history-2.db-journal"))
        .expect("reported");
    assert_eq!(orphan.skipped.as_deref(), Some("orphan"));
    assert!(
        snap(".crema-history-db-21").await == source,
        "repair changed its source"
    );
}

#[test]
async fn repair_refuses_a_pool_a_worker_holds() {
    // REVIEW-444 note 4: repair takes the source's `.lock`, which an
    // installed pool holds, so it never copies a pool in motion.
    fresh();
    install(Which::Db, 23).await;
    new_truth("history-2.db", 2).await.close().expect("closes");
    let before = snap(".crema-history-db-23").await;
    let err = repair::repair(Which::Db, 23, 24)
        .await
        .expect_err("refused");
    assert_eq!(err.code, "sourceInUse", "{err:?}");
    assert!(
        repair::snapshot(".crema-history-db-24")
            .await
            .expect("reads")
            .is_none(),
        "no target made"
    );
    pools::uninstall(Which::Db).expect("uninstalls");
    assert!(
        snap(".crema-history-db-23").await == before,
        "the source changed"
    );
    // Released, the same repair runs, and the pool still installs after.
    let report = repair::repair(Which::Db, 23, 24).await.expect("repairs");
    assert!(!report.incomplete, "{report:?}");
    assert!(
        report.copied.contains(&"/history-2.db".to_owned()),
        "{report:?}"
    );
    install(Which::Db, 23).await;
    pools::uninstall(Which::Db).expect("uninstalls");
}
