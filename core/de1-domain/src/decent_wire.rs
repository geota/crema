//! Pure parsing of the Decent Espresso support API's HTTP replies — the
//! three calls the shells make to link an account and upload shots
//! (geota/crema#84), as de1app's `shot_upload` plugin and decaid's
//! `DecentAccountService` speak them:
//!
//! ```text
//! GET  /support/api/login_test          Basic email:PASSWORD → token text
//! GET  /support/api/sn?onlyespressomachines=1&withskus=1
//!                                       Basic email:token    → "serial [sku]" lines
//! POST /support/api/shot_upload[?replace=1]
//!                                       Basic email:token, JSON ShotRecord
//!                                       → {"ok":true,"stored":…,"id":…}
//! ```
//!
//! The shells own the transport (fetch / OkHttp) and hand the status code
//! and body text here; every classification rule — what counts as a token,
//! which statuses are an auth failure, a permanent rejection or worth a
//! retry, how the stored shot's id is pulled out of the upload answer —
//! lives in one place. Ported from the web `$lib/decent/api.ts` and the
//! Android `DecentClient.kt`, reconciled where they had drifted.
//!
//! A transport failure (no HTTP status at all) never reaches this module;
//! the shell maps it straight to its own retry path.
//!
//! The replies are adjacently-tagged `#[typeshare]` enums
//! (`{ "type": "Token", "content": { "token": "…" } }`, a unit variant is
//! `{ "type": "Rejected" }`) — the shape typeshare 1.x requires for
//! algebraic enums — so both shells get typed replies.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use typeshare::typeshare;

/// Origin of every Decent URL.
pub const DECENT_BASE: &str = "https://decentespresso.com";

/// How much of a failing reply body is kept for the user-facing message.
const BODY_SNIPPET_CHARS: usize = 200;

/// A DE1 registered on the linked account.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DecentMachine {
    /// The machine's serial number, as the server lists it.
    pub serial: String,
    /// Raw SKU text when the server sent one (`"DE1PRO"`, …), else `""`.
    pub sku: String,
}

/// The `login_test` answer, classified.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum DecentLoginReply {
    /// A good login: the account token (the one-way "encrypted password"
    /// de1app stores) every later call sends in the password slot.
    Token {
        /// The account token, trimmed.
        token: String,
    },
    /// The server refused the login (HTTP 401, or its `0` / too-short
    /// answer). The password is wrong — do not retry.
    Rejected,
    /// A non-2xx answer other than 401 — worth retrying later.
    Retry {
        /// The HTTP status.
        status: u16,
        /// The reply body, trimmed and cut to 200 characters.
        detail: String,
    },
}

/// The `sn` (registered machines) answer, classified.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum DecentMachinesReply {
    /// The DE1s on the account, de-duplicated by serial, server order.
    Machines {
        /// The registered machines (possibly empty).
        machines: Vec<DecentMachine>,
    },
    /// The stored token stopped working (HTTP 401, or a 2xx `0` answer) —
    /// the user must re-link.
    Auth,
    /// A non-2xx answer other than 401 — worth retrying later.
    Retry {
        /// The HTTP status.
        status: u16,
        /// The reply body, trimmed and cut to 200 characters.
        detail: String,
    },
}

