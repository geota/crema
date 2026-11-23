//! The shared frame fixtures (`tests/golden/v4/frames/` and `unknown/`, which
//! the Android shell's FrameGoldenTest reads too), and kotlinx's decode
//! behaviour on hostile input (probed on kotlinx.serialization 1.13).

use std::{collections::BTreeSet, fs, path::Path};

use de1_relay_app::{
    DeviceInfo, FRAME_TYPES, Frame, FrameError, decode_frame, encode_frame, frame::Decoded,
};

fn golden(path: &str) -> String {
    let p = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/golden/v4")
        .join(path);
    fs::read_to_string(&p)
        .unwrap_or_else(|e| panic!("{}: {e}", p.display()))
        .trim_end_matches('\n')
        .to_owned()
}

fn s(t: &str) -> String {
    t.to_owned()
}

/// FrameGoldenTest's samples.
fn samples() -> Vec<(&'static str, Frame)> {
    let addr = s("AA:BB:CC:DD:EE:FF");
    let service = s("0000a000-0000-1000-8000-00805f9b34fb");
    let state_char = s("0000a00e-0000-1000-8000-00805f9b34fb");
    let roster = vec![
        DeviceInfo {
            address: addr.clone(),
            name: s("DE1"),
            kind: s("de1"),
            state: s("CONNECTED"),
        },
        DeviceInfo {
            address: s("11:22:33:44:55:66"),
            name: s("BOOKOO_SC 502158"),
            kind: s("scale"),
            state: s("CONNECTED"),
        },
    ];
    let welcome = |roster, scope: &str| Frame::Welcome {
        primary_name: s("Kitchen tablet"),
        authority: s("primary"),
        roster,
        scope: s(scope),
    };
    vec![
        ("welcome", welcome(roster.clone(), "control")),
        ("welcome.mirror", welcome(vec![], "mirror")),
        ("roster", Frame::Roster { devices: roster }),
        (
            "attach",
            Frame::Attach {
                id: 7,
                address: addr.clone(),
            },
        ),
        (
            "attached",
            Frame::Attached {
                id: 7,
                address: addr.clone(),
                state: s("CONNECTED"),
            },
        ),
        (
            "detach",
            Frame::Detach {
                id: 8,
                address: addr.clone(),
            },
        ),
        (
            "detached",
            Frame::Detached {
                id: 8,
                address: addr.clone(),
            },
        ),
        (
            "read",
            Frame::Read {
                id: 9,
                address: addr.clone(),
                service: service.clone(),
                char: state_char.clone(),
            },
        ),
        (
            "readOk",
            Frame::ReadOk {
                id: 9,
                hex: s("0900"),
            },
        ),
        (
            "readErr",
            Frame::ReadErr {
                id: 9,
                reason: s("read failed"),
            },
        ),
        (
            "write",
            Frame::Write {
                id: 10,
                address: addr.clone(),
                service: service.clone(),
                char: s("0000a002-0000-1000-8000-00805f9b34fb"),
                hex: s("02"),
            },
        ),
        ("writeOk", Frame::WriteOk { id: 10 }),
        (
            "writeErr",
            Frame::WriteErr {
                id: 10,
                reason: s("not-authoritative"),
            },
        ),
        (
            "control",
            Frame::Control {
                id: 11,
                method: s("machineState"),
                args: s("IDLE"),
            },
        ),
        (
            "control.noargs",
            Frame::Control {
                id: 12,
                method: s("tareScale"),
                args: s(""),
            },
        ),
        (
            "controlOk",
            Frame::ControlOk {
                id: 11,
                result: None,
            },
        ),
        (
            "controlOk.bagPick",
            Frame::ControlOk {
                id: 13,
                result: Some(s(
                    r#"{"change":{"grind":{"value":"18"}},"undo":{"grind":{"value":"15"}},"changed":true,"message":"Bag b: Niche Zero at 18"}"#,
                )),
            },
        ),
        (
            "controlErr",
            Frame::ControlErr {
                id: 12,
                reason: s("not authorized"),
            },
        ),
        (
            // Grinder-ble G3b, owner decision 5: a remote's grind dial while
            // the hub follows its grinder.
            "controlErr.grindFollows",
            Frame::ControlErr {
                id: 14,
                reason: s("The grind follows Motto 80 on Kitchen tablet"),
            },
        ),
        (
            "notify",
            Frame::Notify {
                address: addr.clone(),
                service: service.clone(),
                char: s("0000a00d-0000-1000-8000-00805f9b34fb"),
                hex: s("0712deadbeef"),
                t: 123_456,
                src: None,
            },
        ),
        (
            "notify.src",
            Frame::Notify {
                address: addr.clone(),
                service,
                char: state_char,
                hex: s("0200"),
                t: 123_457,
                src: Some(s("DE1_STATE")),
            },
        ),
        (
            "state",
            Frame::State {
                address: addr,
                state: s("DISCONNECTED"),
            },
        ),
        (
            "config",
            Frame::Config {
                json: s(r#"{"activeProfileId":"p-1","stopOnWeight":true}"#),
            },
        ),
        (
            "event",
            Frame::Event {
                text: s("Sam's phone started a shot"),
            },
        ),
    ]
}

#[test]
fn every_sample_encodes_to_its_fixture_and_back() {
    for (name, frame) in samples() {
        let text = golden(&format!("frames/{name}.json"));
        assert_eq!(encode_frame(&frame), text, "{name}");
        assert_eq!(decode_frame(&text), Ok(Decoded::Frame(frame)), "{name}");
    }
}

#[test]
fn every_type_and_fixture_has_a_sample() {
    let names: BTreeSet<_> = samples().iter().map(|(n, _)| *n).collect();
    let types: BTreeSet<_> = names
        .iter()
        .map(|n| n.split('.').next().unwrap_or(n))
        .collect();
    assert_eq!(types, FRAME_TYPES.into_iter().collect());
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/golden/v4/frames");
    let fixtures: BTreeSet<String> = fs::read_dir(dir)
        .expect("frames dir")
        .map(|e| {
            e.expect("entry")
                .path()
                .file_stem()
                .expect("stem")
                .to_string_lossy()
                .into_owned()
        })
        .collect();
    assert_eq!(fixtures, names.iter().map(|n| (*n).to_owned()).collect());
}

#[test]
fn unknown_types_are_skipped_and_unknown_fields_ignored() {
    assert_eq!(
        decode_frame(&golden("unknown/type.json")),
        Ok(Decoded::Unknown(s("pairingQr")))
    );
    assert_eq!(
        decode_frame(&golden("unknown/field.json")),
        Ok(Decoded::Frame(Frame::Event {
            text: s("Sam's phone started a shot")
        }))
    );
}

#[test]
fn a_welcome_needs_its_scope() {
    let text = golden("frames/welcome.json").replace(",\"scope\":\"control\"", "");
    assert!(matches!(decode_frame(&text), Err(FrameError::Malformed(_))));
}

/// What kotlinx's relay decode (`decodeTolerant`) did with each input: the
/// frame, `None` for a skipped type, or an error (the session closes).
#[test]
fn hostile_input_as_kotlinx_read_it() {
    let attach = |id, address: &str| {
        Some(Some(Frame::Attach {
            id,
            address: s(address),
        }))
    };
    let ok_event = Some(Some(Frame::Event { text: s("x") }));
    let cases: Vec<(String, Option<Option<Frame>>)> = vec![
        (
            r#"{"type":"attach","id":"7","address":"a"}"#.into(),
            attach(7, "a"),
        ),
        (
            r#"{"type":"attach","id":1e3,"address":"a"}"#.into(),
            attach(1000, "a"),
        ),
        (r#"{"type":"attach","id":1.5e3,"address":"a"}"#.into(), None),
        (r#"{"type":"attach","id":7.0,"address":"a"}"#.into(), None),
        (
            r#"{"type":"attach","id":-0,"address":"a"}"#.into(),
            attach(0, "a"),
        ),
        (
            r#"{"type":"attach","id":07,"address":"a"}"#.into(),
            attach(7, "a"),
        ),
        (r#"{"type":"attach","id":+7,"address":"a"}"#.into(), None),
        (
            r#"{"type":"attach","id":9223372036854775808,"address":"a"}"#.into(),
            None,
        ),
        (
            r#"{"type":"attach","id":"1e3","address":"a"}"#.into(),
            attach(1000, "a"),
        ),
        (
            r#"{"type":"attach","id":" 7","address":"a"}"#.into(),
            attach(7, "a"),
        ),
        (r#"{"type":"attach","id":true,"address":"a"}"#.into(), None),
        (r#"{"type":"attach","id":null,"address":"a"}"#.into(), None),
        (
            r#"{"type":"attach","id":7,"address":"a","address":"b"}"#.into(),
            attach(7, "b"),
        ),
        (
            r#"{"type":"attach","type":"detach","id":7,"address":"a"}"#.into(),
            Some(Some(Frame::Detach {
                id: 7,
                address: s("a"),
            })),
        ),
        (
            r#"{"id":7,"address":"a","type":"attach"}"#.into(),
            attach(7, "a"),
        ),
        (
            "{\"type\":\"attach\",\"id\":7,\"address\":\"a\nb\"}".into(),
            attach(7, "a\nb"),
        ),
        (
            r#"{"type":"attach","id":7,"address":"a\ud800b"}"#.into(),
            attach(7, "a\u{FFFD}b"),
        ),
        (r#"{"type":"attach","id":7,"address":"a\x"}"#.into(), None),
        (
            r#"{"type":"attach","id":7,"address":"a\/b"}"#.into(),
            attach(7, "a/b"),
        ),
        (r#"{"type":"attach","id":7,"address":5}"#.into(), None),
        (r#"{"type":"attach","id":7,"address":"a",}"#.into(), None),
        (r#" {"type":"attach","id":7,"address":"a"}x"#.into(), None),
        (
            r#"{"type":"control","id":1,"method":"m","args":null}"#.into(),
            None,
        ),
        (
            r#"{"type":"controlOk","id":1,"result":null}"#.into(),
            Some(Some(Frame::ControlOk {
                id: 1,
                result: None,
            })),
        ),
        (r#"{"type":"controlOk","id":1,"result":5}"#.into(), None),
        (
            r#"{"type":"event","text":"x","extra":{"a":[1,2,{"b":null}]}}"#.into(),
            ok_event.clone(),
        ),
        (
            format!(
                r#"{{"type":"event","text":"x","extra":{}{}}}"#,
                "[".repeat(300),
                "]".repeat(300)
            ),
            ok_event.clone(),
        ),
        (
            format!(
                r#"{{"type":"event","text":"x","extra":{}{}}}"#,
                "[".repeat(3000),
                "]".repeat(3000)
            ),
            None,
        ),
        (
            r#"{"type":"event","text":"x","extra":tru}"#.into(),
            ok_event.clone(),
        ),
        (
            r#"{"type":"event","text":"x","extra":NaN}"#.into(),
            ok_event,
        ),
        (r#"{"type":"roster","devices":null}"#.into(), None),
        (
            r#"{"type":"welcome","primaryName":"p","authority":"a","roster":[],"scope":null}"#
                .into(),
            None,
        ),
        (r#"{"type":5,"id":7}"#.into(), None),
        (
            r#"{"type":"Attach","id":7,"address":"a"}"#.into(),
            Some(None),
        ),
        ("[]".into(), None),
        ("{}".into(), None),
        (String::new(), None),
    ];
    for (text, want) in cases {
        let got = match decode_frame(&text) {
            Ok(Decoded::Frame(f)) => Some(Some(f)),
            Ok(Decoded::Unknown(_)) => Some(None),
            Err(_) => None,
        };
        assert_eq!(got, want, "{}", text.chars().take(80).collect::<String>());
    }
    assert_eq!(decode_frame("[]"), Err(FrameError::NotAnObject));
    assert_eq!(decode_frame("{\"type\":5}"), Err(FrameError::NoType));
}
