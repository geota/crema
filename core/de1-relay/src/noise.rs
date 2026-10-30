//! The snow builders (§2.4 step 7, §2.6), our `CryptoResolver` and the prologues.
//!
//! The resolver does two things snow's `DefaultResolver` doesn't:
//! - randomness comes from the connection's seed, a fresh stream per
//!   `resolve_rng()` call (`HKDF(seed, "noise" ‖ counter)`), never from the OS;
//! - every X25519 output goes through [`ZeroCheckDh`], which fails the DH (and
//!   so the handshake) when the 32-byte result is all zero, compared in
//!   constant time. That rejects low-order statics and ephemerals (§2.3).
//!
//! Cipher and hash are snow's (`chacha20poly1305`, `sha2`).

use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};

use snow::{
    Builder, HandshakeState,
    params::{CipherChoice, DHChoice, HashChoice},
    resolvers::{CryptoResolver, DefaultResolver},
    types::{Cipher, Dh, Hash, Random},
};
use subtle::ConstantTimeEq;

use crate::{Seed, labels, pake::Psk, seed::SubSeed};

/// The pairing pattern.
pub const XX_PARAMS: &str = "Noise_XXpsk3_25519_ChaChaPoly_SHA256";
/// The resume pattern.
pub const IK_PARAMS: &str = "Noise_IK_25519_ChaChaPoly_SHA256";
/// Exact `XXpsk3` message lengths with empty payloads. Message 1 is 48, not 32:
/// in a psk handshake the `e` token also runs `MixKey`, so message 1's empty
/// payload carries a 16-byte tag (cacophony agrees; see `PROTOCOL.md`).
pub const XX_MESSAGE_LENS: [usize; 3] = [48, 96, 64];
/// Exact `IK` message lengths with empty payloads.
pub const IK_MESSAGE_LENS: [usize; 2] = [96, 48];

/// Which handshake to build.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Pattern {
    /// `XXpsk3`, psk at position 3.
    Pairing,
    /// `IK`.
    Resume,
}

/// Wraps snow's X25519 and fails any DH whose output is all zero.
struct ZeroCheckDh(Box<dyn Dh>);

const ZERO: [u8; 64] = [0; 64];

impl Dh for ZeroCheckDh {
    fn name(&self) -> &'static str {
        self.0.name()
    }

    fn pub_len(&self) -> usize {
        self.0.pub_len()
    }

    fn priv_len(&self) -> usize {
        self.0.priv_len()
    }

    fn set(&mut self, privkey: &[u8]) {
        self.0.set(privkey);
    }

    fn generate(&mut self, rng: &mut dyn Random) -> Result<(), snow::Error> {
        self.0.generate(rng)
    }

    fn pubkey(&self) -> &[u8] {
        self.0.pubkey()
    }

    fn privkey(&self) -> &[u8] {
        self.0.privkey()
    }

    fn dh(&self, pubkey: &[u8], out: &mut [u8]) -> Result<(), snow::Error> {
        self.0.dh(pubkey, out)?;
        let len = self.0.dh_len();
        let all_zero = out
            .get(..len)
            .zip(ZERO.get(..len))
            .is_none_or(|(o, z)| bool::from(o.ct_eq(z)));
        if all_zero {
            return Err(snow::Error::Dh);
        }
        Ok(())
    }

    fn dh_len(&self) -> usize {
        self.0.dh_len()
    }
}

/// Where a resolver's randomness comes from.
enum RngSource {
    /// A fresh stream per call: `HKDF(seed, "noise" ‖ counter_be64)`.
    Seeded { seed: Seed, counter: Arc<AtomicU64> },
    /// Scripted byte strings, one per call (the cacophony replay, AC-2).
    #[cfg(feature = "test-seeds")]
    Scripted(Arc<std::sync::Mutex<std::collections::VecDeque<Vec<u8>>>>),
}

/// Our snow resolver: seeded randomness, snow's primitives, and the all-zero
/// DH check.
pub struct RelayResolver {
    source: RngSource,
}

impl core::fmt::Debug for RelayResolver {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("RelayResolver")
    }
}

/// A connection's Noise entropy: the seed and a shared counter, so every
/// resolver made from it draws a different stream (two builders on one seed
/// never share ephemerals).
pub(crate) struct NoiseEntropy {
    seed: Seed,
    counter: Arc<AtomicU64>,
}

impl NoiseEntropy {
    pub(crate) fn new(seed: &Seed) -> Self {
        Self {
            seed: seed.duplicate(),
            counter: Arc::new(AtomicU64::new(0)),
        }
    }

    pub(crate) fn resolver(&self) -> RelayResolver {
        RelayResolver {
            source: RngSource::Seeded {
                seed: self.seed.duplicate(),
                counter: Arc::clone(&self.counter),
            },
        }
    }
}

impl RelayResolver {
    /// A resolver whose `resolve_rng()` calls return `streams` in order, for
    /// replaying vectors with fixed ephemerals.
    #[cfg(feature = "test-seeds")]
    pub fn scripted_for_tests(streams: Vec<Vec<u8>>) -> Self {
        Self {
            source: RngSource::Scripted(Arc::new(std::sync::Mutex::new(streams.into()))),
        }
    }
}