/// The `shot_upload` answer, classified.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum DecentUploadReply {
    /// The server holds the shot — stored now, or (`"duplicate":true` on a
    /// first upload) already held from an earlier send.
    Uploaded {
        /// The stored shot's server id, when the answer carried one —
        /// persist it as `StoredShot::decent_id`.
        id: Option<String>,
    },
    /// A `?replace=1` upload the server answered `"duplicate":true`: it kept
    /// its EARLIER copy and the edit was not stored (Decenza `f5c3ce3e`,
    /// seen live on 2026-10-04). Not a success and not worth an immediate
    /// retry — the shot's existing upload stands, the edit stays unsent.
    NotReplaced {
        /// The server id of the copy it kept, when the answer carried one.
        id: Option<String>,
    },
    /// The stored token stopped working (HTTP 401, or a 2xx whose body is
    /// exactly `0`) — the user must re-link; the shot stays queued.
    Auth,
    /// HTTP 403: the shot's machine serial is not registered to the linked
    /// account (de1app `5b09f6aa`, Decenza `DECENT_UPLOAD.md`). Permanent
    /// until the owner adds the machine at decentespresso.com — do not
    /// retry, do not park the shot for retry, and do not mark it rejected
    /// (it uploads once the machine is added).
    NotOnAccount,
    /// The server refused this shot for good (a 4xx other than
    /// 401 / 403 / 408 / 429) — do not retry it.
    Rejected {
        /// The HTTP status.
        status: u16,
        /// The reply body, trimmed and cut to 200 characters.
        body: String,
    },
    /// A 5xx / 408 / 429 / other non-2xx answer, or a 2xx that is not the
    /// upload API's JSON answer (a captive portal's page, a proxy) — worth
    /// retrying.
    Retry {
        /// The HTTP status.
        status: u16,
        /// The reply body, trimmed and cut to 200 characters.
        detail: String,
    },
}

fn is_success(status: u16) -> bool {
    (200..300).contains(&status)
}

fn snippet(body: &str) -> String {
    body.chars().take(BODY_SNIPPET_CHARS).collect()
}

/// Classify a `login_test` reply.
///
/// 401 → [`Rejected`](DecentLoginReply::Rejected); any other non-2xx →
/// [`Retry`](DecentLoginReply::Retry). For a 2xx, the trimmed body is the
/// token when it is a real one — de1app's rule: non-empty, not `"0"`, and
/// longer than 4 characters — otherwise the login was rejected.
///
/// The same call with the stored token in the password slot verifies a
/// linked account: `Token` means it still works, `Rejected` that it no
/// longer does.
#[must_use]
pub fn decent_login_token(status: u16, body: &str) -> DecentLoginReply {
    let text = body.trim();
    if status == 401 {
        return DecentLoginReply::Rejected;
    }
    if !is_success(status) {
        return DecentLoginReply::Retry {
            status,
            detail: snippet(text),
        };
    }
    if text.is_empty() || text == "0" || text.chars().count() <= 4 {
        return DecentLoginReply::Rejected;
    }
    DecentLoginReply::Token {
        token: text.to_owned(),
    }
}

/// Classify an `sn` (registered machines) reply.
///
/// 401 → [`Auth`](DecentMachinesReply::Auth); any other non-2xx →
/// [`Retry`](DecentMachinesReply::Retry); a 2xx whose trimmed body is `0`
/// (the server's bad-token answer) → `Auth`; otherwise the body is parsed
/// by [`parse_decent_machines`].
#[must_use]
pub fn decent_machines(status: u16, body: &str) -> DecentMachinesReply {
    let text = body.trim();
    if status == 401 {
        return DecentMachinesReply::Auth;
    }
    if !is_success(status) {
        return DecentMachinesReply::Retry {
            status,
            detail: snippet(text),
        };
    }
    if text == "0" {
        return DecentMachinesReply::Auth;
    }
    DecentMachinesReply::Machines {
        machines: parse_decent_machines(text),
    }
}

/// Parse the `sn` body: one machine per line, `serial [sku]` separated by
/// whitespace, blank lines skipped, de-duplicated by serial (first wins),
/// server order kept. Both shells agreed on this rule.
#[must_use]
pub fn parse_decent_machines(body: &str) -> Vec<DecentMachine> {
    let mut out: Vec<DecentMachine> = Vec::new();
    for line in body.lines() {
        let mut parts = line.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        if out.iter().any(|m| m.serial == serial) {
            continue;
        }
        out.push(DecentMachine {
            serial: serial.to_owned(),
            sku: parts.next().unwrap_or("").to_owned(),
        });
    }
    out
}

