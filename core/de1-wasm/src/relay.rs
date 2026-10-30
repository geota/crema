//! The relay v4 secure channel for the web shell (spec §3.6): the remote side
//! and keys only (a browser can't host the hub's WebSocket server).
//!
//! Kept in its own module, separate from the `CremaBridge` exports.
//!
//! - **Entropy.** Every constructor that draws entropy takes 32 fresh bytes from
//!   `crypto.getRandomValues` (`getrandom` with `wasm_js`). A failure is a
//!   `JsError` and nothing is built; no fixed or default seed exists. The
//!   `*ForTests` constructors are only compiled with the `test-seeds` feature,
//!   which only the separate vitest bundle (`pnpm wasm:test`) has.
//! - **Failing closed.** A connection keeps its core state in a take/put slot.
//!   A call that traps (a panic is a trap on wasm) never puts it back, so the
//!   connection is dead for good: every later call returns a Step that closes
//!   with 4400 and carries nothing else. Unlike `CremaBridge` there is no reset:
//!   a half-updated Noise state can't be resumed. An invalid `nowMs` ends the
//!   connection the same way.
//! - **Keys.** No export returns private key bytes except `privateForStorage`.
//!
//! The logic lives in plain Rust methods returning `RelayError`, so the host
//! tests run it; the exported methods only convert errors to `JsError`.

use std::cell::{Cell, RefCell};

use de1_relay::{CloseReason, RelayError, RemoteConnection, Seed, StaticKeypair, Step, WsIn};
use wasm_bindgen::prelude::*;

/// The WebSocket close code a dead connection closes with (protocol error).
const DEAD_CLOSE: u16 = 4400;

/// The largest integer a JS number holds exactly (`Number.MAX_SAFE_INTEGER`).
const MAX_SAFE_INTEGER: f64 = 9_007_199_254_740_991.0;

fn js_error(e: &RelayError) -> JsError {
    JsError::new(&format!("{}: {e}", e.name()))
}

/// `nowMs` from JS: finite, non-negative and exact; fractions are dropped.
fn checked_now(now_ms: f64) -> Result<u64, RelayError> {
    if !now_ms.is_finite() || !(0.0..=MAX_SAFE_INTEGER).contains(&now_ms) {
        return Err(RelayError::Malformed(
            "nowMs must be a finite, non-negative safe integer",
        ));
    }
    // In range, so the truncating cast is exact for the integer part.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    Ok(now_ms.trunc() as u64)
}

// ---- entropy ----------------------------------------------------------------

#[cfg(test)]
thread_local! {
    /// Test hook: make every entropy draw fail.
    static FAIL_ENTROPY: Cell<bool> = const { Cell::new(false) };
}

/// The entropy source (`crypto.getRandomValues`). Tests swap in a failing
/// source here, so the failure goes through the same handling as a real one.
fn js_entropy(bytes: &mut [u8; 32]) -> Result<(), getrandom::Error> {
    #[cfg(test)]
    if FAIL_ENTROPY.with(Cell::get) {
        return Err(getrandom::Error::UNSUPPORTED);
    }
    getrandom::fill(bytes)
}

/// 32 fresh bytes from `crypto.getRandomValues` for one key pair or one
/// connection. A failure is an error, never a fallback.
fn fresh_seed() -> Result<Seed, RelayError> {
    let mut bytes = [0_u8; 32];
    if js_entropy(&mut bytes).is_err() {
        return Err(RelayError::Entropy);
    }
    let seed = Seed::from_entropy(bytes);
    zeroize::Zeroize::zeroize(&mut bytes);
    Ok(seed)
}

#[cfg(feature = "test-seeds")]
fn test_seed(bytes: &[u8]) -> Result<Seed, RelayError> {
    let array: [u8; 32] = bytes
        .try_into()
        .map_err(|_| RelayError::Malformed("seed must be 32 bytes"))?;
    Ok(Seed::from_entropy(array))
}

