//! # sync-failure
//!
//! One classification for every failed cloud call: what a reply from
//! Visualizer, Decent or Google Drive means, so the shells stop keeping a
//! `catch` tree per service. `visualizer_queue_decision` and
//! `decent_upload_reply` are expressed through it, and a [`SyncFailure`] is what
//! a failed run reports ([`crate::RunOutcome`]).
//!
//! The wording stays shell-side: it is display copy, not policy.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use typeshare::typeshare;

use crate::visualizer_error::{
    RATE_LIMITED, RefreshFailure, classify_token_refresh_failure, visualizer_quota_limit,
};

/// The longest wait a `Retry-After` can ask for. The header is a proxy's or a
/// future server's, and a wrong value must not stop sync for a day.
pub const RETRY_AFTER_CAP_MS: u64 = 60 * 60 * 1000;

/// The most a 429 wait grows by, as a percentage. Jitter only decorrelates a
/// user's own devices, so it is added to the wait, never taken from it: the
/// window is fixed, and a shorter wait would only land in it again.
const RATE_LIMIT_JITTER_PERCENT: u64 = 20;

/// The most any other retry wait grows by, as a percentage.
const RETRY_JITTER_PERCENT: u64 = 25;

/// Which cloud service answered.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SyncService {
    /// visualizer.coffee.
    Visualizer,
    /// decentespresso.com.
    Decent,
    /// Google Drive, on Android: web Drive has no refresh token.
    Drive,
}

/// What was being attempted when the failure came back.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SyncOperation {
    /// An API call with an access token.
    Call,
    /// A refresh-token grant at the token endpoint.
    Refresh,
}

/// Why a 403 is about the whole account rather than one item. The tokens stay:
/// each of these is fixed on the website or by signing in again by choice.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AccountBlockReason {
    /// The account was disabled.
    Disabled,
    /// The account's email address isn't confirmed.
    Unconfirmed,
    /// The token lacks the scope this call needs.
    MissingScope,
}

/// What a failed cloud call means.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum SyncFailure {
    /// No reply at all.
    Offline,
    /// Slow down: a 429, or Drive's 403 rate reasons.
    RateLimited {
        /// The server's `Retry-After`, ms, when it sent a usable one.
        #[typeshare(serialized_as = "Option<I64>")]
        retry_after_ms: Option<u64>,
    },
    /// The server is in trouble, or something that isn't the server answered: a
    /// 5xx, a 408, or a success or redirect that isn't the API's (a captive
    /// portal's 200 HTML). Time fixes it.
    Unavailable {
        /// The HTTP status.
        status: u16,
    },
    /// A 401 on a call: the access token was refused, so refresh once.
    AccessRefused,
    /// The session is over and the user must sign in again.
    SessionEnded,
    /// The free plan's daily cap on new shots.
    Quota {
        /// The cap.
        limit: u32,
    },
    /// The account needs Premium for this.
    NeedsPremium,
    /// Decent: the shot's machine isn't registered to the linked account.
    NotOnAccount,
    /// This one item is refused, and the account is fine: a row on another
    /// account, say. Never reads as "buy Premium".
    Forbidden,
    /// The whole account is refused. See [`AccountBlockReason`].
    AccountBlocked {
        /// Why.
        reason: AccountBlockReason,
    },
    /// Drive has no room left. Only the user can fix it.
    StorageFull,
    /// The target no longer exists.
    NotFound,
    /// The server refused this request for good.
    Rejected {
        /// The HTTP status, or 0 for a 2xx reply that couldn't be mapped.
        status: u16,
    },
}

impl SyncFailure {
    /// Whether time alone can fix this: a later attempt may succeed with no
    /// user action.
    #[must_use]
    pub fn is_transient(&self) -> bool {
        matches!(
            self,
            Self::Offline | Self::RateLimited { .. } | Self::Unavailable { .. }
        )
    }
}

