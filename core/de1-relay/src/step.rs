//! What a connection call returns to the shell, and the error and event types.

use serde::Serialize;
use typeshare::typeshare;

/// One incoming WebSocket message. `Text` carries the raw bytes of the text
/// frame; the core validates UTF-8 itself (the prologue binds the exact bytes).
#[derive(Clone, Copy, Debug)]
pub enum WsIn<'a> {
    /// A WebSocket text message, as received.
    Text(&'a [u8]),
    /// A WebSocket binary message.
    Binary(&'a [u8]),
}

/// One outgoing WebSocket message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Outgoing {
    /// Send as a text message (only the ClientHello and the legacy denial).
    pub text: bool,
    /// The message bytes.
    pub bytes: Vec<u8>,
}

impl Outgoing {
    pub(crate) fn binary(bytes: Vec<u8>) -> Self {
        Self { text: false, bytes }
    }

    pub(crate) fn text(bytes: Vec<u8>) -> Self {
        Self { text: true, bytes }
    }
}

/// Close the WebSocket with this code (§4.5) after sending `Step::send`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Close {
    /// WebSocket close code.
    pub code: u16,
    /// Short ASCII reason for the close frame.
    pub reason: String,
}

/// The result of one call: apply in this order — send (in order,
/// contiguously, under the connection's send lock), deliver, events, close.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Step {
    /// Messages to send, in order.
    pub send: Vec<Outgoing>,
    /// Complete application messages (FRAME payloads), in order.
    pub deliver: Vec<Vec<u8>>,
    /// Events for the shell.
    pub events: Vec<RelayEvent>,
    /// Close the WebSocket after sending.
    pub close: Option<Close>,
}

impl Step {
    pub(crate) fn push_binary(&mut self, bytes: Vec<u8>) {
        self.send.push(Outgoing::binary(bytes));
    }

    pub(crate) fn close_with(&mut self, code: u16, reason: &str) {
        self.close = Some(Close {
            code,
            reason: reason.to_owned(),
        });
    }

    /// True when the step carries nothing at all.
    pub fn is_empty(&self) -> bool {
        self.send.is_empty()
            && self.deliver.is_empty()
            && self.events.is_empty()
            && self.close.is_none()
    }
}

/// Where a connection is (§3.2).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    /// Waiting for the peer's hello.
    AwaitHello,
    /// Hub: slot granted, waiting for `PAKE_A` and the code entry.
    AwaitCode,
    /// Remote: code shown, `PAKE_A` sent, waiting for `PAKE_B`.
    Pake,
    /// Noise handshake in progress.
    Handshake,
    /// Hub: pairing confirmed, waiting for the shell's pin commit.
    AwaitCommit,
    /// Remote: XX message 3 sent, waiting for `PAIR_COMPLETE`.
    AwaitConfirm,
    /// Hub: IK message 2 sent, waiting for the remote's first record.
    AwaitClientFinished,
    /// Session admitted; frames flow.
    Established,
    /// Pairing finished; the connection is closing.
    Paired,
    /// Ended in an orderly way (a refusal, a close, a cancel).
    Closed,
    /// Ended by an error.
    Failed,
}

/// Why the shell closes an established session (§3.2).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CloseReason {
    /// The hub forgot this remote.
    Revoked,
    /// The remote isn't paired with this hub.
    NotPaired,
    /// The hub user turned Hub mode off.
    HubStopped,
    /// Any other orderly close.
    Normal,
}

/// `ABORT.reason` (§4.3): plaintext, advisory.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AbortReason {
    /// The hub's pairing window is closed.
    PairingClosed = 1,
    /// Another pairing is in progress.
    Busy = 2,
    /// Pairing is locked out after too many wrong codes.
    LockedOut = 3,
    /// Cancelled by a user or the shell.
    Cancelled = 4,
    /// A deadline passed.
    Timeout = 5,
    /// The Noise handshake failed (including a wrong code).
    HandshakeFailed = 6,
    /// The hub id differs from the pinned one.
    HostMismatch = 7,
    /// No common protocol version.
    Version = 8,
    /// A malformed or unexpected message.
    Malformed = 9,
}

impl AbortReason {
    pub(crate) fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            1 => Self::PairingClosed,
            2 => Self::Busy,
            3 => Self::LockedOut,
            4 => Self::Cancelled,
            5 => Self::Timeout,
            6 => Self::HandshakeFailed,
            7 => Self::HostMismatch,
            8 => Self::Version,
            9 => Self::Malformed,
            _ => return None,
        })
    }

    /// The name used in events.
    pub fn name(self) -> &'static str {
        match self {
            Self::PairingClosed => "PairingClosed",
            Self::Busy => "Busy",
            Self::LockedOut => "LockedOut",
            Self::Cancelled => "Cancelled",
            Self::Timeout => "Timeout",
            Self::HandshakeFailed => "HandshakeFailed",
            Self::HostMismatch => "HostMismatch",
            Self::Version => "Version",
            Self::Malformed => "Malformed",
        }
    }
}