/// Classify a `shot_upload` reply. `replace` is whether the caller ASKED
/// for a replace (`?replace=1`) — not whether a transport-level resend added
/// it, since a first upload the server already holds is still an upload.
///
/// - 2xx whose trimmed body is exactly `0` → [`Auth`](DecentUploadReply::Auth)
///   (the server's bad-token answer — both shells used to record it as an
///   upload with id `"0"`).
/// - 2xx with the upload API's JSON object (see [`decent_upload_accepted`])
///   → [`Uploaded`](DecentUploadReply::Uploaded) with the id from
///   [`extract_decent_id`], except a replace answered `"duplicate":true` →
///   [`NotReplaced`](DecentUploadReply::NotReplaced) (Decenza `f5c3ce3e`).
/// - Any other 2xx (HTML, plain text, empty, `"ok":false`) →
///   [`Retry`](DecentUploadReply::Retry): nothing was stored (Decenza
///   `decentshotuploader.cpp`: "a captive portal's page, a proxy, a changed
///   API").
/// - 401 → `Auth`.
/// - 403 → [`NotOnAccount`](DecentUploadReply::NotOnAccount).
/// - Other 4xx except 408 / 429 → [`Rejected`](DecentUploadReply::Rejected)
///   (decaid's permanent-rejection rule).
/// - Everything else → `Retry`.
#[must_use]
pub fn decent_upload_reply(status: u16, body: &str, replace: bool) -> DecentUploadReply {
    let text = body.trim();
    if is_success(status) {
        if text == "0" {
            return DecentUploadReply::Auth;
        }
        let Some(answer) = decent_upload_accepted(text) else {
            return DecentUploadReply::Retry {
                status,
                detail: snippet(text),
            };
        };
        let id = extract_decent_id(text);
        return if replace && answer.duplicate {
            DecentUploadReply::NotReplaced { id }
        } else {
            DecentUploadReply::Uploaded { id }
        };
    }
    if status == 401 {
        return DecentUploadReply::Auth;
    }
    if status == 403 {
        return DecentUploadReply::NotOnAccount;
    }
    if (400..500).contains(&status) && !matches!(status, 408 | 429) {
        return DecentUploadReply::Rejected {
            status,
            body: snippet(text),
        };
    }
    DecentUploadReply::Retry {
        status,
        detail: snippet(text),
    }
}

/// What a 2xx upload answer that IS the API's says.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct UploadAnswer {
    /// `"duplicate":true` — the server already held this shot.
    duplicate: bool,
}

/// Is `text` the upload API's own answer? It is a JSON object with
/// `"ok":true` — what decentespresso.com sends (`{"ok":true,"stored":true,
/// "id":"…"}`, `{"ok":true,"stored":false,"duplicate":true,"id":"…"}`,
/// Decenza `tst_decentshotupload`) and what Decenza requires — or, with no
/// `ok` key at all, an object carrying a real shot id (the `{id}` shape
/// this API was documented with). `"ok":false`, any other JSON, and
/// anything that is not JSON (a captive portal's HTML) is not.
fn decent_upload_accepted(text: &str) -> Option<UploadAnswer> {
    let Ok(Value::Object(o)) = serde_json::from_str::<Value>(text) else {
        return None;
    };
    let accepted = match o.get("ok") {
        Some(ok) => ok.as_bool() == Some(true),
        None => extract_decent_id(text).is_some(),
    };
    accepted.then(|| UploadAnswer {
        duplicate: o.get("duplicate").and_then(Value::as_bool) == Some(true),
    })
}

/// A machine serial worth sending to Decent, or `None` when it is unknown.
///
/// Blank, and `0` (any number of zeros), are unknown: some older DE1s answer
/// the serial-number MMR read with 0 (Decenza `6c83ca06`, issue #2013), and
/// an upload filed under `"0"` can only ever come back 403. Callers treat
/// `None` as "serial not read yet" — the upload waits instead of looping.
#[must_use]
pub fn decent_known_serial(serial: Option<&str>) -> Option<String> {
    let s = serial.map(str::trim).filter(|s| !s.is_empty())?;
    if s.bytes().all(|b| b == b'0') {
        return None;
    }
    Some(s.to_owned())
}

