//! BOOKOO Motto 80 (MT80): custom GATT service, fragment frame v1, JSON
//! messages.
//!
//! The wire handling follows two working implementations, and nothing here
//! is guessed:
//! - BOOKOO's official protocol and client: `BooKooCode/OpenSource`
//!   `bookoo_motto80/README.md` and `bookoo_motto80/mt80_gatt_client.py` at
//!   `76458a1` (MIT, Copyright (c) 2024 DGSSL BOOKOO TECH LTD.). The UUIDs,
//!   the 8-byte frame header, [`encode`] (`encode_fragments`),
//!   [`FrameReassembler`] (the official `FrameReassembler`, rule for rule),
//!   the `appHello` handshake written with response in 20-byte packets, and
//!   readiness (a `periodInfo` within 5 s of the handshake).
//! - Sabotage1's decaid driver, `Sabotage1/decaid-bookoo-motto80` v0.1.0 at
//!   `6a09335` (`bookoo-motto80.reaplugin/plugin.js`, GPL-3.0-or-later,
//!   Copyright (C) 2026 Roy Ackerman): the `BOOKOO MT80 ` name prefix, the
//!   firmware gate (`releaseVer >= 102190820`, checked before `periodInfo`
//!   in the same message), the field ranges, the 5 s packet timeout, strict
//!   UTF-8, upper-cased `devState` matching, and ignoring notifications that
//!   arrive before the handshake. Where it drops a whole `periodInfo` for one
//!   out-of-range number, Crema drops only that field (the official client
//!   accepts any `periodInfo` object): a grinder reporting `grindRpm: 0` at
//!   idle still becomes ready.
//!
//! Never read: Sofronio's driver (no licence) and GaggiMate's (CC BY-NC-SA).

use de1_device::{FirmwareVersion, LinkVerdict, LinkWatch};
use serde_json::{Map, Value};

use crate::driver::{GrinderDiagnostics, GrinderEvent, GrinderLinkDeadReason, UnsupportedReason};
use crate::reading::{
    GrinderInfo, GrinderReading, GrinderSettings, GrinderState, ObservedSetting, PresetSlot,
};

/// The grinder catalogue's id for the Motto 80 (`de1_domain::grinder_catalogue`).
pub const MODEL: &str = "bookoo-motto-80";
/// Advertised names are `BOOKOO MT80 xxxxxxxx` (official §4.1; Sabotage1's
/// match, case-insensitive). The custom service is not advertised.
pub const NAME_PREFIX: &str = "BOOKOO MT80 ";
/// The custom GATT service (official §4.2).
pub const SERVICE: &str = "4d543830-0001-4b80-8f00-424f4f4b4f4f";
/// RX, client to device: Write and Write Without Response; write with
/// response (official §4.2 and the client's `response=True`).
pub const RX: &str = "4d543830-0002-4b80-8f00-424f4f4b4f4f";
/// TX, device to client: Notify.
pub const TX: &str = "4d543830-0003-4b80-8f00-424f4f4b4f4f";

const FRAME_MAGIC: u8 = 0xA5;
const FRAME_VERSION: u8 = 0x01;
/// `struct.Struct("<BBHHH")`: magic, version, sequence, total length, offset.
pub const HEADER_LEN: usize = 8;
/// A logical message is 1..=4096 bytes (official `MESSAGE_MAX_LEN`).
pub const MESSAGE_MAX_LEN: usize = 4096;
/// The largest fragment packet (official §5.1: `min(MTU - 3, 512)`).
pub const MAX_PACKET: usize = 512;
/// The packet size that fits the default ATT MTU of 23 (official
/// `SAFE_WRITE_PACKET_LEN`; Sabotage1 writes the same).
pub const SAFE_PACKET: usize = 20;
/// An incomplete message expires after 2 s between fragments
/// (`REASSEMBLY_TIMEOUT_SECONDS`).
const FRAGMENT_EXPIRY_MS: u64 = 2_000;
/// The first `periodInfo` must arrive within 5 s of the handshake
/// (`HANDSHAKE_TIMEOUT_SECONDS`).
const FIRST_READING_MS: u64 = 5_000;
/// 5 s without a valid `periodInfo` ends the session (Sabotage1's default
/// `PacketTimeoutMs`; the grinder streams at 5 Hz).
const SILENT_MS: u64 = 5_000;
/// Malformed frames in a row before the link counts as dead (about 1 s of
/// garbage at MTU 23).
const MALFORMED_LIMIT: u32 = 20;
/// The oldest firmware with this protocol, v1.2.19.0820 (official README;
/// Sabotage1 refuses older).
pub const MIN_RELEASE: i64 = 102_190_820;
/// The handshake, compact (official §5.2 and §7.1).
const HANDSHAKE: &[u8] = br#"{"request":{"appHello":{"op":"handshake"}}}"#;
/// Longest `devState` accepted (Sabotage1).
const MAX_STATE_LEN: usize = 80;
/// Longest serial kept; the official limit is 32 UTF-8 bytes.
const MAX_SERIAL_LEN: usize = 32;
/// The largest integer JSON's numbers carry exactly (JS `MAX_SAFE_INTEGER`).
const MAX_SAFE_INTEGER: i64 = (1 << 53) - 1;

