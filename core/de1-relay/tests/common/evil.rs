//! Misbehaving peers built by hand from the crate's public pieces and snow:
//! a remote or hub that lies about its id, or plants a low-order static or
//! ephemeral key (§2.3).

use std::sync::atomic::{AtomicUsize, Ordering};

use de1_relay::{
    ClientHello, DeviceId, HelloStatus, HubConnection, IK_PARAMS, Intent, RemoteConnection,
    ServerHello, Step, WsIn, XX_PARAMS, kind,
    pake::{self, Code, SIDE_A, SIDE_B},
    test_support,
};
use rand_chacha::ChaCha20Rng;
use rand_core::{RngCore, SeedableRng};
use snow::{
    Builder, HandshakeState,
    params::{CipherChoice, DHChoice, HashChoice},
    resolvers::{CryptoResolver, DefaultResolver},
    types::{Cipher, Dh, Hash, Random},
};

/// A DH whose public key is a chosen point and whose every output is zero —
/// what an attacker holding a low-order "key" computes.
struct ZeroDh {
    public: [u8; 32],
}

impl Dh for ZeroDh {
    fn name(&self) -> &'static str {
        "25519"
    }
    fn pub_len(&self) -> usize {
        32
    }
    fn priv_len(&self) -> usize {
        32
    }
    fn set(&mut self, _: &[u8]) {}
    fn generate(&mut self, _: &mut dyn Random) -> Result<(), snow::Error> {
        Ok(())
    }
    fn pubkey(&self) -> &[u8] {
        &self.public
    }
    fn privkey(&self) -> &[u8] {
        &[0; 32]
    }
    fn dh(&self, _: &[u8], out: &mut [u8]) -> Result<(), snow::Error> {
        out[..32].fill(0);
        Ok(())
    }
}

struct FixedRandom(ChaCha20Rng);

impl Random for FixedRandom {
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), snow::Error> {
        self.0.fill_bytes(dest);
        Ok(())
    }
}

/// snow resolves the static DH first, then the ephemeral one; either can be
/// replaced by a [`ZeroDh`] with a chosen low-order public key. Without one it
/// is a plain resolver (no all-zero check: the attacker doesn't run ours).
pub struct EvilResolver {
    pub low_static: Option<[u8; 32]>,
    pub low_ephemeral: Option<[u8; 32]>,
    calls: AtomicUsize,
    seed: u8,
}

impl EvilResolver {
    pub fn new(low_static: Option<[u8; 32]>, low_ephemeral: Option<[u8; 32]>, seed: u8) -> Self {
        Self {
            low_static,
            low_ephemeral,
            calls: AtomicUsize::new(0),
            seed,
        }
    }
}

impl CryptoResolver for EvilResolver {
    fn resolve_rng(&self) -> Option<Box<dyn Random>> {
        Some(Box::new(FixedRandom(ChaCha20Rng::from_seed(
            [self.seed; 32],
        ))))
    }
    fn resolve_dh(&self, choice: &DHChoice) -> Option<Box<dyn Dh>> {
        let n = self.calls.fetch_add(1, Ordering::SeqCst);
        let low = if n == 0 {
            self.low_static
        } else {
            self.low_ephemeral
        };
        match low {
            Some(public) => Some(Box::new(ZeroDh { public })),
            None => DefaultResolver.resolve_dh(choice),
        }
    }
    fn resolve_hash(&self, choice: &HashChoice) -> Option<Box<dyn Hash>> {
        DefaultResolver.resolve_hash(choice)
    }
    fn resolve_cipher(&self, choice: &CipherChoice) -> Option<Box<dyn Cipher>> {
        DefaultResolver.resolve_cipher(choice)
    }
}

pub fn wire(k: u8, body: &[u8]) -> Vec<u8> {
    let mut v = vec![k];
    v.extend_from_slice(body);
    v
}

/// A remote that knows the code and runs the pairing by hand.
pub struct EvilPairRemote {
    pub hello: Vec<u8>,
    pub server_hello: Vec<u8>,
    pub pake_a: Vec<u8>,
    pub steps: Vec<Step>,
    prologue: Vec<u8>,
    psk: pake::Psk,
}

