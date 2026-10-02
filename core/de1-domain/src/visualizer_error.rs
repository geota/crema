//! # visualizer-error
//!
//! The Visualizer-call error **taxonomy** + the **retry policy**
//! ([`VisualizerCallError::is_recoverable`]), ported from the web shell's
//! `visualizer-call.ts` so every shell agrees on which failures are worth a
//! time-based retry (and a future Android sync can map its HTTP errors onto
//! the same closed set).
//!
//! The human-readable `describeVisualizerError` stays **shell-side** — it is
//! i18n / display copy, not policy. Only the recoverable-vs-terminal decision
//! moves here.

use thiserror::Error;

/// The closed Visualizer-call error taxonomy. Mirrors the shell's
/// `VisualizerCallError | ResponseDecodeError` tagged-error union. The shell
/// marshals its tagged error into `(tag, status)` at the wasm boundary via
/// [`VisualizerCallError::from_tag`].
#[derive(Debug, Clone, PartialEq, Eq, Error)]
pub enum VisualizerCallError {
    /// A transport failure (fetch rejection / malformed 2xx body).
    #[error("network error")]
    Network,
    /// A non-2xx HTTP response, carrying its status code.
    #[error("HTTP status {0}")]
    HttpStatus(u16),
    /// Not signed in to Visualizer.
    #[error("not authenticated")]
    NotAuthenticated,
    /// The OAuth refresh failed — the user must sign in again.
    #[error("token refresh failed")]
    TokenRefreshFailed,
    /// A write hit a premium-gated endpoint (402 / 403).
    #[error("premium subscription required for writes")]
    PremiumGated,
    /// The target row no longer exists (404).
    #[error("Visualizer row not found")]
    NotFound,
    /// A 2xx response that didn't decode into the expected shape.
    #[error("unexpected Visualizer response")]
    ResponseDecode,
}

impl VisualizerCallError {
    /// Reconstruct from the shell's tagged-error `_tag` discriminator (plus the
    /// `status` for the HTTP case). An unknown tag falls back to
    /// [`Self::ResponseDecode`] (terminal) — matching the TS exhaustive union,
    /// where no unrecognised tag is recoverable.
    #[must_use]
    pub fn from_tag(tag: &str, status: Option<u16>) -> Self {
        match tag {
            "NetworkError" => Self::Network,
            "HttpStatusError" => Self::HttpStatus(status.unwrap_or(0)),
            "NotAuthenticatedError" => Self::NotAuthenticated,
            "TokenRefreshFailedError" => Self::TokenRefreshFailed,
            "VisualizerPremiumGatedError" => Self::PremiumGated,
            "VisualizerNotFoundError" => Self::NotFound,
            // "ResponseDecodeError" and any unknown tag are terminal.
            _ => Self::ResponseDecode,
        }
    }

    /// Whether the failure is worth a time-based retry through the upload
    /// queue: a transport failure ([`Self::Network`]), or a transient
    /// `5xx` / `408` / `429` / transport-blocked (`status 0`) HTTP response.
    /// Auth / premium / not-found / decode failures need user action, not
    /// time, so they are terminal. Mirrors `isRecoverable`.
    ///
    /// `429` is Visualizer's rate limit (50 requests/min and 200 per 10 min
    /// per IP, 200 per 10 min per user — `openapi.yaml` "Rate limits"): it
    /// clears with time, so it is retried, with the longer
    /// [`retry_backoff_ms`] delay.
    #[must_use]
    pub fn is_recoverable(&self) -> bool {
        match self {
            Self::Network => true,
            Self::HttpStatus(status) => {
                *status == 0
                    || *status == 408
                    || *status == RATE_LIMITED
                    || (*status >= 500 && *status < 600)
            }
            _ => false,
        }
    }
}

/// HTTP 429 Too Many Requests — Visualizer's rate limit.
const RATE_LIMITED: u16 = 429;