/// The code inside an authenticated `CLOSE` record (§4.4).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CloseCode {
    /// The hub forgot this remote.
    Revoked = 1,
    /// The remote isn't paired with this hub.
    NotPaired = 2,
    /// The hub user turned Hub mode off.
    HubStopped = 3,
    /// A protocol error.
    Protocol = 4,
    /// An orderly close.
    Normal = 5,
}

impl CloseCode {
    pub(crate) fn from_u8(v: u8) -> Option<Self> {
        Some(match v {
            1 => Self::Revoked,
            2 => Self::NotPaired,
            3 => Self::HubStopped,
            4 => Self::Protocol,
            5 => Self::Normal,
            _ => return None,
        })
    }

    /// The name used in events.
    pub fn name(self) -> &'static str {
        match self {
            Self::Revoked => "Revoked",
            Self::NotPaired => "NotPaired",
            Self::HubStopped => "HubStopped",
            Self::Protocol => "Protocol",
            Self::Normal => "Normal",
        }
    }

    /// The WebSocket close code that goes with it.
    pub(crate) fn ws_code(self) -> u16 {
        match self {
            Self::Revoked | Self::NotPaired => crate::ws_close::REFUSED,
            Self::Protocol => crate::ws_close::PROTOCOL,
            Self::HubStopped | Self::Normal => crate::ws_close::NORMAL,
        }
    }
}

impl From<CloseReason> for CloseCode {
    fn from(r: CloseReason) -> Self {
        match r {
            CloseReason::Revoked => Self::Revoked,
            CloseReason::NotPaired => Self::NotPaired,
            CloseReason::HubStopped => Self::HubStopped,
            CloseReason::Normal => Self::Normal,
        }
    }
}

/// Errors (§3.4). Connections turn most of them into a `Step` that closes;
/// the methods that return `Result` use them for calls the shell made in the
/// wrong state or with bad input.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum RelayError {
    /// A message or value that doesn't parse.
    #[error("malformed: {0}")]
    Malformed(&'static str),
    /// A message the phase doesn't expect.
    #[error("unexpected message")]
    UnexpectedMessage,
    /// No common protocol version.
    #[error("version unsupported (peer max {peer_max})")]
    VersionUnsupported {
        /// The peer's highest version.
        peer_max: u8,
    },
    /// A v2/v3 peer.
    #[error("legacy peer (v{version})")]
    LegacyPeer {
        /// The peer's version.
        version: u32,
    },
    /// The Noise handshake failed.
    #[error("handshake failed")]
    HandshakeFailed,
    /// A low-order X25519 key.
    #[error("low-order key")]
    LowOrderKey,
    /// The peer's static key doesn't match the id it claimed.
    #[error("peer id mismatch")]
    PeerIdMismatch,
    /// The hub id differs from the pinned one.
    #[error("host mismatch")]
    HostMismatch,
    /// The code didn't match (hub: message 3 failed).
    #[error("wrong code")]
    WrongCode,
    /// The pairing wasn't confirmed (remote: no `PAIR_COMPLETE`).
    #[error("not confirmed")]
    NotConfirmed,
    /// A code entry that isn't exactly 6 ASCII digits.
    #[error("code must be 6 digits")]
    CodeFormat,
    /// The pairing window is closed.
    #[error("pairing closed")]
    PairingClosed,
    /// Another pairing is in progress.
    #[error("pairing busy")]
    PairingBusy,
    /// Pairing is locked out.
    #[error("locked out ({secs_left} s left)")]
    LockedOut {
        /// Seconds until the lockout ends.
        secs_left: u32,
    },
    /// A deadline passed.
    #[error("timeout")]
    Timeout,
    /// A record failed to decrypt.
    #[error("decrypt failed")]
    Decrypt,
    /// A message over `MAX_MESSAGE_BYTES`.
    #[error("message too large")]
    MessageTooLarge,
    /// The record counter reached `NONCE_LIMIT`.
    #[error("nonce exhausted")]
    NonceExhausted,
    /// The peer sent `ABORT`.
    #[error("aborted: {}", .0.name())]
    Aborted(AbortReason),
    /// The connection is closed.
    #[error("closed")]
    Closed,
    /// A call the phase doesn't allow.
    #[error("invalid state")]
    InvalidState,
    /// The platform's entropy source failed (bridges only).
    #[error("entropy unavailable")]
    Entropy,
    /// A lock was poisoned by a panic; the object fails closed for good.
    #[error("poisoned")]
    Poisoned,
}

