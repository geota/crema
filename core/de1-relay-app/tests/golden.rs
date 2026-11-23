//! The shared control fixtures (`tests/golden/v4/control/`, which the Android
//! shell's ControlVerbGoldenTest reads too): every one decodes to a command
//! that encodes back to exactly its method and arguments, and together they
//! cover every method and machine state a hub accepts.

use std::{collections::BTreeSet, fs, path::Path};

use de1_relay_app::{Decoded, METHODS, decode_command, encode_command, relayable_states};

/// `(fixture name, method, args)` for every control fixture.
fn fixtures() -> Vec<(String, String, String)> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/golden/v4/control");
    let mut out = Vec::new();
    for entry in fs::read_dir(&dir).unwrap_or_else(|e| panic!("{}: {e}", dir.display())) {
        let path = entry.expect("entry").path();
        if path.extension().is_none_or(|e| e != "json") {
            continue;
        }
        let text = fs::read_to_string(&path).expect("read");
        let frame: serde_json::Value = serde_json::from_str(&text).expect("JSON");
        assert_eq!(frame["type"], "control", "{}", path.display());
        let field = |k: &str| frame[k].as_str().unwrap_or_default().to_owned();
        let name = path
            .file_stem()
            .expect("stem")
            .to_string_lossy()
            .into_owned();
        out.push((name, field("method"), field("args")));
    }
    out.sort();
    out
}

#[test]
fn every_fixture_round_trips() {
    let all = fixtures();
    assert_eq!(all.len(), 27, "{all:?}");
    for (name, method, args) in all {
        let Decoded::Valid(cmd) = decode_command(&method, &args) else {
            panic!("{name}: {method}({args:?}) is refused");
        };
        let wire = encode_command(&cmd).unwrap_or_else(|| panic!("{name}: no wire form"));
        // The one alias: an older build's Espresso button starts a shot.
        if (method.as_str(), args.as_str()) == ("machineState", "ESPRESSO") {
            assert_eq!(
                (wire.method.as_str(), wire.args.as_str()),
                ("startShot", "")
            );
            continue;
        }
        assert_eq!(
            (wire.method, wire.args),
            (method, args),
            "{name} re-encodes"
        );
    }
}

#[test]
fn the_fixtures_cover_every_method_and_state() {
    let all = fixtures();
    let methods: BTreeSet<_> = all.iter().map(|(_, m, _)| m.as_str()).collect();
    assert_eq!(methods, METHODS.into_iter().collect());
    let states: BTreeSet<_> = all
        .iter()
        .filter(|(_, m, _)| m == "machineState")
        .map(|(_, _, a)| a.as_str())
        .collect();
    assert_eq!(states, relayable_states().collect());
}
