//! The remote side of one WebSocket (§3.3): the pairing initiator (SPAKE2
//! side A, then `XXpsk3`), the IK initiator, and the transport.
//!
//! The remote never falls back: a legacy or silent hub gets nothing after the
//! ClientHello, and no failure leads to pairing on its own.

use snow::HandshakeState;

use crate::{
    CODE_LIFETIME_MS, CloseReason, DeviceId, HELLO_DEADLINE_MS, PAIR_FINISH_MS, Phase,
    RESUME_DEADLINE_MS, RelayError, RelayEvent, Seed, StaticKeypair, Step, VERSION_MAX,
    VERSION_MIN, WS_MAX_MESSAGE, WsIn,
    hello::{self, ClientHello, HelloStatus, ServerHello},
    noise::{self, IK_MESSAGE_LENS, NoiseEntropy, Pattern, XX_MESSAGE_LENS},
    pake::{self, Code, SIDE_B, SpakeState},
    seed::SeededRandom,
    step::{AbortReason, CloseCode, Outgoing},
    transport::{self, Inbound, Transport, TransportError},
    wire::{self, PAKE_LEN, inner, kind},
    ws_close,
};

#[derive(Clone, Copy, Debug)]
enum Mode {
    Pair,
    Resume {
        hub_public: [u8; 32],
        hub_id: DeviceId,
    },
}

/// The remote's state for one dialled WebSocket.
pub struct RemoteConnection {
    keys: StaticKeypair,
    my_id: DeviceId,
    mode: Mode,
    /// The code, SPAKE2 and Noise randomness, each dropped as soon as it has
    /// been used (resume never holds the first two).
    code_rng: Option<SeededRandom>,
    spake_rng: Option<SeededRandom>,
    noise: Option<NoiseEntropy>,
    rekey_override: Option<u64>,

    phase: Phase,
    deadline: Option<u64>,
    client_hello: Vec<u8>,
    server_hello: Vec<u8>,
    hub_id: Option<DeviceId>,
    hub_name: String,
    code: Option<Code>,
    spake: Option<SpakeState>,
    pake_a: Option<[u8; PAKE_LEN]>,
    handshake: Option<HandshakeState>,
    transport: Option<Transport>,
    peer_static: Option<[u8; 32]>,
    binding: Option<[u8; 32]>,
    #[cfg(feature = "test-seeds")]
    psk_for_tests: Option<[u8; 32]>,
}

impl core::fmt::Debug for RemoteConnection {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.debug_struct("RemoteConnection")
            .field("phase", &self.phase)
            .finish_non_exhaustive()
    }
}

impl RemoteConnection {
    fn new(
        keys: &StaticKeypair,
        mode: Mode,
        seed: &Seed,
        hello: &ClientHello,
        now_ms: u64,
    ) -> (Self, Step) {
        let pairing = matches!(mode, Mode::Pair);
        let client_hello = hello.encode();
        let conn = Self {
            keys: keys.duplicate(),
            my_id: keys.device_id(),
            mode,
            code_rng: pairing.then(|| seed.derive(crate::labels::STREAM_CODE).rng()),
            spake_rng: pairing.then(|| seed.derive(crate::labels::STREAM_SPAKE).rng()),
            noise: Some(NoiseEntropy::new(seed)),
            rekey_override: None,
            phase: Phase::AwaitHello,
            deadline: Some(now_ms + HELLO_DEADLINE_MS),
            client_hello: client_hello.clone(),
            server_hello: Vec::new(),
            hub_id: None,
            hub_name: String::new(),
            code: None,
            spake: None,
            pake_a: None,
            handshake: None,
            transport: None,
            peer_static: None,
            binding: None,
            #[cfg(feature = "test-seeds")]
            psk_for_tests: None,
        };
        let step = Step {
            send: vec![Outgoing::text(client_hello)],
            ..Step::default()
        };
        (conn, step)
    }

    /// A resume to the pinned hub `hub_public`. The ClientHello carries no id
    /// and no name (§2.6). Returns the connection and the Step with the hello.
    pub fn resume(
        keys: &StaticKeypair,
        hub_public: [u8; 32],
        seed: Seed,
        now_ms: u64,
    ) -> (Self, Step) {
        let mode = Mode::Resume {
            hub_public,
            hub_id: DeviceId::of(&hub_public),
        };
        Self::new(keys, mode, &seed, &ClientHello::resume(), now_ms)
    }