/// Split one logical JSON message into fragment frames of at most
/// `max_packet` bytes, every one carrying the same `seq` and total length
/// (official `encode_fragments`). `max_packet` is clamped to 9..=512. A
/// message outside 1..=4096 bytes encodes to nothing.
#[must_use]
pub fn encode(message: &[u8], seq: u16, max_packet: usize) -> Vec<Vec<u8>> {
    let Ok(total) = u16::try_from(message.len()) else {
        return Vec::new();
    };
    if message.is_empty() || message.len() > MESSAGE_MAX_LEN {
        return Vec::new();
    }
    let capacity = max_packet.clamp(HEADER_LEN + 1, MAX_PACKET) - HEADER_LEN;
    message
        .chunks(capacity)
        .enumerate()
        .map(|(i, chunk)| {
            // i * capacity < message.len() <= 4096, so it fits a u16.
            let offset = u16::try_from(i * capacity).unwrap_or(u16::MAX);
            let mut frame = Vec::with_capacity(HEADER_LEN + chunk.len());
            frame.push(FRAME_MAGIC);
            frame.push(FRAME_VERSION);
            frame.extend_from_slice(&seq.to_le_bytes());
            frame.extend_from_slice(&total.to_le_bytes());
            frame.extend_from_slice(&offset.to_le_bytes());
            frame.extend_from_slice(chunk);
            frame
        })
        .collect()
}

/// Why [`FrameReassembler::feed`] rejected a frame. Each one also drops any
/// partial message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameError {
    /// The frame is 8 bytes or shorter.
    NoPayload,
    /// Not magic `0xA5` version `0x01`.
    BadMagicOrVersion,
    /// The total length is outside 1..=4096.
    BadLength,
    /// The offset or the fragment runs past the total length.
    OutOfBounds,
    /// A continuation that doesn't follow the partial message.
    OutOfOrder,
}

/// One frame's header.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Header {
    seq: u16,
    total: u16,
    offset: u16,
}

fn header(packet: &[u8]) -> Option<(u8, u8, Header)> {
    let field = |i: usize| Some(u16::from_le_bytes([*packet.get(i)?, *packet.get(i + 1)?]));
    Some((
        *packet.first()?,
        *packet.get(1)?,
        Header {
            seq: field(2)?,
            total: field(4)?,
            offset: field(6)?,
        },
    ))
}

/// Reassembles TX notifications into complete messages, rule for rule as the
/// official `FrameReassembler`: one message at a time, `offset == 0` always
/// starts over, each continuation must follow exactly, more than 2 s between
/// fragments drops the partial, and any bad frame resets.
#[derive(Debug, Clone, Default)]
pub struct FrameReassembler {
    seq: Option<u16>,
    total: u16,
    payload: Vec<u8>,
    last_at: Option<u64>,
}

impl FrameReassembler {
    fn reset(&mut self) {
        self.seq = None;
        self.total = 0;
        self.payload.clear();
        self.last_at = None;
    }

