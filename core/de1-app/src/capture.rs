//! In-core rolling BLE-capture buffer + slice/META extraction.
//!
//! Every inbound notification routed through [`CremaCore::on_notification`]
//! is teed into a [`CaptureRecorder`] *before* the decode logic. On
//! `ShotCompleted` the shell asks the core for a JSONL slice of the rolling
//! buffer, which the IndexedDB capture store persists under the shot's id —
//! the same JSONL wire format the Android `BleSessionRecorder` and the Rust
//! `examples/replay.rs` tool produce and consume.
//!
//! Owning this in the core (rather than in each shell) means:
//!  - Web and Android share one implementation, byte-for-byte.
//!  - The recorder side-effect rides on the same wasm boundary crossing as
//!    decoding — no extra `recordCapture` call per notification.
//!  - META prelude construction reads the same `last_*` fields the decoders
//!    already update, so it cannot drift from the live snapshot.
//!
//! The on-disk JSONL wire format is unchanged: one
//! `{"t": <ms>, "dir": "in", "src": "<NAME>", "hex": "<lower-hex>"}` object
//! per line, with an optional `META` prelude entry carrying `{"meta": {...}}`
//! instead of `hex`. See `web/src/lib/replay/capture.ts` for the
//! cross-tool contract.

use std::collections::HashMap;

use crate::event::Source;

/// Rolling-buffer cap. Sized for ~8 minutes at ~35 notifications/s — well
/// past any plausible shot. Mirrors the legacy `MAX_ENTRIES` in
/// `web/src/lib/capture/recorder.ts`.
const MAX_ENTRIES: usize = 20_000;
/// Trim the buffer when it exceeds `MAX_ENTRIES + TRIM_SLACK`, so the
/// expensive `drain` runs in batches rather than on every push.
const TRIM_SLACK: usize = 1_000;

/// One captured BLE event — wire-compatible with the JSONL the Kotlin
/// `BleSessionRecorder`, the Rust `replay.rs` tool, and the web shell's
/// `lib/replay/capture` all consume.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RawEntry {
    /// Millisecond timestamp the core was fed (the shell's `now_ms`).
    t_ms: u64,
    /// Which source the bytes came from. Mapped to its canonical capture-file
    /// name on serialisation (`DE1_STATE`, `SCALE_WEIGHT`, …).
    source: Source,
    /// Raw notification bytes.
    data: Vec<u8>,
}

/// Sources whose latest-seen entry is kept indefinitely (separate from the
/// rolling buffer), so a slice taken long after the connect-phase reads still
/// carries the identity bytes the replay tool needs to decode subsequent
/// notifications. Examples: the DE1 firmware Version read (one-shot at
/// connect — never seen again unless reconnect), the StateInfo Read (also
/// one-shot), ShotSettings (one-shot + occasional notify), and every MMR read
/// response (model, serial, GHC info, flush temp, …).
///
/// `SCALE_WEIGHT` is handled separately — we always keep the FIRST one seen
/// (the wire-signature byte pattern lets the replay sniff the scale model),
/// not the most recent. See [`CaptureRecorder::first_scale_weight`].
fn is_identity_source(source: Source) -> bool {
    matches!(
        source,
        Source::De1Version | Source::De1State | Source::De1ShotSettings | Source::De1MmrRead,
    )
}

/// Key under which to track an identity entry's "latest". For everything but
/// MMR, the source itself is the key; MMR replies bucket by the register's
/// 3-byte little-endian address (`data[0..3]`) so a sweep across multiple
/// registers keeps every reply independently. Matches the shell-side
/// `${src}:${data[0]},${data[1]},${data[2]}` key.
fn identity_key(source: Source, data: &[u8]) -> Option<IdentityKey> {
    if !is_identity_source(source) {
        return None;
    }
    if matches!(source, Source::De1MmrRead) && data.len() >= 3 {
        Some(IdentityKey::Mmr {
            addr_lo: data[0],
            addr_mid: data[1],
            addr_hi: data[2],
        })
    } else {
        Some(IdentityKey::Source(source))
    }
}

