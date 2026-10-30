//! A two-sided harness: a hub connection and a remote connection joined by an
//! in-memory "WebSocket" that records every message (the transcript) and can
//! be tampered with.
#![allow(dead_code)]

pub mod evil;

use std::{
    collections::VecDeque,
    sync::{Arc, Mutex, RwLock},
};

use de1_relay::{
    HubConnection, Outgoing, PairingGate, PinSet, RelayEvent, RemoteConnection, StaticKeypair,
    Step, WsIn, test_support::test_seed,
};

pub const T0: u64 = 1_000_000;

pub fn keys(label: &str, n: u32) -> StaticKeypair {
    StaticKeypair::generate(test_seed(label, n))
}

pub fn remote_keys() -> StaticKeypair {
    keys("remote-static", 0)
}

pub fn hub_keys() -> StaticKeypair {
    keys("hub-static", 0)
}

pub fn new_gate() -> Arc<Mutex<PairingGate>> {
    Arc::new(Mutex::new(PairingGate::new()))
}

pub fn new_pins(keys: Vec<[u8; 32]>) -> Arc<RwLock<PinSet>> {
    Arc::new(RwLock::new(PinSet::new(keys).unwrap()))
}

pub fn open_gate(gate: &Arc<Mutex<PairingGate>>, now: u64) {
    gate.lock().unwrap().open(now).unwrap();
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dir {
    ToHub,
    ToRemote,
}

/// One recorded WebSocket message.
#[derive(Clone, Debug)]
pub struct Wire {
    pub dir: Dir,
    pub msg: Outgoing,
}

/// A filter applied to every message in flight: return `None` to drop it.
pub type Tamper = Box<dyn FnMut(Dir, Outgoing) -> Option<Outgoing>>;

pub struct Link {
    pub hub: HubConnection,
    pub remote: RemoteConnection,
    pub gate: Arc<Mutex<PairingGate>>,
    pub pins: Arc<RwLock<PinSet>>,
    pub now: u64,
    pub to_hub: VecDeque<Outgoing>,
    pub to_remote: VecDeque<Outgoing>,
    pub hub_steps: Vec<Step>,
    pub remote_steps: Vec<Step>,
    pub wire: Vec<Wire>,
    pub hub_closed: bool,
    pub remote_closed: bool,
    pub tamper: Option<Tamper>,
}

pub fn ws(msg: &Outgoing) -> WsIn<'_> {
    if msg.text {
        WsIn::Text(&msg.bytes)
    } else {
        WsIn::Binary(&msg.bytes)
    }
}

impl Link {
    pub fn pairing(
        remote_keys: &StaticKeypair,
        hub_keys: &StaticKeypair,
        gate: Arc<Mutex<PairingGate>>,
        pins: Arc<RwLock<PinSet>>,
        n: u32,
        now: u64,
    ) -> Self {
        let hub = HubConnection::new(
            hub_keys,
            gate.clone(),
            pins.clone(),
            "Kitchen tablet",
            test_seed("hub-conn", n),
            now,
        );
        let (remote, first) =
            RemoteConnection::pair(remote_keys, "Pixel 8", test_seed("remote-conn", n), now);
        Self::with(hub, remote, first, gate, pins, now)
    }

    pub fn resume(
        remote_keys: &StaticKeypair,
        hub_keys: &StaticKeypair,
        gate: Arc<Mutex<PairingGate>>,
        pins: Arc<RwLock<PinSet>>,
        n: u32,
        now: u64,
    ) -> Self {
        let hub = HubConnection::new(
            hub_keys,
            gate.clone(),
            pins.clone(),
            "Kitchen tablet",
            test_seed("hub-conn", n),
            now,
        );
        let (remote, first) = RemoteConnection::resume(
            remote_keys,
            hub_keys.public(),
            test_seed("remote-conn", n),
            now,
        );
        Self::with(hub, remote, first, gate, pins, now)
    }

