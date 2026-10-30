//! Fuzz entry points (§3.7), shared by the cargo-fuzz targets in `fuzz/` and
//! the corpus replay in `tests/fuzz_corpus.rs`, so every PR runs the corpus on
//! stable.
//!
//! Each entry point reads its input as a small program: a mode byte, then
//! operations that feed fuzzed bytes, deliver (or corrupt) the honest peer's
//! next message, advance the clock, or make shell calls. The invariants are
//! asserts, so a violation is a crash the fuzzer reports:
//! - no panic anywhere;
//! - nothing is delivered before `Established`;
//! - no `Paired` without the right code and a `confirm_paired`;
//! - no gate failure without an entered code;
//! - no WebSocket message over `WS_MAX_MESSAGE`;
//! - reassembly never holds more than `MAX_MESSAGE_BYTES`.

use std::{
    collections::VecDeque,
    sync::{Arc, Mutex, RwLock},
};

use crate::{
    ClientHello, CloseReason, HubConnection, MAX_MESSAGE_BYTES, Outgoing, PairingGate, PinSet,
    RelayEvent, RemoteConnection, ServerHello, StaticKeypair, Step, WS_MAX_MESSAGE, WsIn,
    hello::{LEGACY_DENIAL, ParsedClientHello},
    test_support::test_seed,
    wire::kind,
};

const T0: u64 = 1_000_000;

/// A cursor over the fuzz input.
struct Input<'a>(&'a [u8]);

impl<'a> Input<'a> {
    fn byte(&mut self) -> Option<u8> {
        let (&b, rest) = self.0.split_first()?;
        self.0 = rest;
        Some(b)
    }

    fn u16(&mut self) -> u16 {
        let hi = self.byte().unwrap_or(0);
        let lo = self.byte().unwrap_or(0);
        u16::from_be_bytes([hi, lo])
    }

    fn bytes(&mut self) -> &'a [u8] {
        let len = usize::from(self.u16()).min(self.0.len());
        let (head, rest) = self.0.split_at(len);
        self.0 = rest;
        head
    }
}

fn ws(m: &Outgoing) -> WsIn<'_> {
    if m.text {
        WsIn::Text(&m.bytes)
    } else {
        WsIn::Binary(&m.bytes)
    }
}

fn check_sizes(step: &Step) {
    for m in &step.send {
        assert!(
            m.bytes.len() <= WS_MAX_MESSAGE,
            "outgoing message over the cap"
        );
    }
}

fn keys() -> (StaticKeypair, StaticKeypair) {
    (
        StaticKeypair::generate(test_seed("remote-static", 0)),
        StaticKeypair::generate(test_seed("hub-static", 0)),
    )
}

/// The observable state an invariant needs about one side.
#[derive(Default)]
struct Watch {
    established: bool,
    paired: bool,
}

impl Watch {
    fn see(&mut self, step: &Step) {
        check_sizes(step);
        for e in &step.events {
            match e {
                RelayEvent::Established { .. } => self.established = true,
                RelayEvent::Paired { .. } => self.paired = true,
                _ => {}
            }
        }
        if !step.deliver.is_empty() {
            assert!(self.established, "delivered before Established");
        }
    }
}

/// The pairing code a step told the shell to show (read from the remote).
fn shown_code(step: &Step, remote: &RemoteConnection) -> Option<String> {
    step.events
        .iter()
        .any(|e| matches!(e, RelayEvent::ShowCode { .. }))
        .then(|| remote.code())
        .flatten()
}

fn corrupt(mut m: Outgoing, pos: u16, xor: u8) -> Outgoing {
    if !m.bytes.is_empty() {
        let i = usize::from(pos) % m.bytes.len();
        m.bytes[i] ^= xor.max(1);
    }
    m
}

