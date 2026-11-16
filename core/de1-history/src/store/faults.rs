//! A fault hook for tests (PLAN C2), behind the `faults` feature: fail at a
//! named point, so every statement kind, every batch boundary and both sides
//! of every commit can be shown to leave the database whole.
//!
//! Without the feature, [`Faults::hit`] is an empty inline function and the
//! points cost nothing.

#[cfg(feature = "faults")]
use std::cell::RefCell;

use super::error::Result;
#[cfg(feature = "faults")]
use super::error::StoreError;

/// Every named point. A point ending in `.commit` fails just before the
/// transaction commits (so nothing lands); one ending in `.committed` fails
/// just after (so the write landed but the caller hears an error, as when a
/// process dies before it learns the outcome).
pub const POINTS: &[&str] = &[
    // Writes: each statement kind, then both sides of the commit.
    "upsert.row",
    "upsert.tags",
    "upsert.heavy",
    "upsert.commit",
    "upsert.committed",
    "delete.row",
    "delete.commit",
    "delete.committed",
    "set_live.commit",
    "set_live.committed",
    "apply.commit",
    "apply.committed",
    // Maintenance: each batch, and the swap.
    "derive.batch",
    "derive.commit",
    "fts.drop",
    "fts.build.begin",
    "fts.build.batch",
    "fts.build.commit",
    "fts.swap",
    "fts.merge",
    // The flip (SPEC §11 steps 5 and 7): after the backup copy, both sides
    // of the promote's commit, and before verify's `done` commits.
    "flip.copied",
    "flip.promote.commit",
    "flip.promote.committed",
    "flip.done.commit",
];

/// The armed faults and a log of the statements the store ran.
#[derive(Debug, Default)]
pub struct Faults {
    #[cfg(feature = "faults")]
    inner: RefCell<Inner>,
}

#[cfg(feature = "faults")]
#[derive(Debug, Default)]
struct Inner {
    /// `(point, hits to let through before failing)`.
    armed: Vec<(&'static str, u32)>,
    /// Query statements' SQL, when logging.
    log: Option<Vec<String>>,
}

impl Faults {
    /// Fails if `point` is armed and its count has run out.
    #[cfg(feature = "faults")]
    pub(crate) fn hit(&self, point: &'static str) -> Result<()> {
        debug_assert!(POINTS.contains(&point), "unknown fault point {point}");
        let mut inner = self.inner.borrow_mut();
        let Some(i) = inner.armed.iter().position(|(p, _)| *p == point) else {
            return Ok(());
        };
        if inner.armed[i].1 == 0 {
            inner.armed.remove(i);
            return Err(StoreError::Fault(point));
        }
        inner.armed[i].1 -= 1;
        Ok(())
    }

    /// Never fails without the `faults` feature.
    #[cfg(not(feature = "faults"))]
    #[inline(always)]
    #[allow(clippy::unused_self, clippy::unnecessary_wraps)]
    pub(crate) fn hit(&self, _point: &'static str) -> Result<()> {
        Ok(())
    }

    /// Records a query statement's SQL, when logging.
    #[cfg(feature = "faults")]
    pub(crate) fn log(&self, sql: &str) {
        if let Some(log) = self.inner.borrow_mut().log.as_mut() {
            log.push(sql.to_owned());
        }
    }

    /// Nothing to record without the `faults` feature.
    #[cfg(not(feature = "faults"))]
    #[inline(always)]
    #[allow(clippy::unused_self)]
    pub(crate) fn log(&self, _sql: &str) {}

    /// Arms `point` to fail at its `after + 1`-th hit (0: the next one). One
    /// arm per point; arming again replaces it.
    ///
    /// # Panics
    /// When `point` isn't one of [`POINTS`].
    #[cfg(feature = "faults")]
    pub fn fail_at(&self, point: &'static str, after: u32) {
        assert!(POINTS.contains(&point), "unknown fault point {point}");
        let mut inner = self.inner.borrow_mut();
        inner.armed.retain(|(p, _)| *p != point);
        inner.armed.push((point, after));
    }

    /// Disarms every point.
    #[cfg(feature = "faults")]
    pub fn clear(&self) {
        self.inner.borrow_mut().armed.clear();
    }

    /// Whether any armed point hasn't fired yet.
    #[cfg(feature = "faults")]
    #[must_use]
    pub fn pending(&self) -> bool {
        !self.inner.borrow().armed.is_empty()
    }

    /// Starts recording query statements, dropping any earlier log.
    #[cfg(feature = "faults")]
    pub fn start_log(&self) {
        self.inner.borrow_mut().log = Some(Vec::new());
    }

    /// The statements recorded since [`Faults::start_log`], and stops.
    #[cfg(feature = "faults")]
    #[must_use]
    pub fn take_log(&self) -> Vec<String> {
        self.inner.borrow_mut().log.take().unwrap_or_default()
    }
}
