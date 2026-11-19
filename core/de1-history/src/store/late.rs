//! Late rows (SPEC §11 steps 8 and 9, M9, S13): what an older build, or this
//! build's own capture during the flip, wrote to the P3 store after the copy.
//!
//! - [`Store::import_late`] takes rows read from the P3 store and, in one
//!   transaction, inserts those whose ids aren't in `migration_source` and
//!   that no row here holds (a held row too), skipping a row whose
//!   `visualizer_id` a row here holds (a pulled duplicate, N8). Every id it
//!   sees goes into `migration_source`, so a row deleted here never comes
//!   back, and nothing here is deleted. Imported rows with no samples go into
//!   `meta.late_ids`, for step 9. With a baseline, `meta.late_baseline` moves
//!   to it in the same transaction.
//! - [`Store::settle_late`] is step 9: an older build writes the series
//!   after the row, so a late row's newer P3 copy may have samples. Only its
//!   `summary` moves onto the row this file holds, keeping every edit made
//!   here; a row deleted or held here, or a brew log (never any samples),
//!   leaves the list and is never written (REVIEW-474 B1, N1).
//! - [`Store::existing`] says which ids have a row, held ones too: what a
//!   delete's details may go by (REVIEW-468 W5-2).
//!
//! - Android (PLAN A5) also reads and copies `shot_heavy`: [`Store::heavy`],
//!   [`Store::ids_with_profile`] and [`Store::put_heavy`] (the flip's step
//!   6, rows the copy holds only, never thinned, M10). Its step 8 runs over
//!   P3a's files in Kotlin and imports through [`Store::import_late_rows`]:
//!   the same rule as [`Store::import_late`] (insert if absent, a pulled
//!   duplicate skipped, every id into `migration_source`), with `late_ids`
//!   and the baseline set by the caller, whose step 9 fills series files
//!   and summaries itself. On both, a brew log is never in `late_ids`.
//!   [`Store::note_details_written`] moves `write_seq` for series written
//!   with no row write (REVIEW-466 note 1).
//!
//! Only on an `authority` file.

use rusqlite::OptionalExtension;
use serde::Serialize;

use super::error::{Result, Source, StoreError, error_of};
use super::write::{Heavy, RowIn, Written, upsert_in};
use super::{Role, Store};
use crate::change::ChangeSet;
use crate::derive::{derive_row, row_id};

/// What [`Store::import_late`] did.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LateImport {
    /// Inserted now.
    pub imported: Vec<String>,
    /// Already in `migration_source`, or held here: not inserted (a row the
    /// copy had, or one deleted here since).
    pub known: Vec<String>,
    /// A pulled duplicate: another row here holds its `visualizer_id`.
    pub duplicates: Vec<String>,
    /// What lists see of it.
    pub change: ChangeSet,
}

fn ids_json(ids: &[String]) -> String {
    serde_json::to_string(ids).unwrap_or_else(|_| "[]".into())
}

impl Store {
    fn authority_only(&self, what: &str) -> Result<()> {
        if self.info().role == Role::Authority {
            Ok(())
        } else {
            Err(StoreError::Refused(format!(
                "{what} is for the truth file only"
            )))
        }
    }

