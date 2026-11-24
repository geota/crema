//! Bookoo scale BLE codec (`scale_type` `bookoo`).
//!
//! Weight notifications arrive on [`NOTIFY_UUID`]; commands are written to
//! [`COMMAND_UUID`]. The commands are fixed byte sequences (the legacy app
//! hard-codes them, checksum included).
//!
//! BOOKOO publishes the protocol for both scales this driver covers, the Mini
//! and the Ultra (BooKooCode/OpenSource `bookoo_mini_scale/protocols.md` and
//! `bookoo_ultra_scale/protocols.md`, both "Last Update November 17, 2026").
//! The two share the frame format; several commands differ by model and
//! firmware. [`BookooFirmware`] tells them apart by the firmware version the
//! scale reports in its `03 0c` serial reply (Mini V1.x, Ultra V3 / V4), and
//! [`BookooScale`] keys every version-dependent command off it.
//!
//! The two models' modes differ and are modelled per model. The Mini has
//! Flow-Rate, Timer and Auto, which the undocumented `0x0E` / `03 0e` pair
//! sets and reports (captured on a Mini). The Ultra has weight, timing and
//! ratio modes, timing and ratio each with a manual and an automatic submode
//! (V4). No documented or captured frame reports which one is active, so Crema
//! neither reads nor sets an Ultra's mode with `0x0E`. It follows the
//! automatic submode of timing or ratio mode alike through the documented
//! `03 0D` events, and sets an Ultra-V4 auto-stop for timing and ratio mode
//! separately.
//!
//! # Weight notification packet
//!
//! The scale sends fixed 20-byte notifications on the weight characteristic.
//! The layout below was reverse-engineered from a real capture
//! (`session-20260517-151504.jsonl`, 606 packets) and cross-checked against the
//! legacy app's `bookoo_parse_response` in `de1plus/bluetooth.tcl` — which
//! decodes *only* the weight (bytes 6–9) and ignores everything else.
//!
//! ```text
//! [0-1]   03 0b      header (constant in the whole capture)
//! [2-4]   timer, 24-bit big-endian milliseconds (see TIMER below)
//! [5]     unit of weight — 01 grams (Ultra: 02 ounces; the weight value
//!         is always grams, per the doc)
//! [6]     "weight indicator" byte — 0x2b ('+') or 0x2d ('-'); see INDICATOR
//! [7-9]   weight, 24-bit big-endian, in units of 0.01 g
//! [10]    "flow indicator" byte — 0x2b ('+') or 0x2d ('-'); see INDICATOR
//! [11-12] flow rate, 16-bit big-endian; see FLOW
//! [13-18] live scale settings; see SETTINGS below
//! [19]    checksum: XOR of bytes [0..=18] (verified: 0 mismatches / 606)
//! ```
//!
//! ## SETTINGS bytes `[13]`–`[18]`
//!
//! Bytes `[13]`–`[18]` are **not** constant — they carry the scale's *live
//! settings*, echoed in every weight notification. An earlier revision of this
//! doc called them constant, because the capture it was decoded from never
//! changed a setting. The corrected map was verified by diffing a capture
//! (`session-20260517-215049.jsonl`) against a known action order — the
//! official BOOKOO app stepping volume, then standby, then two on/off toggles:
//!
//! ```text
//! [13]    battery %        — documented ("percentage of remaining power")
//! [14-15] standby timeout  — u16 big-endian, in units of 0.1 min
//!                            (standby_minutes = value / 10): e.g. 0x0096 = 150
//!                            -> 15 min, 0x012C = 300 -> 30 min
//! [16]    beeper volume    — 0..=5, tracks the set_volume (0x02) command
//! [17]    flow smoothing   — boolean (0/1); CONFIRMED. A capture with a known
//!                            action order showed this byte flipping 0->1 when
//!                            flow smoothing was turned on and 1->0 when it was
//!                            turned off, in lockstep with set_flow_smoothing
//!                            (0x08). Exposed as `flow_smoothing: bool`.
//! [18]    auto-stop mode (0/1) on the Mini. 0 = Flow-Stop, 1 = Cup-Removal.
//!                            The doc calls this byte "Reserved" (Mini) and
//!                            `00` (Ultra). On a Mini it matches the `p1` of
//!                            every set_auto_stop (0x0B) write in the
//!                            official-app HCI snoop (5/5, both directions),
//!                            an undocumented echo. Exposed raw as
//!                            `auto_stop`; [`BookooScale::reports_auto_stop`]
//!                            says whether it means anything (Mini only).
//! ```
//!
//! [`BookooPacket`] surfaces `[13]` as `battery_percent`, `[14-15]` as
//! `standby_minutes`, `[16]` as `volume`, `[17]` as the confirmed
//! `flow_smoothing` bool, and `[18]` as the raw `auto_stop` byte.
//!
//! ## INDICATOR bytes `[6]` and `[10]`
//!
//! The legacy app treats `[6]` as an ASCII sign for the weight (`'-'` →
//! negative). The capture contradicts a pure sign reading: a steady 608.70 g
//! object produced one run of 42 packets all carrying `[6] = '-'` and a
//! separate run of 34 packets all carrying `[6] = '+'`, with byte-identical
//! weight bytes — a static positive weight cannot be negative. `[6]` is
//! *constant within a single placement* and only flips across a
//! remove-and-replace cycle, so it is not a stability flag either (it does not
//! change when motion stops). Its exact meaning could not be pinned down from
//! this capture.
//!
//! `[10]` is the flow rate's sign: the published protocol names it "flow rate
//! symbol data points (+/-)", and in the capture `'-'` appears *only* on
//! packets whose flow rate is non-zero (156/156). `flow_g_per_s` applies it,
//! so a falling weight reads as a negative device flow.
//!
//! To stay faithful to the legacy app for the one field it decodes, the weight
//! still honours `[6]` as a sign. The raw indicator bytes are also exposed on
//! [`BookooPacket`] so callers (and the replay tool) can see them unmassaged.
//!
//! ## FLOW
//!
//! The flow rate is a 16-bit big-endian value at bytes `[11-12]`; its unit is
//! **0.01 g/s** — confirmed against a real pour capture, where a ~590 g pour
//! read a median ~4.2 g/s, matching the scale's own display. [`BookooPacket`]
//! exposes both the raw `flow_raw` and a signed `flow_g_per_s` computed as
//! `flow_raw / 100.0`, negated when the `[10]` sign byte is `'-'`.
//!
//! ## TIMER
//!
//! Bytes `[2-4]` are a 24-bit big-endian counter. Its unit is
//! **milliseconds** — confirmed against a real capture, where a 10 s scale
//! timer read 0 → 10000. [`BookooPacket`] reports `timer_ms` directly as that
//! 24-bit value; a 24-bit ms counter wraps at ~4.66 h, comfortably longer than
//! any espresso shot.
//!
//! # COMMANDS
//!
//! Every command written to [`COMMAND_UUID`] (`ff12`) is exactly **6 bytes**:
//!
//! ```text
//! [0]  0x03   constant header
//! [1]  0x0A   constant header
//! [2]  cmd    command id (see catalog below)
//! [3]  p1     parameter 1
//! [4]  p2     parameter 2
//! [5]  xor    checksum: XOR of bytes [0..=4]
//! ```
//!
//! The checksum is the XOR of the five preceding bytes. [`command`] computes it,
//! so every constant and builder below is checksum-correct by construction —
//! the hand-typed-checksum bug class (which had previously corrupted the three
//! timer commands) is structurally impossible.
//!
//! The whole catalog was reverse-engineered from a Bluetooth-HCI capture of the
//! official BOOKOO app.
//!
//! ## Command catalog
//!
//! ```text
//! cmd  builder                meaning
//! ---- ---------------------- --------------------------------------------
//! 0x01 TARE                   tare the scale
//! 0x02 set_volume             beeper volume, p2 = level 0..=5
//! 0x03 set_standby_minutes    auto-standby timeout, p2 = minutes 5..=30
//! 0x04 TIMER_START            start the timer
//! 0x05 TIMER_STOP             stop the timer
//! 0x06 TIMER_RESET            reset the timer
//! 0x07 TARE_AND_START         tare and start (sent instead of 0x01 in Auto)
//! 0x08 set_flow_smoothing    flow smoothing on/off, p1 = 0|1
//! 0x0A QUERY_SERIAL           query serial number (scale replies 03 0c …)
//! 0x0B set_auto_stop_mode     auto-stop, Mini / Ultra <= V3.1.2 format
//! 0x0B set_auto_stop_v4       auto-stop, Ultra >= V4.0.0 format
//! 0x0E set_mode_enabled       enable/disable a display mode (Mini only)
//! 0x0F QUERY_SETTINGS         query settings (scale replies 03 0e …)
//! 0x10 set_anti_mistouch      anti-mistouch on/off, p1 = 0|1
//! 0x11 SHUTDOWN_V1_4_2        shutdown, Mini V1.4.2 only
//! 0x12 KEEP_AWAKE_V1_4_2      reset the auto-off countdown, Mini V1.4.2 only
//! 0x15 SHUTDOWN               shutdown, Mini >= V1.4.3, Ultra >= V4.0.0
//! 0x25 KEEP_AWAKE             reset the auto-off countdown, Mini >= V1.4.3,
//!                             Ultra >= V4.0.1
//! ```
//!
//! `0x01`–`0x08`, `0x11`/`0x12`, `0x15` and `0x25` are in the published
//! protocol, as is `0x0B` on the Ultra. `0x0A`, `0x0E`, `0x0F`, `0x10` and the
//! Mini's `0x0B` are not ("other data is not open"): they were
//! reverse-engineered from a Bluetooth-HCI capture of the official BOOKOO app
//! with a known action order — see each builder's doc comment.

// The 24-bit raw weight fields are decoded into `f32` grams; representing a
// wire reading as the codec's `f32` weight is inherent to the format, not a
// defect, so the precision-loss lint is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