    pub fn with(
        hub: HubConnection,
        remote: RemoteConnection,
        first: Step,
        gate: Arc<Mutex<PairingGate>>,
        pins: Arc<RwLock<PinSet>>,
        now: u64,
    ) -> Self {
        let mut link = Self {
            hub,
            remote,
            gate,
            pins,
            now,
            to_hub: VecDeque::new(),
            to_remote: VecDeque::new(),
            hub_steps: Vec::new(),
            remote_steps: Vec::new(),
            wire: Vec::new(),
            hub_closed: false,
            remote_closed: false,
            tamper: None,
        };
        link.remote_step(first);
        link
    }

    /// Installs a tamper filter, applying it to messages already queued too
    /// (the remote's ClientHello is queued at construction).
    pub fn set_tamper(&mut self, tamper: Tamper) {
        self.tamper = Some(tamper);
        let queued: Vec<(Dir, Outgoing)> = self
            .to_hub
            .drain(..)
            .map(|m| (Dir::ToHub, m))
            .chain(self.to_remote.drain(..).map(|m| (Dir::ToRemote, m)))
            .collect();
        let keep = self.wire.len() - queued.len();
        self.wire.truncate(keep);
        for (dir, m) in queued {
            self.enqueue(dir, m);
        }
    }

    fn enqueue(&mut self, dir: Dir, msg: Outgoing) {
        let msg = match self.tamper.as_mut() {
            Some(t) => match t(dir, msg) {
                Some(m) => m,
                None => return,
            },
            None => msg,
        };
        self.wire.push(Wire {
            dir,
            msg: msg.clone(),
        });
        match dir {
            Dir::ToHub => self.to_hub.push_back(msg),
            Dir::ToRemote => self.to_remote.push_back(msg),
        }
    }

    pub fn hub_step(&mut self, step: Step) {
        for m in step.send.iter().cloned() {
            self.enqueue(Dir::ToRemote, m);
        }
        if step.close.is_some() {
            self.hub_closed = true;
        }
        self.hub_steps.push(step);
    }

    pub fn remote_step(&mut self, step: Step) {
        for m in step.send.iter().cloned() {
            self.enqueue(Dir::ToHub, m);
        }
        if step.close.is_some() {
            self.remote_closed = true;
        }
        self.remote_steps.push(step);
    }

    /// Delivers queued messages until both queues are empty. A side that
    /// closed receives nothing more; what it sent before closing still arrives.
    pub fn pump(&mut self) {
        while self.deliver_one() {}
    }

    /// Delivers one queued message (to the hub first). False when both queues are empty.
    pub fn deliver_one(&mut self) -> bool {
        if let Some(m) = self.to_hub.pop_front() {
            if !self.hub_closed {
                let step = self.hub.on_ws(ws(&m), self.now);
                self.hub_step(step);
            }
            true
        } else if let Some(m) = self.to_remote.pop_front() {
            if !self.remote_closed {
                let step = self.remote.on_ws(ws(&m), self.now);
                self.remote_step(step);
            }
            true
        } else {
            false
        }
    }

    /// Delivers one queued message to the hub only.
    pub fn deliver_to_hub(&mut self) -> bool {
        let Some(m) = self.to_hub.pop_front() else {
            return false;
        };
        if !self.hub_closed {
            let step = self.hub.on_ws(ws(&m), self.now);
            self.hub_step(step);
        }
        true
    }

    /// Delivers one queued message to the remote only.
    pub fn deliver_to_remote(&mut self) -> bool {
        let Some(m) = self.to_remote.pop_front() else {
            return false;
        };
        if !self.remote_closed {
            let step = self.remote.on_ws(ws(&m), self.now);
            self.remote_step(step);
        }
        true
    }

    pub fn hub_events(&self) -> Vec<RelayEvent> {
        self.hub_steps
            .iter()
            .flat_map(|s| s.events.clone())
            .collect()
    }