/// A hub under test, with an honest remote on the other end.
pub fn hub_ws(data: &[u8]) {
    let mut input = Input(data);
    let mode = input.byte().unwrap_or(0);
    let resume = mode & 1 == 1;
    let (remote_keys, hub_keys) = keys();
    let gate = Arc::new(Mutex::new(PairingGate::new()));
    if let Ok(mut g) = gate.lock() {
        let _ = g.open(T0);
    }
    let pins = Arc::new(RwLock::new(
        PinSet::new(vec![remote_keys.public()]).unwrap_or_default(),
    ));
    let mut now = T0;
    let mut hub = HubConnection::new(
        &hub_keys,
        Arc::clone(&gate),
        Arc::clone(&pins),
        "Hub",
        test_seed("hub-conn", 9),
        now,
    );
    let (mut remote, first) = if resume {
        RemoteConnection::resume(
            &remote_keys,
            hub_keys.public(),
            test_seed("remote-conn", 9),
            now,
        )
    } else {
        RemoteConnection::pair(&remote_keys, "Remote", test_seed("remote-conn", 9), now)
    };
    let mut to_hub: VecDeque<Outgoing> = first.send.into_iter().collect();
    let mut watch = Watch::default();
    let mut code: Option<String> = None;
    let mut right_code_entered = false;
    let mut code_entered = false;
    let mut confirmed = false;

    // Applies a hub step: invariants, then the honest remote's answers.
    let apply = |step: Step,
                 hub: &HubConnection,
                 remote: &mut RemoteConnection,
                 to_hub: &mut VecDeque<Outgoing>,
                 code: &mut Option<String>,
                 now: u64,
                 watch: &mut Watch| {
        watch.see(&step);
        assert!(hub.buffered_for_tests() <= MAX_MESSAGE_BYTES);
        for m in &step.send {
            let answer = remote.on_ws(ws(m), now);
            check_sizes(&answer);
            if code.is_none() {
                *code = shown_code(&answer, remote);
            }
            to_hub.extend(answer.send);
        }
    };

    while let Some(op) = input.byte() {
        let step = match op % 8 {
            0 => hub.on_ws(WsIn::Text(input.bytes()), now),
            1 => hub.on_ws(WsIn::Binary(input.bytes()), now),
            2 => {
                now += u64::from(input.u16()) * 10;
                hub.tick(now)
            }
            3 => {
                let right = input.byte().unwrap_or(0) & 1 == 0;
                let entry = match (&code, right) {
                    (Some(c), true) => c.clone(),
                    _ => "000000".to_owned(),
                };
                match hub.enter_code(&entry, now) {
                    Ok(step) => {
                        if !step.send.is_empty() {
                            code_entered = true;
                            right_code_entered |= right && code.is_some();
                        }
                        step
                    }
                    Err(_) => Step::default(),
                }
            }
            4 => {
                if let (Some(key), Ok(mut p)) = (hub.peer_public_key(), pins.write()) {
                    let _ = p.insert(key);
                }
                match hub.confirm_paired(now) {
                    Ok(step) => {
                        confirmed = true;
                        step
                    }
                    Err(_) => Step::default(),
                }
            }
            5 => match to_hub.pop_front() {
                Some(m) => hub.on_ws(ws(&m), now),
                None => Step::default(),
            },
            6 => {
                let pos = input.u16();
                let xor = input.byte().unwrap_or(1);
                match to_hub.pop_front() {
                    Some(m) => {
                        let m = corrupt(m, pos, xor);
                        hub.on_ws(ws(&m), now)
                    }
                    None => Step::default(),
                }
            }
            _ => match input.byte().unwrap_or(0) % 4 {
                0 => hub.cancel_pairing(now),
                1 => hub.reject_pairing(now),
                2 => hub.close(CloseReason::Revoked, now),
                _ => {
                    if let Ok(mut g) = gate.lock() {
                        g.close();
                    }
                    hub.tick(now)
                }
            },
        };
        apply(
            step,
            &hub,
            &mut remote,
            &mut to_hub,
            &mut code,
            now,
            &mut watch,
        );
        if watch.paired {
            assert!(
                right_code_entered && confirmed,
                "Paired without the right code and a confirm"
            );
        }
        if let Ok(g) = gate.lock()
            && g.status(now).failures > 0
        {
            assert!(code_entered, "gate failure without an entered code");
        }
    }
}

