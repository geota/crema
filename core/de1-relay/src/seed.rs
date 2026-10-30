//! Seeds and the deterministic random streams derived from them (§2.11).
//!
//! The crate draws no entropy of its own: every key pair and connection is
//! built from a 32-byte [`Seed`] the bridge fills from the platform. Each use
//! gets its own domain-separated stream, `HKDF-SHA256(ikm = seed, info = label)`
//! keying a ChaCha20 generator.

use core::fmt;

use hkdf::Hkdf;
use rand_chacha::ChaCha20Rng;
use rand_core::{CryptoRng, RngCore, SeedableRng};
use sha2::Sha256;
use zeroize::{Zeroize, ZeroizeOnDrop};

/// 32 bytes of entropy for one key pair or one connection. Wiped on drop;
/// `Debug` never prints it.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct Seed(pub [u8; 32]);

impl Seed {
    /// The sub-seed for one stream: `HKDF-SHA256(salt = none, ikm = seed, info = label)`.
    pub(crate) fn derive(&self, label: &[u8]) -> SubSeed {
        SubSeed::derive_from(&self.0, label)
    }

    /// A copy for a component that derives several streams later (the Noise
    /// resolver). Crate-internal so a caller can't duplicate a seed by accident.
    pub(crate) fn duplicate(&self) -> Seed {
        Seed(self.0)
    }
}

impl fmt::Debug for Seed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Seed([redacted])")
    }
}

/// One derived stream key. Wiped on drop; `Debug` never prints it.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct SubSeed([u8; 32]);

impl SubSeed {
    pub(crate) fn derive_from(ikm: &[u8; 32], label: &[u8]) -> SubSeed {
        let mut okm = [0_u8; 32];
        // 32 bytes is far below HKDF-SHA256's 255 × 32 limit, so expand can't fail.
        Hkdf::<Sha256>::new(None, ikm)
            .expand(label, &mut okm)
            .unwrap_or_else(|_| unreachable!("32-byte HKDF output"));
        let sub = SubSeed(okm);
        okm.zeroize();
        sub
    }

    /// The ChaCha20 generator keyed by this sub-seed.
    pub(crate) fn rng(&self) -> SeededRandom {
        SeededRandom(ChaCha20Rng::from_seed(self.0))
    }
}

impl fmt::Debug for SubSeed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SubSeed([redacted])")
    }
}

/// A seeded ChaCha20 stream, usable as `rand_core` 0.6's RNG (for `spake2`)
/// and as snow's [`Random`](snow::types::Random). The generator's internal
/// state is not wiped on drop (`rand_chacha` has no zeroize support); it holds
/// only a derived stream key, never a seed.
pub struct SeededRandom(ChaCha20Rng);

impl fmt::Debug for SeededRandom {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SeededRandom([redacted])")
    }
}

impl RngCore for SeededRandom {
    fn next_u32(&mut self) -> u32 {
        self.0.next_u32()
    }

    fn next_u64(&mut self) -> u64 {
        self.0.next_u64()
    }

    fn fill_bytes(&mut self, dest: &mut [u8]) {
        self.0.fill_bytes(dest);
    }

    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> {
        self.0.try_fill_bytes(dest)
    }
}

impl CryptoRng for SeededRandom {}

impl snow::types::Random for SeededRandom {
    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), snow::Error> {
        self.0.fill_bytes(dest);
        Ok(())
    }
}