    /// Feed one notification. `Ok(Some(message))` when it completes one.
    ///
    /// # Errors
    ///
    /// A [`FrameError`] for a frame the official reassembler rejects.
    pub fn feed(&mut self, packet: &[u8], now_ms: u64) -> Result<Option<Vec<u8>>, FrameError> {
        if self
            .last_at
            .is_some_and(|last| now_ms.saturating_sub(last) > FRAGMENT_EXPIRY_MS)
        {
            self.reset();
        }
        if packet.len() <= HEADER_LEN {
            self.reset();
            return Err(FrameError::NoPayload);
        }
        let Some((magic, version, h)) = header(packet) else {
            self.reset();
            return Err(FrameError::NoPayload);
        };
        let fragment = &packet[HEADER_LEN..];
        if magic != FRAME_MAGIC || version != FRAME_VERSION {
            self.reset();
            return Err(FrameError::BadMagicOrVersion);
        }
        let total = usize::from(h.total);
        let offset = usize::from(h.offset);
        if !(1..=MESSAGE_MAX_LEN).contains(&total) {
            self.reset();
            return Err(FrameError::BadLength);
        }
        if offset >= total || fragment.len() > total - offset {
            self.reset();
            return Err(FrameError::OutOfBounds);
        }
        if offset == 0 {
            self.seq = Some(h.seq);
            self.total = h.total;
            self.payload.clear();
        } else if self.seq != Some(h.seq) || h.total != self.total || offset != self.payload.len() {
            self.reset();
            return Err(FrameError::OutOfOrder);
        }
        self.payload.extend_from_slice(fragment);
        self.last_at = Some(now_ms);
        if self.payload.len() != total {
            return Ok(None);
        }
        let complete = std::mem::take(&mut self.payload);
        self.reset();
        Ok(Some(complete))
    }
}

/// Decode a `releaseVer` (`M|mm|pp|bbbb`: `102190820` is v1.2.19.0820).
/// `None` outside 0..=2147483647 (Sabotage1's accepted range).
#[must_use]
pub fn firmware_from_release(release: i64) -> Option<FirmwareVersion> {
    let release = u32::try_from(release)
        .ok()
        .filter(|r| i32::try_from(*r).is_ok())?;
    Some(FirmwareVersion::new(
        release / 100_000_000,
        release / 1_000_000 % 100,
        release / 10_000 % 100,
        release % 10_000,
    ))
}

/// The firmware this protocol needs.
fn min_firmware() -> FirmwareVersion {
    firmware_from_release(MIN_RELEASE).unwrap_or(FirmwareVersion::new(1, 2, 19, 820))
}

/// A `devState` string, upper-cased (Sabotage1 matches `toUpperCase()`). The
/// table is official §7.3.1 plus its history: `SHAKING` and `Reverse` (the
/// 2026-09-02 table), the pre-1.3.11 spellings `HighSpeedClean` and
/// `BootGuide`, and Sabotage1's `HIGH_SPEED_CLEAN`.
#[must_use]
pub fn state_from(raw: &str) -> GrinderState {
    match raw.to_ascii_uppercase().as_str() {
        "IDLE" => GrinderState::Idle,
        "GRINDING" => GrinderState::Grinding,
        "HIGHSPEEDCLEAN" | "HIGH_SPEED_CLEAN" | "HIGHSPEED_CLEAN" | "ADJUSTMENTPURGE"
        | "REVERSE" => GrinderState::Purging,
        "SETTING" | "BOOTGUIDE" | "SHAKING" => GrinderState::Busy,
        "UPDATING" => GrinderState::Updating,
        "WARNING" => GrinderState::Warning,
        _ => GrinderState::Unknown,
    }
}

/// A JSON integer, as JavaScript's `Number.isInteger` sees one (so `500.0`
/// counts), within the range JSON numbers carry exactly.
fn integer(value: &Value) -> Option<i64> {
    if let Some(i) = value.as_i64() {
        return (i.abs() <= MAX_SAFE_INTEGER).then_some(i);
    }
    let f = value.as_f64()?;
    // A float that is a whole number within ±2^53 converts exactly.
    #[allow(clippy::cast_possible_truncation)]
    let i = f as i64;
    (f.fract() == 0.0 && i.abs() <= MAX_SAFE_INTEGER).then_some(i)
}

/// Field readers over one JSON object that count values present but unusable.
struct Fields<'a> {
    object: &'a Map<String, Value>,
    rejected: u32,
}

impl Fields<'_> {
    fn int(&mut self, key: &str, min: i64, max: i64) -> Option<i64> {
        let value = self.object.get(key)?;
        let found = integer(value).filter(|v| (min..=max).contains(v));
        if found.is_none() {
            self.rejected += 1;
        }
        found
    }

    fn uint<T: TryFrom<i64>>(&mut self, key: &str, min: i64, max: i64) -> Option<T> {
        self.int(key, min, max).and_then(|v| T::try_from(v).ok())
    }

    fn bool(&mut self, key: &str) -> Option<bool> {
        let value = self.object.get(key)?;
        let found = value.as_bool();
        if found.is_none() {
            self.rejected += 1;
        }
        found
    }
}

