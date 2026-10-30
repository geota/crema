//! The relay v4 secure channel for the Android shell (spec §3.5): UniFFI
//! objects around [`de1_relay`]'s sans-IO core.
//!
//! Kept in its own module, separate from the `CremaBridge` exports.
//!
//! - **Entropy.** Every constructor that draws entropy takes 32 fresh bytes from
//!   the OS (`getrandom`, the `getrandom(2)` syscall on Android) for its
//!   [`Seed`]. A failure is a [`RelayFfiError`] and nothing is built; no fixed or
//!   default seed exists. The deterministic `*_for_tests` constructors are only
//!   compiled with the `test-seeds` feature, which the APK's library never has.
//! - **Poisoning.** Each connection holds its core state in a `Mutex`. Unlike
//!   `CremaBridge::core()`, a poisoned lock is never recovered: a panic may have
//!   left a Noise state half-updated (a nonce consumed with no output), so the
//!   object fails closed for good: every later call returns a Step that closes
//!   with 4400 and carries nothing else, and the `Result` calls return
//!   `Poisoned`.
//! - **Keys.** No export returns private key bytes except
//!   [`RelayKeys::private_for_storage`], for the platform key store.
//! - **Events** cross as JSON (`events_json`, a JSON array of `RelayEvent`), so
//!   Kotlin decodes them with the typeshare-generated type. `Debug` of a
//!   [`RelayStep`] never prints bytes or events (a `ShowCode` carries the code).

use std::sync::{Arc, Mutex, MutexGuard, RwLock};

use de1_relay::{
    CloseReason, DeviceId, GateStatus, HubConnection, PairingGate, PinSet, RelayError,
    RemoteConnection, Seed, StaticKeypair, Step, WsIn,
};
use zeroize::Zeroize;

/// The WebSocket close code a poisoned object closes with (protocol error).
const POISONED_CLOSE: u16 = 4400;

/// The error every fallible relay export throws: the core's `RelayError` name
/// as `kind` (`Malformed`, `CodeFormat`, `Timeout`, `Poisoned`, `Entropy`, …)
/// and a readable `detail` (not `message`, which Kotlin's `Exception` already
/// has). Neither ever carries key bytes or a code.
#[derive(Debug, uniffi::Error)]
pub enum RelayFfiError {
    /// A relay call failed.
    Failed {
        /// The `RelayError` variant name.
        kind: String,
        /// A human-readable description.
        detail: String,
    },
}

impl std::fmt::Display for RelayFfiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Failed { kind, detail } => write!(f, "{kind}: {detail}"),
        }
    }
}

impl std::error::Error for RelayFfiError {}

impl From<RelayError> for RelayFfiError {
    fn from(e: RelayError) -> Self {
        Self::Failed {
            kind: e.name().to_owned(),
            detail: e.to_string(),
        }
    }
}

fn poisoned_error() -> RelayFfiError {
    RelayError::Poisoned.into()
}

// ---- entropy ----------------------------------------------------------------

