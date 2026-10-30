//! The hub side of one WebSocket (§3.3): the legacy check, the pairing
//! responder with its two-phase commit, the IK responder with the live pin
//! set and the admission rule, and the transport.

use std::sync::{Arc, Mutex, MutexGuard, RwLock};

use snow::HandshakeState;

use crate::{
    CloseReason, DeviceId, HELLO_DEADLINE_MS, PAIR_FINISH_MS, PAKE_A_DEADLINE_MS, PairingGate,
    Phase, PinSet, RESUME_DEADLINE_MS, RelayError, RelayEvent, Seed, StaticKeypair, Step,
    VERSION_MAX, WS_MAX_MESSAGE, WsIn,
    gate::{Grant, Ticket},
    hello::{ClientHello, HelloStatus, Intent, LEGACY_DENIAL, ParsedClientHello, ServerHello},
    is_low_order,
    noise::{self, IK_MESSAGE_LENS, NoiseEntropy, Pattern, XX_MESSAGE_LENS},
    pake::{self, Code, SIDE_A},
    seed::SeededRandom,
    step::{AbortReason, CloseCode},
    transport::{self, Inbound, Transport, TransportError},
    wire::{self, PAKE_LEN, inner, kind},
    ws_close,
};

/// Where the pairing handshake is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PairStage {
    /// Waiting for XX message 1.
    Msg1,
    /// Message 2 sent; waiting for message 3.
    Msg3,
}

/// The hub's state for one accepted WebSocket.
pub struct HubConnection {
    keys: StaticKeypair,
    hub_id: crate::DeviceId,
    hub_name: String,
    gate: Arc<Mutex<PairingGate>>,
    pins: Arc<RwLock<PinSet>>,
    spake_rng: SeededRandom,
    noise: NoiseEntropy,
    rekey_override: Option<u64>,

    phase: Phase,
    now_ms: u64,
    deadline: Option<u64>,
    intent: Option<Intent>,
    client_hello: Vec<u8>,
    server_hello: Vec<u8>,
    remote_id: Option<DeviceId>,
    remote_name: String,
    ticket: Option<Ticket>,
    window_until: u64,
    pake_a: Option<[u8; PAKE_LEN]>,
    spake_ran: bool,
    pair_stage: PairStage,
    handshake: Option<HandshakeState>,
    transport: Option<Transport>,
    peer_static: Option<[u8; 32]>,
    binding: Option<[u8; 32]>,
    #[cfg(feature = "test-seeds")]
    psk_for_tests: Option<[u8; 32]>,
}

impl core::fmt::Debug for HubConnection {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("HubConnection")
            .field("phase", &self.phase)
            .finish_non_exhaustive()
    }
}

impl Drop for HubConnection {
    /// A connection that goes away mid-pairing frees the slot.
    fn drop(&mut self) {
        if let Some(ticket) = self.ticket.take()
            && let Ok(mut gate) = self.gate.lock()
        {
            gate.release(ticket);
        }
    }
}

impl HubConnection {
    /// A connection for a freshly accepted WebSocket. `gate` and `pins` are
    /// the process-wide ones; `seed` is fresh entropy for this connection.
    pub fn new(
        keys: &StaticKeypair,
        gate: Arc<Mutex<PairingGate>>,
        pins: Arc<RwLock<PinSet>>,
        hub_name: &str,
        seed: Seed,
        now_ms: u64,
    ) -> Self {
        Self {
            hub_id: keys.device_id(),
            keys: keys.duplicate(),
            hub_name: crate::hello::wire_name(hub_name),
            gate,
            pins,
            spake_rng: seed.derive(crate::labels::STREAM_SPAKE).rng(),
            noise: NoiseEntropy::new(&seed),
            rekey_override: None,
            phase: Phase::AwaitHello,
            now_ms,
            deadline: Some(now_ms + HELLO_DEADLINE_MS),
            intent: None,
            client_hello: Vec::new(),
            server_hello: Vec::new(),
            remote_id: None,
            remote_name: String::new(),
            ticket: None,
            window_until: 0,
            pake_a: None,
            spake_ran: false,
            pair_stage: PairStage::Msg1,
            handshake: None,
            transport: None,
            peer_static: None,
            binding: None,
            #[cfg(feature = "test-seeds")]
            psk_for_tests: None,
        }
    }

    /// The current phase.
    pub fn phase(&self) -> Phase {
        self.phase
    }

    /// The peer's static key, after `PairPending` (pairing) or IK message 1 (resume).
    pub fn peer_public_key(&self) -> Option<[u8; 32]> {
        self.peer_static
    }