/// Decode a `periodInfo` object (official §7.3) received at `at_ms`. `None`
/// unless `devState` is a non-empty string of at most 80 bytes; any other
/// field that is missing, mistyped or out of range is left out and counted
/// in the returned number.
fn decode_period(period: &Map<String, Value>, at_ms: u64) -> Option<(GrinderReading, u32)> {
    let raw_state = period
        .get("devState")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty() && s.len() <= MAX_STATE_LEN)?;
    let mut f = Fields {
        object: period,
        rejected: 0,
    };
    // Ranges: `geneSetting` §8.1 (bladeGap 0–999, grindRpm 500–1500,
    // feedingRpm 10–65, brightness 1–5, standbySec 60–900, selectPreset
    // −1–9), humidity 0–100 and totalGrinds ≥ 0 as Sabotage1 checks them.
    let setting = f.int("bladeGap", 0, 999).map(|gap| ObservedSetting {
        value: gap.to_string(),
        number: u16::try_from(gap).ok().map(f32::from),
    });
    let grind_rpm = f.uint("grindRpm", 500, 1_500);
    let feed_rpm = f.uint("feedingRpm", 10, 65);
    let humidity_pct = f.uint("humidity", 0, 100);
    let total_grinds = f.uint("totalGrinds", 0, MAX_SAFE_INTEGER);
    let preset = f.int("selectPreset", -1, 9).map(|p| match u8::try_from(p) {
        Ok(index) => PresetSlot::Slot { index },
        Err(_) => PresetSlot::Manual,
    });
    let settings = GrinderSettings {
        cup_detect: f.bool("cupDetect"),
        auto_stop: f.bool("autoStop"),
        fast_clean: f.bool("fastClean"),
        brightness: f.uint("brightness", 1, 5),
        standby_s: f.uint("standbySec", 60, 900),
        wifi_connected: match period.get("netState").and_then(Value::as_str) {
            Some(s) if s.eq_ignore_ascii_case("CONNECTED") => Some(true),
            Some(s) if s.eq_ignore_ascii_case("DISCONNECTED") => Some(false),
            _ => None,
        },
    };
    let reading = GrinderReading {
        at_ms,
        state: state_from(raw_state),
        raw_state: raw_state.to_owned(),
        setting,
        grind_rpm,
        feed_rpm,
        humidity_pct,
        total_grinds,
        preset,
        settings,
    };
    Some((reading, f.rejected))
}

/// Parse a complete message: strict UTF-8, then a JSON object.
fn parse(message: &[u8]) -> Option<Map<String, Value>> {
    let text = std::str::from_utf8(message).ok()?;
    match serde_json::from_str(text).ok()? {
        Value::Object(object) => Some(object),
        _ => None,
    }
}

/// One connection to a Motto 80. Rebuilt for every connect: every disconnect
/// clears the subscription and the handshake (official §4.3).
#[derive(Debug, Clone)]
pub(crate) struct Motto80 {
    reassembler: FrameReassembler,
    capture: CaptureShaper,
    link: LinkWatch,
    handshake_sent: bool,
    next_seq: u16,
    malformed_in_row: u32,
    firmware: Option<FirmwareVersion>,
    serial: Option<String>,
    stopped: bool,
    diagnostics: GrinderDiagnostics,
}

impl Motto80 {
    pub(crate) fn new() -> Self {
        Motto80 {
            reassembler: FrameReassembler::default(),
            capture: CaptureShaper::default(),
            link: LinkWatch::new(FIRST_READING_MS, SILENT_MS),
            handshake_sent: false,
            // The official client's default sequence for the handshake.
            next_seq: 1,
            malformed_in_row: 0,
            firmware: None,
            serial: None,
            stopped: false,
            diagnostics: GrinderDiagnostics::default(),
        }
    }

    pub(crate) fn info(&self) -> GrinderInfo {
        GrinderInfo {
            model: MODEL.to_owned(),
            firmware: self.firmware.map(|v| v.to_string()),
            serial: self.serial.clone(),
        }
    }

    pub(crate) fn diagnostics(&self) -> GrinderDiagnostics {
        self.diagnostics
    }

