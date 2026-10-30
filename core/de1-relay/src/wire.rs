//! The binary envelope (§4.3) and the inner record header (§4.4).

use crate::{AbortReason, CloseCode, RelayError};

/// Binary message kinds: the first byte of every binary WebSocket message.
pub mod kind {
    /// Hub → remote: the ServerHello.
    pub const SERVER_HELLO: u8 = 0x10;
    /// Either, before the handshake completes: an advisory refusal.
    pub const ABORT: u8 = 0x11;
    /// Remote → hub: SPAKE2 message A.
    pub const PAKE_A: u8 = 0x20;
    /// Hub → remote: SPAKE2 message B.
    pub const PAKE_B: u8 = 0x21;
    /// Either: one Noise handshake message.
    pub const NOISE_HS: u8 = 0x30;
    /// Either, after the handshake: one Noise transport message.
    pub const RECORD: u8 = 0x40;
}

/// Inner types inside a decrypted record (`flags ‖ type ‖ payload`).
pub mod inner {
    /// One application message (a `Frame`'s JSON); the only fragmentable type.
    pub const FRAME: u8 = 0x01;
    /// Liveness, and the remote's "client finished" first record.
    pub const HEARTBEAT: u8 = 0x02;
    /// Hub → remote, pairing only: key confirmation.
    pub const PAIR_COMPLETE: u8 = 0x03;
    /// An authenticated refusal or close.
    pub const CLOSE: u8 = 0x04;
    /// `flags` bit 0: more fragments follow.
    pub const FLAG_MORE: u8 = 0x01;
}

/// Longest `ABORT` detail and `CLOSE` text.
pub(crate) const DETAIL_MAX: usize = 64;

/// The length of a SPAKE2 message on the wire (side byte + element).
pub(crate) const PAKE_LEN: usize = 33;

pub(crate) fn encode_abort(reason: AbortReason, detail: &[u8]) -> Vec<u8> {
    debug_assert!(detail.len() <= DETAIL_MAX);
    let mut out = Vec::with_capacity(3 + detail.len());
    out.push(kind::ABORT);
    out.push(reason as u8);
    out.push(u8::try_from(detail.len()).unwrap_or(0));
    out.extend_from_slice(detail);
    out
}

/// An `ABORT` body (after the kind byte).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct Abort {
    pub reason: AbortReason,
    /// For `VERSION`, the sender's max version.
    pub peer_max: Option<u8>,
}

pub(crate) fn decode_abort(body: &[u8]) -> Result<Abort, RelayError> {
    let [reason, len, detail @ ..] = body else {
        return Err(RelayError::Malformed("abort length"));
    };
    let reason = AbortReason::from_u8(*reason).ok_or(RelayError::Malformed("abort reason"))?;
    if usize::from(*len) != detail.len() || detail.len() > DETAIL_MAX {
        return Err(RelayError::Malformed("abort detail length"));
    }
    if reason == AbortReason::Version {
        let [max] = detail else {
            return Err(RelayError::Malformed("abort version detail"));
        };
        return Ok(Abort {
            reason,
            peer_max: Some(*max),
        });
    }
    if !detail.is_ascii() {
        return Err(RelayError::Malformed("abort detail"));
    }
    Ok(Abort {
        reason,
        peer_max: None,
    })
}

pub(crate) fn encode_pake(kind_byte: u8, msg: &[u8; PAKE_LEN]) -> Vec<u8> {
    let mut out = Vec::with_capacity(1 + PAKE_LEN);
    out.push(kind_byte);
    out.extend_from_slice(msg);
    out
}

pub(crate) fn encode_handshake(msg: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(1 + msg.len());
    out.push(kind::NOISE_HS);
    out.extend_from_slice(msg);
    out
}

/// A `CLOSE` payload: `code u8 ‖ text_len u8 ‖ text`.
pub(crate) fn encode_close(code: CloseCode, text: &str) -> Vec<u8> {
    let text = &text.as_bytes()[..text.len().min(DETAIL_MAX)];
    let mut out = Vec::with_capacity(2 + text.len());
    out.push(code as u8);
    out.push(u8::try_from(text.len()).unwrap_or(0));
    out.extend_from_slice(text);
    out
}

pub(crate) fn decode_close(payload: &[u8]) -> Result<(CloseCode, String), RelayError> {
    let [code, len, text @ ..] = payload else {
        return Err(RelayError::Malformed("close length"));
    };
    let code = CloseCode::from_u8(*code).ok_or(RelayError::Malformed("close code"))?;
    if usize::from(*len) != text.len() || text.len() > DETAIL_MAX {
        return Err(RelayError::Malformed("close text length"));
    }
    let text = core::str::from_utf8(text).map_err(|_| RelayError::Malformed("close text"))?;
    Ok((code, text.to_owned()))
}