/// Identity-keeper map key — see [`identity_key`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum IdentityKey {
    Source(Source),
    Mmr {
        addr_lo: u8,
        addr_mid: u8,
        addr_hi: u8,
    },
}

/// The connect-phase identity the META prelude carries, populated by the core
/// as decoded notifications arrive. Each field is `Option` because the read
/// behind it may not have happened yet — META omits any unset field, matching
/// the byte-for-byte shell-side behaviour.
#[derive(Debug, Default, Clone)]
pub(crate) struct MetaSnapshot {
    /// Scale's BLE advertised name (the string handed to `connect_scale`).
    pub scale_name: Option<String>,
    /// DE1's firmware build (MMR `0x800010`, `MmrRegister::FirmwareVersion`).
    pub de1_firmware_version: Option<u32>,
    /// DE1's machine-model identifier (MMR `0x80000C`, `MachineModel`).
    pub de1_machine_model: Option<u32>,
    /// DE1's serial number (MMR `0x803830`, `SerialNumber`).
    pub de1_serial_number: Option<u32>,
}

impl MetaSnapshot {
    /// True when no field has been observed — META prepends only when at
    /// least one identity field is known.
    fn is_empty(&self) -> bool {
        self.scale_name.is_none()
            && self.de1_firmware_version.is_none()
            && self.de1_machine_model.is_none()
            && self.de1_serial_number.is_none()
    }
}

/// Rolling BLE-capture buffer plus the identity-keeper a replay needs to
/// decode bytes recorded long after the connect-phase reads. Owned by
/// [`CremaCore`]; fed inside `on_notification`.
#[derive(Debug, Default)]
pub(crate) struct CaptureRecorder {
    /// Rolling buffer of every recorded entry.
    buf: Vec<RawEntry>,
    /// Latest-seen entry per identity key. Survives the rolling-cap trim.
    identity_latest: HashMap<IdentityKey, RawEntry>,
    /// First `SCALE_WEIGHT` ever recorded — kept so a replay can sniff scale
    /// type by wire-signature even after the rolling buffer has churned past
    /// it. Distinct from the latest-keeper because the replay sniffer needs
    /// the *first* packet, not the most recent.
    first_scale_weight: Option<RawEntry>,
    /// `true` while a replay is feeding the core. Recording is short-circuited
    /// so the replay's own notifications don't get re-recorded.
    suppressed: bool,
}

impl CaptureRecorder {
    /// Append one inbound notification.
    ///
    /// No-op when `suppressed` (replay mode). The rolling buffer is trimmed
    /// in batches so the `drain` cost amortises.
    pub(crate) fn record(&mut self, source: Source, data: &[u8], now_ms: u64) {
        if self.suppressed {
            return;
        }
        let entry = RawEntry {
            t_ms: now_ms,
            source,
            data: data.to_vec(),
        };
        if let Some(key) = identity_key(source, data) {
            self.identity_latest.insert(key, entry.clone());
        }
        if matches!(source, Source::ScaleWeight) && self.first_scale_weight.is_none() {
            self.first_scale_weight = Some(entry.clone());
        }
        self.buf.push(entry);
        // Trim in batches so `Vec::drain` doesn't run on every push.
        if self.buf.len() > MAX_ENTRIES + TRIM_SLACK {
            let excess = self.buf.len() - MAX_ENTRIES;
            self.buf.drain(..excess);
        }
    }