/// Classify a failed call to `service`.
///
/// `status` is `None` (or 0, a transport-blocked reply) when there was no
/// answer, `body` is the reply text, `retry_after` the raw `Retry-After` header.
/// A [`SyncOperation::Refresh`] is [`SyncFailure::SessionEnded`] only on
/// [`classify_token_refresh_failure`]'s definitive answer: anything else keeps
/// the session, so nothing else here may read as signed out.
#[must_use]
pub fn classify_sync_failure(
    service: SyncService,
    operation: SyncOperation,
    status: Option<u16>,
    body: &str,
    retry_after: Option<&str>,
    now_ms: i64,
) -> SyncFailure {
    let Some(status) = status.filter(|s| *s != 0) else {
        return SyncFailure::Offline;
    };
    let rate_limited = || SyncFailure::RateLimited {
        retry_after_ms: retry_after.and_then(|value| parse_retry_after(value, now_ms)),
    };
    if status == RATE_LIMITED {
        return rate_limited();
    }
    if status == 408 || !(400..500).contains(&status) {
        return SyncFailure::Unavailable { status };
    }
    if operation == SyncOperation::Refresh {
        return if classify_token_refresh_failure(Some(status), Some(body))
            == RefreshFailure::Definitive
        {
            SyncFailure::SessionEnded
        } else {
            SyncFailure::Rejected { status }
        };
    }
    match service {
        SyncService::Visualizer => classify_visualizer_4xx(status, body),
        SyncService::Decent => match status {
            401 => SyncFailure::SessionEnded,
            403 => SyncFailure::NotOnAccount,
            _ => SyncFailure::Rejected { status },
        },
        SyncService::Drive => match status {
            401 => SyncFailure::AccessRefused,
            403 if has_drive_reason(body, "storageQuotaExceeded") => SyncFailure::StorageFull,
            403 if has_drive_reason(body, "rateLimitExceeded")
                || has_drive_reason(body, "userRateLimitExceeded") =>
            {
                rate_limited()
            }
            _ => SyncFailure::Rejected { status },
        },
    }
}

/// Classify a Visualizer 4xx other than 408 and 429.
fn classify_visualizer_4xx(status: u16, body: &str) -> SyncFailure {
    match status {
        401 => SyncFailure::AccessRefused,
        402 => SyncFailure::NeedsPremium,
        403 => classify_visualizer_403(body),
        404 => SyncFailure::NotFound,
        _ => visualizer_quota_limit(status, body)
            .map_or(SyncFailure::Rejected { status }, |limit| {
                SyncFailure::Quota { limit }
            }),
    }
}

/// Tell a Visualizer 403 apart by its body. The texts are the server's
/// (`Authorization#check_premium!`, `Api::BaseController`). Doorkeeper answers
/// a missing scope with a bare `head :forbidden`, so the empty body is that one.
/// A page that isn't the API's JSON says nothing about the account.
fn classify_visualizer_403(body: &str) -> SyncFailure {
    if body.trim().is_empty() {
        return SyncFailure::AccountBlocked {
            reason: AccountBlockReason::MissingScope,
        };
    }
    let Some(message) = serde_json::from_str::<Value>(body).ok().and_then(|reply| {
        reply
            .get("error")
            .and_then(Value::as_str)
            .map(str::to_ascii_lowercase)
    }) else {
        return SyncFailure::Forbidden;
    };
    if message.contains("premium") {
        SyncFailure::NeedsPremium
    } else if message.contains("account has been disabled") {
        SyncFailure::AccountBlocked {
            reason: AccountBlockReason::Disabled,
        }
    } else if message.contains("confirm your email") {
        SyncFailure::AccountBlocked {
            reason: AccountBlockReason::Unconfirmed,
        }
    } else if message.contains("insufficient_scope") {
        SyncFailure::AccountBlocked {
            reason: AccountBlockReason::MissingScope,
        }
    } else {
        SyncFailure::Forbidden
    }
}

/// Whether a Drive error body lists `reason` (`error.errors[].reason`).
fn has_drive_reason(body: &str, reason: &str) -> bool {
    serde_json::from_str::<Value>(body)
        .ok()
        .as_ref()
        .and_then(|reply| reply.pointer("/error/errors"))
        .and_then(Value::as_array)
        .is_some_and(|errors| {
            errors
                .iter()
                .any(|e| e.get("reason").and_then(Value::as_str) == Some(reason))
        })
}