#[cfg(test)]
thread_local! {
    /// Test hook: make every OS entropy draw on this thread fail.
    static FAIL_ENTROPY: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// 32 fresh bytes from the OS for one key pair or one connection. A failure
/// is an error, never a fallback.
fn fresh_seed() -> Result<Seed, RelayFfiError> {
    #[cfg(test)]
    if FAIL_ENTROPY.with(std::cell::Cell::get) {
        return Err(RelayError::Entropy.into());
    }
    let mut bytes = [0_u8; 32];
    if getrandom::fill(&mut bytes).is_err() {
        return Err(RelayError::Entropy.into());
    }
    let seed = Seed::from_entropy(bytes);
    bytes.zeroize();
    Ok(seed)
}

/// A test seed from the shell (feature `test-seeds` only): exactly 32 bytes.
#[cfg(feature = "test-seeds")]
fn test_seed(mut bytes: Vec<u8>) -> Result<Seed, RelayFfiError> {
    let array: Result<[u8; 32], _> = bytes.as_slice().try_into();
    bytes.zeroize();
    array
        .map(Seed::from_entropy)
        .map_err(|_| RelayError::Malformed("seed must be 32 bytes").into())
}

fn key_32(bytes: &[u8], what: &'static str) -> Result<[u8; 32], RelayFfiError> {
    bytes
        .try_into()
        .map_err(|_| RelayError::Malformed(what).into())
}

// ---- keys -----------------------------------------------------------------

/// A device's long-term X25519 key pair (§2.3). `Debug` prints the device id only.
#[derive(Debug, uniffi::Object)]
pub struct RelayKeys {
    inner: StaticKeypair,
}

#[uniffi::export]
impl RelayKeys {
    /// A new key pair from fresh OS entropy.
    #[uniffi::constructor]
    pub fn generate() -> Result<Arc<Self>, RelayFfiError> {
        Ok(Arc::new(Self {
            inner: StaticKeypair::generate(fresh_seed()?),
        }))
    }

    /// Rebuilds a key pair from the bytes `private_for_storage` returned. The
    /// input buffer is wiped.
    #[uniffi::constructor]
    pub fn from_private(mut bytes: Vec<u8>) -> Result<Arc<Self>, RelayFfiError> {
        let keys = StaticKeypair::from_private(&bytes);
        bytes.zeroize();
        Ok(Arc::new(Self { inner: keys? }))
    }

    /// The X25519 public key (32 bytes).
    pub fn public_key(&self) -> Vec<u8> {
        self.inner.public().to_vec()
    }

    /// The device id, 32 lowercase hex characters.
    pub fn device_id(&self) -> String {
        self.inner.device_id().to_hex()
    }

    /// The short id (8 hex characters) for UI and the NSD `kid`.
    pub fn short_id(&self) -> String {
        self.inner.device_id().short()
    }

    /// The private key, only for the platform key store (§5.1): seal it at
    /// once and zero-fill the array. The only export that returns private key
    /// bytes. Never log it.
    pub fn private_for_storage(&self) -> Vec<u8> {
        self.inner.private_for_storage().to_vec()
    }
}

#[cfg(feature = "test-seeds")]
#[uniffi::export]
impl RelayKeys {
    /// A key pair from a fixed seed (feature `test-seeds`; never in the APK).
    #[uniffi::constructor]
    pub fn generate_for_tests(seed: Vec<u8>) -> Result<Arc<Self>, RelayFfiError> {
        Ok(Arc::new(Self {
            inner: StaticKeypair::generate(test_seed(seed)?),
        }))
    }
}

/// The device id of a static public key (32 lowercase hex characters).
#[uniffi::export]
pub fn relay_device_id(public_key: Vec<u8>) -> Result<String, RelayFfiError> {
    Ok(DeviceId::of(&key_32(&public_key, "public key length")?).to_hex())
}

// ---- pin set and gate -------------------------------------------------------

/// The hub's live set of pinned remote keys, one per hub process, shared by
/// every `RelayHubConnection` (§2.6, §3.2).
#[derive(Debug, uniffi::Object)]
pub struct RelayPinSet {
    inner: Arc<RwLock<PinSet>>,
}

#[uniffi::export]
impl RelayPinSet {
    /// A pin set holding `keys`.
    #[uniffi::constructor]
    pub fn new(keys: Vec<Vec<u8>>) -> Result<Arc<Self>, RelayFfiError> {
        let keys = keys
            .iter()
            .map(|k| key_32(k, "public key length"))
            .collect::<Result<Vec<_>, _>>()?;
        Ok(Arc::new(Self {
            inner: Arc::new(RwLock::new(PinSet::new(keys)?)),
        }))
    }

    /// Pins a key. Returns `true` if it was newly pinned and `false` if it was
    /// already pinned (a re-pair): after a failed `confirm_paired` the shell
    /// removes the key only if this returned `true`, and otherwise restores the
    /// old pin record (PROTOCOL.md §4 step 9).
    pub fn insert(&self, key: Vec<u8>) -> Result<bool, RelayFfiError> {
        let key = key_32(&key, "public key length")?;
        let mut pins = self.inner.write().map_err(|_| poisoned_error())?;
        Ok(pins.insert(key)?)
    }

    /// Unpins a key (Forget: call before the file write).
    pub fn remove(&self, key: Vec<u8>) -> Result<(), RelayFfiError> {
        let key = key_32(&key, "public key length")?;
        let mut pins = self.inner.write().map_err(|_| poisoned_error())?;
        pins.remove(&key);
        Ok(())
    }

    /// Whether a key is pinned.
    pub fn contains(&self, key: Vec<u8>) -> Result<bool, RelayFfiError> {
        let key = key_32(&key, "public key length")?;
        let pins = self.inner.read().map_err(|_| poisoned_error())?;
        Ok(pins.contains(&key))
    }
}

/// The hub's pairing window, failure count and lockout, one per hub process
/// (§2.5).
#[derive(Debug, uniffi::Object)]
pub struct RelayPairingGate {
    inner: Arc<Mutex<PairingGate>>,
}

fn gate_json(status: &GateStatus) -> String {
    serde_json::to_string(status).unwrap_or_default()
}

/// What a poisoned gate reports: closed, nothing pending.
fn closed_gate_json() -> String {
    gate_json(&GateStatus {
        open: false,
        secs_left: 0,
        failures: 0,
        locked_secs_left: 0,
        pending: false,
    })
}

#[uniffi::export]
impl RelayPairingGate {
    /// A closed gate.
    #[uniffi::constructor]
    #[allow(clippy::new_without_default)]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Arc::new(Mutex::new(PairingGate::new())),
        })
    }

    /// Opens the window; returns the `GateStatus` JSON. `LockedOut` while locked.
    pub fn open(&self, now_ms: u64) -> Result<String, RelayFfiError> {
        let mut gate = self.inner.lock().map_err(|_| poisoned_error())?;
        Ok(gate_json(&gate.open(now_ms)?))
    }

    /// Closes the window (the slot holder aborts on its next call: tick it).
    /// Named `close_window`, not `close`, which Kotlin's `AutoCloseable` (the
    /// object's `destroy()`) already uses.
    pub fn close_window(&self) {
        if let Ok(mut gate) = self.inner.lock() {
            gate.close();
        }
    }

    /// The `GateStatus` JSON (a poisoned gate reports itself closed).
    pub fn status(&self, now_ms: u64) -> String {
        match self.inner.lock() {
            Ok(gate) => gate_json(&gate.status(now_ms)),
            Err(_) => closed_gate_json(),
        }
    }
}

