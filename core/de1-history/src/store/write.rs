//! Writes (SPEC §4, §9): `upsert`, `delete`, `set_live`, and `apply` for a
//! client's ordered writes. Each call is one transaction and returns a
//! [`ChangeSet`] carrying the `write_seq` it committed.
//!
//! - Rows are written with `INSERT … ON CONFLICT(id) DO UPDATE`, never
//!   `INSERT OR REPLACE`, whose implicit delete would leave a stale search
//!   entry and give the row a new `rid` (M10).
//! - `shot_heavy` is written after its row, in the row's transaction, with
//!   `COALESCE`, so a NULL never replaces a stored value (M10).
//! - `delete(ids)` removes `shot_tag`, `shot_heavy` and `shot` rows
//!   explicitly, children first: the schema has no cascades (M10).
//! - Upserts never touch `created_seq` after the first insert. An upsert of
//!   a row held by a delete during an export walk ends the hold, since the
//!   row exists again (REVIEW-442 B1); the derive rewrite never touches
//!   `hold_delete` (S6).

use std::sync::LazyLock;

use rusqlite::types::Value as Sql;
use rusqlite::{OptionalExtension, Transaction, params_from_iter};
use serde_json::Value;

use super::error::{Fail, Result, StoreError};
use super::faults::Faults;
use super::{Store, TxState, change_set};
use crate::change::{ChangeKinds, ChangeSet};
use crate::derive::{DERIVE_V, Derived, derive_live, derive_row, derive_value, row_id};
use crate::query::Live;

/// A row to write: its `row_json` (the P3 `ShotRow`, the truth), and on
/// Android its heavy parts.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RowIn {
    /// The row, as a JSON object with an `id`.
    pub row_json: String,
    /// Profile and brew series, for `shot_heavy`. `None`, or a field `None`,
    /// keeps what is stored (M10).
    pub heavy: Option<Heavy>,
    /// The row was pulled from a remote (Visualizer): when it is new and
    /// another row already holds its `visualizer_id`, nothing is written and
    /// the existing row's id comes back (N8).
    pub pulled: bool,
}

impl RowIn {
    /// A row with no heavy parts, not pulled.
    #[must_use]
    pub fn new(row_json: impl Into<String>) -> RowIn {
        RowIn {
            row_json: row_json.into(),
            heavy: None,
            pulled: false,
        }
    }
}

/// A row's heavy parts (`shot_heavy`, Android). Truth after the flip.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Heavy {
    /// The profile, as JSON.
    pub profile_json: Option<String>,
    /// The brew series, as JSON.
    pub brew_series_json: Option<String>,
}

/// A pulled row that wasn't inserted, because another row holds its
/// `visualizer_id` (N8).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Duplicate {
    /// The pulled row's id.
    pub id: String,
    /// The id of the row that holds its `visualizer_id`.
    pub existing_id: String,
}

/// What a write did.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Written {
    /// The change notice.
    pub change: ChangeSet,
    /// Pulled rows skipped as duplicates.
    pub duplicates: Vec<Duplicate>,
}

/// One client write, for [`Store::apply`].
#[derive(Debug, Clone, PartialEq)]
pub enum Write {
    /// [`Store::upsert`].
    Upsert(Vec<RowIn>),
    /// [`Store::delete`].
    Delete(Vec<String>),
    /// [`Store::set_live`].
    SetLive(Live),
}

