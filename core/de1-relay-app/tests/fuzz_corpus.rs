//! The checked-in fuzz seed corpus replays through the same entry points the
//! cargo-fuzz targets use, on stable, in every `cargo test`.

use std::{fs, path::Path};

use de1_relay_app::fuzzing;

/// A fuzz entry point.
type Target = fn(&[u8]);

#[test]
fn corpus_replays() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("fuzz/corpus");
    let targets: [(&str, Target); 3] = [
        ("fuzz_decode_command", |d| {
            fuzzing::decode(d);
        }),
        ("fuzz_number", fuzzing::number),
        ("fuzz_decode_frame", fuzzing::frame),
    ];
    for (name, run) in targets {
        let dir = root.join(name);
        let mut n = 0;
        for entry in fs::read_dir(&dir).unwrap_or_else(|e| panic!("{}: {e}", dir.display())) {
            run(&fs::read(entry.expect("entry").path()).expect("read"));
            n += 1;
        }
        assert!(n > 0, "{name} has no corpus");
    }
}