// ---- steps ------------------------------------------------------------------

/// One outgoing WebSocket message.
#[derive(uniffi::Record)]
pub struct RelayOutgoing {
    /// Send as a text message.
    pub text: bool,
    /// The message bytes.
    pub bytes: Vec<u8>,
}

/// What a call returns: apply in order — send (contiguously, under the
/// connection's send lock), deliver, events, close.
#[derive(uniffi::Record)]
pub struct RelayStep {
    /// Messages to send, in order.
    pub send: Vec<RelayOutgoing>,
    /// Complete application messages (Frame JSON), in order.
    pub deliver: Vec<Vec<u8>>,
    /// A JSON array of `RelayEvent`.
    pub events_json: String,
    /// Close the WebSocket with this code after sending.
    pub close_code: Option<u16>,
    /// The close reason.
    pub close_reason: Option<String>,
}

impl std::fmt::Debug for RelayStep {
    /// Sizes and the close only: never message bytes or events (a `ShowCode`
    /// event carries the pairing code).
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("RelayStep")
            .field(
                "send",
                &self.send.iter().map(|m| m.bytes.len()).collect::<Vec<_>>(),
            )
            .field("deliver", &self.deliver.len())
            .field("events_json", &"[redacted]")
            .field("close_code", &self.close_code)
            .finish_non_exhaustive()
    }
}

impl From<Step> for RelayStep {
    fn from(step: Step) -> Self {
        // Events are serialized by reference: `RelayEvent` wipes a shown code
        // on drop, so its fields are never moved out.
        let events_json = serde_json::to_string(&step.events).unwrap_or_else(|_| "[]".into());
        Self {
            events_json,
            close_code: step.close.as_ref().map(|c| c.code),
            close_reason: step.close.as_ref().map(|c| c.reason.clone()),
            send: step
                .send
                .into_iter()
                .map(|o| RelayOutgoing {
                    text: o.text,
                    bytes: o.bytes,
                })
                .collect(),
            deliver: step.deliver,
        }
    }
}

/// What every call on a poisoned connection returns: close 4400, nothing else.
fn poisoned_step() -> RelayStep {
    RelayStep {
        send: Vec::new(),
        deliver: Vec::new(),
        events_json: "[]".into(),
        close_code: Some(POISONED_CLOSE),
        close_reason: Some("poisoned".into()),
    }
}

/// Why an established session is closed.
#[derive(Debug, Clone, Copy, uniffi::Enum)]
pub enum RelayCloseReason {
    /// The hub forgot this remote.
    Revoked,
    /// The remote isn't paired with this hub.
    NotPaired,
    /// The hub user turned Hub mode off.
    HubStopped,
    /// Any other orderly close.
    Normal,
}

impl From<RelayCloseReason> for CloseReason {
    fn from(r: RelayCloseReason) -> Self {
        match r {
            RelayCloseReason::Revoked => Self::Revoked,
            RelayCloseReason::NotPaired => Self::NotPaired,
            RelayCloseReason::HubStopped => Self::HubStopped,
            RelayCloseReason::Normal => Self::Normal,
        }
    }
}

// ---- connections ------------------------------------------------------------

/// A connection's core state behind a lock that is never recovered once
/// poisoned.
struct Guarded<T> {
    inner: Mutex<T>,
    /// Test hook: panic inside the next call, with the lock held.
    #[cfg(test)]
    panic_next: std::sync::atomic::AtomicBool,
}

impl<T> Guarded<T> {
    fn new(inner: T) -> Self {
        Self {
            inner: Mutex::new(inner),
            #[cfg(test)]
            panic_next: std::sync::atomic::AtomicBool::new(false),
        }
    }