/// What [`Store::apply`] did with a client's write (M11).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Applied {
    /// Applied now, with `rpc_applied` moved to its seq in the same
    /// transaction.
    Done(Written),
    /// Its seq is at or below the tab's last applied one: applied before,
    /// not again.
    AlreadyApplied {
        /// The tab's last applied seq.
        last: u64,
    },
    /// A seq before it hasn't arrived: it waits until the gap fills or the
    /// client resends its outbox. Nothing was written.
    ///
    /// `last: 0` for a tab whose earlier writes were applied means the store
    /// forgot it ([`Store::prune_tabs`], after its lock was seen gone): the
    /// tab takes a new tab id and starts again at seq 1 (REVIEW-439 note 7).
    Gap {
        /// The tab's last applied seq; the next one applied is `last + 1`.
        last: u64,
    },
    /// The write is refused for good (a row that isn't a JSON object with an
    /// id): nothing of it was written, and its seq is consumed, so the tab's
    /// next seq applies. The client drops it from its outbox.
    Refused {
        /// Why.
        reason: String,
    },
}

/// The derived columns, in [`derived_values`]'s order.
pub(crate) const DERIVED_COLUMNS: [&str; 25] = [
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
];

/// The row upsert: insert, or update every column but `rid`, `created_seq`
/// and `hold_delete`. Returns the row's `rid`.
static UPSERT: LazyLock<String> = LazyLock::new(|| {
    let cols = DERIVED_COLUMNS.join(", ");
    let marks: Vec<String> = (1..=4 + DERIVED_COLUMNS.len())
        .map(|i| format!("?{i}"))
        .collect();
    let sets: Vec<String> = ["row_json", "changed_seq"]
        .iter()
        .chain(DERIVED_COLUMNS.iter())
        .map(|c| format!("{c} = excluded.{c}"))
        .collect();
    format!(
        "INSERT INTO shot (id, row_json, created_seq, changed_seq, {cols}) VALUES ({}) \
         ON CONFLICT(id) DO UPDATE SET {} RETURNING rid",
        marks.join(", "),
        sets.join(", ")
    )
});

/// The derive rewrite's update: the derived columns and `changed_seq` of one
/// `rid`, nothing else (S6).
pub(crate) static UPDATE_DERIVED: LazyLock<String> = LazyLock::new(|| {
    let sets: Vec<String> = DERIVED_COLUMNS
        .iter()
        .enumerate()
        .map(|(i, c)| format!("{c} = ?{}", i + 1))
        .collect();
    format!(
        "UPDATE shot SET {}, changed_seq = ?{} WHERE rid = ?{}",
        sets.join(", "),
        DERIVED_COLUMNS.len() + 1,
        DERIVED_COLUMNS.len() + 2
    )
});

/// `shot_heavy`, never thinned: a NULL keeps the stored value (M10).
const UPSERT_HEAVY: &str = "INSERT INTO shot_heavy (rid, profile_json, brew_series_json) VALUES (?1, ?2, ?3) \
     ON CONFLICT(rid) DO UPDATE SET \
     profile_json = COALESCE(excluded.profile_json, profile_json), \
     brew_series_json = COALESCE(excluded.brew_series_json, brew_series_json)";

fn int(b: bool) -> Sql {
    Sql::Integer(i64::from(b))
}

fn text(s: &Option<String>) -> Sql {
    s.clone().map_or(Sql::Null, Sql::Text)
}

fn real(r: Option<f64>) -> Sql {
    r.map_or(Sql::Null, Sql::Real)
}

/// `d`'s values for [`DERIVED_COLUMNS`].
pub(crate) fn derived_values(d: &Derived) -> Vec<Sql> {
    vec![
        Sql::Integer(i64::from(d.derive_v)),
        Sql::Integer(d.completed_at),
        d.deleted_at.map_or(Sql::Null, Sql::Integer),
        int(d.pinned),
        int(d.is_brew_log),
        Sql::Text(d.method.clone()),
        text(&d.family),
        text(&d.profile_name),
        text(&d.bean_id),
        text(&d.coffee_key),
        int(d.has_tags),
        text(&d.visualizer_id),
        text(&d.decent_id),
        d.sample_count.map_or(Sql::Null, Sql::Integer),
        Sql::Integer(d.sort_rating),
        Sql::Text(d.sort_profile.clone()),
        Sql::Text(d.sort_bean.clone()),
        Sql::Real(d.sort_yield),
        Sql::Integer(d.sort_time),
        real(d.st_dose),
        real(d.st_yield),
        real(d.st_ratio),
        real(d.st_time_s),
        real(d.st_rating),
        Sql::Text(d.search_text.clone()),
    ]
}