    /// A pairing attempt (one code, one SPAKE2 run). Returns the connection
    /// and the Step with the hello.
    pub fn pair(keys: &StaticKeypair, my_name: &str, seed: Seed, now_ms: u64) -> (Self, Step) {
        let hello = ClientHello::pair(keys.device_id(), my_name);
        Self::new(keys, Mode::Pair, &seed, &hello, now_ms)
    }

    /// The code to show, while the hub waits for it to be typed.
    pub fn code(&self) -> Option<String> {
        if self.phase == Phase::Pake {
            self.code.as_ref().map(|c| c.as_str().to_owned())
        } else {
            None
        }
    }

    /// The current phase.
    pub fn phase(&self) -> Phase {
        self.phase
    }

    /// The hub's static key, once trusted: in `Paired` and `Established`. `None`
    /// before, after a failure and after a normal close (`Closed`), so the
    /// shell pins the key it read at `Paired`.
    pub fn peer_public_key(&self) -> Option<[u8; 32]> {
        // Only once the hub is trusted: PAIR_COMPLETE decrypted, or IK message 2
        // did. A fake hub that reached XX message 2 without the code reveals nothing.
        match self.phase {
            Phase::Paired | Phase::Established => self.peer_static,
            _ => None,
        }
    }

    /// The Noise handshake hash `h` of an established session (`Established` only).
    pub fn channel_binding(&self) -> Option<[u8; 32]> {
        (self.phase == Phase::Established)
            .then_some(self.binding)
            .flatten()
    }

    /// When the shell should call `tick` next.
    pub fn next_deadline_ms(&self) -> Option<u64> {
        match self.phase {
            Phase::Established => self.transport.as_ref().map(Transport::next_deadline_ms),
            Phase::Paired | Phase::Closed | Phase::Failed => None,
            _ => self.deadline,
        }
    }

    fn pairing(&self) -> bool {
        matches!(self.mode, Mode::Pair)
    }

    fn is_terminal(&self) -> bool {
        matches!(self.phase, Phase::Paired | Phase::Closed | Phase::Failed)
    }

    // ---- terminal transitions ------------------------------------------------

    fn finish(&mut self, step: &mut Step, phase: Phase, code: u16, reason: &str) {
        self.phase = phase;
        self.deadline = None;
        self.code = None;
        self.spake = None;
        self.handshake = None;
        if let Some(t) = self.transport.as_mut() {
            t.drop_partial();
        }
        step.close_with(code, reason);
    }

    /// A failure on our side. Pairing reports `PairingFailed` (`NotConfirmed`
    /// once message 3 is out); resume reports `Failed`, or `Refused` when the
    /// cause is the hub's.
    fn fail(&mut self, step: &mut Step, abort: Option<AbortReason>, code: u16, error: &RelayError) {
        if let Some(reason) = abort {
            step.push_binary(wire::encode_abort(reason, &[]));
        }
        if self.pairing() {
            let reason = if self.phase == Phase::AwaitConfirm {
                RelayError::NotConfirmed.name()
            } else {
                error.name()
            };
            step.events.push(pairing_failed(reason));
        } else {
            step.events.push(RelayEvent::Failed {
                error: error.name().to_owned(),
            });
        }
        self.finish(step, Phase::Failed, code, error.name());
    }

    fn refuse(
        &mut self,
        step: &mut Step,
        phase: Phase,
        code: u16,
        reason: &str,
        authenticated: bool,
    ) {
        step.events.push(RelayEvent::Refused {
            reason: reason.to_owned(),
            authenticated,
        });
        self.finish(step, phase, code, reason);
    }

    fn malformed(&mut self, what: &'static str) -> Step {
        let mut step = Step::default();
        // In AwaitHello the hub may be a legacy one: never send it a second message.
        let abort = (!matches!(self.phase, Phase::AwaitHello | Phase::Established))
            .then_some(AbortReason::Malformed);
        self.fail(
            &mut step,
            abort,
            ws_close::PROTOCOL,
            &RelayError::Malformed(what),
        );
        step
    }

    fn preflight(&mut self, now_ms: u64) -> Option<Step> {
        if self.is_terminal() {
            return Some(Step::default());
        }
        if self.phase != Phase::Established && self.deadline.is_some_and(|d| now_ms >= d) {
            return Some(self.on_deadline());
        }
        None
    }

