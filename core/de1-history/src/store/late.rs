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
//! Only on an `authority` file.

use rusqlite::OptionalExtension;
use serde::Serialize;

use super::error::{Result, StoreError};
use super::write::{RowIn, Written, upsert_in};
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