impl EvilPairRemote {
    /// Hello (claiming `claimed_id`), `PAKE_A`, the hub's code entry and `PAKE_B`.
    pub fn start(hub: &mut HubConnection, claimed_id: DeviceId, code: &str, now: u64) -> Self {
        let hello = ClientHello {
            vmin: 4,
            vmax: 4,
            intent: Intent::Pair,
            client_id: Some(claimed_id),
            client_name: "Evil".into(),
        }
        .encode();
        let s1 = hub.on_ws(WsIn::Text(&hello), now);
        let server_hello = s1.send[0].bytes.clone();
        let hub_id = ServerHello::parse(&server_hello).unwrap().hub_id;
        let code_v = Code::parse(code).unwrap();
        let (state, a) = pake::start_a(
            &code_v,
            &claimed_id,
            &hub_id,
            ChaCha20Rng::from_seed([11; 32]),
        );
        let s2 = hub.on_ws(WsIn::Binary(&wire(kind::PAKE_A, &a)), now);
        let s3 = hub.enter_code(code, now).unwrap();
        let pake_b = s3.send[0].bytes[1..].to_vec();
        let key = pake::finish(state, &pake_b, SIDE_B).unwrap();
        let prologue = test_support::pair_prologue(&hello, &server_hello, &a, &pake_b);
        let psk = pake::derive_psk(&key, &prologue, &claimed_id, &hub_id);
        Self {
            hello,
            server_hello,
            pake_a: a.to_vec(),
            steps: vec![s1, s2, s3],
            prologue,
            psk,
        }
    }

    /// XX messages 1–3 with this static key and resolver; returns the hub's steps.
    pub fn handshake(
        &mut self,
        hub: &mut HubConnection,
        static_private: &[u8; 32],
        resolver: EvilResolver,
        now: u64,
    ) -> Vec<Step> {
        let psk = self.psk.expose_for_tests();
        let mut hs = Builder::with_resolver(XX_PARAMS.parse().unwrap(), Box::new(resolver))
            .local_private_key(static_private)
            .unwrap()
            .prologue(&self.prologue)
            .unwrap()
            .psk(3, &psk)
            .unwrap()
            .build_initiator()
            .unwrap();
        let mut buf = [0_u8; 256];
        let mut out = [0_u8; 256];
        let n = hs.write_message(&[], &mut buf).unwrap();
        let s = hub.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), now);
        let mut steps = vec![s.clone()];
        let Some(msg2) = s.send.first().filter(|m| m.bytes[0] == kind::NOISE_HS) else {
            return steps;
        };
        hs.read_message(&msg2.bytes[1..], &mut out).unwrap();
        let n = hs.write_message(&[], &mut buf).unwrap();
        steps.push(hub.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), now));
        steps
    }
}

/// A resume by hand: hello, then IK message 1 with this static and resolver.
pub fn evil_resume_msg1(
    hub: &mut HubConnection,
    hub_public: &[u8; 32],
    static_private: &[u8; 32],
    resolver: EvilResolver,
    now: u64,
) -> (Step, Option<HandshakeState>) {
    let hello = ClientHello {
        vmin: 4,
        vmax: 4,
        intent: Intent::Resume,
        client_id: None,
        client_name: String::new(),
    }
    .encode();
    let s1 = hub.on_ws(WsIn::Text(&hello), now);
    let server_hello = s1.send[0].bytes.clone();
    let prologue = test_support::resume_prologue(&hello, &server_hello);
    let mut hs = Builder::with_resolver(IK_PARAMS.parse().unwrap(), Box::new(resolver))
        .local_private_key(static_private)
        .unwrap()
        .remote_public_key(hub_public)
        .unwrap()
        .prologue(&prologue)
        .unwrap()
        .build_initiator()
        .unwrap();
    let mut buf = [0_u8; 256];
    let n = hs.write_message(&[], &mut buf).unwrap();
    (
        hub.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), now),
        Some(hs),
    )
}

/// A hub that runs the pairing by hand against a real remote: it claims
/// `claimed_id` in its ServerHello and answers XX message 1 with this static
/// and resolver. Returns the remote's steps.
pub fn evil_pair_hub(
    remote: &mut RemoteConnection,
    first: &Step,
    claimed_id: DeviceId,
    static_private: &[u8; 32],
    resolver: EvilResolver,
    now: u64,
) -> Vec<Step> {
    evil_pair_hub_guessing(
        remote,
        first,
        claimed_id,
        static_private,
        resolver,
        None,
        now,
    )
}

