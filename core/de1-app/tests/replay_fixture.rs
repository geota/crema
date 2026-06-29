//! Regression test: replay a recorded real-hardware DE1 BLE session through
//! [`CremaCore`] and pin the decoded events.
//!
//! The fixture `captures/session-20260517-122732-shot-pull.jsonl` (at the repo
//! root) is a genuine session captured on an Android device by
//! `BleSessionRecorder`: a
//! cold-start heat-up, one full espresso shot, and a return to sleep. Replaying
//! it offline exercises the core's whole decode path — `MachineState` /
//! `SubState` parsing, the [`ShotMonitor`] state machine, phase and frame
//! classification, and the shot summary — with no DE1 and no Bluetooth.
//!
//! A decoder regression (a wrong state, phase, frame, event count, or a wrong
//! `ShotCompleted` duration / sample count) makes this test fail.
//!
//! The ~30-line parse loop below is deliberately close to the one in
//! `examples/replay.rs`: both consume the same capture schema. Keeping the test
//! self-contained — rather than reaching into the example or hoisting its logic
//! into the library — means this mild duplication is intentional and accepted.

use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;

use de1_app::{CremaCore, Event, Source};
use de1_domain::ShotPhase;
use de1_protocol::{MachineState, SubState};

/// One parsed capture line — mirrors the JSON Lines schema (see `replay.rs`).
#[derive(serde::Deserialize)]
struct CaptureLine {
    /// Millisecond timestamp.
    t: u64,
    /// `"in"` or `"out"`.
    dir: String,
    /// `NotificationSource` name (inbound) or characteristic label (outbound).
    src: String,
    /// Lowercase hex payload, no separators.
    hex: String,
}

/// Map a capture `src` string — the Kotlin `NotificationSource` enum name — to
/// the core's [`Source`]. Returns `None` for an unrecognised name.
fn source_from_name(name: &str) -> Option<Source> {
    match name {
        "DE1_STATE" => Some(Source::De1State),
        "DE1_SHOT_SAMPLE" => Some(Source::De1ShotSample),
        "DE1_WATER_LEVELS" => Some(Source::De1WaterLevels),
        _ => None,
    }
}

/// Decode a lowercase-hex string with no separators into bytes.
fn decode_hex(hex: &str) -> Result<Vec<u8>, String> {
    if !hex.len().is_multiple_of(2) {
        return Err(format!("odd-length hex string ({} chars)", hex.len()));
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&hex[i..i + 2], 16)
                .map_err(|_| format!("invalid hex digit pair \"{}\"", &hex[i..i + 2]))
        })
        .collect()
}