/// GATT service UUID.
pub const SERVICE_UUID: &str = "00000ffe-0000-1000-8000-00805f9b34fb";
/// Characteristic the scale notifies weight on.
pub const NOTIFY_UUID: &str = "0000ff11-0000-1000-8000-00805f9b34fb";
/// Characteristic commands are written to.
pub const COMMAND_UUID: &str = "0000ff12-0000-1000-8000-00805f9b34fb";

/// Build a 6-byte Bookoo `ff12` command `[0x03, 0x0A, cmd, p1, p2, xor]`.
///
/// `xor` is computed as the XOR of the five preceding bytes, so the checksum is
/// always correct by construction — see the [`COMMANDS`](self#commands) section
/// of the module docs. Every constant and builder in this module is defined in
/// terms of this function; checksums are never hand-typed.
///
/// # Why Crema's XOR is the source of truth — not legacy de1app
///
/// **Legacy de1app ships the wrong XOR for the three timer commands.** It
/// hand-types `0A`/`0D`/`0C` for `TIMER_START`/`TIMER_STOP`/`TIMER_RESET`
/// (`de1plus/bluetooth.tcl:499, 515, 531`), and Decenza copies those bytes
/// (`bookooscale.cpp:184-194`). reaprime now computes the XOR
/// (`miniscale.dart:154-173`) and agrees with Crema. The structurally-correct
/// XOR per this builder is `0D`/`0C`/`0F`. Whether the Bookoo checks the XOR
/// at all is still unverified on hardware.
///
/// Crema's `const fn command()` derives the XOR structurally — see the test
/// `timer_constants_match_the_captured_bytes`. Crema is the source of truth
/// here; do NOT "fix" Crema's bytes down to match either upstream app.
#[must_use]
pub const fn command(cmd: u8, p1: u8, p2: u8) -> [u8; 6] {
    let xor = 0x03 ^ 0x0A ^ cmd ^ p1 ^ p2;
    [0x03, 0x0A, cmd, p1, p2, xor]
}

/// Command: tare the scale.
pub const TARE: [u8; 6] = command(0x01, 0x00, 0x00);
/// Command: start the timer.
pub const TIMER_START: [u8; 6] = command(0x04, 0x00, 0x00);
/// Command: stop the timer.
pub const TIMER_STOP: [u8; 6] = command(0x05, 0x00, 0x00);
/// Command: reset the timer.
pub const TIMER_RESET: [u8; 6] = command(0x06, 0x00, 0x00);
/// Command `0x07`: tare and start (the doc's "recommend" tare).
///
/// [`BookooScale::tare_command`] sends it instead of [`TARE`] while the scale
/// reports its Auto mode, where `0x01` is ignored once an extraction has run
/// (Mini all versions, Ultra all versions: "ignored during automatic running,
/// automatic done"). What `0x07` does in Auto, per the doc:
///
/// - **Mini V1.0.0–V1.0.1:** nothing in Auto (ready or done).
/// - **Mini V1.0.2–V1.4.1:** in Auto *ready*, starts the extraction at once;
///   ignored in Auto *done*.
/// - **Mini ≥ V1.4.2:** in Auto *ready*, starts the extraction at once; in Auto
///   *done*, tares, clears the last result and returns to ready without
///   starting.
/// - **Ultra ≤ V3.1.2:** in Auto *ready*, starts the extraction (Auto *done* is
///   not mentioned).
/// - **Ultra ≥ V4.0.0:** in Auto *ready*, starts the extraction; in Auto
///   *done*, returns to idle and tares without starting; ignored in Auto
///   *idle* or *running*.
///
/// All versions ignore it during an automatic run. Where it does not tare, the
/// weight does not zero, and the core's open-weight guard refuses the weight
/// stop as it does for any tare that did not land.
pub const TARE_AND_START: [u8; 6] = command(0x07, 0x00, 0x00);

/// Command `0x25`: reset the automatic-shutdown countdown (keep awake).
///
/// Mini ≥ V1.4.3 (not beta V1.5.0.0806), Ultra ≥ V4.0.1. The doc says to send
/// it early and periodically rather than just before the scale would sleep. It
/// does not change the configured auto-off duration.
pub const KEEP_AWAKE: [u8; 6] = command(0x25, 0x00, 0x00);
/// Command `0x12`: the Mini V1.4.2 form of [`KEEP_AWAKE`]. Sent to exactly
/// that version and nothing else.
pub const KEEP_AWAKE_V1_4_2: [u8; 6] = command(0x12, 0x00, 0x00);
/// Command `0x15`: shut the scale down (the BLE link drops as it sleeps).
///
/// Mini ≥ V1.4.3 (not beta V1.5.0.0806), Ultra ≥ V4.0.0 (not while charging).
pub const SHUTDOWN: [u8; 6] = command(0x15, 0x00, 0x00);
/// Command `0x11`: the Mini V1.4.2 form of [`SHUTDOWN`]. Sent to exactly that
/// version and nothing else.
pub const SHUTDOWN_V1_4_2: [u8; 6] = command(0x11, 0x00, 0x00);

/// How often to send [`KEEP_AWAKE`] while connected, in milliseconds. The
/// shortest auto-off the scale allows is 5 minutes; a write every minute
/// resets the countdown well before it runs out.
pub const KEEP_AWAKE_INTERVAL_MS: u32 = 60_000;

/// Command: query the scale's serial number.
///
/// This is a query: the scale answers with a `03 0c …` notification rather than
/// changing any setting.
pub const QUERY_SERIAL: [u8; 6] = command(0x0A, 0x00, 0x00);
/// Command: query the scale's settings.
///
/// This is a query: the scale answers with a `03 0e …` notification rather than
/// changing any setting.
pub const QUERY_SETTINGS: [u8; 6] = command(0x0F, 0x00, 0x00);

/// A display/behaviour mode that can be toggled with [`set_mode_enabled`].
///
/// The discriminant is the `p1` index the scale expects for command `0x0E`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookooMode {
    /// Flow-rate display (mode index 0).
    FlowRate = 0,
    /// Timer display (mode index 1).
    Timer = 1,
    /// Auto mode (mode index 2).
    Auto = 2,
}

impl BookooMode {
    /// The `p1` index byte the scale uses for this mode in command `0x0E`.
    #[must_use]
    pub const fn index(self) -> u8 {
        self as u8
    }
}

/// Command: set the beeper volume.
///
/// `level` is clamped to the valid range `0..=5`.
#[must_use]
pub const fn set_volume(level: u8) -> [u8; 6] {
    let level = if level > 5 { 5 } else { level };
    command(0x02, 0x00, level)
}

/// Command: set the auto-standby timeout, in minutes.
///
/// `minutes` is clamped to the valid range `5..=30`.
#[must_use]
pub const fn set_standby_minutes(minutes: u8) -> [u8; 6] {
    let minutes = if minutes < 5 {
        5
    } else if minutes > 30 {
        30
    } else {
        minutes
    };
    command(0x03, 0x00, minutes)
}

/// Command: enable or disable one of the scale's display modes.
///
/// `0x0E` toggles modes *individually*; the scale keeps at least one mode
/// enabled at all times. To switch the *active* mode, use [`select_mode`].
#[must_use]
pub const fn set_mode_enabled(mode: BookooMode, enabled: bool) -> [u8; 6] {
    command(0x0E, mode.index(), enabled as u8)
}

/// The ordered command sequence that switches the scale to a single active
/// display mode.
///
/// Command `0x0E` only enables/disables modes *individually*, and the scale
/// requires **at least one mode enabled at all times**. Selecting a mode is
/// therefore three writes, and the order matters: the target is enabled
/// *first*, then the other two are disabled — so an enabled mode always exists,
/// even mid-sequence. Send the three commands in the returned order.
#[must_use]
pub const fn select_mode(target: BookooMode) -> [[u8; 6]; 3] {
    let (a, b) = match target {
        BookooMode::FlowRate => (BookooMode::Timer, BookooMode::Auto),
        BookooMode::Timer => (BookooMode::FlowRate, BookooMode::Auto),
        BookooMode::Auto => (BookooMode::FlowRate, BookooMode::Timer),
    };
    [
        set_mode_enabled(target, true),
        set_mode_enabled(a, false),
        set_mode_enabled(b, false),
    ]
}

/// Command `0x08` — enable or disable *flow smoothing*.
///
/// The feature mapping (`0x08` = flow smoothing) was confirmed against a
/// Bluetooth-HCI capture of the official BOOKOO app with a known action order.
#[must_use]
pub const fn set_flow_smoothing(enabled: bool) -> [u8; 6] {
    command(0x08, enabled as u8, 0x00)
}

/// The scale's auto-stop condition: what ends an automatic-mode extraction.
///
/// Crema's id for each is the discriminant (`0` / `1`), which is also the
/// `p1` byte of the Mini / Ultra-V3 format. The Ultra-V4 format numbers the
/// conditions differently — see [`AutoStopMode::v4_condition`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AutoStopMode {
    /// Stop when the liquid flow stops (`p1 = 0`).
    FlowStop = 0,
    /// Stop when the cup is removed (`p1 = 1`; on Ultra V4, "server being
    /// removed").
    CupRemoval = 1,
}

impl AutoStopMode {
    /// The `p1` byte the scale uses for this mode in the Mini / Ultra-V3
    /// command `0x0B`.
    #[must_use]
    pub const fn p1(self) -> u8 {
        self as u8
    }

    /// The Ultra-V4 stop-condition byte (`0x0B`'s BYTE5): `00` liquid flow
    /// stopping, `02` server being removed. V4's third condition, `01` filter
    /// being removed (a pour-over dripper lifted off), has no Crema setting.
    #[must_use]
    pub const fn v4_condition(self) -> u8 {
        match self {
            AutoStopMode::FlowStop => 0x00,
            AutoStopMode::CupRemoval => 0x02,
        }
    }