/// The derived columns stored for `rid`, with its `shot_tag` rows as the
/// tags, sorted.
pub(crate) fn stored_derived(tx: &rusqlite::Connection, rid: i64) -> rusqlite::Result<Derived> {
    let sql = format!(
        "SELECT {} FROM shot WHERE rid = ?1",
        DERIVED_COLUMNS.join(", ")
    );
    let mut d = tx.prepare_cached(&sql)?.query_row([rid], |r| {
        Ok(Derived {
            derive_v: r.get(0)?,
            completed_at: r.get(1)?,
            deleted_at: r.get(2)?,
            pinned: r.get(3)?,
            is_brew_log: r.get(4)?,
            method: r.get(5)?,
            family: r.get(6)?,
            profile_name: r.get(7)?,
            bean_id: r.get(8)?,
            coffee_key: r.get(9)?,
            has_tags: r.get(10)?,
            visualizer_id: r.get(11)?,
            decent_id: r.get(12)?,
            sample_count: r.get(13)?,
            sort_rating: r.get(14)?,
            sort_profile: r.get(15)?,
            sort_bean: r.get(16)?,
            sort_yield: r.get(17)?,
            sort_time: r.get(18)?,
            st_dose: r.get(19)?,
            st_yield: r.get(20)?,
            st_ratio: r.get(21)?,
            st_time_s: r.get(22)?,
            st_rating: r.get(23)?,
            search_text: r.get(24)?,
            tags: Vec::new(),
        })
    })?;
    d.tags = tx
        .prepare_cached("SELECT tag FROM shot_tag WHERE rid = ?1 ORDER BY tag")?
        .query_map([rid], |r| r.get(0))?
        .collect::<rusqlite::Result<_>>()?;
    Ok(d)
}

/// `d` with its tags sorted, to compare with [`stored_derived`].
pub(crate) fn with_sorted_tags(d: &Derived) -> Derived {
    let mut d = d.clone();
    d.tags.sort();
    d
}

/// Replaces `rid`'s `shot_tag` rows with `tags`.
pub(crate) fn write_tags(tx: &Transaction<'_>, rid: i64, tags: &[String]) -> rusqlite::Result<()> {
    tx.prepare_cached("DELETE FROM shot_tag WHERE rid = ?1")?
        .execute([rid])?;
    let mut insert = tx.prepare_cached("INSERT INTO shot_tag (tag, rid) VALUES (?1, ?2)")?;
    let mut seen: Vec<&str> = Vec::with_capacity(tags.len());
    for t in tags {
        if !seen.contains(&t.as_str()) {
            seen.push(t);
            insert.execute((t, rid))?;
        }
    }
    Ok(())
}

/// The row as lists see it: derived, not tombstoned, not held.
fn visible(d: Option<Derived>, held: bool) -> Option<Derived> {
    d.filter(|d| !held && d.is_live())
}

/// What an existing row holds, before a write.
struct Existing {
    rid: i64,
    row_json: String,
    held: bool,
    derive_v: i64,
}