impl RelayError {
    /// The variant's name, for events.
    pub fn name(&self) -> &'static str {
        match self {
            Self::Malformed(_) => "Malformed",
            Self::UnexpectedMessage => "UnexpectedMessage",
            Self::VersionUnsupported { .. } => "VersionUnsupported",
            Self::LegacyPeer { .. } => "LegacyPeer",
            Self::HandshakeFailed => "HandshakeFailed",
            Self::LowOrderKey => "LowOrderKey",
            Self::PeerIdMismatch => "PeerIdMismatch",
            Self::HostMismatch => "HostMismatch",
            Self::WrongCode => "WrongCode",
            Self::NotConfirmed => "NotConfirmed",
            Self::CodeFormat => "CodeFormat",
            Self::PairingClosed => "PairingClosed",
            Self::PairingBusy => "PairingBusy",
            Self::LockedOut { .. } => "LockedOut",
            Self::Timeout => "Timeout",
            Self::Decrypt => "Decrypt",
            Self::MessageTooLarge => "MessageTooLarge",
            Self::NonceExhausted => "NonceExhausted",
            Self::Aborted(_) => "Aborted",
            Self::Closed => "Closed",
            Self::InvalidState => "InvalidState",
            Self::Entropy => "Entropy",
            Self::Poisoned => "Poisoned",
        }
    }
}

/// Events for the shell (§3.4). Names are sanitized; public keys never ride
/// in events (read `peer_public_key()`).
#[typeshare]
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "type", content = "content")]
pub enum RelayEvent {
    /// Hub: a v2/v3 remote dialled; it got the legacy denial.
    LegacyPeer {
        /// The remote's protocol version.
        version: u32,
        /// The remote's display name.
        name: String,
    },
    /// Hub: a remote asks to pair; show the code entry dialog.
    PairRequest {
        /// The remote's claimed device id (unauthenticated until pairing completes).
        remote_id: String,
        /// The remote's display name.
        remote_name: String,
    },
    /// Hub: the code matched. Persist the pin, insert it into the live
    /// `PinSet`, then call `confirm_paired()` (or `reject_pairing()` if the write failed).
    PairPending {
        /// The remote's device id, checked against its static key.
        remote_id: String,
        /// The remote's display name.
        remote_name: String,
    },
    /// A pairing attempt ended without success. `failures`/`locked` describe the
    /// hub's gate after it (always 0/false on the remote).
    PairingFailed {
        /// `WrongCode`, `NotConfirmed`, `Timeout`, `Cancelled`, `Malformed`, …
        reason: String,
        /// Failed codes counted in this window.
        failures: u8,
        /// Whether pairing is now locked out.
        locked: bool,
    },
    /// Hub: an unpinned remote tried to resume; it got `CLOSE(NOT_PAIRED)`.
    UnknownRemote {
        /// The remote's device id.
        remote_id: String,
    },
    /// Remote: the hub's ServerHello (advisory until the handshake).
    HubHello {
        /// The hub's device id.
        hub_id: String,
        /// The hub's display name.
        hub_name: String,
        /// `Ready`, `AwaitingCode`, `PairingClosed`, `Busy` or `LockedOut`.
        status: String,
        /// Seconds left in the pairing window (or the lockout).
        secs_left: u32,
    },
    /// Remote: show the code, read from `RemoteConnection::code()`, until
    /// `expires_at_ms`. The event itself never carries the code, so no event,
    /// event JSON or record a bridge builds from it can leak it into a log.
    ShowCode {
        /// When the code expires (`now_ms` clock).
        #[typeshare(serialized_as = "U64")]
        expires_at_ms: u64,
    },
    /// Remote: the hub refused. Only `authenticated` refusals (a `CLOSE` record)
    /// may change trust state or role.
    Refused {
        /// `HostMismatch`, `HubOutdated`, `HubUnverified`, `Timeout`, `Revoked`,
        /// `NotPaired`, `HubStopped`, `Protocol`, `Normal`, or an `ABORT` reason.
        reason: String,
        /// Whether the refusal arrived inside the session.
        authenticated: bool,
    },
    /// Both: pairing completed.
    Paired {
        /// The peer's device id.
        peer_id: String,
        /// The peer's display name.
        peer_name: String,
    },
    /// Both: the session is up (hub: admitted; remote: hub authenticated).
    Established {
        /// The peer's device id.
        peer_id: String,
    },
    /// Hub: the remote ended an established session with an authenticated `CLOSE` record.
    PeerClosed {
        /// `Normal`, `Protocol`, … (the record's code).
        reason: String,
    },
    /// The connection failed for a reason no more specific event covers.
    Failed {
        /// The `RelayError` name.
        error: String,
    },
}
