//! Decent's stolen-machine serial list — the DE1 serials Decent publishes as
//! stolen from customers or "lost" by a carrier in the last mile, exported by
//! de1app for other apps to consume (`machine.tcl`
//! `export_stolen_serials_json`, the single source of truth being
//! `stolen_machine_sn_list`). de1app checks the connected machine's serial
//! (`check_for_missing_sn`) and tells the owner to contact Decent support.
//!
//! The file looks like:
//!
//! ```json
//! { "updated": "2026-09-29", "stolen_sns": ["76", "317", "380"] }
//! ```
//!
//! Crema's UX (user decision, 2026-10): a non-blocking one-line notice in
//! Settings → Machine, the list fetched at most once per
//! [`STOLEN_SERIALS_REFRESH_MS`] and cached, every failure ignored silently.
//! This module is the pure half — the shells own the fetch and the cache.

/// Where Decent publishes the list (de1app `machine.tcl`).
pub const STOLEN_SERIALS_URL: &str =
    "https://raw.githubusercontent.com/decentespresso/de1app/main/de1plus/stolen_serials.json";

/// How often the shells may refetch the list, ms — at most once a day.
pub const STOLEN_SERIALS_REFRESH_MS: u64 = 24 * 60 * 60 * 1000;

/// The serials in a list document, or `None` when `list_json` is not a list
/// document at all (not JSON, or no `stolen_sns` array) — so a shell never
/// caches an error page over a good list. Entries may be strings (what
/// de1app writes) or integers; anything else is skipped.
#[must_use]
pub fn parse_stolen_serials(list_json: &str) -> Option<Vec<u32>> {
    let doc: serde_json::Value = serde_json::from_str(list_json).ok()?;
    let entries = doc.get("stolen_sns")?.as_array()?;
    Some(
        entries
            .iter()
            .filter_map(|e| match e {
                serde_json::Value::String(s) => s.trim().parse::<u32>().ok(),
                serde_json::Value::Number(n) => n.as_u64().and_then(|n| u32::try_from(n).ok()),
                _ => None,
            })
            .collect(),
    )
}

/// Whether `list_json` is a usable list document (see
/// [`parse_stolen_serials`]) — the shells cache a fetched body only if so.
#[must_use]
pub fn stolen_serials_list_is_valid(list_json: &str) -> bool {
    parse_stolen_serials(list_json).is_some()
}

/// Whether the DE1 with serial `serial` (MMR `SerialNumber`, the decimal
/// number de1app keeps as `::settings(sn)`) is on the list. `0` is the
/// firmware's "no serial" and never matches; a malformed list matches
/// nothing.
#[must_use]
pub fn serial_on_stolen_list(serial: u32, list_json: &str) -> bool {
    serial != 0 && parse_stolen_serials(list_json).is_some_and(|list| list.contains(&serial))
}

/// Whether the cached list is due a refetch: never fetched, older than
/// [`STOLEN_SERIALS_REFRESH_MS`], or stamped in the future (a clock that
/// moved backwards).
#[must_use]
pub fn stolen_serials_refresh_due(last_fetch_ms: Option<u64>, now_ms: u64) -> bool {
    match last_fetch_ms {
        None => true,
        Some(last) => last > now_ms || now_ms - last >= STOLEN_SERIALS_REFRESH_MS,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The list as de1app publishes it (2026-09-29).
    const FIXTURE: &str = r#"{
  "updated": "2026-09-29",
  "stolen_sns": ["76", "317", "380", "659", "2276", "2548", "4291", "5317", "5502", "6654", "8980", "10079", "11358"]
}
"#;

    #[test]
    fn a_listed_serial_is_flagged() {
        assert!(serial_on_stolen_list(76, FIXTURE));
        assert!(serial_on_stolen_list(11358, FIXTURE));
        assert!(serial_on_stolen_list(5502, FIXTURE));
    }

    #[test]
    fn an_unlisted_serial_is_not() {
        assert!(!serial_on_stolen_list(6262, FIXTURE));
        assert!(!serial_on_stolen_list(77, FIXTURE));
        // Never a prefix / substring match.
        assert!(!serial_on_stolen_list(7, FIXTURE));
        assert!(!serial_on_stolen_list(1135, FIXTURE));
    }

    #[test]
    fn serial_zero_never_matches() {
        assert!(!serial_on_stolen_list(0, r#"{"stolen_sns": ["0"]}"#));
    }

    #[test]
    fn numeric_and_padded_entries_are_accepted() {
        let list = r#"{"stolen_sns": [76, " 317 ", "x", null, -4]}"#;
        assert_eq!(parse_stolen_serials(list), Some(vec![76, 317]));
        assert!(serial_on_stolen_list(317, list));
    }

    #[test]
    fn a_malformed_body_is_not_a_list_and_matches_nothing() {
        for body in [
            "",
            "404: Not Found",
            "<html></html>",
            "{}",
            r#"{"stolen_sns": "76"}"#,
        ] {
            assert!(!stolen_serials_list_is_valid(body), "{body:?}");
            assert!(!serial_on_stolen_list(76, body), "{body:?}");
        }
        assert!(stolen_serials_list_is_valid(FIXTURE));
        assert!(stolen_serials_list_is_valid(r#"{"stolen_sns": []}"#));
    }

    #[test]
    fn refresh_is_due_daily() {
        let day = STOLEN_SERIALS_REFRESH_MS;
        assert!(stolen_serials_refresh_due(None, 0));
        assert!(!stolen_serials_refresh_due(Some(1_000), 1_000 + day - 1));
        assert!(stolen_serials_refresh_due(Some(1_000), 1_000 + day));
        // A clock that went backwards refetches rather than waiting forever.
        assert!(stolen_serials_refresh_due(Some(5_000), 1_000));
    }
}
