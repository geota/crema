//! Property tests for the relayed-command codec.

use de1_relay_app::{
    Decoded, QcField, RelayCommand as C, decode_command, encode_command, jvm, refusal,
};
use proptest::prelude::*;

/// Text with the characters the rules care about: ASCII, separators the
/// wire uses (`\n`, `,`, `:`), Kotlin-only whitespace, controls, format
/// characters and astral ones.
fn text() -> impl Strategy<Value = String> {
    prop::collection::vec(
        prop_oneof![
            8 => prop::char::range('a', 'z'),
            2 => Just(' '),
            1 => prop::sample::select(vec![
                '\n', ',', ':', '\u{1C}', '\u{85}', '\u{A0}', '\u{200E}', '\u{202E}', '\u{FEFF}',
                '⅓', 'é', '😀', '\u{E0001}', '0', '9', '.',
            ]),
            1 => any::<char>(),
        ],
        0..40,
    )
    .prop_map(|cs| cs.into_iter().collect())
}

fn opt_text() -> impl Strategy<Value = Option<String>> {
    prop::option::of(text())
}

/// A brew-override number: mostly in or near the physical ranges, sometimes
/// any double at all.
fn number() -> impl Strategy<Value = f64> {
    prop_oneof![
        4 => 0.0..120.0_f64,
        1 => any::<f64>(),
        1 => Just(0.0),
        1 => Just(-0.0),
    ]
}

fn qc_field() -> impl Strategy<Value = QcField> {
    prop::sample::select(vec![
        QcField::SteamTime,
        QcField::SteamFlow,
        QcField::SteamTemp,
        QcField::HotWaterTemp,
        QcField::HotWaterVolume,
        QcField::FlushTime,
        QcField::FlushTemp,
    ])
}

fn command() -> impl Strategy<Value = C> {
    prop_oneof![
        prop::sample::select(vec![
            C::StartShot,
            C::Stop,
            C::Wake,
            C::Sleep,
            C::StartSteam,
            C::StartHotWater,
            C::StartFlush,
            C::StartSteamRinse,
            C::StartDescale,
            C::StartClean,
            C::CancelMaintenance,
            C::TareScale,
            C::ResetBrew,
        ]),
        text().prop_map(|id| C::SetActiveProfile { id }),
        opt_text().prop_map(|id| C::SetActiveBean { id }),
        (opt_text(), opt_text()).prop_map(|(id, name)| C::SetActiveGrinder { id, name }),
        any::<bool>().prop_map(|on| C::SetStopOnWeight { on }),
        any::<bool>().prop_map(|on| C::SetAutoTare { on }),
        (qc_field(), any::<f32>()).prop_map(|(field, value)| C::SetQc { field, value }),
        text().prop_map(|grind| C::SetGrind { grind }),
        (number(), number(), number(), prop::option::of(number())).prop_map(
            |(dose, yield_out, brew_temp, preinf)| C::AdjustBrew {
                dose,
                yield_out,
                brew_temp,
                preinf,
            }
        ),
        (opt_text(), any::<bool>())
            .prop_map(|(copy_name, as_new)| C::QuickSave { copy_name, as_new }),
    ]
}

proptest! {
    /// A command a sender may build (no refusal) and send (a wire form)
    /// decodes back to itself. Two forms are shared: Wake sends Stop's, and
    /// "no grinder" sends no name whatever it holds.
    #[test]
    fn sendable_commands_round_trip(cmd in command()) {
        if refusal(&cmd).is_none() && let Some(wire) = encode_command(&cmd) {
            let back = decode_command(&wire.method, &wire.args);
            let expected = match &cmd {
                C::Wake => C::Stop,
                C::SetActiveGrinder { id: None, .. } => C::SetActiveGrinder { id: None, name: None },
                other => other.clone(),
            };
            prop_assert_eq!(back, Decoded::Valid(expected), "{:?} via {:?}", cmd, wire);
        }
    }

    /// Anything decodes without a panic; what's accepted passes the
    /// refusal rules and re-encodes to a form that decodes the same way.
    #[test]
    fn any_control_decodes(method in prop_oneof![
        3 => prop::sample::select(de1_relay_app::METHODS.to_vec()).prop_map(str::to_owned),
        1 => text(),
    ], args in text()) {
        if let Decoded::Valid(cmd) = decode_command(&method, &args) {
            prop_assert_eq!(refusal(&cmd), None);
            let wire = encode_command(&cmd).expect("an accepted command has a wire form");
            prop_assert_eq!(decode_command(&wire.method, &wire.args), Decoded::Valid(cmd));
        }
    }

    /// Java's Double.toString text parses back to the same double.
    #[test]
    fn doubles_print_and_parse_back(bits in any::<u64>()) {
        let v = f64::from_bits(bits);
        let back = jvm::parse_double(&jvm::double_to_string(v)).expect("parses");
        if v.is_nan() {
            prop_assert!(back.is_nan());
        } else {
            prop_assert_eq!(back.to_bits(), v.to_bits());
        }
    }

    /// Floats print with their own shortest digits, which parse back to them.
    #[test]
    fn floats_print_and_parse_back(bits in any::<u32>()) {
        let v = f32::from_bits(bits);
        let text = jvm::float_to_string(v);
        let back: f32 = jvm::parse_double(&text).expect("parses") as f32;
        if v.is_nan() {
            prop_assert!(back.is_nan());
        } else {
            prop_assert_eq!(back.to_bits(), v.to_bits(), "{}", text);
        }
    }
}
