//! Property tests for the frame codec: every frame round-trips through its
//! wire text, and any text decodes without a panic.

use de1_relay_app::{DeviceInfo, Frame, decode_frame, encode_frame, frame::Decoded};
use proptest::prelude::*;

/// Text with what JSON quoting cares about: quotes, backslashes, controls,
/// `/`, non-ASCII and astral characters.
fn text() -> impl Strategy<Value = String> {
    prop::collection::vec(
        prop_oneof![
            6 => prop::char::range('a', 'z'),
            1 => prop::sample::select(vec!['"', '\\', '/', '\n', '\t', '\u{0}', '\u{1F}', '\u{7F}', 'é', '😀', '\u{2028}', '{', ':']),
            1 => any::<char>(),
        ],
        0..24,
    )
    .prop_map(|cs| cs.into_iter().collect())
}

fn device() -> impl Strategy<Value = DeviceInfo> {
    (text(), text(), text(), text()).prop_map(|(address, name, kind, state)| DeviceInfo {
        address,
        name,
        kind,
        state,
    })
}

fn frame() -> impl Strategy<Value = Frame> {
    let id = any::<i64>();
    prop_oneof![
        (
            text(),
            text(),
            prop::collection::vec(device(), 0..3),
            text()
        )
            .prop_map(|(primary_name, authority, roster, scope)| Frame::Welcome {
                primary_name,
                authority,
                roster,
                scope
            }),
        prop::collection::vec(device(), 0..3).prop_map(|devices| Frame::Roster { devices }),
        (id, text()).prop_map(|(id, address)| Frame::Attach { id, address }),
        (id, text(), text()).prop_map(|(id, address, state)| Frame::Attached {
            id,
            address,
            state
        }),
        (id, text(), text(), text()).prop_map(|(id, address, service, char)| Frame::Read {
            id,
            address,
            service,
            char
        }),
        (id, text(), text(), text(), text()).prop_map(|(id, address, service, char, hex)| {
            Frame::Write {
                id,
                address,
                service,
                char,
                hex,
            }
        }),
        id.prop_map(|id| Frame::WriteOk { id }),
        (id, text(), text()).prop_map(|(id, method, args)| Frame::Control { id, method, args }),
        (id, prop::option::of(text())).prop_map(|(id, result)| Frame::ControlOk { id, result }),
        (id, text()).prop_map(|(id, reason)| Frame::ControlErr { id, reason }),
        (text(), text(), text(), text(), id, prop::option::of(text())).prop_map(
            |(address, service, char, hex, t, src)| Frame::Notify {
                address,
                service,
                char,
                hex,
                t,
                src
            }
        ),
        text().prop_map(|json| Frame::Config { json }),
        text().prop_map(|json| Frame::Grinder { json }),
        text().prop_map(|text| Frame::Event { text }),
    ]
}

proptest! {
    #[test]
    fn frames_round_trip(f in frame()) {
        let wire = encode_frame(&f);
        prop_assert_eq!(decode_frame(&wire), Ok(Decoded::Frame(f)), "{}", wire);
    }

    #[test]
    fn any_text_decodes_without_a_panic(t in "\\PC{0,64}|\\{\"type\":\"[a-zA-Z]{0,8}\".{0,48}\\}") {
        let _ = decode_frame(&t);
    }
}
