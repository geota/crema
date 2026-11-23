//! The codec and the refusal rules: what a hub accepts and the exact words it
//! refuses with (the Android shell's MachineCommandCodecTest cases, and the
//! texts its users see).

use de1_relay_app::{
    Decoded, QcField, RelayCommand as C, Wire, decode_command, encode_command, refusal,
};

fn ok(method: &str, args: &str) -> C {
    match decode_command(method, args) {
        Decoded::Valid(cmd) => cmd,
        Decoded::Invalid(why) => panic!("{method}({args:?}) refused: {why}"),
    }
}

fn refused(method: &str, args: &str) -> String {
    match decode_command(method, args) {
        Decoded::Invalid(why) => why,
        Decoded::Valid(cmd) => panic!("{method}({args:?}) accepted as {cmd:?}"),
    }
}

fn w(method: &str, args: &str) -> Option<Wire> {
    Some(Wire {
        method: method.into(),
        args: args.into(),
    })
}

fn s(t: &str) -> Option<String> {
    Some(t.to_owned())
}

#[test]
fn machine_states() {
    assert_eq!(ok("machineState", "ESPRESSO"), C::StartShot);
    assert_eq!(ok("machineState", "IDLE"), C::Stop);
    assert_eq!(ok("machineState", "SLEEP"), C::Sleep);
    assert_eq!(ok("machineState", "STEAM_RINSE"), C::StartSteamRinse);
    assert_eq!(encode_command(&C::Wake), w("machineState", "IDLE"));
    assert_eq!(encode_command(&C::StartShot), w("startShot", ""));
    for bad in ["", "espresso", "SCHEDULED_IDLE", "REFILL"] {
        refused("machineState", bad);
    }
    assert_eq!(
        refused("machineState", &"X".repeat(40)),
        format!("machine state not relayable: {}", "X".repeat(32))
    );
}

#[test]
fn grind_is_text_and_a_number_is_in_range() {
    for text in [
        "14.5",
        "15",
        "0",
        "2.1.3",
        "4⅓",
        "1 turn + 3",
        "14,5",
        "1e3",
        "NaN",
        "Infinity",
    ] {
        assert_eq!(
            ok("setQcGrind", text),
            C::SetGrind { grind: text.into() },
            "{text}"
        );
    }
    assert_eq!(refused("setQcGrind", "-1"), "grind out of range: -1");
    assert_eq!(
        refused("setQcGrind", " 20000 "),
        "grind out of range: 20000"
    );
    assert_eq!(refused("setQcGrind", "  "), "a grind is required");
    assert_eq!(refused("setQcGrind", ""), "a grind is required");
    assert_eq!(refused("setQcGrind", "a\nb"), "malformed grind");
    assert_eq!(refused("setQcGrind", &"x".repeat(33)), "malformed grind");
    ok("setQcGrind", &"x".repeat(32));
    // Padded: accepted (the hub stores it trimmed); a control character anywhere is not.
    ok("setQcGrind", &format!("12{}", " ".repeat(60_000)));
    assert_eq!(
        refused("setQcGrind", &format!("12{}\u{1}", " ".repeat(60_000))),
        "malformed grind"
    );
    assert_eq!(refused("setQcGrind", "\n\u{1F}12\t\r"), "malformed grind");
    for c in (0x202A..=0x202E)
        .chain(0x2066..=0x2069)
        .chain([0x200E, 0x200F, 0xAD, 0xFEFF])
    {
        let c = char::from_u32(c).expect("scalar");
        assert_eq!(
            refused("setQcGrind", &format!("12{c}3")),
            "malformed grind",
            "{c:?}"
        );
    }
    // Kotlin's whitespace: U+001C is trimmed, so nothing is left; U+0085 is a control.
    assert_eq!(refused("setQcGrind", "\u{1C}"), "a grind is required");
    assert_eq!(refused("setQcGrind", "\u{85}"), "malformed grind");
    // A supplementary format character was never a Kotlin `FORMAT` char.
    ok("setQcGrind", "12\u{E0001}");
    // 32 UTF-16 units: 16 emoji fit, 17 don't.
    ok("setQcGrind", &"😀".repeat(16));
    refused("setQcGrind", &"😀".repeat(17));
    assert_eq!(
        refusal(&C::SetGrind {
            grind: "4 1/3".into()
        }),
        None
    );
    assert!(
        refusal(&C::SetGrind {
            grind: "10000,5".into()
        })
        .is_some()
    );
}

#[test]
fn grinder_pick_carries_its_name() {
    let pick = C::SetActiveGrinder {
        id: s("grinder:niche-zero"),
        name: s("Niche Zero"),
    };
    assert_eq!(
        encode_command(&pick),
        w("setActiveGrinder", "grinder:niche-zero\nNiche Zero")
    );
    assert_eq!(
        ok("setActiveGrinder", "grinder:niche-zero\nNiche Zero"),
        pick
    );
    assert_eq!(
        ok("setActiveGrinder", ""),
        C::SetActiveGrinder {
            id: None,
            name: None
        }
    );
    assert_eq!(
        refused("setActiveGrinder", "grinder:niche-zero"),
        "a grinder pick needs its name"
    );
    assert_eq!(
        refused("setActiveGrinder", "grinder:niche-zero\n"),
        "a name is required"
    );
    assert_eq!(
        refused("setActiveGrinder", "grinder:niche-zero\nNiche\nZero"),
        "malformed name"
    );
    assert_eq!(
        refused("setActiveGrinder", "\nNiche Zero"),
        "an id is required"
    );
    assert_eq!(
        refused("setActiveGrinder", "x\n\u{1C}\u{2000}"),
        "a name is required"
    );
}

