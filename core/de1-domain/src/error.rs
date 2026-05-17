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

/// An error importing a legacy DE1-app profile into a
/// [`Profile`](crate::Profile).
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[non_exhaustive]
pub enum ImportError {
    /// The legacy v2 JSON profile was not valid JSON or did not match the
    /// expected schema.
    #[error("invalid v2 JSON profile: {0}")]
    Json(String),
    /// The legacy Tcl-dictionary profile was not well-formed Tcl.
    #[error("malformed legacy Tcl profile: {0}")]
    Tcl(String),
    /// A required profile field was missing.
    #[error("legacy profile is missing the required field `{0}`")]
    MissingField(&'static str),
    /// A profile field held a value the importer does not recognise.
    #[error("legacy profile field `{0}` has unrecognised value `{1}`")]
    UnknownValue(&'static str, String),
    /// The imported profile had no steps; at least one is required.
    #[error("imported profile has no steps")]
    NoSteps,
    /// The imported profile had more steps than the DE1 can hold (32).
    #[error("imported profile has {count} steps; the DE1 supports at most 32")]
    TooManySteps {
        /// The number of steps the rejected profile had.
        count: usize,
    },
}
