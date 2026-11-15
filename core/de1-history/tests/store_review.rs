//! REVIEW-439's notes 1 to 5, each as a test: exclusive locking before the
//! first read on a VFS without shared memory, the journal mode checked, a
//! corrupt `shot` read by the rebuild going through the open-time checks, a
//! poisoned store reporting `Corrupt` first, and an index that disagrees
//! with its table found by `integrity_check`.

mod common;

use std::fs;
use std::io::{Seek, SeekFrom, Write as _};
use std::path::Path;

use common::{dir, file, int, row, rows_in};
use de1_history::store::{JournalMode, Maintained, OpenOptions, Role, Store, StoreError};
use serde_json::json;

/// SQLite's own `unix-dotfile` VFS has no shared memory, like `opfs-sahpool`.
fn no_shm(journal: JournalMode, create: bool) -> OpenOptions {
    let mut o = OpenOptions::new(Role::Authority);
    if create {
        o = o.create(1);
    }
    o.journal = journal;
    o.vfs = Some("unix-dotfile".into());
    o
}

fn mode(store: &Store) -> String {
    store
        .raw()
        .query_row("PRAGMA journal_mode", [], |r| r.get(0))
        .expect("a mode")
}

#[cfg(unix)]
#[test]
fn exclusive_wal_reopens_on_a_vfs_without_shared_memory() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut s = Store::open(&path, no_shm(JournalMode::ExclusiveWal, true)).expect("creates");
    s.upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    assert_eq!(mode(&s), "wal");
    s.close().expect("closes");
    for i in 0..3 {
        let mut s = Store::open(&path, no_shm(JournalMode::ExclusiveWal, false)).expect("reopens");
        assert_eq!(mode(&s), "wal");
        assert_eq!(s.count(&Default::default()).expect("reads"), i + 1);
        s.upsert(&rows_in(&[row(&format!("b{i}"), 2, json!({}))]))
            .expect("writes");
        s.close().expect("closes");
    }
}

#[cfg(unix)]
#[test]
fn a_journal_mode_the_file_wont_take_is_refused() {
    let d = dir();
    let path = file(&d, "history.db");
    // WAL without exclusive locking needs shared memory: SQLite answers
    // with the mode it kept.
    let err = Store::open(&path, no_shm(JournalMode::Wal, true)).expect_err("refused");
    assert!(
        matches!(err, StoreError::JournalMode { ref asked, .. } if asked == "wal"),
        "{err:?}"
    );
    // Nothing was written: no schema, so a later create starts clean.
    let s = Store::open(&path, no_shm(JournalMode::Truncate, true)).expect("creates");
    assert_eq!(mode(&s), "truncate");
}

fn truncate_store(path: &Path) -> Store {
    let mut o = OpenOptions::new(Role::Authority).create(1);
    o.journal = JournalMode::Truncate;
    Store::open(path, o).expect("opens")
}

/// The pages of table or index `name` of type `kind`, from `dbstat`.
fn pages_of(store: &Store, name: &str, kind: &str) -> Vec<u64> {
    let mut st = store
        .raw()
        .prepare("SELECT pageno FROM dbstat WHERE name = ?1 AND pagetype = ?2 ORDER BY pageno")
        .expect("dbstat");
    st.query_map((name, kind), |r| r.get::<_, i64>(0))
        .expect("rows")
        .map(|r| u64::try_from(r.expect("a row")).expect("a page"))
        .collect()
}

/// Overwrites bytes and moves the change counter, so the store's page cache
/// is dropped.
fn smash(path: &Path, offset: u64, bytes: &[u8]) {
    let mut f = fs::OpenOptions::new()
        .write(true)
        .open(path)
        .expect("opens");
    f.seek(SeekFrom::Start(offset)).expect("seeks");
    f.write_all(bytes).expect("writes");
    f.seek(SeekFrom::Start(24)).expect("seeks");
    f.write_all(&[0x7f, 0x7f, 0x7f, 0x7f]).expect("bumps");
}