    fn on_deadline(&mut self) -> Step {
        let mut step = Step::default();
        match self.phase {
            // No ServerHello: maybe a legacy or silent hub. Close; send nothing.
            Phase::AwaitHello => {
                self.refuse(
                    &mut step,
                    Phase::Failed,
                    ws_close::TIMEOUT,
                    "Timeout",
                    false,
                );
            }
            // The code expired: the attempt ends.
            Phase::Pake => {
                step.push_binary(wire::encode_abort(AbortReason::Timeout, &[]));
                step.events.push(pairing_failed(RelayError::Timeout.name()));
                self.finish(&mut step, Phase::Closed, ws_close::TIMEOUT, "Timeout");
            }
            _ if self.pairing() => {
                self.fail(
                    &mut step,
                    Some(AbortReason::Timeout),
                    ws_close::TIMEOUT,
                    &RelayError::Timeout,
                );
            }
            _ => {
                step.push_binary(wire::encode_abort(AbortReason::Timeout, &[]));
                self.refuse(
                    &mut step,
                    Phase::Failed,
                    ws_close::TIMEOUT,
                    "Timeout",
                    false,
                );
            }
        }
        step
    }

    // ---- input ----------------------------------------------------------------

    /// One WebSocket message from the hub.
    pub fn on_ws(&mut self, input: WsIn<'_>, now_ms: u64) -> Step {
        if let Some(step) = self.preflight(now_ms) {
            return step;
        }
        match (self.phase, input) {
            (Phase::AwaitHello, WsIn::Text(raw)) => {
                let mut step = Step::default();
                if hello::is_legacy_denial(raw) {
                    self.refuse(
                        &mut step,
                        Phase::Closed,
                        ws_close::VERSION,
                        "HubOutdated",
                        false,
                    );
                } else {
                    // A v3 challenge or anything else: no fallback, nothing sent.
                    self.refuse(
                        &mut step,
                        Phase::Failed,
                        ws_close::PROTOCOL,
                        "HubOutdated",
                        false,
                    );
                }
                step
            }
            (_, WsIn::Text(_)) => self.malformed("text from the hub"),
            (_, WsIn::Binary(msg)) if msg.len() > WS_MAX_MESSAGE => {
                self.malformed("oversize message")
            }
            (phase, WsIn::Binary(msg)) => {
                let Some((&k, body)) = msg.split_first() else {
                    return self.malformed("empty message");
                };
                match (phase, k) {
                    // Once the session is up, only records are allowed (§4.3).
                    (Phase::Established, kind::ABORT) => {
                        let mut step = Step::default();
                        self.fail(
                            &mut step,
                            None,
                            ws_close::PROTOCOL,
                            &RelayError::UnexpectedMessage,
                        );
                        step
                    }
                    (_, kind::ABORT) => self.on_abort(body),
                    (Phase::AwaitHello, kind::SERVER_HELLO) => self.on_server_hello(msg, now_ms),
                    (Phase::Pake, kind::PAKE_B) => self.on_pake_b(body, now_ms),
                    (Phase::Handshake, kind::NOISE_HS) => {
                        if self.pairing() {
                            self.on_pair_msg2(body, now_ms)
                        } else {
                            self.on_resume_msg2(body, now_ms)
                        }
                    }
                    (Phase::AwaitConfirm | Phase::Established, kind::RECORD) => {
                        self.on_record(body, now_ms)
                    }
                    _ => self.malformed("unexpected message kind"),
                }
            }
        }
    }

    fn on_abort(&mut self, body: &[u8]) -> Step {
        let Ok(abort) = wire::decode_abort(body) else {
            return self.malformed("abort");
        };
        let mut step = Step::default();
        let reason = abort.reason.name();
        let retryable = matches!(
            abort.reason,
            AbortReason::HandshakeFailed | AbortReason::Malformed
        );
        if self.pairing() && self.phase == Phase::AwaitConfirm {
            // A wrong code arrives here as ABORT(HANDSHAKE_FAILED) (§2.4 step 8).
            if !retryable {
                step.events.push(RelayEvent::Refused {
                    reason: reason.to_owned(),
                    authenticated: false,
                });
            }
            step.events
                .push(pairing_failed(RelayError::NotConfirmed.name()));
            self.finish(&mut step, Phase::Failed, ws_close::NORMAL, reason);
            return step;
        }
        if self.pairing() && retryable && self.phase != Phase::AwaitHello {
            step.events.push(pairing_failed(reason));
            self.finish(&mut step, Phase::Failed, ws_close::NORMAL, reason);
            return step;
        }
        self.refuse(&mut step, Phase::Closed, ws_close::NORMAL, reason, false);
        step
    }