    /// The handshake frames, to write with response in order after
    /// subscribing to TX. Starts the 5 s readiness deadline.
    pub(crate) fn connect_writes(&mut self, max_packet: usize, now_ms: u64) -> Vec<Vec<u8>> {
        let seq = self.next_seq;
        self.next_seq = self.next_seq.wrapping_add(1);
        self.handshake_sent = true;
        self.link.start(now_ms);
        encode(HANDSHAKE, seq, max_packet)
    }

    fn stop(&mut self) {
        self.stopped = true;
        self.link.finish();
        self.reassembler = FrameReassembler::default();
    }

    fn poll_link(&mut self, now_ms: u64, events: &mut Vec<GrinderEvent>) {
        if let Some(verdict) = self.link.poll(now_ms) {
            let reason = match verdict {
                LinkVerdict::NoFirstReading => GrinderLinkDeadReason::NoFirstReading,
                LinkVerdict::Silent => GrinderLinkDeadReason::Silent,
            };
            self.stop();
            events.push(GrinderEvent::LinkDead { reason });
        }
    }

    pub(crate) fn tick(&mut self, now_ms: u64) -> Vec<GrinderEvent> {
        let mut events = Vec::new();
        if !self.stopped {
            self.poll_link(now_ms, &mut events);
        }
        events
    }

    pub(crate) fn on_notification(&mut self, bytes: &[u8], now_ms: u64) -> Vec<GrinderEvent> {
        let mut events = Vec::new();
        // Sabotage1 ignores everything until it has sent the handshake.
        if self.stopped || !self.handshake_sent {
            return events;
        }
        self.poll_link(now_ms, &mut events);
        if self.stopped {
            return events;
        }
        match self.reassembler.feed(bytes, now_ms) {
            Err(_) => {
                self.diagnostics.malformed_frames =
                    self.diagnostics.malformed_frames.saturating_add(1);
                self.malformed_in_row += 1;
                if self.malformed_in_row >= MALFORMED_LIMIT {
                    self.stop();
                    events.push(GrinderEvent::LinkDead {
                        reason: GrinderLinkDeadReason::Malformed,
                    });
                }
            }
            Ok(None) => self.malformed_in_row = 0,
            Ok(Some(message)) => {
                self.malformed_in_row = 0;
                self.on_message(&message, now_ms, &mut events);
            }
        }
        events
    }

    fn on_message(&mut self, message: &[u8], now_ms: u64, events: &mut Vec<GrinderEvent>) {
        let Some(document) = parse(message) else {
            self.diagnostics.unreadable_messages =
                self.diagnostics.unreadable_messages.saturating_add(1);
            return;
        };
        // `response` is ignored: Crema sends no requests but the handshake.
        let Some(broadcast) = document.get("broadcast").and_then(Value::as_object) else {
            return;
        };
        if let Some(base) = broadcast.get("baseInfo").and_then(Value::as_object) {
            let before = self.info();
            if let Some(version) = base
                .get("releaseVer")
                .and_then(integer)
                .and_then(firmware_from_release)
            {
                self.firmware = Some(version);
                if version < min_firmware() {
                    self.stop();
                    events.push(GrinderEvent::Unsupported {
                        reason: UnsupportedReason::FirmwareTooOld {
                            found: version.to_string(),
                            min: min_firmware().to_string(),
                        },
                    });
                    return;
                }
            }
            if let Some(serial) = base
                .get("snCode")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty() && s.len() <= MAX_SERIAL_LEN)
            {
                self.serial = Some(serial.to_owned());
            }
            // `wifiName` (the user's SSID) is never read.
            let info = self.info();
            if self.link.ready() && info != before {
                events.push(GrinderEvent::Info(info));
            }
        }
        if let Some(period) = broadcast.get("periodInfo").and_then(Value::as_object) {
            match decode_period(period, now_ms) {
                Some((reading, rejected)) => {
                    self.diagnostics.out_of_range_fields = self
                        .diagnostics
                        .out_of_range_fields
                        .saturating_add(rejected);
                    if self.link.seen(now_ms) {
                        events.push(GrinderEvent::Ready { info: self.info() });
                    }
                    events.push(GrinderEvent::Reading(reading));
                }
                None => {
                    self.diagnostics.unreadable_messages =
                        self.diagnostics.unreadable_messages.saturating_add(1);
                }
            }
        }
    }

    pub(crate) fn redact_for_capture(&mut self, bytes: &[u8], now_ms: u64) -> Vec<Vec<u8>> {
        self.capture.feed(bytes, now_ms)
    }
}

