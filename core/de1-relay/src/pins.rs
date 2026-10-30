//! The hub's live pin set (§2.6, §3.2) and the low-order key check (§2.3).

use curve25519_dalek::montgomery::MontgomeryPoint;
use subtle::{Choice, ConstantTimeEq};

use crate::RelayError;

/// Any clamped scalar works: clamping makes it a multiple of the cofactor 8.
const PROBE_SCALAR: [u8; 32] = [0x5a; 32];

/// Whether an X25519 public key is a low-order point: one whose DH with any
/// clamped private key is all zero. That covers the five low-order
/// u-coordinates (0, 1, p − 1 and the two of order 8), their non-canonical
/// encodings (p, p + 1) and the same seven with bit 255 set, plus the twist's
/// points of order 2 and 4 — exactly the keys the resolver's DH check refuses.
pub fn is_low_order(key: &[u8; 32]) -> bool {
    MontgomeryPoint(*key).mul_clamped(PROBE_SCALAR).to_bytes() == [0_u8; 32]
}

/// The hub's live set of pinned remote static keys, shared by every
/// `HubConnection` (as `Arc<RwLock<PinSet>>`) and updated by the shell: on
/// pairing before `confirm_paired()`, on Forget before the file write.
/// Connections read it when IK message 1 arrives and again at admission.
#[derive(Clone, Debug, Default)]
pub struct PinSet {
    keys: Vec<[u8; 32]>,
}

impl PinSet {
    /// A pin set holding `keys` (duplicates collapse).
    ///
    /// # Errors
    /// [`RelayError::LowOrderKey`] if any key is low-order.
    pub fn new(keys: Vec<[u8; 32]>) -> Result<Self, RelayError> {
        let mut set = Self::default();
        for key in keys {
            set.insert(key)?;
        }
        Ok(set)
    }

    /// Pins a key. Returns `true` if it was newly pinned and `false` if it was
    /// already pinned (a re-pair). The pairing rollback rule depends on it:
    /// after a failed `confirm_paired` the shell removes the key only if this
    /// returned `true`, and otherwise restores the old pin record.
    ///
    /// # Errors
    /// [`RelayError::LowOrderKey`] if the key is low-order; nothing changes.
    pub fn insert(&mut self, key: [u8; 32]) -> Result<bool, RelayError> {
        if is_low_order(&key) {
            return Err(RelayError::LowOrderKey);
        }
        if self.contains(&key) {
            return Ok(false);
        }
        self.keys.push(key);
        Ok(true)
    }

    /// Unpins a key (a no-op if it isn't pinned).
    pub fn remove(&mut self, key: &[u8; 32]) {
        self.keys.retain(|k| !bool::from(k.ct_eq(key)));
    }

    /// Whether a key is pinned. Compares every entry in constant time.
    pub fn contains(&self, key: &[u8; 32]) -> bool {
        let mut found = Choice::from(0);
        for k in &self.keys {
            found |= k.ct_eq(key);
        }
        found.into()
    }

    /// Number of pinned keys.
    pub fn len(&self) -> usize {
        self.keys.len()
    }

    /// Whether no key is pinned.
    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }
}
