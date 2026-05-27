//! Profile-ID generation — one canonical scheme for every shell.
//!
//! Every Crema profile carries a stable string ID:
//!
//! - **Built-ins** ship with their ID baked into
//!   `profiles/builtin.json`, generated once by the `gen-builtin-ids`
//!   binary in this crate. The IDs survive every rebuild — once
//!   committed, the JSON entry's `"id"` is left untouched.
//! - **Custom profiles** mint a fresh ID via [`new_profile_id`], called
//!   through the wasm bridge (web) or the UniFFI bridge (Android).
//!
//! IDs are **UUID v7** per RFC 9562 (2024): a 48-bit millisecond
//! timestamp prefix + 74 bits of randomness, formatted as the standard
//! 36-character dashed `xxxxxxxx-xxxx-7xxx-yxxx-xxxxxxxxxxxx`. The
//! timestamp prefix makes IDs **lexicographically sortable by creation
//! time** — handy for logs, history scrolls, and debug grepping — while
//! the 74 random bits keep collision odds astronomical. Every parser
//! that understands UUIDs already understands v7, so the URL space
//! (`/profiles/<id>/edit`) needs no change.

use uuid::Uuid;

/// Generate a fresh profile ID — a UUID v7 in standard 36-character
/// dashed form (e.g. `01910f80-7a3b-7c54-b2d1-23a4f8e9cd00`).
///
/// Used by the wasm + UniFFI bridges to mint IDs for custom profiles.
/// Built-in IDs are pre-generated and live in `profiles/builtin.json`
/// rather than being minted at runtime.
///
/// Each call yields a unique ID: even two back-to-back calls in the
/// same millisecond differ in their 74 random bits, and the timestamp
/// prefix means IDs from later calls sort lexicographically after IDs
/// from earlier calls.
#[must_use]
pub fn new_profile_id() -> String {
    Uuid::now_v7().to_string()
}

/// Mint a fresh stored-shot ID — same UUID v7 scheme as
/// [`new_profile_id`], but with the `shot:` prefix the shell uses to
/// scope shot ids in its history store and routes. Centralising the
/// minter here means every shell (web wasm, Android UniFFI) produces
/// time-ordered, collision-free shot ids without depending on
/// browser-specific APIs like `crypto.randomUUID`.
#[must_use]
pub fn new_shot_id() -> String {
    format!("shot:{}", Uuid::now_v7())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The canonical UUID v7 form — 8-4-4-4-12 lowercase hex, version
    /// nibble `7`, variant nibble in `{8,9,a,b}`. Mirrors the regex the
    /// TS shell tests use.
    const UUID_V7: &str = r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    fn matches_v7(id: &str) -> bool {
        // Hand-rolled tiny matcher to avoid an extra regex dep.
        let bytes = id.as_bytes();
        if bytes.len() != 36 {
            return false;
        }
        // Dash positions in the 8-4-4-4-12 form.
        for &i in &[8, 13, 18, 23] {
            if bytes[i] != b'-' {
                return false;
            }
        }
        // Version nibble at index 14 must be '7'.
        if bytes[14] != b'7' {
            return false;
        }
        // Variant nibble at index 19 must be one of 8/9/a/b.
        if !matches!(bytes[19], b'8' | b'9' | b'a' | b'b') {
            return false;
        }
        // Every other position is lowercase hex.
        for (i, &b) in bytes.iter().enumerate() {
            if i == 8 || i == 13 || i == 18 || i == 23 {
                continue;
            }
            let ok = b.is_ascii_digit() || (b'a'..=b'f').contains(&b);
            if !ok {
                return false;
            }
        }
        true
    }

    #[test]
    fn new_profile_id_matches_uuid_v7() {
        let id = new_profile_id();
        assert!(matches_v7(&id), "{id} should match {UUID_V7}");
    }

    #[test]
    fn new_profile_id_is_unique() {
        // Two successive calls never collide.
        let a = new_profile_id();
        let b = new_profile_id();
        assert_ne!(a, b);
    }

    #[test]
    fn new_profile_id_is_lexicographically_sortable() {
        // Two calls in quick succession must sort by creation time —
        // the v7 timestamp prefix is monotonic and same-ms ties are
        // broken by a fresh random suffix, so `a <= b` always holds.
        let mut prev = new_profile_id();
        for _ in 0..50 {
            let next = new_profile_id();
            assert!(
                prev.as_str() <= next.as_str(),
                "v7 IDs must sort by creation time, but {prev} > {next}",
            );
            prev = next;
        }
    }
}