    /// The mode for Crema's id (`0` / `1`), or `None` for any other id.
    #[must_use]
    pub const fn from_id(id: u8) -> Option<AutoStopMode> {
        match id {
            0 => Some(AutoStopMode::FlowStop),
            1 => Some(AutoStopMode::CupRemoval),
            _ => None,
        }
    }
}

/// Command `0x0B` in the Mini / Ultra ≤ V3.1.2 format: `0B <stop> 00`.
///
/// The Ultra doc gives this format for release firmware V3.1.2 and earlier and
/// says not to use it on V4.0.0 and later. The Mini doc does not list `0x0B`;
/// the official BOOKOO app sends this same format to a Mini (V1.4.1 HCI
/// capture, cross-checked with the scale's echo in weight byte `[18]`). Send it
/// only through [`BookooScale::auto_stop_writes`], which checks the firmware.
#[must_use]
pub const fn set_auto_stop_mode(mode: AutoStopMode) -> [u8; 6] {
    command(0x0B, mode.p1(), 0x00)
}

/// The Ultra-V4 mode whose automatic submode a `0x0B` stop condition applies
/// to (`0x0B`'s BYTE4). V4 sets the condition separately for each.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AutoStopTarget {
    /// Timing mode (`01`).
    Timing = 1,
    /// Ratio mode (`02`).
    Ratio = 2,
}

impl AutoStopTarget {
    /// Both targets, in wire order.
    pub const ALL: [AutoStopTarget; 2] = [AutoStopTarget::Timing, AutoStopTarget::Ratio];

    /// The BYTE4 id, which is also Crema's id for the target.
    #[must_use]
    pub const fn id(self) -> u8 {
        self as u8
    }

    /// The target for its id, or `None` for any other id.
    #[must_use]
    pub const fn from_id(id: u8) -> Option<AutoStopTarget> {
        match id {
            1 => Some(AutoStopTarget::Timing),
            2 => Some(AutoStopTarget::Ratio),
            _ => None,
        }
    }

    /// A display name for the mode.
    #[must_use]
    pub const fn name(self) -> &'static str {
        match self {
            AutoStopTarget::Timing => "Timing",
            AutoStopTarget::Ratio => "Ratio",
        }
    }
}

/// Command `0x0B` in the Ultra ≥ V4.0.0 format: `0B <target> <stop>`.
///
/// The two formats are not interchangeable: on V4, the V3 frame for
/// Cup-Removal (`0B 01 00`) would set *timing mode = flow stops*.
#[must_use]
pub const fn set_auto_stop_v4(target: AutoStopTarget, mode: AutoStopMode) -> [u8; 6] {
    command(0x0B, target as u8, mode.v4_condition())
}

/// The model a firmware version belongs to — see [`BookooFirmware::model`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookooModel {
    /// The Mini (Themis Mini): firmware V1.x.
    Mini,
    /// The Ultra (Themis Ultra): firmware V3.x and V4.x (and later).
    Ultra,
}

/// A Bookoo firmware version as the `03 0c` serial reply encodes it:
/// `major × 100 + minor × 10 + patch` (V1.4.3 is `143`, V4.0.1 is `401`).
///
/// The published protocols gate several commands by model and version, and
/// the two models share one advertised name, so the version is how Crema
/// tells them apart: the Mini ships V1.x, the Ultra V3.x / V4.x.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BookooFirmware(pub u16);

/// Mini V1.4.2: the one release that used `0x11` / `0x12`.
const MINI_V1_4_2: u16 = 142;
/// Mini V1.4.3: the first release with `0x15` / `0x25`.
const MINI_V1_4_3: u16 = 143;
/// Mini beta V1.5.0.0806, which the doc says lacks `0x15` / `0x25`. Its
/// build suffix is not in the encoding, so every reported `150` is treated
/// as that beta.
const MINI_BETA_V1_5_0: u16 = 150;
/// Ultra V3.1.2: the last release with the V3 `0x0B` format.
const ULTRA_V3_1_2: u16 = 312;
/// Ultra V4.0.0: the V4 `0x0B` format and `0x15`.
const ULTRA_V4_0_0: u16 = 400;
/// Ultra V4.0.1: the first release with `0x25`.
const ULTRA_V4_0_1: u16 = 401;

impl BookooFirmware {
    /// The model this version belongs to: major 1 is a Mini, majors 3–9 an
    /// Ultra. `None` for anything else (a major 0 or 2, or a debug build's
    /// four-digit value), and every version-gated command stays unsent.
    #[must_use]
    pub const fn model(self) -> Option<BookooModel> {
        match self.0 / 100 {
            1 => Some(BookooModel::Mini),
            3..=9 => Some(BookooModel::Ultra),
            _ => None,
        }
    }

    /// The keep-awake command this firmware takes, if any.
    #[must_use]
    pub const fn keep_awake_command(self) -> Option<&'static [u8; 6]> {
        match self.model() {
            Some(BookooModel::Mini) if self.0 == MINI_V1_4_2 => Some(&KEEP_AWAKE_V1_4_2),
            Some(BookooModel::Mini) if self.0 >= MINI_V1_4_3 && self.0 != MINI_BETA_V1_5_0 => {
                Some(&KEEP_AWAKE)
            }
            Some(BookooModel::Ultra) if self.0 >= ULTRA_V4_0_1 => Some(&KEEP_AWAKE),
            _ => None,
        }
    }

    /// The shutdown command this firmware takes, if any.
    #[must_use]
    pub const fn shutdown_command(self) -> Option<&'static [u8; 6]> {
        match self.model() {
            Some(BookooModel::Mini) if self.0 == MINI_V1_4_2 => Some(&SHUTDOWN_V1_4_2),
            Some(BookooModel::Mini) if self.0 >= MINI_V1_4_3 && self.0 != MINI_BETA_V1_5_0 => {
                Some(&SHUTDOWN)
            }
            Some(BookooModel::Ultra) if self.0 >= ULTRA_V4_0_0 => Some(&SHUTDOWN),
            _ => None,
        }
    }

    /// The modes whose auto-stop condition is set separately on this
    /// firmware: timing and ratio on Ultra ≥ V4.0.0, none elsewhere (one
    /// setting for the scale's automatic mode).
    #[must_use]
    pub const fn auto_stop_targets(self) -> &'static [AutoStopTarget] {
        match self.model() {
            Some(BookooModel::Ultra) if self.0 >= ULTRA_V4_0_0 => &AutoStopTarget::ALL,
            _ => &[],
        }
    }

    /// The `0x0B` write that sets `mode` for one `target` mode; `None` unless
    /// this firmware sets the condition per mode (Ultra ≥ V4.0.0).
    #[must_use]
    pub fn auto_stop_target_write(
        self,
        target: AutoStopTarget,
        mode: AutoStopMode,
    ) -> Option<[u8; 6]> {
        (!self.auto_stop_targets().is_empty()).then(|| set_auto_stop_v4(target, mode))
    }

    /// Whether the `03 0e` settings reply's mode index means the Mini's
    /// Flow-Rate / Timer / Auto (captured on a Mini; the Ultra's modes —
    /// weight, timing, ratio, the last two with manual and automatic
    /// submodes — have no known index, so Crema neither reads nor sets them
    /// with `0x0E` there).
    #[must_use]
    pub const fn has_mini_modes(self) -> bool {
        matches!(self.model(), Some(BookooModel::Mini))
    }

    /// The `0x0B` writes that set `mode` on this firmware; empty when Crema
    /// can't set it correctly.
    ///
    /// - Mini: the captured `0B <stop> 00`.
    /// - Ultra ≤ V3.1.2: the documented V3 `0B <stop> 00`.
    /// - Ultra ≥ V4.0.0: `0B <target> <stop>` for both automatic submodes
    ///   (timing, then ratio), so Crema's one setting means the same in each.
    /// - Ultra V3.1.3–V3.9.9 (betas such as V3.2.4, neither format documented)
    ///   and an unknown model: nothing.
    #[must_use]
    pub fn auto_stop_writes(self, mode: AutoStopMode) -> Vec<[u8; 6]> {
        match self.model() {
            Some(BookooModel::Mini) => vec![set_auto_stop_mode(mode)],
            Some(BookooModel::Ultra) if self.0 <= ULTRA_V3_1_2 => vec![set_auto_stop_mode(mode)],
            Some(BookooModel::Ultra) if self.0 >= ULTRA_V4_0_0 => vec![
                set_auto_stop_v4(AutoStopTarget::Timing, mode),
                set_auto_stop_v4(AutoStopTarget::Ratio, mode),
            ],
            _ => Vec::new(),
        }
    }

    /// Whether weight byte `[18]` echoes the auto-stop setting: on a Mini
    /// (captured, undocumented). The Ultra doc fixes it at `00`.
    #[must_use]
    pub const fn echoes_auto_stop(self) -> bool {
        matches!(self.model(), Some(BookooModel::Mini))
    }
}

/// An Ultra automatic-mode event (`03 0D`, documented for beta V3.2.4+ and
/// release V4.0.0+): the scale's automatic submode — of timing or ratio mode
/// alike — changed phase. The discriminant is the event byte `[2]`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AutoModeEvent {
    /// `00` — brewing ended (stop condition or command): now *done*.
    Stopped = 0,
    /// `01` — brewing started (automatically or by command): now *running*.
    Started = 1,
    /// `02` — the automatic submode entered *ready*.
    Ready = 2,
    /// `03` — left *ready* (to idle).
    ExitReady = 3,
    /// `04` — left *done*.
    ExitDone = 4,
}

/// `[0-1]` header of an Ultra `03 0D` automatic-mode event.
const AUTO_EVENT_HEADER: [u8; 2] = [0x03, 0x0D];