    fn on_server_hello(&mut self, msg: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        let sh = match ServerHello::parse(msg) {
            Ok(sh) => sh,
            Err(RelayError::Malformed(what)) => return self.malformed(what),
            Err(_) => return self.malformed("server hello"),
        };
        if !(VERSION_MIN..=VERSION_MAX).contains(&sh.version) {
            step.push_binary(wire::encode_abort(AbortReason::Version, &[VERSION_MAX]));
            self.refuse(
                &mut step,
                Phase::Closed,
                ws_close::VERSION,
                AbortReason::Version.name(),
                false,
            );
            return step;
        }
        self.server_hello = msg.to_vec();
        self.hub_id = Some(sh.hub_id);
        self.hub_name = crate::sanitize_name(&sh.name);
        step.events.push(RelayEvent::HubHello {
            hub_id: sh.hub_id.to_hex(),
            hub_name: self.hub_name.clone(),
            status: sh.status.name().to_owned(),
            secs_left: u32::from(sh.secs_left),
        });
        match self.mode {
            Mode::Resume { hub_public, hub_id } => {
                if sh.hub_id != hub_id {
                    // Before any Noise message (§2.6): a reset hub or an impostor.
                    step.push_binary(wire::encode_abort(AbortReason::HostMismatch, &[]));
                    self.refuse(
                        &mut step,
                        Phase::Closed,
                        ws_close::AUTH,
                        "HostMismatch",
                        false,
                    );
                    return step;
                }
                if sh.status != HelloStatus::Ready {
                    self.refuse(
                        &mut step,
                        Phase::Closed,
                        ws_close::REFUSED,
                        sh.status.name(),
                        false,
                    );
                    return step;
                }
                self.start_ik(&mut step, hub_public, now_ms);
            }
            Mode::Pair => {
                if sh.status != HelloStatus::AwaitingCode {
                    self.refuse(
                        &mut step,
                        Phase::Closed,
                        ws_close::REFUSED,
                        sh.status.name(),
                        false,
                    );
                    return step;
                }
                // The code stream is consumed (and freed) by drawing the code.
                let code = self
                    .code_rng
                    .take()
                    .map(|mut rng| pake::generate_code(&mut rng));
                let (Some(code), Some(spake_rng)) = (code, self.spake_rng.take()) else {
                    return self.malformed("no entropy");
                };
                let lifetime = CODE_LIFETIME_MS.min(u64::from(sh.secs_left) * 1000);
                let expires_at_ms = now_ms + lifetime;
                // The code itself stays here; the shell reads it with `code()`.
                step.events.push(RelayEvent::ShowCode { expires_at_ms });
                let (state, pake_a) = pake::start_a(&code, &self.my_id, &sh.hub_id, spake_rng);
                step.push_binary(wire::encode_pake(kind::PAKE_A, &pake_a));
                self.code = Some(code);
                self.spake = Some(state);
                self.pake_a = Some(pake_a);
                self.phase = Phase::Pake;
                self.deadline = Some(expires_at_ms);
            }
        }
        step
    }

    fn start_ik(&mut self, step: &mut Step, hub_public: [u8; 32], now_ms: u64) {
        let Ok(prologue) = noise::resume_prologue(&self.client_hello, &self.server_hello) else {
            // A ServerHello over 65 535 bytes can't be bound.
            self.fail(
                step,
                Some(AbortReason::Malformed),
                ws_close::PROTOCOL,
                &RelayError::Malformed("prologue"),
            );
            return;
        };
        let Some(entropy) = self.noise.take() else {
            self.fail(step, None, ws_close::PROTOCOL, &RelayError::InvalidState);
            return;
        };
        let built = noise::build(
            Pattern::Resume,
            true,
            self.keys.private_bytes(),
            Some(&hub_public),
            &prologue,
            None,
            entropy.resolver(),
        );
        let mut msg1 = [0_u8; 128];
        let written = built.and_then(|mut hs| hs.write_message(&[], &mut msg1).map(|n| (hs, n)));
        match written {
            Ok((hs, n)) if n == IK_MESSAGE_LENS[0] => {
                step.push_binary(wire::encode_handshake(&msg1[..n]));
                self.handshake = Some(hs);
                self.phase = Phase::Handshake;
                self.deadline = Some(now_ms + RESUME_DEADLINE_MS);
            }
            // A low-order pinned hub key fails `es` here.
            _ => self.fail(
                step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::HandshakeFailed,
            ),
        }
    }