    /// The state, or `None` once a panic poisoned the lock.
    fn lock(&self) -> Option<MutexGuard<'_, T>> {
        let guard = self.inner.lock().ok()?;
        #[cfg(test)]
        if self
            .panic_next
            .swap(false, std::sync::atomic::Ordering::SeqCst)
        {
            panic!("injected panic inside a relay call");
        }
        Some(guard)
    }

    /// Runs a Step-returning call, failing closed on a poisoned lock.
    fn step(&self, f: impl FnOnce(&mut T) -> Step) -> RelayStep {
        match self.lock() {
            Some(mut state) => f(&mut state).into(),
            None => poisoned_step(),
        }
    }

    /// Runs a `Result` call, failing closed (`Poisoned`) on a poisoned lock.
    fn result(
        &self,
        f: impl FnOnce(&mut T) -> Result<Step, RelayError>,
    ) -> Result<RelayStep, RelayFfiError> {
        let mut state = self.lock().ok_or_else(poisoned_error)?;
        Ok(f(&mut state)?.into())
    }

    /// Reads a value; a poisoned lock reads as `None`.
    fn read<R>(&self, f: impl FnOnce(&T) -> Option<R>) -> Option<R> {
        self.lock().and_then(|state| f(&state))
    }
}

impl<T: std::fmt::Debug> std::fmt::Debug for Guarded<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self.inner.try_lock() {
            Ok(state) => state.fmt(f),
            Err(_) => f.write_str("[locked or poisoned]"),
        }
    }
}

/// The hub's side of one accepted WebSocket. `Debug` prints the phase only.
#[derive(Debug, uniffi::Object)]
pub struct RelayHubConnection {
    conn: Guarded<HubConnection>,
}

impl RelayHubConnection {
    fn build(
        keys: &RelayKeys,
        gate: &RelayPairingGate,
        pins: &RelayPinSet,
        hub_name: &str,
        seed: Seed,
        now_ms: u64,
    ) -> Arc<Self> {
        Arc::new(Self {
            conn: Guarded::new(HubConnection::new(
                &keys.inner,
                Arc::clone(&gate.inner),
                Arc::clone(&pins.inner),
                hub_name,
                seed,
                now_ms,
            )),
        })
    }
}

#[uniffi::export]
impl RelayHubConnection {
    /// A connection for a freshly accepted WebSocket, seeded from the OS.
    #[uniffi::constructor]
    pub fn new(
        keys: Arc<RelayKeys>,
        gate: Arc<RelayPairingGate>,
        pins: Arc<RelayPinSet>,
        hub_name: String,
        now_ms: u64,
    ) -> Result<Arc<Self>, RelayFfiError> {
        Ok(Self::build(
            &keys,
            &gate,
            &pins,
            &hub_name,
            fresh_seed()?,
            now_ms,
        ))
    }

    /// The raw bytes of a WebSocket text frame.
    pub fn on_text(&self, raw: Vec<u8>, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.on_ws(WsIn::Text(&raw), now_ms))
    }

    /// A WebSocket binary message.
    pub fn on_binary(&self, bytes: Vec<u8>, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.on_ws(WsIn::Binary(&bytes), now_ms))
    }

    /// The hub user typed the code (the input string is wiped).
    pub fn enter_code(&self, mut code: String, now_ms: u64) -> Result<RelayStep, RelayFfiError> {
        let result = self.conn.result(|c| c.enter_code(&code, now_ms));
        code.zeroize();
        result
    }

    /// Sends `PAIR_COMPLETE` after the pin is stored and in the live set. `Ok`
    /// only when it did; on any error the shell rolls the pin back
    /// (PROTOCOL.md §4 step 9) and applies `tick()`'s Step.
    pub fn confirm_paired(&self, now_ms: u64) -> Result<RelayStep, RelayFfiError> {
        self.conn.result(|c| c.confirm_paired(now_ms))
    }

    /// The pin couldn't be stored: `ABORT(CANCELLED)`, no failure counted.
    pub fn reject_pairing(&self, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.reject_pairing(now_ms))
    }

    /// The hub user cancelled the pairing request.
    pub fn cancel_pairing(&self, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.cancel_pairing(now_ms))
    }

    /// Encrypts one application message into its records.
    pub fn send(&self, message: Vec<u8>, now_ms: u64) -> Result<RelayStep, RelayFfiError> {
        self.conn.result(|c| c.send(&message, now_ms))
    }

    /// Closes the session (an authenticated `CLOSE` once established).
    pub fn close(&self, reason: RelayCloseReason, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.close(reason.into(), now_ms))
    }

    /// Drives deadlines, the gate check and heartbeats.
    pub fn tick(&self, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.tick(now_ms))
    }

    /// When to call `tick` next.
    pub fn next_deadline_ms(&self) -> Option<u64> {
        self.conn.read(HubConnection::next_deadline_ms)
    }

    /// The remote's static public key once trusted (`AwaitCommit`, `Paired`,
    /// `Established`); `None` before, after a failure and after a normal close,
    /// so the shell keeps the key it read at `PairPending` or `Established`.
    pub fn peer_public_key(&self) -> Option<Vec<u8>> {
        self.conn.read(|c| c.peer_public_key().map(|k| k.to_vec()))
    }

    /// The session's channel binding, in `Established` only.
    pub fn channel_binding(&self) -> Option<Vec<u8>> {
        self.conn.read(|c| c.channel_binding().map(|h| h.to_vec()))
    }
}