fn key_32(bytes: &[u8]) -> Result<[u8; 32], RelayError> {
    bytes
        .try_into()
        .map_err(|_| RelayError::Malformed("public key length"))
}

// ---- keys -----------------------------------------------------------------

/// A device's long-term X25519 key pair. `Debug` prints the device id only.
#[wasm_bindgen]
#[derive(Debug)]
pub struct RelayKeys {
    inner: StaticKeypair,
}

impl RelayKeys {
    fn try_generate() -> Result<Self, RelayError> {
        Ok(Self {
            inner: StaticKeypair::generate(fresh_seed()?),
        })
    }

    fn try_from_private(bytes: &[u8]) -> Result<Self, RelayError> {
        Ok(Self {
            inner: StaticKeypair::from_private(bytes)?,
        })
    }
}

#[wasm_bindgen]
impl RelayKeys {
    /// A new key pair from fresh entropy.
    pub fn generate() -> Result<RelayKeys, JsError> {
        Self::try_generate().map_err(|e| js_error(&e))
    }

    /// Rebuilds a key pair from the bytes `privateForStorage` returned.
    /// Zero-fill your copy after the call.
    #[wasm_bindgen(js_name = fromPrivate)]
    pub fn from_private(bytes: &[u8]) -> Result<RelayKeys, JsError> {
        Self::try_from_private(bytes).map_err(|e| js_error(&e))
    }

    /// The X25519 public key (32 bytes).
    #[wasm_bindgen(js_name = publicKey)]
    pub fn public_key(&self) -> Vec<u8> {
        self.inner.public().to_vec()
    }

    /// The device id, 32 lowercase hex characters.
    #[wasm_bindgen(js_name = deviceId)]
    pub fn device_id(&self) -> String {
        self.inner.device_id().to_hex()
    }

    /// The short id (8 hex characters).
    #[wasm_bindgen(js_name = shortId)]
    pub fn short_id(&self) -> String {
        self.inner.device_id().short()
    }

    /// The private key, only for sealing with a non-extractable WebCrypto key
    /// (§5.2): seal it at once and zero-fill the array. The only export that
    /// returns private key bytes. Never log it.
    #[wasm_bindgen(js_name = privateForStorage)]
    pub fn private_for_storage(&self) -> Vec<u8> {
        self.inner.private_for_storage().to_vec()
    }
}

#[cfg(feature = "test-seeds")]
#[wasm_bindgen]
impl RelayKeys {
    /// A key pair from a fixed seed (test bundle only).
    #[wasm_bindgen(js_name = generateForTests)]
    pub fn generate_for_tests(seed: &[u8]) -> Result<RelayKeys, JsError> {
        test_seed(seed)
            .map(|s| Self {
                inner: StaticKeypair::generate(s),
            })
            .map_err(|e| js_error(&e))
    }
}

// ---- steps ------------------------------------------------------------------

/// What a call returns: apply in order — send, deliver, events, close. Call
/// `free()` when done. `Debug` never prints bytes or events.
#[wasm_bindgen]
pub struct RelayStep {
    send: Vec<(bool, Vec<u8>)>,
    deliver: Vec<Vec<u8>>,
    events_json: String,
    close: Option<(u16, String)>,
}

impl std::fmt::Debug for RelayStep {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RelayStep")
            .field(
                "send",
                &self.send.iter().map(|(_, b)| b.len()).collect::<Vec<_>>(),
            )
            .field("deliver", &self.deliver.len())
            .field("events_json", &"[redacted]")
            .field("close", &self.close.as_ref().map(|c| c.0))
            .finish()
    }
}

impl From<Step> for RelayStep {
    fn from(step: Step) -> Self {
        // By reference: `RelayEvent` wipes a shown code on drop.
        let events_json = serde_json::to_string(&step.events).unwrap_or_else(|_| "[]".into());
        Self {
            events_json,
            close: step.close.as_ref().map(|c| (c.code, c.reason.clone())),
            send: step.send.into_iter().map(|o| (o.text, o.bytes)).collect(),
            deliver: step.deliver,
        }
    }
}