/// A remote under test, with an honest hub on the other end.
pub fn remote_ws(data: &[u8]) {
    let mut input = Input(data);
    let mode = input.byte().unwrap_or(0);
    let resume = mode & 1 == 1;
    let (remote_keys, hub_keys) = keys();
    let gate = Arc::new(Mutex::new(PairingGate::new()));
    if let Ok(mut g) = gate.lock() {
        let _ = g.open(T0);
    }
    let pins = Arc::new(RwLock::new(
        PinSet::new(vec![remote_keys.public()]).unwrap_or_default(),
    ));
    let mut now = T0;
    let mut hub = HubConnection::new(
        &hub_keys,
        Arc::clone(&gate),
        Arc::clone(&pins),
        "Hub",
        test_seed("hub-conn", 8),
        now,
    );
    let (mut remote, first) = if resume {
        RemoteConnection::resume(
            &remote_keys,
            hub_keys.public(),
            test_seed("remote-conn", 8),
            now,
        )
    } else {
        RemoteConnection::pair(&remote_keys, "Remote", test_seed("remote-conn", 8), now)
    };
    check_sizes(&first);
    let mut to_remote: VecDeque<Outgoing> = VecDeque::new();
    for m in &first.send {
        to_remote.extend(hub.on_ws(ws(m), now).send);
    }
    let mut watch = Watch::default();
    let mut code: Option<String> = None;
    let mut right_code_entered = false;
    let mut confirmed = false;

    while let Some(op) = input.byte() {
        let step = match op % 8 {
            0 => remote.on_ws(WsIn::Text(input.bytes()), now),
            1 => remote.on_ws(WsIn::Binary(input.bytes()), now),
            2 => {
                now += u64::from(input.u16()) * 10;
                let h = hub.tick(now);
                to_remote.extend(h.send);
                remote.tick(now)
            }
            3 => {
                let right = input.byte().unwrap_or(0) & 1 == 0;
                let entry = match (&code, right) {
                    (Some(c), true) => c.clone(),
                    _ => "000000".to_owned(),
                };
                if let Ok(h) = hub.enter_code(&entry, now) {
                    if !h.send.is_empty() {
                        right_code_entered |= right && code.is_some();
                    }
                    to_remote.extend(h.send);
                }
                Step::default()
            }
            4 => {
                if let (Some(key), Ok(mut p)) = (hub.peer_public_key(), pins.write()) {
                    let _ = p.insert(key);
                }
                if let Ok(h) = hub.confirm_paired(now) {
                    confirmed = true;
                    to_remote.extend(h.send);
                }
                Step::default()
            }
            5 => match to_remote.pop_front() {
                Some(m) => remote.on_ws(ws(&m), now),
                None => Step::default(),
            },
            6 => {
                let pos = input.u16();
                let xor = input.byte().unwrap_or(1);
                match to_remote.pop_front() {
                    Some(m) => {
                        let m = corrupt(m, pos, xor);
                        remote.on_ws(ws(&m), now)
                    }
                    None => Step::default(),
                }
            }
            _ => remote.close(CloseReason::Normal, now),
        };
        watch.see(&step);
        assert!(remote.buffered_for_tests() <= MAX_MESSAGE_BYTES);
        if code.is_none() {
            code = shown_code(&step, &remote);
        }
        for m in &step.send {
            let h = hub.on_ws(ws(m), now);
            check_sizes(&h);
            to_remote.extend(h.send);
        }
        if watch.paired {
            assert!(
                right_code_entered && confirmed,
                "Paired without the right code and a confirm"
            );
        }
    }
}

