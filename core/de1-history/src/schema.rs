//! The schema (SPEC §4), as text, which the store (`store` feature) runs.
//!
//! `shot` holds `row_json`, the only source of truth in the table, and the
//! columns [`crate::derive`] fills from it. `hold_delete`, `created_seq` and
//! `changed_seq` are state, written by the store, never derived (S6).
//! `shot_heavy` is truth on Android after the flip (M10): no foreign key here
//! cascades, so a stray `INSERT OR REPLACE` or a parent-only delete fails on
//! the key instead of emptying it.

/// `PRAGMA application_id`: "CRMH", so a stray file is recognised.
pub const APPLICATION_ID: i32 = 0x4352_4D48;

/// The schema version this build writes.
pub const SCHEMA_V: u32 = 1;

/// The tables and indexes, without the search index.
macro_rules! tables_v1 {
    () => {
        "CREATE TABLE shot (
  rid           INTEGER PRIMARY KEY,
  id            TEXT NOT NULL UNIQUE,
  row_json      TEXT NOT NULL,
  created_seq   INTEGER NOT NULL,
  changed_seq   INTEGER NOT NULL,
  hold_delete   INTEGER NOT NULL DEFAULT 0,
  derive_v      INTEGER NOT NULL,
  completed_at  INTEGER NOT NULL,
  deleted_at    INTEGER,
  pinned        INTEGER NOT NULL,
  is_brew_log   INTEGER NOT NULL,
  method        TEXT NOT NULL,
  family        TEXT,
  profile_name  TEXT,
  bean_id       TEXT,
  coffee_key    TEXT,
  has_tags      INTEGER NOT NULL,
  visualizer_id TEXT,
  decent_id     TEXT,
  sample_count  INTEGER,
  sort_rating   INTEGER NOT NULL,
  sort_profile  TEXT NOT NULL,
  sort_bean     TEXT NOT NULL,
  sort_yield    REAL NOT NULL,
  sort_time     INTEGER NOT NULL,
  st_dose       REAL,
  st_yield      REAL,
  st_ratio      REAL,
  st_time_s     REAL,
  st_rating     REAL,
  search_text   TEXT NOT NULL
) STRICT;
CREATE INDEX shot_by_date ON shot(completed_at DESC, id DESC) WHERE deleted_at IS NULL AND hold_delete = 0;
-- One per non-date sort (B1): ties run the sort's way, so each serves both
-- directions. Spelled with the list's partial condition, as shot_by_date.
CREATE INDEX shot_sort_rating  ON shot(sort_rating DESC, completed_at DESC, id DESC) WHERE deleted_at IS NULL AND hold_delete = 0;
CREATE INDEX shot_sort_profile ON shot(sort_profile DESC, completed_at DESC, id DESC) WHERE deleted_at IS NULL AND hold_delete = 0;
CREATE INDEX shot_sort_bean    ON shot(sort_bean DESC, completed_at DESC, id DESC) WHERE deleted_at IS NULL AND hold_delete = 0;
CREATE INDEX shot_sort_yield   ON shot(sort_yield DESC, completed_at DESC, id DESC) WHERE deleted_at IS NULL AND hold_delete = 0;
CREATE INDEX shot_sort_time    ON shot(sort_time DESC, completed_at DESC, id DESC) WHERE deleted_at IS NULL AND hold_delete = 0;
CREATE INDEX shot_by_vid  ON shot(visualizer_id) WHERE visualizer_id IS NOT NULL;
CREATE INDEX shot_by_did  ON shot(decent_id) WHERE decent_id IS NOT NULL;
CREATE INDEX shot_by_bean ON shot(bean_id, completed_at DESC);
CREATE INDEX shot_by_seq  ON shot(changed_seq);
CREATE INDEX shot_by_cseq ON shot(created_seq);
CREATE TABLE shot_tag (
  tag TEXT NOT NULL,
  rid INTEGER NOT NULL REFERENCES shot(rid),
  PRIMARY KEY (tag, rid)
) WITHOUT ROWID, STRICT;
-- Not in SPEC §4's listing: `delete(ids)` removes a row's tags by `rid` (M10).
CREATE INDEX shot_tag_by_rid ON shot_tag(rid);
CREATE TABLE shot_heavy (
  rid INTEGER PRIMARY KEY REFERENCES shot(rid),
  profile_json TEXT,
  brew_series_json TEXT
) STRICT;
CREATE TABLE live_bean (
  bean_id TEXT PRIMARY KEY,
  coffee_key TEXT,
  name TEXT,
  roaster TEXT
) STRICT;
CREATE TABLE live_bean_tag (
  tag TEXT NOT NULL,
  tag_folded TEXT NOT NULL,
  bean_id TEXT NOT NULL,
  PRIMARY KEY (tag, bean_id)
) WITHOUT ROWID, STRICT;
CREATE TABLE live_method (
  method TEXT PRIMARY KEY,
  label_folded TEXT NOT NULL
) STRICT;
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) STRICT;
CREATE TABLE migration_source (id TEXT PRIMARY KEY) WITHOUT ROWID, STRICT;
CREATE TABLE rpc_applied (tab TEXT PRIMARY KEY, seq INTEGER NOT NULL, at_ms INTEGER NOT NULL) STRICT;
"
    };
}