/// The upsert body, inside a transaction.
pub(crate) fn upsert_in(
    tx: &Transaction<'_>,
    st: &mut TxState,
    faults: &Faults,
    rows: &[RowIn],
) -> std::result::Result<Outcome, Fail> {
    let mut out = Outcome::default();
    for (i, row) in rows.iter().enumerate() {
        let value: Value = serde_json::from_str(&row.row_json)
            .map_err(|e| StoreError::Refused(format!("row {i}: not JSON: {e}")))?;
        let id = row_id(&value)
            .ok_or_else(|| StoreError::Refused(format!("row {i}: no id")))?
            .to_owned();
        let derived =
            derive_value(&value).map_err(|e| StoreError::Refused(format!("row {i}: {e}")))?;
        faults.hit("upsert.row")?;
        let existing = tx
            .prepare_cached("SELECT rid, row_json, hold_delete, derive_v FROM shot WHERE id = ?1")?
            .query_row([&id], |r| {
                Ok(Existing {
                    rid: r.get(0)?,
                    row_json: r.get(1)?,
                    held: r.get::<_, i64>(2)? != 0,
                    derive_v: r.get(3)?,
                })
            })
            .optional()?;
        if existing.is_none()
            && row.pulled
            && let Some(vid) = &derived.visualizer_id
        {
            let holder: Option<String> = tx
                .prepare_cached(
                    "SELECT id FROM shot WHERE visualizer_id = ?1 ORDER BY rid LIMIT 1",
                )?
                .query_row([vid], |r| r.get(0))
                .optional()?;
            if let Some(existing_id) = holder {
                out.duplicates.push(Duplicate { id, existing_id });
                continue;
            }
        }
        // A held row that is written again exists again: its hold ends here,
        // or the release would delete it with its details (REVIEW-442 B1).
        // So a held row is never "unchanged". The derive rewrite keeps its
        // own rule and never touches `hold_delete` (S6).
        let unchanged = existing.as_ref().is_some_and(|e| {
            !e.held && e.row_json == row.row_json && e.derive_v == i64::from(DERIVE_V)
        });
        let rid = if let (true, Some(e)) = (unchanged, &existing) {
            e.rid
        } else {
            let seq = i64::try_from(st.next())
                .map_err(|_| StoreError::Refused("write_seq overflow".into()))?;
            let mut params = vec![
                Sql::Text(id.clone()),
                Sql::Text(row.row_json.clone()),
                Sql::Integer(seq),
                Sql::Integer(seq),
            ];
            params.extend(derived_values(&derived));
            let rid: i64 = tx
                .prepare_cached(&UPSERT)?
                .query_row(params_from_iter(params), |r| r.get(0))?;
            let was_held = existing.as_ref().is_some_and(|e| e.held);
            if was_held {
                tx.prepare_cached("UPDATE shot SET hold_delete = 0 WHERE rid = ?1")?
                    .execute([rid])?;
            }
            faults.hit("upsert.tags")?;
            write_tags(tx, rid, &derived.tags)?;
            let old = existing.as_ref().and_then(|e| derive_row(&e.row_json).ok());
            let stale = existing
                .as_ref()
                .is_some_and(|e| e.derive_v != i64::from(DERIVE_V));
            let k = ChangeKinds::between(
                visible(old, was_held).as_ref(),
                visible(Some(derived.clone()), false).as_ref(),
                true,
            );
            out.kinds = out.kinds.union(if stale { ChangeKinds::ALL } else { k });
            if !out.upserted.contains(&id) {
                out.upserted.push(id.clone());
            }
            rid
        };
        if let Some(h) = &row.heavy
            && (h.profile_json.is_some() || h.brew_series_json.is_some())
        {
            faults.hit("upsert.heavy")?;
            let stored: Option<(Option<String>, Option<String>)> = tx
                .prepare_cached(
                    "SELECT profile_json, brew_series_json FROM shot_heavy WHERE rid = ?1",
                )?
                .query_row([rid], |r| Ok((r.get(0)?, r.get(1)?)))
                .optional()?;
            let (old_p, old_s) = stored.clone().unwrap_or_default();
            let merged = (
                h.profile_json.clone().or(old_p),
                h.brew_series_json.clone().or(old_s),
            );
            if stored.as_ref() != Some(&merged) {
                st.next();
                tx.prepare_cached(UPSERT_HEAVY)?.execute((
                    rid,
                    &h.profile_json,
                    &h.brew_series_json,
                ))?;
                if !out.upserted.contains(&id) {
                    out.upserted.push(id.clone());
                }
            }
        }
    }
    Ok(out)
}

