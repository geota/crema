//! `gen-builtin-profiles` — regenerate `profiles/builtin.json` from de1app's
//! `*.tcl` profiles.
//!
//! This is the procedure `profiles/README.md` describes, as a tool: every
//! input `*.tcl` file is run through
//! [`import_legacy_tcl`](de1_domain::import_legacy_tcl), the files are taken in
//! file-name order, and the resulting `Vec<Profile>` is written with
//! `serde_json::to_string_pretty` plus a trailing newline.
//!
//! Each built-in's stable ID is **preserved**: a regenerated profile takes the
//! ID of the existing `builtin.json` entry with the same title. A title that is
//! new mints a fresh UUID v7 (the same as `gen-builtin-ids`).
//!
//! Arguments are `*.tcl` files or directories (whose `*.tcl` files are all
//! taken). Files are ordered by file name across all arguments, so an extra
//! file (e.g. an older revision of a profile kept alongside its successor)
//! slots in by its name.
//!
//! ```text
//! cd core && cargo run -p de1-domain --bin gen-builtin-profiles -- \
//!     ../../crema-design/de1app/de1plus/profiles  [extra.tcl ...]
//! ```
//!
//! `--out <path>` writes somewhere other than `profiles/builtin.json`
//! (handy for diffing before committing).

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use de1_domain::{Profile, import_legacy_tcl, new_profile_id};

/// Where `builtin.json` lives relative to the crate manifest.
const BUILTIN_PATH: &str = "profiles/builtin.json";

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("gen-builtin-profiles: {e}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let manifest_dir = env!("CARGO_MANIFEST_DIR");
    let builtin: PathBuf = [manifest_dir, BUILTIN_PATH].iter().collect();

    let mut out = builtin.clone();
    let mut inputs: Vec<PathBuf> = Vec::new();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--out" {
            out = PathBuf::from(args.next().ok_or("--out needs a path")?);
        } else {
            inputs.push(PathBuf::from(arg));
        }
    }
    if inputs.is_empty() {
        return Err("usage: gen-builtin-profiles [--out PATH] <dir-or-tcl>...".to_string());
    }

    let mut files = Vec::new();
    for input in &inputs {
        collect_tcl(input, &mut files)?;
    }
    files.sort_by(|a, b| a.file_name().cmp(&b.file_name()));

    // Existing IDs keyed by title, so every built-in keeps its stable ID.
    let existing: Vec<Profile> = std::fs::read_to_string(&builtin)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    let ids: HashMap<String, String> = existing.into_iter().map(|p| (p.title, p.id)).collect();

    let mut profiles = Vec::with_capacity(files.len());
    let mut minted = Vec::new();
    for file in &files {
        let text = std::fs::read_to_string(file)
            .map_err(|e| format!("cannot read {}: {e}", file.display()))?;
        let mut profile =
            import_legacy_tcl(&text).map_err(|e| format!("{}: {e}", file.display()))?;
        profile.id = match ids.get(&profile.title) {
            Some(id) if !id.is_empty() => id.clone(),
            _ => {
                minted.push(profile.title.clone());
                new_profile_id()
            }
        };
        profiles.push(profile);
    }

    let mut output = serde_json::to_string_pretty(&profiles)
        .map_err(|e| format!("cannot serialize profiles: {e}"))?;
    output.push('\n');
    std::fs::write(&out, output).map_err(|e| format!("cannot write {}: {e}", out.display()))?;

    eprintln!(
        "gen-builtin-profiles: wrote {} profile(s) to {}; new ID(s) for {:?}",
        profiles.len(),
        out.display(),
        minted,
    );
    Ok(())
}

/// Push `path` (a `.tcl` file) or every `.tcl` file in `path` (a directory).
fn collect_tcl(path: &Path, files: &mut Vec<PathBuf>) -> Result<(), String> {
    if path.is_dir() {
        let entries =
            std::fs::read_dir(path).map_err(|e| format!("cannot list {}: {e}", path.display()))?;
        for entry in entries {
            let p = entry.map_err(|e| e.to_string())?.path();
            if p.extension().is_some_and(|x| x == "tcl") {
                files.push(p);
            }
        }
        Ok(())
    } else if path.is_file() {
        files.push(path.to_path_buf());
        Ok(())
    } else {
        Err(format!("no such file or directory: {}", path.display()))
    }
}
