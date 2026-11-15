//! The SQLite history's dev oracle (#310 SPEC §3, N7): stage 1 compares each
//! index answer with the core's reference rules over the rows in memory. Only
//! the `dev-oracle` feature builds this, so the APK's library is unchanged.

use crate::CremaError;

/// The ids a `HistoryQuery` lists over a JSON array of rows, with the live
/// libraries (`Live` JSON), as a JSON array. See
/// [`de1_history::reference::filter_sort`].
#[uniffi::export]
pub fn history_reference_filter_sort(
    rows_json: String,
    query_json: String,
    live_json: String,
) -> Result<String, CremaError> {
    Ok(de1_history::reference::filter_sort_json(
        &rows_json,
        &query_json,
        &live_json,
    )?)
}

/// The stat strip over the rows a `HistoryQuery` lists, as `BrewHistoryStats`
/// JSON. See [`de1_history::reference::stats`].
#[uniffi::export]
pub fn history_reference_stats(
    rows_json: String,
    query_json: String,
    live_json: String,
) -> Result<String, CremaError> {
    Ok(de1_history::reference::stats_json(
        &rows_json,
        &query_json,
        &live_json,
    )?)
}