    /// Every entry whose `t_ms` is in `[from_ms, to_ms]`, in chronological
    /// order — plus any identity entries (latest connect-phase reads + first
    /// scale weight) older than `from_ms`, prepended with timestamps tucked
    /// just before `from_ms` so they replay first.
    pub(crate) fn slice(&self, from_ms: u64, to_ms: u64) -> Vec<RawEntry> {
        let in_window: Vec<RawEntry> = self
            .buf
            .iter()
            .filter(|e| e.t_ms >= from_ms && e.t_ms <= to_ms)
            .cloned()
            .collect();
        // The in-window dedupe key matches the shell-side
        // `${src}|${hex}` — so an identity entry that already appears
        // inside the window isn't prepended again.
        let in_window_keys: std::collections::HashSet<(Source, Vec<u8>)> = in_window
            .iter()
            .map(|e| (e.source, e.data.clone()))
            .collect();
        let mut candidates: Vec<RawEntry> = Vec::new();
        for entry in self.identity_latest.values() {
            if entry.t_ms < from_ms && !in_window_keys.contains(&(entry.source, entry.data.clone()))
            {
                candidates.push(entry.clone());
            }
        }
        if let Some(first) = &self.first_scale_weight
            && first.t_ms < from_ms
            && !in_window_keys.contains(&(first.source, first.data.clone()))
        {
            candidates.push(first.clone());
        }
        // Keep their original relative order — just bump timestamps so they
        // land before the window. Spacing 1 ms apart preserves chronology;
        // matches the shell's `fromT - (candidates.length - i)` arithmetic.
        candidates.sort_by_key(|e| e.t_ms);
        // `Vec::len()` is `usize`; on every target we run on (wasm32 and
        // 64-bit native) it fits a `u64`. `try_from` keeps clippy quiet about
        // the implied cast without needing an `#[allow]`.
        let n = u64::try_from(candidates.len()).unwrap_or(u64::MAX);
        let mut prelude: Vec<RawEntry> = Vec::with_capacity(candidates.len());
        for (i, mut entry) in candidates.into_iter().enumerate() {
            let i_u64 = u64::try_from(i).unwrap_or(u64::MAX);
            // `from_ms - n + i` — the same arithmetic the shell ran.
            entry.t_ms = from_ms.saturating_sub(n.saturating_sub(i_u64));
            prelude.push(entry);
        }
        prelude.extend(in_window);
        prelude
    }

    /// Drop every entry — used on disconnect / a full session reset.
    pub(crate) fn clear(&mut self) {
        self.buf.clear();
        self.identity_latest.clear();
        self.first_scale_weight = None;
    }

    /// `true` to silence recording during a capture replay.
    pub(crate) fn set_suppressed(&mut self, on: bool) {
        self.suppressed = on;
    }
}

/// Canonical capture-file name for a `Source` — the inverse of the shell's
/// `sourceFromName` in `lib/replay/capture.ts`, so a recorded capture
/// round-trips cleanly through the replay tool. Same names the Android
/// `BleSessionRecorder` and `examples/replay.rs` use.
fn source_name(source: Source) -> &'static str {
    match source {
        Source::De1State => "DE1_STATE",
        Source::De1ShotSample => "DE1_SHOT_SAMPLE",
        Source::De1WaterLevels => "DE1_WATER_LEVELS",
        Source::De1Version => "DE1_VERSION",
        Source::De1MmrRead => "DE1_MMR_READ",
        Source::De1Calibration => "DE1_CALIBRATION",
        Source::De1ShotSettings => "DE1_SHOT_SETTINGS",
        Source::De1ProfileHeader => "DE1_PROFILE_HEADER",
        Source::De1FrameAck => "DE1_FRAME_ACK",
        Source::ScaleWeight => "SCALE_WEIGHT",
        Source::ScaleCommand => "SCALE_FF12",
    }
}

/// Encode `data` as a no-separator lowercase hex string. Matches the
/// shell's `toHex` byte-for-byte.
fn to_hex(data: &[u8]) -> String {
    const HEX_DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(data.len() * 2);
    for byte in data {
        out.push(char::from(HEX_DIGITS[usize::from(byte >> 4)]));
        out.push(char::from(HEX_DIGITS[usize::from(byte & 0x0f)]));
    }
    out
}

/// JSON-escape a string for the META payload. The fields the META prelude
/// carries today (scale advertised names, integer-stringified MMR values)
/// don't contain control characters, but the encoder still handles `"`, `\`,
/// and the C0 controls so a malformed scale name can't break the JSONL.
fn json_escape_into(out: &mut String, s: &str) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{08}' => out.push_str("\\b"),
            '\u{0C}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 => {
                use std::fmt::Write;
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Serialise one `RawEntry` to a single JSONL line: a
/// `{"t":<n>,"dir":"in","src":"<NAME>","hex":"<hex>"}` object.
fn entry_to_jsonl(entry: &RawEntry, out: &mut String) {
    use std::fmt::Write;
    out.push_str("{\"t\":");
    let _ = write!(out, "{}", entry.t_ms);
    out.push_str(",\"dir\":\"in\",\"src\":\"");
    out.push_str(source_name(entry.source));
    out.push_str("\",\"hex\":\"");
    out.push_str(&to_hex(&entry.data));
    out.push_str("\"}");
}