#[test]
fn recorded_session_decodes_to_pinned_event_sequence() {
    // The fixture lives alongside this test. `CARGO_MANIFEST_DIR` is `core/de1-app`.
    let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/session-20260517-122732-shot-pull.jsonl");

    let file = File::open(&fixture)
        .unwrap_or_else(|e| panic!("opening fixture {}: {e}", fixture.display()));
    let reader = BufReader::new(file);

    let mut core = CremaCore::new();
    let mut events = Vec::new();
    let mut decode_errors = 0_usize;

    // Parse + replay loop — mirrors `examples/replay.rs` (intentional, see the
    // module comment): feed every inbound notification through `CremaCore`
    // exactly the way the `de1-ffi` bridge does.
    for (index, line) in reader.lines().enumerate() {
        let line = line.unwrap_or_else(|e| panic!("reading line {}: {e}", index + 1));
        if line.trim().is_empty() {
            continue;
        }

        let entry: CaptureLine = serde_json::from_str(&line)
            .unwrap_or_else(|e| panic!("line {}: unparseable capture line: {e}", index + 1));

        // Only inbound notifications drive the core; outbound writes are context.
        if entry.dir != "in" {
            continue;
        }

        let source = source_from_name(&entry.src)
            .unwrap_or_else(|| panic!("line {}: unknown src \"{}\"", index + 1, entry.src));
        let data =
            decode_hex(&entry.hex).unwrap_or_else(|e| panic!("line {}: bad hex: {e}", index + 1));

        let output = core.on_notification(source, &data, entry.t);
        for event in output.events {
            if let Event::DecodeError { message } = &event {
                eprintln!("line {}: decode error: {message}", index + 1);
                decode_errors += 1;
            }
            events.push(event);
        }
    }

    assert_eq!(decode_errors, 0, "the recorded session must decode cleanly");
    assert_eq!(events.len(), 1170, "total decoded event count");

    // The per-sample telemetry events are pinned only by count; the
    // non-telemetry events are pinned exactly, in order.
    let non_telemetry: Vec<Event> = events
        .iter()
        .filter(|e| !matches!(e, Event::Telemetry { .. }))
        .cloned()
        .collect();

    // `Event::ShotCompleted` carries derived metrics (peak pressure /
    // temperature; weights are `None` because the capture has no scale
    // source). The peak numerics depend on the smoothed telemetry float
    // values, so the test pins `ShotCompleted` separately by destructuring
    // for `duration` + `sample_count` and asserting the metrics' presence
    // shape, rather than baking specific f32 maxima into the expected vec.
    let stripped: Vec<Event> = non_telemetry
        .iter()
        .cloned()
        .map(|e| match e {
            Event::ShotCompleted {
                duration,
                sample_count,
                ..
            } => Event::ShotCompleted {
                duration,
                sample_count,
                peak_pressure: None,
                peak_temp: None,
                peak_weight: None,
                final_weight: None,
            },
            other => other,
        })
        .collect();

    let expected: Vec<Event> = vec![
        Event::MachineStateChanged {
            state: MachineState::Idle,
            substate: SubState::Heating,
        },
        Event::MachineStateChanged {
            state: MachineState::Idle,
            substate: SubState::Ready,
        },
        Event::MachineStateChanged {
            state: MachineState::Espresso,
            substate: SubState::FinalHeating,
        },
        Event::ShotStarted,
        Event::ShotPhaseChanged {
            phase: ShotPhase::Heating,
        },
        Event::ShotFrameChanged { frame: 3 },
        Event::MachineStateChanged {
            state: MachineState::Espresso,
            substate: SubState::Preinfusion,
        },
        Event::ShotPhaseChanged {
            phase: ShotPhase::Preinfusion,
        },
        Event::ShotFrameChanged { frame: 0 },
        Event::MachineStateChanged {
            state: MachineState::Espresso,
            substate: SubState::Pouring,
        },
        Event::ShotPhaseChanged {
            phase: ShotPhase::Pouring,
        },
        Event::ShotFrameChanged { frame: 1 },
        Event::ShotFrameChanged { frame: 2 },
        Event::ShotFrameChanged { frame: 3 },
        Event::MachineStateChanged {
            state: MachineState::Espresso,
            substate: SubState::Ending,
        },
        Event::ShotPhaseChanged {
            phase: ShotPhase::Ending,
        },
        Event::MachineStateChanged {
            state: MachineState::Idle,
            substate: SubState::Ready,
        },
        Event::ShotPhaseChanged {
            phase: ShotPhase::Idle,
        },
        Event::ShotCompleted {
            duration: 81_074,
            sample_count: 389,
            peak_pressure: None,
            peak_temp: None,
            peak_weight: None,
            final_weight: None,
        },
        Event::MachineStateChanged {
            state: MachineState::Sleep,
            substate: SubState::Ready,
        },
    ];

    assert_eq!(
        stripped, expected,
        "non-telemetry event sequence must match the recorded session",
    );

    // Pin the derived-metrics shape on `ShotCompleted`: the replay carries
    // no scale source so both weight fields are `None`; the DE1 telemetry
    // arrived (389 samples) so both pressure / temperature peaks must be
    // `Some` and within plausible espresso-shot bands. Specific maxima are
    // intentionally not pinned — they're floats produced by the smoothed
    // telemetry path and need not be byte-identical across smoothing
    // tweaks.
    let shot_completed = non_telemetry
        .iter()
        .find(|e| matches!(e, Event::ShotCompleted { .. }))
        .expect("a ShotCompleted event");
    let Event::ShotCompleted {
        peak_pressure,
        peak_temp,
        peak_weight,
        final_weight,
        ..
    } = shot_completed
    else {
        unreachable!("filtered for ShotCompleted");
    };
    let pressure = peak_pressure.expect("telemetry arrived → peak_pressure is Some");
    let temp = peak_temp.expect("telemetry arrived → peak_temp is Some");
    assert!(
        (0.0..=15.0).contains(&pressure),
        "peak_pressure {pressure} bar should be in a plausible espresso range",
    );
    assert!(
        (50.0..=120.0).contains(&temp),
        "peak_temp {temp} °C should be in a plausible espresso range",
    );
    assert!(
        peak_weight.is_none(),
        "no scale source in the capture → peak_weight stays None",
    );
    assert!(
        final_weight.is_none(),
        "no scale source in the capture → final_weight stays None",
    );

    // Cross-check the telemetry split: 1150 telemetry + 20 non-telemetry = 1170.
    assert_eq!(
        events.len() - non_telemetry.len(),
        1150,
        "telemetry event count",
    );
}
