//! # oauth-callback
//!
//! What an OAuth redirect means for a sign-in in progress. Both Android
//! services (Visualizer, Google Drive) answer a redirect by this one rule, so
//! neither decides on its own when a stored handshake may be touched.
//!
//! Any app or page can fire the custom-scheme redirect, and a redirect can land
//! on a cold start or replay when the activity is recreated. So a redirect that
//! doesn't carry the pending handshake's `state` is [`CallbackOutcome::NotOurs`]:
//! the shell writes nothing and says nothing. That covers **no pending
//! handshake**, which used to be treated as an expired sign-in and wrote the
//! store (geota/crema#322).

/// What a shell does with an OAuth redirect.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CallbackOutcome {
    /// No pending handshake, or a `state` that doesn't match it: write nothing
    /// and keep any handshake in progress.
    NotOurs,
    /// The user declined (`access_denied`).
    Cancelled,
    /// The provider refused the request. Any `error` other than
    /// `access_denied` is a real fault, not the user's choice, so its text is
    /// kept for the notice (geota/crema#45).
    Refused {
        /// The redirect's `error` value.
        error: String,
    },
    /// The redirect carried neither a code nor an error.
    NoCode,
    /// Exchange the code, with the pending verifier.
    Exchange,
}

/// The OAuth `error` that means the user said no (RFC 6749 §4.1.2.1).
const ACCESS_DENIED: &str = "access_denied";

/// Decide what a redirect means, from the stored handshake (`expected_state`,
/// `verifier`) and the redirect's own `code`, `returned_state` and `error`.
#[must_use]
pub fn callback_outcome(
    expected_state: Option<&str>,
    verifier: Option<&str>,
    code: Option<&str>,
    returned_state: Option<&str>,
    error: Option<&str>,
) -> CallbackOutcome {
    let (Some(expected), Some(_)) = (expected_state, verifier) else {
        return CallbackOutcome::NotOurs;
    };
    if returned_state != Some(expected) {
        return CallbackOutcome::NotOurs;
    }
    match (error, code) {
        (Some(ACCESS_DENIED), _) => CallbackOutcome::Cancelled,
        (Some(error), _) => CallbackOutcome::Refused {
            error: error.to_owned(),
        },
        (None, None) => CallbackOutcome::NoCode,
        (None, Some(_)) => CallbackOutcome::Exchange,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const S: Option<&str> = Some("s1");
    const V: Option<&str> = Some("v");

    #[test]
    fn no_pending_handshake_is_not_ours() {
        for (state, verifier) in [(None, None), (S, None), (None, V)] {
            assert_eq!(
                callback_outcome(state, verifier, Some("code"), S, None),
                CallbackOutcome::NotOurs,
            );
            assert_eq!(
                callback_outcome(state, verifier, None, S, Some("access_denied")),
                CallbackOutcome::NotOurs,
            );
        }
    }

    #[test]
    fn a_state_that_does_not_match_is_not_ours_whatever_else_it_carries() {
        for returned in [Some("evil"), None] {
            for (code, error) in [
                (Some("code"), None),
                (None, Some("access_denied")),
                (None, Some("invalid_request")),
                (None, None),
            ] {
                assert_eq!(
                    callback_outcome(S, V, code, returned, error),
                    CallbackOutcome::NotOurs,
                );
            }
        }
    }

    #[test]
    fn the_matching_redirect_is_read_for_what_it_says() {
        assert_eq!(
            callback_outcome(S, V, Some("code"), S, None),
            CallbackOutcome::Exchange,
        );
        assert_eq!(
            callback_outcome(S, V, None, S, None),
            CallbackOutcome::NoCode
        );
        assert_eq!(
            callback_outcome(S, V, None, S, Some("access_denied")),
            CallbackOutcome::Cancelled,
        );
    }

    #[test]
    fn a_refusal_keeps_the_providers_error_and_beats_a_code() {
        assert_eq!(
            callback_outcome(S, V, Some("code"), S, Some("invalid_request")),
            CallbackOutcome::Refused {
                error: "invalid_request".to_owned()
            },
        );
    }
}