#[test]
fn a_corrupt_shot_page_only_the_rebuild_reads_goes_through_quick_check() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut s = truncate_store(&path);
    let rows: Vec<String> = (0..40)
        .map(|i| {
            row(
                &format!("r{i:03}"),
                i,
                json!({ "metadata": { "notes": "y".repeat(9000) } }),
            )
        })
        .collect();
    s.upsert(&rows_in(&rows)).expect("writes");
    let ps = u64::try_from(int(&s, "PRAGMA page_size")).expect("a size");
    let over = pages_of(&s, "shot", "overflow");
    let victim = over[over.len() / 2];
    // The overflow page's next-page pointer: garbage.
    smash(&path, (victim - 1) * ps, &[0x7f, 0xff, 0xff, 0xff]);
    s.start_fts_rebuild().expect("starts");
    let mut last = None;
    for _ in 0..6 {
        match s.maintain() {
            Ok(Maintained::FtsBatch { .. } | Maintained::FtsStarted) => {}
            other => {
                last = Some(other);
                break;
            }
        }
    }
    // Not a loop of dropping and restarting the index: the reopen's
    // quick_check found the table damaged.
    let last = last.expect("stopped");
    assert!(matches!(last, Err(StoreError::Corrupt(_))), "{last:?}");
    assert!(matches!(s.maintain(), Err(StoreError::Poisoned(_))));
}

#[test]
fn a_poisoned_store_reports_corrupt_first() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut s = truncate_store(&path);
    let rows: Vec<String> = (0..200)
        .map(|i| {
            row(
                &format!("r{i:03}"),
                i,
                json!({ "metadata": { "notes": "x".repeat(300) } }),
            )
        })
        .collect();
    s.upsert(&rows_in(&rows)).expect("writes");
    let ps = u64::try_from(int(&s, "PRAGMA page_size")).expect("a size");
    let leaves = pages_of(&s, "shot", "leaf");
    let victim = leaves[leaves.len() / 2];
    smash(
        &path,
        (victim - 1) * ps,
        &vec![0xa5; usize::try_from(ps).expect("a size")],
    );
    s.start_fts_rebuild().expect("starts");
    let first = s.maintain();
    assert!(matches!(first, Err(StoreError::Corrupt(_))), "{first:?}");
    let second = s.maintain();
    assert!(
        matches!(second, Err(StoreError::Poisoned(ref e)) if matches!(**e, StoreError::Corrupt(_))),
        "{second:?}"
    );
}

#[test]
fn an_index_that_disagrees_with_its_table_is_found_by_integrity_check() {
    let d = dir();
    let path = file(&d, "history.db");
    {
        let mut s = truncate_store(&path);
        s.upsert(&rows_in(&[
            row("a", 1, json!({ "visualizerId": "v-1" })),
            row("b", 2, json!({})),
        ]))
        .expect("writes");
        s.close().expect("closes");
    }
    {
        // The index's definition no longer matches its entries.
        let c = rusqlite::Connection::open(&path).expect("opens");
        c.execute_batch(
            "PRAGMA writable_schema = ON;
             UPDATE sqlite_schema SET sql = 'CREATE INDEX shot_by_vid ON shot(id) WHERE visualizer_id IS NOT NULL'
               WHERE name = 'shot_by_vid';
             PRAGMA writable_schema = OFF;",
        )
        .expect("rewrites");
    }
    let mut o = OpenOptions::new(Role::Authority);
    o.journal = JournalMode::Truncate;
    let mut s = Store::open(&path, o).expect("opens");
    // Not `Reopened` forever: the reopen ran integrity_check.
    let err = s.delete(&["a".to_owned()]).expect_err("fails");
    assert!(
        matches!(err, StoreError::Corrupt(ref m) if m.starts_with("integrity_check")),
        "{err:?}"
    );
    assert!(matches!(
        s.delete(&["a".to_owned()]),
        Err(StoreError::Poisoned(_))
    ));
}