/// The search index (SPEC §5). Its own text, since FTS damage drops and
/// rebuilds exactly this (M6).
macro_rules! fts_table_v1 {
    () => {
        "CREATE VIRTUAL TABLE shot_fts USING fts5(
  search_text, content='shot', content_rowid='rid',
  tokenize = 'trigram case_sensitive 1', detail = none
);
"
    };
}

/// The triggers that keep the search index in step: the external-content
/// pattern, where an update issues `'delete'` with the old text.
macro_rules! fts_triggers_v1 {
    () => {
        "CREATE TRIGGER shot_fts_ai AFTER INSERT ON shot BEGIN
  INSERT INTO shot_fts(rowid, search_text) VALUES (new.rid, new.search_text);
END;
CREATE TRIGGER shot_fts_ad AFTER DELETE ON shot BEGIN
  INSERT INTO shot_fts(shot_fts, rowid, search_text) VALUES ('delete', old.rid, old.search_text);
END;
CREATE TRIGGER shot_fts_au AFTER UPDATE OF search_text ON shot BEGIN
  INSERT INTO shot_fts(shot_fts, rowid, search_text) VALUES ('delete', old.rid, old.search_text);
  INSERT INTO shot_fts(rowid, search_text) VALUES (new.rid, new.search_text);
END;
"
    };
}

/// The tables and indexes of schema 1, without the search index.
pub const TABLES_V1: &str = tables_v1!();

/// The search index of schema 1 and its triggers.
pub const FTS_V1: &str = concat!(fts_table_v1!(), fts_triggers_v1!());

/// The search index's triggers alone: what the rebuild's swap puts on the new
/// table once it is renamed `shot_fts`.
pub const FTS_TRIGGERS_V1: &str = fts_triggers_v1!();

/// Schema 1, whole: [`TABLES_V1`] then [`FTS_V1`].
pub const V1: &str = concat!(tables_v1!(), fts_table_v1!(), fts_triggers_v1!());

/// The batched rebuild's new table (SPEC §5, S15), built in short
/// transactions by `rid` while `shot_fts`, if it still exists, keeps serving.
/// Its triggers keep it in step only for rows at or below the build's cursor,
/// `meta.fts_build_rid`; the build's batches add the rows above it.
pub const FTS_NEXT_V1: &str = "CREATE VIRTUAL TABLE shot_fts_next USING fts5(
  search_text, content='shot', content_rowid='rid',
  tokenize = 'trigram case_sensitive 1', detail = none
);
CREATE TRIGGER shot_fts_next_ai AFTER INSERT ON shot
WHEN new.rid <= (SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'fts_build_rid') BEGIN
  INSERT INTO shot_fts_next(rowid, search_text) VALUES (new.rid, new.search_text);
