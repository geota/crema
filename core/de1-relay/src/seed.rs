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
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

/// 32 bytes of entropy for one key pair or one connection. Not `Clone`; wiped
/// on drop; `Debug` never prints it.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct Seed([u8; 32]);

impl Seed {
    /// Wraps 32 bytes the bridge just drew from the platform's entropy source.
    ///
    /// **Single use:** each seed must be fresh entropy and go to exactly one
    /// key pair or one connection. Two connections built from the same seed
    /// would generate the same code, SPAKE2 scalar and ephemerals. The
    /// constructors that take a `Seed` consume it, so the type can't be
    /// reused by accident; never build two from the same bytes.
    pub fn from_entropy(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// The seed bytes, for vectors and tests only.
    #[cfg(feature = "test-seeds")]
    pub fn expose_for_tests(&self) -> [u8; 32] {
        self.0
    }

    pub(crate) fn bytes(&self) -> &[u8; 32] {
        &self.0
    }

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
        let okm = hkdf_sha256(None, ikm, label);
        SubSeed(*okm)
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

/// HKDF-SHA256 with a 32-byte output. The PRK and the output buffers we hold
/// are wiped; the keyed HMAC states inside `hkdf` are not (`hkdf` 0.12, `hmac`
/// 0.12 and `sha2` 0.10 have no zeroize support).
pub(crate) fn hkdf_sha256(salt: Option<&[u8]>, ikm: &[u8], info: &[u8]) -> Zeroizing<[u8; 32]> {
    let mut okm = Zeroizing::new([0_u8; 32]);
    let (mut prk, _) = Hkdf::<Sha256>::extract(salt, ikm);
    // A 32-byte PRK and a 32-byte output are always valid for SHA-256.
    let expanded = Hkdf::<Sha256>::from_prk(&prk)
        .map_err(|_| ())
        .and_then(|h| h.expand(info, okm.as_mut()).map_err(|_| ()));
    prk.as_mut_slice().zeroize();
    expanded.unwrap_or_else(|()| unreachable!("32-byte HKDF-SHA256"));
    okm
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