/// Records into an established session, including fuzzed inner headers.
pub fn transport_records(data: &[u8]) {
    let mut input = Input(data);
    let (remote_keys, hub_keys) = keys();
    let gate = Arc::new(Mutex::new(PairingGate::new()));
    let pins = Arc::new(RwLock::new(
        PinSet::new(vec![remote_keys.public()]).unwrap_or_default(),
    ));
    let mut now = T0;
    let mut hub = HubConnection::new(&hub_keys, gate, pins, "Hub", test_seed("hub-conn", 7), now);
    let (mut remote, first) = RemoteConnection::resume(
        &remote_keys,
        hub_keys.public(),
        test_seed("remote-conn", 7),
        now,
    );
    let mode = input.byte().unwrap_or(0);
    if mode & 1 == 1 {
        let interval = u64::from(mode >> 1).max(1);
        hub.set_rekey_interval_for_tests(interval);
        remote.set_rekey_interval_for_tests(interval);
    }
    // Handshake.
    let mut queue: VecDeque<(bool, Outgoing)> = first.send.into_iter().map(|m| (true, m)).collect();
    while let Some((to_hub, m)) = queue.pop_front() {
        let step = if to_hub {
            hub.on_ws(ws(&m), now)
        } else {
            remote.on_ws(ws(&m), now)
        };
        queue.extend(step.send.into_iter().map(|m| (!to_hub, m)));
    }
    let mut hub_watch = Watch {
        established: true,
        paired: false,
    };
    let mut remote_watch = Watch {
        established: true,
        paired: false,
    };
    while let Some(op) = input.byte() {
        let to_hub = op & 0x80 != 0;
        let records: Vec<Outgoing> = match op % 8 {
            0 => {
                let len = usize::from(input.u16()) * 17;
                let fill = input.byte().unwrap_or(0);
                let msg = vec![fill; len.min(MAX_MESSAGE_BYTES + 1)];
                let sender_step = if to_hub {
                    remote.send(&msg, now)
                } else {
                    hub.send(&msg, now)
                };
                sender_step.map(|s| s.send).unwrap_or_default()
            }
            1 => {
                let flags = input.byte().unwrap_or(0);
                let ty = input.byte().unwrap_or(0);
                let payload = input.bytes();
                let s = if to_hub {
                    remote.send_raw_record_for_tests(flags, ty, payload, now)
                } else {
                    hub.send_raw_record_for_tests(flags, ty, payload, now)
                };
                s.map(|s| s.send).unwrap_or_default()
            }
            2 => {
                let mut bytes = vec![kind::RECORD];
                bytes.extend_from_slice(input.bytes());
                vec![Outgoing { text: false, bytes }]
            }
            3 => {
                // A full-size fragment with MORE, from the honest sender's keys.
                let payload = vec![input.byte().unwrap_or(0); crate::MAX_RECORD_PAYLOAD];
                let s = if to_hub {
                    remote.send_raw_record_for_tests(
                        crate::inner::FLAG_MORE,
                        crate::inner::FRAME,
                        &payload,
                        now,
                    )
                } else {
                    hub.send_raw_record_for_tests(
                        crate::inner::FLAG_MORE,
                        crate::inner::FRAME,
                        &payload,
                        now,
                    )
                };
                s.map(|s| s.send).unwrap_or_default()
            }
            4 => {
                now += u64::from(input.u16()) * 10;
                let h = hub.tick(now);
                hub_watch.see(&h);
                let r = remote.tick(now);
                remote_watch.see(&r);
                Vec::new()
            }
            5 => {
                let pos = input.u16();
                let xor = input.byte().unwrap_or(1);
                let s = if to_hub {
                    remote.send(b"{}", now)
                } else {
                    hub.send(b"{}", now)
                };
                s.map(|s| s.send.into_iter().map(|m| corrupt(m, pos, xor)).collect())
                    .unwrap_or_default()
            }
            6 => {
                let reason = match input.byte().unwrap_or(0) % 4 {
                    0 => CloseReason::Revoked,
                    1 => CloseReason::NotPaired,
                    2 => CloseReason::HubStopped,
                    _ => CloseReason::Normal,
                };
                if to_hub {
                    remote.close(reason, now).send
                } else {
                    hub.close(reason, now).send
                }
            }
            _ => {
                let bytes = input.bytes().to_vec();
                vec![Outgoing {
                    text: input.byte().unwrap_or(0) & 1 == 1,
                    bytes,
                }]
            }
        };
        for m in records {
            assert!(
                m.bytes.len() <= WS_MAX_MESSAGE,
                "outgoing message over the cap"
            );
            if to_hub {
                let s = hub.on_ws(ws(&m), now);
                hub_watch.see(&s);
            } else {
                let s = remote.on_ws(ws(&m), now);
                remote_watch.see(&s);
            }
        }
        assert!(hub.buffered_for_tests() <= MAX_MESSAGE_BYTES);
        assert!(remote.buffered_for_tests() <= MAX_MESSAGE_BYTES);
    }
}

