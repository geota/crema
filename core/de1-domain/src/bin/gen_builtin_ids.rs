//! `gen-builtin-ids` — fill in missing IDs in `profiles/builtin.json`.
//!
//! Each entry in `builtin.json` carries a stable [`Profile::id`](de1_domain::Profile)
//! (a UUID v7) that the shell uses as the profile's permanent identifier
//! — the URL key, the pinned/last-used storage key, the deduplication
//! key. The IDs are committed to the file: they are generated **once**
//! and survive every subsequent rebuild.
//!
//! This binary is the once-runner. It:
//!
//! 1. Loads `core/de1-domain/profiles/builtin.json` into a list of
//!    [`Profile`]s.
//! 2. For each profile whose `id` field is empty, mints a fresh
//!    UUID v7 via [`new_profile_id`](de1_domain::new_profile_id).
//! 3. Leaves profiles that already carry an ID untouched —
//!    **idempotency**: a second run is a no-op (zero diff).
//! 4. Writes the list back as `serde_json::to_string_pretty`, with a
//!    trailing newline to match the existing file's shape.
//!
//! Run with:
//!
//! ```text
//! cd core && cargo run -p de1-domain --bin gen-builtin-ids
//! ```
//!
//! The first run populates every entry's `id`; commit the file. A second
//! run leaves the file byte-identical, which the project's gate scripts
//! depend on.

use std::path::PathBuf;
use std::process::ExitCode;

use de1_domain::{Profile, new_profile_id};

/// Where `builtin.json` lives relative to the crate manifest. Resolved
/// at compile time via `CARGO_MANIFEST_DIR` so the binary runs from any
/// cwd (top-level `cargo run -p de1-domain --bin gen-builtin-ids`).
const BUILTIN_PATH: &str = "profiles/builtin.json";

fn main() -> ExitCode {
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    let path: PathBuf = [manifest_dir, BUILTIN_PATH].iter().collect();

    let input = match std::fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("gen-builtin-ids: cannot read {}: {e}", path.display());
            return ExitCode::FAILURE;
        }
    };

    let mut profiles: Vec<Profile> = match serde_json::from_str(&input) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("gen-builtin-ids: cannot parse {}: {e}", path.display());
            return ExitCode::FAILURE;
        }
    };

    let mut filled = 0usize;
    let mut already = 0usize;
    for profile in &mut profiles {
        if profile.id.is_empty() {
            profile.id = new_profile_id();
            filled += 1;
        } else {
            already += 1;
        }
    }

    // Pretty-print with 2-space indent — matches the existing
    // `builtin.json` style (see `profiles/README.md`).
    let mut output = match serde_json::to_string_pretty(&profiles) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("gen-builtin-ids: cannot serialize profiles: {e}");
            return ExitCode::FAILURE;
        }
    };
    // The existing file ends with a trailing newline; preserve that.
    output.push('\n');

    if output == input {
        eprintln!(
            "gen-builtin-ids: {} profile(s) already have an ID; no changes (file is byte-identical).",
            already,
        );
        return ExitCode::SUCCESS;
    }

    if let Err(e) = std::fs::write(&path, &output) {
        eprintln!("gen-builtin-ids: cannot write {}: {e}", path.display());
        return ExitCode::FAILURE;
    }

    eprintln!(
        "gen-builtin-ids: wrote {}: filled {} new ID(s), {} already had one.",
        path.display(),
        filled,
        already,
    );
    ExitCode::SUCCESS
}
