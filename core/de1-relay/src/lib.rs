//! # de1-relay
//!
//! The Crema LAN relay's secure channel (relay protocol v4). A remote pairs
//! with a hub once, TV-style: the remote shows a 6-digit code, the hub user
//! types it, SPAKE2 turns the code into a key, and a Noise `XXpsk3` handshake
//! keyed by it exchanges and authenticates the two static keys, which both
//! sides pin. Every later connection is a Noise `IK` handshake with the pins,
//! and every frame after it rides in that session's transport.
//!
//! Sans-IO, like the other core crates: no sockets, no clock and no entropy
//! source. The shell hands in WebSocket messages, the time (`now_ms`) and a
//! 32-byte [`Seed`] per key pair and connection; the core answers with a
//! [`Step`] (what to send, what to deliver, events, and whether to close).
//!
//! - [`HubConnection`]: one per accepted WebSocket on the hub (the responder).
//! - [`RemoteConnection`]: one per dialled WebSocket on the remote (the initiator).
//! - [`PairingGate`]: the hub's pairing window, failure count and lockout, one per
//!   hub process, shared by every [`HubConnection`].
//! - [`PinSet`]: the hub's live set of pinned remote keys, shared the same way.
//!
//! `PROTOCOL.md` next to this crate is the normative wire and crypto description.

mod gate;
mod hello;
mod hub;
mod keys;
mod noise;
pub mod pake;
mod pins;
mod remote;
mod seed;
mod step;
mod transport;
mod wire;

#[cfg(feature = "test-seeds")]
pub mod fuzzing;
#[cfg(feature = "test-seeds")]
pub mod test_support;
#[cfg(feature = "test-seeds")]
pub mod test_vectors;

pub use gate::{GateStatus, PairingGate};
pub use hello::{
    ClientHello, HelloStatus, Intent, LEGACY_DENIAL, ParsedClientHello, ServerHello, sanitize_name,
};
pub use hub::HubConnection;
pub use keys::{DeviceId, StaticKeypair};
pub use noise::{IK_MESSAGE_LENS, IK_PARAMS, RelayResolver, XX_MESSAGE_LENS, XX_PARAMS};
pub use pins::{PinSet, is_low_order};
pub use remote::RemoteConnection;
pub use seed::{Seed, SeededRandom, SubSeed};
pub use step::{
    AbortReason, Close, CloseCode, CloseReason, Outgoing, Phase, RelayError, RelayEvent, Step, WsIn,
};
pub use wire::{inner, kind};

/// The only protocol version this build speaks, and its supported range.
pub const PROTOCOL_VERSION: u8 = 4;
/// Lowest version this build accepts.
pub const VERSION_MIN: u8 = 4;
/// Highest version this build accepts.
pub const VERSION_MAX: u8 = 4;

/// Largest ClientHello text the hub accepts, in bytes.
pub const CLIENT_HELLO_MAX: usize = 1024;
/// Largest display name on the wire, in UTF-8 bytes.
pub const NAME_MAX_BYTES: usize = 64;
/// Longest display name after sanitizing, in characters.
pub const NAME_MAX_CHARS: usize = 32;

/// The hub waits this long for the ClientHello; the remote for the ServerHello.
pub const HELLO_DEADLINE_MS: u64 = 5_000;
/// The hub waits this long for `PAKE_A` after `AWAITING_CODE`.
pub const PAKE_A_DEADLINE_MS: u64 = 5_000;
/// How long a pairing window stays open.
pub const PAIR_WINDOW_MS: u64 = 120_000;
/// Longest a pairing code lives (also capped by the hub's `secs_left`).
pub const CODE_LIFETIME_MS: u64 = 120_000;
/// Time for the rest of the ceremony once the hub user entered the code.
pub const PAIR_FINISH_MS: u64 = 10_000;
/// From the ServerHello to admission (the remote's first record) on resume.
pub const RESUME_DEADLINE_MS: u64 = 5_000;
/// Failed codes per window; the last one locks pairing out.
pub const MAX_PAIR_FAILURES: u8 = 3;
/// How long pairing stays locked out after the last failed code.
pub const PAIR_LOCKOUT_MS: u64 = 60_000;
/// Automatic pairing attempts per user action on the remote (a shell rule).
pub const MAX_PAIR_ATTEMPTS: u8 = 3;
/// Unauthenticated sessions the hub's acceptor allows at once (a shell rule).
pub const MAX_UNAUTH_SESSIONS: usize = 4;
/// Unauthenticated sessions per remote address (a shell rule).
pub const MAX_UNAUTH_PER_ADDRESS: usize = 2;

