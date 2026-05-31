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
    /// `5xx` / `408` / transport-blocked (`status 0`) HTTP response. Auth /
    /// premium / not-found / decode failures need user action, not time, so
    /// they are terminal. Mirrors `isRecoverable`.
    #[must_use]
    pub fn is_recoverable(&self) -> bool {
        match self {
            Self::Network => true,
            Self::HttpStatus(status) => {
                *status == 0 || *status == 408 || (*status >= 500 && *status < 600)
            }
            _ => false,
        }
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
    fn terminal_http_is_not_recoverable() {
        for status in [404u16, 402, 401, 400, 200, 301, 499] {
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
}
