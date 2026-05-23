//! Error type for the application core.

use de1_domain::DomainError;

/// An error in the `de1-app` facade.
///
/// Marked `#[non_exhaustive]`: more variants may be added as the facade grows,
/// so downstream `match`es should include a wildcard arm.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[non_exhaustive]
pub enum AppError {
    /// Serializing a [`CoreOutput`](crate::CoreOutput) to JSON failed.
    #[error("core output serialization failed: {0}")]
    Serialization(String),
    /// Assembling a profile into protocol packets failed — `NoSteps` or
    /// `TooManySteps`. Returned from
    /// [`CremaCore::upload_profile`](crate::CremaCore::upload_profile);
    /// the bridge surfaces it as an
    /// [`Event::ProfileUploadFailed`](crate::Event::ProfileUploadFailed)
    /// with `kind == Empty` or `TooManySteps`.
    #[error("profile upload validation failed: {0}")]
    ProfileUpload(#[from] DomainError),
    /// A setter rejected its argument before issuing any wire write — the
    /// caller passed a value outside the allowed range or enum.
    ///
    /// Used by safety-critical bridge clamps where the shell **must** never
    /// have been able to reach the setter with a bad value (e.g. mains
    /// heater voltage, which is hardware-damaging when mis-set); the
    /// `AppError` is the last-line guard. The shell catches the error and
    /// shows a non-fatal toast or refuses to commit the UI change.
    ///
    /// - `field`: the parameter name (e.g. `"voltage"`, `"hz"`).
    /// - `value`: the rejected value, formatted for the shell to display.
    /// - `reason`: why it was rejected, in human terms.
    #[error("invalid argument for {field}: {value} ({reason})")]
    InvalidArg {
        /// The parameter name that was rejected.
        field: String,
        /// The rejected value, formatted for the shell.
        value: String,
        /// Human-readable explanation.
        reason: String,
    },
    /// A capability-gated method was called on hardware that does not
    /// support it — for example, the Decent Scale power-off byte on v1.0
    /// or v1.1 firmware (silently ignored by anything before v1.2), or
    /// when no scale is connected.
    ///
    /// The shell catches this and surfaces the [`reason`](Self) — typically
    /// "this scale doesn't support remote power-off, please long-press the
    /// button" — so the user gets actionable feedback instead of a silent
    /// no-op write.
    ///
    /// - `feature`: a short identifier for the unsupported feature
    ///   (e.g. `"decent_scale_power_off"`), suitable for log filtering.
    /// - `reason`: a human-readable, user-facing explanation.
    #[error("feature {feature} is unsupported on the connected hardware: {reason}")]
    UnsupportedOnHardware {
        /// Short identifier for the unsupported feature.
        feature: String,
        /// Human-readable explanation, suitable for surfacing to the user.
        reason: String,
    },
}
