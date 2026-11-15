//! The store's open-time checks and error classes (SPEC §11, M6, S4), on real
//! files in temp dirs.

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

#[cfg(not(target_arch = "wasm32"))]
use std::fs;
#[cfg(not(target_arch = "wasm32"))]
use std::io::{Seek, SeekFrom, Write as _};

use common::{create, dir, file, int, reopen, row, rows_in};
use de1_history::schema::{APPLICATION_ID, SCHEMA_V};
use de1_history::store::{JournalMode, OpenOptions, Role, RowIn, Store, StoreError};
use serde_json::json;

#[test]
fn a_new_file_gets_the_schema_its_ids_and_versions() {
    let d = dir();
    let path = file(&d, "history.db");
    let store = Store::open(&path, OpenOptions::new(Role::Authority).create(3)).expect("opens");
    let info = store.info().clone();
    assert_eq!(info.schema_v, SCHEMA_V);
    assert_eq!((info.min_reader_v, info.min_writer_v), (1, 1));
    assert_eq!(info.role, Role::Authority);
    assert_eq!(info.generation, 3);
    assert_eq!(store.write_seq(), 0);
    assert_eq!(
        int(&store, "PRAGMA application_id"),
        i64::from(APPLICATION_ID)
    );
    assert_eq!(int(&store, "PRAGMA foreign_keys"), 1);
    assert_eq!(int(&store, "PRAGMA recursive_triggers"), 0);
    // temp_store = MEMORY is 2.
    assert_eq!(int(&store, "PRAGMA temp_store"), 2);
    // synchronous = FULL is 2.
    assert_eq!(int(&store, "PRAGMA synchronous"), 2);
    let mode: String = store
        .raw()
        .query_row("PRAGMA journal_mode", [], |r| r.get(0))
        .expect("a mode");
    assert_eq!(mode, "wal");
    let health = store.health();
    assert!(health.writable && !health.reindexing && !health.derive_pending);
    store.close().expect("closes");

    let again = reopen(&path);
    assert_eq!(again.info(), &info);
}

#[test]
fn the_journal_mode_is_the_shells_choice() {
    let d = dir();
    for (journal, want) in [
        (JournalMode::Truncate, "truncate"),
        (JournalMode::ExclusiveWal, "wal"),
    ] {
        let path = file(&d, &format!("{want}-{journal:?}.db"));
        let mut opts = OpenOptions::new(Role::Index).create(0);
        opts.journal = journal;
        let store = Store::open(&path, opts).expect("opens");
        let mode: String = store
            .raw()
            .query_row("PRAGMA journal_mode", [], |r| r.get(0))
            .expect("a mode");
        assert_eq!(mode, want);
    }
}