/// The hellos: the hub's ClientHello parser and the remote's ServerHello parser.
pub fn client_hello(data: &[u8]) {
    let (remote_keys, hub_keys) = keys();
    let gate = Arc::new(Mutex::new(PairingGate::new()));
    if let Ok(mut g) = gate.lock() {
        let _ = g.open(T0);
    }
    let pins = Arc::new(RwLock::new(PinSet::default()));
    let mut hub = HubConnection::new(&hub_keys, gate, pins, "Hub", test_seed("hub-conn", 6), T0);
    let step = hub.on_ws(WsIn::Text(data), T0);
    check_sizes(&step);
    assert!(step.send.len() <= 1);
    if let Some(m) = step.send.first().filter(|m| m.text) {
        assert_eq!(m.bytes, LEGACY_DENIAL.as_bytes());
    }
    assert!(step.deliver.is_empty());

    if let Ok(ParsedClientHello::V4(hello)) = ClientHello::parse(data) {
        let again = ClientHello::parse(&hello.encode());
        assert_eq!(
            again,
            Ok(ParsedClientHello::V4(hello)),
            "ClientHello round trip"
        );
    }
    if let Ok(sh) = ServerHello::parse(data) {
        assert_eq!(sh.encode(), data, "ServerHello round trip");
    }

    // The remote never answers a text or a garbage ServerHello.
    let (mut remote, _) = RemoteConnection::resume(
        &remote_keys,
        hub_keys.public(),
        test_seed("remote-conn", 6),
        T0,
    );
    let step = remote.on_ws(WsIn::Text(data), T0);
    assert!(step.send.is_empty() && step.close.is_some());
    let (mut remote, _) =
        RemoteConnection::pair(&remote_keys, "Remote", test_seed("remote-conn", 6), T0);
    let mut msg = vec![kind::SERVER_HELLO];
    msg.extend_from_slice(data);
    let step = remote.on_ws(WsIn::Binary(&msg), T0);
    check_sizes(&step);
}