#[cfg(feature = "test-seeds")]
#[uniffi::export]
impl RelayHubConnection {
    /// A connection from a fixed seed (feature `test-seeds`; never in the APK).
    #[uniffi::constructor]
    pub fn new_for_tests(
        keys: Arc<RelayKeys>,
        gate: Arc<RelayPairingGate>,
        pins: Arc<RelayPinSet>,
        hub_name: String,
        now_ms: u64,
        seed: Vec<u8>,
    ) -> Result<Arc<Self>, RelayFfiError> {
        Ok(Self::build(
            &keys,
            &gate,
            &pins,
            &hub_name,
            test_seed(seed)?,
            now_ms,
        ))
    }
}

/// The remote's side of one dialled WebSocket. `Debug` prints the phase only.
#[derive(Debug, uniffi::Object)]
pub struct RelayRemoteConnection {
    conn: Guarded<RemoteConnection>,
    first: Mutex<Option<RelayStep>>,
}

impl RelayRemoteConnection {
    fn wrap((conn, first): (RemoteConnection, Step)) -> Arc<Self> {
        Arc::new(Self {
            conn: Guarded::new(conn),
            first: Mutex::new(Some(first.into())),
        })
    }
}

#[uniffi::export]
impl RelayRemoteConnection {
    /// A resume to the pinned hub, seeded from the OS. Call `start()` for the
    /// ClientHello.
    #[uniffi::constructor]
    pub fn resume(
        keys: Arc<RelayKeys>,
        hub_public_key: Vec<u8>,
        now_ms: u64,
    ) -> Result<Arc<Self>, RelayFfiError> {
        let hub = key_32(&hub_public_key, "hub public key length")?;
        Ok(Self::wrap(RemoteConnection::resume(
            &keys.inner,
            hub,
            fresh_seed()?,
            now_ms,
        )))
    }

    /// A pairing attempt (one code), seeded from the OS. Call `start()` for the
    /// ClientHello.
    #[uniffi::constructor]
    pub fn pair(
        keys: Arc<RelayKeys>,
        my_name: String,
        now_ms: u64,
    ) -> Result<Arc<Self>, RelayFfiError> {
        Ok(Self::wrap(RemoteConnection::pair(
            &keys.inner,
            &my_name,
            fresh_seed()?,
            now_ms,
        )))
    }

    /// The ClientHello Step, taken once (empty afterwards).
    pub fn start(&self) -> RelayStep {
        match self.first.lock() {
            Ok(mut first) => first.take().unwrap_or_else(|| Step::default().into()),
            Err(_) => poisoned_step(),
        }
    }

    /// The code to show while the hub waits for it (never log it).
    pub fn code(&self) -> Option<String> {
        self.conn.read(RemoteConnection::code)
    }

    /// The raw bytes of a WebSocket text frame.
    pub fn on_text(&self, raw: Vec<u8>, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.on_ws(WsIn::Text(&raw), now_ms))
    }

    /// A WebSocket binary message.
    pub fn on_binary(&self, bytes: Vec<u8>, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.on_ws(WsIn::Binary(&bytes), now_ms))
    }

    /// Encrypts one application message into its records.
    pub fn send(&self, message: Vec<u8>, now_ms: u64) -> Result<RelayStep, RelayFfiError> {
        self.conn.result(|c| c.send(&message, now_ms))
    }

    /// Closes (an authenticated `CLOSE` once established, `ABORT(CANCELLED)`
    /// during a pairing).
    pub fn close(&self, reason: RelayCloseReason, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.close(reason.into(), now_ms))
    }

    /// Drives deadlines and heartbeats.
    pub fn tick(&self, now_ms: u64) -> RelayStep {
        self.conn.step(|c| c.tick(now_ms))
    }

    /// When to call `tick` next.
    pub fn next_deadline_ms(&self) -> Option<u64> {
        self.conn.read(RemoteConnection::next_deadline_ms)
    }

    /// The hub's static public key once trusted (`Paired`, `Established`);
    /// `None` before, after a failure and after a normal close, so the shell
    /// pins the key it read at `Paired`.
    pub fn peer_public_key(&self) -> Option<Vec<u8>> {
        self.conn.read(|c| c.peer_public_key().map(|k| k.to_vec()))
    }

    /// The session's channel binding, in `Established` only.
    pub fn channel_binding(&self) -> Option<Vec<u8>> {
        self.conn.read(|c| c.channel_binding().map(|h| h.to_vec()))
    }
}

