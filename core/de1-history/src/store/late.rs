//! Android stage 2 (PLAN A5): the heavy parts read and copied, and step 8's
//! late rows (SPEC §11, S13, M10).
//!
//! - [`Store::heavy`] reads `shot_heavy` by id, for a shot read whole and
//!   for the flip's verify. [`Store::ids_with_profile`] lists the rows whose
//!   profile is stored.
//! - [`Store::put_heavy`] is the flip's step 6: profiles and brew series
//!   copied from `shots.db` into `shot_heavy`, in batches, for rows the copy
//!   holds. It never touches a row, and a NULL keeps what is stored (M10).
//! - [`Store::import_late`] is step 8 on a truth file, in one transaction:
//!   each row whose id the file doesn't hold yet goes in (a pulled one is
//!   skipped when another row holds its `visualizer_id`, N8), every id given
//!   joins `migration_source`, and the late-row baseline and `late_ids`
//!   move. A row the file already holds is never written over, so an import
//!   that a crash cut short can run again without undoing an edit made since.

use rusqlite::OptionalExtension;

use super::error::{Result, Source, StoreError, error_of};
use super::write::{Heavy, RowIn, Written, upsert_in};
use super::{Role, Store};

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

/// Step 8's import into a truth file (SPEC §11, S13).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LateImport {
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

/// What [`Store::import_late`] did.
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

    /// `meta.late_ids`: rows that arrived with no series, retried each boot
    /// (step 9). Empty when none.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn late_ids(&self) -> Result<Vec<String>> {
        self.usable()?;
        let raw: Option<String> = self
            .conn
            .query_row("SELECT value FROM meta WHERE key = 'late_ids'", [], |r| {
                r.get(0)
            })
            .optional()
            .map_err(|e| error_of(&e, Source::Session))?;
        Ok(raw
            .and_then(|r| serde_json::from_str::<Vec<String>>(&r).ok())
            .unwrap_or_default())
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

    /// Step 8 on a truth file (SPEC §11, S13), in one transaction: see the
    /// module docs.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an index, or for a row that isn't a JSON
    /// object with an id (nothing is written); a SQLite error class.
    pub fn import_late(&mut self, imp: &LateImport) -> Result<LateImported> {
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
        let ids_json =
            serde_json::to_string(&ids).map_err(|e| StoreError::Refused(e.to_string()))?;
        let late_json = imp
            .late_ids
            .as_ref()
            .map(|l| serde_json::to_string(l).map_err(|e| StoreError::Refused(e.to_string())))
            .transpose()?;
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
                [&ids_json],
            )?;
            if added > 0 {
                st.next();
            }
            if let Some(l) = &late_json {
                st.next();
                tx.execute(META_UPSERT, ("late_ids", l))?;
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