    /// The Noise handshake hash `h` of this session, after the handshake.
    pub fn channel_binding(&self) -> Option<[u8; 32]> {
        self.binding
    }

    /// When the shell should call `tick` next.
    pub fn next_deadline_ms(&self) -> Option<u64> {
        match self.phase {
            Phase::Established => self.transport.as_ref().map(Transport::next_deadline_ms),
            Phase::Paired | Phase::Closed | Phase::Failed => None,
            _ => self.deadline,
        }
    }

    fn is_terminal(&self) -> bool {
        matches!(self.phase, Phase::Paired | Phase::Closed | Phase::Failed)
    }

    fn pairing_phase(&self) -> bool {
        self.intent == Some(Intent::Pair)
            && matches!(
                self.phase,
                Phase::AwaitCode | Phase::Handshake | Phase::AwaitCommit
            )
    }

    fn lock_gate(&self) -> Result<MutexGuard<'_, PairingGate>, RelayError> {
        self.gate.lock().map_err(|_| RelayError::Poisoned)
    }

    /// Runs `f` under the gate lock and releases it before returning.
    fn with_gate<R>(&self, f: impl FnOnce(&mut PairingGate) -> R) -> Result<R, RelayError> {
        let mut gate = self.lock_gate()?;
        Ok(f(&mut gate))
    }

    fn pinned(&self, key: &[u8; 32]) -> Result<bool, RelayError> {
        // The read lock is held only for the lookup.
        let pins = self.pins.read().map_err(|_| RelayError::Poisoned)?;
        Ok(pins.contains(key))
    }

    // ---- terminal transitions ------------------------------------------------

    /// Ends the connection with an error, frees the slot uncounted.
    fn fail(&mut self, step: &mut Step, abort: Option<AbortReason>, code: u16, error: &RelayError) {
        if let Some(reason) = abort {
            step.push_binary(wire::encode_abort(reason, &[]));
        }
        let was_pairing = self.pairing_phase();
        self.release_slot();
        if was_pairing {
            self.push_pairing_failed(step, error.name());
        } else {
            step.events.push(RelayEvent::Failed {
                error: error.name().to_owned(),
            });
        }
        self.finish(step, Phase::Failed, code, error.name());
    }

    /// Ends a pairing without counting a failure (cancel, timeout, reject).
    fn end_pairing(&mut self, step: &mut Step, abort: AbortReason, code: u16, reason: &str) {
        step.push_binary(wire::encode_abort(abort, &[]));
        self.release_slot();
        self.push_pairing_failed(step, reason);
        self.finish(step, Phase::Closed, code, reason);
    }

    fn finish(&mut self, step: &mut Step, phase: Phase, code: u16, reason: &str) {
        self.phase = phase;
        self.deadline = None;
        self.handshake = None;
        if let Some(t) = self.transport.as_mut() {
            t.drop_partial();
        }
        step.close_with(code, reason);
    }

    fn push_pairing_failed(&self, step: &mut Step, reason: &str) {
        let (failures, locked) = self
            .lock_gate()
            .map(|g| g.failure_state(self.now_ms))
            .unwrap_or((0, false));
        step.events.push(RelayEvent::PairingFailed {
            reason: reason.to_owned(),
            failures,
            locked,
        });
    }

    fn release_slot(&mut self) {
        if let Some(ticket) = self.ticket.take()
            && let Ok(mut gate) = self.gate.lock()
        {
            gate.release(ticket);
        }
    }

    fn poisoned(&mut self) -> Step {
        let mut step = Step::default();
        self.ticket = None;
        step.events.push(RelayEvent::Failed {
            error: RelayError::Poisoned.name().to_owned(),
        });
        self.finish(&mut step, Phase::Failed, ws_close::PROTOCOL, "poisoned");
        step
    }

    /// Runs before every call: a terminal connection does nothing, a changed
    /// gate generation aborts the pending pairing, a passed deadline times out.
    fn preflight(&mut self, now_ms: u64) -> Option<Step> {
        self.now_ms = self.now_ms.max(now_ms);
        if self.is_terminal() {
            return Some(Step::default());
        }
        if let Some(ticket) = self.ticket {
            match self.with_gate(|g| g.holds(ticket)) {
                Err(_) => return Some(self.poisoned()),
                Ok(false) => {
                    let mut step = Step::default();
                    self.ticket = None;
                    self.end_pairing(
                        &mut step,
                        AbortReason::Cancelled,
                        ws_close::REFUSED,
                        "Cancelled",
                    );
                    return Some(step);
                }
                Ok(true) => {}
            }
        }
        if self.phase != Phase::Established && self.deadline.is_some_and(|d| now_ms >= d) {
            return Some(self.on_deadline());
        }
        None
    }

    fn on_deadline(&mut self) -> Step {
        let mut step = Step::default();
        match self.phase {
            Phase::AwaitCode | Phase::AwaitCommit => {
                self.end_pairing(
                    &mut step,
                    AbortReason::Timeout,
                    ws_close::TIMEOUT,
                    "Timeout",
                );
            }
            Phase::Handshake if self.intent == Some(Intent::Pair) => {
                // Code entered, no message 3 in time: not a failed code (§2.4 step 8).
                self.end_pairing(
                    &mut step,
                    AbortReason::Timeout,
                    ws_close::TIMEOUT,
                    "Timeout",
                );
            }
            Phase::AwaitClientFinished => {
                // The handshake finished on our side, so no plaintext ABORT; a
                // replayed IK message 1 ends here, never admitted.
                step.events.push(RelayEvent::Failed {
                    error: RelayError::Timeout.name().to_owned(),
                });
                self.finish(&mut step, Phase::Failed, ws_close::TIMEOUT, "Timeout");
            }
            _ => self.fail(
                &mut step,
                Some(AbortReason::Timeout),
                ws_close::TIMEOUT,
                &RelayError::Timeout,
            ),
        }
        step
    }

    fn malformed(&mut self, what: &'static str) -> Step {
        let mut step = Step::default();
        // After our side finished a handshake, refusals are records or nothing.
        let abort = (!matches!(self.phase, Phase::AwaitClientFinished | Phase::Established))
            .then_some(AbortReason::Malformed);
        self.fail(
            &mut step,
            abort,
            ws_close::PROTOCOL,
            &RelayError::Malformed(what),
        );
        step
    }

    // ---- input ----------------------------------------------------------------

    /// One WebSocket message from the remote.
    pub fn on_ws(&mut self, input: WsIn<'_>, now_ms: u64) -> Step {
        if let Some(step) = self.preflight(now_ms) {
            return step;
        }
        match (self.phase, input) {
            (Phase::AwaitHello, WsIn::Text(raw)) => self.on_client_hello(raw, now_ms),
            (Phase::AwaitHello, WsIn::Binary(_)) => self.malformed("binary before the hello"),
            (_, WsIn::Text(_)) => self.malformed("text after the hello"),
            (_, WsIn::Binary(msg)) if msg.len() > WS_MAX_MESSAGE => {
                self.malformed("oversize message")
            }
            (phase, WsIn::Binary(msg)) => {
                let Some((&k, body)) = msg.split_first() else {
                    return self.malformed("empty message");
                };
                match (phase, k) {
                    (_, kind::ABORT) => self.on_abort(body),
                    (Phase::AwaitCode, kind::PAKE_A) => self.on_pake_a(body),
                    (Phase::Handshake, kind::NOISE_HS) => match self.intent {
                        Some(Intent::Pair) => self.on_pair_handshake(body, now_ms),
                        _ => self.on_resume_msg1(body, now_ms),
                    },
                    (Phase::AwaitClientFinished | Phase::Established, kind::RECORD) => {
                        self.on_record(body, now_ms)
                    }
                    _ => self.malformed("unexpected message kind"),
                }
            }
        }
    }

    fn on_client_hello(&mut self, raw: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        let hello = match ClientHello::parse(raw) {
            Ok(ParsedClientHello::Legacy { version, name }) => {
                step.send
                    .push(crate::Outgoing::text(LEGACY_DENIAL.as_bytes().to_vec()));
                step.events.push(RelayEvent::LegacyPeer { version, name });
                self.finish(&mut step, Phase::Closed, ws_close::VERSION, "update Crema");
                return step;
            }
            Ok(ParsedClientHello::V4(hello)) => hello,
            Err(RelayError::Malformed(what)) => return self.malformed(what),
            Err(_) => return self.malformed("hello"),
        };
        let Some(version) = hello.common_version() else {
            step.push_binary(wire::encode_abort(AbortReason::Version, &[VERSION_MAX]));
            step.events.push(RelayEvent::Failed {
                error: RelayError::VersionUnsupported {
                    peer_max: hello.vmax,
                }
                .name()
                .to_owned(),
            });
            self.finish(&mut step, Phase::Closed, ws_close::VERSION, "version");
            return step;
        };
        self.client_hello = raw.to_vec();
        self.intent = Some(hello.intent);
        match hello.intent {
            Intent::Resume => self.start_resume(version, now_ms),
            Intent::Pair => self.start_pairing(&hello, version, now_ms),
        }
    }

    fn server_hello(&self, version: u8, status: HelloStatus, secs_left: u32) -> ServerHello {
        ServerHello {
            version,
            status,
            hub_id: self.hub_id,
            secs_left: u16::try_from(secs_left).unwrap_or(u16::MAX),
            name: self.hub_name.clone(),
            ext: Vec::new(),
        }
    }

    fn start_resume(&mut self, version: u8, now_ms: u64) -> Step {
        let mut step = Step::default();
        self.server_hello = self.server_hello(version, HelloStatus::Ready, 0).encode();
        let prologue = noise::resume_prologue(&self.client_hello, &self.server_hello);
        match noise::build(
            Pattern::Resume,
            false,
            self.keys.private_bytes(),
            None,
            &prologue,
            None,
            self.noise.resolver(),
        ) {
            Ok(hs) => self.handshake = Some(hs),
            Err(_) => {
                self.fail(
                    &mut step,
                    None,
                    ws_close::PROTOCOL,
                    &RelayError::HandshakeFailed,
                );
                return step;
            }
        }
        step.push_binary(self.server_hello.clone());
        self.phase = Phase::Handshake;
        self.deadline = Some(now_ms + RESUME_DEADLINE_MS);
        step
    }

    fn start_pairing(&mut self, hello: &ClientHello, version: u8, now_ms: u64) -> Step {
        let mut step = Step::default();
        let Ok(grant) = self.with_gate(|g| g.try_acquire(now_ms)) else {
            return self.poisoned();
        };
        let (status, secs) = match grant {
            Grant::Granted {
                ticket,
                window_until,
                secs_left,
            } => {
                self.ticket = Some(ticket);
                self.window_until = window_until;
                (HelloStatus::AwaitingCode, secs_left)
            }
            Grant::Closed => (HelloStatus::PairingClosed, 0),
            Grant::Busy => (HelloStatus::Busy, 0),
            Grant::LockedOut { secs_left } => (HelloStatus::LockedOut, secs_left),
        };
        self.server_hello = self.server_hello(version, status, secs).encode();
        step.push_binary(self.server_hello.clone());
        if status != HelloStatus::AwaitingCode {
            self.finish(&mut step, Phase::Closed, ws_close::REFUSED, status.name());
            return step;
        }
        let remote_id = hello.client_id.unwrap_or(DeviceId([0; 16]));
        self.remote_id = Some(remote_id);
        self.remote_name = crate::sanitize_name(&hello.client_name);
        step.events.push(RelayEvent::PairRequest {
            remote_id: remote_id.to_hex(),
            remote_name: self.remote_name.clone(),
        });
        self.phase = Phase::AwaitCode;
        self.deadline = Some((now_ms + PAKE_A_DEADLINE_MS).min(self.window_until));
        step
    }

    fn on_abort(&mut self, body: &[u8]) -> Step {
        let abort = match wire::decode_abort(body) {
            Ok(a) => a,
            Err(_) => return self.malformed("abort"),
        };
        let mut step = Step::default();
        let reason = abort.reason.name();
        let was_pairing = self.pairing_phase();
        self.release_slot();
        if was_pairing {
            self.push_pairing_failed(&mut step, reason);
        }
        self.finish(&mut step, Phase::Closed, ws_close::NORMAL, reason);
        step
    }

    fn on_pake_a(&mut self, body: &[u8]) -> Step {
        if self.pake_a.is_some() {
            return self.malformed("second PAKE_A");
        }
        if pake::check_element(body, SIDE_A).is_err() {
            // Not a failed code: no code was entered and no SPAKE2 ran (§2.4 step 5).
            return self.malformed("PAKE_A element");
        }
        let mut msg = [0_u8; PAKE_LEN];
        msg.copy_from_slice(body);
        self.pake_a = Some(msg);
        self.deadline = Some(self.window_until);
        Step::default()
    }

    /// The hub user typed the code. Runs SPAKE2 (once per connection) and sends `PAKE_B`.
    ///
    /// # Errors
    /// [`RelayError::CodeFormat`] unless `code` is exactly six ASCII digits (state
    /// unchanged, not an attempt); [`RelayError::InvalidState`] outside
    /// `AwaitCode`, before `PAKE_A` arrived, or for a second entry.
    pub fn enter_code(&mut self, code: &str, now_ms: u64) -> Result<Step, RelayError> {
        if self.spake_ran {
            return Err(RelayError::InvalidState);
        }
        let code = Code::parse(code)?;
        if let Some(step) = self.preflight(now_ms) {
            return Ok(step);
        }
        if self.phase != Phase::AwaitCode {
            return Err(RelayError::InvalidState);
        }
        let (Some(pake_a), Some(remote_id), Some(ticket)) =
            (self.pake_a, self.remote_id, self.ticket)
        else {
            return Err(RelayError::InvalidState);
        };
        self.spake_ran = true;
        let mut step = Step::default();
        let (state, pake_b) = pake::start_b(&code, &remote_id, &self.hub_id, &mut self.spake_rng);
        drop(code);
        let Ok(spake_key) = pake::finish(state, &pake_a, SIDE_A) else {
            return Ok(self.malformed("PAKE_A element"));
        };
        let prologue =
            noise::pair_prologue(&self.client_hello, &self.server_hello, &pake_a, &pake_b);
        let psk = pake::derive_psk(&spake_key, &prologue, &remote_id, &self.hub_id);
        drop(spake_key);
        #[cfg(feature = "test-seeds")]
        {
            self.psk_for_tests = Some(psk.expose_for_tests());
        }
        match noise::build(
            Pattern::Pairing,
            false,
            self.keys.private_bytes(),
            None,
            &prologue,
            Some(&psk),
            self.noise.resolver(),
        ) {
            Ok(hs) => self.handshake = Some(hs),
            Err(_) => {
                self.fail(
                    &mut step,
                    Some(AbortReason::HandshakeFailed),
                    ws_close::PROTOCOL,
                    &RelayError::HandshakeFailed,
                );
                return Ok(step);
            }
        }
        step.push_binary(wire::encode_pake(kind::PAKE_B, &pake_b));
        self.phase = Phase::Handshake;
        self.pair_stage = PairStage::Msg1;
        let deadline = now_ms + PAIR_FINISH_MS;
        self.deadline = Some(deadline);
        if self.with_gate(|g| g.extend(ticket, deadline)).is_err() {
            return Ok(self.poisoned());
        }
        Ok(step)
    }

    fn on_pair_handshake(&mut self, body: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        let Some(hs) = self.handshake.as_mut() else {
            return self.malformed("no handshake");
        };
        let mut payload = [0_u8; 64];
        match self.pair_stage {
            PairStage::Msg1 => {
                if body.len() != XX_MESSAGE_LENS[0] {
                    return self.malformed("XX message 1 length");
                }
                match hs.read_message(body, &mut payload) {
                    Ok(0) => {}
                    Ok(_) => return self.malformed("XX message 1 payload"),
                    Err(_) => {
                        self.fail(
                            &mut step,
                            Some(AbortReason::HandshakeFailed),
                            ws_close::AUTH,
                            &RelayError::HandshakeFailed,
                        );
                        return step;
                    }
                }
                let mut msg2 = [0_u8; 128];
                match hs.write_message(&[], &mut msg2) {
                    Ok(n) if n == XX_MESSAGE_LENS[1] => {
                        step.push_binary(wire::encode_handshake(&msg2[..n]))
                    }
                    // A low-order ephemeral fails `ee` here (§2.3).
                    _ => {
                        self.fail(
                            &mut step,
                            Some(AbortReason::HandshakeFailed),
                            ws_close::AUTH,
                            &RelayError::HandshakeFailed,
                        );
                        return step;
                    }
                }
                self.pair_stage = PairStage::Msg3;
                step
            }
            PairStage::Msg3 => {
                if body.len() != XX_MESSAGE_LENS[2] {
                    return self.malformed("XX message 3 length");
                }
                match hs.read_message(body, &mut payload) {
                    Ok(0) => self.on_pair_confirmed(now_ms),
                    Ok(_) => self.malformed("XX message 3 payload"),
                    Err(snow::Error::Decrypt) => self.on_wrong_code(now_ms),
                    // A low-order static fails `se` before the psk is even mixed:
                    // no code was tested, so nothing is counted.
                    Err(_) => {
                        self.fail(
                            &mut step,
                            Some(AbortReason::HandshakeFailed),
                            ws_close::AUTH,
                            &RelayError::HandshakeFailed,
                        );
                        step
                    }
                }
            }
        }
    }

    /// Message 3 failed to decrypt after a code entry: the only event that
    /// counts as a failed code (§2.4 step 8).
    fn on_wrong_code(&mut self, now_ms: u64) -> Step {
        let mut step = Step::default();
        step.push_binary(wire::encode_abort(AbortReason::HandshakeFailed, &[]));
        let ticket = self.ticket.take();
        let Ok(counted) = self.with_gate(|g| ticket.and_then(|t| g.record_failure(t, now_ms)))
        else {
            return self.poisoned();
        };
        let (failures, locked) = counted.unwrap_or((0, false));
        step.events.push(RelayEvent::PairingFailed {
            reason: RelayError::WrongCode.name().to_owned(),
            failures,
            locked,
        });
        self.finish(&mut step, Phase::Failed, ws_close::AUTH, "wrong code");
        step
    }

    fn on_pair_confirmed(&mut self, now_ms: u64) -> Step {
        let mut step = Step::default();
        let Some(hs) = self.handshake.take() else {
            return self.malformed("no handshake");
        };
        let peer = noise::remote_static(&hs);
        let Some(peer) = peer.filter(|p| !is_low_order(p)) else {
            self.fail(
                &mut step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::LowOrderKey,
            );
            return step;
        };
        if Some(DeviceId::of(&peer)) != self.remote_id {
            self.fail(
                &mut step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::PeerIdMismatch,
            );
            return step;
        }
        let binding = noise::handshake_hash(&hs);
        let Ok(state) = hs.into_transport_mode() else {
            self.fail(
                &mut step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::HandshakeFailed,
            );
            return step;
        };
        self.transport = Some(Transport::new(
            state,
            transport::rekey_interval(self.rekey_override),
            now_ms,
        ));
        self.binding = Some(binding);
        self.peer_static = Some(peer);
        // Two-phase commit: PAIR_COMPLETE waits for the shell to store the pin.
        self.phase = Phase::AwaitCommit;
        step.events.push(RelayEvent::PairPending {
            remote_id: DeviceId::of(&peer).to_hex(),
            remote_name: self.remote_name.clone(),
        });
        step
    }

    /// The shell stored the pin and inserted it into the live `PinSet`: send
    /// `PAIR_COMPLETE`, record the success and close.
    ///
    /// # Errors
    /// [`RelayError::InvalidState`] outside `AwaitCommit`, or when the peer's key
    /// isn't in the live pin set yet (the commit order of §2.4 step 9).
    pub fn confirm_paired(&mut self, now_ms: u64) -> Result<Step, RelayError> {
        if let Some(step) = self.preflight(now_ms) {
            return Ok(step);
        }
        let (Phase::AwaitCommit, Some(peer)) = (self.phase, self.peer_static) else {
            return Err(RelayError::InvalidState);
        };
        match self.pinned(&peer) {
            Ok(true) => {}
            Ok(false) => return Err(RelayError::InvalidState),
            Err(_) => return Ok(self.poisoned()),
        }
        let mut step = Step::default();
        let records = match self
            .transport
            .as_mut()
            .map(|t| t.encrypt(inner::PAIR_COMPLETE, &[], now_ms))
        {
            Some(Ok(records)) => records,
            _ => {
                self.fail(
                    &mut step,
                    Some(AbortReason::HandshakeFailed),
                    ws_close::PROTOCOL,
                    &RelayError::InvalidState,
                );
                return Ok(step);
            }
        };
        for r in records {
            step.push_binary(r);
        }
        let ticket = self.ticket.take();
        if self
            .with_gate(|g| {
                if let Some(t) = ticket {
                    g.record_success(t);
                }
            })
            .is_err()
        {
            return Ok(self.poisoned());
        }
        step.events.push(RelayEvent::Paired {
            peer_id: DeviceId::of(&peer).to_hex(),
            peer_name: self.remote_name.clone(),
        });
        self.finish(&mut step, Phase::Paired, ws_close::NORMAL, "paired");
        Ok(step)
    }

    /// The shell couldn't store the pin: `ABORT(CANCELLED)`, no failure counted,
    /// nothing pinned. Outside a pairing it does nothing.
    pub fn reject_pairing(&mut self, now_ms: u64) -> Step {
        self.cancel_pairing(now_ms)
    }

    /// The hub user cancelled the pairing request: `ABORT(CANCELLED)` and close,
    /// no failure counted. Outside a pairing it does nothing.
    pub fn cancel_pairing(&mut self, now_ms: u64) -> Step {
        if let Some(step) = self.preflight(now_ms) {
            return step;
        }
        let mut step = Step::default();
        if self.pairing_phase() {
            self.end_pairing(
                &mut step,
                AbortReason::Cancelled,
                ws_close::REFUSED,
                "Cancelled",
            );
        }
        step
    }

    fn on_resume_msg1(&mut self, body: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        if body.len() != IK_MESSAGE_LENS[0] {
            return self.malformed("IK message 1 length");
        }
        let Some(mut hs) = self.handshake.take() else {
            return self.malformed("no handshake");
        };
        let mut payload = [0_u8; 64];
        match hs.read_message(body, &mut payload) {
            Ok(0) => {}
            Ok(_) => return self.malformed("IK message 1 payload"),
            // A rewritten hello, a wrong hub key or a low-order key (`es`/`ss`).
            Err(_) => {
                self.fail(
                    &mut step,
                    Some(AbortReason::HandshakeFailed),
                    ws_close::AUTH,
                    &RelayError::HandshakeFailed,
                );
                return step;
            }
        }
        let Some(peer) = noise::remote_static(&hs) else {
            self.fail(
                &mut step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::HandshakeFailed,
            );
            return step;
        };
        // The live pin set, read now (not at accept time).
        let known = match self.pinned(&peer) {
            Ok(known) => known,
            Err(_) => return self.poisoned(),
        };
        let mut msg2 = [0_u8; 64];
        match hs.write_message(&[], &mut msg2) {
            Ok(n) if n == IK_MESSAGE_LENS[1] => {
                step.push_binary(wire::encode_handshake(&msg2[..n]))
            }
            _ => {
                self.fail(
                    &mut step,
                    Some(AbortReason::HandshakeFailed),
                    ws_close::AUTH,
                    &RelayError::HandshakeFailed,
                );
                return step;
            }
        }
        let binding = noise::handshake_hash(&hs);
        let Ok(state) = hs.into_transport_mode() else {
            self.fail(
                &mut step,
                None,
                ws_close::AUTH,
                &RelayError::HandshakeFailed,
            );
            return step;
        };
        self.transport = Some(Transport::new(
            state,
            transport::rekey_interval(self.rekey_override),
            now_ms,
        ));
        self.binding = Some(binding);
        self.peer_static = Some(peer);
        if !known {
            // Message 2 then an authenticated refusal, so a forgotten remote can
            // prove the real hub refused it (§2.6).
            self.send_close_record(&mut step, CloseCode::NotPaired, now_ms);
            step.events.push(RelayEvent::UnknownRemote {
                remote_id: DeviceId::of(&peer).to_hex(),
            });
            self.finish(&mut step, Phase::Closed, ws_close::REFUSED, "not paired");
            return step;
        }
        // Nothing with a side effect until the remote's first record (§2.6).
        self.phase = Phase::AwaitClientFinished;
        step
    }

    fn send_close_record(&mut self, step: &mut Step, code: CloseCode, now_ms: u64) {
        let payload = wire::encode_close(code, "");
        if let Some(Ok(records)) = self
            .transport
            .as_mut()
            .map(|t| t.encrypt(inner::CLOSE, &payload, now_ms))
        {
            for r in records {
                step.push_binary(r);
            }
        }
    }

    fn transport_failed(&mut self, err: TransportError) -> Step {
        let mut step = Step::default();
        let (code, error) = match err {
            TransportError::Decrypt => (ws_close::AUTH, RelayError::Decrypt),
            TransportError::Protocol(what) => (ws_close::PROTOCOL, RelayError::Malformed(what)),
            TransportError::TooLarge => (ws_close::PROTOCOL, RelayError::MessageTooLarge),
            TransportError::NonceExhausted => (ws_close::PROTOCOL, RelayError::NonceExhausted),
        };
        step.events.push(RelayEvent::Failed {
            error: error.name().to_owned(),
        });
        self.finish(&mut step, Phase::Failed, code, error.name());
        step
    }

    fn on_record(&mut self, body: &[u8], now_ms: u64) -> Step {
        let Some(transport) = self.transport.as_mut() else {
            return self.malformed("no transport");
        };
        let inbound = match transport.decrypt(body, now_ms) {
            Ok(i) => i,
            Err(e) => return self.transport_failed(e),
        };
        let mut step = Step::default();
        if self.phase == Phase::AwaitClientFinished {
            if inbound != Inbound::Heartbeat {
                return self
                    .transport_failed(TransportError::Protocol("first record must be HEARTBEAT"));
            }
            let Some(peer) = self.peer_static else {
                return self.malformed("no peer");
            };
            // Admission re-reads the live pin set: a Forget since message 1 wins.
            match self.pinned(&peer) {
                Ok(true) => {
                    self.phase = Phase::Established;
                    self.deadline = None;
                    step.events.push(RelayEvent::Established {
                        peer_id: DeviceId::of(&peer).to_hex(),
                    });
                }
                Ok(false) => {
                    self.send_close_record(&mut step, CloseCode::Revoked, now_ms);
                    self.finish(&mut step, Phase::Closed, ws_close::REFUSED, "revoked");
                }
                Err(_) => return self.poisoned(),
            }
            return step;
        }
        match inbound {
            Inbound::Frame(message) => step.deliver.push(message),
            Inbound::Partial | Inbound::Heartbeat => {}
            Inbound::PairComplete => {
                return self.transport_failed(TransportError::Protocol("PAIR_COMPLETE on resume"));
            }
            Inbound::Close(code) => {
                self.finish(&mut step, Phase::Closed, ws_close::NORMAL, code.name());
            }
        }
        step
    }

    // ---- output ---------------------------------------------------------------

    /// Encrypts one application message (a `Frame`'s JSON) into its records.
    ///
    /// # Errors
    /// [`RelayError::InvalidState`] unless established;
    /// [`RelayError::MessageTooLarge`] over `MAX_MESSAGE_BYTES` (nothing changes).
    pub fn send(&mut self, message: &[u8], now_ms: u64) -> Result<Step, RelayError> {
        if self.phase != Phase::Established {
            return Err(RelayError::InvalidState);
        }
        if message.len() > crate::MAX_MESSAGE_BYTES {
            return Err(RelayError::MessageTooLarge);
        }
        let Some(transport) = self.transport.as_mut() else {
            return Err(RelayError::InvalidState);
        };
        match transport.encrypt(inner::FRAME, message, now_ms) {
            Ok(records) => {
                let mut step = Step::default();
                for r in records {
                    step.push_binary(r);
                }
                Ok(step)
            }
            Err(e) => Ok(self.transport_failed(e)),
        }
    }

    /// Closes the session: an authenticated `CLOSE` record once the transport
    /// exists, `ABORT(CANCELLED)` during a pairing, a plain close otherwise.
    pub fn close(&mut self, reason: CloseReason, now_ms: u64) -> Step {
        self.now_ms = self.now_ms.max(now_ms);
        if self.is_terminal() {
            return Step::default();
        }
        let mut step = Step::default();
        if self.pairing_phase() {
            self.end_pairing(
                &mut step,
                AbortReason::Cancelled,
                ws_close::REFUSED,
                "Cancelled",
            );
            return step;
        }
        if matches!(self.phase, Phase::AwaitClientFinished | Phase::Established) {
            let code = CloseCode::from(reason);
            self.send_close_record(&mut step, code, now_ms);
            self.finish(&mut step, Phase::Closed, code.ws_code(), code.name());
            return step;
        }
        self.finish(&mut step, Phase::Closed, ws_close::NORMAL, "closed");
        step
    }

    /// Drives deadlines, the gate generation check and heartbeats. Call it
    /// when `next_deadline_ms()` passes, and right after closing the gate.
    pub fn tick(&mut self, now_ms: u64) -> Step {
        if let Some(step) = self.preflight(now_ms) {
            return step;
        }
        let mut step = Step::default();
        if self.phase != Phase::Established {
            return step;
        }
        let Some(transport) = self.transport.as_mut() else {
            return step;
        };
        if transport.timed_out(now_ms) {
            step.events.push(RelayEvent::Failed {
                error: RelayError::Timeout.name().to_owned(),
            });
            self.finish(&mut step, Phase::Failed, ws_close::TIMEOUT, "Timeout");
            return step;
        }
        if transport.heartbeat_due(now_ms) {
            match transport.encrypt(inner::HEARTBEAT, &[], now_ms) {
                Ok(records) => {
                    for r in records {
                        step.push_binary(r);
                    }
                }
                Err(e) => return self.transport_failed(e),
            }
        }
        step
    }
}