#[cfg(feature = "test-seeds")]
#[uniffi::export]
impl RelayRemoteConnection {
    /// A resume from a fixed seed (feature `test-seeds`; never in the APK).
    #[uniffi::constructor]
    pub fn resume_for_tests(
        keys: Arc<RelayKeys>,
        hub_public_key: Vec<u8>,
        now_ms: u64,
        seed: Vec<u8>,
    ) -> Result<Arc<Self>, RelayFfiError> {
        let hub = key_32(&hub_public_key, "hub public key length")?;
        Ok(Self::wrap(RemoteConnection::resume(
            &keys.inner,
            hub,
            test_seed(seed)?,
            now_ms,
        )))
    }

    /// A pairing attempt from a fixed seed (feature `test-seeds`; never in the APK).
    #[uniffi::constructor]
    pub fn pair_for_tests(
        keys: Arc<RelayKeys>,
        my_name: String,
        now_ms: u64,
        seed: Vec<u8>,
    ) -> Result<Arc<Self>, RelayFfiError> {
        Ok(Self::wrap(RemoteConnection::pair(
            &keys.inner,
            &my_name,
            test_seed(seed)?,
            now_ms,
        )))
    }
}

#[cfg(test)]
mod tests {
    use std::panic::{AssertUnwindSafe, catch_unwind};

    use super::*;

    const T0: u64 = 1_000_000;

    fn kind(e: &RelayFfiError) -> &str {
        match e {
            RelayFfiError::Failed { kind, .. } => kind,
        }
    }

    fn without_entropy<R>(f: impl FnOnce() -> R) -> R {
        FAIL_ENTROPY.with(|c| c.set(true));
        let r = f();
        FAIL_ENTROPY.with(|c| c.set(false));
        r
    }

    fn keys(byte: u8) -> Arc<RelayKeys> {
        RelayKeys::from_private(vec![byte; 32]).unwrap()
    }

    /// AC-52: with the OS entropy source failing, every entropy-drawing
    /// constructor returns `Entropy` and builds nothing; it works again after.
    #[test]
    fn entropy_failure_is_an_error() {
        let (k, gate, pins) = (
            keys(1),
            RelayPairingGate::new(),
            RelayPinSet::new(vec![]).unwrap(),
        );
        let hub_key = keys(2).public_key();
        without_entropy(|| {
            assert_eq!(kind(&RelayKeys::generate().unwrap_err()), "Entropy");
            let hub =
                RelayHubConnection::new(k.clone(), gate.clone(), pins.clone(), "Hub".into(), T0);
            assert_eq!(kind(&hub.unwrap_err()), "Entropy");
            let resume = RelayRemoteConnection::resume(k.clone(), hub_key.clone(), T0);
            assert_eq!(kind(&resume.unwrap_err()), "Entropy");
            let pair = RelayRemoteConnection::pair(k.clone(), "Phone".into(), T0);
            assert_eq!(kind(&pair.unwrap_err()), "Entropy");
            // Constructors that draw no entropy are unaffected.
            assert!(RelayKeys::from_private(vec![3; 32]).is_ok());
        });
        assert!(RelayKeys::generate().is_ok());
        assert!(RelayHubConnection::new(k, gate, pins, "Hub".into(), T0).is_ok());
    }

    fn pake_a(k: &Arc<RelayKeys>, hub_keys: &Arc<RelayKeys>) -> Vec<u8> {
        let gate = RelayPairingGate::new();
        gate.open(T0).unwrap();
        let pins = RelayPinSet::new(vec![]).unwrap();
        let hub = RelayHubConnection::new(hub_keys.clone(), gate, pins, "Hub".into(), T0).unwrap();
        let remote = RelayRemoteConnection::pair(k.clone(), "Phone".into(), T0).unwrap();
        let hello = remote.start();
        let sh = hub.on_text(hello.send[0].bytes.clone(), T0);
        let step = remote.on_binary(sh.send[0].bytes.clone(), T0);
        step.send[0].bytes.clone()
    }

    fn ik_msg1(k: &Arc<RelayKeys>, hub_keys: &Arc<RelayKeys>) -> Vec<u8> {
        let pins = RelayPinSet::new(vec![k.public_key()]).unwrap();
        let hub = RelayHubConnection::new(
            hub_keys.clone(),
            RelayPairingGate::new(),
            pins,
            "Hub".into(),
            T0,
        )
        .unwrap();
        let remote = RelayRemoteConnection::resume(k.clone(), hub_keys.public_key(), T0).unwrap();
        let sh = hub.on_text(remote.start().send[0].bytes.clone(), T0);
        remote.on_binary(sh.send[0].bytes.clone(), T0).send[0]
            .bytes
            .clone()
    }