    fn on_pake_b(&mut self, body: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        let (Some(state), Some(pake_a), Some(hub_id)) =
            (self.spake.take(), self.pake_a, self.hub_id)
        else {
            return self.malformed("no SPAKE2 state");
        };
        // The code is spent: one SPAKE2 run, never reused.
        self.code = None;
        let Ok(spake_key) = pake::finish(state, body, SIDE_B) else {
            return self.malformed("PAKE_B element");
        };
        let Ok(prologue) =
            noise::pair_prologue(&self.client_hello, &self.server_hello, &pake_a, body)
        else {
            return self.malformed("prologue");
        };
        let psk = pake::derive_psk(&spake_key, &prologue, &self.my_id, &hub_id);
        drop(spake_key);
        #[cfg(feature = "test-seeds")]
        {
            self.psk_for_tests = Some(psk.expose_for_tests());
        }
        let Some(entropy) = self.noise.take() else {
            return self.malformed("no entropy");
        };
        let built = noise::build(
            Pattern::Pairing,
            true,
            self.keys.private_bytes(),
            None,
            &prologue,
            Some(&psk),
            entropy.resolver(),
        );
        drop(psk);
        let mut msg1 = [0_u8; 64];
        let written = built.and_then(|mut hs| hs.write_message(&[], &mut msg1).map(|n| (hs, n)));
        match written {
            Ok((hs, n)) if n == XX_MESSAGE_LENS[0] => {
                step.push_binary(wire::encode_handshake(&msg1[..n]));
                self.handshake = Some(hs);
                self.phase = Phase::Handshake;
                self.deadline = Some(now_ms + PAIR_FINISH_MS);
            }
            _ => self.fail(
                &mut step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::HandshakeFailed,
            ),
        }
        step
    }

