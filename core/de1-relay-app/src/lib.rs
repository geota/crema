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
//! - [`jvm`]: the Kotlin/JVM text and number rules those checks keep, so the
//!   wire and the refusal texts are what the Android shell sent before.
//!
//! The wire fixtures in `tests/golden/v4/` are shared: the cargo tests here
//! and the Android shell's golden tests read the same files.

pub mod command;
pub mod fuzzing;
pub mod jvm;

pub use command::{
    Decoded, METHODS, QcField, RelayCommand, Wire, decode_command, encode_command, refusal,
    relayable_states,
};