/// Shapes the raw TX stream for the capture file, apart from the decoder so
/// it can never change what is decoded.
///
/// - A complete message is recorded as one frame per 512 bytes, not as the
///   fragments it arrived in (at MTU 23 a 248-byte `periodInfo` is 21
///   notifications, 105 a second, which would crowd the DE1's bytes out of
///   the rolling buffer). Replay reassembles it the same way.
/// - Any `snCode` is rewritten to `"REDACTED"` and any `wifiName` to `null`,
///   wherever they appear, before anything is recorded.
/// - A message that is only `periodInfo` and repeats the last one recorded is
///   kept at most once a second (the 5 s silence watchdog still sees it).
/// - A fragment that never became part of a complete, readable message (bad,
///   superseded, expired) keeps its header and has its payload zeroed, so a
///   partial `baseInfo` can't leak its serial.
#[derive(Debug, Clone, Default)]
struct CaptureShaper {
    reassembler: FrameReassembler,
    held: Vec<Vec<u8>>,
    last_period: Option<(Vec<u8>, u64)>,
}

/// How often an unchanged `periodInfo` is recorded.
const CAPTURE_REPEAT_MS: u64 = 1_000;

fn zeroed(mut frame: Vec<u8>) -> Vec<u8> {
    if let Some(payload) = frame.get_mut(HEADER_LEN..) {
        payload.fill(0);
    }
    frame
}

/// Rewrite private fields anywhere in `value`. Returns whether it changed.
fn redact(value: &mut Value) -> bool {
    let mut changed = false;
    match value {
        Value::Object(object) => {
            for (key, item) in object.iter_mut() {
                if key == "snCode" {
                    *item = Value::String("REDACTED".to_owned());
                    changed = true;
                } else if key == "wifiName" {
                    *item = Value::Null;
                    changed = true;
                } else {
                    changed |= redact(item);
                }
            }
        }
        Value::Array(items) => {
            for item in items {
                changed |= redact(item);
            }
        }
        _ => {}
    }
    changed
}

/// Whether `document` is `{"broadcast":{"periodInfo":…}}` and nothing else.
fn is_period_only(document: &Value) -> bool {
    document.as_object().is_some_and(|top| {
        top.len() == 1
            && top
                .get("broadcast")
                .and_then(Value::as_object)
                .is_some_and(|b| b.len() == 1 && b.contains_key("periodInfo"))
    })
}

impl CaptureShaper {
    fn orphan_held(&mut self, out: &mut Vec<Vec<u8>>) {
        out.extend(self.held.drain(..).map(zeroed));
    }

    fn feed(&mut self, bytes: &[u8], now_ms: u64) -> Vec<Vec<u8>> {
        let mut out = Vec::new();
        let starts_over = header(bytes).is_some_and(|(_, _, h)| h.offset == 0);
        match self.reassembler.feed(bytes, now_ms) {
            Err(_) => {
                self.orphan_held(&mut out);
                out.push(zeroed(bytes.to_vec()));
            }
            Ok(None) => {
                if starts_over {
                    self.orphan_held(&mut out);
                }
                self.held.push(bytes.to_vec());
            }
            Ok(Some(message)) => {
                if starts_over {
                    self.orphan_held(&mut out);
                }
                self.held.push(bytes.to_vec());
                let seq = header(bytes).map_or(0, |(_, _, h)| h.seq);
                match self.shape(&message, now_ms) {
                    Some(Some(payload)) => {
                        self.held.clear();
                        out.extend(encode(&payload, seq, MAX_PACKET));
                    }
                    Some(None) => self.held.clear(),
                    None => self.orphan_held(&mut out),
                }
            }
        }
        out
    }

    /// `None`: unreadable (record nothing readable). `Some(None)`: a repeat
    /// to skip. `Some(Some(payload))`: record this.
    fn shape(&mut self, message: &[u8], now_ms: u64) -> Option<Option<Vec<u8>>> {
        let text = std::str::from_utf8(message).ok()?;
        let mut document: Value = serde_json::from_str(text).ok()?;
        let payload = if redact(&mut document) {
            serde_json::to_vec(&document).ok()?
        } else {
            message.to_vec()
        };
        if is_period_only(&document) {
            if let Some((last, at)) = &self.last_period
                && *last == payload
                && now_ms.saturating_sub(*at) < CAPTURE_REPEAT_MS
            {
                return Some(None);
            }
            self.last_period = Some((payload.clone(), now_ms));
        }
        Some(Some(payload))
    }
}

#[cfg(test)]
mod tests;
