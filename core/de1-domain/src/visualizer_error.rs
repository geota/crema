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
//! moves here, and it is the shared [`SyncFailure`] classification's.

use serde::{Deserialize, Serialize};
use thiserror::Error;
use typeshare::typeshare;

use crate::sync_failure::{
    SyncFailure, SyncOperation, SyncService, classify_sync_failure, rate_limit_jitter, retry_jitter,
};

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

    /// What this failure means in the shared classification. `body` is the
    /// reply text, which tells a quota 422 or a premium 403 from the rest.
    #[must_use]
    pub fn sync_failure(&self, body: &str) -> SyncFailure {
        match self {
            Self::Network => SyncFailure::Offline,
            Self::HttpStatus(status) => classify_sync_failure(
                SyncService::Visualizer,
                SyncOperation::Call,
                Some(*status),
                body,
                None,
                0,
            ),
            Self::NotAuthenticated | Self::TokenRefreshFailed => SyncFailure::SessionEnded,
            Self::PremiumGated => SyncFailure::NeedsPremium,
            Self::NotFound => SyncFailure::NotFound,
            Self::ResponseDecode => SyncFailure::Rejected { status: 0 },
        }
    }

    /// Whether the failure is worth a time-based retry through the upload
    /// queue: [`SyncFailure::is_transient`]. Auth / premium / not-found /
    /// decode failures need user action, not time, so they are terminal.
    /// Mirrors `isRecoverable`.
    ///
    /// `429` is Visualizer's rate limit (50 requests/min and 200 per 10 min
    /// per IP, 200 per 10 min per user — `openapi.yaml` "Rate limits"): it
    /// clears with time, so it is retried, with the longer
    /// [`retry_backoff_ms`] delay.
    #[must_use]
    pub fn is_recoverable(&self) -> bool {
        self.sync_failure("").is_transient()
    }
}

/// HTTP 429 Too Many Requests — Visualizer's rate limit.
pub(crate) const RATE_LIMITED: u16 = 429;

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
/// - `429`: Visualizer's windows are fixed, a rejected request counts and
///   doesn't extend them, and the 10-minute one is the one that binds. So one
///   60 s probe covers the minute limit, then every wait is 10 minutes, the
///   longest a window can take to pass. 0 to 20% added jitter.
/// - Everything else: 1 s × 2^attempt, capped at 60 s (the queue's existing
///   schedule). 0 to 25% added jitter.
///
/// `jitter_unit` is the shell's random number in `0..1`.
#[must_use]
pub fn retry_backoff_ms(status: Option<u16>, attempt: u32, jitter_unit: f64) -> u64 {
    if status == Some(RATE_LIMITED) {
        let wait = if attempt == 0 { 60_000 } else { 600_000 };
        rate_limit_jitter(wait, jitter_unit)
    } else {
        retry_jitter((1_000 << attempt.min(16)).min(60_000), jitter_unit)
    }
}

/// The wasm / FFI bridge entry point: reconstruct the error from the shell's
/// `(tag, status)` marshalling and apply the retry policy. See
/// [`VisualizerCallError::is_recoverable`].
#[must_use]
pub fn is_recoverable(tag: &str, status: Option<u16>) -> bool {
    VisualizerCallError::from_tag(tag, status).is_recoverable()
}

/// What a failed OAuth refresh means for the session (both shells; web
/// `classifyRefreshFailure`, review 03 P1-1).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum RefreshFailure {
    /// The token endpoint rejected the refresh token: the session is over.
    Definitive,
    /// A transient server answer (5xx, 408, 429): keep the session, retry on
    /// the status-aware backoff.
    Retryable,
    /// Anything else — a transport failure, a captive portal, a proxy page,
    /// `invalid_client`: nothing proves the refresh token is dead, so the
    /// session stays.
    Transient,
}

