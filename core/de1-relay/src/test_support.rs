//! Deterministic hooks for vectors, tests and fuzzing (feature `test-seeds`).
//! Never enabled for a release artifact.

use sha2::{Digest, Sha256};

use crate::{
    RelayResolver, Seed, labels,
    noise::{self, Pattern},
    pake::Psk,
};

/// `seed(label, n) = SHA-256("de1-relay test seed " ‖ label ‖ " " ‖ n)` (§3.7),
/// `n` in decimal.
pub fn test_seed(label: &str, n: u32) -> Seed {
    let digest = Sha256::new()
        .chain_update(labels::TEST_SEED)
        .chain_update(label.as_bytes())
        .chain_update(b" ")
        .chain_update(n.to_string().as_bytes())
        .finalize();
    let mut seed = [0_u8; 32];
    seed.copy_from_slice(&digest);
    Seed(seed)
}

/// Builds a pairing (`XXpsk3`, psk at 3) or resume (`IK`) handshake through the
/// same builder and resolver production uses.
///
/// # Errors
/// snow's error, e.g. for a missing remote key on an IK initiator.
pub fn build_handshake(
    pairing: bool,
    initiator: bool,
    local_private: &[u8; 32],
    remote_public: Option<&[u8; 32]>,
    prologue: &[u8],
    psk: Option<&Psk>,
    resolver: RelayResolver,
) -> Result<snow::HandshakeState, snow::Error> {
    let pattern = if pairing {
        Pattern::Pairing
    } else {
        Pattern::Resume
    };
    noise::build(
        pattern,
        initiator,
        local_private,
        remote_public,
        prologue,
        psk,
        resolver,
    )
}

/// The sub-seed a connection derives for `label` (for the secrets tests).
pub fn sub_seed(seed: &Seed, label: &[u8]) -> crate::SubSeed {
    seed.derive(label)
}

/// A psk from raw bytes (the cacophony vectors give their own).
pub fn psk_from_bytes(bytes: [u8; 32]) -> Psk {
    Psk::from_bytes_for_tests(bytes)
}

/// The pairing prologue (§2.4 step 6).
pub fn pair_prologue(
    client_hello: &[u8],
    server_hello: &[u8],
    pake_a: &[u8],
    pake_b: &[u8],
) -> Vec<u8> {
    noise::pair_prologue(client_hello, server_hello, pake_a, pake_b)
}

/// The resume prologue (§2.6).
pub fn resume_prologue(client_hello: &[u8], server_hello: &[u8]) -> Vec<u8> {
    noise::resume_prologue(client_hello, server_hello)
}
