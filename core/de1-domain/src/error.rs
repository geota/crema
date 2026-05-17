//! Error type for the domain layer.

/// An error in domain-level processing.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[non_exhaustive]
pub enum DomainError {
    /// A profile had no steps; at least one is required.
    #[error("profile has no steps")]
    NoSteps,
    /// A profile had more steps than the DE1 can hold (32).
    #[error("profile has {count} steps; the DE1 supports at most 32")]
    TooManySteps {
        /// The number of steps the rejected profile had.
        count: usize,
    },
    /// Serializing or deserializing a [`StoredShot`](crate::StoredShot) failed.
    #[error("shot serialization failed: {0}")]
    Serialization(String),
}