/// Build the META prelude JSONL line, or `None` if every META field is unset
/// (so we don't emit an empty `{"meta":{}}` blob that the replay would
/// silently treat as noise).
fn meta_to_jsonl(t_ms: u64, meta: &MetaSnapshot) -> Option<String> {
    if meta.is_empty() {
        return None;
    }
    use std::fmt::Write;
    let mut out = String::new();
    let _ = write!(
        out,
        "{{\"t\":{t_ms},\"dir\":\"in\",\"src\":\"META\",\"hex\":\"\",\"meta\":{{"
    );
    let mut first = true;
    let mut sep = |s: &mut String| {
        if first {
            first = false;
        } else {
            s.push(',');
        }
    };
    if let Some(name) = &meta.scale_name {
        sep(&mut out);
        out.push_str("\"scaleName\":");
        json_escape_into(&mut out, name);
    }
    if let Some(v) = meta.de1_firmware_version {
        sep(&mut out);
        let _ = write!(out, "\"de1FirmwareVersion\":{v}");
    }
    if let Some(v) = meta.de1_machine_model {
        sep(&mut out);
        let _ = write!(out, "\"de1MachineModel\":{v}");
    }
    if let Some(v) = meta.de1_serial_number {
        sep(&mut out);
        let _ = write!(out, "\"de1SerialNumber\":{v}");
    }
    out.push_str("}}");
    Some(out)
}