    fn on_pair_msg2(&mut self, body: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        if body.len() != XX_MESSAGE_LENS[1] {
            return self.malformed("XX message 2 length");
        }
        let (Some(mut hs), Some(hub_id)) = (self.handshake.take(), self.hub_id) else {
            return self.malformed("no handshake");
        };
        let mut payload = [0_u8; 128];
        match hs.read_message(body, &mut payload) {
            Ok(0) => {}
            Ok(_) => return self.malformed("XX message 2 payload"),
            // Tampering, or a low-order hub ephemeral or static (`ee`/`es`).
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
        if DeviceId::of(&peer) != hub_id {
            self.fail(
                &mut step,
                Some(AbortReason::HandshakeFailed),
                ws_close::AUTH,
                &RelayError::PeerIdMismatch,
            );
            return step;
        }
        let mut msg3 = [0_u8; 128];
        match hs.write_message(&[], &mut msg3) {
            Ok(n) if n == XX_MESSAGE_LENS[2] => {
                step.push_binary(wire::encode_handshake(&msg3[..n]))
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
        self.phase = Phase::AwaitConfirm;
        step
    }

    fn on_resume_msg2(&mut self, body: &[u8], now_ms: u64) -> Step {
        let mut step = Step::default();
        if body.len() != IK_MESSAGE_LENS[1] {
            return self.malformed("IK message 2 length");
        }
        let (Some(mut hs), Some(hub_id)) = (self.handshake.take(), self.hub_id) else {
            return self.malformed("no handshake");
        };
        let mut payload = [0_u8; 64];
        match hs.read_message(body, &mut payload) {
            Ok(0) => {}
            Ok(_) => return self.malformed("IK message 2 payload"),
            // Not the pinned hub, a rewritten hello, or a low-order ephemeral.
            Err(_) => {
                self.refuse(
                    &mut step,
                    Phase::Failed,
                    ws_close::AUTH,
                    "HubUnverified",
                    false,
                );
                return step;
            }
        }
        let binding = noise::handshake_hash(&hs);
        let peer = noise::remote_static(&hs);
        let Ok(state) = hs.into_transport_mode() else {
            self.refuse(
                &mut step,
                Phase::Failed,
                ws_close::AUTH,
                "HubUnverified",
                false,
            );
            return step;
        };
        let mut transport = Transport::new(
            state,
            transport::rekey_interval(self.rekey_override),
            now_ms,
        );
        // "Client finished": the hub admits the session on this record.
        match transport.encrypt(inner::HEARTBEAT, &[], now_ms) {
            Ok(records) => {
                for r in records {
                    step.push_binary(r);
                }
            }
            Err(_) => {
                self.fail(
                    &mut step,
                    None,
                    ws_close::PROTOCOL,
                    &RelayError::InvalidState,
                );
                return step;
            }
        }
        self.transport = Some(transport);
        self.binding = Some(binding);
        self.peer_static = peer;
        self.phase = Phase::Established;
        self.deadline = None;
        step.events.push(RelayEvent::Established {
            peer_id: hub_id.to_hex(),
        });
        step
    }

    fn transport_failed(&mut self, err: TransportError) -> Step {
        let mut step = Step::default();
        let (code, error) = match err {
            TransportError::Decrypt => (ws_close::AUTH, RelayError::Decrypt),
            TransportError::Protocol(what) => (ws_close::PROTOCOL, RelayError::Malformed(what)),
            TransportError::TooLarge => (ws_close::PROTOCOL, RelayError::MessageTooLarge),
            TransportError::NonceExhausted => (ws_close::PROTOCOL, RelayError::NonceExhausted),
        };
        self.fail(&mut step, None, code, &error);
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
        if self.phase == Phase::AwaitConfirm {
            if inbound != Inbound::PairComplete {
                return self.transport_failed(TransportError::Protocol("expected PAIR_COMPLETE"));
            }
            step.events.push(RelayEvent::Paired {
                peer_id: self.hub_id.map(|id| id.to_hex()).unwrap_or_default(),
                peer_name: self.hub_name.clone(),
            });
            self.finish(&mut step, Phase::Paired, ws_close::NORMAL, "paired");
            return step;
        }
        match inbound {
            Inbound::Frame(message) => step.deliver.push(message),
            Inbound::Partial | Inbound::Heartbeat => {}
            Inbound::PairComplete => {
                return self.transport_failed(TransportError::Protocol("PAIR_COMPLETE on resume"));
            }
            // The only refusal that may change trust state (§2.9).
            Inbound::Close(code) => {
                self.refuse(&mut step, Phase::Closed, code.ws_code(), code.name(), true);
            }
        }
        step
    }

    // ---- output ---------------------------------------------------------------

    /// Encrypts one application message into its records.
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

    /// Closes: an authenticated `CLOSE` once established, `ABORT(CANCELLED)`
    /// during a pairing, a plain close while waiting for the ServerHello.
    pub fn close(&mut self, reason: CloseReason, now_ms: u64) -> Step {
        let mut step = Step::default();
        match self.phase {
            Phase::Paired | Phase::Closed | Phase::Failed => {}
            Phase::Established => {
                let code = CloseCode::from(reason);
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
                self.finish(&mut step, Phase::Closed, code.ws_code(), code.name());
            }
            Phase::AwaitHello => self.finish(&mut step, Phase::Closed, ws_close::NORMAL, "closed"),
            _ => {
                step.push_binary(wire::encode_abort(AbortReason::Cancelled, &[]));
                self.finish(&mut step, Phase::Closed, ws_close::NORMAL, "Cancelled");
            }
        }
        step
    }

    /// Drives deadlines and heartbeats.
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

fn pairing_failed(reason: &str) -> RelayEvent {
    RelayEvent::PairingFailed {
        reason: reason.to_owned(),
        failures: 0,
        locked: false,
    }
}

#[cfg(feature = "test-seeds")]
impl RemoteConnection {
    /// Uses a reduced rekey interval (records per direction); call before the handshake.
    pub fn set_rekey_interval_for_tests(&mut self, records: u64) {
        self.rekey_override = Some(records);
    }

    /// The handshake hash whatever the phase, for vectors and tests.
    pub fn channel_binding_for_tests(&self) -> Option<[u8; 32]> {
        self.binding
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
