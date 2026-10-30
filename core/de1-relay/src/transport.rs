//! The record layer after a handshake (§2.7): encryption into canonical
//! fragments, decryption and reassembly, rekeying, heartbeats and limits.
//!
//! Every failure here is fatal for the connection: there is no
//! resynchronisation, and a session that ends mid-message drops its buffer.

use snow::TransportState;

use crate::{
    CloseCode, HEARTBEAT_IDLE_MS, MAX_MESSAGE_BYTES, MAX_RECORD_PAYLOAD, NOISE_MAX_MESSAGE,
    NONCE_LIMIT, RECV_TIMEOUT_MS, REKEY_INTERVAL, TAG_LEN,
    wire::{self, inner, kind},
};

/// One decrypted record, as the connection sees it.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Inbound {
    /// A complete application message.
    Frame(Vec<u8>),
    /// A non-final fragment; nothing to deliver yet.
    Partial,
    /// A `HEARTBEAT`.
    Heartbeat,
    /// The hub's key confirmation.
    PairComplete,
    /// An authenticated `CLOSE`.
    Close(CloseCode),
}

/// Why the record layer gave up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TransportError {
    /// A record failed authentication (close 4401).
    Decrypt,
    /// A rule of §2.7/§4.4 was broken (close 4400).
    Protocol(&'static str),
    /// A message over `MAX_MESSAGE_BYTES` (close 4400).
    TooLarge,
    /// The record counter reached `NONCE_LIMIT`.
    NonceExhausted,
}

/// A session's transport: snow's two CipherStates plus our framing.
pub(crate) struct Transport {
    state: TransportState,
    rekey_interval: u64,
    reassembly: Vec<u8>,
    fragmenting: bool,
    last_sent_ms: u64,
    last_alive_ms: u64,
}

impl Transport {
    pub(crate) fn new(state: TransportState, rekey_interval: u64, now_ms: u64) -> Self {
        Self {
            state,
            rekey_interval: rekey_interval.max(1),
            reassembly: Vec::new(),
            fragmenting: false,
            last_sent_ms: now_ms,
            last_alive_ms: now_ms,
        }
    }

    /// Bytes held for a message still being reassembled.
    #[cfg(feature = "test-seeds")]
    pub(crate) fn buffered(&self) -> usize {
        self.reassembly.len()
    }

    fn encrypt_record(
        &mut self,
        flags: u8,
        ty: u8,
        payload: &[u8],
    ) -> Result<Vec<u8>, TransportError> {
        let n = self.state.sending_nonce();
        if n >= NONCE_LIMIT {
            return Err(TransportError::NonceExhausted);
        }
        let mut plain = Vec::with_capacity(2 + payload.len());
        plain.push(flags);
        plain.push(ty);
        plain.extend_from_slice(payload);
        let mut out = vec![0_u8; 1 + plain.len() + TAG_LEN];
        out[0] = kind::RECORD;
        let len = self
            .state
            .write_message(&plain, &mut out[1..])
            .map_err(|_| TransportError::Protocol("record encrypt"))?;
        out.truncate(1 + len);
        if (n + 1).is_multiple_of(self.rekey_interval) {
            self.state.rekey_outgoing();
        }
        Ok(out)
    }

    /// Encrypts one message into its records, in wire order. Only `FRAME`
    /// fragments: every record but the last carries exactly
    /// `MAX_RECORD_PAYLOAD` bytes and `MORE`.
    pub(crate) fn encrypt(
        &mut self,
        ty: u8,
        payload: &[u8],
        now_ms: u64,
    ) -> Result<Vec<Vec<u8>>, TransportError> {
        if payload.len() > MAX_MESSAGE_BYTES {
            return Err(TransportError::TooLarge);
        }
        if ty != inner::FRAME && payload.len() > MAX_RECORD_PAYLOAD {
            return Err(TransportError::Protocol("unfragmentable message too large"));
        }
        let chunks: Vec<&[u8]> = if payload.is_empty() {
            vec![payload]
        } else {
            payload.chunks(MAX_RECORD_PAYLOAD).collect()
        };
        // Refuse up front rather than consume nonces for half a message.
        let needed = u64::try_from(chunks.len()).unwrap_or(u64::MAX);
        if self.state.sending_nonce().saturating_add(needed) > NONCE_LIMIT {
            return Err(TransportError::NonceExhausted);
        }
        let last = chunks.len() - 1;
        let mut records = Vec::with_capacity(chunks.len());
        for (i, chunk) in chunks.into_iter().enumerate() {
            let flags = if i < last { inner::FLAG_MORE } else { 0 };
            records.push(self.encrypt_record(flags, ty, chunk)?);
        }
        self.last_sent_ms = now_ms;
        Ok(records)
    }