/// Build the full JSONL slice — the META prelude (if any META field is set)
/// followed by every entry from [`CaptureRecorder::slice`], one JSON object
/// per line, trailing newline. Public-by-crate so [`CremaCore`] can wrap it.
pub(crate) fn slice_to_jsonl(
    recorder: &CaptureRecorder,
    meta: &MetaSnapshot,
    from_ms: u64,
    to_ms: u64,
) -> String {
    let entries = recorder.slice(from_ms, to_ms);
    // META timestamp matches the shell's behaviour: one ms before the first
    // entry, falling back to `from_ms` when the slice is empty.
    let meta_t = entries
        .first()
        .map_or(from_ms, |e| e.t_ms.saturating_sub(1));
    let mut out = String::new();
    if let Some(meta_line) = meta_to_jsonl(meta_t, meta) {
        out.push_str(&meta_line);
        out.push('\n');
    }
    for entry in &entries {
        entry_to_jsonl(entry, &mut out);
        out.push('\n');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// One parsed line, the shape the replay parser produces.
    #[derive(Debug, serde::Deserialize)]
    struct ParsedLine {
        t: u64,
        dir: String,
        src: String,
        #[serde(default)]
        hex: String,
        #[serde(default)]
        meta: Option<serde_json::Value>,
    }

    fn parse_jsonl(s: &str) -> Vec<ParsedLine> {
        s.lines()
            .filter(|l| !l.is_empty())
            .map(|l| serde_json::from_str::<ParsedLine>(l).expect("valid JSONL line"))
            .collect()
    }

    #[test]
    fn record_and_slice_round_trip_byte_for_byte() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        rec.record(Source::De1ShotSample, &[0x01, 0x02, 0x03], 200);
        rec.record(Source::ScaleWeight, &[0xff, 0xee, 0xdd], 300);

        let json = slice_to_jsonl(&rec, &MetaSnapshot::default(), 0, 500);
        let parsed = parse_jsonl(&json);
        assert_eq!(parsed.len(), 3);
        assert_eq!(parsed[0].t, 100);
        assert_eq!(parsed[0].dir, "in");
        assert_eq!(parsed[0].src, "DE1_STATE");
        assert_eq!(parsed[0].hex, "0405");
        assert_eq!(parsed[1].src, "DE1_SHOT_SAMPLE");
        assert_eq!(parsed[1].hex, "010203");
        assert_eq!(parsed[2].src, "SCALE_WEIGHT");
        assert_eq!(parsed[2].hex, "ffeedd");
    }

    #[test]
    fn identity_prepend_for_de1_version_outside_window() {
        let mut rec = CaptureRecorder::default();
        // A connect-phase DE1_VERSION at t=0, then 100 unrelated samples
        // well after — slicing the late window must still prepend the
        // version so a replay can decode the bytes that follow.
        rec.record(Source::De1Version, &[0xaa, 0xbb], 0);
        for i in 0..100 {
            rec.record(Source::De1ShotSample, &[i, 0, 0], 10 + u64::from(i));
        }
        let entries = rec.slice(50, 200);
        // The DE1_VERSION is prepended ahead of the window.
        let first = entries.first().expect("at least one entry");
        assert_eq!(first.source, Source::De1Version);
        assert_eq!(first.data, vec![0xaa, 0xbb]);
        assert!(
            first.t_ms < 50,
            "prepended version landed before the window"
        );
        // Everything after the version is in-window.
        for entry in &entries[1..] {
            assert!(entry.t_ms >= 50 && entry.t_ms <= 200);
        }
    }

    #[test]
    fn first_scale_weight_is_prepended_when_outside_window() {
        let mut rec = CaptureRecorder::default();
        // First weight at t=10 — well before the slice window. Subsequent
        // weights inside the window should not displace it from the
        // identity keeper (the keeper holds the FIRST, not the latest).
        rec.record(Source::ScaleWeight, &[0x03, 0x0b, 0x01], 10);
        rec.record(Source::ScaleWeight, &[0x03, 0x0b, 0x99], 200);
        rec.record(Source::ScaleWeight, &[0x03, 0x0b, 0xaa], 300);
        let entries = rec.slice(150, 400);
        let first = entries.first().expect("at least one entry");
        assert_eq!(first.source, Source::ScaleWeight);
        // The FIRST weight (t=10, payload 03 0b 01), not the latest, was
        // prepended.
        assert_eq!(first.data, vec![0x03, 0x0b, 0x01]);
        assert!(first.t_ms < 150);
    }

    #[test]
    fn mmr_per_register_tracking() {
        let mut rec = CaptureRecorder::default();
        // Two MMR replies for different register addresses — both must
        // survive the rolling-buffer dropoff and re-emerge as identity
        // entries for any future slice.
        let machine_model = vec![0x0c, 0x00, 0x80, 0x04, 0x01, 0, 0, 0];
        let serial_number = vec![0x30, 0x38, 0x80, 0x04, 0x42, 0, 0, 0];
        rec.record(Source::De1MmrRead, &machine_model, 0);
        rec.record(Source::De1MmrRead, &serial_number, 1);
        // Fill the buffer with a bunch of late shot samples to slice past.
        for i in 0..50 {
            rec.record(Source::De1ShotSample, &[i, 0, 0], 1000 + u64::from(i));
        }
        let entries = rec.slice(1000, 2000);
        // Both MMR replies must be prepended.
        let mmr_entries: Vec<&RawEntry> = entries
            .iter()
            .filter(|e| matches!(e.source, Source::De1MmrRead))
            .collect();
        assert_eq!(mmr_entries.len(), 2, "both MMR replies survived");
        let data_set: std::collections::HashSet<Vec<u8>> =
            mmr_entries.iter().map(|e| e.data.clone()).collect();
        assert!(data_set.contains(&machine_model));
        assert!(data_set.contains(&serial_number));
    }

    #[test]
    fn meta_prelude_carries_scale_name() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        let meta = MetaSnapshot {
            scale_name: Some("BOOKOO_SC".to_owned()),
            ..MetaSnapshot::default()
        };
        let json = slice_to_jsonl(&rec, &meta, 50, 200);
        let parsed = parse_jsonl(&json);
        assert!(parsed.len() >= 2, "META prelude plus the in-window entry");
        let first = &parsed[0];
        assert_eq!(first.src, "META");
        let meta_v = first.meta.as_ref().expect("META carries a payload");
        assert_eq!(meta_v["scaleName"], "BOOKOO_SC");
    }

    #[test]
    fn meta_prelude_omitted_when_every_field_is_unset() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        let json = slice_to_jsonl(&rec, &MetaSnapshot::default(), 0, 200);
        let parsed = parse_jsonl(&json);
        // Just the one in-window entry — no META prelude.
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].src, "DE1_STATE");
    }

    #[test]
    fn meta_prelude_carries_every_set_field() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        let meta = MetaSnapshot {
            scale_name: Some("BOOKOO_SC".to_owned()),
            de1_firmware_version: Some(1352),
            de1_machine_model: Some(4),
            de1_serial_number: Some(98_765),
        };
        let json = slice_to_jsonl(&rec, &meta, 0, 200);
        let parsed = parse_jsonl(&json);
        let meta_v = parsed[0].meta.as_ref().expect("META carries a payload");
        assert_eq!(meta_v["scaleName"], "BOOKOO_SC");
        assert_eq!(meta_v["de1FirmwareVersion"], 1352);
        assert_eq!(meta_v["de1MachineModel"], 4);
        assert_eq!(meta_v["de1SerialNumber"], 98_765);
    }

    #[test]
    fn replay_suppression_drops_recordings() {
        let mut rec = CaptureRecorder::default();
        rec.set_suppressed(true);
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        rec.record(Source::De1ShotSample, &[0x01, 0x02], 200);
        let entries = rec.slice(0, 1000);
        assert!(entries.is_empty(), "suppressed records are dropped");
        // Disabling suppression resumes recording.
        rec.set_suppressed(false);
        rec.record(Source::De1State, &[0x06, 0x07], 300);
        let entries = rec.slice(0, 1000);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].t_ms, 300);
    }

    #[test]
    fn clear_drops_buffer_identity_and_first_weight() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1Version, &[0xaa, 0xbb], 0);
        rec.record(Source::ScaleWeight, &[0x03, 0x0b, 0x01], 10);
        rec.record(Source::De1ShotSample, &[0x01, 0x02], 100);
        rec.clear();
        let entries = rec.slice(0, 1000);
        assert!(
            entries.is_empty(),
            "every kind of stored state is gone after clear",
        );
    }

    #[test]
    fn rolling_trim_keeps_recent_entries_and_identity_survives() {
        let mut rec = CaptureRecorder::default();
        // One identity entry well before the trim window — must survive.
        rec.record(Source::De1Version, &[0xaa, 0xbb], 0);
        // Flood the buffer past MAX_ENTRIES + TRIM_SLACK.
        let total: u64 =
            u64::try_from(MAX_ENTRIES).unwrap() + u64::try_from(TRIM_SLACK).unwrap() + 5;
        for i in 1..=total {
            let byte = u8::try_from(i & 0xff).unwrap_or(0);
            rec.record(Source::De1ShotSample, &[byte], 100 + i);
        }
        // The recent slice must still get the identity prepend.
        let entries = rec.slice(100 + total - 10, 100 + total);
        assert!(
            entries
                .iter()
                .any(|e| matches!(e.source, Source::De1Version)),
            "identity entry survives rolling trim",
        );
    }

    #[test]
    fn jsonl_has_trailing_newline_and_no_blank_lines() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        let json = slice_to_jsonl(&rec, &MetaSnapshot::default(), 0, 200);
        assert!(json.ends_with('\n'));
        // No double-newline anywhere in the body.
        assert!(!json.contains("\n\n"));
    }

    #[test]
    fn json_escape_handles_special_chars_in_scale_name() {
        let mut rec = CaptureRecorder::default();
        rec.record(Source::De1State, &[0x04, 0x05], 100);
        let meta = MetaSnapshot {
            scale_name: Some("Name with \"quotes\" and \\backslash".to_owned()),
            ..MetaSnapshot::default()
        };
        let json = slice_to_jsonl(&rec, &meta, 0, 200);
        // Parses without error and round-trips the value.
        let parsed = parse_jsonl(&json);
        let meta_v = parsed[0].meta.as_ref().unwrap();
        assert_eq!(meta_v["scaleName"], "Name with \"quotes\" and \\backslash");
    }
}