/// Decode an Ultra `03 0D` automatic-mode event: 20 bytes, the `03 0D`
/// header, a known event byte at `[2]` and the `[19]` XOR of `[0..=18]`.
/// The doc doesn't say which characteristic carries it, so Crema reads it on
/// both. The time, weight and result fields are not used.
#[must_use]
pub fn parse_auto_mode_event(data: &[u8]) -> Option<AutoModeEvent> {
    if data.len() != FULL_PACKET_LEN
        || data[..2] != AUTO_EVENT_HEADER
        || data[..FULL_PACKET_LEN - 1].iter().fold(0_u8, |a, &b| a ^ b) != data[FULL_PACKET_LEN - 1]
    {
        return None;
    }
    match data[2] {
        0 => Some(AutoModeEvent::Stopped),
        1 => Some(AutoModeEvent::Started),
        2 => Some(AutoModeEvent::Ready),
        3 => Some(AutoModeEvent::ExitReady),
        4 => Some(AutoModeEvent::ExitDone),
        _ => None,
    }
}

/// The phase of an Ultra's automatic submode, as its `03 0D` events report
/// it. Idle (or a manual submode, or weight mode) is no phase.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AutoPhase {
    Ready,
    Running,
    Done,
}

/// What Crema has learnt about a connected Bookoo from its replies: the
/// firmware (from `03 0c`), the Mini's active mode (from `03 0e`) and the
/// Ultra's automatic-submode phase (from `03 0D` events). A new connection
/// starts empty; until the firmware arrives, no version-gated command is sent.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct BookooScale {
    firmware: Option<BookooFirmware>,
    active_mode: Option<u8>,
    auto_phase: Option<AutoPhase>,
}

impl BookooScale {
    /// Fold a decoded `ff12` reply into what the scale has told us.
    pub fn absorb(&mut self, response: &CommandResponse) {
        match response {
            CommandResponse::Serial {
                firmware_version, ..
            } => self.firmware = Some(BookooFirmware(*firmware_version)),
            CommandResponse::Settings { active_mode, .. } => self.active_mode = Some(*active_mode),
        }
    }

    /// Fold an Ultra automatic-mode event into the tracked phase.
    pub fn absorb_auto_event(&mut self, event: AutoModeEvent) {
        self.auto_phase = match event {
            AutoModeEvent::Ready => Some(AutoPhase::Ready),
            AutoModeEvent::Started => Some(AutoPhase::Running),
            AutoModeEvent::Stopped => Some(AutoPhase::Done),
            AutoModeEvent::ExitReady | AutoModeEvent::ExitDone => None,
        };
    }

    /// Fold any frame the scale sent, on either characteristic: a `03 0c` /
    /// `03 0e` reply or an Ultra `03 0D` event. Anything else is ignored.
    pub fn absorb_frame(&mut self, data: &[u8]) {
        if let Some(response) = parse_command_response(data) {
            self.absorb(&response);
        } else if let Some(event) = parse_auto_mode_event(data) {
            self.absorb_auto_event(event);
        }
    }

    /// Whether the scale shows the Mini's three modes (a Mini, once its
    /// firmware is known). See [`BookooFirmware::has_mini_modes`].
    #[must_use]
    pub fn has_mini_modes(&self) -> bool {
        self.firmware.is_some_and(BookooFirmware::has_mini_modes)
    }

    /// The modes whose auto-stop is set separately — see
    /// [`BookooFirmware::auto_stop_targets`]. Empty until the firmware is
    /// known.
    #[must_use]
    pub fn auto_stop_targets(&self) -> &'static [AutoStopTarget] {
        self.firmware.map_or(&[], BookooFirmware::auto_stop_targets)
    }

    /// The `0x0B` write for one `target` mode — see
    /// [`BookooFirmware::auto_stop_target_write`].
    #[must_use]
    pub fn auto_stop_target_write(
        &self,
        target: AutoStopTarget,
        mode: AutoStopMode,
    ) -> Option<[u8; 6]> {
        self.firmware
            .and_then(|fw| fw.auto_stop_target_write(target, mode))
    }

    /// The reported firmware, once the serial reply has arrived.
    #[must_use]
    pub const fn firmware(&self) -> Option<BookooFirmware> {
        self.firmware
    }

    /// Whether the scale is known to be in its automatic mode, per model:
    ///
    /// - **Mini:** its last `03 0e` reply named Auto (index 2) as active.
    /// - **Ultra:** its last `03 0D` event left the automatic submode — of
    ///   timing or ratio mode — in ready, running or done. Automatic *idle*
    ///   doesn't count: there `0x01` tares and V4 ignores `0x07`. An Ultra on
    ///   firmware without the events (release ≤ V3.1.2) is never known to be.
    /// - **Unknown firmware:** never, since the mode index's meaning depends
    ///   on the model.
    #[must_use]
    pub fn in_auto_mode(&self) -> bool {
        match self.firmware.and_then(BookooFirmware::model) {
            Some(BookooModel::Mini) => self.active_mode == Some(BookooMode::Auto.index()),
            Some(BookooModel::Ultra) => self.auto_phase.is_some(),
            None => false,
        }
    }

    /// The tare frame to send: [`TARE_AND_START`] while the scale is known to
    /// be in its automatic mode ([`in_auto_mode`](Self::in_auto_mode); owner
    /// decision 2026-11-24 — `0x01` is ignored in Auto running / done; see
    /// [`TARE_AND_START`] for what `0x07` does per model and version),
    /// [`TARE`] otherwise.
    #[must_use]
    pub fn tare_command(&self) -> &'static [u8; 6] {
        if self.in_auto_mode() {
            &TARE_AND_START
        } else {
            &TARE
        }
    }

    /// The keep-awake command, once the firmware is known to take one.
    #[must_use]
    pub fn keep_awake_command(&self) -> Option<&'static [u8; 6]> {
        self.firmware.and_then(BookooFirmware::keep_awake_command)
    }

    /// The shutdown command, once the firmware is known to take one.
    #[must_use]
    pub fn shutdown_command(&self) -> Option<&'static [u8; 6]> {
        self.firmware.and_then(BookooFirmware::shutdown_command)
    }

    /// The `0x0B` writes for `mode`; empty until the firmware is known, and on
    /// firmware whose format Crema can't set (see
    /// [`BookooFirmware::auto_stop_writes`]).
    #[must_use]
    pub fn auto_stop_writes(&self, mode: AutoStopMode) -> Vec<[u8; 6]> {
        self.firmware
            .map_or_else(Vec::new, |fw| fw.auto_stop_writes(mode))
    }

    /// Whether weight byte `[18]` is the live auto-stop setting (a Mini, once
    /// its firmware is known).
    #[must_use]
    pub fn reports_auto_stop(&self) -> bool {
        self.firmware.is_some_and(BookooFirmware::echoes_auto_stop)
    }
}

/// Command `0x10` — enable or disable *anti-mistouch*.
///
/// The feature mapping (`0x10` = anti-mistouch) was confirmed against a
/// Bluetooth-HCI capture of the official BOOKOO app with a known action order.
#[must_use]
pub const fn set_anti_mistouch(enabled: bool) -> [u8; 6] {
    command(0x10, enabled as u8, 0x00)
}

/// Full length of a Bookoo weight notification.
const FULL_PACKET_LEN: usize = 20;

/// A fully decoded Bookoo weight notification.
///
/// See the [module documentation](self) for the packet layout and for the
/// fields whose meaning the capture could not pin down.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BookooPacket {
    /// Weight in grams. Negative when the `[6]` indicator byte is `'-'`, to
    /// match the legacy app — but see the module docs: `[6]` is not a reliable
    /// sign. Magnitude is bytes `[7-9]` (24-bit big-endian) ÷ 100.
    pub weight_g: f32,
    /// Raw `[6]` "weight indicator" byte — `0x2b` (`'+'`) or `0x2d` (`'-'`).
    /// Exposed unmassaged because its meaning is unconfirmed.
    pub weight_indicator: u8,
    /// Raw 16-bit big-endian flow value, bytes `[11-12]`.
    pub flow_raw: u16,
    /// Flow rate in g/s: `flow_raw / 100.0`, negative when the `[10]` sign
    /// byte is `'-'`. The 0.01 g/s unit is confirmed against a real pour
    /// capture — see the module docs.
    pub flow_g_per_s: f32,
    /// Raw `[10]` flow sign byte — `0x2b` (`'+'`) or `0x2d` (`'-'`).
    pub flow_indicator: u8,
    /// Scale-timer reading in milliseconds, bytes `[2-4]` (24-bit big-endian).
    pub timer_ms: u32,
    /// Battery charge percentage, byte `[13]` — documented in both published
    /// protocols ("percentage of remaining power").
    pub battery_percent: u8,
    /// Auto-standby timeout in minutes, decoded from bytes `[14-15]`.
    ///
    /// Bytes `[14-15]` are a 16-bit big-endian value in units of 0.1 min;
    /// this field is that value divided by 10 (e.g. `0x012C` = 300 -> 30 min).
    pub standby_minutes: u8,
    /// Beeper volume `0..=5`, byte `[16]` — the scale's live volume setting.
    pub volume: u8,
    /// Whether flow smoothing is on, byte `[17]`.
    ///
    /// **Confirmed.** A capture with a known action order showed this byte
    /// flipping `0`→`1` when flow smoothing was turned on and `1`→`0` when it
    /// was turned off, in lockstep with [`set_flow_smoothing`]. See the module
    /// docs.
    pub flow_smoothing: bool,
    /// Raw byte `[18]`: on a Mini, the live auto-stop mode (`0` = Flow-Stop,
    /// `1` = Cup-Removal).
    ///
    /// The doc calls it "Reserved" (Mini) and `00` (Ultra). On a Mini it
    /// matches the `p1` of every [`set_auto_stop_mode`] (`0x0B`) write in the
    /// official-app HCI snoop (5/5, both directions), an undocumented echo; on
    /// an Ultra it says nothing. Use it only where
    /// [`BookooScale::reports_auto_stop`] holds.
    pub auto_stop: u8,
}