    /// AC-52: two production constructions draw different seeds: different
    /// keys, different `PAKE_A`s (and codes), different ephemerals.
    #[test]
    fn production_seeds_differ() {
        let (a, b) = (
            RelayKeys::generate().unwrap(),
            RelayKeys::generate().unwrap(),
        );
        assert_ne!(a.public_key(), b.public_key());
        let (k, hub_keys) = (keys(4), keys(5));
        let (p1, p2) = (pake_a(&k, &hub_keys), pake_a(&k, &hub_keys));
        assert_eq!((p1[0], p1.len()), (0x20, 34));
        assert_ne!(p1, p2);
        let (m1, m2) = (ik_msg1(&k, &hub_keys), ik_msg1(&k, &hub_keys));
        assert_eq!((m1[0], m1.len()), (0x30, 97));
        // IK message 1 starts with the remote's ephemeral public key.
        assert_ne!(m1[1..33], m2[1..33]);
    }

    /// AC-53: a panic inside a connection call poisons that object for good:
    /// every later call returns close 4400 with nothing else, and the `Result`
    /// calls return `Poisoned`.
    #[test]
    fn poisoned_connection_fails_closed() {
        let gate = RelayPairingGate::new();
        gate.open(T0).unwrap();
        let pins = RelayPinSet::new(vec![]).unwrap();
        let hub = RelayHubConnection::new(keys(6), gate.clone(), pins, "Hub".into(), T0).unwrap();
        let remote = RelayRemoteConnection::pair(keys(7), "Phone".into(), T0).unwrap();
        let hello = remote.start();
        let sh = hub.on_text(hello.send[0].bytes.clone(), T0);
        assert_eq!(sh.close_code, None);

        hub.conn
            .panic_next
            .store(true, std::sync::atomic::Ordering::SeqCst);
        assert!(catch_unwind(AssertUnwindSafe(|| hub.tick(T0))).is_err());
        let assert_dead = |s: RelayStep| {
            assert_eq!(s.close_code, Some(POISONED_CLOSE));
            assert!(s.send.is_empty() && s.deliver.is_empty());
            assert_eq!(s.events_json, "[]");
        };
        assert_dead(hub.on_binary(vec![0x20; 34], T0));
        assert_dead(hub.on_text(b"{}".to_vec(), T0));
        assert_dead(hub.tick(T0 + 1));
        assert_dead(hub.cancel_pairing(T0));
        assert_dead(hub.reject_pairing(T0));
        assert_dead(hub.close(RelayCloseReason::Normal, T0));
        assert_eq!(
            kind(&hub.enter_code("123456".into(), T0).unwrap_err()),
            "Poisoned"
        );
        assert_eq!(kind(&hub.confirm_paired(T0).unwrap_err()), "Poisoned");
        assert_eq!(kind(&hub.send(b"x".to_vec(), T0).unwrap_err()), "Poisoned");
        assert_eq!(hub.next_deadline_ms(), None);
        assert_eq!(hub.peer_public_key(), None);
        // The poisoned connection still released nothing it shouldn't: the gate
        // lease ends with the window, and other objects keep working.
        assert!(gate.status(T0).contains("\"open\":true"));

        remote
            .conn
            .panic_next
            .store(true, std::sync::atomic::Ordering::SeqCst);
        assert!(
            catch_unwind(AssertUnwindSafe(
                || remote.on_binary(sh.send[0].bytes.clone(), T0)
            ))
            .is_err()
        );
        assert_dead(remote.on_binary(sh.send[0].bytes.clone(), T0));
        assert_dead(remote.tick(T0));
        assert_eq!(
            kind(&remote.send(b"x".to_vec(), T0).unwrap_err()),
            "Poisoned"
        );
        assert_eq!(remote.code(), None);
        assert_eq!(remote.channel_binding(), None);
    }

