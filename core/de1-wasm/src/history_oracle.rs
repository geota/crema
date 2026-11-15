//! The SQLite history's dev oracle (#310 SPEC §3, N7): stage 1 compares each
//! index answer with the core's reference rules over the rows in memory. Only
//! the `dev-oracle` feature builds this, so the production bundle is unchanged.

use wasm_bindgen::prelude::*;

/// The ids a `HistoryQuery` lists over a JSON array of rows, with the live
/// libraries (`Live` JSON), as a JSON array. See
/// `de1_history::reference::filter_sort`.
#[wasm_bindgen(js_name = historyReferenceFilterSort)]
pub fn history_reference_filter_sort(
    rows_json: &str,
    query_json: &str,
    live_json: &str,
) -> Result<String, String> {
    de1_history::reference::filter_sort_json(rows_json, query_json, live_json)
}

/// The stat strip over the rows a `HistoryQuery` lists, as `BrewHistoryStats`
/// JSON. See `de1_history::reference::stats`.
#[wasm_bindgen(js_name = historyReferenceStats)]
pub fn history_reference_stats(
    rows_json: &str,
    query_json: &str,
    live_json: &str,
) -> Result<String, String> {
    de1_history::reference::stats_json(rows_json, query_json, live_json)
}