/// Classify a failed refresh-token grant from the token endpoint's `status`
/// (`None`: the request never got an answer) and its `body`.
///
/// **Definitive** only for HTTP 400 or 401 whose OAuth `error` is
/// `invalid_grant`: Visualizer runs Doorkeeper 5.9 with refresh-token
/// rotation, which answers a revoked, expired, unknown or already-rotated
/// refresh token with exactly that (RFC 6749 §5.2). **Retryable** for 5xx,
/// 408 and 429. Everything else is **Transient**: a shell signs out only on
/// Definitive.
#[must_use]
pub fn classify_token_refresh_failure(status: Option<u16>, body: Option<&str>) -> RefreshFailure {
    let Some(status) = status else {
        return RefreshFailure::Transient;
    };
    let oauth_error = body
        .and_then(|b| serde_json::from_str::<serde_json::Value>(b).ok())
        .and_then(|v| v.get("error").and_then(|e| e.as_str()).map(str::to_owned));
    if (status == 400 || status == 401) && oauth_error.as_deref() == Some("invalid_grant") {
        RefreshFailure::Definitive
    } else if status >= 500 || status == 408 || status == RATE_LIMITED {
        RefreshFailure::Retryable
    } else {
        RefreshFailure::Transient
    }
}

/// Whether a stored row goes out with no telemetry, so an upload must refuse it.
///
/// A machine shot with no samples is one whose series hasn't loaded, or was
/// lost: Visualizer would take it as `elapsed: 0` and Decent as a record with no
/// measurements, and the row would count as uploaded (geota/crema#330). `None`
/// reads as no samples. A brew log has none by nature, and stays local for its
/// own reason, so this says nothing about it.
#[must_use]
pub fn upload_refused_no_samples(is_brew_log: bool, sample_count: Option<u32>) -> bool {
    !is_brew_log && sample_count.unwrap_or(0) == 0
}

/// The most attempts a queued Visualizer operation gets before it is dropped.
pub const MAX_QUEUE_ATTEMPTS: u32 = 3;

/// How long a remote delete waits, parked, while the user is signed out (no
/// attempt spent: only signing in again fixes it, and nothing else re-offers a
/// delete whose local row is gone).
pub const AUTH_PARK_MS: u32 = 5 * 60 * 1000;

/// How long a remote delete waits, parked, once [`MAX_QUEUE_ATTEMPTS`] have
/// failed for a reason time can fix. Nothing else re-offers a delete whose local
/// row is gone, so it is never dropped for such a failure (geota/crema#331).
pub const DELETE_PARK_MS: u32 = 60 * 60 * 1000;

/// The kind of queued Visualizer operation.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum QueueOp {
    /// Upload a new shot.
    Create,
    /// Update a remote row.
    Update,
    /// Delete a remote row.
    Delete,
}

/// What a retry queue does with an operation that just failed.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum QueueDecision {
    /// Remove it. `stop_pass`: stop this drain (the daily quota — every other
    /// upload would hit it too).
    Drop {
        /// Stop the drain.
        stop_pass: bool,
    },
    /// Keep it without spending an attempt; look again after `delay_ms` (a
    /// delete while signed out, or out of attempts).
    Park {
        /// Wait, ms.
        delay_ms: u32,
    },
    /// Retry after `delay_ms`. `stop_pass`: leave the rest of the queue for a
    /// later drain (the rate limit).
    Retry {
        /// Wait, ms.
        delay_ms: u32,
        /// Stop the drain.
        stop_pass: bool,
    },
}