    /// The ids of `ids` that have a row here, held ones too.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn existing(&self, ids: &[String]) -> Result<Vec<String>> {
        let text = ids_json(ids);
        let run = || -> rusqlite::Result<Vec<String>> {
            self.conn
                .prepare(
                    "SELECT id FROM shot WHERE id IN (SELECT value FROM json_each(?1)) ORDER BY id",
                )?
                .query_map([&text], |r| r.get(0))?
                .collect()
        };
        run().map_err(|e| super::error::error_of(&e, super::error::Source::Session))
    }

    /// `meta.late_ids`: late rows that came with no samples (step 9).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn late_ids(&self) -> Result<Vec<String>> {
        Ok(self
            .meta_value("late_ids")?
            .and_then(|t| serde_json::from_str(&t).ok())
            .unwrap_or_default())
    }

    /// Step 8's import (see the module). `baseline`, when given, becomes
    /// `meta.late_baseline` in the same transaction: the last batch of a
    /// step passes it.
    ///
    /// # Errors
    /// [`StoreError::Refused`] off the truth file, or for a row that isn't a
    /// JSON object with an id; a SQLite error class, with nothing written.
    pub fn import_late(&mut self, rows: &[RowIn], baseline: Option<&str>) -> Result<LateImport> {
        self.authority_only("a late-row import")?;
        let mut late = self.late_ids()?;
        let mut out = LateImport::default();
        let (written, st) = self.write("late.commit", "late.committed", |tx, st, f| {
            out = LateImport::default();
            let mut fresh: Vec<RowIn> = Vec::new();
            let mut seen: Vec<String> = Vec::new();
            for (i, row) in rows.iter().enumerate() {
                let value: serde_json::Value = serde_json::from_str(&row.row_json)
                    .map_err(|e| StoreError::Refused(format!("row {i}: not JSON: {e}")))?;
                let id = row_id(&value)
                    .ok_or_else(|| StoreError::Refused(format!("row {i}: no id")))?
                    .to_owned();
                let in_source = tx
                    .prepare_cached("SELECT 1 FROM migration_source WHERE id = ?1")?
                    .query_row([&id], |_| Ok(()))
                    .optional()?
                    .is_some();
                let here = tx
                    .prepare_cached("SELECT 1 FROM shot WHERE id = ?1")?
                    .query_row([&id], |_| Ok(()))
                    .optional()?
                    .is_some();
                seen.push(id.clone());
                if in_source || here {
                    out.known.push(id);
                    continue;
                }
                fresh.push(RowIn {
                    pulled: true,
                    ..row.clone()
                });
            }
            let o = upsert_in(tx, st, f, &fresh)?;
            for d in &o.duplicates {
                out.duplicates.push(d.id.clone());
            }
            for r in &fresh {
                let id = derive_row(&r.row_json)
                    .ok()
                    .and_then(|_| serde_json::from_str::<serde_json::Value>(&r.row_json).ok())
                    .and_then(|v| row_id(&v).map(str::to_owned));
                let Some(id) = id else { continue };
                if out.duplicates.contains(&id) {
                    continue;
                }
                // A brew log never has samples: it isn't waiting for any, and
                // step 9 would only rewrite it (REVIEW-474 B1).
                let derived = derive_row(&r.row_json).ok();
                if derived
                    .as_ref()
                    .is_some_and(|d| !d.is_brew_log && d.sample_count == Some(0))
                    && !late.contains(&id)
                {
                    late.push(id.clone());
                }
                out.imported.push(id);
            }
            tx.prepare_cached(
                "INSERT OR IGNORE INTO migration_source (id) SELECT value FROM json_each(?1)",
            )?
            .execute([ids_json(&seen)])?;
            let put = |k: &str, v: &str| -> rusqlite::Result<usize> {
                tx.prepare_cached(
                    "INSERT INTO meta (key, value) VALUES (?1, ?2) \
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                )?
                .execute((k, v))
            };
            put("late_ids", &ids_json(&late))?;
            if let Some(b) = baseline {
                put("late_baseline", b)?;
            }
            if !out.imported.is_empty() || baseline.is_some() {
                st.next();
            }
            Ok(o)
        })?;
        out.change = written.written(&st).change;
        Ok(out)
    }

    /// Step 9 (see the module): `rows` are the P3 store's copies of late
    /// rows, read again. For each id still in `late_ids` whose row here is
    /// there, not held and not a brew log, and whose P3 copy now has samples,
    /// only the P3 copy's `summary` moves onto the row this file holds: every
    /// other field stays, so no edit made here is undone (REVIEW-474 B1), as
    /// P3's own step 9 fills only the samples. The series itself is in the
    /// shared details already. An id with no row, a held row, a brew log, or
    /// one that has its samples now leaves `late_ids`; nothing comes back
    /// (N1).
    ///
    /// # Errors
    /// [`StoreError::Refused`] off the truth file; as [`Store::upsert`].
    pub fn settle_late(&mut self, rows: &[RowIn]) -> Result<Written> {
        self.authority_only("a late-row fill")?;
        let late = self.late_ids()?;
        let incoming: std::collections::HashMap<String, serde_json::Value> = rows
            .iter()
            .filter_map(|r| {
                let v: serde_json::Value = serde_json::from_str(&r.row_json).ok()?;
                let id = row_id(&v)?.to_owned();
                Some((id, v))
            })
            .collect();
        let (o, st) = self.write("late.commit", "late.committed", |tx, st, f| {
            let mut fills: Vec<RowIn> = Vec::new();
            let mut keep: Vec<String> = Vec::new();
            for id in &late {
                let here: Option<(String, bool, bool, Option<i64>)> = tx
                    .prepare_cached(
                        "SELECT row_json, hold_delete, is_brew_log, sample_count FROM shot WHERE id = ?1",
                    )?
                    .query_row([id], |r| {
                        Ok((r.get(0)?, r.get::<_, i64>(1)? != 0, r.get::<_, i64>(2)? != 0, r.get(3)?))
                    })
                    .optional()?;
                // Deleted or held here, or a brew log: done with, never written.
                let Some((stored, held, brew, samples)) = here else { continue };
                if held || brew || samples.unwrap_or(0) > 0 {
                    continue;
                }
                let filled = incoming.get(id).and_then(|p3| {
                    let summary = p3.get("summary")?;
                    let n = summary.get("sampleCount").and_then(serde_json::Value::as_i64)?;
                    if n <= 0 {
                        return None;
                    }
                    let mut row: serde_json::Value = serde_json::from_str(&stored).ok()?;
                    row.as_object_mut()?.insert("summary".into(), summary.clone());
                    Some(row.to_string())
                });
                match filled {
                    Some(row_json) => fills.push(RowIn {
                        row_json,
                        heavy: None,
                        pulled: false,
                    }),
                    None => keep.push(id.clone()),
                }
            }
            let o = upsert_in(tx, st, f, &fills)?;
            if keep != late {
                st.next();
                tx.prepare_cached(
                    "INSERT INTO meta (key, value) VALUES ('late_ids', ?1) \
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                )?
                .execute([ids_json(&keep)])?;
            }
            Ok(o)
        })?;
        Ok(o.written(&st))
    }
}

/// A row's heavy parts as `shot_heavy` holds them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HeavyOut {
    /// The row's id.
    pub id: String,
    /// The profile, as JSON.
    pub profile_json: Option<String>,
    /// The brew series, as JSON.
    pub brew_series_json: Option<String>,
}