/// Seed inputs for each target (written by `gen_vectors -- --corpus`).
pub fn seed_corpus() -> Vec<(&'static str, String, Vec<u8>)> {
    let deliver_all = |n: usize| vec![5_u8; n];
    let mut out = Vec::new();
    // hub_ws: a full pairing with the right code and a commit; a wrong code; a resume.
    let mut pair_ok = vec![0_u8];
    pair_ok.extend(deliver_all(2));
    pair_ok.extend([3, 0]);
    pair_ok.extend(deliver_all(3));
    pair_ok.push(4);
    out.push(("fuzz_hub_ws", "pair_right_code".to_owned(), pair_ok));
    let mut pair_wrong = vec![0_u8];
    pair_wrong.extend(deliver_all(2));
    pair_wrong.extend([3, 1]);
    pair_wrong.extend(deliver_all(3));
    out.push(("fuzz_hub_ws", "pair_wrong_code".to_owned(), pair_wrong));
    let mut resume = vec![1_u8];
    resume.extend(deliver_all(4));
    resume.extend([2, 0x0f, 0xff]);
    out.push(("fuzz_hub_ws", "resume".to_owned(), resume));
    let mut legacy = vec![0_u8, 0];
    let v3 = br#"{"type":"hello","v":3,"role":"secondary","clientId":"install-1","clientName":"Sam's phone"}"#;
    legacy.extend(u16::try_from(v3.len()).unwrap_or(0).to_be_bytes());
    legacy.extend_from_slice(v3);
    out.push(("fuzz_hub_ws", "legacy_v3_hello".to_owned(), legacy));
    // remote_ws: the same flows from the remote's side.
    let mut r_pair = vec![0_u8];
    r_pair.extend(deliver_all(1));
    r_pair.extend([3, 0]);
    r_pair.extend(deliver_all(3));
    r_pair.push(4);
    r_pair.extend(deliver_all(1));
    out.push(("fuzz_remote_ws", "pair_right_code".to_owned(), r_pair));
    let mut r_resume = vec![1_u8];
    r_resume.extend(deliver_all(3));
    out.push(("fuzz_remote_ws", "resume".to_owned(), r_resume));
    let mut r_denial = vec![1_u8, 0];
    r_denial.extend(
        u16::try_from(LEGACY_DENIAL.len())
            .unwrap_or(0)
            .to_be_bytes(),
    );
    r_denial.extend_from_slice(LEGACY_DENIAL.as_bytes());
    out.push(("fuzz_remote_ws", "legacy_denial".to_owned(), r_denial));
    // transport_records: messages both ways, a large one, fragments, a rekey interval.
    out.push((
        "fuzz_transport_records",
        "small_both_ways".to_owned(),
        vec![0, 0, 0, 1, 0x61, 0x80, 0, 2, 0x62],
    ));
    out.push((
        "fuzz_transport_records",
        "large_message".to_owned(),
        vec![0, 0, 0x2d, 0xf0, 0x7a],
    ));
    out.push((
        "fuzz_transport_records",
        "fragments_then_tick".to_owned(),
        vec![0, 3, 1, 3, 2, 4, 0x0b, 0xb8],
    ));
    out.push((
        "fuzz_transport_records",
        "rekey_every_4".to_owned(),
        vec![
            9, 0, 0, 1, 1, 0, 0, 1, 2, 0, 0, 1, 3, 0, 0, 1, 4, 0, 0, 1, 5,
        ],
    ));
    out.push((
        "fuzz_transport_records",
        "raw_heartbeat_more".to_owned(),
        vec![0, 1, 1, 2, 0, 0],
    ));
    // client_hello: v2/v3/v4 hellos and a ServerHello body.
    out.push(("fuzz_client_hello", "v2".to_owned(), br#"{"type":"hello","v":2,"role":"secondary","clientId":"install-1","clientName":"Sam's phone"}"#.to_vec()));
    out.push(("fuzz_client_hello", "v3".to_owned(), v3.to_vec()));
    let (remote_keys, hub_keys) = keys();
    out.push((
        "fuzz_client_hello",
        "v4_pair".to_owned(),
        ClientHello::pair(remote_keys.device_id(), "Pixel 8").encode(),
    ));
    out.push((
        "fuzz_client_hello",
        "v4_resume".to_owned(),
        ClientHello::resume().encode(),
    ));
    let sh = ServerHello {
        version: 4,
        status: crate::HelloStatus::AwaitingCode,
        hub_id: hub_keys.device_id(),
        secs_left: 120,
        name: "Kitchen tablet".into(),
        ext: Vec::new(),
    };
    out.push(("fuzz_client_hello", "server_hello".to_owned(), sh.encode()));
    out
}