/// The retry queue's rule for an operation `op` that failed with the error
/// `(tag, status)` and reply `body`, `attempts_made` attempts in (this one
/// included). `jitter_unit` is the shell's random number in `0..1`. Both shells'
/// queues follow it (web `UploadQueue.doDrain`):
///
/// 1. the free-plan daily quota ([`SyncFailure::Quota`]): drop and stop;
/// 2. a delete while signed out (not authenticated, or the refresh token
///    definitively rejected): park for [`AUTH_PARK_MS`];
/// 3. not recoverable ([`SyncFailure::is_transient`]): drop. A delete's tombstone
///    stays, so the remote copy is never pulled back;
/// 4. [`MAX_QUEUE_ATTEMPTS`] made: drop, unless it's a delete, which nothing else
///    would re-offer. A delete parks for [`DELETE_PARK_MS`], or on a 429 keeps
///    its cooldown below, which also stops the drain;
/// 5. otherwise retry after [`retry_backoff_ms`]; a 429 also stops the drain.
///    A first 429 is the 60 s probe, so its attempts count from 0.
#[must_use]
pub fn visualizer_queue_decision(
    op: QueueOp,
    tag: &str,
    status: Option<u16>,
    body: &str,
    attempts_made: u32,
    jitter_unit: f64,
) -> QueueDecision {
    let failure = VisualizerCallError::from_tag(tag, status).sync_failure(body);
    let rate_limited = matches!(failure, SyncFailure::RateLimited { .. });
    if matches!(failure, SyncFailure::Quota { .. }) {
        return QueueDecision::Drop { stop_pass: true };
    }
    if op == QueueOp::Delete && failure == SyncFailure::SessionEnded {
        return QueueDecision::Park {
            delay_ms: AUTH_PARK_MS,
        };
    }
    if !failure.is_transient() {
        return QueueDecision::Drop { stop_pass: false };
    }
    if attempts_made >= MAX_QUEUE_ATTEMPTS {
        if op != QueueOp::Delete {
            return QueueDecision::Drop { stop_pass: false };
        }
        if !rate_limited {
            return QueueDecision::Park {
                delay_ms: DELETE_PARK_MS,
            };
        }
    }
    let attempt = if rate_limited {
        attempts_made.saturating_sub(1)
    } else {
        attempts_made
    };
    QueueDecision::Retry {
        // At most 12 min (`retry_backoff_ms`'s longest, with jitter): fits a `u32`.
        delay_ms: u32::try_from(retry_backoff_ms(status, attempt, jitter_unit)).unwrap_or(u32::MAX),
        stop_pass: rate_limited,
    }
}