/// Android's step 8 import into a truth file (SPEC §11, S13): see
/// [`Store::import_late_rows`].
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LateRows {
    /// Rows the P3 store holds that `migration_source` doesn't: each goes in
    /// only when the file doesn't hold its id yet.
    pub rows: Vec<RowIn>,
    /// Ids that join `migration_source`: the rows' own, and any the shell
    /// looked at and left out (a Visualizer duplicate), so they aren't looked
    /// at again.
    pub source_ids: Vec<String>,
    /// The new `late_ids` (rows that arrived with no series), when given.
    pub late_ids: Option<Vec<String>>,
    /// The new late-row baseline (`meta.late_baseline`), when given. Opaque
    /// to the core.
    pub late_baseline: Option<String>,
}

/// What [`Store::import_late_rows`] did.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LateImported {
    /// The write (pulled duplicates in `duplicates`).
    pub written: Written,
    /// Rows left out because the file already holds their id.
    pub present: Vec<String>,
}

const META_UPSERT: &str = "INSERT INTO meta (key, value) VALUES (?1, ?2) \
     ON CONFLICT(key) DO UPDATE SET value = excluded.value";

impl Store {
    /// `shot_heavy` for `ids`, in no particular order. An id with no row
    /// there (no heavy parts, or no such shot) is left out.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn heavy(&self, ids: &[String]) -> Result<Vec<HeavyOut>> {
        self.usable()?;
        let run = || -> rusqlite::Result<Vec<HeavyOut>> {
            let mut st = self.conn.prepare_cached(
                "SELECT s.id, h.profile_json, h.brew_series_json \
                 FROM shot s JOIN shot_heavy h ON h.rid = s.rid WHERE s.id = ?1",
            )?;
            let mut out = Vec::with_capacity(ids.len());
            for id in ids {
                if let Some(h) = st
                    .query_row([id], |r| {
                        Ok(HeavyOut {
                            id: r.get(0)?,
                            profile_json: r.get(1)?,
                            brew_series_json: r.get(2)?,
                        })
                    })
                    .optional()?
                {
                    out.push(h);
                }
            }
            Ok(out)
        };
        run().map_err(|e| error_of(&e, Source::Session))
    }

    /// The ids of the rows whose profile is stored.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn ids_with_profile(&self) -> Result<Vec<String>> {
        self.usable()?;
        let run = || -> rusqlite::Result<Vec<String>> {
            self.conn
                .prepare(
                    "SELECT s.id FROM shot s JOIN shot_heavy h ON h.rid = s.rid \
                     WHERE h.profile_json IS NOT NULL ORDER BY s.rid",
                )?
                .query_map([], |r| r.get(0))?
                .collect()
        };
        run().map_err(|e| error_of(&e, Source::Session))
    }

    /// The flip's step 6 (SPEC §11): heavy parts copied into `shot_heavy`,
    /// in one transaction, for rows this file holds. A row it doesn't hold is
    /// skipped and named in the result; no row is written. A NULL part keeps
    /// the stored one (M10). Only on a truth file.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an index; a SQLite error class.
    pub fn put_heavy(&mut self, rows: &[(String, Heavy)]) -> Result<Vec<String>> {
        if self.info.role != Role::Authority {
            return Err(StoreError::Refused(
                "heavy parts live only in the truth file".into(),
            ));
        }
        let (missing, _) = self.write("heavy.commit", "heavy.committed", |tx, st, f| {
            let mut missing = Vec::new();
            for (id, h) in rows {
                let rid: Option<i64> = tx
                    .prepare_cached("SELECT rid FROM shot WHERE id = ?1")?
                    .query_row([id], |r| r.get(0))
                    .optional()?;
                let Some(rid) = rid else {
                    missing.push(id.clone());
                    continue;
                };
                if h.profile_json.is_none() && h.brew_series_json.is_none() {
                    continue;
                }
                f.hit("heavy.row")?;
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
                if stored.as_ref() == Some(&merged) {
                    continue;
                }
                st.next();
                tx.prepare_cached(
                    "INSERT INTO shot_heavy (rid, profile_json, brew_series_json) VALUES (?1, ?2, ?3) \
                     ON CONFLICT(rid) DO UPDATE SET \
                     profile_json = COALESCE(excluded.profile_json, profile_json), \
                     brew_series_json = COALESCE(excluded.brew_series_json, brew_series_json)",
                )?
                .execute((rid, &h.profile_json, &h.brew_series_json))?;
            }
            Ok(missing)
        })?;
        Ok(missing)
    }

    /// Which of `ids` `migration_source` doesn't hold, in the order given.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn not_in_migration_source(&self, ids: &[String]) -> Result<Vec<String>> {
        self.usable()?;
        let run = || -> rusqlite::Result<Vec<String>> {
            let mut st = self
                .conn
                .prepare_cached("SELECT 1 FROM migration_source WHERE id = ?1")?;
            let mut out = Vec::new();
            for id in ids {
                if st.query_row([id], |_| Ok(())).optional()?.is_none() && !out.contains(id) {
                    out.push(id.clone());
                }
            }
            Ok(out)
        };
        run().map_err(|e| error_of(&e, Source::Session))
    }

    /// Series files were written with no row write (a restore filling a
    /// curve its row already named): `meta.details_at` moves, and with it
    /// `write_seq`, so auto-backup's "changed?" mark moves too (REVIEW-466
    /// note 1).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn note_details_written(&mut self, at_ms: i64) -> Result<u64> {
        self.write("late.commit", "late.committed", |tx, st, _| {
            st.next();
            tx.execute(META_UPSERT, ("details_at", at_ms.to_string()))?;
            Ok(())
        })?;
        Ok(self.write_seq())
    }

    /// Android's step 8 on a truth file (SPEC §11, S13), in one transaction:
    /// each row whose id the file doesn't hold yet goes in (a pulled one
    /// skipped when another row holds its `visualizer_id`, N8), every id
    /// given joins `migration_source`, and the caller's `late_ids` (less any
    /// brew log, which never waits for samples) and baseline move. A row the
    /// file already holds is never written over.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an index, or for a row that isn't a JSON
    /// object with an id (nothing is written); a SQLite error class.
    pub fn import_late_rows(&mut self, imp: &LateRows) -> Result<LateImported> {
        if self.info.role != Role::Authority {
            return Err(StoreError::Refused(
                "late rows go only into the truth file".into(),
            ));
        }
        let mut ids = Vec::with_capacity(imp.source_ids.len() + imp.rows.len());
        let mut parsed = Vec::with_capacity(imp.rows.len());
        for (i, r) in imp.rows.iter().enumerate() {
            let id = serde_json::from_str::<serde_json::Value>(&r.row_json)
                .ok()
                .and_then(|v| crate::derive::row_id(&v).map(str::to_owned))
                .ok_or_else(|| StoreError::Refused(format!("late row {i}: no id")))?;
            parsed.push(id);
        }
        ids.extend(parsed.iter().cloned());
        ids.extend(imp.source_ids.iter().cloned());
        let all_ids =
            serde_json::to_string(&ids).map_err(|e| StoreError::Refused(e.to_string()))?;
        self.note_written(&imp.rows);
        let ((out, present), st) = self.write("late.commit", "late.committed", |tx, st, f| {
            let mut fresh = Vec::new();
            let mut present = Vec::new();
            for (r, id) in imp.rows.iter().zip(&parsed) {
                let held: Option<i64> = tx
                    .prepare_cached("SELECT rid FROM shot WHERE id = ?1")?
                    .query_row([id], |r| r.get(0))
                    .optional()?;
                if held.is_some() {
                    present.push(id.clone());
                } else {
                    fresh.push(r.clone());
                }
            }
            let out = upsert_in(tx, st, f, &fresh)?;
            f.hit("late.source")?;
            let added = tx.execute(
                "INSERT OR IGNORE INTO migration_source (id) SELECT DISTINCT value FROM json_each(?1)",
                [&all_ids],
            )?;
            if added > 0 {
                st.next();
            }
            if let Some(l) = &imp.late_ids {
                // One meaning for `late_ids` on both shells: a brew log is never in it (REVIEW-474 B1).
                let mut keep = Vec::with_capacity(l.len());
                for id in l {
                    let brew: Option<i64> = tx
                        .prepare_cached("SELECT is_brew_log FROM shot WHERE id = ?1")?
                        .query_row([id], |r| r.get(0))
                        .optional()?;
                    if brew != Some(1) && !keep.contains(id) {
                        keep.push(id.clone());
                    }
                }
                st.next();
                tx.execute(META_UPSERT, ("late_ids", ids_json(&keep)))?;
            }
            if let Some(b) = &imp.late_baseline {
                st.next();
                tx.execute(META_UPSERT, ("late_baseline", b))?;
            }
            Ok((out, present))
        })?;
        Ok(LateImported {
            written: out.written(&st),
            present,
        })
    }
}