#[cfg(feature = "test-seeds")]
impl HubConnection {
    /// Uses a reduced rekey interval (records per direction); call before the handshake.
    pub fn set_rekey_interval_for_tests(&mut self, records: u64) {
        self.rekey_override = Some(records);
    }

    /// The psk of this pairing, for vectors and tests.
    pub fn psk_for_tests(&self) -> Option<[u8; 32]> {
        self.psk_for_tests
    }

    /// Bytes held by an unfinished reassembly.
    pub fn buffered_for_tests(&self) -> usize {
        self.transport.as_ref().map_or(0, Transport::buffered)
    }

    /// Encrypts one record with arbitrary inner flags, type and payload, as a
    /// misbehaving peer would (any phase with a transport).
    ///
    /// # Errors
    /// [`RelayError::InvalidState`] without a transport; [`RelayError::MessageTooLarge`]
    /// when the record can't be encrypted.
    pub fn send_raw_record_for_tests(
        &mut self,
        flags: u8,
        ty: u8,
        payload: &[u8],
        now_ms: u64,
    ) -> Result<Step, RelayError> {
        let transport = self.transport.as_mut().ok_or(RelayError::InvalidState)?;
        let record = transport
            .encrypt_raw(flags, ty, payload, now_ms)
            .map_err(|_| RelayError::MessageTooLarge)?;
        let mut step = Step::default();
        step.push_binary(record);
        Ok(step)
    }
}