/// [`visualizer_queue_decision`] for the bridges: `op` by name (`"create"`,
/// `"update"`, `"delete"`; anything else reads as an update) and the decision
/// as JSON.
#[must_use]
pub fn visualizer_queue_decision_json(
    op: &str,
    tag: &str,
    status: Option<u16>,
    body: &str,
    attempts_made: u32,
    jitter_unit: f64,
) -> String {
    let op = match op {
        "create" => QueueOp::Create,
        "delete" => QueueOp::Delete,
        _ => QueueOp::Update,
    };
    let decision = visualizer_queue_decision(op, tag, status, body, attempts_made, jitter_unit);
    serde_json::to_string(&decision)
        .unwrap_or_else(|_| r#"{"kind":"Drop","details":{"stop_pass":false}}"#.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_machine_shot_with_no_samples_is_refused() {
        assert!(upload_refused_no_samples(false, Some(0)));
        assert!(upload_refused_no_samples(false, None));
    }

    #[test]
    fn a_shot_with_samples_goes_and_a_brew_log_is_not_this_rules_business() {
        assert!(!upload_refused_no_samples(false, Some(1)));
        assert!(!upload_refused_no_samples(true, Some(0)));
        assert!(!upload_refused_no_samples(true, None));
    }

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
        for status in [404u16, 402, 401, 400, 403, 422, 499] {
            assert!(!is_recoverable("HttpStatusError", Some(status)), "{status}");
        }
    }

    /// Not the API's answer (a captive portal), so nothing proves it final.
    #[test]
    fn a_success_or_redirect_status_is_recoverable() {
        for status in [200u16, 301] {
            assert!(is_recoverable("HttpStatusError", Some(status)), "{status}");
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
        assert_eq!(retry_backoff_ms(None, 0, 0.0), 1_000);
        assert_eq!(retry_backoff_ms(Some(503), 1, 0.0), 2_000);
        assert_eq!(retry_backoff_ms(Some(503), 3, 0.0), 8_000);
        assert_eq!(retry_backoff_ms(Some(503), 10, 0.0), 60_000);
        assert_eq!(retry_backoff_ms(Some(429), 0, 0.0), 60_000);
        assert_eq!(retry_backoff_ms(Some(429), 1, 0.0), 600_000);
        assert_eq!(retry_backoff_ms(Some(429), 2, 0.0), 600_000);
        assert_eq!(retry_backoff_ms(Some(429), 40, 0.0), 600_000);
    }

    #[test]
    fn backoffs_carry_their_added_jitter() {
        assert_eq!(retry_backoff_ms(Some(503), 3, 1.0), 10_000, "25%");
        assert_eq!(retry_backoff_ms(None, 10, 1.0), 75_000, "25% over the cap");
        assert_eq!(retry_backoff_ms(Some(429), 0, 1.0), 72_000, "20%");
        assert_eq!(retry_backoff_ms(Some(429), 1, 1.0), 720_000, "20%");
    }

    /// AC-F17.1: only a 400 / 401 `invalid_grant` ends the session.
    #[test]
    fn f17_1_only_invalid_grant_on_400_or_401_is_definitive() {
        let grant = Some(r#"{"error":"invalid_grant","error_description":"revoked"}"#);
        assert_eq!(
            classify_token_refresh_failure(Some(400), grant),
            RefreshFailure::Definitive
        );
        assert_eq!(
            classify_token_refresh_failure(Some(401), grant),
            RefreshFailure::Definitive
        );
        let transient = [
            (Some(401), None),
            (Some(401), Some(r#"{"error":"invalid_client"}"#)),
            (Some(403), grant),
            (Some(403), Some("<html>proxy</html>")),
            (Some(400), Some("<html>captive portal</html>")),
            (Some(200), Some("<html>captive portal</html>")),
            (None, None),
        ];
        for (status, body) in transient {
            assert_eq!(
                classify_token_refresh_failure(status, body),
                RefreshFailure::Transient,
                "{status:?} {body:?}"
            );
        }
        for status in [500, 502, 503, 408, 429] {
            assert_eq!(
                classify_token_refresh_failure(Some(status), grant),
                RefreshFailure::Retryable,
                "{status}"
            );
        }
    }

    fn decide(op: QueueOp, tag: &str, status: Option<u16>, body: &str, made: u32) -> QueueDecision {
        visualizer_queue_decision(op, tag, status, body, made, 0.0)
    }

    /// AC-F17.2: one case per branch of the web drain.
    #[test]
    fn f17_2_the_queue_decision_matches_the_web_drain() {
        let quota = r#"{"error":"You've reached your daily limit of 30 shots."}"#;
        assert_eq!(
            decide(QueueOp::Create, "HttpStatusError", Some(422), quota, 1),
            QueueDecision::Drop { stop_pass: true }
        );
        for tag in ["NotAuthenticatedError", "TokenRefreshFailedError"] {
            assert_eq!(
                decide(QueueOp::Delete, tag, None, "", 1),
                QueueDecision::Park {
                    delay_ms: AUTH_PARK_MS
                }
            );
            assert_eq!(
                decide(QueueOp::Create, tag, None, "", 1),
                QueueDecision::Drop { stop_pass: false },
                "a signed-out create is re-offered by the next pass instead"
            );
        }
        assert_eq!(
            decide(QueueOp::Create, "HttpStatusError", Some(422), "{}", 1),
            QueueDecision::Drop { stop_pass: false },
            "not recoverable"
        );
        assert_eq!(
            decide(QueueOp::Create, "NetworkError", None, "", 1),
            QueueDecision::Retry {
                delay_ms: 2_000,
                stop_pass: false
            }
        );
        assert_eq!(
            decide(QueueOp::Create, "HttpStatusError", Some(503), "", 2),
            QueueDecision::Retry {
                delay_ms: 4_000,
                stop_pass: false
            }
        );
        assert_eq!(
            decide(
                QueueOp::Create,
                "NetworkError",
                None,
                "",
                MAX_QUEUE_ATTEMPTS
            ),
            QueueDecision::Drop { stop_pass: false },
            "the third attempt is the last"
        );
        assert_eq!(
            decide(QueueOp::Create, "HttpStatusError", Some(429), "", 1),
            QueueDecision::Retry {
                delay_ms: 60_000,
                stop_pass: true
            },
            "the first 429 is the probe"
        );
        assert_eq!(
            decide(QueueOp::Create, "HttpStatusError", Some(429), "", 2),
            QueueDecision::Retry {
                delay_ms: 600_000,
                stop_pass: true
            },
            "then the whole window"
        );
        assert_eq!(
            visualizer_queue_decision_json("delete", "NotAuthenticatedError", None, "", 1, 0.0),
            r#"{"kind":"Park","details":{"delay_ms":300000}}"#
        );
    }

    #[test]
    fn a_queue_retry_carries_the_shells_jitter() {
        assert_eq!(
            visualizer_queue_decision(QueueOp::Create, "NetworkError", None, "", 1, 1.0),
            QueueDecision::Retry {
                delay_ms: 2_500,
                stop_pass: false
            }
        );
    }

    /// geota/crema#331: nothing else re-offers a delete whose local row is gone.
    #[test]
    fn delete_after_3_failures_retries() {
        for (tag, status) in [
            ("NetworkError", None),
            ("HttpStatusError", Some(503)),
            ("HttpStatusError", Some(408)),
        ] {
            for made in [MAX_QUEUE_ATTEMPTS, MAX_QUEUE_ATTEMPTS + 40] {
                assert_eq!(
                    decide(QueueOp::Delete, tag, status, "", made),
                    QueueDecision::Park {
                        delay_ms: DELETE_PARK_MS
                    },
                    "{tag} {status:?}"
                );
            }
            assert!(matches!(
                decide(QueueOp::Delete, tag, status, "", MAX_QUEUE_ATTEMPTS - 1),
                QueueDecision::Retry { .. }
            ));
        }
    }

    #[test]
    fn a_rate_limited_delete_out_of_attempts_still_stops_the_drain() {
        assert_eq!(
            decide(
                QueueOp::Delete,
                "HttpStatusError",
                Some(429),
                "",
                MAX_QUEUE_ATTEMPTS
            ),
            QueueDecision::Retry {
                delay_ms: 600_000,
                stop_pass: true
            }
        );
    }

    #[test]
    fn a_terminal_delete_failure_stops_at_once() {
        for status in [400, 403, 422] {
            assert_eq!(
                decide(QueueOp::Delete, "HttpStatusError", Some(status), "", 1),
                QueueDecision::Drop { stop_pass: false },
                "{status}"
            );
        }
        assert_eq!(
            decide(QueueOp::Delete, "VisualizerPremiumGatedError", None, "", 1),
            QueueDecision::Drop { stop_pass: false }
        );
    }

    #[test]
    fn only_a_delete_outlives_its_attempts() {
        for op in [QueueOp::Create, QueueOp::Update] {
            assert_eq!(
                decide(op, "HttpStatusError", Some(503), "", MAX_QUEUE_ATTEMPTS),
                QueueDecision::Drop { stop_pass: false }
            );
            assert_eq!(
                decide(op, "HttpStatusError", Some(429), "", MAX_QUEUE_ATTEMPTS),
                QueueDecision::Drop { stop_pass: false }
            );
        }
    }

    #[test]
    fn call_errors_read_as_sync_failures() {
        let from = |tag, status| VisualizerCallError::from_tag(tag, status).sync_failure("");
        assert_eq!(from("NetworkError", None), SyncFailure::Offline);
        assert_eq!(from("HttpStatusError", None), SyncFailure::Offline);
        assert_eq!(
            from("NotAuthenticatedError", None),
            SyncFailure::SessionEnded
        );
        assert_eq!(
            from("TokenRefreshFailedError", None),
            SyncFailure::SessionEnded
        );
        assert_eq!(
            from("VisualizerPremiumGatedError", None),
            SyncFailure::NeedsPremium
        );
        assert_eq!(from("VisualizerNotFoundError", None), SyncFailure::NotFound);
        assert_eq!(
            from("ResponseDecodeError", None),
            SyncFailure::Rejected { status: 0 }
        );
    }
}