#[test]
fn a_missing_file_without_create_is_cant_open_and_nothing_is_made() {
    let d = dir();
    let path = file(&d, "history.db");
    let err = Store::open(&path, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert!(matches!(err, StoreError::CantOpen(_)), "{err:?}");
    // On disk: no file made, and a missing directory is refused too.
    // (`memvfs`, on wasm32, has no directories.)
    #[cfg(not(target_arch = "wasm32"))]
    {
        assert!(!path.exists());
        let err = Store::open(
            d.path().join("nope/history.db"),
            OpenOptions::new(Role::Authority).create(1),
        )
        .expect_err("refused");
        assert!(matches!(err, StoreError::CantOpen(_)), "{err:?}");
    }
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn an_empty_file_without_create_is_foreign_and_stays_empty() {
    let d = dir();
    let path = file(&d, "history.db");
    fs::write(&path, b"").expect("writes");
    let err = Store::open(&path, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert!(matches!(err, StoreError::Foreign(_)), "{err:?}");
    assert_eq!(fs::metadata(&path).expect("exists").len(), 0);
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn another_application_id_is_foreign_and_untouched() {
    let d = dir();
    let path = file(&d, "other.db");
    {
        let c = common::raw_conn(&path);
        c.execute_batch("PRAGMA application_id = 1234; CREATE TABLE t (x);")
            .expect("writes");
    }
    let before = fs::read(&path).expect("reads");
    for create in [false, true] {
        let mut opts = OpenOptions::new(Role::Authority);
        opts.create = create;
        let err = Store::open(&path, opts).expect_err("refused");
        assert!(matches!(err, StoreError::Foreign(_)), "{err:?}");
    }
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
}

#[test]
fn our_id_without_meta_is_foreign() {
    let d = dir();
    let path = file(&d, "half.db");
    {
        let c = common::raw_conn(&path);
        c.execute_batch(&format!(
            "PRAGMA application_id = {APPLICATION_ID}; CREATE TABLE shot (x);"
        ))
        .expect("writes");
    }
    let err = Store::open(&path, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert!(matches!(err, StoreError::Foreign(_)), "{err:?}");
}

/// Sets one `meta` value behind the store's back.
fn set_meta(path: &std::path::Path, key: &str, value: &str) {
    let c = common::raw_conn(path);
    c.execute("UPDATE meta SET value = ?1 WHERE key = ?2", (value, key))
        .expect("writes");
}

#[test]
fn a_newer_min_reader_is_too_new() {
    let d = dir();
    let path = file(&d, "history.db");
    create(&path).close().expect("closes");
    set_meta(&path, "min_reader_v", &(SCHEMA_V + 1).to_string());
    let err = Store::open(&path, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert_eq!(
        err,
        StoreError::TooNew {
            min_reader_v: SCHEMA_V + 1
        }
    );
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn a_newer_min_writer_reads_but_refuses_writes() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    store.close().expect("closes");
    set_meta(&path, "min_writer_v", &(SCHEMA_V + 1).to_string());
    let before = fs::read(&path).expect("reads");

    let mut store = reopen(&path);
    assert!(!store.health().writable);
    assert_eq!(store.count(&Default::default()).expect("reads"), 1);
    let err = store
        .upsert(&rows_in(&[row("b", 2, json!({}))]))
        .expect_err("refused");
    assert_eq!(
        err,
        StoreError::ReadOnlyTooNew {
            min_writer_v: SCHEMA_V + 1
        }
    );
    assert!(matches!(
        store.delete(&["a".into()]),
        Err(StoreError::ReadOnlyTooNew { .. })
    ));
    assert!(matches!(
        store.set_live(&Default::default()),
        Err(StoreError::ReadOnlyTooNew { .. })
    ));
    store.close().expect("closes");
    // A read-only open writes nothing, not even its open mark.
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn another_role_is_refused_and_untouched() {
    let d = dir();
    let path = file(&d, "history-index.db");
    Store::open(&path, OpenOptions::new(Role::Authority).create(1))
        .expect("opens")
        .close()
        .expect("closes");
    let before = fs::read(&path).expect("reads");
    let err = Store::open(&path, OpenOptions::new(Role::Index).create(0)).expect_err("refused");
    assert_eq!(
        err,
        StoreError::WrongRole {
            found: Role::Authority,
            expected: Role::Index
        }
    );
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn an_overwritten_header_is_corrupt_and_the_file_is_left_alone() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    store.close().expect("closes");
    {
        let mut f = fs::OpenOptions::new()
            .write(true)
            .open(&path)
            .expect("opens");
        f.write_all(&[0x5a; 100]).expect("overwrites");
    }
    let before = fs::read(&path).expect("reads");
    let err = Store::open(&path, OpenOptions::new(Role::Authority).create(1)).expect_err("refused");
    assert!(matches!(err, StoreError::Corrupt(_)), "{err:?}");
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
}

#[cfg(not(target_arch = "wasm32"))]
/// A database whose pages after the first are overwritten: the header reads,
/// `quick_check` doesn't pass.
fn damaged_file(path: &std::path::Path) {
    let mut opts = OpenOptions::new(Role::Authority).create(1);
    opts.journal = JournalMode::Truncate;
    let mut store = Store::open(path, opts).expect("opens");
    let rows: Vec<String> = (0..200)
        .map(|i| {
            row(
                &format!("r{i:03}"),
                i,
                json!({ "metadata": { "notes": "x".repeat(300) } }),
            )
        })
        .collect();
    store.upsert(&rows_in(&rows)).expect("writes");
    store.close().expect("closes");
    let len = fs::metadata(path).expect("exists").len();
    let mut f = fs::OpenOptions::new()
        .write(true)
        .open(path)
        .expect("opens");
    f.seek(SeekFrom::Start(len / 2)).expect("seeks");
    f.write_all(&vec![0xa5; 8192]).expect("overwrites");
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn overwritten_pages_are_corrupt_when_quick_check_runs() {
    let d = dir();
    let path = file(&d, "history.db");
    damaged_file(&path);
    let before = fs::read(&path).expect("reads");
    let mut opts = OpenOptions::new(Role::Authority);
    opts.journal = JournalMode::Truncate;
    opts.quick_check = true;
    let err = Store::open(&path, opts).expect_err("refused");
    assert!(matches!(err, StoreError::Corrupt(_)), "{err:?}");
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
}

#[test]
fn quick_check_runs_after_an_unclean_close_and_weekly() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut opts = OpenOptions::new(Role::Authority).create(1);
    opts.now_ms = 1_000;
    Store::open(&path, opts.clone())
        .expect("opens")
        .close()
        .expect("closes");
    opts.create = false;
    // Clean, and a day later: no check.
    opts.now_ms = 1_000 + 86_400_000;
    let store = Store::open(&path, opts.clone()).expect("opens");
    assert!(!store.health().quick_checked);
    // Left open: the mark stays, and the next open checks.
    std::mem::forget(store);
    // The forgotten store still holds its claim on the file; a second store
    // in this process can't open it, so check through a copy.
    let copy = file(&d, "copy.db");
    {
        let c = common::raw_conn(&path);
        c.execute_batch(&format!("VACUUM INTO '{}'", copy.display()))
            .expect("copies");
    }
    let store = Store::open(&copy, opts.clone()).expect("opens");
    assert!(store.health().quick_checked, "an unclean close checks");
    store.close().expect("closes");
    let store = Store::open(&copy, opts.clone()).expect("opens");
    assert!(!store.health().quick_checked);
    store.close().expect("closes");
    // A week after the last check.
    opts.now_ms += 7 * 86_400_000;
    let store = Store::open(&copy, opts.clone()).expect("opens");
    assert!(store.health().quick_checked, "weekly");
    store.close().expect("closes");
    let store = Store::open(&copy, opts).expect("opens");
    assert!(!store.health().quick_checked);
}

#[test]
fn a_second_connection_to_an_open_file_is_refused_two_files_are_fine() {
    let d = dir();
    let a = file(&d, "a.db");
    let b = file(&d, "b.db");
    let store_a = create(&a);
    let err = Store::open(&a, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert!(matches!(err, StoreError::AlreadyOpen(_)), "{err:?}");
    // Another spelling of the same file is the same file.
    let other = d.path().join(".").join("a.db");
    let err = Store::open(&other, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert!(matches!(err, StoreError::AlreadyOpen(_)), "{err:?}");
    let store_b = create(&b);
    drop(store_a);
    let again = reopen(&a);
    drop(store_b);
    drop(again);
}

#[cfg(unix)]
#[test]
fn a_read_only_file_is_refused() {
    use std::os::unix::fs::PermissionsExt;
    let d = dir();
    let path = file(&d, "history.db");
    let mut opts = OpenOptions::new(Role::Authority).create(1);
    opts.journal = JournalMode::Truncate;
    Store::open(&path, opts.clone())
        .expect("opens")
        .close()
        .expect("closes");
    fs::set_permissions(&path, fs::Permissions::from_mode(0o444)).expect("chmods");
    opts.create = false;
    let err = Store::open(&path, opts).expect_err("refused");
    assert!(
        matches!(err, StoreError::ReadOnly | StoreError::CantOpen(_)),
        "{err:?}"
    );
}

#[test]
fn a_full_disk_is_full_and_writes_no_partial_row() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    let pages = int(&store, "PRAGMA page_count");
    store
        .raw()
        .query_row(
            &format!("PRAGMA max_page_count = {}", pages + 1),
            [],
            |_| Ok(()),
        )
        .expect("caps");
    let big: Vec<RowIn> = (0..50)
        .map(|i| {
            RowIn::new(row(
                &format!("big{i}"),
                10 + i,
                json!({ "metadata": { "notes": "n".repeat(4000) } }),
            ))
        })
        .collect();
    let err = store.upsert(&big).expect_err("full");
    assert_eq!(err, StoreError::Full);
    assert_eq!(int(&store, "SELECT count(*) FROM shot"), 1);
    assert_eq!(int(&store, "SELECT count(*) FROM shot_tag"), 0);
    assert_eq!(store.write_seq(), 1);
    // The store goes on: a small write still fits.
    store
        .raw()
        .query_row("PRAGMA max_page_count = 1000000", [], |_| Ok(()))
        .expect("uncaps");
    store
        .upsert(&rows_in(&[row("b", 2, json!({}))]))
        .expect("writes");
    assert_eq!(store.write_seq(), 2);
}

#[cfg(not(target_arch = "wasm32"))]
#[test]
fn corruption_found_mid_session_reopens_and_the_open_checks_decide() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut opts = OpenOptions::new(Role::Authority).create(1);
    opts.journal = JournalMode::Truncate;
    let mut store = Store::open(&path, opts).expect("opens");
    let rows: Vec<String> = (0..200)
        .map(|i| {
            row(
                &format!("r{i:03}"),
                i,
                json!({ "metadata": { "notes": "x".repeat(300) } }),
            )
        })
        .collect();
    store.upsert(&rows_in(&rows)).expect("writes");
    // Damage the middle of the file while the store holds it, and move the
    // change counter so its page cache is dropped.
    let len = fs::metadata(&path).expect("exists").len();
    {
        let mut f = fs::OpenOptions::new()
            .write(true)
            .open(&path)
            .expect("opens");
        f.seek(SeekFrom::Start(len / 2)).expect("seeks");
        f.write_all(&vec![0xa5; 8192]).expect("overwrites");
        f.seek(SeekFrom::Start(24)).expect("seeks");
        f.write_all(&[0x7f, 0x7f, 0x7f, 0x7f]).expect("bumps");
    }
    let before = fs::read(&path).expect("reads");
    // An edit of every row reads every page.
    let edits: Vec<String> = (0..200)
        .map(|i| {
            row(
                &format!("r{i:03}"),
                i,
                json!({ "metadata": { "notes": "edited" } }),
            )
        })
        .collect();
    let err = store.upsert(&rows_in(&edits)).expect_err("fails");
    // The reopen's checks decided: `Corrupt`, and the store, which only a
    // failed reopen poisons, is unusable now. The file was left as it was.
    assert!(matches!(err, StoreError::Corrupt(_)), "{err:?}");
    assert!(matches!(
        store.count(&Default::default()),
        Err(StoreError::Poisoned(_))
    ));
    drop(store);
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
}

#[test]
fn the_sqlite_version_is_the_one_both_builds_pin() {
    // The same test runs natively and on wasm32 (PLAN C4): a bump that splits
    // the two SQLites fails one of them.
    assert_eq!(
        de1_history::store::sqlite_version(),
        de1_history::store::SQLITE_VERSION
    );
}

#[test]
fn a_second_spelling_of_an_open_file_is_refused() {
    let d = dir();
    let path = file(&d, "history.db");
    let _held = create(&path);
    // `.` and doubled slashes name the same file (REVIEW-439 note 8).
    let other = std::path::PathBuf::from(format!("{}/.//history.db", d.path().display()));
    let err = Store::open(&other, OpenOptions::new(Role::Authority)).expect_err("refused");
    assert!(matches!(err, StoreError::AlreadyOpen(_)), "{err:?}");
}