    /// A pairing and a resume through the bridge objects, with production seeds.
    #[test]
    fn pair_then_resume_through_the_bridge() {
        let (hub_keys, remote_keys) = (
            RelayKeys::generate().unwrap(),
            RelayKeys::generate().unwrap(),
        );
        let gate = RelayPairingGate::new();
        gate.open(T0).unwrap();
        let pins = RelayPinSet::new(vec![]).unwrap();
        let hub = RelayHubConnection::new(
            hub_keys.clone(),
            gate.clone(),
            pins.clone(),
            "Hub".into(),
            T0,
        )
        .unwrap();
        let remote = RelayRemoteConnection::pair(remote_keys.clone(), "Phone".into(), T0).unwrap();
        let sh = hub.on_text(remote.start().send[0].bytes.clone(), T0);
        let shown = remote.on_binary(sh.send[0].bytes.clone(), T0);
        assert!(shown.events_json.contains("ShowCode"));
        let code = remote.code().unwrap();
        hub.on_binary(shown.send[0].bytes.clone(), T0);
        let mut to_remote = hub.enter_code(code, T0).unwrap().send;
        let mut to_hub = Vec::new();
        while !to_remote.is_empty() || !to_hub.is_empty() {
            for m in std::mem::take(&mut to_remote) {
                to_hub.extend(remote.on_binary(m.bytes, T0).send);
            }
            for m in std::mem::take(&mut to_hub) {
                let s = hub.on_binary(m.bytes, T0);
                to_remote.extend(s.send);
            }
            if hub.peer_public_key().is_some() && to_remote.is_empty() && to_hub.is_empty() {
                let key = hub.peer_public_key().unwrap();
                assert_eq!(key, remote_keys.public_key());
                assert!(pins.insert(key).unwrap(), "newly pinned");
                let done = hub.confirm_paired(T0).unwrap();
                assert!(done.events_json.contains("Paired"));
                for m in done.send {
                    let s = remote.on_binary(m.bytes, T0);
                    assert!(s.events_json.contains("Paired"));
                    assert_eq!(s.close_code, Some(1000));
                }
            }
        }
        assert_eq!(remote.peer_public_key(), Some(hub_keys.public_key()));

        let hub = RelayHubConnection::new(hub_keys.clone(), gate, pins, "Hub".into(), T0).unwrap();
        let remote = RelayRemoteConnection::resume(remote_keys, hub_keys.public_key(), T0).unwrap();
        let sh = hub.on_text(remote.start().send[0].bytes.clone(), T0);
        let m1 = remote.on_binary(sh.send[0].bytes.clone(), T0);
        let m2 = hub.on_binary(m1.send[0].bytes.clone(), T0);
        let fin = remote.on_binary(m2.send[0].bytes.clone(), T0);
        let admitted = hub.on_binary(fin.send[0].bytes.clone(), T0);
        assert!(admitted.events_json.contains("Established"));
        assert_eq!(hub.channel_binding(), remote.channel_binding());
        assert!(hub.channel_binding().is_some());
        let big = vec![0x5a_u8; 300_000];
        let mut delivered = Vec::new();
        for m in hub.send(big.clone(), T0).unwrap().send {
            delivered.extend(remote.on_binary(m.bytes, T0).deliver);
        }
        assert_eq!(delivered, vec![big]);
    }

    /// `Debug` never prints a private key, message bytes or events.
    #[test]
    fn debug_redacts() {
        let k = keys(9);
        let private: String = k
            .private_for_storage()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect();
        let shown = format!("{k:?}");
        assert!(!shown.contains(&private) && shown.contains(&k.device_id()));
        let step = RelayStep {
            send: vec![RelayOutgoing {
                text: false,
                bytes: vec![0xab; 4],
            }],
            deliver: vec![b"secret frame".to_vec()],
            events_json: r#"[{"type":"ShowCode","content":{"code":"482913","expires_at_ms":1}}]"#
                .into(),
            close_code: None,
            close_reason: None,
        };
        let shown = format!("{step:?}");
        assert!(
            !shown.contains("482913") && !shown.contains("secret") && !shown.contains("ab, ab"),
            "{shown}"
        );
        let gate = RelayPairingGate::new();
        gate.open(T0).unwrap();
        let hub =
            RelayHubConnection::new(k, gate, RelayPinSet::new(vec![]).unwrap(), "Hub".into(), T0)
                .unwrap();
        assert!(!format!("{hub:?}").contains(&private));
    }

    /// No export returns private key bytes except `private_for_storage`: every
    /// `pub fn` in this module that returns bytes is on the allowed list.
    #[test]
    fn only_private_for_storage_returns_private_key_bytes() {
        let src = include_str!("relay.rs");
        let src = &src[..src.find("#[cfg(test)]\nmod tests").unwrap()];
        let mut byte_fns: Vec<&str> = src
            .lines()
            .filter(|l| {
                l.trim_start().starts_with("pub fn ") && l.contains("Vec<u8>") && l.contains("->")
            })
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
        byte_fns.dedup();
        // Public keys, the session's channel binding, and the one private export.
        assert_eq!(
            byte_fns,
            [
                "channel_binding",
                "peer_public_key",
                "private_for_storage",
                "public_key"
            ]
        );
    }

    #[test]
    fn pin_set_reports_repairs_and_refuses_bad_keys() {
        let pins = RelayPinSet::new(vec![]).unwrap();
        let k = keys(10).public_key();
        assert!(pins.insert(k.clone()).unwrap());
        assert!(!pins.insert(k.clone()).unwrap());
        assert!(pins.contains(k.clone()).unwrap());
        pins.remove(k.clone()).unwrap();
        assert!(!pins.contains(k).unwrap());
        assert_eq!(kind(&pins.insert(vec![0; 32]).unwrap_err()), "LowOrderKey");
        assert_eq!(kind(&pins.insert(vec![1; 31]).unwrap_err()), "Malformed");
        assert_eq!(kind(&relay_device_id(vec![1; 5]).unwrap_err()), "Malformed");
    }
}
