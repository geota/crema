//! Error type for the application core.

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
}