/// Pull the stored shot's id out of a 2xx upload answer.
///
/// - A JSON object: the first non-null of `id` / `shot_id` / `shotId`.
/// - A bare JSON string or number.
/// - Otherwise (not JSON), a bare token matching `^[A-Za-z0-9_-]{1,64}$`.
///
/// A string id must be non-blank and not `ok` (any case); a numeric `0`
/// or the string `"0"` is never an id. Anything else (booleans, arrays,
/// nested objects, prose) → `None`.
#[must_use]
pub fn extract_decent_id(body: &str) -> Option<String> {
    let text = body.trim();
    if text.is_empty() {
        return None;
    }
    match serde_json::from_str::<Value>(text) {
        Ok(Value::Object(o)) => ["id", "shot_id", "shotId"]
            .iter()
            .find_map(|k| o.get(*k).filter(|v| !v.is_null()))
            .and_then(id_of_value),
        Ok(v) => id_of_value(&v),
        Err(_) => {
            let bare = text.len() <= 64
                && text
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
            if bare { id_of_str(text) } else { None }
        }
    }
}

fn id_of_value(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => id_of_str(s.trim()),
        Value::Number(n) => {
            let s = if let Some(i) = n.as_i64() {
                i.to_string()
            } else if let Some(u) = n.as_u64() {
                u.to_string()
            } else {
                let f = n.as_f64()?;
                if f == 0.0 {
                    return None;
                }
                f.to_string()
            };
            id_of_str(&s)
        }
        _ => None,
    }
}

fn id_of_str(s: &str) -> Option<String> {
    if s.is_empty() || s == "0" || s.eq_ignore_ascii_case("ok") {
        None
    } else {
        Some(s.to_owned())
    }
}

/// The uploaded shot's public page — the link Decent's own "copy link"
/// button hands out (`https://decentespresso.com/shot/<serial>/<id>`),
/// viewable without an account, so it doubles as the share link.
///
/// `Some` only when the serial is known ([`decent_known_serial`]: not blank,
/// not `0`), the id is present and non-blank, and the id is a real server id — not the legacy `uploaded:` placeholder
/// early builds stored. Otherwise `None`: there is no fallback to the
/// account's shot-history page (the shell hides the link instead). Both
/// path segments are percent-encoded like JS `encodeURIComponent`.
#[must_use]
pub fn decent_shot_view_url(serial: Option<&str>, decent_id: Option<&str>) -> Option<String> {
    let serial = decent_known_serial(serial)?;
    let id = decent_id.map(str::trim).filter(|s| !s.is_empty())?;
    if id.starts_with("uploaded:") {
        return None;
    }
    Some(format!(
        "{DECENT_BASE}/shot/{}/{}",
        encode_uri_component(&serial),
        encode_uri_component(id)
    ))
}

/// JS `encodeURIComponent`: keep `A-Z a-z 0-9 - _ . ! ~ * ' ( )`,
/// percent-encode every other UTF-8 byte (uppercase hex).
fn encode_uri_component(s: &str) -> String {
    use std::fmt::Write as _;
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&b) {
            out.push(char::from(b));
        } else {
            // Writing to a String cannot fail.
            let _ = write!(out, "%{b:02X}");
        }
    }
    out
}

/// JSON-bridged [`decent_login_token`] for the wasm + uniffi facades.
///
/// # Errors
/// The serialise error string (effectively never) — RS5: surfaced rather
/// than an empty reply the shell would misread.
pub fn decent_login_token_json(status: u16, body: &str) -> Result<String, String> {
    serde_json::to_string(&decent_login_token(status, body)).map_err(|e| e.to_string())
}