impl CryptoResolver for RelayResolver {
    fn resolve_rng(&self) -> Option<Box<dyn Random>> {
        match &self.source {
            RngSource::Seeded { seed, counter } => {
                let n = counter.fetch_add(1, Ordering::Relaxed);
                let label = [labels::STREAM_NOISE, &n.to_be_bytes()].concat();
                Some(Box::new(SubSeed::derive_from(&seed.0, &label).rng()))
            }
            #[cfg(feature = "test-seeds")]
            RngSource::Scripted(streams) => {
                let next = streams.lock().ok()?.pop_front()?;
                Some(Box::new(ScriptedRandom(next.into())))
            }
        }
    }

    fn resolve_dh(&self, choice: &DHChoice) -> Option<Box<dyn Dh>> {
        DefaultResolver
            .resolve_dh(choice)
            .map(|dh| Box::new(ZeroCheckDh(dh)) as Box<dyn Dh>)
    }

    fn resolve_hash(&self, choice: &HashChoice) -> Option<Box<dyn Hash>> {
        DefaultResolver.resolve_hash(choice)
    }

    fn resolve_cipher(&self, choice: &CipherChoice) -> Option<Box<dyn Cipher>> {
        DefaultResolver.resolve_cipher(choice)
    }
}

/// Returns scripted bytes; fails once they run out.
#[cfg(feature = "test-seeds")]
struct ScriptedRandom(std::collections::VecDeque<u8>);

#[cfg(feature = "test-seeds")]
impl Random for ScriptedRandom {
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), snow::Error> {
        for b in dest.iter_mut() {
            *b = self.0.pop_front().ok_or(snow::Error::Rng)?;
        }
        Ok(())
    }
}

/// Builds a handshake state through our resolver.
pub(crate) fn build(
    pattern: Pattern,
    initiator: bool,
    local_private: &[u8; 32],
    remote_public: Option<&[u8; 32]>,
    prologue: &[u8],
    psk: Option<&Psk>,
    resolver: RelayResolver,
) -> Result<HandshakeState, snow::Error> {
    let params = match pattern {
        Pattern::Pairing => XX_PARAMS,
        Pattern::Resume => IK_PARAMS,
    }
    .parse()?;
    let mut builder = Builder::with_resolver(params, Box::new(resolver))
        .local_private_key(local_private)?
        .prologue(prologue)?;
    if let Some(rs) = remote_public {
        builder = builder.remote_public_key(rs)?;
    }
    if let Some(psk) = psk {
        builder = builder.psk(3, psk.as_bytes())?;
    }
    if initiator {
        builder.build_initiator()
    } else {
        builder.build_responder()
    }
}

/// `lp(x)` = 2-byte big-endian length ‖ x. Callers bound every input below 64 KiB.
fn push_lp(out: &mut Vec<u8>, x: &[u8]) {
    let len = u16::try_from(x.len()).unwrap_or(u16::MAX);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(&x[..usize::from(len)]);
}

/// `"CremaRelay v4 pair" ‖ 0x00 ‖ lp(client_hello) ‖ lp(server_hello) ‖ lp(pake_a) ‖ lp(pake_b)`.
/// The PAKE fields are the 33-byte SPAKE2 messages (without the kind byte).
pub(crate) fn pair_prologue(
    client_hello: &[u8],
    server_hello: &[u8],
    pake_a: &[u8],
    pake_b: &[u8],
) -> Vec<u8> {
    let mut out = Vec::with_capacity(
        labels::PAIR_PROLOGUE.len() + 9 + client_hello.len() + server_hello.len() + 66,
    );
    out.extend_from_slice(labels::PAIR_PROLOGUE);
    out.push(0);
    push_lp(&mut out, client_hello);
    push_lp(&mut out, server_hello);
    push_lp(&mut out, pake_a);
    push_lp(&mut out, pake_b);
    out
}

/// `"CremaRelay v4 resume" ‖ 0x00 ‖ lp(client_hello) ‖ lp(server_hello)`.
pub(crate) fn resume_prologue(client_hello: &[u8], server_hello: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(
        labels::RESUME_PROLOGUE.len() + 5 + client_hello.len() + server_hello.len(),
    );
    out.extend_from_slice(labels::RESUME_PROLOGUE);
    out.push(0);
    push_lp(&mut out, client_hello);
    push_lp(&mut out, server_hello);
    out
}

/// The 32-byte handshake hash, read before `into_transport_mode()` (snow's
/// `TransportState` doesn't keep it).
pub(crate) fn handshake_hash(hs: &HandshakeState) -> [u8; 32] {
    let mut h = [0_u8; 32];
    let src = hs.get_handshake_hash();
    h.copy_from_slice(&src[..32]);
    h
}

/// The peer's static key once the handshake revealed it.
pub(crate) fn remote_static(hs: &HandshakeState) -> Option<[u8; 32]> {
    hs.get_remote_static().and_then(|rs| rs.try_into().ok())
}