impl RelayStep {
    /// What every call on a dead connection returns: close 4400, nothing else.
    fn dead() -> Self {
        Self {
            send: Vec::new(),
            deliver: Vec::new(),
            events_json: "[]".into(),
            close: Some((DEAD_CLOSE, "connection failed".into())),
        }
    }
}

#[wasm_bindgen]
impl RelayStep {
    /// How many messages to send.
    #[wasm_bindgen(js_name = sendCount)]
    pub fn send_count(&self) -> usize {
        self.send.len()
    }

    /// Whether message `i` is a text message (false out of range).
    #[wasm_bindgen(js_name = sendIsText)]
    pub fn send_is_text(&self, i: usize) -> bool {
        self.send.get(i).is_some_and(|(text, _)| *text)
    }

    /// The bytes of message `i` (empty out of range).
    #[wasm_bindgen(js_name = sendBytes)]
    pub fn send_bytes(&self, i: usize) -> Vec<u8> {
        self.send.get(i).map(|(_, b)| b.clone()).unwrap_or_default()
    }

    /// How many application messages were delivered.
    #[wasm_bindgen(js_name = deliverCount)]
    pub fn deliver_count(&self) -> usize {
        self.deliver.len()
    }

    /// Delivered message `i` (empty out of range).
    pub fn deliver(&self, i: usize) -> Vec<u8> {
        self.deliver.get(i).cloned().unwrap_or_default()
    }

    /// A JSON array of `RelayEvent`.
    #[wasm_bindgen(js_name = eventsJson)]
    pub fn events_json(&self) -> String {
        self.events_json.clone()
    }

    /// Close the WebSocket with this code after sending, if any.
    #[wasm_bindgen(js_name = closeCode)]
    pub fn close_code(&self) -> Option<u16> {
        self.close.as_ref().map(|c| c.0)
    }

    /// The close reason, if any.
    #[wasm_bindgen(js_name = closeReason)]
    pub fn close_reason(&self) -> Option<String> {
        self.close.as_ref().map(|c| c.1.clone())
    }
}

// ---- the remote connection --------------------------------------------------

/// The remote's side of one WebSocket to a hub. Call `free()` when the
/// WebSocket ends. `Debug` prints the phase only.
#[wasm_bindgen]
pub struct RelayRemoteConnection {
    /// The core state; `None` once a call trapped or failed fatally.
    slot: RefCell<Option<RemoteConnection>>,
    first: Cell<Option<Vec<u8>>>,
    /// Test hook: panic inside the next call, with the state taken.
    #[cfg(test)]
    panic_next: Cell<bool>,
}

impl std::fmt::Debug for RelayRemoteConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self.slot.try_borrow() {
            Ok(slot) => match slot.as_ref() {
                Some(conn) => conn.fmt(f),
                None => f.write_str("RelayRemoteConnection(dead)"),
            },
            Err(_) => f.write_str("RelayRemoteConnection(busy)"),
        }
    }
}

impl RelayRemoteConnection {
    fn wrap((conn, first): (RemoteConnection, Step)) -> Self {
        let hello = first.send.into_iter().next().map(|o| o.bytes);
        Self {
            slot: RefCell::new(Some(conn)),
            first: Cell::new(hello),
            #[cfg(test)]
            panic_next: Cell::new(false),
        }
    }

    fn try_resume(
        keys: &RelayKeys,
        hub_public_key: &[u8],
        now_ms: f64,
    ) -> Result<Self, RelayError> {
        let hub = key_32(hub_public_key)?;
        let now = checked_now(now_ms)?;
        Ok(Self::wrap(RemoteConnection::resume(
            &keys.inner,
            hub,
            fresh_seed()?,
            now,
        )))
    }

    fn try_pair(keys: &RelayKeys, my_name: &str, now_ms: f64) -> Result<Self, RelayError> {
        let now = checked_now(now_ms)?;
        Ok(Self::wrap(RemoteConnection::pair(
            &keys.inner,
            my_name,
            fresh_seed()?,
            now,
        )))
    }