/// [`classify_sync_failure`] for the bridges: `service` by name (`"visualizer"`,
/// `"decent"`, `"drive"`; anything else reads as Visualizer), `operation` by
/// name (`"refresh"`; anything else is a call) and the failure as JSON.
#[must_use]
pub fn classify_sync_failure_json(
    service: &str,
    operation: &str,
    status: Option<u16>,
    body: &str,
    retry_after: Option<&str>,
    now_ms: i64,
) -> String {
    let service = match service {
        "decent" => SyncService::Decent,
        "drive" => SyncService::Drive,
        _ => SyncService::Visualizer,
    };
    let operation = if operation == "refresh" {
        SyncOperation::Refresh
    } else {
        SyncOperation::Call
    };
    let failure = classify_sync_failure(service, operation, status, body, retry_after, now_ms);
    serde_json::to_string(&failure).unwrap_or_else(|_| r#"{"kind":"Offline"}"#.to_owned())
}

/// `delay_ms` plus up to `max_percent` of itself. `unit` is the shell's random
/// number in `0..1`: the core has no randomness of its own, so a test can pin
/// it. Anything outside that range is clamped, and a non-number adds nothing.
fn with_added_jitter(delay_ms: u64, max_percent: u64, unit: f64) -> u64 {
    let unit = if unit.is_finite() {
        unit.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let span = delay_ms.saturating_mul(max_percent) / 100;
    // Sound: `span` is far below 2^53 for any real delay, the product is a
    // non-negative value no larger than it, and truncating drops under 1 ms.
    #[allow(
        clippy::cast_precision_loss,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    let added = (span as f64 * unit) as u64;
    delay_ms.saturating_add(added)
}

/// A wait after a 429: `delay_ms` with its 0 to 20% added jitter.
pub(crate) fn rate_limit_jitter(delay_ms: u64, unit: f64) -> u64 {
    with_added_jitter(delay_ms, RATE_LIMIT_JITTER_PERCENT, unit)
}

/// Any other retry wait: `delay_ms` with its 0 to 25% added jitter.
pub(crate) fn retry_jitter(delay_ms: u64, unit: f64) -> u64 {
    with_added_jitter(delay_ms, RETRY_JITTER_PERCENT, unit)
}

/// How long to wait before wait number `attempt` (0-based) after a rate limit:
/// [`crate::retry_backoff_ms`]'s schedule, or the server's `Retry-After` when
/// that is longer, capped at [`RETRY_AFTER_CAP_MS`].
#[must_use]
pub fn rate_limit_wait_ms(retry_after_ms: Option<u64>, attempt: u32, jitter_unit: f64) -> u64 {
    let scheduled = crate::retry_backoff_ms(Some(RATE_LIMITED), attempt, jitter_unit);
    scheduled.max(retry_after_ms.unwrap_or(0).min(RETRY_AFTER_CAP_MS))
}

/// Read a `Retry-After` header into a wait in ms from `now_ms`. Handles both
/// RFC 9110 §10.2.3 forms: delay-seconds, and an HTTP date in any of the three
/// formats §5.6.7 tells a recipient to accept. A date already past is 0. `None`
/// when the value is neither.
#[must_use]
pub fn parse_retry_after(value: &str, now_ms: i64) -> Option<u64> {
    let value = value.trim();
    if !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()) {
        // An absurdly long number still means "a long time"; the caller caps it.
        return Some(
            value
                .parse::<u64>()
                .map_or(u64::MAX, |s| s.saturating_mul(1000)),
        );
    }
    let at_ms = http_date_ms(value, now_ms)?;
    Some(u64::try_from(at_ms.saturating_sub(now_ms)).unwrap_or(0))
}

/// Unix ms of an HTTP date: IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`), the
/// obsolete RFC 850 form (`Sunday, 06-Nov-94 08:49:37 GMT`) or asctime
/// (`Sun Nov  6 08:49:37 1994`). The weekday is noise here, so it isn't checked.
fn http_date_ms(value: &str, now_ms: i64) -> Option<i64> {
    let parts: Vec<&str> = value.split_ascii_whitespace().collect();
    let (year, month, day, time) = match parts.as_slice() {
        [_, day, month, year, time, "GMT"] => {
            (year.parse().ok()?, *month, day.parse().ok()?, *time)
        }
        [_, date, time, "GMT"] => {
            let mut date = date.split('-');
            let (day, month, year) = (date.next()?, date.next()?, date.next()?);
            let year = two_digit_year(year.parse().ok()?, now_ms);
            (year, month, day.parse().ok()?, *time)
        }
        [_, month, day, time, year] => (year.parse().ok()?, *month, day.parse().ok()?, *time),
        _ => return None,
    };
    let month = month_number(month)?;
    let mut time = time.split(':');
    let hour: i64 = time.next()?.parse().ok()?;
    let minute: i64 = time.next()?.parse().ok()?;
    let second: i64 = time.next()?.parse().ok()?;
    if !(1..=31).contains(&day) || hour > 23 || minute > 59 || second > 60 {
        return None;
    }
    let seconds = days_from_civil(year, month, day) * 86_400 + hour * 3600 + minute * 60 + second;
    seconds.checked_mul(1000)
}

/// The full year of a two-digit one (RFC 9110 §5.6.7): one that reads more than
/// 50 years ahead means the most recent past year with those digits.
fn two_digit_year(digits: i64, now_ms: i64) -> i64 {
    /// A Gregorian year in ms, near enough to name the current year.
    const YEAR_MS: i64 = 31_556_952_000;
    let this_year = 1970 + now_ms / YEAR_MS;
    let year = this_year - this_year % 100 + digits;
    if year > this_year + 50 {
        year - 100
    } else {
        year
    }
}

/// A month's number from its three-letter HTTP-date name.
fn month_number(name: &str) -> Option<i64> {
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let index = MONTHS.iter().position(|m| *m == name)?;
    i64::try_from(index + 1).ok()
}

/// Days from 1970-01-01 to a Gregorian date (Howard Hinnant's
/// `days_from_civil`). The core has no date library, and this is its one date.
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let year_of_era = year.rem_euclid(400);
    let day_of_year = (153 * ((month + 9) % 12) + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;

    fn call(service: SyncService, status: Option<u16>, body: &str) -> SyncFailure {
        classify_sync_failure(service, SyncOperation::Call, status, body, None, NOW)
    }

    fn refresh(service: SyncService, status: Option<u16>, body: &str) -> SyncFailure {
        classify_sync_failure(service, SyncOperation::Refresh, status, body, None, NOW)
    }

    const ALL: [SyncService; 3] = [
        SyncService::Visualizer,
        SyncService::Decent,
        SyncService::Drive,
    ];
    const INVALID_GRANT: &str = r#"{"error":"invalid_grant"}"#;

    fn drive_error(reason: &str) -> String {
        format!(
            r#"{{"error":{{"code":403,"message":"x","errors":[{{"domain":"usageLimits","reason":"{reason}","message":"x"}}]}}}}"#
        )
    }

    #[test]
    fn no_reply_is_offline_everywhere() {
        for service in ALL {
            assert_eq!(call(service, None, ""), SyncFailure::Offline);
            assert_eq!(
                call(service, Some(0), ""),
                SyncFailure::Offline,
                "transport-blocked"
            );
            assert_eq!(refresh(service, None, ""), SyncFailure::Offline);
        }
    }

    #[test]
    fn a_429_is_rate_limited_everywhere_and_carries_its_retry_after() {
        for service in ALL {
            assert_eq!(
                call(service, Some(429), ""),
                SyncFailure::RateLimited {
                    retry_after_ms: None
                }
            );
            assert_eq!(
                classify_sync_failure(service, SyncOperation::Call, Some(429), "", Some("90"), NOW),
                SyncFailure::RateLimited {
                    retry_after_ms: Some(90_000)
                }
            );
        }
    }

    #[test]
    fn server_trouble_is_unavailable_everywhere() {
        for service in ALL {
            for status in [500, 503, 599, 408] {
                assert_eq!(
                    call(service, Some(status), ""),
                    SyncFailure::Unavailable { status },
                    "{service:?} {status}"
                );
            }
        }
    }

    /// A captive portal answers 200 HTML: not the API, and not a refusal.
    #[test]
    fn a_success_or_redirect_that_reached_here_is_transient() {
        for service in ALL {
            for status in [200, 302] {
                let failure = call(service, Some(status), "<html>portal</html>");
                assert_eq!(failure, SyncFailure::Unavailable { status });
                assert!(failure.is_transient());
            }
        }
    }

    #[test]
    fn a_401_on_a_call_refreshes_except_on_decent() {
        assert_eq!(
            call(SyncService::Visualizer, Some(401), ""),
            SyncFailure::AccessRefused
        );
        assert_eq!(
            call(SyncService::Drive, Some(401), ""),
            SyncFailure::AccessRefused
        );
        assert_eq!(
            call(SyncService::Decent, Some(401), ""),
            SyncFailure::SessionEnded
        );
    }

    #[test]
    fn only_invalid_grant_ends_a_session_at_the_token_endpoint() {
        for service in [SyncService::Visualizer, SyncService::Drive] {
            for status in [400, 401] {
                assert_eq!(
                    refresh(service, Some(status), INVALID_GRANT),
                    SyncFailure::SessionEnded
                );
            }
            for (status, body) in [
                (401, r#"{"error":"invalid_client"}"#),
                (400, "<html>captive portal</html>"),
                (403, "<html>proxy</html>"),
                (403, INVALID_GRANT),
                (503, INVALID_GRANT),
                (200, "<html>captive portal</html>"),
            ] {
                let failure = refresh(service, Some(status), body);
                assert_ne!(failure, SyncFailure::SessionEnded, "{status} {body}");
                assert_ne!(failure, SyncFailure::AccessRefused, "{status} {body}");
            }
            assert_eq!(
                refresh(service, Some(429), INVALID_GRANT),
                SyncFailure::RateLimited {
                    retry_after_ms: None
                }
            );
        }
    }

    /// The replies below are `Authorization#check_premium!` and
    /// `Api::BaseController`, word for word.
    #[test]
    fn visualizer_403s_are_told_apart_by_the_body() {
        let forbidden = |body| call(SyncService::Visualizer, Some(403), body);
        assert_eq!(
            forbidden(r#"{"error":"You must be a premium user to access this feature."}"#),
            SyncFailure::NeedsPremium
        );
        assert_eq!(
            forbidden(r#"{"error":"You are not authorized to perform this action."}"#),
            SyncFailure::Forbidden,
            "a row on another account must not ask for Premium"
        );
        assert_eq!(
            forbidden(
                r#"{"error":"This account has been disabled. Contact miha@visualizer.coffee for more information."}"#
            ),
            SyncFailure::AccountBlocked {
                reason: AccountBlockReason::Disabled
            }
        );
        assert_eq!(
            forbidden(
                r#"{"error":"Confirm your email address first. Sign in at visualizer.coffee to resend the confirmation email."}"#
            ),
            SyncFailure::AccountBlocked {
                reason: AccountBlockReason::Unconfirmed
            }
        );
        let scope = SyncFailure::AccountBlocked {
            reason: AccountBlockReason::MissingScope,
        };
        assert_eq!(forbidden(""), scope, "Doorkeeper's bare head :forbidden");
        assert_eq!(forbidden(r#"{"error":"insufficient_scope"}"#), scope);
    }

    #[test]
    fn a_403_page_that_is_not_the_api_says_nothing_about_the_account() {
        for body in [
            "<html>premium disabled confirm your email</html>",
            r#"{"status":"nope"}"#,
        ] {
            assert_eq!(
                call(SyncService::Visualizer, Some(403), body),
                SyncFailure::Forbidden
            );
        }
    }

    #[test]
    fn a_402_needs_premium_on_visualizer_only() {
        assert_eq!(
            call(SyncService::Visualizer, Some(402), ""),
            SyncFailure::NeedsPremium
        );
        assert_eq!(
            call(SyncService::Decent, Some(402), ""),
            SyncFailure::Rejected { status: 402 }
        );
        assert_eq!(
            call(SyncService::Drive, Some(402), ""),
            SyncFailure::Rejected { status: 402 }
        );
    }

    #[test]
    fn a_403_on_decent_is_the_machine_not_the_account() {
        assert_eq!(
            call(SyncService::Decent, Some(403), ""),
            SyncFailure::NotOnAccount
        );
    }

    #[test]
    fn the_daily_limit_is_quota_on_visualizer_and_other_4xx_are_rejected() {
        let quota = r#"{"error":"You've reached your daily limit of 30 shots."}"#;
        assert_eq!(
            call(SyncService::Visualizer, Some(422), quota),
            SyncFailure::Quota { limit: 30 }
        );
        assert_eq!(
            call(SyncService::Visualizer, Some(422), "{}"),
            SyncFailure::Rejected { status: 422 }
        );
        assert_eq!(
            call(SyncService::Visualizer, Some(404), ""),
            SyncFailure::NotFound
        );
        for service in [SyncService::Decent, SyncService::Drive] {
            for status in [400, 404, 422] {
                assert_eq!(
                    call(service, Some(status), quota),
                    SyncFailure::Rejected { status }
                );
            }
        }
    }

    /// Drive's "Resolve errors": only these two 403 reasons say back off. The
    /// project's `dailyLimitExceeded` needs its owner, not time.
    #[test]
    fn drive_403_reasons() {
        let drive = |reason: &str| call(SyncService::Drive, Some(403), &drive_error(reason));
        for reason in ["rateLimitExceeded", "userRateLimitExceeded"] {
            assert_eq!(
                drive(reason),
                SyncFailure::RateLimited {
                    retry_after_ms: None
                }
            );
        }
        assert_eq!(drive("storageQuotaExceeded"), SyncFailure::StorageFull);
        for reason in [
            "dailyLimitExceeded",
            "insufficientPermissions",
            "domainPolicy",
        ] {
            assert_eq!(
                drive(reason),
                SyncFailure::Rejected { status: 403 },
                "{reason}"
            );
        }
        for body in ["", "<html>proxy</html>", r#"{"error":"nope"}"#] {
            assert_eq!(
                call(SyncService::Drive, Some(403), body),
                SyncFailure::Rejected { status: 403 }
            );
        }
    }

    #[test]
    fn a_drive_rate_reason_keeps_its_retry_after() {
        let body = drive_error("userRateLimitExceeded");
        assert_eq!(
            classify_sync_failure(
                SyncService::Drive,
                SyncOperation::Call,
                Some(403),
                &body,
                Some("30"),
                NOW
            ),
            SyncFailure::RateLimited {
                retry_after_ms: Some(30_000)
            }
        );
    }

    #[test]
    fn only_a_later_attempt_can_fix_what_is_transient() {
        for failure in [
            SyncFailure::Offline,
            SyncFailure::RateLimited {
                retry_after_ms: None,
            },
            SyncFailure::Unavailable { status: 503 },
        ] {
            assert!(failure.is_transient(), "{failure:?}");
        }
        for failure in [
            SyncFailure::AccessRefused,
            SyncFailure::SessionEnded,
            SyncFailure::Quota { limit: 30 },
            SyncFailure::NeedsPremium,
            SyncFailure::NotOnAccount,
            SyncFailure::Forbidden,
            SyncFailure::AccountBlocked {
                reason: AccountBlockReason::Disabled,
            },
            SyncFailure::StorageFull,
            SyncFailure::NotFound,
            SyncFailure::Rejected { status: 400 },
        ] {
            assert!(!failure.is_transient(), "{failure:?}");
        }
    }

    #[test]
    fn the_bridge_names_services_and_shapes_json() {
        assert_eq!(
            classify_sync_failure_json(
                "drive",
                "call",
                Some(403),
                &drive_error("storageQuotaExceeded"),
                None,
                NOW
            ),
            r#"{"kind":"StorageFull"}"#
        );
        assert_eq!(
            classify_sync_failure_json("decent", "call", Some(401), "", None, NOW),
            r#"{"kind":"SessionEnded"}"#
        );
        assert_eq!(
            classify_sync_failure_json(
                "visualizer",
                "refresh",
                Some(400),
                INVALID_GRANT,
                None,
                NOW
            ),
            r#"{"kind":"SessionEnded"}"#
        );
        assert_eq!(
            classify_sync_failure_json("visualizer", "call", Some(403), "", None, NOW),
            r#"{"kind":"AccountBlocked","details":{"reason":"MissingScope"}}"#
        );
        assert_eq!(
            classify_sync_failure_json("visualizer", "call", Some(429), "", Some("5"), NOW),
            r#"{"kind":"RateLimited","details":{"retry_after_ms":5000}}"#
        );
    }

    #[test]
    fn retry_after_reads_seconds() {
        assert_eq!(parse_retry_after("120", NOW), Some(120_000));
        assert_eq!(parse_retry_after(" 0 ", NOW), Some(0));
        assert_eq!(
            parse_retry_after("99999999999999999999999", NOW),
            Some(u64::MAX),
            "too long to parse still means a long time"
        );
    }

    /// 1994-11-06 08:49:37 UTC, RFC 9110's own example, read from the three
    /// formats a recipient must accept.
    #[test]
    fn retry_after_reads_each_http_date_form() {
        const EXAMPLE_MS: i64 = 784_111_777_000;
        let now = EXAMPLE_MS - 30_000;
        for value in [
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994",
        ] {
            assert_eq!(parse_retry_after(value, now), Some(30_000), "{value}");
        }
    }

    #[test]
    fn a_retry_after_date_in_the_past_means_now() {
        assert_eq!(
            parse_retry_after("Sun, 06 Nov 1994 08:49:37 GMT", NOW),
            Some(0)
        );
    }

    #[test]
    fn a_two_digit_year_stays_within_fifty_years() {
        // 2027-01-15; "26" is last year, and "99" is 1999 rather than 2099.
        let now = (days_from_civil(2027, 1, 15)) * 86_400_000;
        assert_eq!(two_digit_year(26, now), 2026);
        assert_eq!(two_digit_year(99, now), 1999);
    }

    #[test]
    fn retry_after_refuses_what_it_cannot_read() {
        for value in [
            "",
            "soon",
            "-5",
            "1.5",
            "Sun, 06 Nov 1994 08:49:37 PST",
            "Sun, 32 Nov 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 25:49:37 GMT",
            "Sun, 06 Foo 1994 08:49:37 GMT",
            "Sun, 06 Nov 1994 08:49 GMT",
        ] {
            assert_eq!(parse_retry_after(value, NOW), None, "{value:?}");
        }
    }

    #[test]
    fn jitter_only_adds_up_to_its_share() {
        assert_eq!(retry_jitter(1_000, 0.0), 1_000);
        assert_eq!(retry_jitter(1_000, 0.5), 1_125);
        assert_eq!(retry_jitter(1_000, 1.0), 1_250);
        assert_eq!(rate_limit_jitter(60_000, 0.0), 60_000);
        assert_eq!(rate_limit_jitter(60_000, 0.5), 66_000);
        assert_eq!(rate_limit_jitter(60_000, 1.0), 72_000);
    }

    #[test]
    fn a_bad_random_number_never_shortens_or_blows_up_a_wait() {
        assert_eq!(retry_jitter(1_000, -3.0), 1_000);
        assert_eq!(retry_jitter(1_000, 7.0), 1_250);
        assert_eq!(retry_jitter(1_000, f64::NAN), 1_000);
        assert_eq!(retry_jitter(1_000, f64::INFINITY), 1_000);
    }

    #[test]
    fn retry_after_wins_only_when_longer_and_stops_at_an_hour() {
        assert_eq!(rate_limit_wait_ms(None, 0, 0.0), 60_000);
        assert_eq!(rate_limit_wait_ms(None, 1, 0.0), 600_000);
        assert_eq!(
            rate_limit_wait_ms(Some(5_000), 0, 0.0),
            60_000,
            "shorter loses"
        );
        assert_eq!(rate_limit_wait_ms(Some(90_000), 0, 0.0), 90_000);
        assert_eq!(
            rate_limit_wait_ms(Some(u64::MAX), 0, 0.0),
            RETRY_AFTER_CAP_MS
        );
    }
}
