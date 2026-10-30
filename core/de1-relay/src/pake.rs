//! The SPAKE2 wrapper (§2.4 steps 1–5): code generation, the element check
//! the `spake2` crate doesn't do, and the psk derivation.
//!
//! `spake2` 0.4 is Brian Warner's python-spake2 construction on Ed25519 (not
//! RFC 9382): it accepts the identity and non-canonical encodings, does no
//! cofactor multiplication and returns the raw transcript hash. So every
//! received element goes through [`check_element`] first, and the output is
//! only ever used as HKDF input keying material for the psk.

use core::fmt;

use curve25519_dalek::{edwards::CompressedEdwardsY, traits::IsIdentity};
use hkdf::Hkdf;
use rand_core::{CryptoRng, RngCore};
use sha2::{Digest, Sha256};
use spake2::{Ed25519Group, Identity, Password, Spake2};
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::{DeviceId, RelayError, labels, wire::PAKE_LEN};

/// Draws below this bound are uniform modulo 10⁶ (4294 × 10⁶); larger draws are redrawn.
pub const CODE_REJECT_BOUND: u32 = 4_294_000_000;

/// SPAKE2 side bytes.
pub const SIDE_A: u8 = b'A';
/// SPAKE2 side bytes.
pub const SIDE_B: u8 = b'B';

/// A pairing code: six ASCII digits. Wiped on drop; `Debug` never prints it.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct Code([u8; 6]);

impl Code {
    /// The six digits.
    pub fn as_str(&self) -> &str {
        // Only ever built from ASCII digits.
        core::str::from_utf8(&self.0).unwrap_or_else(|_| unreachable!("code is ASCII"))
    }

    /// Parses a hub-side code entry: exactly six ASCII digits.
    ///
    /// # Errors
    /// [`RelayError::CodeFormat`] for anything else.
    pub fn parse(s: &str) -> Result<Self, RelayError> {
        let bytes: [u8; 6] = s
            .as_bytes()
            .try_into()
            .map_err(|_| RelayError::CodeFormat)?;
        if !bytes.iter().all(u8::is_ascii_digit) {
            return Err(RelayError::CodeFormat);
        }
        Ok(Self(bytes))
    }
}

impl fmt::Debug for Code {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Code([redacted])")
    }
}

/// A uniform 6-digit code (§2.4 step 1): draw `u32`s until one is below
/// [`CODE_REJECT_BOUND`], take it modulo 10⁶, zero-pad.
pub fn generate_code(rng: &mut impl RngCore) -> Code {
    let mut v = loop {
        let draw = rng.next_u32();
        if draw < CODE_REJECT_BOUND {
            break draw % 1_000_000;
        }
    };
    let mut digits = [b'0'; 6];
    for d in digits.iter_mut().rev() {
        // v % 10 < 10, so the narrowing is exact.
        *d = b'0' + u8::try_from(v % 10).unwrap_or(0);
        v /= 10;
    }
    Code(digits)
}

/// The check that rejected a SPAKE2 element (§2.4 step 5), in the order they run.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ElementRejection {
    /// Not 33 bytes.
    Length,
    /// The side byte isn't the expected `'A'`/`'B'`.
    Side,
    /// Bytes 1..33 don't decompress to a curve point.
    Decompress,
    /// The point re-encodes differently (y ≥ p).
    NonCanonical,
    /// The point is the identity.
    Identity,
    /// The point has a torsion component.
    Torsion,
}

/// The receiver's check on an inbound SPAKE2 message, before `finish`:
/// length and side byte, decompression, canonical encoding, not the identity,
/// torsion-free. Load-bearing: neither `spake2` nor dalek does the last three.
///
/// # Errors
/// The first check that fails.
pub fn check_element(msg: &[u8], expected_side: u8) -> Result<(), ElementRejection> {
    if msg.len() != PAKE_LEN {
        return Err(ElementRejection::Length);
    }
    if msg[0] != expected_side {
        return Err(ElementRejection::Side);
    }
    let mut encoded = [0_u8; 32];
    encoded.copy_from_slice(&msg[1..]);
    let point = CompressedEdwardsY(encoded)
        .decompress()
        .ok_or(ElementRejection::Decompress)?;
    if point.compress().as_bytes() != &encoded {
        return Err(ElementRejection::NonCanonical);
    }
    if point.is_identity() {
        return Err(ElementRejection::Identity);
    }
    if !point.is_torsion_free() {
        return Err(ElementRejection::Torsion);
    }
    Ok(())
}