/// The delete body: each named row's children, then the row (M10).
/// While an export walk is open, a row of its set isn't removed but held
/// (`hold_delete = 1`): hidden everywhere, and removed once no walk needs it
/// (S6).
pub(crate) fn delete_in(
    tx: &Transaction<'_>,
    st: &mut TxState,
    faults: &Faults,
    ids: &[String],
    walking: bool,
) -> std::result::Result<Outcome, Fail> {
    let mut out = Outcome::default();
    for id in ids {
        let found: Option<(i64, String, bool)> = tx
            .prepare_cached("SELECT rid, row_json, hold_delete FROM shot WHERE id = ?1")?
            .query_row([id], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? != 0))
            })
            .optional()?;
        let Some((rid, row_json, held)) = found else {
            continue;
        };
        faults.hit("delete.row")?;
        let in_walk = walking
            && tx
                .prepare_cached("SELECT 1 FROM temp.walk_set WHERE rid = ?1 LIMIT 1")?
                .query_row([rid], |_| Ok(()))
                .optional()?
                .is_some();
        if in_walk {
            if held {
                continue;
            }
            let seq = i64::try_from(st.next())
                .map_err(|_| StoreError::Refused("write_seq overflow".into()))?;
            tx.prepare_cached("UPDATE shot SET hold_delete = 1, changed_seq = ?1 WHERE rid = ?2")?
                .execute((seq, rid))?;
            let old = visible(derive_row(&row_json).ok(), false);
            out.kinds = out
                .kinds
                .union(ChangeKinds::between(old.as_ref(), None, true));
            out.deleted.push(id.clone());
            continue;
        }
        st.next();
        tx.prepare_cached("DELETE FROM shot_tag WHERE rid = ?1")?
            .execute([rid])?;
        tx.prepare_cached("DELETE FROM shot_heavy WHERE rid = ?1")?
            .execute([rid])?;
        tx.prepare_cached("DELETE FROM shot WHERE rid = ?1")?
            .execute([rid])?;
        let old = visible(derive_row(&row_json).ok(), held);
        out.kinds = out
            .kinds
            .union(ChangeKinds::between(old.as_ref(), None, true));
        out.deleted.push(id.clone());
    }
    Ok(out)
}