/// As [`evil_pair_hub`], but the fake hub runs SPAKE2 with `guess` instead of
/// the code the remote shows (a hub without the code).
pub fn evil_pair_hub_guessing(
    remote: &mut RemoteConnection,
    first: &Step,
    claimed_id: DeviceId,
    static_private: &[u8; 32],
    resolver: EvilResolver,
    guess: Option<&str>,
    now: u64,
) -> Vec<Step> {
    let hello = first.send[0].bytes.clone();
    let remote_id = match ClientHello::parse(&hello).unwrap() {
        de1_relay::ParsedClientHello::V4(h) => h.client_id.unwrap(),
        _ => unreachable!(),
    };
    let sh = ServerHello {
        version: 4,
        status: HelloStatus::AwaitingCode,
        hub_id: claimed_id,
        secs_left: 120,
        name: "Evil hub".into(),
        ext: vec![],
    }
    .encode();
    let s1 = remote.on_ws(WsIn::Binary(&sh), now);
    let code = s1
        .events
        .iter()
        .find_map(|e| match e {
            de1_relay::RelayEvent::ShowCode { code, .. } => Some(code.clone()),
            _ => None,
        })
        .unwrap();
    let code = guess.map_or(code, str::to_owned);
    let pake_a = s1.send[0].bytes[1..].to_vec();
    let (state, b) = pake::start_b(
        &Code::parse(&code).unwrap(),
        &remote_id,
        &claimed_id,
        ChaCha20Rng::from_seed([12; 32]),
    );
    let key = pake::finish(state, &pake_a, SIDE_A).unwrap();
    let prologue = test_support::pair_prologue(&hello, &sh, &pake_a, &b);
    let psk = pake::derive_psk(&key, &prologue, &remote_id, &claimed_id).expose_for_tests();
    let s2 = remote.on_ws(WsIn::Binary(&wire(kind::PAKE_B, &b)), now);
    let msg1 = s2.send[0].bytes.clone();
    let mut hs = Builder::with_resolver(XX_PARAMS.parse().unwrap(), Box::new(resolver))
        .local_private_key(static_private)
        .unwrap()
        .prologue(&prologue)
        .unwrap()
        .psk(3, &psk)
        .unwrap()
        .build_responder()
        .unwrap();
    let mut out = [0_u8; 256];
    let mut buf = [0_u8; 256];
    hs.read_message(&msg1[1..], &mut out).unwrap();
    let n = hs.write_message(&[], &mut buf).unwrap();
    let s3 = remote.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), now);
    vec![s1, s2, s3]
}

/// A resume hub by hand: READY, then IK message 2 with this resolver.
pub fn evil_resume_hub(
    remote: &mut RemoteConnection,
    first: &Step,
    hub_keys: &de1_relay::StaticKeypair,
    resolver: EvilResolver,
    now: u64,
) -> Vec<Step> {
    let hello = first.send[0].bytes.clone();
    let sh = ServerHello {
        version: 4,
        status: HelloStatus::Ready,
        hub_id: hub_keys.device_id(),
        secs_left: 0,
        name: "Hub".into(),
        ext: vec![],
    }
    .encode();
    let s1 = remote.on_ws(WsIn::Binary(&sh), now);
    let prologue = test_support::resume_prologue(&hello, &sh);
    let mut hs = Builder::with_resolver(IK_PARAMS.parse().unwrap(), Box::new(resolver))
        .local_private_key(hub_keys.private_for_storage().as_ref())
        .unwrap()
        .prologue(&prologue)
        .unwrap()
        .build_responder()
        .unwrap();
    let mut out = [0_u8; 256];
    let mut buf = [0_u8; 256];
    hs.read_message(&s1.send[0].bytes[1..], &mut out).unwrap();
    let n = hs.write_message(&[], &mut buf).unwrap();
    let s2 = remote.on_ws(WsIn::Binary(&wire(kind::NOISE_HS, &buf[..n])), now);
    vec![s1, s2]
}
