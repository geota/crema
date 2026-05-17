//! Offline replay of a captured DE1 BLE session.
//!
//! Reads a capture file recorded on an Android device by `BleSessionRecorder`
//! and feeds every inbound notification through [`CremaCore`] exactly the way
//! the `de1-ffi` bridge's `CremaBridge::on_notification` does — so the core's
//! decode logic can be validated with no DE1, no scale, and no Bluetooth.
//!
//! A capture can interleave DE1 and Bookoo-scale traffic in one file; both are
//! replayed through the same [`CremaCore`], so scale-aware behaviour (such as
//! shot-start auto-tare) is reconstructed faithfully.
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
//!  - `src` — for `dir:"in"`, usually a `NotificationSource` enum name
//!    (`DE1_STATE`, `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`, `SCALE_WEIGHT`), but
//!    an arbitrary string is allowed for inbound traffic the core does not
//!    model (e.g. `SCALE_FF12`); for `dir:"out"`, a short label (e.g.
//!    `SCALE_COMMAND` for a tare/timer write).
//!  - `hex` — raw bytes, lowercase hex, no separators.
//!
//! The Android recorder (`BleSessionRecorder.kt`) writes the same format; keep
//! the two in sync.
//!
//! Only `dir:"in"` lines with a `src` the core models are fed through it. A
//! `dir:"in"` line whose `src` is unrecognised (e.g. `SCALE_FF12`, Bookoo
//! command-characteristic notifications captured only for reverse-engineering)
//! is treated as informational and printed like a `dir:"out"` line — never fed
//! to the core. A final summary reports lines read, events decoded, and decode
//! errors.
//!
//! ## Scale captures
//!
//! A live session calls `CremaCore::connect_scale` to pick the scale codec
//! before any weight notification arrives, but a capture file records no
//! connection event. So when the first `SCALE_WEIGHT` line is replayed, this
//! tool connects a Bookoo scale on the core itself — the only scale the Android
//! app supports — so the `SCALE_WEIGHT` bytes decode into `ScaleReading` /
//! `ScaleStale` events just as they did live.

use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::Path;
use std::process::ExitCode;

use de1_app::{CremaCore, Event, Source};
use de1_scale::bookoo;

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
    // A capture records no scale-connection event, so the core has no scale
    // codec selected. Connect a Bookoo scale lazily on the first SCALE_WEIGHT
    // line — see the module comment.
    let mut scale_connected = false;

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
                    // An inbound line whose `src` is not a known
                    // `NotificationSource` is traffic the core does not model
                    // — e.g. `SCALE_FF12`, Bookoo command-characteristic
                    // notifications captured purely for reverse-engineering.
                    // The core knows nothing about it, so do NOT feed it
                    // through `on_notification`; treat it as informational and
                    // print it like a `dir:"out"` line, then carry on.
                    println!(
                        "[{:>10} ms] (in) {} {} byte(s) — unmodelled src, not decoded",
                        entry.t,
                        entry.src,
                        entry.hex.len() / 2,
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
                // The first scale notification stands in for the live
                // `connect_scale` call — without it the core has no codec and
                // silently drops every weight packet.
                if source == Source::ScaleWeight && !scale_connected {
                    core.connect_scale("BOOKOO_SC");
                    scale_connected = true;
                }
                // `Event::ScaleReading` now carries the Bookoo's native flow
                // and timer (`device_flow_g_per_s` / `device_timer_ms`). This
                // extra decode is kept only to surface the raw indicator bytes
                // and checksum status, which the event does not expose.
                if source == Source::ScaleWeight
                    && let Some(p) = bookoo::parse_packet(&data)
                {
                    let csum = if p.checksum_ok { "" } else { " CHECKSUM-BAD" };
                    println!(
                        "[{:>10} ms] BookooPacket {{ weight: {:.2} g, flow: {:.2} g/s \
                         (raw {}), timer: {} ms, w_ind: {:#04x}, f_ind: {:#04x} }}{csum}",
                        entry.t,
                        p.weight_g,
                        p.flow_g_per_s,
                        p.flow_raw,
                        p.timer_ms,
                        p.weight_indicator,
                        p.flow_indicator,
                    );
                }
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
        "SCALE_WEIGHT" => Some(Source::ScaleWeight),
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