/// Whether `data` is a structurally valid weight notification: exactly
/// [`FULL_PACKET_LEN`] bytes, the `03 0b` header, an ASCII `'+'`/`'-'` sign
/// byte at `[6]`, and a trailing XOR of `[0..=18]` at `[19]` — the gate decaid
/// applies (`miniscale.dart` `_parseNotification`, 1330cb4f / 7e09a131).
/// A short frame, a corrupt one, or one whose sign byte is anything else
/// would otherwise inject a bogus weight into stop-at-weight.
fn is_valid_weight_frame(data: &[u8]) -> bool {
    data.len() == FULL_PACKET_LEN
        && matches_weight_header(data)
        && (data[6] == b'+' || data[6] == b'-')
        && data[..FULL_PACKET_LEN - 1].iter().fold(0_u8, |a, &b| a ^ b) == data[FULL_PACKET_LEN - 1]
}

/// Decode a weight notification into grams.
///
/// Bytes 7–9 are a big-endian 24-bit magnitude in units of 0.01 g; byte 6 is
/// an ASCII sign byte (`'-'` marks a negative weight).
///
/// Kept for callers that only need the weight; [`parse_packet`] decodes the
/// whole notification. Only a frame passing [`is_valid_weight_frame`] is
/// decoded — the command channel's config replies are ALSO right-length with
/// a valid XOR, so the `03 0b` header is part of the gate too (review #32).
pub fn parse_weight(data: &[u8]) -> Option<f32> {
    if !is_valid_weight_frame(data) {
        return None;
    }
    let raw = (u32::from(data[7]) << 16) | (u32::from(data[8]) << 8) | u32::from(data[9]);
    let magnitude = raw as f32 / 100.0;
    Some(if data[6] == b'-' {
        -magnitude
    } else {
        magnitude
    })
}

/// Whether `bytes` begin with the Bookoo weight-notification header
/// (`0x03 0x0b`). Lets a Bookoo be identified from its first weight packet
/// when no advertised name is available (replay back-compat) — the byte
/// signature lives here, in the codec that owns the encoding, not in the
/// generic [`crate::Scale`] dispatcher.
#[must_use]
pub fn matches_weight_header(bytes: &[u8]) -> bool {
    bytes.len() >= 2 && bytes[0] == 0x03 && bytes[1] == 0x0b
}

/// Decode a full 20-byte Bookoo weight notification.
///
/// Returns `None` for anything that fails [`is_valid_weight_frame`] — wrong
/// length, header, sign byte or checksum. (A bad checksum used to be
/// reported in a `checksum_ok` field while the reading was still surfaced.)
pub fn parse_packet(data: &[u8]) -> Option<BookooPacket> {
    if !is_valid_weight_frame(data) {
        return None;
    }

    let timer_ms = (u32::from(data[2]) << 16) | (u32::from(data[3]) << 8) | u32::from(data[4]);

    let weight_raw = (u32::from(data[7]) << 16) | (u32::from(data[8]) << 8) | u32::from(data[9]);
    let weight_magnitude = weight_raw as f32 / 100.0;
    let weight_g = if data[6] == b'-' {
        -weight_magnitude
    } else {
        weight_magnitude
    };

    let flow_raw = (u16::from(data[11]) << 8) | u16::from(data[12]);
    let flow_magnitude = f32::from(flow_raw) / 100.0;
    let flow_g_per_s = if data[10] == b'-' {
        -flow_magnitude
    } else {
        flow_magnitude
    };

    // Bytes [14-15] are a 16-bit big-endian standby timeout in units of
    // 0.1 min; the scale only ever sets whole minutes in the documented
    // 5..=30 range, so dividing by 10 yields a small u8. A malformed packet
    // outside that range saturates rather than wrapping.
    let standby_raw = (u16::from(data[14]) << 8) | u16::from(data[15]);
    let standby_minutes = u8::try_from(standby_raw / 10).unwrap_or(u8::MAX);

    Some(BookooPacket {
        weight_g,
        weight_indicator: data[6],
        flow_raw,
        flow_g_per_s,
        flow_indicator: data[10],
        timer_ms,
        battery_percent: data[13],
        standby_minutes,
        volume: data[16],
        flow_smoothing: data[17] != 0,
        auto_stop: data[18],
    })
}

/// A decoded response from the Bookoo `ff12` command characteristic.
///
/// The `ff12` channel — which the app otherwise only *writes* commands to —
/// also has the NOTIFY property, and the scale pushes two 20-byte response
/// frames back on it, distinguished by their `[0-1]` header:
///
/// - **`03 0c`** ([`CommandResponse::Serial`]) — the reply to a `0x0a`
///   ([`QUERY_SERIAL`]) query, and to every `0x10` ([`set_anti_mistouch`])
///   write. Carries the firmware version, the 14-byte ASCII serial number, and
///   the live anti-mistouch state.
/// - **`03 0e`** ([`CommandResponse::Settings`]) — the reply to a `0x0f`
///   ([`QUERY_SETTINGS`]) query. Carries the active display-mode index and the
///   enabled-modes bitmask.
///
/// Both frames are 20 bytes with `[19]` an XOR checksum of `[0..=18]`, the same
/// scheme as the weight notification — see [`parse_command_response`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CommandResponse {
    /// A `03 0c` serial-number response, returned by a [`QUERY_SERIAL`] query
    /// and echoed after every [`set_anti_mistouch`] write.
    Serial {
        /// Firmware version, byte `[2-3]` (u16 big-endian). The encoding is
        /// `major × 100 + minor × 10 + patch` — e.g. firmware 1.4.1 is
        /// `0x008d` = `141`.
        firmware_version: u16,
        /// The scale's serial number, decoded from the 14 ASCII bytes
        /// `[4-17]`. Trailing NUL / whitespace padding is trimmed.
        serial: String,
        /// The scale's live anti-mistouch state, byte `[18]` (`0` / `1`).
        anti_mistouch: bool,
    },
    /// A `03 0e` settings response, returned by a [`QUERY_SETTINGS`] query.
    Settings {
        /// The active display-mode index, byte `[2]` — `0` = Flow-Rate,
        /// `1` = Timer, `2` = Auto (the [`BookooMode`] discriminants).
        active_mode: u8,
        /// The enabled-modes bitmask, byte `[3]` — a 3-bit mask, bit `n` set
        /// when the [`BookooMode`] of index `n` is enabled.
        enabled_modes: u8,
    },
}

/// Format the Bookoo's `u16` firmware version as a `"M.m.p"` string.
///
/// The scale encodes the firmware as `major × 100 + minor × 10 + patch`,
/// so e.g. `141` is firmware `1.4.1`. Centralised here so every shell
/// renders the version identically without duplicating the decode (web
/// used to carry its own `formatFirmware`; an Android shell would have
/// done the same).
#[must_use]
pub fn format_firmware_version(encoded: u16) -> String {
    let major = encoded / 100;
    let minor = (encoded / 10) % 10;
    let patch = encoded % 10;
    format!("{major}.{minor}.{patch}")
}

/// `[0-1]` header of a `03 0c` serial-number command response.
const RESPONSE_HEADER_SERIAL: [u8; 2] = [0x03, 0x0C];
/// `[0-1]` header of a `03 0e` settings command response.
const RESPONSE_HEADER_SETTINGS: [u8; 2] = [0x03, 0x0E];