/// Largest Noise transport message, tag included.
pub const NOISE_MAX_MESSAGE: usize = 65_535;
/// The Poly1305 tag on every record.
pub const TAG_LEN: usize = 16;
/// Application bytes in one record: 65 535 − 16 (tag) − 2 (inner header).
pub const MAX_RECORD_PAYLOAD: usize = NOISE_MAX_MESSAGE - TAG_LEN - 2;
/// Largest WebSocket message either side sends or accepts (a record plus its kind byte).
pub const WS_MAX_MESSAGE: usize = 65_536;
/// Largest application message (one `Frame`), reassembled.
pub const MAX_MESSAGE_BYTES: usize = 1_048_576;
/// Records per direction between two rekeys.
pub const REKEY_INTERVAL: u64 = 65_536;
/// Records per direction after which the connection gives up (unreachable in practice).
pub const NONCE_LIMIT: u64 = 1 << 62;
/// An idle sender emits a `HEARTBEAT` after this long.
pub const HEARTBEAT_IDLE_MS: u64 = 10_000;
/// A session with no complete message or `HEARTBEAT` for this long times out.
pub const RECV_TIMEOUT_MS: u64 = 30_000;

/// Domain-separation labels (Appendix A of the spec).
pub mod labels {
    /// Device id hash prefix.
    pub const DEVICE_ID: &[u8] = b"CremaRelay v4 device id";
    /// SPAKE2 `id_A` prefix (the remote).
    pub const SPAKE_ID_REMOTE: &[u8] = b"CremaRelay v4 remote ";
    /// SPAKE2 `id_B` prefix (the hub).
    pub const SPAKE_ID_HUB: &[u8] = b"CremaRelay v4 hub ";
    /// Pairing prologue prefix.
    pub const PAIR_PROLOGUE: &[u8] = b"CremaRelay v4 pair";
    /// Resume prologue prefix.
    pub const RESUME_PROLOGUE: &[u8] = b"CremaRelay v4 resume";
    /// psk HKDF info prefix.
    pub const PSK: &[u8] = b"CremaRelay v4 psk";
    /// Channel-binding HKDF info prefix (`CremaRelay v4 ‖ purpose`).
    pub const BINDING: &[u8] = b"CremaRelay v4 ";
    /// Test seed prefix.
    pub const TEST_SEED: &[u8] = b"de1-relay test seed ";
    /// Sub-seed label for the pairing code stream.
    pub const STREAM_CODE: &[u8] = b"code";
    /// Sub-seed label for the SPAKE2 scalar stream.
    pub const STREAM_SPAKE: &[u8] = b"spake";
    /// Sub-seed label prefix for Noise RNG streams (followed by a counter).
    pub const STREAM_NOISE: &[u8] = b"noise";
    /// Sub-seed label for static key generation.
    pub const STREAM_STATIC: &[u8] = b"static";
}

/// WebSocket close codes (§4.5).
pub mod ws_close {
    /// Normal: pairing done, hub mode off.
    pub const NORMAL: u16 = 1000;
    /// Protocol error.
    pub const PROTOCOL: u16 = 4400;
    /// Authentication failed (handshake or record).
    pub const AUTH: u16 = 4401;
    /// Refused: pairing closed, busy, locked, cancelled, unknown or revoked remote.
    pub const REFUSED: u16 = 4403;
    /// Timeout.
    pub const TIMEOUT: u16 = 4408;
    /// Version: legacy peer or no common version.
    pub const VERSION: u16 = 4426;
}
