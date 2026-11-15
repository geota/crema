//! `de1-history`: the shot history's database rules (#310,
//! `.scratch/history-sqlite/SPEC.md`).
//!
//! This is the pure half. It turns a stored row into the columns the schema
//! holds ([`derive`]), folds text for search ([`fold`]), types the queries
//! ([`query`]) and their cursors ([`cursor`]), works out what a write changed
//! ([`change`]), holds the schema text ([`schema`]) and builds the SQL for
//! every query ([`sql`]), and combines per-family stat sums ([`stats`]).
//! [`reference`], behind the `dev-oracle` feature, is the same rules over
//! rows in memory: the spec the SQL is tested against.
//!
//! Nothing here opens a database or touches a file: the `store` feature is
//! reserved for C2's `rusqlite` code. Shells never write SQL (SPEC rule 5).

pub mod change;
pub mod cursor;
pub mod derive;
pub mod fold;
pub mod query;
#[cfg(feature = "dev-oracle")]
pub mod reference;
pub mod schema;
pub mod sql;
pub mod stats;

pub use change::ChangeKinds;
pub use cursor::{Cursor, CursorError};
pub use derive::{DERIVE_V, Derived, derive_row, derive_value};
pub use fold::{MIN_SEARCH_CHARS, fold, search_needle};
pub use query::{
    Anchor, BeanScope, Dir, HistoryQuery, Live, LiveBean, LiveMethod, Sort, SortKey, SortValue,
};
pub use stats::{FamilySums, combine, family_sums};