/// Decode a 20-byte Bookoo `ff12` command-characteristic notification into a
/// [`CommandResponse`].
///
/// Returns `None` for anything that is not exactly [`FULL_PACKET_LEN`] bytes,
/// for a frame whose `[0-1]` header is neither of the two known responses, or
/// for a frame whose `[19]` XOR checksum (over `[0..=18]`) does not match — the
/// same checksum scheme [`parse_packet`] uses, here applied strictly because a
/// command response carries no field a corrupt frame could still surface.
pub fn parse_command_response(data: &[u8]) -> Option<CommandResponse> {
    if data.len() != FULL_PACKET_LEN {
        return None;
    }
    // The checksum byte [19] is an XOR of every preceding byte.
    let checksum = data[..FULL_PACKET_LEN - 1].iter().fold(0_u8, |a, &b| a ^ b);
    if checksum != data[FULL_PACKET_LEN - 1] {
        return None;
    }

    match [data[0], data[1]] {
        RESPONSE_HEADER_SERIAL => {
            let firmware_version = (u16::from(data[2]) << 8) | u16::from(data[3]);
            // Bytes [4-17] are the 14-byte ASCII serial; drop trailing NUL /
            // whitespace padding and any non-ASCII byte defensively.
            let serial: String = data[4..18]
                .iter()
                .copied()
                .take_while(|&b| b != 0)
                .filter(|b| b.is_ascii())
                .map(char::from)
                .collect::<String>()
                .trim()
                .to_owned();
            Some(CommandResponse::Serial {
                firmware_version,
                serial,
                anti_mistouch: data[18] != 0,
            })
        }
        RESPONSE_HEADER_SETTINGS => Some(CommandResponse::Settings {
            active_mode: data[2],
            enabled_modes: data[3],
        }),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn firmware_version_formatting() {
        // Standard cases from the Bookoo's own docs.
        assert_eq!(format_firmware_version(141), "1.4.1");
        assert_eq!(format_firmware_version(100), "1.0.0");
        assert_eq!(format_firmware_version(202), "2.0.2");
        // Zero — no version reported yet.
        assert_eq!(format_firmware_version(0), "0.0.0");
        // The encoding maxes out at 999 in practice; a 4-digit value
        // overflows the documented format but should still produce
        // *some* readable output (used to find debug-mode firmware).
        assert_eq!(format_firmware_version(1234), "12.3.4");
    }

    /// Decode a lowercase-hex string into a fixed 20-byte array.
    fn hex20(s: &str) -> [u8; 20] {
        let bytes: Vec<u8> = (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect();
        bytes.try_into().unwrap()
    }

    /// Decode a lowercase-hex string into a fixed 6-byte command array.
    fn hex6(s: &str) -> [u8; 6] {
        let bytes: Vec<u8> = (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect();
        bytes.try_into().unwrap()
    }

    /// A 20-byte weight frame for `bytes 0..=9` zero-padded, with the XOR.
    fn weight_frame(head: [u8; 10]) -> [u8; 20] {
        let mut f = [0u8; 20];
        f[..10].copy_from_slice(&head);
        f[19] = f[..19].iter().fold(0, |a, &b| a ^ b);
        f
    }

    #[test]
    fn decodes_a_big_endian_24bit_weight_to_grams() {
        // Bytes 7–9 = 0x0007D0 = 2000 -> 20.00 g; sign byte '+'. Header
        // 03 0b — the parser rejects headerless frames (review #32).
        let packet = weight_frame([0x03, 0x0B, 0, 0, 0, 0, b'+', 0x00, 0x07, 0xD0]);
        assert_eq!(parse_weight(&packet), Some(20.0));
    }

    #[test]
    fn decodes_a_negative_weight() {
        let packet = weight_frame([0x03, 0x0B, 0, 0, 0, 0, b'-', 0x00, 0x07, 0xD0]);
        assert_eq!(parse_weight(&packet), Some(-20.0));
    }

    #[test]
    fn rejects_a_short_packet() {
        assert_eq!(parse_weight(&[0; 9]), None);
        // decaid 1330cb4f: the old 10-byte minimum accepted a truncated
        // frame; only the full 20 bytes are a weight frame now.
        assert_eq!(
            parse_weight(&[0x03, 0x0B, 0, 0, 0, 0, b'+', 0x00, 0x07, 0xD0]),
            None
        );
    }

    #[test]
    fn rejects_a_frame_with_a_bad_sign_byte_or_checksum() {
        // decaid miniscale.dart: sign must be '+' (0x2B) or '-' (0x2D).
        let mut bad_sign = hex20("030b000000012b00edc62b0000640096010000d1");
        bad_sign[6] = 0x00;
        bad_sign[19] = bad_sign[..19].iter().fold(0, |a, &b| a ^ b);
        assert_eq!(parse_weight(&bad_sign), None);
        assert_eq!(parse_packet(&bad_sign), None);
        // A flipped weight byte with the old checksum is rejected outright.
        let mut corrupt = hex20("030b000000012b00edc62b0000640096010000d1");
        corrupt[8] ^= 0x40;
        assert_eq!(parse_weight(&corrupt), None);
        assert_eq!(parse_packet(&corrupt), None);
    }

    #[test]
    fn parse_packet_rejects_wrong_length() {
        assert_eq!(parse_packet(&[0; 19]), None);
        assert_eq!(parse_packet(&[0; 21]), None);
    }

    #[test]
    fn decodes_an_idle_packet_from_a_real_capture() {
        // Idle: weight 0, flow 0, timer 0.
        let packet = hex20("030b000000012b0000002b0000640096010000fa");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.weight_g, 0.0);
        assert_eq!(p.flow_raw, 0);
        assert_eq!(p.flow_g_per_s, 0.0);
        assert_eq!(p.timer_ms, 0);
        assert_eq!(p.weight_indicator, b'+');
        assert_eq!(p.flow_indicator, b'+');
    }

    #[test]
    fn decodes_a_settled_weight_from_a_real_capture() {
        // Bytes 7-9 = 0xEDC6 = 60870 -> 608.70 g; flow 0.
        let packet = hex20("030b000000012b00edc62b0000640096010000d1");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert!((p.weight_g - 608.70).abs() < 0.001, "weight {}", p.weight_g);
        assert_eq!(p.flow_raw, 0);
        assert_eq!(p.weight_indicator, b'+');
    }

    #[test]
    fn the_indicator_byte_does_not_flip_the_weight_of_a_static_object() {
        // Same 0xEDC6 weight bytes, [6] = '-' instead of '+'. The legacy app
        // would read this as -608.70 g; the indicator byte is preserved raw so
        // callers can see it is unreliable (see module docs).
        let packet = hex20("030b000000012d00edc62b0000640096010000d7");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.weight_indicator, b'-');
        // parse_weight / weight_g still honour [6] for legacy compatibility.
        assert!((p.weight_g + 608.70).abs() < 0.001, "weight {}", p.weight_g);
    }

    #[test]
    fn decodes_flow_rate_from_a_real_capture() {
        // Bytes 7-9 = 0xC06C = 49260 -> 492.60 g; bytes 11-12 = 0x312F = 12591.
        let packet = hex20("030b000000012b00c06c2b312f64009601000048");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert!((p.weight_g - 492.60).abs() < 0.001, "weight {}", p.weight_g);
        assert_eq!(p.flow_raw, 0x312F);
        assert!((p.flow_g_per_s - 125.91).abs() < 0.001);
    }

    #[test]
    fn decodes_another_flow_packet_from_a_real_capture() {
        // Bytes 7-9 = 0x6E13 = 28179 -> 281.79 g; bytes 11-12 = 0xA426 = 42022.
        let packet = hex20("030b000000012b006e132ba42664009601000005");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert!((p.weight_g - 281.79).abs() < 0.001, "weight {}", p.weight_g);
        assert_eq!(p.flow_raw, 0xA426);
        assert_eq!(p.flow_indicator, b'+');
    }

    #[test]
    fn decodes_live_settings_from_a_standby_15_packet() {
        // Real capture packet whose [14-15] = 0x0096 = 150 -> 15 min standby.
        // Bytes [13-18] = 64 0096 01 00 00: battery 100, vol 1, flow smoothing
        // off, auto-stop mode 0 (Flow-Stop).
        let packet = hex20("030b000000012b0000002b0000640096010000fa");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.battery_percent, 100);
        assert_eq!(p.standby_minutes, 15);
        assert_eq!(p.volume, 1);
        assert!(!p.flow_smoothing);
        assert_eq!(p.auto_stop, 0);
    }

    #[test]
    fn decodes_live_settings_from_a_standby_30_packet() {
        // Real capture packet (session-20260517-215049): [14-15] = 0x012C =
        // 300 -> 30 min standby; [16] = 0x03 volume; [17]/[18] = 00/01.
        let packet = hex20("030b000000012b0000002b000264012c03000140");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.battery_percent, 100);
        assert_eq!(p.standby_minutes, 30);
        assert_eq!(p.volume, 3);
        assert!(!p.flow_smoothing);
        assert_eq!(p.auto_stop, 1);
    }

    #[test]
    fn decodes_live_settings_from_a_standby_16_volume_3_packet() {
        // Real capture packet: [14-15] = 0x00A0 = 160 -> 16 min standby.
        let packet = hex20("030b000000012b0000002b00026400a0030001cd");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.standby_minutes, 16);
        assert_eq!(p.volume, 3);
    }

    #[test]
    fn decodes_a_packet_where_flow_smoothing_is_on() {
        // Real capture packet recorded right after set_flow_smoothing(true):
        // [13-18] = 64 012C 03 01 01 — the [17] flow-smoothing byte flipped to
        // 1, confirming the [17] = flow-smoothing mapping.
        let packet = hex20("030b000000012b0000002b000064012c03010143");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.standby_minutes, 30);
        assert_eq!(p.volume, 3);
        assert!(p.flow_smoothing);
        assert_eq!(p.auto_stop, 1);
    }

    #[test]
    fn decodes_volume_0_from_a_real_capture() {
        // Real capture packet with volume 0 and a 10-minute standby
        // ([14-15] = 0x0064 = 100 -> 10 min).
        let packet = hex20("030b000000012b0000002b000064006400000108");
        let p = parse_packet(&packet).expect("20-byte packet");
        assert_eq!(p.standby_minutes, 10);
        assert_eq!(p.volume, 0);
    }

    #[test]
    fn command_computes_the_xor_checksum() {
        // XOR of 03 0a 01 00 00 = 08.
        assert_eq!(command(0x01, 0x00, 0x00), hex6("030a01000008"));
    }

    #[test]
    fn timer_constants_match_the_captured_bytes() {
        assert_eq!(TARE, hex6("030a01000008"));
        assert_eq!(TIMER_START, hex6("030a0400000d"));
        assert_eq!(TIMER_STOP, hex6("030a0500000c"));
        assert_eq!(TIMER_RESET, hex6("030a0600000f"));
    }

    #[test]
    fn set_volume_matches_the_captured_bytes() {
        assert_eq!(set_volume(1), hex6("030a0200010a"));
        assert_eq!(set_volume(2), hex6("030a02000209"));
        assert_eq!(set_volume(3), hex6("030a02000308"));
        assert_eq!(set_volume(5), hex6("030a0200050e"));
    }

    #[test]
    fn set_volume_clamps_to_the_valid_range() {
        // Out-of-range input clamps to 5.
        assert_eq!(set_volume(9), set_volume(5));
        assert_eq!(set_volume(255), set_volume(5));
    }

    #[test]
    fn set_standby_minutes_matches_the_captured_bytes() {
        assert_eq!(set_standby_minutes(11), hex6("030a03000b01"));
        assert_eq!(set_standby_minutes(12), hex6("030a03000c06"));
        assert_eq!(set_standby_minutes(17), hex6("030a0300111b"));
        assert_eq!(set_standby_minutes(18), hex6("030a03001218"));
        assert_eq!(set_standby_minutes(19), hex6("030a03001319"));
        assert_eq!(set_standby_minutes(20), hex6("030a0300141e"));
        assert_eq!(set_standby_minutes(21), hex6("030a0300151f"));
    }

    #[test]
    fn set_standby_minutes_clamps_to_the_valid_range() {
        assert_eq!(set_standby_minutes(2), set_standby_minutes(5));
        assert_eq!(set_standby_minutes(0), set_standby_minutes(5));
        assert_eq!(set_standby_minutes(40), set_standby_minutes(30));
    }

    #[test]
    fn set_mode_enabled_matches_the_captured_bytes() {
        assert_eq!(
            set_mode_enabled(BookooMode::FlowRate, true),
            hex6("030a0e000106")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::FlowRate, false),
            hex6("030a0e000007")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Timer, false),
            hex6("030a0e010006")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Timer, true),
            hex6("030a0e010107")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Auto, true),
            hex6("030a0e020104")
        );
        assert_eq!(
            set_mode_enabled(BookooMode::Auto, false),
            hex6("030a0e020005")
        );
    }

    #[test]
    fn select_mode_enables_the_target_before_disabling_the_others() {
        // Target on first, then the other two off — never zero modes enabled.
        assert_eq!(
            select_mode(BookooMode::Timer),
            [
                hex6("030a0e010107"), // Timer    -> on
                hex6("030a0e000007"), // FlowRate -> off
                hex6("030a0e020005"), // Auto     -> off
            ]
        );
    }

    #[test]
    fn set_flow_smoothing_matches_the_captured_bytes() {
        assert_eq!(set_flow_smoothing(true), hex6("030a08010000"));
        assert_eq!(set_flow_smoothing(false), hex6("030a08000001"));
    }

    #[test]
    fn set_auto_stop_mode_matches_the_captured_bytes() {
        // 0x0B 00 = Flow-Stop, 0x0B 01 = Cup-Removal (confirmed by capture).
        assert_eq!(
            set_auto_stop_mode(AutoStopMode::FlowStop),
            hex6("030a0b000002")
        );
        assert_eq!(
            set_auto_stop_mode(AutoStopMode::CupRemoval),
            hex6("030a0b010003")
        );
    }

    #[test]
    fn documented_command_frames_are_byte_exact() {
        // 03 0a cmd 00 00 xor, per both published protocols.
        assert_eq!(TARE_AND_START, hex6("030a0700000e"));
        assert_eq!(KEEP_AWAKE, hex6("030a2500002c"));
        assert_eq!(SHUTDOWN, hex6("030a1500001c"));
        assert_eq!(KEEP_AWAKE_V1_4_2, hex6("030a1200001b"));
        assert_eq!(SHUTDOWN_V1_4_2, hex6("030a11000018"));
    }

    #[test]
    fn set_auto_stop_v4_puts_the_target_in_byte4_and_the_condition_in_byte5() {
        // Timing mode, flow stops: 03 0a 0b 01 00.
        assert_eq!(
            set_auto_stop_v4(AutoStopTarget::Timing, AutoStopMode::FlowStop),
            hex6("030a0b010003")
        );
        // Timing mode, server removed: 03 0a 0b 01 02.
        assert_eq!(
            set_auto_stop_v4(AutoStopTarget::Timing, AutoStopMode::CupRemoval),
            hex6("030a0b010201")
        );
        // Ratio mode, flow stops / server removed.
        assert_eq!(
            set_auto_stop_v4(AutoStopTarget::Ratio, AutoStopMode::FlowStop),
            hex6("030a0b020000")
        );
        assert_eq!(
            set_auto_stop_v4(AutoStopTarget::Ratio, AutoStopMode::CupRemoval),
            hex6("030a0b020202")
        );
    }

    #[test]
    fn firmware_tells_the_mini_from_the_ultra() {
        assert_eq!(BookooFirmware(100).model(), Some(BookooModel::Mini));
        assert_eq!(BookooFirmware(143).model(), Some(BookooModel::Mini));
        assert_eq!(BookooFirmware(312).model(), Some(BookooModel::Ultra));
        assert_eq!(BookooFirmware(403).model(), Some(BookooModel::Ultra));
        // Majors 0 and 2, and a debug build's four-digit value: unknown.
        assert_eq!(BookooFirmware(0).model(), None);
        assert_eq!(BookooFirmware(202).model(), None);
        assert_eq!(BookooFirmware(1234).model(), None);
    }

    #[test]
    fn auto_stop_writes_follow_the_firmware_format() {
        let flow = AutoStopMode::FlowStop;
        let cup = AutoStopMode::CupRemoval;
        // Mini (captured format) and Ultra <= V3.1.2 (documented V3 format).
        for fw in [141, 143, 300, 312] {
            assert_eq!(
                BookooFirmware(fw).auto_stop_writes(flow),
                vec![hex6("030a0b000002")],
                "fw {fw}"
            );
            assert_eq!(
                BookooFirmware(fw).auto_stop_writes(cup),
                vec![hex6("030a0b010003")],
                "fw {fw}"
            );
        }
        // Ultra >= V4.0.0: never the V3 frame; both automatic submodes.
        for fw in [400, 401, 403, 500] {
            assert_eq!(
                BookooFirmware(fw).auto_stop_writes(flow),
                vec![hex6("030a0b010003"), hex6("030a0b020000")],
                "fw {fw}"
            );
            assert_eq!(
                BookooFirmware(fw).auto_stop_writes(cup),
                vec![hex6("030a0b010201"), hex6("030a0b020202")],
                "fw {fw}"
            );
        }
        // Ultra betas between the two (V3.2.4) and unknown models: nothing.
        for fw in [313, 324, 399, 0, 202, 1234] {
            assert!(
                BookooFirmware(fw).auto_stop_writes(flow).is_empty(),
                "fw {fw}"
            );
            assert!(
                BookooFirmware(fw).auto_stop_writes(cup).is_empty(),
                "fw {fw}"
            );
        }
    }

    #[test]
    fn keep_awake_and_shutdown_are_version_gated() {
        let keep = |fw| BookooFirmware(fw).keep_awake_command().copied();
        let off = |fw| BookooFirmware(fw).shutdown_command().copied();
        // Mini <= V1.4.1: neither.
        for fw in [100, 102, 141] {
            assert_eq!(keep(fw), None, "fw {fw}");
            assert_eq!(off(fw), None, "fw {fw}");
        }
        // Mini V1.4.2 exactly: 12 / 11.
        assert_eq!(keep(142), Some(hex6("030a1200001b")));
        assert_eq!(off(142), Some(hex6("030a11000018")));
        // Mini >= V1.4.3: 25 / 15.
        for fw in [143, 144, 160] {
            assert_eq!(keep(fw), Some(hex6("030a2500002c")), "fw {fw}");
            assert_eq!(off(fw), Some(hex6("030a1500001c")), "fw {fw}");
        }
        // Mini beta V1.5.0: neither.
        assert_eq!(keep(150), None);
        assert_eq!(off(150), None);
        // Ultra V3: neither; V4.0.0: shutdown only; >= V4.0.1: both.
        for fw in [300, 312, 324] {
            assert_eq!(keep(fw), None, "fw {fw}");
            assert_eq!(off(fw), None, "fw {fw}");
        }
        assert_eq!(keep(400), None);
        assert_eq!(off(400), Some(hex6("030a1500001c")));
        for fw in [401, 403] {
            assert_eq!(keep(fw), Some(hex6("030a2500002c")), "fw {fw}");
            assert_eq!(off(fw), Some(hex6("030a1500001c")), "fw {fw}");
        }
        // 11 / 12 never go to an Ultra or another Mini.
        for fw in [141, 143, 312, 400, 401, 1234] {
            assert_ne!(keep(fw), Some(KEEP_AWAKE_V1_4_2), "fw {fw}");
            assert_ne!(off(fw), Some(SHUTDOWN_V1_4_2), "fw {fw}");
        }
    }

    /// A Bookoo that has answered `03 0c` with `fw` and, if given, `03 0e`
    /// with `active_mode`.
    fn bookoo_with(fw: Option<u16>, active_mode: Option<u8>) -> BookooScale {
        let mut scale = BookooScale::default();
        if let Some(firmware_version) = fw {
            scale.absorb(&CommandResponse::Serial {
                firmware_version,
                serial: String::new(),
                anti_mistouch: false,
            });
        }
        if let Some(active_mode) = active_mode {
            scale.absorb(&CommandResponse::Settings {
                active_mode,
                enabled_modes: 0b111,
            });
        }
        scale
    }

    #[test]
    fn nothing_version_gated_is_sent_before_the_firmware_is_known() {
        let scale = BookooScale::default();
        assert_eq!(scale.firmware(), None);
        assert!(scale.auto_stop_writes(AutoStopMode::FlowStop).is_empty());
        assert!(scale.auto_stop_writes(AutoStopMode::CupRemoval).is_empty());
        assert_eq!(scale.keep_awake_command(), None);
        assert_eq!(scale.shutdown_command(), None);
        assert!(!scale.reports_auto_stop());
    }

    #[test]
    fn a_serial_reply_records_the_firmware() {
        // The real 03 0c frame from a Mini on V1.4.1.
        let frame = hex20("030c008d534e32343030643636613839653701ce");
        let mut scale = BookooScale::default();
        scale.absorb(&parse_command_response(&frame).expect("a 03 0c response"));
        assert_eq!(scale.firmware(), Some(BookooFirmware(141)));
        assert!(scale.reports_auto_stop());
        assert_eq!(
            scale.auto_stop_writes(AutoStopMode::CupRemoval),
            vec![hex6("030a0b010003")]
        );
    }

    #[test]
    fn tare_sends_tare_and_start_only_in_auto_mode() {
        // Mode unknown, Flow-Rate, Timer: 01.
        for mode in [None, Some(0), Some(1)] {
            assert_eq!(
                *bookoo_with(Some(143), mode).tare_command(),
                hex6("030a01000008"),
                "mode {mode:?}"
            );
        }
        // A Mini in Auto (index 2): 07.
        for fw in [141, 143] {
            assert_eq!(
                *bookoo_with(Some(fw), Some(2)).tare_command(),
                hex6("030a0700000e"),
                "fw {fw}"
            );
        }
        // Index 2 means Auto only on a Mini: not with the firmware unknown,
        // and not on an Ultra, whose modes the index doesn't describe.
        for fw in [None, Some(312), Some(403)] {
            assert_eq!(
                *bookoo_with(fw, Some(2)).tare_command(),
                hex6("030a01000008"),
                "fw {fw:?}"
            );
        }
        // Leaving Auto goes back to 01.
        let mut scale = bookoo_with(Some(143), Some(2));
        scale.absorb(&CommandResponse::Settings {
            active_mode: 1,
            enabled_modes: 0b010,
        });
        assert_eq!(*scale.tare_command(), hex6("030a01000008"));
    }

    /// An Ultra `03 0D` automatic-mode event frame for event byte `event`.
    fn auto_event(event: u8) -> [u8; 20] {
        let mut f = [0u8; 20];
        f[..3].copy_from_slice(&[0x03, 0x0D, event]);
        f[19] = f[..19].iter().fold(0, |a, &b| a ^ b);
        f
    }

    #[test]
    fn parse_auto_mode_event_decodes_the_five_events_and_rejects_the_rest() {
        assert_eq!(
            parse_auto_mode_event(&auto_event(0)),
            Some(AutoModeEvent::Stopped)
        );
        assert_eq!(
            parse_auto_mode_event(&auto_event(1)),
            Some(AutoModeEvent::Started)
        );
        assert_eq!(
            parse_auto_mode_event(&auto_event(2)),
            Some(AutoModeEvent::Ready)
        );
        assert_eq!(
            parse_auto_mode_event(&auto_event(3)),
            Some(AutoModeEvent::ExitReady)
        );
        assert_eq!(
            parse_auto_mode_event(&auto_event(4)),
            Some(AutoModeEvent::ExitDone)
        );
        assert_eq!(parse_auto_mode_event(&auto_event(5)), None);
        let mut corrupt = auto_event(2);
        corrupt[19] ^= 0xFF;
        assert_eq!(parse_auto_mode_event(&corrupt), None);
        assert_eq!(parse_auto_mode_event(&auto_event(2)[..19]), None);
        // A weight frame or a settings reply is not an event.
        assert_eq!(
            parse_auto_mode_event(&hex20("030b000000012b0000002b0000640096010000fa")),
            None
        );
        assert_eq!(
            parse_auto_mode_event(&hex20("030e02040000000000000000000000000000000b")),
            None
        );
    }

    #[test]
    fn an_ultra_tares_with_tare_and_start_while_its_automatic_submode_is_ready_running_or_done() {
        let tare = hex6("030a01000008");
        let tare_and_start = hex6("030a0700000e");
        let mut ultra = bookoo_with(Some(403), None);
        assert_eq!(*ultra.tare_command(), tare);
        for (event, expected) in [
            (2, tare_and_start), // ready
            (1, tare_and_start), // running
            (0, tare_and_start), // done
            (4, tare),           // exit done: idle
            (2, tare_and_start), // ready again
            (3, tare),           // exit ready: idle
        ] {
            ultra.absorb_frame(&auto_event(event));
            assert_eq!(*ultra.tare_command(), expected, "after event {event}");
        }
        // The same events on a Mini don't move its tare: it never sends them,
        // and its Auto comes from the mode index.
        let mut mini = bookoo_with(Some(143), Some(1));
        mini.absorb_frame(&auto_event(0));
        assert_eq!(*mini.tare_command(), tare);
    }

    #[test]
    fn only_a_mini_has_the_flow_timer_auto_modes() {
        assert!(bookoo_with(Some(141), None).has_mini_modes());
        assert!(!bookoo_with(None, None).has_mini_modes());
        assert!(!bookoo_with(Some(312), None).has_mini_modes());
        assert!(!bookoo_with(Some(403), None).has_mini_modes());
    }

    #[test]
    fn an_ultra_v4_sets_auto_stop_per_target_mode() {
        let v4 = bookoo_with(Some(400), None);
        assert_eq!(v4.auto_stop_targets(), &AutoStopTarget::ALL);
        assert_eq!(
            v4.auto_stop_target_write(AutoStopTarget::Timing, AutoStopMode::CupRemoval),
            Some(hex6("030a0b010201"))
        );
        assert_eq!(
            v4.auto_stop_target_write(AutoStopTarget::Ratio, AutoStopMode::FlowStop),
            Some(hex6("030a0b020000"))
        );
        // One setting elsewhere: no per-target write.
        for fw in [None, Some(143), Some(312), Some(324)] {
            let scale = bookoo_with(fw, None);
            assert!(scale.auto_stop_targets().is_empty(), "fw {fw:?}");
            assert_eq!(
                scale.auto_stop_target_write(AutoStopTarget::Timing, AutoStopMode::FlowStop),
                None,
                "fw {fw:?}"
            );
        }
        assert_eq!(AutoStopTarget::from_id(1), Some(AutoStopTarget::Timing));
        assert_eq!(AutoStopTarget::from_id(2), Some(AutoStopTarget::Ratio));
        assert_eq!(AutoStopTarget::from_id(0), None);
    }

    #[test]
    fn only_a_mini_reports_byte_18_as_the_auto_stop_setting() {
        assert!(bookoo_with(Some(141), None).reports_auto_stop());
        assert!(bookoo_with(Some(143), None).reports_auto_stop());
        assert!(!bookoo_with(Some(312), None).reports_auto_stop());
        assert!(!bookoo_with(Some(403), None).reports_auto_stop());
        assert!(!bookoo_with(Some(1234), None).reports_auto_stop());
    }

    #[test]
    fn the_flow_sign_byte_negates_the_flow() {
        // Bytes 11-12 = 0x01A4 = 420 -> 4.20 g/s; [10] = '-'.
        let mut frame = hex20("030b000000012b0000002b01a464009601000000");
        frame[19] = frame[..19].iter().fold(0, |a, &b| a ^ b);
        let p = parse_packet(&frame).expect("a weight frame");
        assert!(
            (p.flow_g_per_s - 4.20).abs() < 0.001,
            "flow {}",
            p.flow_g_per_s
        );
        frame[10] = b'-';
        frame[19] = frame[..19].iter().fold(0, |a, &b| a ^ b);
        let p = parse_packet(&frame).expect("a weight frame");
        assert_eq!(p.flow_indicator, b'-');
        assert!(
            (p.flow_g_per_s + 4.20).abs() < 0.001,
            "flow {}",
            p.flow_g_per_s
        );
        assert_eq!(p.flow_raw, 0x01A4);
    }

    #[test]
    fn set_anti_mistouch_matches_the_captured_bytes() {
        assert_eq!(set_anti_mistouch(true), hex6("030a10010018"));
        assert_eq!(set_anti_mistouch(false), hex6("030a10000019"));
    }

    #[test]
    fn query_constants_match_the_captured_bytes() {
        assert_eq!(QUERY_SERIAL, hex6("030a0a000003"));
        assert_eq!(QUERY_SETTINGS, hex6("030a0f000006"));
    }

    #[test]
    fn parse_command_response_decodes_a_serial_response_with_anti_mistouch_on() {
        // Real 03 0c response: fw 1.4.1 (0x008d = 141), serial "SN2400d66a89e7",
        // [18] = 0x01 -> anti-mistouch on.
        let frame = hex20("030c008d534e32343030643636613839653701ce");
        let response = parse_command_response(&frame).expect("a 03 0c response");
        assert_eq!(
            response,
            CommandResponse::Serial {
                firmware_version: 141,
                serial: "SN2400d66a89e7".to_owned(),
                anti_mistouch: true,
            }
        );
    }

    #[test]
    fn parse_command_response_decodes_a_serial_response_with_anti_mistouch_off() {
        // Same 03 0c response with [18] = 0x00 -> anti-mistouch off; the
        // checksum [19] flips from 0xce to 0xcf accordingly.
        let frame = hex20("030c008d534e32343030643636613839653700cf");
        let response = parse_command_response(&frame).expect("a 03 0c response");
        assert_eq!(
            response,
            CommandResponse::Serial {
                firmware_version: 141,
                serial: "SN2400d66a89e7".to_owned(),
                anti_mistouch: false,
            }
        );
    }

    #[test]
    fn parse_command_response_decodes_a_settings_response() {
        // Real 03 0e response: [2] = 0x02 -> Auto active, [3] = 0x04 -> only
        // the Auto mode (bit 2) enabled.
        let frame = hex20("030e02040000000000000000000000000000000b");
        let response = parse_command_response(&frame).expect("a 03 0e response");
        assert_eq!(
            response,
            CommandResponse::Settings {
                active_mode: 2,
                enabled_modes: 0b100,
            }
        );
    }

    #[test]
    fn parse_command_response_rejects_a_wrong_length_frame() {
        assert_eq!(parse_command_response(&[0; 19]), None);
        assert_eq!(parse_command_response(&[0; 21]), None);
    }

    #[test]
    fn parse_command_response_rejects_an_unknown_header() {
        // A 03 0b weight-notification header is not a command response.
        let frame = hex20("030b000000012b0000002b0000640096010000fa");
        assert_eq!(parse_command_response(&frame), None);
    }

    #[test]
    fn parse_command_response_rejects_a_corrupt_checksum() {
        // A command response carries no field a corrupt frame could still
        // surface, so a checksum mismatch rejects the frame outright.
        let mut frame = hex20("030e02040000000000000000000000000000000b");
        frame[19] ^= 0xFF;
        assert_eq!(parse_command_response(&frame), None);
    }

    #[test]
    fn a_corrupt_checksum_is_rejected() {
        // Take a known-good packet and flip the checksum byte.
        let mut packet = hex20("030b000000012b0000002b0000640096010000fa");
        assert!(parse_packet(&packet).is_some());
        packet[19] ^= 0xFF;
        assert_eq!(parse_packet(&packet), None);
        assert_eq!(parse_weight(&packet), None);
    }
}
