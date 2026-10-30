//! Static key pairs and device ids (§2.3).

use core::fmt;

use curve25519_dalek::montgomery::MontgomeryPoint;
use rand_core::RngCore;
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

use crate::{RelayError, Seed, labels};

/// A device's long-term X25519 key pair. The private half is wiped on drop;
/// `Debug` prints the device id only.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct StaticKeypair {
    private: [u8; 32],
    public: [u8; 32],
}

impl StaticKeypair {
    /// A new key pair: 32 bytes from the seed's `"static"` stream; the X25519
    /// scalar multiplication clamps them, as snow's does.
    pub fn generate(seed: Seed) -> Self {
        let mut private = Zeroizing::new([0_u8; 32]);
        seed.derive(labels::STREAM_STATIC)
            .rng()
            .fill_bytes(private.as_mut());
        Self::from_array(&private)
    }

    /// Rebuilds a key pair from the bytes [`Self::private_for_storage`] returned.
    ///
    /// # Errors
    /// [`RelayError::Malformed`] unless `bytes` is exactly 32 bytes long.
    pub fn from_private(bytes: &[u8]) -> Result<Self, RelayError> {
        let array: &[u8; 32] = bytes
            .try_into()
            .map_err(|_| RelayError::Malformed("private key length"))?;
        Ok(Self::from_array(array))
    }

    fn from_array(private: &[u8; 32]) -> Self {
        let public = MontgomeryPoint::mul_base_clamped(*private).to_bytes();
        Self {
            private: *private,
            public,
        }
    }

    /// The X25519 public key.
    pub fn public(&self) -> [u8; 32] {
        self.public
    }

    /// The device id derived from the public key.
    pub fn device_id(&self) -> DeviceId {
        DeviceId::of(&self.public)
    }

    /// The private key, only for the shell's platform wrapper (§5). Never
    /// logged, never sent.
    pub fn private_for_storage(&self) -> Zeroizing<[u8; 32]> {
        Zeroizing::new(self.private)
    }

    /// The private key for snow's builder.
    pub(crate) fn private_bytes(&self) -> &[u8; 32] {
        &self.private
    }

    /// A copy owned by a connection. Crate-internal: the shell never clones a
    /// private key.
    pub(crate) fn duplicate(&self) -> Self {
        Self::from_array(&self.private)
    }
}

impl fmt::Debug for StaticKeypair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StaticKeypair")
            .field("device_id", &self.device_id().to_hex())
            .finish_non_exhaustive()
    }
}

/// `SHA-256("CremaRelay v4 device id" ‖ static_public)[0..16]`. Self-certifying:
/// after a handshake each side checks `DeviceId::of(peer_static)` against the
/// id the peer claimed.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct DeviceId(pub [u8; 16]);

impl DeviceId {
    /// The id of a static public key.
    pub fn of(static_public: &[u8; 32]) -> Self {
        let digest = Sha256::new()
            .chain_update(labels::DEVICE_ID)
            .chain_update(static_public)
            .finalize();
        let mut id = [0_u8; 16];
        id.copy_from_slice(&digest[..16]);
        Self(id)
    }

    /// 32 lowercase hex characters.
    pub fn to_hex(&self) -> String {
        hex_lower(&self.0)
    }

    /// The first 8 hex characters, for UI and the NSD `kid`.
    pub fn short(&self) -> String {
        hex_lower(&self.0[..4])
    }

    /// Parses exactly 32 lowercase hex characters (the only form `to_hex` makes).
    ///
    /// # Errors
    /// [`RelayError::Malformed`] for any other string.
    pub fn from_hex(s: &str) -> Result<Self, RelayError> {
        let bytes: &[u8; 32] = s
            .as_bytes()
            .try_into()
            .map_err(|_| RelayError::Malformed("device id length"))?;
        let (pairs, _) = bytes.as_chunks::<2>();
        let mut id = [0_u8; 16];
        for (out, &[hi, lo]) in id.iter_mut().zip(pairs) {
            let hi = hex_digit(hi).ok_or(RelayError::Malformed("device id hex"))?;
            let lo = hex_digit(lo).ok_or(RelayError::Malformed("device id hex"))?;
            *out = (hi << 4) | lo;
        }
        Ok(Self(id))
    }
}

impl fmt::Debug for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DeviceId({})", self.to_hex())
    }
}

fn hex_digit(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        _ => None,
    }
}

pub(crate) fn hex_lower(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(char::from(DIGITS[usize::from(b >> 4)]));
        s.push(char::from(DIGITS[usize::from(b & 0x0f)]));
    }
    s
}