    /// Takes the state out for one call and puts it back after. A call that
    /// traps (or an invalid `nowMs`) leaves the slot empty: the connection is
    /// dead and every later call returns the closing Step.
    fn with<R>(
        &self,
        now_ms: f64,
        f: impl FnOnce(&mut RemoteConnection, u64) -> R,
    ) -> Result<R, RelayError> {
        let taken = self.slot.try_borrow_mut().ok().and_then(|mut s| s.take());
        let Some(mut conn) = taken else {
            return Err(RelayError::Poisoned);
        };
        let Ok(now) = checked_now(now_ms) else {
            return Err(RelayError::Poisoned);
        };
        #[cfg(test)]
        if self.panic_next.replace(false) {
            panic!("injected panic inside a relay call");
        }
        let out = f(&mut conn, now);
        if let Ok(mut slot) = self.slot.try_borrow_mut() {
            *slot = Some(conn);
        }
        Ok(out)
    }

    fn step(&self, now_ms: f64, f: impl FnOnce(&mut RemoteConnection, u64) -> Step) -> RelayStep {
        self.with(now_ms, f)
            .map_or_else(|_| RelayStep::dead(), RelayStep::from)
    }

    fn try_send(&self, message: &[u8], now_ms: f64) -> Result<RelayStep, RelayError> {
        self.with(now_ms, |c, now| c.send(message, now))?
            .map(RelayStep::from)
    }

    fn read<R>(&self, f: impl FnOnce(&RemoteConnection) -> Option<R>) -> Option<R> {
        self.slot.try_borrow().ok()?.as_ref().and_then(f)
    }
}

#[wasm_bindgen]
impl RelayRemoteConnection {
    /// A resume to the pinned hub (fresh entropy). Call `start()` for the ClientHello.
    pub fn resume(
        keys: &RelayKeys,
        hub_public_key: &[u8],
        now_ms: f64,
    ) -> Result<RelayRemoteConnection, JsError> {
        Self::try_resume(keys, hub_public_key, now_ms).map_err(|e| js_error(&e))
    }

    /// A pairing attempt (fresh entropy). Call `start()` for the ClientHello.
    pub fn pair(
        keys: &RelayKeys,
        my_name: &str,
        now_ms: f64,
    ) -> Result<RelayRemoteConnection, JsError> {
        Self::try_pair(keys, my_name, now_ms).map_err(|e| js_error(&e))
    }

    /// The ClientHello Step, taken once (empty afterwards).
    pub fn start(&self) -> RelayStep {
        let mut step = Step::default();
        if let Some(hello) = self.first.take() {
            step.send.push(de1_relay::Outgoing {
                text: true,
                bytes: hello,
            });
        }
        step.into()
    }

    /// The raw bytes of a WebSocket text message.
    #[wasm_bindgen(js_name = onText)]
    pub fn on_text(&self, raw: &[u8], now_ms: f64) -> RelayStep {
        self.step(now_ms, |c, now| c.on_ws(WsIn::Text(raw), now))
    }

    /// A WebSocket binary message.
    #[wasm_bindgen(js_name = onBinary)]
    pub fn on_binary(&self, bytes: &[u8], now_ms: f64) -> RelayStep {
        self.step(now_ms, |c, now| c.on_ws(WsIn::Binary(bytes), now))
    }

    /// Encrypts one application message into its records.
    pub fn send(&self, message: &[u8], now_ms: f64) -> Result<RelayStep, JsError> {
        self.try_send(message, now_ms).map_err(|e| js_error(&e))
    }

    /// Closes (an authenticated `CLOSE(NORMAL)` once established).
    pub fn close(&self, now_ms: f64) -> RelayStep {
        self.step(now_ms, |c, now| c.close(CloseReason::Normal, now))
    }