/// Visualizer's free-plan daily cap on new shots when the reply doesn't name
/// one (`Shot::DAILY_LIMIT`, 30 since visualizer 3e9ba33c, 2026-09-19).
pub const VISUALIZER_DEFAULT_DAILY_LIMIT: u32 = 30;

/// Detect Visualizer's free-plan daily upload cap and return the cap.
///
/// A free account may create at most `Shot::DAILY_LIMIT` new shots per
/// rolling 24 h. At the cap, `POST /shots/upload` fails the `daily_limit`
/// validation and replies **422** with
/// `{"error":"Could not save the provided file. You've reached your daily
/// limit of 30 shots. Please consider upgrading to a premium account."}`
/// (visualizer `Shot#daily_limit` + `Api::ShotsController#upload`).
///
/// Rule: `status == 422` and the body's `error` string (or the raw body when
/// it isn't JSON) contains `daily limit`, ASCII case-insensitive. Every other
/// 422 (an unparseable file, a bad field) is NOT the quota. Returns the cap
/// parsed from `daily limit of N`, else [`VISUALIZER_DEFAULT_DAILY_LIMIT`];
/// `None` when this isn't the quota reply.
#[must_use]
pub fn visualizer_quota_limit(status: u16, body: &str) -> Option<u32> {
    if status != 422 {
        return None;
    }
    let message = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(str::to_owned))
        .unwrap_or_else(|| body.to_owned())
        .to_ascii_lowercase();
    let at = message.find("daily limit")?;
    let limit = message[at + "daily limit".len()..]
        .trim_start()
        .strip_prefix("of")
        .map(str::trim_start)
        .and_then(|rest| {
            let digits: String = rest.chars().take_while(char::is_ascii_digit).collect();
            digits.parse::<u32>().ok()
        })
        .filter(|n| *n > 0)
        .unwrap_or(VISUALIZER_DEFAULT_DAILY_LIMIT);
    Some(limit)
}

/// Delay before retry number `attempt` (attempts made so far, 0-based) of a
/// recoverable failure with HTTP `status` (`None` for a transport failure).
///
/// - `429`: Visualizer's limits are per minute / per 10 minutes and the reply
///   carries no `Retry-After`, so wait a full minute and double from there:
///   60 s, 120 s, 240 s, … capped at 10 min.
/// - Everything else: 1 s × 2^attempt, capped at 60 s (the queue's existing
///   schedule).
#[must_use]
pub fn retry_backoff_ms(status: Option<u16>, attempt: u32) -> u64 {
    let factor = 1u64 << attempt.min(16);
    if status == Some(RATE_LIMITED) {
        (60_000 * factor).min(600_000)
    } else {
        (1_000 * factor).min(60_000)
    }
}