    pub fn remote_events(&self) -> Vec<RelayEvent> {
        self.remote_steps
            .iter()
            .flat_map(|s| s.events.clone())
            .collect()
    }

    pub fn hub_delivered(&self) -> Vec<Vec<u8>> {
        self.hub_steps
            .iter()
            .flat_map(|s| s.deliver.clone())
            .collect()
    }

    pub fn remote_delivered(&self) -> Vec<Vec<u8>> {
        self.remote_steps
            .iter()
            .flat_map(|s| s.deliver.clone())
            .collect()
    }

    pub fn hub_close(&self) -> Option<u16> {
        self.hub_steps
            .iter()
            .find_map(|s| s.close.as_ref().map(|c| c.code))
    }

    pub fn remote_close(&self) -> Option<u16> {
        self.remote_steps
            .iter()
            .find_map(|s| s.close.as_ref().map(|c| c.code))
    }

    /// The code the remote showed.
    pub fn shown_code(&self) -> Option<String> {
        self.remote_events().into_iter().find_map(|e| match e {
            RelayEvent::ShowCode { code, .. } => Some(code),
            _ => None,
        })
    }

    pub fn enter_code(&mut self, code: &str) {
        let step = self.hub.enter_code(code, self.now).unwrap();
        self.hub_step(step);
        self.pump();
    }

    /// The shell's commit: insert the pin, then confirm.
    pub fn commit(&mut self) {
        let key = self.hub.peer_public_key().unwrap();
        self.pins.write().unwrap().insert(key).unwrap();
        let step = self.hub.confirm_paired(self.now).unwrap();
        self.hub_step(step);
        self.pump();
    }

    pub fn hub_send(&mut self, msg: &[u8]) {
        let step = self.hub.send(msg, self.now).unwrap();
        self.hub_step(step);
        self.pump();
    }

    pub fn remote_send(&mut self, msg: &[u8]) {
        let step = self.remote.send(msg, self.now).unwrap();
        self.remote_step(step);
        self.pump();
    }

    pub fn tick(&mut self, now: u64) {
        self.now = now;
        let s = self.hub.tick(now);
        self.hub_step(s);
        let s = self.remote.tick(now);
        self.remote_step(s);
        self.pump();
    }

    /// Recorded messages in one direction.
    pub fn sent(&self, dir: Dir) -> Vec<Outgoing> {
        self.wire
            .iter()
            .filter(|w| w.dir == dir)
            .map(|w| w.msg.clone())
            .collect()
    }
}

/// A full pairing with the right code, committed. Returns the finished link.
pub fn paired_link(remote_keys: &StaticKeypair, hub_keys: &StaticKeypair, n: u32) -> Link {
    let gate = new_gate();
    open_gate(&gate, T0);
    let mut link = Link::pairing(remote_keys, hub_keys, gate, new_pins(vec![]), n, T0);
    link.pump();
    let code = link.shown_code().expect("code shown");
    link.enter_code(&code);
    link.commit();
    link
}

/// A pinned remote resumes and both sides reach `Established`.
pub fn established(remote_keys: &StaticKeypair, hub_keys: &StaticKeypair, n: u32) -> Link {
    let pins = new_pins(vec![remote_keys.public()]);
    let mut link = Link::resume(remote_keys, hub_keys, new_gate(), pins, n, T0);
    link.pump();
    link
}

/// A code that differs from `code` in every digit.
pub fn wrong_code(code: &str) -> String {
    code.bytes()
        .map(|b| char::from(b'0' + (b - b'0' + 1) % 10))
        .collect()
}

pub fn has_event(events: &[RelayEvent], f: impl Fn(&RelayEvent) -> bool) -> bool {
    events.iter().any(f)
}

pub fn is_established(e: &RelayEvent) -> bool {
    matches!(e, RelayEvent::Established { .. })
}

pub fn is_paired(e: &RelayEvent) -> bool {
    matches!(e, RelayEvent::Paired { .. })
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

pub fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}
