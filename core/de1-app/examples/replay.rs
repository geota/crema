//! Offline replay of a captured DE1 BLE session.
//!
//! Reads a capture file recorded on an Android device by `BleSessionRecorder`
//! and feeds every inbound notification through [`CremaCore`] exactly the way
//! the `de1-ffi` bridge's `CremaBridge::on_notification` does — so the core's
//! decode logic can be validated with no DE1 and no Bluetooth.
//!
//! Run it as:
//!
//! ```text
//! cargo run -p de1-app --example replay -- <path-to-capture.jsonl>
//! ```
//!
//! ## Capture format — JSON Lines, one BLE message per line, no header
//!
//! Each line is `{"t": <u64 ms>, "dir": "in"|"out", "src": "<string>", "hex":
//! "<lowercase hex payload>"}`:
//!  - `t`   — millisecond timestamp (Android `SystemClock.elapsedRealtime()`).
//!  - `dir` — `"in"` is a notification received from the DE1; `"out"` is a
//!    command written to it.
//!  - `src` — for `dir:"in"`, the `NotificationSource` enum name (`DE1_STATE`,
//!    `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`); for `dir:"out"`, a short label.
//!  - `hex` — raw bytes, lowercase hex, no separators.
//!
//! The Android recorder (`BleSessionRecorder.kt`) writes the same format; keep
//! the two in sync.
//!
//! Only `dir:"in"` lines are fed through the core; `dir:"out"` lines are
//! printed as context. A final summary reports lines read, events decoded, and
//! decode errors.

use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::Path;
use std::process::ExitCode;

use de1_app::{CremaCore, Event, Source};

/// One parsed capture line — mirrors the JSON Lines schema above.
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

fn main() -> ExitCode {
    let Some(path) = std::env::args().nth(1) else {
        eprintln!("usage: cargo run -p de1-app --example replay -- <path-to-capture.jsonl>");
        return ExitCode::FAILURE;
    };
    match replay(Path::new(&path)) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Read `path` and replay every inbound notification through a fresh
/// [`CremaCore`], printing the events each one yields.
fn replay(path: &Path) -> Result<(), Box<dyn std::error::Error>> {
    let file = File::open(path)?;
    let reader = BufReader::new(file);

    let mut core = CremaCore::new();
    let mut lines_read = 0_usize;
    let mut events_decoded = 0_usize;
    let mut decode_errors = 0_usize;

    for (index, line) in reader.lines().enumerate() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        lines_read += 1;

        let entry: CaptureLine = match serde_json::from_str(&line) {
            Ok(entry) => entry,
            Err(e) => {
                // A malformed capture line is a recorder/file problem, not a
                // core decode error — report it and carry on.
                eprintln!("line {}: skipping unparseable line: {e}", index + 1);
                continue;
            }
        };

        match entry.dir.as_str() {
            "in" => {
                let Some(source) = source_from_name(&entry.src) else {
                    eprintln!(
                        "line {}: skipping inbound line with unknown src \"{}\"",
                        index + 1,
                        entry.src,
                    );
                    continue;
                };
                let data = match decode_hex(&entry.hex) {
                    Ok(data) => data,
                    Err(e) => {
                        eprintln!("line {}: skipping line with bad hex: {e}", index + 1);
                        continue;
                    }
                };
                // Mirror the `de1-ffi` bridge: feed the raw bytes straight to
                // `CremaCore::on_notification` with the capture's timestamp.
                let output = core.on_notification(source, &data, entry.t);
                for event in &output.events {
                    if matches!(event, Event::DecodeError { .. }) {
                        decode_errors += 1;
                    } else {
                        events_decoded += 1;
                    }
                    println!("[{:>10} ms] {event:?}", entry.t);
                }
            }
            "out" => {
                // Outbound writes are context only — the core is not driven by
                // them, so print and move on.
                println!(
                    "[{:>10} ms] (out) {} {} byte(s)",
                    entry.t,
                    entry.src,
                    entry.hex.len() / 2,
                );
            }
            other => {
                eprintln!("line {}: skipping line with unknown dir \"{other}\"", index + 1);
            }
        }
    }

    println!("---");
    println!(
        "{lines_read} line(s) read, {events_decoded} event(s) decoded, \
         {decode_errors} decode error(s)",
    );
    Ok(())
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