/// The wasm / FFI bridge entry point: reconstruct the error from the shell's
/// `(tag, status)` marshalling and apply the retry policy. See
/// [`VisualizerCallError::is_recoverable`].
#[must_use]
pub fn is_recoverable(tag: &str, status: Option<u16>) -> bool {
    VisualizerCallError::from_tag(tag, status).is_recoverable()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Cases pinned by the TS `visualizer-call.vitest.ts` retry-policy table.

    #[test]
    fn network_is_recoverable() {
        assert!(is_recoverable("NetworkError", None));
    }

    #[test]
    fn transient_http_is_recoverable() {
        for status in [500u16, 503, 599, 408, 0] {
            assert!(is_recoverable("HttpStatusError", Some(status)), "{status}");
        }
    }

    #[test]
    fn rate_limited_is_recoverable() {
        assert!(is_recoverable("HttpStatusError", Some(429)));
    }

    #[test]
    fn terminal_http_is_not_recoverable() {
        for status in [404u16, 402, 401, 400, 422, 200, 301, 499] {
            assert!(!is_recoverable("HttpStatusError", Some(status)), "{status}");
        }
    }

    #[test]
    fn terminal_tags_are_not_recoverable() {
        for tag in [
            "VisualizerPremiumGatedError",
            "NotAuthenticatedError",
            "TokenRefreshFailedError",
            "ResponseDecodeError",
            "VisualizerNotFoundError",
        ] {
            assert!(!is_recoverable(tag, None), "{tag}");
        }
    }

    #[test]
    fn an_unknown_tag_is_terminal() {
        // Defensive: a tag the core doesn't recognise must not be retried.
        assert!(!is_recoverable("SomethingNew", None));
        assert!(!is_recoverable("SomethingNew", Some(500)));
    }

    #[test]
    fn from_tag_maps_every_variant() {
        assert_eq!(
            VisualizerCallError::from_tag("NetworkError", None),
            VisualizerCallError::Network
        );
        assert_eq!(
            VisualizerCallError::from_tag("HttpStatusError", Some(503)),
            VisualizerCallError::HttpStatus(503)
        );
        // HttpStatusError with no status defaults to 0 (transport-blocked).
        assert_eq!(
            VisualizerCallError::from_tag("HttpStatusError", None),
            VisualizerCallError::HttpStatus(0)
        );
        assert_eq!(
            VisualizerCallError::from_tag("VisualizerPremiumGatedError", None),
            VisualizerCallError::PremiumGated
        );
        assert_eq!(
            VisualizerCallError::from_tag("whatever", None),
            VisualizerCallError::ResponseDecode
        );
    }

    /// The exact reply visualizer `Api::ShotsController#upload` sends at the
    /// free-plan cap (`Shot#daily_limit`, 3e9ba33c).
    const QUOTA_BODY: &str = r#"{"error":"Could not save the provided file. You've reached your daily limit of 30 shots. Please consider upgrading to a premium account."}"#;

    #[test]
    fn quota_422_is_detected_with_its_limit() {
        assert_eq!(visualizer_quota_limit(422, QUOTA_BODY), Some(30));
        // The pre-3e9ba33c cap (50) parses too.
        let old = QUOTA_BODY.replace("30", "50");
        assert_eq!(visualizer_quota_limit(422, &old), Some(50));
        // A non-JSON body carrying the message still counts; no number → 30.
        assert_eq!(
            visualizer_quota_limit(422, "You've reached your Daily Limit."),
            Some(VISUALIZER_DEFAULT_DAILY_LIMIT)
        );
    }

    #[test]
    fn other_422s_and_statuses_are_not_quota() {
        let bad_file =
            r#"{"error":"Could not save the provided file. Profile file can't be blank"}"#;
        assert_eq!(visualizer_quota_limit(422, bad_file), None);
        assert_eq!(
            visualizer_quota_limit(422, r#"{"error":"Request must be JSON."}"#),
            None
        );
        assert_eq!(visualizer_quota_limit(422, ""), None);
        // Same text on another status is not the quota reply.
        assert_eq!(visualizer_quota_limit(400, QUOTA_BODY), None);
        assert_eq!(visualizer_quota_limit(429, QUOTA_BODY), None);
    }

    #[test]
    fn quota_422_is_terminal_not_retried() {
        // The quota must stop the loop, not be retried every few seconds.
        assert!(!is_recoverable("HttpStatusError", Some(422)));
    }

    #[test]
    fn backoff_schedules() {
        assert_eq!(retry_backoff_ms(None, 0), 1_000);
        assert_eq!(retry_backoff_ms(Some(503), 1), 2_000);
        assert_eq!(retry_backoff_ms(Some(503), 3), 8_000);
        assert_eq!(retry_backoff_ms(Some(503), 10), 60_000);
        assert_eq!(retry_backoff_ms(Some(429), 0), 60_000);
        assert_eq!(retry_backoff_ms(Some(429), 1), 120_000);
        assert_eq!(retry_backoff_ms(Some(429), 4), 600_000);
        assert_eq!(retry_backoff_ms(Some(429), 40), 600_000);
    }
}
