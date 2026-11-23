//! # de1-relay-app
//!
//! The Crema LAN relay's app layer, above the secure channel (`de1-relay`):
//! what the frames a session carries mean. Sans-IO and pure, like the rest of
//! the core, and free of crypto: nothing here sees a key or a socket.
//!
//! - [`command`]: relayed commands, their `control` frame wire form
//!   ([`encode_command`], [`decode_command`]) and the argument checks a hub
//!   runs on whatever a remote sends ([`refusal`]). Whether an id names a
//!   record in the hub's library is the shell's check, after these.
//! - [`frame`]: the frames a session carries, tagged by `"type"`
//!   ([`encode_frame`], [`decode_frame`], which skips an unknown type).
//! - [`jvm`] and [`json`]: the Kotlin/JVM text and number rules and the
//!   kotlinx JSON rules the shell's code followed, so the wire, what is
//!   accepted and the refusal texts are what the Android shell sent and read
//!   before. "Byte for byte" was checked against the JVM the tests run on
//!   (JDK 21); on a device, ART's `Double.toString` may print a few doubles
//!   with more digits than the shortest, which parse back to the same value.
//!
//! The wire fixtures in `tests/golden/v4/` are shared: the cargo tests here
//! and the Android shell's golden tests read the same files.

pub mod command;
pub mod frame;
pub mod fuzzing;
pub mod json;
pub mod jvm;

pub use frame::{DeviceInfo, FRAME_TYPES, Frame, FrameError, decode_frame, encode_frame};

pub use command::{
    Decoded, METHODS, QcField, RelayCommand, Wire, decode_command, encode_command, refusal,
    relayable_states,
};
