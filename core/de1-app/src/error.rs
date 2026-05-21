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
}