/// `id_A = "CremaRelay v4 remote " ‖ remote_device_id`.
pub fn id_remote(remote: &DeviceId) -> Vec<u8> {
    [labels::SPAKE_ID_REMOTE, &remote.0].concat()
}

/// `id_B = "CremaRelay v4 hub " ‖ hub_device_id`.
pub fn id_hub(hub: &DeviceId) -> Vec<u8> {
    [labels::SPAKE_ID_HUB, &hub.0].concat()
}

/// A SPAKE2 run in progress (the `spake2` state; its scalars and password copy
/// are not wiped, see `PROTOCOL.md`).
pub struct SpakeState(Spake2<Ed25519Group>);

impl fmt::Debug for SpakeState {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SpakeState([redacted])")
    }
}

/// Starts side A (the remote): returns the state and message A.
pub fn start_a(
    code: &Code,
    remote: &DeviceId,
    hub: &DeviceId,
    rng: impl RngCore + CryptoRng,
) -> (SpakeState, [u8; PAKE_LEN]) {
    let (state, msg) = Spake2::<Ed25519Group>::start_a_with_rng(
        &Password::new(code.0),
        &Identity::new(&id_remote(remote)),
        &Identity::new(&id_hub(hub)),
        rng,
    );
    (SpakeState(state), to_pake_array(&msg))
}

/// Starts side B (the hub): returns the state and message B.
pub fn start_b(
    code: &Code,
    remote: &DeviceId,
    hub: &DeviceId,
    rng: impl RngCore + CryptoRng,
) -> (SpakeState, [u8; PAKE_LEN]) {
    let (state, msg) = Spake2::<Ed25519Group>::start_b_with_rng(
        &Password::new(code.0),
        &Identity::new(&id_remote(remote)),
        &Identity::new(&id_hub(hub)),
        rng,
    );
    (SpakeState(state), to_pake_array(&msg))
}

fn to_pake_array(msg: &[u8]) -> [u8; PAKE_LEN] {
    let mut out = [0_u8; PAKE_LEN];
    out.copy_from_slice(msg);
    out
}

/// Checks the peer's element, then finishes the run.
///
/// # Errors
/// The element check's rejection; [`ElementRejection::Decompress`] if `spake2`
/// itself refuses (it can't once the check passed).
pub fn finish(
    state: SpakeState,
    peer_msg: &[u8],
    expected_side: u8,
) -> Result<SpakeKey, ElementRejection> {
    check_element(peer_msg, expected_side)?;
    let mut out = state
        .0
        .finish(peer_msg)
        .map_err(|_| ElementRejection::Decompress)?;
    let mut key = [0_u8; 32];
    key.copy_from_slice(&out);
    out.zeroize();
    let spake_key = SpakeKey(key);
    key.zeroize();
    Ok(spake_key)
}

/// The SPAKE2 output (32 bytes). Wiped on drop; `Debug` never prints it.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct SpakeKey([u8; 32]);

impl fmt::Debug for SpakeKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SpakeKey([redacted])")
    }
}

/// The Noise psk (32 bytes). Wiped on drop; `Debug` never prints it.
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct Psk([u8; 32]);

impl Psk {
    pub(crate) fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// The psk bytes, for vectors and tests only.
    #[cfg(feature = "test-seeds")]
    pub fn expose_for_tests(&self) -> [u8; 32] {
        self.0
    }

    /// A psk from raw bytes, for vectors and tests only.
    #[cfg(feature = "test-seeds")]
    pub fn from_bytes_for_tests(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }
}

impl fmt::Debug for Psk {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Psk([redacted])")
    }
}

/// `psk = HKDF-SHA256(salt = SHA-256(pair_prologue), ikm = spake2_key,
/// info = "CremaRelay v4 psk" ‖ remote_device_id ‖ hub_device_id, L = 32)` (§2.4 step 4).
pub fn derive_psk(
    spake_key: &SpakeKey,
    pair_prologue: &[u8],
    remote: &DeviceId,
    hub: &DeviceId,
) -> Psk {
    let salt = Sha256::digest(pair_prologue);
    let info = [labels::PSK, &remote.0, &hub.0].concat();
    let mut okm = [0_u8; 32];
    Hkdf::<Sha256>::new(Some(&salt), &spake_key.0)
        .expand(&info, &mut okm)
        .unwrap_or_else(|_| unreachable!("32-byte HKDF output"));
    let psk = Psk(okm);
    okm.zeroize();
    psk
}