/// The live mirror's hash, so an unchanged library writes nothing.
fn live_hash(live: &Live) -> String {
    let bytes = serde_json::to_vec(live).unwrap_or_default();
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in bytes {
        h ^= u64::from(b);
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    format!("{h:016x}")
}

/// The `set_live` body: rewrites the mirror whole (derived data, rule 4).
pub(crate) fn set_live_in(
    tx: &Transaction<'_>,
    st: &mut TxState,
    live: &Live,
) -> std::result::Result<ChangeKinds, Fail> {
    let hash = live_hash(live);
    let stored: Option<String> = tx
        .query_row("SELECT value FROM meta WHERE key = 'live_hash'", [], |r| {
            r.get(0)
        })
        .optional()?;
    if stored.as_deref() == Some(hash.as_str()) {
        return Ok(ChangeKinds::default());
    }
    st.next();
    let rows = derive_live(live);
    tx.execute_batch("DELETE FROM live_bean; DELETE FROM live_bean_tag; DELETE FROM live_method;")?;
    for b in &rows.beans {
        tx.prepare_cached(
            "INSERT INTO live_bean (bean_id, coffee_key, name, roaster) VALUES (?1, ?2, ?3, ?4)",
        )?
        .execute((&b.bean_id, &b.coffee_key, &b.name, &b.roaster))?;
    }
    for t in &rows.bean_tags {
        tx.prepare_cached(
            "INSERT INTO live_bean_tag (tag, tag_folded, bean_id) VALUES (?1, ?2, ?3)",
        )?
        .execute((&t.tag, &t.tag_folded, &t.bean_id))?;
    }
    for m in &rows.methods {
        tx.prepare_cached("INSERT INTO live_method (method, label_folded) VALUES (?1, ?2)")?
            .execute((&m.method, &m.label_folded))?;
    }
    tx.execute(
        "INSERT INTO meta (key, value) VALUES ('live_hash', ?1) \
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        [hash],
    )?;
    // The mirror moves coffee scopes, effective tags and label searches: what
    // a filtered or searching list shows, never the unfiltered set.
    Ok(ChangeKinds {
        facet: true,
        text: true,
        ..ChangeKinds::default()
    })
}

/// A write's body, for [`Store::apply`] and the plain calls.
fn write_in(
    tx: &Transaction<'_>,
    st: &mut TxState,
    faults: &Faults,
    write: &Write,
    walking: bool,
) -> std::result::Result<Outcome, Fail> {
    match write {
        Write::Upsert(rows) => upsert_in(tx, st, faults, rows),
        Write::Delete(ids) => delete_in(tx, st, faults, ids, walking),
        Write::SetLive(live) => Ok(Outcome {
            kinds: set_live_in(tx, st, live)?,
            ..Outcome::default()
        }),
    }
}

/// What a write's body did, before it commits.
#[derive(Debug, Default)]
pub(crate) struct Outcome {
    pub(crate) upserted: Vec<String>,
    pub(crate) deleted: Vec<String>,
    pub(crate) duplicates: Vec<Duplicate>,
    pub(crate) kinds: ChangeKinds,
}

impl Outcome {
    pub(crate) fn written(self, st: &TxState) -> Written {
        Written {
            change: change_set(st, self.upserted, self.deleted, self.kinds),
            duplicates: self.duplicates,
        }
    }
}

impl Store {
    /// Writes `rows`, in one transaction: each row with its tags and search
    /// text (the triggers), then its `shot_heavy`. A row whose `row_json` is
    /// unchanged isn't rewritten. A pulled row whose `visualizer_id` another
    /// row holds isn't inserted (N8).
    ///
    /// # Errors
    /// [`StoreError::Refused`] when a row isn't a JSON object with an id
    /// (nothing is written); a SQLite error class otherwise, with the
    /// transaction rolled back.
    pub fn upsert(&mut self, rows: &[RowIn]) -> Result<Written> {
        let (out, st) = self.write("upsert.commit", "upsert.committed", |tx, st, f| {
            upsert_in(tx, st, f, rows)
        })?;
        Ok(out.written(&st))
    }

    /// Removes the named rows, in one transaction: for each, its `shot_tag`
    /// and `shot_heavy` rows, then the row. Unknown ids are skipped. This is
    /// the only call that removes a row (rule 1). While an export walk is
    /// open, a row of its set is held instead: hidden, and listed as deleted
    /// in the change set, until [`Store::release_holds`] gives it back.
    ///
    /// # Errors
    /// A SQLite error class, with the transaction rolled back.
    pub fn delete(&mut self, ids: &[String]) -> Result<ChangeSet> {
        let walking = self.walks.any();
        let (out, st) = self.write("delete.commit", "delete.committed", |tx, st, f| {
            delete_in(tx, st, f, ids, walking)
        })?;
        Ok(out.written(&st).change)
    }

    /// Rewrites the live mirror (`live_bean`, `live_bean_tag`, `live_method`)
    /// from the bean library and custom methods. An unchanged library writes
    /// nothing.
    ///
    /// # Errors
    /// A SQLite error class, with the transaction rolled back.
    pub fn set_live(&mut self, live: &Live) -> Result<ChangeSet> {
        let (kinds, st) = self.write("set_live.commit", "set_live.committed", |tx, st, _| {
            set_live_in(tx, st, live)
        })?;
        Ok(change_set(&st, Vec::new(), Vec::new(), kinds))
    }

    /// Applies tab `tab`'s write number `seq` in order (SPEC §8, M11): a seq
    /// at or below the tab's last applied one is
    /// [`Applied::AlreadyApplied`], one past a gap is [`Applied::Gap`], and
    /// the next one is applied with `rpc_applied` moved to it in the same
    /// transaction, so an owner after a handover skips what this one applied.
    /// Seqs start at 1. `at_ms` is when, for [`Store::prune_tabs`].
    ///
    /// # Errors
    /// As the write's own call.
    pub fn apply(&mut self, tab: &str, seq: u64, at_ms: i64, write: &Write) -> Result<Applied> {
        let seq_i =
            i64::try_from(seq).map_err(|_| StoreError::Refused("seq out of range".into()))?;
        let walking = self.walks.any();
        let (applied, st) = self.write("apply.commit", "apply.committed", |tx, st, f| {
            let last: i64 = tx
                .prepare_cached("SELECT seq FROM rpc_applied WHERE tab = ?1")?
                .query_row([tab], |r| r.get(0))
                .optional()?
                .unwrap_or(0);
            let last_u = u64::try_from(last).unwrap_or(0);
            if seq <= last_u {
                return Ok(Err(Applied::AlreadyApplied { last: last_u }));
            }
            if seq > last_u + 1 {
                return Ok(Err(Applied::Gap { last: last_u }));
            }
            // The body in a savepoint: a write refused for good (a row
            // that isn't a JSON object with an id) is rolled back alone and
            // its seq consumed, so the tab's later writes don't wait on it
            // forever (REVIEW-439 note 7).
            tx.execute_batch("SAVEPOINT apply_body")?;
            let (seq_before, dirty_before) = (st.seq, st.dirty);
            let result = match write_in(tx, st, f, write, walking) {
                Ok(out) => {
                    tx.execute_batch("RELEASE apply_body")?;
                    Ok(out)
                }
                Err(Fail::Store(StoreError::Refused(reason))) => {
                    tx.execute_batch("ROLLBACK TO apply_body; RELEASE apply_body")?;
                    st.seq = seq_before;
                    st.dirty = dirty_before;
                    Err(Applied::Refused { reason })
                }
                Err(other) => return Err(other),
            };
            tx.prepare_cached(
                "INSERT INTO rpc_applied (tab, seq, at_ms) VALUES (?1, ?2, ?3) \
                 ON CONFLICT(tab) DO UPDATE SET seq = excluded.seq, at_ms = excluded.at_ms",
            )?
            .execute((tab, seq_i, at_ms))?;
            Ok(result)
        })?;
        Ok(match applied {
            Ok(out) => Applied::Done(out.written(&st)),
            Err(skipped) => skipped,
        })
    }

    /// The last seq applied for `tab`; 0 when none.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn applied_seq(&self, tab: &str) -> Result<u64> {
        self.usable()?;
        let last: Option<i64> = self
            .conn
            .query_row("SELECT seq FROM rpc_applied WHERE tab = ?1", [tab], |r| {
                r.get(0)
            })
            .optional()
            .map_err(|e| super::error::error_of(&e, super::error::Source::Session))?;
        Ok(last.map_or(0, |s| u64::try_from(s).unwrap_or(0)))
    }

    /// Forgets the applied seqs of tabs the caller knows are gone (their
    /// `crema-history-tab:<id>` lock is free), last written before
    /// `before_ms`. Returns how many it removed.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn prune_tabs(&mut self, gone: &[String], before_ms: i64) -> Result<usize> {
        let (n, _) = self.write("apply.commit", "apply.committed", |tx, _, _| {
            let mut n = 0;
            for tab in gone {
                n += tx
                    .prepare_cached("DELETE FROM rpc_applied WHERE tab = ?1 AND at_ms < ?2")?
                    .execute((tab, before_ms))?;
            }
            Ok(n)
        })?;
        Ok(n)
    }
}
