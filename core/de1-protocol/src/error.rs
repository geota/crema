//! Error type for protocol decoding.

/// An error decoding a DE1 protocol packet.
///
/// Marked `#[non_exhaustive]`: more variants may be added as later packet types
/// are implemented, so downstream `match`es should include a wildcard arm.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[non_exhaustive]
pub enum ProtocolError {
    /// A packet was shorter than its fixed layout requires.
    #[error("{packet} packet too short: need {expected} bytes, got {got}")]
    PacketTooShort {
        /// Name of the packet being parsed.
        packet: &'static str,
        /// Minimum length the layout requires.
        expected: usize,
        /// Length actually received.
        got: usize,
    },
    /// A state byte did not match any known [`MachineState`](crate::MachineState).
    #[error("unknown MachineState byte: {0}")]
    UnknownMachineState(u8),
    /// A substate byte did not match any known [`SubState`](crate::SubState).
    #[error("unknown SubState byte: {0}")]
    UnknownSubState(u8),
    /// A calibration command byte did not match any known
    /// [`CalCommand`](crate::CalCommand).
    #[error("unknown CalCommand byte: {0}")]
    UnknownCalCommand(u8),
    /// A calibration target byte did not match any known
    /// [`CalTarget`](crate::CalTarget).
    #[error("unknown CalTarget byte: {0}")]
    UnknownCalTarget(u8),
    /// A firmware-frame upload offset did not fit the 24-bit address field.
    #[error("firmware offset out of 24-bit range: {0:#x}")]
    FirmwareOffsetOutOfRange(u32),
}