/// JSON-bridged [`decent_machines`] for the wasm + uniffi facades.
///
/// # Errors
/// The serialise error string (effectively never).
pub fn decent_machines_json(status: u16, body: &str) -> Result<String, String> {
    serde_json::to_string(&decent_machines(status, body)).map_err(|e| e.to_string())
}

/// JSON-bridged [`decent_upload_reply`] for the wasm + uniffi facades.
///
/// # Errors
/// The serialise error string (effectively never).
pub fn decent_upload_reply_json(status: u16, body: &str, replace: bool) -> Result<String, String> {
    serde_json::to_string(&decent_upload_reply(status, body, replace)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    fn retry_login(status: u16, detail: &str) -> DecentLoginReply {
        DecentLoginReply::Retry {
            status,
            detail: detail.to_owned(),
        }
    }

    // ── login_test ───────────────────────────────────────────────────

    #[test]
    fn login_returns_the_trimmed_token() {
        assert_eq!(
            decent_login_token(200, "abcdef0123456789\n"),
            DecentLoginReply::Token {
                token: "abcdef0123456789".to_owned()
            }
        );
    }

    #[test]
    fn login_zero_empty_or_short_answers_are_rejections() {
        for body in ["0", "", "  ", "abcd", " 0 \n"] {
            assert_eq!(
                decent_login_token(200, body),
                DecentLoginReply::Rejected,
                "{body:?}"
            );
        }
        // Five characters is the shortest real token.
        assert!(matches!(
            decent_login_token(204, "abcde"),
            DecentLoginReply::Token { .. }
        ));
    }

    #[test]
    fn login_401_is_rejected_and_other_failures_retry() {
        assert_eq!(decent_login_token(401, "nope"), DecentLoginReply::Rejected);
        assert_eq!(decent_login_token(500, " oops "), retry_login(500, "oops"));
        assert_eq!(decent_login_token(403, ""), retry_login(403, ""));
        assert_eq!(decent_login_token(302, "moved"), retry_login(302, "moved"));
    }

    // ── sn ───────────────────────────────────────────────────────────

    #[test]
    fn machines_are_parsed_de_duplicated_with_optional_skus() {
        assert_eq!(
            decent_machines(200, "6262 DE1PRO\n\n7000\n6262\n"),
            DecentMachinesReply::Machines {
                machines: vec![
                    DecentMachine {
                        serial: "6262".to_owned(),
                        sku: "DE1PRO".to_owned()
                    },
                    DecentMachine {
                        serial: "7000".to_owned(),
                        sku: String::new()
                    },
                ]
            }
        );
    }

    #[test]
    fn machine_lines_tolerate_crlf_and_extra_whitespace() {
        assert_eq!(
            parse_decent_machines("  6262\tDE1XL  extra\r\n   \r\n7000  \r\n"),
            vec![
                DecentMachine {
                    serial: "6262".to_owned(),
                    sku: "DE1XL".to_owned()
                },
                DecentMachine {
                    serial: "7000".to_owned(),
                    sku: String::new()
                },
            ]
        );
        assert_eq!(
            decent_machines(200, ""),
            DecentMachinesReply::Machines {
                machines: Vec::new()
            }
        );
    }

    #[test]
    fn machines_auth_and_retry() {
        assert_eq!(decent_machines(401, ""), DecentMachinesReply::Auth);
        assert_eq!(decent_machines(200, " 0\n"), DecentMachinesReply::Auth);
        assert_eq!(
            decent_machines(503, "busy"),
            DecentMachinesReply::Retry {
                status: 503,
                detail: "busy".to_owned()
            }
        );
    }

    // ── shot_upload ──────────────────────────────────────────────────

    fn uploaded(id: Option<&str>) -> DecentUploadReply {
        DecentUploadReply::Uploaded {
            id: id.map(str::to_owned),
        }
    }

    /// Reply bodies decentespresso.com sends, from Decenza's
    /// `tests/tst_decentshotupload.cpp` (captured against the live server).
    const STORED: &str = r#"{"ok":true,"stored":true,"id":"srv-1"}"#;
    const REPLACED: &str = r#"{"ok":true,"stored":true,"replaced":true,"id":"srv-1"}"#;
    const DUPLICATE: &str = r#"{"ok":true,"stored":false,"duplicate":true,"id":"srv-1"}"#;
    const NOT_YOUR_MACHINE: &str = r#"{"ok":false,"error":"not your machine"}"#;
    const NO_PROFILE: &str = r#"{"ok":false,"error":"no workflow.profile"}"#;
    const CAPTIVE_PORTAL: &str = "<html>Sign in to Wi-Fi</html>";

    #[test]
    fn upload_api_answers_are_uploads_with_their_id() {
        assert_eq!(
            decent_upload_reply(200, STORED, false),
            uploaded(Some("srv-1"))
        );
        assert_eq!(
            decent_upload_reply(200, REPLACED, true),
            uploaded(Some("srv-1"))
        );
        // An id-only object (the documented `{id}` shape) still counts.
        assert_eq!(
            decent_upload_reply(200, r#"{"id": 4242}"#, false),
            uploaded(Some("4242"))
        );
        assert_eq!(
            decent_upload_reply(201, r#"{"shot_id": "abc-1"}"#, false),
            uploaded(Some("abc-1"))
        );
        assert_eq!(
            decent_upload_reply(200, r#"{"shotId": "x9"}"#, false),
            uploaded(Some("x9"))
        );
        // `"ok":true` with no id is still the API's answer.
        assert_eq!(
            decent_upload_reply(200, r#"{"ok":true}"#, false),
            uploaded(None)
        );
    }

    #[test]
    fn a_first_upload_answered_duplicate_is_stored() {
        // Decenza firstUploadAnsweredDuplicateIsStored: after a reinstall the
        // server already holds the shot — that is an upload.
        assert_eq!(
            decent_upload_reply(200, DUPLICATE, false),
            uploaded(Some("srv-1"))
        );
    }

    #[test]
    fn a_replace_answered_duplicate_was_not_applied() {
        // Decenza f5c3ce3e: decentespresso.com answers ?replace=1 with
        // "duplicate" and keeps the earlier copy.
        assert_eq!(
            decent_upload_reply(200, DUPLICATE, true),
            DecentUploadReply::NotReplaced {
                id: Some("srv-1".to_owned())
            }
        );
        assert_eq!(
            decent_upload_reply(200, r#"{"ok":true,"duplicate":true}"#, true),
            DecentUploadReply::NotReplaced { id: None }
        );
        // `"duplicate":false` is a replace that landed.
        assert_eq!(
            decent_upload_reply(200, r#"{"ok":true,"duplicate":false,"id":"7"}"#, true),
            uploaded(Some("7"))
        );
    }

    #[test]
    fn a_2xx_that_is_not_the_api_answer_is_retried_not_uploaded() {
        for body in [
            CAPTIVE_PORTAL,
            "",
            "ok",
            "stored",
            "4242",
            r#""abc""#,
            "[1]",
            "null",
            r#"{"ok":false}"#,
            r#"{"ok":"yes","id":"1"}"#,
            r#"{"status":"ok"}"#,
            r#"{"id":0}"#,
        ] {
            assert!(
                matches!(
                    decent_upload_reply(200, body, false),
                    DecentUploadReply::Retry { status: 200, .. }
                ),
                "{body:?}"
            );
            // A replace too: an odd 2xx is never NotReplaced.
            assert!(
                matches!(
                    decent_upload_reply(200, body, true),
                    DecentUploadReply::Retry { .. }
                ),
                "{body:?}"
            );
        }
        assert_eq!(
            decent_upload_reply(200, CAPTIVE_PORTAL, false),
            DecentUploadReply::Retry {
                status: 200,
                detail: CAPTIVE_PORTAL.to_owned()
            }
        );
    }

    #[test]
    fn upload_2xx_zero_is_an_auth_failure_not_an_id() {
        // Both shells used to store this as an upload with id "0".
        assert_eq!(
            decent_upload_reply(200, "0", false),
            DecentUploadReply::Auth
        );
        assert_eq!(
            decent_upload_reply(200, " 0\n", true),
            DecentUploadReply::Auth
        );
    }

    #[test]
    fn upload_401_is_auth() {
        assert_eq!(
            decent_upload_reply(401, "bad", false),
            DecentUploadReply::Auth
        );
    }

    #[test]
    fn upload_403_is_a_serial_not_on_the_account() {
        // de1app 5b09f6aa / Decenza: permanent until the machine is added —
        // neither a retry nor a rejection of the shot.
        assert_eq!(
            decent_upload_reply(403, NOT_YOUR_MACHINE, false),
            DecentUploadReply::NotOnAccount
        );
        assert_eq!(
            decent_upload_reply(403, "", true),
            DecentUploadReply::NotOnAccount
        );
    }

    #[test]
    fn upload_permanent_4xx_is_rejected_with_a_truncated_body() {
        assert_eq!(
            decent_upload_reply(400, NO_PROFILE, false),
            DecentUploadReply::Rejected {
                status: 400,
                body: NO_PROFILE.to_owned()
            }
        );
        let long = "é".repeat(300);
        let DecentUploadReply::Rejected { status, body } = decent_upload_reply(422, &long, false)
        else {
            panic!("expected Rejected");
        };
        assert_eq!(status, 422);
        assert_eq!(body.chars().count(), 200);
    }

    #[test]
    fn upload_transient_statuses_retry() {
        for status in [408, 429, 500, 503, 302, 100] {
            assert!(
                matches!(
                    decent_upload_reply(status, "x", false),
                    DecentUploadReply::Retry { status: s, .. } if s == status
                ),
                "{status}"
            );
        }
        let DecentUploadReply::Retry { detail, .. } =
            decent_upload_reply(502, &"x".repeat(500), false)
        else {
            panic!("expected Retry");
        };
        assert_eq!(detail.len(), 200);
    }

    #[test]
    fn serial_zero_or_blank_is_unknown() {
        assert_eq!(decent_known_serial(Some("6262")).as_deref(), Some("6262"));
        assert_eq!(decent_known_serial(Some(" 6262 ")).as_deref(), Some("6262"));
        assert_eq!(decent_known_serial(Some("1000")).as_deref(), Some("1000"));
        assert_eq!(
            decent_known_serial(Some("SIM-DE1")).as_deref(),
            Some("SIM-DE1")
        );
        for unknown in [
            None,
            Some(""),
            Some("  "),
            Some("0"),
            Some(" 0 "),
            Some("000"),
        ] {
            assert_eq!(decent_known_serial(unknown), None, "{unknown:?}");
        }
    }

    #[test]
    fn extract_id_rules() {
        assert_eq!(
            extract_decent_id(r#"{"id": 4242}"#).as_deref(),
            Some("4242")
        );
        // `id` null falls through to the next key.
        assert_eq!(
            extract_decent_id(r#"{"id": null, "shot_id": 7}"#).as_deref(),
            Some("7")
        );
        assert_eq!(extract_decent_id(r#"{"id": ""}"#), None);
        assert_eq!(extract_decent_id(r#"{"id": 0}"#), None);
        assert_eq!(extract_decent_id(r#"{"id": "0"}"#), None);
        assert_eq!(extract_decent_id(r#"{"id": {"x": 1}}"#), None);
        assert_eq!(extract_decent_id(r#"{"status": "ok"}"#), None);
        assert_eq!(extract_decent_id(r#""abc""#).as_deref(), Some("abc"));
        assert_eq!(extract_decent_id(r#""ok""#), None);
        assert_eq!(extract_decent_id(r#""OK""#), None);
        assert_eq!(extract_decent_id("99").as_deref(), Some("99"));
        assert_eq!(extract_decent_id("99.0").as_deref(), Some("99"));
        assert_eq!(extract_decent_id("0"), None);
        assert_eq!(extract_decent_id("0.0"), None);
        assert_eq!(extract_decent_id("true"), None);
        assert_eq!(extract_decent_id("[1]"), None);
        assert_eq!(extract_decent_id("null"), None);
        assert_eq!(extract_decent_id("abc").as_deref(), Some("abc"));
        assert_eq!(extract_decent_id("a_B-9").as_deref(), Some("a_B-9"));
        assert_eq!(extract_decent_id("ok"), None);
        assert_eq!(extract_decent_id("Ok"), None);
        assert_eq!(extract_decent_id(""), None);
        assert_eq!(extract_decent_id("stored shot 12"), None);
        assert_eq!(extract_decent_id(&"a".repeat(65)), None);
        assert_eq!(extract_decent_id(&"a".repeat(64)), Some("a".repeat(64)));
    }

    // ── view URL ─────────────────────────────────────────────────────

    #[test]
    fn view_url_needs_a_serial_and_a_real_id() {
        assert_eq!(
            decent_shot_view_url(Some("6262"), Some("99")).as_deref(),
            Some("https://decentespresso.com/shot/6262/99")
        );
        assert_eq!(decent_shot_view_url(None, Some("99")), None);
        assert_eq!(decent_shot_view_url(Some("6262"), None), None);
        assert_eq!(decent_shot_view_url(Some(" "), Some("99")), None);
        assert_eq!(decent_shot_view_url(Some("0"), Some("99")), None);
        assert_eq!(decent_shot_view_url(Some("6262"), Some("")), None);
        assert_eq!(
            decent_shot_view_url(Some("6262"), Some("uploaded:crema-shot:1")),
            None
        );
    }

    #[test]
    fn view_url_encodes_like_encode_uri_component() {
        assert_eq!(
            decent_shot_view_url(Some("SN 1/2"), Some("a:b?é")).as_deref(),
            Some("https://decentespresso.com/shot/SN%201%2F2/a%3Ab%3F%C3%A9")
        );
        assert_eq!(encode_uri_component("-_.!~*'()"), "-_.!~*'()");
    }

    // ── wire shape ───────────────────────────────────────────────────

    #[test]
    fn replies_serialise_adjacently_tagged() {
        let v = |s: Result<String, String>| serde_json::from_str::<Value>(&s.unwrap()).unwrap();
        assert_eq!(
            v(decent_login_token_json(200, "abcdef")),
            json!({ "type": "Token", "content": { "token": "abcdef" } })
        );
        assert_eq!(
            v(decent_login_token_json(401, "")),
            json!({ "type": "Rejected" })
        );
        assert_eq!(
            v(decent_machines_json(200, "6262 DE1PRO")),
            json!({ "type": "Machines", "content": { "machines": [{ "serial": "6262", "sku": "DE1PRO" }] } })
        );
        assert_eq!(v(decent_machines_json(401, "")), json!({ "type": "Auth" }));
        assert_eq!(
            v(decent_upload_reply_json(200, r#"{"ok":true}"#, false)),
            json!({ "type": "Uploaded", "content": { "id": null } })
        );
        assert_eq!(
            v(decent_upload_reply_json(200, DUPLICATE, true)),
            json!({ "type": "NotReplaced", "content": { "id": "srv-1" } })
        );
        assert_eq!(
            v(decent_upload_reply_json(403, "", false)),
            json!({ "type": "NotOnAccount" })
        );
        assert_eq!(
            v(decent_upload_reply_json(422, "no", false)),
            json!({ "type": "Rejected", "content": { "status": 422, "body": "no" } })
        );
        assert_eq!(
            v(decent_upload_reply_json(503, "", false)),
            json!({ "type": "Retry", "content": { "status": 503, "detail": "" } })
        );
    }
}
