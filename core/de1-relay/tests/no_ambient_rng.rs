//! AC-19: `de1-relay` draws no entropy of its own.

use std::{fs, path::Path, process::Command};

/// No OS-entropy API appears anywhere in the crate's sources.
#[test]
fn sources_name_no_ambient_rng() {
    let forbidden = [
        "getrandom",
        "OsRng",
        "thread_rng",
        "from_entropy",
        "rand::rngs",
        "SystemTime",
        "Instant::now",
    ];
    let src = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    let mut checked = 0;
    for entry in fs::read_dir(&src).unwrap() {
        let path = entry.unwrap().path();
        let text = fs::read_to_string(&path).unwrap();
        for word in forbidden {
            assert!(!text.contains(word), "{} mentions {word}", path.display());
        }
        checked += 1;
    }
    assert!(checked >= 14);
}

/// `cargo tree -p de1-relay -e normal` lists no getrandom (nor ring), with and
/// without the test feature.
#[test]
fn dependency_tree_has_no_getrandom() {
    let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    for extra in [&[][..], &["--all-features"][..]] {
        let out = Command::new(&cargo)
            .args([
                "tree",
                "-p",
                "de1-relay",
                "-e",
                "normal",
                "--prefix",
                "none",
                "--offline",
            ])
            .args(extra)
            .current_dir(env!("CARGO_MANIFEST_DIR"))
            .output()
            .unwrap();
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        let tree = String::from_utf8(out.stdout).unwrap();
        assert!(
            tree.contains("snow v0.10.0") && tree.contains("spake2 v0.4.0"),
            "{tree}"
        );
        for line in tree.lines() {
            assert!(
                !line.starts_with("getrandom ") && !line.starts_with("ring "),
                "{line}"
            );
        }
    }
}
