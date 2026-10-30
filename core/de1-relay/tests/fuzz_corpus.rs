//! AC-21: the checked-in fuzz seed corpus replays through the same entry
//! points the cargo-fuzz targets use, on stable, in every `cargo test`.

use std::{fs, path::Path};

use de1_relay::fuzzing;

/// A fuzz entry point.
type Target = fn(&[u8]);

#[test]
fn corpus_replays() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("fuzz/corpus");
    let targets: [(&str, Target); 4] = [
        ("fuzz_hub_ws", fuzzing::hub_ws),
        ("fuzz_remote_ws", fuzzing::remote_ws),
        ("fuzz_transport_records", fuzzing::transport_records),
        ("fuzz_client_hello", fuzzing::client_hello),
    ];
    for (name, run) in targets {
        let dir = root.join(name);
        let mut n = 0;
        for entry in fs::read_dir(&dir).unwrap_or_else(|e| panic!("{}: {e}", dir.display())) {
            let path = entry.unwrap().path();
            run(&fs::read(&path).unwrap());
            n += 1;
        }
        assert!(n > 0, "{name} has no corpus");
    }
}

/// The generated seed inputs match the checked-in ones.
#[test]
fn seed_corpus_is_checked_in() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("fuzz/corpus");
    for (target, name, bytes) in fuzzing::seed_corpus() {
        let path = root.join(target).join(&name);
        assert_eq!(
            fs::read(&path).unwrap_or_default(),
            bytes,
            "{}",
            path.display()
        );
    }
}