END;
CREATE TRIGGER shot_fts_next_ad AFTER DELETE ON shot
WHEN old.rid <= (SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'fts_build_rid') BEGIN
  INSERT INTO shot_fts_next(shot_fts_next, rowid, search_text) VALUES ('delete', old.rid, old.search_text);
END;
CREATE TRIGGER shot_fts_next_au AFTER UPDATE OF search_text ON shot
WHEN old.rid <= (SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'fts_build_rid') BEGIN
  INSERT INTO shot_fts_next(shot_fts_next, rowid, search_text) VALUES ('delete', old.rid, old.search_text);
  INSERT INTO shot_fts_next(rowid, search_text) VALUES (new.rid, new.search_text);
END;
";

/// Drops a rebuild's new table and its triggers: a build that is restarted,
/// or the first half of the swap. Derived data only (rule 4).
pub const DROP_FTS_NEXT: &str = "DROP TRIGGER IF EXISTS shot_fts_next_ai;
DROP TRIGGER IF EXISTS shot_fts_next_ad;
DROP TRIGGER IF EXISTS shot_fts_next_au;
DROP TABLE IF EXISTS shot_fts_next;
";

/// Drops the search index and its triggers, in one transaction, on FTS damage
/// (M6). Derived data only (rule 4).
pub const DROP_FTS: &str = "DROP TRIGGER IF EXISTS shot_fts_ai;
DROP TRIGGER IF EXISTS shot_fts_ad;
DROP TRIGGER IF EXISTS shot_fts_au;
DROP TABLE IF EXISTS shot_fts;
";

/// The connection pragmas both shells set (SPEC §4). The journal mode is the
/// shell's: WAL on Android, decided by W0 on web.
pub const PRAGMAS: &str = "PRAGMA foreign_keys = ON;
PRAGMA temp_store = MEMORY;
PRAGMA recursive_triggers = OFF;
";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_derived_column_has_a_place_in_shot() {
        for col in [
            "derive_v",
            "completed_at",
            "deleted_at",
            "pinned",
            "is_brew_log",
            "method",
            "family",
            "profile_name",
            "bean_id",
            "coffee_key",
            "has_tags",
            "visualizer_id",
            "decent_id",
            "sample_count",
            "sort_rating",
            "sort_profile",
            "sort_bean",
            "sort_yield",
            "sort_time",
            "st_dose",
            "st_yield",
            "st_ratio",
            "st_time_s",
            "st_rating",
            "search_text",
        ] {
            assert!(
                TABLES_V1.contains(&format!("\n  {col} ")),
                "{col} is missing"
            );
        }
    }

    #[test]
    fn no_key_cascades_and_rows_are_never_replaced() {
        let upper = V1.to_uppercase();
        assert!(!upper.contains("CASCADE"));
        assert!(!upper.contains("ON DELETE"));
        assert!(!upper.contains("OR REPLACE"));
    }

    #[test]
    fn every_table_is_strict() {
        let tables = TABLES_V1.matches("CREATE TABLE").count();
        assert_eq!(TABLES_V1.matches("STRICT;").count(), tables);
    }

    #[test]
    fn the_whole_schema_is_tables_then_fts() {
        assert_eq!(V1, format!("{TABLES_V1}{FTS_V1}"));
        assert!(FTS_V1.ends_with(FTS_TRIGGERS_V1));
        // The rebuild's table is the same index under another name.
        let next_table = &FTS_NEXT_V1[..FTS_NEXT_V1.find(";").expect("a statement")];
        let table = &FTS_V1[..FTS_V1.find(";").expect("a statement")];
        assert_eq!(next_table.replace("shot_fts_next", "shot_fts"), table);
        assert_eq!(APPLICATION_ID.to_be_bytes(), *b"CRMH");
    }
}