    /// Drives deadlines and heartbeats.
    pub fn tick(&self, now_ms: f64) -> RelayStep {
        self.step(now_ms, |c, now| c.tick(now))
    }

    /// When to call `tick` next.
    #[wasm_bindgen(js_name = nextDeadlineMs)]
    #[allow(clippy::cast_precision_loss)] // deadlines are epoch-ms, far below 2^53
    pub fn next_deadline_ms(&self) -> Option<f64> {
        self.read(RemoteConnection::next_deadline_ms)
            .map(|d| d as f64)
    }

    /// The code to show while the hub waits for it (never log it).
    pub fn code(&self) -> Option<String> {
        self.read(RemoteConnection::code)
    }

    /// The hub's static public key once trusted (`Paired`, `Established`);
    /// `None` before, after a failure and after a normal close, so pin the
    /// key read at `Paired`.
    #[wasm_bindgen(js_name = peerPublicKey)]
    pub fn peer_public_key(&self) -> Option<Vec<u8>> {
        self.read(|c| c.peer_public_key().map(|k| k.to_vec()))
    }

    /// The session's channel binding, in `Established` only.
    #[wasm_bindgen(js_name = channelBinding)]
    pub fn channel_binding(&self) -> Option<Vec<u8>> {
        self.read(|c| c.channel_binding().map(|h| h.to_vec()))
    }
}

#[cfg(feature = "test-seeds")]
#[wasm_bindgen]
impl RelayRemoteConnection {
    /// A resume from a fixed seed (test bundle only).
    #[wasm_bindgen(js_name = resumeForTests)]
    pub fn resume_for_tests(
        keys: &RelayKeys,
        hub_public_key: &[u8],
        now_ms: f64,
        seed: &[u8],
    ) -> Result<RelayRemoteConnection, JsError> {
        (|| {
            let hub = key_32(hub_public_key)?;
            let now = checked_now(now_ms)?;
            Ok(Self::wrap(RemoteConnection::resume(
                &keys.inner,
                hub,
                test_seed(seed)?,
                now,
            )))
        })()
        .map_err(|e: RelayError| js_error(&e))
    }

    /// A pairing attempt from a fixed seed (test bundle only).
    #[wasm_bindgen(js_name = pairForTests)]
    pub fn pair_for_tests(
        keys: &RelayKeys,
        my_name: &str,
        now_ms: f64,
        seed: &[u8],
    ) -> Result<RelayRemoteConnection, JsError> {
        (|| {
            let now = checked_now(now_ms)?;
            Ok(Self::wrap(RemoteConnection::pair(
                &keys.inner,
                my_name,
                test_seed(seed)?,
                now,
            )))
        })()
        .map_err(|e: RelayError| js_error(&e))
    }
}

#[cfg(test)]
mod tests {
    use std::panic::{AssertUnwindSafe, catch_unwind};

    use super::*;

    const T0: f64 = 1_000_000.0;

    fn keys(byte: u8) -> RelayKeys {
        RelayKeys::try_from_private(&[byte; 32]).unwrap()
    }

    /// AC-52: with the entropy source failing, every entropy-drawing
    /// constructor returns `Entropy` and builds nothing.
    #[test]
    fn entropy_failure_is_an_error() {
        let k = keys(1);
        let hub = keys(2).public_key();
        FAIL_ENTROPY.with(|c| c.set(true));
        assert_eq!(RelayKeys::try_generate().unwrap_err(), RelayError::Entropy);
        assert_eq!(
            RelayRemoteConnection::try_resume(&k, &hub, T0).unwrap_err(),
            RelayError::Entropy
        );
        assert_eq!(
            RelayRemoteConnection::try_pair(&k, "Phone", T0).unwrap_err(),
            RelayError::Entropy
        );
        assert!(RelayKeys::try_from_private(&[3; 32]).is_ok());
        FAIL_ENTROPY.with(|c| c.set(false));
        assert!(RelayKeys::try_generate().is_ok());
        assert!(RelayRemoteConnection::try_pair(&k, "Phone", T0).is_ok());
    }