#[test]
fn quick_save() {
    assert_eq!(
        ok("quickSave", ""),
        C::QuickSave {
            copy_name: None,
            as_new: false
        }
    );
    assert_eq!(
        ok("quickSave", "copy:Blooming 2"),
        C::QuickSave {
            copy_name: s("Blooming 2"),
            as_new: false
        }
    );
    assert_eq!(
        ok("quickSave", "new:Blooming 2"),
        C::QuickSave {
            copy_name: s("Blooming 2"),
            as_new: true
        }
    );
    assert_eq!(
        refused("quickSave", "x"),
        "quickSave takes nothing, copy:<name> or new:<name>"
    );
    assert_eq!(refused("quickSave", "new: "), "a copy needs a name");
    assert_eq!(
        refused("quickSave", &format!("copy:{}", "n".repeat(129))),
        "malformed name"
    );
    assert_eq!(
        refusal(&C::QuickSave {
            copy_name: None,
            as_new: true
        })
        .as_deref(),
        Some("Save as new needs a name")
    );
}

#[test]
fn brew_override() {
    let brew = |dose, yield_out, brew_temp, preinf| C::AdjustBrew {
        dose,
        yield_out,
        brew_temp,
        preinf,
    };
    assert_eq!(
        ok("quickAdjustBrew", "18.0,36.0,93.0,"),
        brew(18.0, 36.0, 93.0, None)
    );
    assert_eq!(
        ok("quickAdjustBrew", "18.0,36.0,93.0,8.0"),
        brew(18.0, 36.0, 93.0, Some(8.0))
    );
    assert_eq!(
        ok("quickAdjustBrew", "18.0,36.0,93.0"),
        brew(18.0, 36.0, 93.0, None)
    );
    assert_eq!(
        ok("quickAdjustBrew", "18,0,93d, 0x1p3 "),
        brew(18.0, 0.0, 93.0, Some(8.0))
    );
    assert_eq!(
        encode_command(&brew(18.0, 36.0, 93.0, Some(8.0))),
        w("quickAdjustBrew", "18.0,36.0,93.0,8.0")
    );
    assert_eq!(
        encode_command(&brew(18.0, 36.0, 93.0, None)),
        w("quickAdjustBrew", "18.0,36.0,93.0,")
    );
    let shape = "quickAdjustBrew needs dose,yield,temp[,preinf]";
    for bad in [
        "18.0,36.0",
        "18,36,93,8,1",
        "18,36,93,abc",
        "18,36,inf,",
        "",
    ] {
        assert_eq!(refused("quickAdjustBrew", bad), shape, "{bad}");
    }
    let range = "brew override out of range";
    for bad in [
        "NaN,36.0,93.0,",
        "18.0,36.0,Infinity,",
        "18.0,36.0,93.0,-1",
        "18.0,36.0,500.0,",
        "4.9,36,93,",
        "18,4.9,93,",
    ] {
        assert_eq!(refused("quickAdjustBrew", bad), range, "{bad}");
    }
}

#[test]
fn flags_ids_and_no_argument_verbs() {
    assert_eq!(
        ok("setStopOnWeight", "true"),
        C::SetStopOnWeight { on: true }
    );
    assert_eq!(
        encode_command(&C::SetAutoTare { on: false }),
        w("setAutoTare", "false")
    );
    assert_eq!(
        refused("setStopOnWeight", "yes"),
        "setStopOnWeight needs true or false"
    );
    assert_eq!(
        refused("setAutoTare", ""),
        "setAutoTare needs true or false"
    );
    ok("setActiveProfile", "builtin:classic");
    assert_eq!(refused("setActiveProfile", ""), "an id is required");
    assert_eq!(
        refused("setActiveProfile", &"x".repeat(200)),
        "malformed id"
    );
    assert_eq!(refused("setActiveProfile", "a\nb"), "malformed id");
    assert_eq!(ok("setActiveBean", ""), C::SetActiveBean { id: None });
    assert_eq!(refused("setActiveBean", "a\u{0}b"), "malformed id");
    for v in ["startShot", "cancelMaintenance", "tareScale", "resetBrew"] {
        ok(v, "");
        assert_eq!(refused(v, "x"), format!("{v} takes no arguments"));
    }
}

#[test]
fn unknown_and_retired_methods() {
    for m in ["handoff", "writeMmr", "setQcSteamTime", "setQcFlushTemp"] {
        assert_eq!(refused(m, "30.0"), format!("unknown relayed control: {m}"));
    }
    assert_eq!(
        refused(&"m".repeat(40), ""),
        format!("unknown relayed control: {}", "m".repeat(32))
    );
}

#[test]
fn machine_settings_are_range_checked_and_never_sent() {
    let qc = |field, value| refusal(&C::SetQc { field, value });
    assert_eq!(
        encode_command(&C::SetQc {
            field: QcField::SteamTime,
            value: 30.0
        }),
        None
    );
    assert_eq!(qc(QcField::SteamTemp, 0.0), None);
    assert_eq!(qc(QcField::SteamTemp, 148.0), None);
    assert_eq!(
        qc(QcField::SteamTemp, 120.0).as_deref(),
        Some("steam temperature out of range: 120.0")
    );
    assert_eq!(
        qc(QcField::SteamTime, 61.0).as_deref(),
        Some("steam time out of range: 61.0")
    );
    assert_eq!(
        qc(QcField::HotWaterVolume, 501.0).as_deref(),
        Some("hot-water volume out of range: 501.0")
    );
    assert_eq!(
        qc(QcField::HotWaterTemp, 98.5).as_deref(),
        Some("hot-water temperature out of range: 98.5")
    );
    assert_eq!(
        qc(QcField::FlushTemp, f32::NAN).as_deref(),
        Some("flush temperature out of range: NaN")
    );
    assert_eq!(qc(QcField::SteamFlow, 1.2), None);
}