    /// Decrypts one `RECORD` body (after the kind byte) and applies the
    /// canonical fragmentation rules.
    pub(crate) fn decrypt(&mut self, body: &[u8], now_ms: u64) -> Result<Inbound, TransportError> {
        if body.len() > NOISE_MAX_MESSAGE {
            return Err(TransportError::Protocol("record too large"));
        }
        let n = self.state.receiving_nonce();
        if n >= NONCE_LIMIT {
            return Err(TransportError::NonceExhausted);
        }
        let mut plain = vec![0_u8; body.len()];
        let len = self
            .state
            .read_message(body, &mut plain)
            .map_err(|_| TransportError::Decrypt)?;
        plain.truncate(len);
        if (n + 1).is_multiple_of(self.rekey_interval) {
            self.state.rekey_incoming();
        }
        let [flags, ty, payload @ ..] = plain.as_slice() else {
            return Err(TransportError::Protocol("record shorter than its header"));
        };
        if flags & !inner::FLAG_MORE != 0 {
            return Err(TransportError::Protocol("reserved flag bit set"));
        }
        let more = flags & inner::FLAG_MORE != 0;
        if *ty != inner::FRAME {
            if more {
                return Err(TransportError::Protocol("MORE on a non-FRAME record"));
            }
            if self.fragmenting {
                return Err(TransportError::Protocol("record between fragments"));
            }
        }
        match *ty {
            inner::FRAME => self.on_frame(payload, more, now_ms),
            inner::HEARTBEAT => {
                if !payload.is_empty() {
                    return Err(TransportError::Protocol("HEARTBEAT with a payload"));
                }
                self.last_alive_ms = now_ms;
                Ok(Inbound::Heartbeat)
            }
            inner::PAIR_COMPLETE => {
                if !payload.is_empty() {
                    return Err(TransportError::Protocol("PAIR_COMPLETE with a payload"));
                }
                Ok(Inbound::PairComplete)
            }
            inner::CLOSE => {
                let (code, _text) = wire::decode_close(payload)
                    .map_err(|_| TransportError::Protocol("malformed CLOSE"))?;
                Ok(Inbound::Close(code))
            }
            _ => Err(TransportError::Protocol("unknown inner type")),
        }
    }

    fn on_frame(
        &mut self,
        payload: &[u8],
        more: bool,
        now_ms: u64,
    ) -> Result<Inbound, TransportError> {
        if more && payload.len() != MAX_RECORD_PAYLOAD {
            return Err(TransportError::Protocol(
                "non-final fragment of the wrong size",
            ));
        }
        if !more && self.fragmenting && payload.is_empty() {
            return Err(TransportError::Protocol("empty final fragment"));
        }
        if self.reassembly.len() + payload.len() > MAX_MESSAGE_BYTES {
            self.drop_partial();
            return Err(TransportError::TooLarge);
        }
        if more {
            self.reassembly.extend_from_slice(payload);
            self.fragmenting = true;
            return Ok(Inbound::Partial);
        }
        let message = if self.fragmenting {
            self.reassembly.extend_from_slice(payload);
            self.fragmenting = false;
            core::mem::take(&mut self.reassembly)
        } else {
            payload.to_vec()
        };
        self.last_alive_ms = now_ms;
        Ok(Inbound::Frame(message))
    }

    /// Encrypts one record with arbitrary flags and type (tests and fuzzing).
    #[cfg(feature = "test-seeds")]
    pub(crate) fn encrypt_raw(
        &mut self,
        flags: u8,
        ty: u8,
        payload: &[u8],
        now_ms: u64,
    ) -> Result<Vec<u8>, TransportError> {
        let record = self.encrypt_record(flags, ty, payload)?;
        self.last_sent_ms = now_ms;
        Ok(record)
    }

    /// Frees a partial message (the session is ending).
    pub(crate) fn drop_partial(&mut self) {
        self.reassembly = Vec::new();
        self.fragmenting = false;
    }

    /// When `tick` next has something to do.
    pub(crate) fn next_deadline_ms(&self) -> u64 {
        (self.last_sent_ms + HEARTBEAT_IDLE_MS).min(self.last_alive_ms + RECV_TIMEOUT_MS)
    }

    /// Whether the receive side timed out.
    pub(crate) fn timed_out(&self, now_ms: u64) -> bool {
        now_ms >= self.last_alive_ms + RECV_TIMEOUT_MS
    }

    /// Whether an idle sender owes a heartbeat.
    pub(crate) fn heartbeat_due(&self, now_ms: u64) -> bool {
        now_ms >= self.last_sent_ms + HEARTBEAT_IDLE_MS
    }
}

/// The default rekey interval, or a reduced one for tests.
pub(crate) fn rekey_interval(test_override: Option<u64>) -> u64 {
    test_override.unwrap_or(REKEY_INTERVAL)
}