    /// Two production constructions draw different seeds.
    #[test]
    fn production_seeds_differ() {
        let (a, b) = (
            RelayKeys::try_generate().unwrap(),
            RelayKeys::try_generate().unwrap(),
        );
        assert_ne!(a.public_key(), b.public_key());
    }

    /// A call that panics leaves the connection dead for good: close 4400 and
    /// nothing else, `send` fails, reads return nothing.
    #[test]
    fn trapped_call_fails_closed() {
        let conn = RelayRemoteConnection::try_pair(&keys(4), "Phone", T0).unwrap();
        assert_eq!(conn.start().send_count(), 1);
        conn.panic_next.set(true);
        assert!(catch_unwind(AssertUnwindSafe(|| conn.tick(T0))).is_err());
        for step in [
            conn.tick(T0),
            conn.on_binary(&[0x10], T0),
            conn.on_text(b"{}", T0),
            conn.close(T0),
        ] {
            assert_eq!(step.close_code(), Some(DEAD_CLOSE));
            assert_eq!((step.send_count(), step.deliver_count()), (0, 0));
            assert_eq!(step.events_json(), "[]");
        }
        assert_eq!(conn.try_send(b"x", T0).unwrap_err(), RelayError::Poisoned);
        assert_eq!(conn.next_deadline_ms(), None);
        assert_eq!(conn.code(), None);
    }

    /// An invalid `nowMs` is refused at construction and kills a live connection.
    #[test]
    fn invalid_time_fails_closed() {
        for bad in [f64::NAN, f64::INFINITY, -1.0, 1e300] {
            assert!(RelayRemoteConnection::try_pair(&keys(5), "Phone", bad).is_err());
        }
        let conn = RelayRemoteConnection::try_pair(&keys(5), "Phone", T0).unwrap();
        assert_eq!(conn.tick(f64::NAN).close_code(), Some(DEAD_CLOSE));
        assert_eq!(conn.tick(T0).close_code(), Some(DEAD_CLOSE));
        assert_eq!(checked_now(1_234.9), Ok(1_234));
    }

    /// `Debug` never prints a private key, bytes or events.
    #[test]
    fn debug_redacts() {
        let k = keys(6);
        let private: String = k
            .private_for_storage()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        assert!(!format!("{k:?}").contains(&private));
        let step = RelayStep {
            send: vec![(true, b"hello".to_vec())],
            deliver: vec![b"frame".to_vec()],
            events_json: r#"[{"type":"ShowCode","content":{"code":"482913"}}]"#.into(),
            close: None,
        };
        let shown = format!("{step:?}");
        assert!(
            !shown.contains("482913") && !shown.contains("frame"),
            "{shown}"
        );
        let conn = RelayRemoteConnection::try_pair(&k, "Phone", T0).unwrap();
        assert!(!format!("{conn:?}").contains(&private));
    }

    /// No export returns private key bytes except `privateForStorage`.
    #[test]
    fn only_private_for_storage_returns_private_key_bytes() {
        let src = include_str!("relay.rs");
        let src = &src[..src.find("#[cfg(test)]\nmod tests").unwrap()];
        let mut byte_fns: Vec<&str> = src
            .lines()
            .filter(|l| l.trim_start().starts_with("pub fn ") && l.contains("->"))
            .filter(|l| {
                l.split("->")
                    .nth(1)
                    .is_some_and(|ret| ret.contains("Vec<u8>"))
            })
            .map(|l| {
                l.trim_start()
                    .trim_start_matches("pub fn ")
                    .split('(')
                    .next()
                    .unwrap()
            })
            .collect();
        byte_fns.sort_unstable();
        // Message bytes, public keys, the channel binding, and the one private export.
        assert_eq!(
            byte_fns,
            [
                "channel_binding",
                "deliver",
                "peer_public_key",
                "private_for_storage",
                "public_key",
                "send_bytes"
            ]
        );
    }
}
