//! The DE1 firmware flash state machine — pure logic, sans-IO.
//!
//! The shell owns the GATT link, the clock and keeping the process alive; this
//! module owns *every* protocol decision: which bytes go where, in what order,
//! when a phase is complete, when to give up, and how long the link stays
//! locked. It is fed write ACKs, `FWMapRequest` (A009) frames, build-number
//! reads, link events and clock ticks, and answers with [`FlashAction`]s plus a
//! [`FlashStatus`] snapshot.
//!
//! ## The sequence (cited against the three reference apps)
//!
//! 1. **Preconditions** — checked by [`CremaCore::firmware_flash_blockers`]
//!    before anything starts (see that function).
//! 2. **Sleep the machine and lock out other writes.** decaid requests
//!    `sleeping` before erase (`unified_de1.firmware.dart:12`) and holds an
//!    exclusive MMR gate for the whole flash (`firmware_mmr_gate.dart:18-25`);
//!    de1app sends Sleep on the update-mode connect (`de1_comms.tcl:307-312`).
//!    Decenza dropped the Sleep in `c6117361` without a rationale; crema
//!    follows decaid. Crema writes `RequestedState = Sleep`, waits for its ACK
//!    plus [`SLEEP_SETTLE_MS`], and from here [`FirmwareFlash::locks_writes`]
//!    makes the core (and the shells) drop every other DE1 write.
//! 3. **Erase** the spare bank: subscribe to A009, then write
//!    `FWMapRequest{WindowIncrement 0, FWToErase 1, FWToMap 1, FirstError
//!    FF FF FF}` (decaid `unified_de1.firmware.dart:45-52`, `:104-109`). Erase
//!    is complete only on a frame with `WindowIncrement == 0 && FWToErase == 0
//!    && FWToMap == 1 && FirstError == FF FF FF` — decaid's `_isEraseComplete`
//!    (`unified_de1.firmware.dart:175-180`; `doc/AI_BLE_NOTES.md:613-617`) —
//!    that arrives **after the erase request's own ACK** (Decenza `04fca499`).
//!    Requiring the `FF FF FF` echo matters because crema also polls (below): a
//!    late or cached verify verdict (`… FF FF FD` or a bad-block address) has
//!    the other three fields of an erase-complete frame (Decenza
//!    `firmwareupdater.cpp:853-861`). Crema races notifications against a
//!    fresh read of A009 every [`FW_MAP_POLL_INTERVAL_MS`] (decaid `a207a74d`,
//!    `unified_de1.firmware.dart:122-173`). If the DE1 says nothing at all on
//!    A009 for [`ERASE_FALLBACK_MS`] the upload starts anyway (any decoded
//!    A009 frame after the ACK means it is answering, and then only a real
//!    erase-complete starts the upload) — de1app always waits a
//!    fixed 10 s (`de1_comms.tcl:1048`), Decenza falls back after 10 s
//!    (`firmwareupdater.cpp:40`); a 1 s wait caused Decenza's `0x4800` verify
//!    failures. If it keeps reporting "still erasing", crema fails at
//!    [`ERASE_TIMEOUT_MS`] (decaid's 60 s).
//! 4. **Upload** the image in 16-byte chunks to `WriteToMMR` (A006), one
//!    write-with-response in flight, the next sent on the previous ACK (de1app
//!    `bluetooth.tcl:3303-3312`, decaid `unified_de1.firmware.dart:228-246`).
//!    Each packet is `[0x10][offset as u24 BIG-endian][16 bytes]` — de1app
//!    `de1_comms.tcl:1120` (`"\x10" + make_U24P0 offset`, `binary.tcl:259-264`
//!    packs high byte first), Decenza `firmwarepackets.h:51-62`. Decenza's
//!    little-endian addresses fired the pumps (`FIRMWARE_UPDATE.md` lesson 2),
//!    so the frame comes from [`firmware_write_frame`], golden-tested. No
//!    chunk is ever retried (none of the reference apps does).
//! 5. **Verify**: after the last ACK wait [`POST_UPLOAD_SETTLE_MS`] (Decenza),
//!    re-subscribe to A009 (de1app `de1_comms.tcl:1095`, Decenza
//!    `firmwareupdater.cpp:819-822`), write `FWMapRequest{0, 0, 1, FF FF FF}`.
//!    A frame is a verdict only when `WindowIncrement == 0 && FWToErase == 0 &&
//!    FWToMap == 1 && FirstError != FF FF FF` (decaid
//!    `_isTerminalVerificationResponse`, `unified_de1.firmware.dart:182-187`;
//!    Decenza `04fca499`). `FF FF FD` is success; anything else is the first
//!    bad offset. The verify request is written **once** and never re-sent:
//!    de1app (`de1_comms.tcl:1108-1115`), decaid (`unified_de1.firmware.dart:
//!    68-75`) and Decenza (`firmwareupdater.cpp:823`) each send it once and
//!    then only wait or poll. If its write fails or never ACKs, it may still
//!    have reached the DE1, so crema keeps polling A009 for the verdict until
//!    [`VERIFY_TIMEOUT_MS`] after the request (the DE1 is then done checking
//!    before the user is told to power-cycle) and treats the outcome as
//!    *unconfirmed*. Once the request may have reached the DE1, nothing after
//!    it is a definite failure: a lost verdict, a timeout or a link drop all
//!    lead to the unconfirmed power-cycle path (Decenza's "ambiguous verify",
//!    `firmwareupdater.cpp:224-233`; `FIRMWARE_UPDATE.md:59`). A link drop
//!    takes that path only after the same [`VERIFY_TIMEOUT_MS`] wait: the DE1
//!    goes on checking without Bluetooth, and nothing shows that cutting its
//!    power mid-check is safe, so the user isn't told to power-cycle until it
//!    can have finished. If the link comes back meanwhile, polling resumes and
//!    a verdict for an ACKed request still counts.
//! 6. **Power-cycle**: the DE1 keeps running the old image until it is
//!    switched off and on (de1app pages 4/5, `de1_skin_settings.tcl:1250-1265`;
//!    Decenza `AwaitingReboot`). The lock stays held (Decenza keeps its guard
//!    through `AwaitingReboot`, `firmwareupdater.cpp:335-337`, and refuses a new
//!    flash there, `:404-412`).
//! 7. **Read back** the build at MMR `0x800010` once the link has dropped and
//!    come back (the flash's own [`FlashAction::ReadBuild`], allowed through
//!    the lock); only an **exact** match with the target build is success
//!    (Decenza `firmwareupdater.cpp:120-200`), which releases the lock. The
//!    user can also exit explicitly ([`CremaCore::firmware_flash_exit`]).
//!
//! ## When the lock is released
//!
//! - **Success**, or the user's explicit exit from the power-cycle step.
//! - **A failure or cancel before erase** or after erase completed: at once.
//! - **A failure or cancel during erase**: only once the DE1 reports
//!   erase-complete on A009, or [`ERASE_TIMEOUT_MS`] after the erase request —
//!   de1app's one guard blocks MMR writes exactly while erasing
//!   (`de1_comms.tcl:1160-1165`, `95df6c19`). A cancel also sends Sleep, like
//!   decaid's `cancelFirmwareUpload` (`unified_de1.dart:597-608`).
//!
//! ## What a failure leaves
//!
//! Every write lands in the DE1's spare flash bank and, per Decenza
//! (`FIRMWARE_UPDATE.md` "Safety: dual-bank flash"), the bootloader switches
//! banks only after a successful verify; decaid's real-hardware run of
//! 2026-07-16 failed verify and the machine kept its old build (without a
//! power-cycle). That is what the evidence supports — the shells say "Decent's
//! apps report…", not more. Retry restarts from erase; there is no resume.

use de1_domain::{
    FIRMWARE_CHUNK_LEN, FirmwareArtifact, FirmwareImageError, firmware_manifest,
    validate_firmware_image,
};
use de1_protocol::{
    FIRST_ERROR_NONE, FIRST_ERROR_REQUEST, FWMapRequest, MachineState, MmrRegister,
    firmware_write_frame, mmr, requested_state,
};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::event::WriteTarget;

/// Pause after the Sleep request's ACK before erasing. Not in decaid (it erases
/// straight after `requestState`); de1app's Bengle path waits 700 ms after
/// Sleep before its next state change (`bengle.tcl:522`). One second lets the
/// machine leave its heating loop before the flash controller is busy.
pub const SLEEP_SETTLE_MS: u64 = 1_000;

/// No A009 frame at all for this long after the erase ACK: start the upload
/// anyway (de1app fixed 10 s, `de1_comms.tcl:1048`; Decenza fallback,
/// `firmwareupdater.cpp:40`). Measured erase: 1.31 s (Decenza `04fca499`).
/// Only silence counts: once any A009 frame decodes after the ACK, the DE1
/// is answering and the upload waits for a real erase-complete (up to
/// [`ERASE_TIMEOUT_MS`]).
pub const ERASE_FALLBACK_MS: u64 = 10_000;

/// The DE1 keeps reporting "still erasing": give up after this (decaid
/// `firmwareEraseTimeout`, `unified_de1.dart:74`). Also the longest the lock is
/// held after a failure or cancel during erase.
pub const ERASE_TIMEOUT_MS: u64 = 60_000;

/// Fresh-read cadence of A009 during erase and verify (decaid
/// `_firmwareMapPollInterval`, `unified_de1.firmware.dart:3`).
pub const FW_MAP_POLL_INTERVAL_MS: u64 = 250;

/// A poll read with no answer for this long is abandoned and re-issued
/// (decaid `_firmwareMapPollReadTimeout`, `AI_BLE_NOTES.md:651`).
pub const FW_MAP_POLL_READ_TIMEOUT_MS: u64 = 2_000;

/// Any single write unacknowledged this long fails it. The shells bound their
/// own writes at 5 s, so this is a backstop.
pub const WRITE_ACK_TIMEOUT_MS: u64 = 15_000;

/// Pause between the last chunk's ACK and the verify request (Decenza
/// `m_postUploadSettleMs`, `FIRMWARE_UPDATE.md` log: "settling 1500 ms").
pub const POST_UPLOAD_SETTLE_MS: u64 = 1_500;

/// Verification bound (decaid `firmwareVerificationTimeout`,
/// `unified_de1.dart:75`; Decenza uses 60 s), counted from the verify request.
/// Past it the outcome is unconfirmed, not failed.
///
/// The verify request is written **once** and never re-sent, like de1app
/// (`de1_comms.tcl:1108-1115`), decaid (`unified_de1.firmware.dart:68-75`,
/// then only waits and polls) and Decenza (`firmwareupdater.cpp:823`, then
/// only waits). A second verify-and-map after a first one succeeded is
/// undocumented A/B behaviour, and a failed or timed-out write may still be
/// on the air. So a lost write waits for the DE1's verdict on A009 up to this
/// bound and then goes to the unconfirmed power-cycle path.
pub const VERIFY_TIMEOUT_MS: u64 = 120_000;

/// While waiting for the power-cycle, re-read the build this often after the
/// link comes back (de1app's Bengle monitor polls every 3 s, `bengle.tcl:613`).
pub const BUILD_READ_INTERVAL_MS: u64 = 3_000;

/// …for this long after each link restore (then until the next restore).
pub const BUILD_READ_WINDOW_MS: u64 = 60_000;

/// After a link-up during the power-cycle wait, how long to wait for the DE1's
/// serial number before falling back to the Bluetooth id (or "unknown").
pub const SERIAL_WAIT_MS: u64 = 5_000;

/// ETA guess per chunk before enough have been ACKed to measure: Android's
/// ~30 ms per write-with-response ACK (Decenza `FIRMWARE_UPDATE.md:22`).
pub const ESTIMATED_MS_PER_CHUNK: u64 = 30;

/// Chunks ACKed before the ETA switches from the estimate to the measured rate
/// (de1app shows its ETA after 0.3 %, `vars.tcl:4089`).
pub const ETA_MEASURE_AFTER_CHUNKS: u32 = 100;

/// Rough verify duration added to the ETA (Decenza's log: ~2 s).
pub const ESTIMATED_VERIFY_MS: u64 = 5_000;

/// Where a flash is. The shells map each to copy.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FlashPhase {
    /// Putting the machine to sleep before erasing.
    Preparing,
    /// Erasing the DE1's spare flash bank.
    Erasing,
    /// Streaming the image.
    Uploading,
    /// Waiting for the DE1 to check the written image.
    Verifying,
    /// Verified, or the verdict was lost: the DE1 must be switched off and on;
    /// the build it reports afterwards decides.
    AwaitingPowerCycle,
    /// The DE1 restarted on the new build.
    Succeeded,
    /// Stopped by an error before the verify request.
    Failed,
    /// Stopped by the user.
    Cancelled,
}

/// Why a flash failed (before the verify request; after it, the outcome is
/// unconfirmed rather than failed — see [`FlashStatus::verify_unconfirmed`]).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum FlashFailure {
    /// The DE1 disconnected before verification.
    LinkLost {
        /// The phase the link dropped in.
        phase: FlashPhase,
    },
    /// A write failed.
    WriteFailed {
        /// The phase the write belonged to.
        phase: FlashPhase,
        /// The shell's error text.
        message: String,
    },
    /// A write was never acknowledged.
    AckTimeout {
        /// The phase the write belonged to.
        phase: FlashPhase,
    },
    /// The DE1 kept reporting "erasing" past [`ERASE_TIMEOUT_MS`].
    EraseTimeout,
    /// The DE1 found a bad block at `first_error` (a byte offset).
    VerifyFailed {
        /// The offset of the first corrupt block.
        first_error: u32,
    },
}

/// One thing the shell must do, in order. The shell performs actions one at a
/// time and reports back: a `Write` with
/// [`CremaCore::firmware_flash_on_write_ack`] /
/// [`CremaCore::firmware_flash_on_write_failed`], a `ReadFwMap` with
/// [`CremaCore::firmware_flash_on_fw_map`] /
/// [`CremaCore::firmware_flash_on_read_failed`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum FlashAction {
    /// Write `data` with response to `target` and report the ACK. Allowed
    /// through the write lock (it *is* the flash).
    Write {
        /// The characteristic.
        target: WriteTarget,
        /// The exact bytes.
        data: Vec<u8>,
    },
    /// Enable notifications on `FWMapRequest` (A009) and route each frame to
    /// [`CremaCore::firmware_flash_on_fw_map`]. Best effort — no report; the
    /// poll covers a failed subscribe.
    SubscribeFwMap,
    /// Read A009 once (a fresh GATT read) and report the bytes.
    ReadFwMap,
    /// Write `data` (an MMR `0x800010` read request) to
    /// [`WriteTarget::De1MmrRequest`] through the lock, no report: the reply
    /// arrives as an ordinary MMR notification, which feeds the flash.
    ReadBuild {
        /// The exact bytes.
        data: Vec<u8>,
    },
    /// Write `data` (an MMR `SerialNumber` read request) to
    /// [`WriteTarget::De1MmrRequest`] through the lock, no report — like
    /// [`FlashAction::ReadBuild`]. The serial tells the DE1 being updated from
    /// another one, across browser restarts (audit r4 LOW-1).
    ReadSerial {
        /// The exact bytes.
        data: Vec<u8>,
    },
}

/// A snapshot for the UI.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlashStatus {
    /// Where the flash is.
    pub phase: FlashPhase,
    /// The manifest entry being flashed.
    pub artifact: FirmwareArtifact,
    /// The build being installed.
    pub target_build: u32,
    /// Upload writes in total.
    pub chunks_total: u32,
    /// Upload writes acknowledged.
    pub chunks_acked: u32,
    /// Overall progress, 0–100.
    pub percent: f32,
    /// Seconds left until verification ends, when known.
    pub eta_seconds: Option<u32>,
    /// `true` while the ETA is a guess rather than measured.
    pub eta_is_estimate: bool,
    /// Seconds since the flash started.
    pub elapsed_seconds: u32,
    /// Whether [`CremaCore::firmware_flash_cancel`] would act.
    pub can_cancel: bool,
    /// Whether other writes are locked out. The shells drop every non-flash
    /// DE1 write and skip their reconnect sweeps while this is `true`.
    pub locks_writes: bool,
    /// A failure or cancel happened during erase and the lock is held until
    /// the DE1 finishes erasing (at most [`ERASE_TIMEOUT_MS`]).
    pub erase_settling: bool,
    /// Whether [`CremaCore::firmware_flash_exit`] would act.
    pub can_exit: bool,
    /// Why it failed.
    pub failure: Option<FlashFailure>,
    /// The verify outcome was lost (link drop, timeout, failed request): the
    /// DE1 may be running either build until it is switched off and on.
    pub verify_unconfirmed: bool,
    /// The link has dropped and come back since verification (a power-cycle,
    /// or only a Bluetooth reconnect — the two can't be told apart).
    pub machine_restarted: bool,
    /// After reconnecting the DE1 reported this build, not the target.
    pub restarted_on_build: Option<u32>,
    /// Whether the flash currently sees the DE1 link up.
    pub link_up: bool,
    /// A different DE1 than the one being updated is connected (its serial,
    /// or its Bluetooth id): it is not read, and the lock stays until the DE1
    /// being updated reports or the user exits.
    pub foreign_machine: Option<String>,
    /// The serial number of the DE1 being updated, when known (non-zero).
    /// The shells persist it with the power-cycle marker.
    pub machine_serial: Option<u32>,
    /// The connected DE1 could not be identified (no serial on one side and
    /// no comparable Bluetooth id): it is *not* treated as a different DE1,
    /// but Crema can't confirm it is the same one.
    pub identity_unknown: bool,
    /// How long the DE1 may still be erasing (during the erase, or after an
    /// interrupted one): the shells persist it so a restart keeps the lock.
    pub erase_hold_ms_left: Option<u32>,
    /// While verifying: how long until [`VERIFY_TIMEOUT_MS`] after the verify
    /// request, i.e. how long the DE1 may still be checking the image. The
    /// shells persist it so a page reload or process restart inside the
    /// window comes back as "still verifying, don't switch it off" rather
    /// than as the power-cycle step ([`FirmwareFlash::restore_verifying`]).
    pub verify_hold_ms_left: Option<u32>,
}

/// Why [`CremaCore::firmware_flash_start`] refused.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum FlashStartError {
    /// A precondition failed.
    Blocked {
        /// Every failing precondition.
        blockers: Vec<FlashBlocker>,
    },
    /// The image is not a pinned official image.
    InvalidImage {
        /// Why.
        error: FirmwareImageError,
    },
}

/// What one call into the flash returns.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlashStep {
    /// Do these, in order.
    pub actions: Vec<FlashAction>,
    /// The snapshot after this step (`None` when no flash exists).
    pub status: Option<FlashStatus>,
    /// Set when a start was refused.
    pub error: Option<FlashStartError>,
    /// Diagnostic lines from this step, each stamped `[+mm:ss]` from the
    /// start of the attempt. The shells append them to their event log.
    #[serde(default)]
    pub log: Vec<String>,
}

impl FlashStep {
    /// Serialize for the FFI / wasm bridges.
    #[must_use]
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| r#"{"actions":[]}"#.to_owned())
    }
}

/// A precondition that stops a flash from starting — the strictest of the
/// reference apps combined (see [`CremaCore::firmware_flash_blockers`]).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum FlashBlocker {
    /// No DE1 link.
    NotConnected,
    /// Connected but the post-connect setup has not finished.
    NotReady,
    /// This device mirrors another (secondary); only the primary may flash.
    ReadOnlyMirror,
    /// A flash is running, waiting for the DE1's power-cycle, or settling
    /// after an erase-phase failure.
    FlashInProgress,
    /// MMR `0x800010` has not been read.
    BuildUnknown,
    /// MMR `MachineModel` has not been read.
    ModelUnknown,
    /// Not a classic DE1 model (Bengle, or a value outside the known table).
    UnsupportedModel {
        /// The raw `MachineModel` value.
        model: u32,
    },
    /// The machine state is unknown.
    StateUnknown,
    /// The machine is not Idle or asleep.
    MachineBusy {
        /// What it is doing.
        state: MachineState,
    },
    /// The machine is refilling or out of water.
    Refilling,
    /// The machine reports an error substate.
    MachineError,
    /// A shot or other flow is running.
    ShotInProgress,
    /// A guided brew is running.
    GuidedBrewActive,
    /// A profile upload is in flight.
    ProfileUploading,
    /// A descale / clean / transport cycle is running.
    MaintenanceRunning,
    /// The tablet is on battery below 50 % and not charging.
    LowPower,
}

/// What the shell knows that the core does not.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlashShellConditions {
    /// A DE1 link is up.
    pub connected: bool,
    /// The connect setup (subscriptions, seed reads, MMR sweep) finished.
    pub ready: bool,
    /// A guided brew session is running in the shell.
    pub guided_brew_active: bool,
    /// Charging, or battery ≥ 50 % (or no battery to speak of).
    pub power_ok: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Pending {
    Sleep,
    Erase,
    Chunk(u32),
    Verify,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Stage {
    Sleep {
        acked_at: Option<u64>,
    },
    Erase {
        acked_at: Option<u64>,
        /// An A009 frame decoded after the erase ACK: the DE1 is answering,
        /// so the silent fallback no longer applies.
        heard: bool,
    },
    Upload,
    Settle {
        since: u64,
    },
    Verify {
        acked_at: Option<u64>,
        /// When the (single) verify request was written.
        sent_at: u64,
        /// Its write failed or never ACKed: not re-sent; any verdict is
        /// treated as unconfirmed.
        write_lost: bool,
    },
    AwaitPowerCycle,
    Succeeded,
    Failed,
    Cancelled,
}

/// The flash itself. Owns the validated image.
#[derive(Debug, Clone)]
pub struct FirmwareFlash {
    image: Vec<u8>,
    artifact: FirmwareArtifact,
    chunk_count: u32,
    stage: Stage,
    pending: Option<(Pending, u64)>,
    chunks_acked: u32,
    started_ms: u64,
    upload_started_ms: Option<u64>,
    ended_ms: Option<u64>,
    poll_sent_at: Option<u64>,
    last_poll_ms: u64,
    failure: Option<FlashFailure>,
    verify_unconfirmed: bool,
    link_up: bool,
    machine_restarted: bool,
    restarted_on_build: Option<u32>,
    last_build_read_ms: u64,
    build_reads_until: u64,
    /// When the erase request was sent.
    erase_started_ms: Option<u64>,
    /// The lock is held until this time after a failure / cancel mid-erase
    /// (cleared early by an erase-complete frame).
    erase_hold_until: Option<u64>,
    /// How long after the verify request the DE1 may still be checking:
    /// [`VERIFY_TIMEOUT_MS`], or what was left of it when a verify was
    /// restored ([`Self::restore_verifying`]).
    verify_window_ms: u64,
    /// Whether the verify window was restored after a reload, so
    /// [`Self::verify_window_ms`] counts from the restore, not the request.
    verify_restored: bool,
    /// The latest time the flash has seen (every input carries one).
    clock_ms: u64,
    /// Diagnostic lines not yet handed to the shell ([`Self::take_log`]).
    log: Vec<String>,
    /// The last A009 frame logged (frames are logged when they change).
    last_frame_logged: Option<Vec<u8>>,
    /// The serial number of the DE1 this flash is for (non-zero), once bound.
    serial: Option<u32>,
    /// The shell's Bluetooth id of that DE1 (Android: its static address;
    /// web: `device.id`, only within one browser session), once bound.
    link_id: Option<String>,
    /// The Bluetooth id of the DE1 on the current link ("" = unknown).
    link_id_now: String,
    /// What the current link's serial read has shown.
    link_serial: LinkSerial,
    /// A build read that arrived while the link's serial was still awaited.
    pending_build: Option<u32>,
    /// A *different* DE1 is connected: it must not satisfy the wait nor
    /// release this flash's lock (audit r3 LOW-2).
    foreign_machine: Option<String>,
    /// The current link's identity fell back to "unknown".
    identity_unknown: bool,
}

/// The current link's serial number, as far as the flash knows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LinkSerial {
    NotAsked,
    Waiting { until: u64 },
    Known(u32),
    Unknown,
}

impl FirmwareFlash {
    /// Validate `image` against the pinned manifest and start: the first
    /// action is the Sleep request.
    ///
    /// # Errors
    ///
    /// The image's [`FirmwareImageError`] if it is not a pinned official
    /// image.
    pub fn begin(
        image: Vec<u8>,
        now_ms: u64,
    ) -> Result<(Self, Vec<FlashAction>), FirmwareImageError> {
        let check = validate_firmware_image(&image);
        let artifact = match (check.ok, check.artifact, check.error) {
            (true, Some(artifact), _) => artifact,
            (_, _, Some(error)) => return Err(error),
            _ => {
                return Err(FirmwareImageError::Malformed {
                    reason: "validation returned no verdict".to_owned(),
                });
            }
        };
        let mut flash = Self::blank(image, artifact, now_ms);
        flash.note_start(now_ms);
        let action = flash.send(Pending::Sleep, now_ms);
        Ok((flash, vec![action]))
    }

    fn blank(image: Vec<u8>, artifact: FirmwareArtifact, now_ms: u64) -> Self {
        let chunk_count = u32::try_from(image.len() / FIRMWARE_CHUNK_LEN).unwrap_or(u32::MAX);
        FirmwareFlash {
            image,
            artifact,
            chunk_count,
            stage: Stage::Sleep { acked_at: None },
            pending: None,
            chunks_acked: 0,
            started_ms: now_ms,
            upload_started_ms: None,
            ended_ms: None,
            poll_sent_at: None,
            last_poll_ms: 0,
            failure: None,
            verify_unconfirmed: false,
            link_up: true,
            machine_restarted: false,
            restarted_on_build: None,
            last_build_read_ms: 0,
            build_reads_until: 0,
            erase_started_ms: None,
            erase_hold_until: None,
            verify_window_ms: VERIFY_TIMEOUT_MS,
            verify_restored: false,
            clock_ms: now_ms,
            log: Vec::new(),
            last_frame_logged: None,
            serial: None,
            link_id: None,
            link_id_now: String::new(),
            link_serial: LinkSerial::NotAsked,
            pending_build: None,
            foreign_machine: None,
            identity_unknown: false,
        }
    }

    /// Tie the flash to the DE1 it is for: its serial number (`0` = unknown)
    /// and the shell's Bluetooth id (`""` = unknown). The first known value of
    /// each wins; later calls don't change it.
    pub fn bind_machine(&mut self, serial: u32, link_id: &str) {
        if self.serial.is_none() && serial != 0 {
            self.serial = Some(serial);
        }
        if self.link_id.is_none() && !link_id.is_empty() {
            self.link_id = Some(link_id.to_owned());
        }
    }

    /// Both Bluetooth ids are known and differ.
    fn ids_differ(&self) -> bool {
        self.link_id.as_deref().is_some_and(|mine| {
            !self.link_id_now.is_empty() && !mine.eq_ignore_ascii_case(&self.link_id_now)
        })
    }

    /// Whether the current link is a different DE1: `None` while its serial
    /// is still awaited. A known serial on both sides decides; otherwise the
    /// Bluetooth ids, when both are known; otherwise it is unknown, which is
    /// never treated as different.
    fn foreign_verdict(&self) -> Option<bool> {
        match (self.serial, self.link_serial) {
            (Some(_), LinkSerial::Waiting { .. }) => None,
            (Some(mine), LinkSerial::Known(theirs)) => Some(mine != theirs),
            _ => Some(self.ids_differ()),
        }
    }

    fn identity_is_unknown(&self) -> bool {
        let serials = matches!(
            (self.serial, self.link_serial),
            (Some(_), LinkSerial::Known(_))
        );
        let ids = self.link_id.is_some() && !self.link_id_now.is_empty();
        !serials && !ids
    }

    fn mark_foreign(&mut self, now_ms: u64) {
        let who = match self.link_serial {
            LinkSerial::Known(s) => format!("serial {s}"),
            _ => self.link_id_now.clone(),
        };
        if self.foreign_machine.as_deref() != Some(who.as_str()) {
            let mine = match (self.serial, self.link_id.as_deref()) {
                (Some(s), _) => format!("serial {s}"),
                (None, Some(id)) => id.to_owned(),
                (None, None) => "?".to_owned(),
            };
            self.note(
                now_ms,
                format!("a different DE1 connected ({who}, not {mine}): not read, lock kept"),
            );
        }
        self.foreign_machine = Some(who);
        self.link_up = false;
        self.pending_build = None;
    }

    /// The identity of the current link is settled: act on it.
    fn resolve_identity(&mut self, now_ms: u64) {
        match self.foreign_verdict() {
            None => {}
            Some(true) => self.mark_foreign(now_ms),
            Some(false) => {
                self.identity_unknown = self.identity_is_unknown();
                if self.identity_unknown {
                    self.note(now_ms, "the connected DE1 can't be identified (no serial or Bluetooth id to compare): not treated as different");
                }
                if let Some(build) = self.pending_build.take() {
                    self.accept_build(build, now_ms);
                }
            }
        }
    }

    /// The DE1 reported its serial number (MMR `SerialNumber`).
    pub fn on_serial(&mut self, serial: u32, now_ms: u64) {
        self.see(now_ms);
        if !matches!(self.link_serial, LinkSerial::Waiting { .. }) {
            return;
        }
        self.link_serial = if serial == 0 {
            LinkSerial::Unknown
        } else {
            LinkSerial::Known(serial)
        };
        self.note(now_ms, format!("connected DE1 reports serial {serial}"));
        self.resolve_identity(now_ms);
    }

    /// Add a diagnostic line, stamped `[+mm:ss]` from the start of this
    /// attempt.
    pub(crate) fn note(&mut self, now_ms: u64, line: impl AsRef<str>) {
        let secs = now_ms.saturating_sub(self.started_ms) / 1000;
        self.log.push(format!(
            "[+{:02}:{:02}] {}",
            secs / 60,
            secs % 60,
            line.as_ref()
        ));
    }

    fn note_start(&mut self, now_ms: u64) {
        let line = format!(
            "start: build {} ({:?}), {} bytes, sha256 {}, {} writes; Sleep requested",
            self.artifact.build,
            self.artifact.channel,
            self.image.len(),
            self.artifact.sha256,
            self.chunk_count
        );
        self.note(now_ms, line);
    }

    /// The diagnostic lines since the last call (the shells append them to
    /// their event log, which the diagnostics export carries).
    pub fn take_log(&mut self) -> Vec<String> {
        std::mem::take(&mut self.log)
    }

    /// Re-create a flash that was waiting for the power-cycle when the shell
    /// went away (page reload, process restart): the lock and the power-cycle
    /// step come back, and the next build read decides. `None` if `build` is
    /// not a pinned image.
    #[must_use]
    pub fn restore_awaiting(build: u32, unconfirmed: bool, now_ms: u64) -> Option<Self> {
        let artifact = firmware_manifest().into_iter().find(|a| a.build == build)?;
        let chunks = artifact.chunk_count;
        let mut flash = Self::blank(Vec::new(), artifact, now_ms);
        flash.chunk_count = chunks;
        flash.chunks_acked = chunks;
        flash.stage = Stage::AwaitPowerCycle;
        flash.verify_unconfirmed = unconfirmed;
        // A fresh shell can't know whether the DE1 restarted meanwhile, so any
        // build read counts: the target means it took.
        flash.machine_restarted = true;
        // Not connected yet: the shell's next link-up (any connect path)
        // calls `on_link_restored`, which opens the build-read window.
        flash.link_up = false;
        flash.note(
            now_ms,
            format!("restored: waiting for the DE1 to restart on build {build} (unconfirmed: {unconfirmed})"),
        );
        Some(flash)
    }

    /// Re-create a flash whose verify window was still open when the shell
    /// went away (page reload, process restart): the DE1 may still be
    /// checking the image without Bluetooth, so the flash comes back in
    /// [`FlashPhase::Verifying`] for the `hold_ms_left` that remained (capped
    /// at [`VERIFY_TIMEOUT_MS`]), polling for the verdict once the link is up.
    /// When the window closes it moves to the unconfirmed power-cycle step,
    /// exactly as a link drop mid-verify does. Restoring straight to the
    /// power-cycle step instead would tell the user to switch the DE1 off
    /// while it may still be verifying. `None` if `build` is not pinned or
    /// the window is over (the shell then restores the power-cycle wait).
    #[must_use]
    pub fn restore_verifying(build: u32, hold_ms_left: u64, now_ms: u64) -> Option<Self> {
        let hold_ms_left = hold_ms_left.min(VERIFY_TIMEOUT_MS);
        if hold_ms_left == 0 {
            return None;
        }
        let artifact = firmware_manifest().into_iter().find(|a| a.build == build)?;
        let chunks = artifact.chunk_count;
        let mut flash = Self::blank(Vec::new(), artifact, now_ms);
        flash.chunk_count = chunks;
        flash.chunks_acked = chunks;
        // The request may or may not have reached the DE1 before the shell
        // went away, so a verdict now is unconfirmed (as after a link drop).
        flash.stage = Stage::Verify {
            acked_at: None,
            sent_at: now_ms,
            write_lost: true,
        };
        flash.verify_window_ms = hold_ms_left;
        flash.verify_restored = true;
        flash.machine_restarted = true;
        flash.link_up = false;
        flash.note(
            now_ms,
            format!(
                "restored: the DE1 may still be verifying build {build} for {} s",
                hold_ms_left.div_ceil(1000)
            ),
        );
        Some(flash)
    }

    /// Re-create the lock of an erase that may still be running when the
    /// shell went away (tab closed or process killed within
    /// [`ERASE_TIMEOUT_MS`] of the erase request): a stopped flash whose lock
    /// expires after `hold_ms_left`. `None` if `build` is not pinned or the
    /// hold is over.
    #[must_use]
    pub fn restore_erase_hold(build: u32, hold_ms_left: u64, now_ms: u64) -> Option<Self> {
        // An erase can't outlast ERASE_TIMEOUT_MS from its request, whatever a
        // wall clock that jumped between the kill and the relaunch claims
        // (audit r3 LOW-1).
        let hold_ms_left = hold_ms_left.min(ERASE_TIMEOUT_MS);
        if hold_ms_left == 0 {
            return None;
        }
        let artifact = firmware_manifest().into_iter().find(|a| a.build == build)?;
        let chunks = artifact.chunk_count;
        let mut flash = Self::blank(Vec::new(), artifact, now_ms);
        flash.chunk_count = chunks;
        flash.stage = Stage::Failed;
        flash.failure = Some(FlashFailure::LinkLost {
            phase: FlashPhase::Erasing,
        });
        flash.erase_hold_until = Some(now_ms + hold_ms_left);
        flash.ended_ms = Some(now_ms);
        flash.link_up = false;
        flash.note(
            now_ms,
            format!(
                "restored: an interrupted erase may still run for {} s",
                hold_ms_left.div_ceil(1000)
            ),
        );
        Some(flash)
    }

    /// The latest shell time the flash has seen. The core feeds this back on
    /// link loss and build reads instead of its own clock, which the connect
    /// sweep can move onto a different (epoch) scale.
    #[must_use]
    pub fn clock_ms(&self) -> u64 {
        self.clock_ms
    }

    /// The build being installed.
    #[must_use]
    pub fn target_build(&self) -> u32 {
        self.artifact.build
    }

    /// Upload writes in total (`image length / 16`).
    #[must_use]
    pub fn chunk_count(&self) -> u32 {
        self.chunk_count
    }

    /// How long the DE1 may still be erasing: during the erase, or after an
    /// interrupted one. The shells persist it so a restart keeps the lock.
    fn erase_hold_ms_left(&self) -> Option<u64> {
        match self.stage {
            Stage::Erase { .. } => self
                .erase_started_ms
                .map(|t| (t + ERASE_TIMEOUT_MS).saturating_sub(self.clock_ms)),
            Stage::Failed | Stage::Cancelled if self.erase_settling() => self
                .erase_hold_until
                .map(|t| t.saturating_sub(self.clock_ms)),
            _ => None,
        }
    }

    /// How long the DE1 may still be checking the image (Verify stage only).
    fn verify_hold_ms_left(&self) -> Option<u64> {
        match self.stage {
            Stage::Verify { sent_at, .. } => {
                Some((sent_at + self.verify_window_ms).saturating_sub(self.clock_ms))
            }
            _ => None,
        }
    }

    fn erase_settling(&self) -> bool {
        matches!(self.stage, Stage::Failed | Stage::Cancelled)
            && self.erase_hold_until.is_some_and(|t| self.clock_ms < t)
    }

    fn active(&self) -> bool {
        matches!(
            self.stage,
            Stage::Sleep { .. }
                | Stage::Erase { .. }
                | Stage::Upload
                | Stage::Settle { .. }
                | Stage::Verify { .. }
        )
    }

    /// Whether every other DE1 write must be dropped: from the Sleep request
    /// until the DE1 reports the target build after a restart (or the user
    /// exits), and while an interrupted erase may still be running.
    #[must_use]
    pub fn locks_writes(&self) -> bool {
        self.active() || self.stage == Stage::AwaitPowerCycle || self.erase_settling()
    }

    /// Whether the user may exit (forget the flash, release the lock): on the
    /// power-cycle step, or once finished.
    #[must_use]
    pub fn can_exit(&self) -> bool {
        self.stage == Stage::AwaitPowerCycle || !self.locks_writes()
    }

    /// `true` once nothing more will happen without the user.
    #[must_use]
    pub fn is_terminal(&self) -> bool {
        matches!(
            self.stage,
            Stage::Succeeded | Stage::Failed | Stage::Cancelled
        )
    }

    fn phase(&self) -> FlashPhase {
        match self.stage {
            Stage::Sleep { .. } => FlashPhase::Preparing,
            Stage::Erase { .. } => FlashPhase::Erasing,
            Stage::Upload => FlashPhase::Uploading,
            Stage::Settle { .. } | Stage::Verify { .. } => FlashPhase::Verifying,
            Stage::AwaitPowerCycle => FlashPhase::AwaitingPowerCycle,
            Stage::Succeeded => FlashPhase::Succeeded,
            Stage::Failed => FlashPhase::Failed,
            Stage::Cancelled => FlashPhase::Cancelled,
        }
    }

    /// The upload frame for chunk `index`: `[0x10][offset u24 BE][16 bytes]`.
    fn chunk_frame(&self, index: u32) -> Vec<u8> {
        let offset = index as usize * FIRMWARE_CHUNK_LEN;
        let end = (offset + FIRMWARE_CHUNK_LEN).min(self.image.len());
        // Offsets stay below 2^24: the largest pinned image is 463 872 bytes.
        let offset_u32 = u32::try_from(offset).unwrap_or(u32::MAX);
        firmware_write_frame(offset_u32, &self.image[offset..end]).unwrap_or_default()
    }

    fn send(&mut self, what: Pending, now_ms: u64) -> FlashAction {
        self.pending = Some((what, now_ms));
        let (target, data) = match what {
            Pending::Sleep => (
                WriteTarget::De1RequestedState,
                vec![requested_state(MachineState::Sleep)],
            ),
            Pending::Erase => (
                WriteTarget::De1FwMapRequest,
                FWMapRequest::erase(1).encode().to_vec(),
            ),
            Pending::Chunk(i) => (WriteTarget::De1MmrWrite, self.chunk_frame(i)),
            Pending::Verify => (
                WriteTarget::De1FwMapRequest,
                FWMapRequest::request_first_error().encode().to_vec(),
            ),
        };
        FlashAction::Write { target, data }
    }

    fn read_serial_action() -> FlashAction {
        FlashAction::ReadSerial {
            data: mmr::read_request(MmrRegister::SerialNumber.address(), 1).to_vec(),
        }
    }

    fn read_build_action() -> FlashAction {
        FlashAction::ReadBuild {
            data: mmr::read_request(MmrRegister::FirmwareVersion.address(), 1).to_vec(),
        }
    }

    /// Leave an active stage for `next`; an interrupted erase keeps the lock
    /// for up to [`ERASE_TIMEOUT_MS`] from the erase request.
    fn stop(&mut self, next: Stage, now_ms: u64) {
        if matches!(self.stage, Stage::Erase { .. }) {
            let since = self.erase_started_ms.unwrap_or(now_ms);
            self.erase_hold_until = Some(since + ERASE_TIMEOUT_MS);
        }
        self.stage = next;
        self.pending = None;
        self.poll_sent_at = None;
        self.ended_ms = Some(now_ms);
    }

    fn fail(&mut self, failure: FlashFailure, now_ms: u64) {
        self.stop(Stage::Failed, now_ms);
        self.failure = Some(failure);
    }

    /// The verify request may have reached the DE1 but its outcome is lost.
    fn unconfirmed(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.verify_unconfirmed = true;
        self.await_power_cycle(now_ms)
    }

    /// Verify is over: wait for the power-cycle. When the link dropped during
    /// the check and is up again, that drop may have been a restart: identify
    /// the DE1 and read its build now, as a reconnect into this step would.
    fn await_power_cycle(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.stage = Stage::AwaitPowerCycle;
        self.pending = None;
        self.poll_sent_at = None;
        if self.machine_restarted && self.link_up {
            let link_id = self.link_id_now.clone();
            return self.on_link_restored_from(now_ms, &link_id);
        }
        Vec::new()
    }

    fn start_upload(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.note(now_ms, "upload started");
        self.stage = Stage::Upload;
        self.poll_sent_at = None;
        self.upload_started_ms = Some(now_ms);
        vec![self.send(Pending::Chunk(0), now_ms)]
    }

    fn send_verify(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.stage = Stage::Verify {
            acked_at: None,
            sent_at: now_ms,
            write_lost: false,
        };
        self.note(now_ms, "verify requested (sent once, never re-sent)");
        vec![
            FlashAction::SubscribeFwMap,
            self.send(Pending::Verify, now_ms),
        ]
    }

    fn see(&mut self, now_ms: u64) {
        self.clock_ms = self.clock_ms.max(now_ms);
    }

    /// The pending write was acknowledged.
    pub fn on_write_ack(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.see(now_ms);
        let Some((what, _)) = self.pending.take() else {
            return Vec::new();
        };
        match (what, self.stage) {
            (Pending::Sleep, Stage::Sleep { .. }) => {
                self.stage = Stage::Sleep {
                    acked_at: Some(now_ms),
                };
                self.note(now_ms, "Sleep acknowledged");
                Vec::new()
            }
            (Pending::Erase, Stage::Erase { heard, .. }) => {
                self.stage = Stage::Erase {
                    acked_at: Some(now_ms),
                    heard,
                };
                self.last_poll_ms = now_ms;
                self.note(now_ms, "erase acknowledged");
                Vec::new()
            }
            (Pending::Chunk(i), Stage::Upload) => {
                self.chunks_acked = i + 1;
                if self.chunks_acked >= self.chunk_count {
                    self.stage = Stage::Settle { since: now_ms };
                    let ms = now_ms.saturating_sub(self.upload_started_ms.unwrap_or(now_ms));
                    self.note(
                        now_ms,
                        format!(
                            "upload done: {} writes in {} s ({} ms per write)",
                            self.chunks_acked,
                            ms / 1000,
                            ms / u64::from(self.chunks_acked.max(1))
                        ),
                    );
                    Vec::new()
                } else {
                    vec![self.send(Pending::Chunk(i + 1), now_ms)]
                }
            }
            (
                Pending::Verify,
                Stage::Verify {
                    sent_at,
                    write_lost: false,
                    ..
                },
            ) => {
                self.stage = Stage::Verify {
                    acked_at: Some(now_ms),
                    sent_at,
                    write_lost: false,
                };
                self.last_poll_ms = now_ms;
                self.note(now_ms, "verify acknowledged");
                Vec::new()
            }
            // An ACK that no longer matches the stage (after a cancel, or the
            // cancel's own Sleep).
            _ => Vec::new(),
        }
    }

    fn write_failed(&mut self, what: Pending, failure: FlashFailure, now_ms: u64) {
        let at = match what {
            Pending::Chunk(i) => format!(" at write {i} of {}", self.chunk_count),
            _ => String::new(),
        };
        self.note(now_ms, format!("{what:?} write failed{at}: {failure:?}"));
        match self.stage {
            // The verify request may or may not have reached the DE1. Never
            // re-send it (no upstream does); wait for its verdict on A009 up
            // to the verify bound, then treat the outcome as unconfirmed.
            Stage::Verify {
                acked_at: None,
                sent_at,
                ..
            } => {
                self.stage = Stage::Verify {
                    acked_at: None,
                    sent_at,
                    write_lost: true,
                };
                self.last_poll_ms = now_ms;
                self.note(
                    now_ms,
                    "verify request not re-sent; waiting for the DE1's verdict",
                );
            }
            _ => self.fail(failure, now_ms),
        }
    }

    /// The pending write failed.
    pub fn on_write_failed(&mut self, message: &str, now_ms: u64) {
        self.see(now_ms);
        if let Some((what, _)) = self.pending.take()
            && self.active()
        {
            let phase = self.phase();
            self.write_failed(
                what,
                FlashFailure::WriteFailed {
                    phase,
                    message: message.to_owned(),
                },
                now_ms,
            );
        }
    }

    /// A poll read failed (logged by the shell; the next tick retries).
    pub fn on_read_failed(&mut self, now_ms: u64) {
        self.see(now_ms);
        self.poll_sent_at = None;
    }

    /// decaid's erase-complete predicate, including the `FF FF FF` echo.
    fn is_erase_complete(frame: &FWMapRequest) -> bool {
        frame.window_increment == 0
            && frame.fw_to_erase == 0
            && frame.fw_to_map == 1
            && frame.first_error == FIRST_ERROR_REQUEST
    }

    /// An A009 frame arrived (notification or poll read).
    pub fn on_fw_map(&mut self, data: &[u8], now_ms: u64) -> Vec<FlashAction> {
        self.see(now_ms);
        self.poll_sent_at = None;
        let Ok(frame) = FWMapRequest::decode(data) else {
            self.note(now_ms, format!("A009 undecodable ({} bytes)", data.len()));
            return Vec::new();
        };
        if self.last_frame_logged.as_deref() != Some(data) {
            self.last_frame_logged = Some(data.to_vec());
            let hex: Vec<String> = data.iter().map(|b| format!("{b:02X}")).collect();
            self.note(
                now_ms,
                format!("A009 {} ({:?})", hex.join(" "), self.phase()),
            );
        }
        let terminal = frame.window_increment == 0
            && frame.fw_to_erase == 0
            && frame.fw_to_map == 1
            && frame.first_error != FIRST_ERROR_REQUEST;
        match self.stage {
            Stage::Erase {
                acked_at: Some(_),
                heard,
            } => {
                if Self::is_erase_complete(&frame) {
                    self.note(now_ms, "erase complete");
                    return self.start_upload(now_ms);
                }
                // "Still erasing", a late or cached verify verdict, or
                // anything else: not complete, but the DE1 is answering, so
                // the silent fallback is off from here.
                if !heard && let Stage::Erase { heard, .. } = &mut self.stage {
                    *heard = true;
                }
                Vec::new()
            }
            Stage::Verify {
                write_lost: true, ..
            } => {
                if terminal {
                    // The DE1 has finished checking, but this can't be tied
                    // to a request known to have arrived: let the build after
                    // a restart decide.
                    self.note(
                        now_ms,
                        format!(
                            "verdict {:06X} after a lost verify write: unconfirmed",
                            frame.first_error
                        ),
                    );
                    return self.unconfirmed(now_ms);
                }
                Vec::new()
            }
            Stage::Verify {
                acked_at: Some(_), ..
            } => {
                if !terminal {
                    return Vec::new();
                }
                if frame.first_error == FIRST_ERROR_NONE {
                    self.note(
                        now_ms,
                        "verdict FFFFFD: verified; waiting for the power-cycle",
                    );
                    return self.await_power_cycle(now_ms);
                } else {
                    self.note(
                        now_ms,
                        format!(
                            "verdict {:06X}: the DE1 rejected the image",
                            frame.first_error
                        ),
                    );
                    self.fail(
                        FlashFailure::VerifyFailed {
                            first_error: frame.first_error,
                        },
                        now_ms,
                    );
                }
                Vec::new()
            }
            // An interrupted erase finished: release the lock early.
            Stage::Failed | Stage::Cancelled
                if self.erase_settling() && Self::is_erase_complete(&frame) =>
            {
                self.note(now_ms, "interrupted erase finished: lock released");
                self.erase_hold_until = None;
                Vec::new()
            }
            // Before the request's own ACK, or outside erase/verify: stale.
            _ => Vec::new(),
        }
    }

    fn poll_due(&mut self, now_ms: u64) -> bool {
        if let Some(sent) = self.poll_sent_at {
            if now_ms.saturating_sub(sent) < FW_MAP_POLL_READ_TIMEOUT_MS {
                return false;
            }
            self.poll_sent_at = None;
        }
        if now_ms.saturating_sub(self.last_poll_ms) < FW_MAP_POLL_INTERVAL_MS {
            return false;
        }
        self.last_poll_ms = now_ms;
        self.poll_sent_at = Some(now_ms);
        true
    }

    fn poll(&mut self, now_ms: u64) -> Vec<FlashAction> {
        if self.link_up && self.poll_due(now_ms) {
            vec![FlashAction::ReadFwMap]
        } else {
            Vec::new()
        }
    }

    /// The clock advanced.
    pub fn on_tick(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.see(now_ms);
        if let Some((what, sent)) = self.pending
            && self.active()
            && now_ms.saturating_sub(sent) >= WRITE_ACK_TIMEOUT_MS
        {
            self.pending = None;
            let phase = self.phase();
            self.write_failed(what, FlashFailure::AckTimeout { phase }, now_ms);
            return Vec::new();
        }
        match self.stage {
            Stage::Sleep { acked_at: Some(at) } if now_ms.saturating_sub(at) >= SLEEP_SETTLE_MS => {
                self.stage = Stage::Erase {
                    acked_at: None,
                    heard: false,
                };
                self.erase_started_ms = Some(now_ms);
                self.note(now_ms, "erase requested");
                vec![
                    FlashAction::SubscribeFwMap,
                    self.send(Pending::Erase, now_ms),
                ]
            }
            Stage::Erase {
                acked_at: Some(at),
                heard,
            } => {
                let waited = now_ms.saturating_sub(at);
                if !heard && waited >= ERASE_FALLBACK_MS {
                    self.note(
                        now_ms,
                        "no A009 answer 10 s after the erase ACK: upload started (fallback)",
                    );
                    return self.start_upload(now_ms);
                }
                if heard && waited >= ERASE_TIMEOUT_MS {
                    self.note(now_ms, "no erase-complete 60 s after the erase ACK");
                    self.fail(FlashFailure::EraseTimeout, now_ms);
                    return Vec::new();
                }
                self.poll(now_ms)
            }
            Stage::Settle { since } if now_ms.saturating_sub(since) >= POST_UPLOAD_SETTLE_MS => {
                self.send_verify(now_ms)
            }
            Stage::Verify {
                acked_at,
                sent_at,
                write_lost,
            } if acked_at.is_some() || write_lost => {
                if now_ms.saturating_sub(sent_at) >= self.verify_window_ms {
                    // Say which window closed: a restored one counts from the
                    // restore and is only what was left of the 120 s (final
                    // cohort review 2026-10-28, 06 #16).
                    let window_s = self.verify_window_ms.div_ceil(1000);
                    let line = if self.verify_restored {
                        format!(
                            "no verdict in the {window_s} s of the verify window left at the restore: unconfirmed"
                        )
                    } else {
                        format!("no verdict {window_s} s after the verify request: unconfirmed")
                    };
                    self.note(now_ms, line);
                    return self.unconfirmed(now_ms);
                }
                self.poll(now_ms)
            }
            Stage::AwaitPowerCycle if matches!(self.link_serial, LinkSerial::Waiting { until } if now_ms >= until) =>
            {
                self.note(
                    now_ms,
                    "no serial reply: identifying the DE1 by its Bluetooth id, if any",
                );
                self.link_serial = LinkSerial::Unknown;
                self.resolve_identity(now_ms);
                Vec::new()
            }
            Stage::AwaitPowerCycle
                if self.machine_restarted
                    && self.link_up
                    && now_ms < self.build_reads_until
                    && now_ms.saturating_sub(self.last_build_read_ms) >= BUILD_READ_INTERVAL_MS =>
            {
                self.last_build_read_ms = now_ms;
                vec![Self::read_build_action()]
            }
            Stage::Failed | Stage::Cancelled if self.erase_settling() => self.poll(now_ms),
            _ => Vec::new(),
        }
    }

    /// The DE1 link dropped.
    pub fn on_link_lost(&mut self, now_ms: u64) {
        self.see(now_ms);
        if self.link_up {
            self.note(now_ms, format!("link lost ({:?})", self.phase()));
        }
        self.link_up = false;
        self.foreign_machine = None;
        self.link_serial = LinkSerial::NotAsked;
        self.pending_build = None;
        self.identity_unknown = false;
        match self.stage {
            Stage::Sleep { .. } | Stage::Erase { .. } | Stage::Upload | Stage::Settle { .. } => {
                let phase = self.phase();
                self.fail(FlashFailure::LinkLost { phase }, now_ms);
            }
            Stage::Verify {
                acked_at,
                sent_at,
                write_lost,
            } => {
                // The DE1 goes on checking without the link, and nothing shows
                // cutting its power mid-check is safe: don't ask for the
                // power-cycle yet. Wait out VERIFY_TIMEOUT_MS from the request
                // (the tick does), polling again if the link returns; then the
                // unconfirmed path, where the build after a restart decides
                // (Decenza's "ambiguous verify"). An unACKed request may or may
                // not have arrived, so its verdict won't count as confirmed.
                if acked_at.is_none() && !write_lost {
                    self.stage = Stage::Verify {
                        acked_at: None,
                        sent_at,
                        write_lost: true,
                    };
                }
                self.note(
                    now_ms,
                    "the DE1 keeps checking: waiting out the verify window before the power-cycle",
                );
                self.pending = None;
                self.poll_sent_at = None;
                // The drop may have been a restart; the build read decides.
                self.machine_restarted = true;
            }
            Stage::AwaitPowerCycle => self.machine_restarted = true,
            _ => {}
        }
    }

    /// The DE1 link is back, to the DE1 with Bluetooth id `link_id` (empty =
    /// unknown). Waiting on a restart with a known serial, the DE1's serial is
    /// read alongside the build, and the build only counts once the serial
    /// matches (r4 LOW-1: a web `device.id` changes across browser restarts).
    /// Without a serial the Bluetooth ids decide when both are known. A
    /// different DE1 neither counts as the link coming back nor gets its build
    /// accepted: it can't satisfy the wait or release the lock (r3 LOW-2).
    pub fn on_link_restored_from(&mut self, now_ms: u64, link_id: &str) -> Vec<FlashAction> {
        self.see(now_ms);
        link_id.clone_into(&mut self.link_id_now);
        self.foreign_machine = None;
        self.pending_build = None;
        self.identity_unknown = false;
        if self.stage == Stage::AwaitPowerCycle && self.machine_restarted && self.serial.is_some() {
            self.link_serial = LinkSerial::Waiting {
                until: now_ms + SERIAL_WAIT_MS,
            };
            let mut actions = vec![Self::read_serial_action()];
            actions.extend(self.on_link_restored(now_ms));
            return actions;
        }
        self.link_serial = LinkSerial::NotAsked;
        if self.ids_differ() {
            self.mark_foreign(now_ms);
            return Vec::new();
        }
        let actions = self.on_link_restored(now_ms);
        self.resolve_identity(now_ms);
        actions
    }

    /// The DE1 link is back. Re-reads the build straight away when waiting
    /// on a restart.
    pub fn on_link_restored(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.see(now_ms);
        if !self.link_up {
            self.note(now_ms, format!("link restored ({:?})", self.phase()));
        }
        self.link_up = true;
        if self.stage == Stage::AwaitPowerCycle && self.machine_restarted {
            self.last_build_read_ms = now_ms;
            self.build_reads_until = now_ms + BUILD_READ_WINDOW_MS;
            return vec![Self::read_build_action()];
        }
        Vec::new()
    }

    /// The DE1 reported its build (MMR `0x800010`). Only a read after the link
    /// has dropped and come back counts — before it, the machine still runs the
    /// old image (and a same-build reflash would match falsely).
    pub fn on_build(&mut self, build: u32, now_ms: u64) {
        self.see(now_ms);
        if self.stage != Stage::AwaitPowerCycle || !self.machine_restarted || !self.link_up {
            return;
        }
        if matches!(self.link_serial, LinkSerial::Waiting { .. }) {
            // Not yet known to be the DE1 being updated: hold the reading.
            self.pending_build = Some(build);
            return;
        }
        self.accept_build(build, now_ms);
    }

    fn accept_build(&mut self, build: u32, now_ms: u64) {
        if self.restarted_on_build != Some(build) || build == self.artifact.build {
            self.note(now_ms, format!("DE1 reports build {build}"));
        }
        if build == self.artifact.build {
            self.stage = Stage::Succeeded;
            self.restarted_on_build = None;
            self.ended_ms = Some(now_ms);
        } else {
            // Not booted into the new bank (yet): a Bluetooth-only reconnect,
            // or a restart that kept the old image. Stay locked and ask for a
            // power-cycle (Decenza keeps `AwaitingReboot`, `:160-176`).
            self.restarted_on_build = Some(build);
        }
    }

    /// Whether a cancel is still allowed: until the verify request is sent.
    #[must_use]
    pub fn can_cancel(&self) -> bool {
        matches!(
            self.stage,
            Stage::Sleep { .. } | Stage::Erase { .. } | Stage::Upload | Stage::Settle { .. }
        )
    }

    /// Stop. Returns the follow-up actions (the Sleep request, like decaid's
    /// `cancelFirmwareUpload`), or `None` if nothing was cancelled. A cancel
    /// during erase keeps the lock until the erase finishes.
    pub fn cancel(&mut self, now_ms: u64) -> Option<Vec<FlashAction>> {
        self.see(now_ms);
        if !self.can_cancel() {
            return None;
        }
        self.note(
            now_ms,
            format!("cancelled ({:?}); Sleep requested", self.phase()),
        );
        self.stop(Stage::Cancelled, now_ms);
        // Through the flash's own path: the shell writes it even while the
        // lock is held. The ACK is ignored (stage Cancelled).
        Some(vec![self.send(Pending::Sleep, now_ms)])
    }

    fn percent(&self) -> f32 {
        #[allow(clippy::cast_precision_loss)] // counts ≤ 28 992, exact in f32
        let frac = if self.chunk_count == 0 {
            0.0
        } else {
            self.chunks_acked as f32 / self.chunk_count as f32
        };
        match self.stage {
            Stage::Sleep { .. } => 0.0,
            Stage::Erase { .. } => 1.0,
            Stage::Upload => 1.0 + 97.0 * frac,
            Stage::Settle { .. } | Stage::Verify { .. } => 99.0,
            Stage::AwaitPowerCycle | Stage::Succeeded => 100.0,
            Stage::Failed | Stage::Cancelled => (97.0 * frac).min(99.0),
        }
    }

    fn eta(&self, now_ms: u64) -> (Option<u32>, bool) {
        let remaining = u64::from(self.chunk_count.saturating_sub(self.chunks_acked));
        let secs = |ms: u64| Some(u32::try_from(ms.div_ceil(1000)).unwrap_or(u32::MAX));
        match self.stage {
            Stage::Sleep { .. } | Stage::Erase { .. } => (
                secs(remaining * ESTIMATED_MS_PER_CHUNK + ESTIMATED_VERIFY_MS + 2_000),
                true,
            ),
            Stage::Upload => {
                let started = self.upload_started_ms.unwrap_or(now_ms);
                if self.chunks_acked >= ETA_MEASURE_AFTER_CHUNKS {
                    let per = now_ms.saturating_sub(started) / u64::from(self.chunks_acked);
                    (secs(remaining * per + ESTIMATED_VERIFY_MS), false)
                } else {
                    (
                        secs(remaining * ESTIMATED_MS_PER_CHUNK + ESTIMATED_VERIFY_MS),
                        true,
                    )
                }
            }
            Stage::Settle { .. } | Stage::Verify { .. } => (secs(ESTIMATED_VERIFY_MS), true),
            _ => (None, false),
        }
    }

    /// A snapshot for the UI.
    #[must_use]
    pub fn status(&self, now_ms: u64) -> FlashStatus {
        let (eta_seconds, eta_is_estimate) = self.eta(now_ms);
        let end = self.ended_ms.unwrap_or(now_ms);
        FlashStatus {
            phase: self.phase(),
            artifact: self.artifact.clone(),
            target_build: self.artifact.build,
            chunks_total: self.chunk_count,
            chunks_acked: self.chunks_acked,
            percent: self.percent(),
            eta_seconds,
            eta_is_estimate,
            elapsed_seconds: u32::try_from(end.saturating_sub(self.started_ms) / 1000)
                .unwrap_or(u32::MAX),
            can_cancel: self.can_cancel(),
            locks_writes: self.locks_writes(),
            erase_settling: self.erase_settling(),
            can_exit: self.can_exit(),
            failure: self.failure.clone(),
            verify_unconfirmed: self.verify_unconfirmed,
            machine_restarted: self.machine_restarted,
            restarted_on_build: self.restarted_on_build,
            link_up: self.link_up,
            foreign_machine: self.foreign_machine.clone(),
            machine_serial: self.serial,
            identity_unknown: self.identity_unknown,
            erase_hold_ms_left: self
                .erase_hold_ms_left()
                .map(|ms| u32::try_from(ms).unwrap_or(u32::MAX)),
            verify_hold_ms_left: self
                .verify_hold_ms_left()
                .map(|ms| u32::try_from(ms).unwrap_or(u32::MAX)),
        }
    }
}

/// Models crema flashes: every classic DE1 — DE1 (1), DE1Plus (2), DE1Pro (3),
/// DE1XL (4), DE1Cafe (5), DE1XXL (6), DE1XXXL (7). This follows de1app,
/// Decent's own app, which hands the same `bootfwupdate.dat` to every
/// non-Bengle model (`fwfile`, `de1_comms.tcl:947-956`) and has done so in the
/// field for years. decaid's manifest lists only Pro / XL / XXL / XXXL
/// (`assets/firmware/manifest.json` `supportedModels`, enforced in
/// `firmware_validator.dart:120-124`) with no stated hardware reason, and
/// Decenza ships that manifest without enforcing it; crema first followed
/// decaid, which wrongly refused a DE1+. Bengle (≥ 128) needs a different
/// image and an extra `FWUpgrade` state (de1app `bengle.tcl:494-524`) and is
/// excluded, as is any value outside the known table.
pub const FLASHABLE_MODELS: &[u32] = &[1, 2, 3, 4, 5, 6, 7];

/// Golden `FlashStep` JSON the shells' tests decode and drive, so a decode or
/// byte-handling bug in a shell is caught there (relative to the de1-app
/// crate). Regenerated by
/// `cargo test -p de1-app write_flash_step_fixture -- --ignored`; a test fails
/// if the committed file drifts.
pub const FLASH_STEP_FIXTURE_PATH: &str = "tests/fixtures/flash-steps.json";

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Build a flash around a synthetic image, bypassing the manifest digest
    /// (no firmware bytes live in the repo).
    pub(crate) fn synthetic_flash(chunks: u32, now: u64) -> (FirmwareFlash, Vec<FlashAction>) {
        let mut image = vec![0u8; chunks as usize * 16];
        for (i, b) in image.iter_mut().enumerate() {
            *b = u8::try_from(i % 251).unwrap();
        }
        let artifact = de1_domain::firmware_manifest()[1].clone();
        let mut flash = FirmwareFlash::blank(image, artifact, now);
        flash.note_start(now);
        let a = flash.send(Pending::Sleep, now);
        (flash, vec![a])
    }

    fn write(target: WriteTarget, data: &[u8]) -> FlashAction {
        FlashAction::Write {
            target,
            data: data.to_vec(),
        }
    }

    const ERASE: [u8; 7] = [0, 0, 1, 1, 0xFF, 0xFF, 0xFF];
    const VERIFY: [u8; 7] = [0, 0, 0, 1, 0xFF, 0xFF, 0xFF];
    const ERASE_DONE: [u8; 7] = [0, 0, 0, 1, 0xFF, 0xFF, 0xFF];
    const ERASING: [u8; 7] = [0, 0, 1, 1, 0xFF, 0xFF, 0xFF];
    const VERIFY_OK: [u8; 7] = [0, 0, 0, 1, 0xFF, 0xFF, 0xFD];
    /// An MMR read request for one word at `0x800010`, written out by hand.
    const BUILD_READ: [u8; 20] = [
        0x00, 0x80, 0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ];

    /// Sleep → settle → erase write ACKed (no erase-complete yet).
    fn to_erase_acked() -> FirmwareFlash {
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_write_ack(10);
        f.on_tick(1_010);
        f.on_write_ack(1_050);
        f
    }

    /// Drive a flash from Sleep through to the first chunk.
    fn to_upload(chunks: u32) -> (FirmwareFlash, u64) {
        let (mut f, a) = synthetic_flash(chunks, 0);
        assert_eq!(a, vec![write(WriteTarget::De1RequestedState, &[0x00])]);
        assert!(f.on_write_ack(10).is_empty());
        assert!(f.on_tick(500).is_empty(), "still settling after sleep");
        let a = f.on_tick(1_010);
        assert_eq!(
            a,
            vec![
                FlashAction::SubscribeFwMap,
                write(WriteTarget::De1FwMapRequest, &ERASE)
            ]
        );
        assert!(f.on_write_ack(1_050).is_empty());
        let a = f.on_fw_map(&ERASE_DONE, 2_360);
        assert_eq!(f.phase(), FlashPhase::Uploading);
        assert_eq!(a.len(), 1);
        (f, 2_360)
    }

    fn to_verify(chunks: u32) -> (FirmwareFlash, u64) {
        let (mut f, mut now) = to_upload(chunks);
        for _ in 0..chunks {
            now += 30;
            f.on_write_ack(now);
        }
        now += POST_UPLOAD_SETTLE_MS;
        f.on_tick(now);
        now += 40;
        f.on_write_ack(now);
        (f, now)
    }

    #[test]
    fn begin_refuses_an_image_outside_the_manifest() {
        let err = FirmwareFlash::begin(vec![0u8; 4096], 0).unwrap_err();
        assert!(matches!(err, FirmwareImageError::NotADe1Image { .. }));
    }

    #[test]
    fn sleep_then_erase_with_the_reference_bytes() {
        let (f, _) = to_upload(4);
        assert!(f.locks_writes());
    }

    #[test]
    fn chunk_frames_carry_a_big_endian_offset_and_the_0x10_length() {
        let (f, _) = synthetic_flash(0x2001, 0);
        // Chunk 0x1234 → offset 0x12340 → bytes 01 23 40 (high byte first).
        let frame = f.chunk_frame(0x1234);
        assert_eq!(frame.len(), 20);
        assert_eq!(&frame[..4], &[0x10, 0x01, 0x23, 0x40]);
        assert_eq!(&frame[4..], &f.image[0x12340..0x12350]);
        // The last chunk of a 28 992-chunk image: offset 0x07_13F0.
        let (big, _) = synthetic_flash(28_992, 0);
        let last = big.chunk_frame(28_991);
        assert_eq!(&last[..4], &[0x10, 0x07, 0x13, 0xF0]);
        // Never little-endian (Decenza's pump-firing bug wrote F0 13 07).
        assert_ne!(&last[1..4], &[0xF0, 0x13, 0x07]);
    }

    #[test]
    fn chunks_go_one_at_a_time_in_order_then_settle_then_verify() {
        let (mut f, mut now) = to_upload(3);
        for i in 1..3u32 {
            now += 30;
            let a = f.on_write_ack(now);
            assert_eq!(a.len(), 1, "exactly one write in flight");
            let FlashAction::Write { target, data } = &a[0] else {
                panic!()
            };
            assert_eq!(*target, WriteTarget::De1MmrWrite);
            assert_eq!(&data[..4], &[0x10, 0, 0, u8::try_from(i * 16).unwrap()]);
        }
        now += 30;
        assert!(f.on_write_ack(now).is_empty(), "last ack starts the settle");
        assert_eq!(f.chunks_acked, 3);
        assert!(f.on_tick(now + 1_000).is_empty());
        let a = f.on_tick(now + POST_UPLOAD_SETTLE_MS);
        assert_eq!(
            a,
            vec![
                FlashAction::SubscribeFwMap,
                write(WriteTarget::De1FwMapRequest, &VERIFY)
            ]
        );
    }

    // ── H1: the erase-complete predicate ─────────────────────────────────

    #[test]
    fn a_late_verify_verdict_after_the_erase_ack_does_not_start_the_upload() {
        // Retry after a verify that timed out: the old verdict lands now.
        let mut f = to_erase_acked();
        assert!(f.on_fw_map(&VERIFY_OK, 1_100).is_empty());
        assert!(
            f.on_fw_map(&[0, 0, 0, 1, 0x00, 0x12, 0x30], 1_150)
                .is_empty()
        );
        assert_eq!(f.phase(), FlashPhase::Erasing);
    }

    #[test]
    fn a_stale_poll_reading_a_cached_verdict_does_not_start_the_upload() {
        let mut f = to_erase_acked();
        assert_eq!(f.on_tick(1_300), vec![FlashAction::ReadFwMap]);
        assert!(f.on_fw_map(&VERIFY_OK, 1_320).is_empty());
        assert_eq!(f.phase(), FlashPhase::Erasing);
        // The genuine echo still completes it.
        assert_eq!(f.on_tick(1_600), vec![FlashAction::ReadFwMap]);
        let a = f.on_fw_map(&ERASE_DONE, 1_620);
        assert_eq!(f.phase(), FlashPhase::Uploading);
        assert_eq!(a.len(), 1);
    }

    #[test]
    fn a_read_returning_our_own_erase_request_means_still_erasing() {
        let mut f = to_erase_acked();
        assert!(f.on_fw_map(&ERASE, 1_300).is_empty());
        assert_eq!(f.phase(), FlashPhase::Erasing);
        // …so no silent-erase fallback either.
        f.on_tick(1_050 + ERASE_FALLBACK_MS + 1);
        assert_eq!(f.phase(), FlashPhase::Erasing);
    }

    #[test]
    fn a_stale_erase_complete_before_the_erase_ack_is_ignored() {
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_write_ack(10);
        f.on_tick(1_010);
        assert!(f.on_fw_map(&ERASE_DONE, 1_020).is_empty());
        assert_eq!(f.phase(), FlashPhase::Erasing);
    }

    #[test]
    fn erase_polls_every_250_ms_and_ignores_still_erasing() {
        let mut f = to_erase_acked();
        assert!(f.on_tick(1_200).is_empty());
        assert_eq!(f.on_tick(1_300), vec![FlashAction::ReadFwMap]);
        assert!(f.on_tick(1_400).is_empty(), "one poll in flight");
        assert!(f.on_fw_map(&ERASING, 1_450).is_empty());
        assert_eq!(f.phase(), FlashPhase::Erasing);
        assert_eq!(f.on_tick(1_560), vec![FlashAction::ReadFwMap]);
        f.on_read_failed(1_570);
        assert_eq!(f.on_tick(1_820), vec![FlashAction::ReadFwMap]);
    }

    #[test]
    fn a_silent_erase_falls_back_to_uploading_after_ten_seconds() {
        let mut f = to_erase_acked();
        f.on_tick(1_050 + ERASE_FALLBACK_MS - 1);
        assert_eq!(f.phase(), FlashPhase::Erasing);
        let a = f.on_tick(1_050 + ERASE_FALLBACK_MS);
        assert_eq!(f.phase(), FlashPhase::Uploading);
        assert!(matches!(
            &a[0],
            FlashAction::Write {
                target: WriteTarget::De1MmrWrite,
                ..
            }
        ));
    }

    #[test]
    fn a_busy_erase_never_falls_back_and_times_out_at_sixty_seconds() {
        let mut f = to_erase_acked();
        f.on_fw_map(&ERASING, 1_300);
        f.on_tick(1_050 + ERASE_FALLBACK_MS + 1);
        assert_eq!(f.phase(), FlashPhase::Erasing);
        f.on_tick(1_050 + ERASE_TIMEOUT_MS);
        assert_eq!(f.failure, Some(FlashFailure::EraseTimeout));
    }

    #[test]
    fn any_a009_frame_after_the_erase_ack_turns_the_silent_fallback_off() {
        // A cached verify verdict is not erase-complete (H1), but it shows the
        // DE1 answers: wait for a real erase-complete instead of assuming.
        let mut f = to_erase_acked();
        f.on_fw_map(&VERIFY_OK, 1_300);
        f.on_tick(1_050 + ERASE_FALLBACK_MS + 1);
        assert_eq!(f.phase(), FlashPhase::Erasing, "no fallback once heard");
        let a = f.on_fw_map(&ERASE_DONE, 1_050 + ERASE_FALLBACK_MS + 500);
        assert_eq!(f.phase(), FlashPhase::Uploading);
        assert_eq!(a.len(), 1);
        // …and if erase-complete never comes, it fails at 60 s.
        let mut g = to_erase_acked();
        g.on_fw_map(&VERIFY_OK, 1_300);
        g.on_tick(1_050 + ERASE_TIMEOUT_MS);
        assert_eq!(g.failure, Some(FlashFailure::EraseTimeout));
    }

    #[test]
    fn the_erase_hold_left_is_reported_and_can_be_restored() {
        let f = to_erase_acked();
        // Erase requested at 1 010: up to 60 s from there.
        assert_eq!(f.status(1_050).erase_hold_ms_left, Some(59_960));
        let mut r = FirmwareFlash::restore_erase_hold(1358, 20_000, 5_000).expect("pinned");
        assert!(r.locks_writes());
        assert!(r.status(5_000).erase_settling);
        assert!(!r.can_exit(), "no exit while the DE1 may be erasing");
        r.on_tick(24_999);
        assert!(r.locks_writes());
        r.on_tick(25_000);
        assert!(!r.locks_writes(), "released when the hold ends");
        assert!(FirmwareFlash::restore_erase_hold(1358, 0, 5_000).is_none());
        assert!(FirmwareFlash::restore_erase_hold(1234, 9_000, 5_000).is_none());
    }

    #[test]
    fn the_verify_window_left_is_reported() {
        let (f, acked) = to_verify(4);
        let sent = acked - 40;
        assert_eq!(f.phase(), FlashPhase::Verifying);
        assert_eq!(
            f.status(acked).verify_hold_ms_left,
            Some(u32::try_from(sent + VERIFY_TIMEOUT_MS - acked).unwrap())
        );
        let (u, _) = to_upload(4);
        assert_eq!(u.status(3_000).verify_hold_ms_left, None);
    }

    #[test]
    fn a_reload_inside_the_verify_window_restores_verifying_not_the_power_cycle() {
        // Report 06 #7: restoring straight to AwaitPowerCycle told the user to
        // switch the DE1 off while it may still be checking the image.
        let mut r = FirmwareFlash::restore_verifying(1358, 30_000, 5_000).expect("pinned");
        assert_eq!(r.phase(), FlashPhase::Verifying);
        assert!(r.locks_writes());
        assert!(!r.can_exit(), "no exit while the DE1 may be verifying");
        let st = r.status(5_000);
        assert!(!st.link_up);
        assert_eq!(st.verify_hold_ms_left, Some(30_000));
        assert!(
            r.on_tick(6_000).is_empty(),
            "no polls while the link is down"
        );
        // The link comes back inside the window: poll for the verdict.
        r.on_link_restored(10_000);
        assert_eq!(
            r.on_tick(10_000 + FW_MAP_POLL_INTERVAL_MS),
            vec![FlashAction::ReadFwMap]
        );
        // The window closes with no verdict: the unconfirmed power-cycle step.
        r.on_tick(5_000 + 30_000 - 1);
        assert_eq!(r.phase(), FlashPhase::Verifying);
        r.on_link_lost(5_000 + 30_000 - 1);
        let _ = r.take_log();
        r.on_tick(5_000 + 30_000);
        assert_eq!(r.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(r.status(35_000).verify_unconfirmed);
        // Final cohort review 2026-10-28, 06 #16: the note names the 30 s
        // that were left at the restore, not the full 120 s from the request.
        let log = r.take_log().join("\n");
        assert!(
            log.contains("no verdict in the 30 s of the verify window left at the restore"),
            "{log}"
        );
        assert!(!log.contains("120 s"), "{log}");
    }

    #[test]
    fn a_restored_verify_window_is_capped_and_needs_time_left() {
        let r = FirmwareFlash::restore_verifying(1358, u64::MAX, 0).expect("pinned");
        assert_eq!(
            r.status(0).verify_hold_ms_left,
            Some(u32::try_from(VERIFY_TIMEOUT_MS).unwrap())
        );
        assert!(FirmwareFlash::restore_verifying(1358, 0, 5_000).is_none());
        assert!(FirmwareFlash::restore_verifying(1234, 9_000, 5_000).is_none());
    }

    #[test]
    fn a_restored_erase_hold_never_outlasts_the_erase_timeout() {
        // A wall clock that jumped back an hour between kill and relaunch
        // makes the shell compute an hour left: still at most 60 s.
        let mut r = FirmwareFlash::restore_erase_hold(1358, 3_600_000, 5_000).expect("pinned");
        assert_eq!(r.status(5_000).erase_hold_ms_left, Some(60_000));
        r.on_tick(5_000 + ERASE_TIMEOUT_MS - 1);
        assert!(r.locks_writes());
        r.on_tick(5_000 + ERASE_TIMEOUT_MS);
        assert!(!r.locks_writes(), "released at 60 s, not after an hour");
        assert!(r.can_exit());
        // Garbage (u64::MAX, e.g. a negative difference cast) is clamped too.
        let g = FirmwareFlash::restore_erase_hold(1358, u64::MAX, 0).expect("pinned");
        assert_eq!(g.status(0).erase_hold_ms_left, Some(60_000));
    }

    /// Verified, link dropped: the power-cycle wait.
    fn awaiting_restart(serial: u32, link_id: &str) -> (FirmwareFlash, u64) {
        let (mut f, now) = to_verify(1);
        f.bind_machine(serial, link_id);
        f.on_fw_map(&VERIFY_OK, now + 100);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        f.on_link_lost(now + 1_000);
        (f, now + 30_000)
    }

    #[test]
    fn a_different_de1_neither_satisfies_the_wait_nor_releases_the_lock() {
        // Within a session, by Bluetooth id (no serial known): verified on A,
        // the user connects B, which already runs the target build (r3 LOW-2).
        let (mut f, now) = awaiting_restart(0, "AA:AA");
        f.bind_machine(0, "CC:CC"); // first id wins
        assert!(
            f.on_link_restored_from(now, "BB:BB").is_empty(),
            "no build read from another DE1"
        );
        f.on_build(1358, now + 100);
        assert_eq!(
            f.phase(),
            FlashPhase::AwaitingPowerCycle,
            "B's build doesn't count"
        );
        assert!(f.locks_writes(), "A's lock is kept");
        assert_eq!(f.status(now).foreign_machine.as_deref(), Some("BB:BB"));
        assert!(
            f.on_tick(now + 3_000).is_empty(),
            "no periodic reads either"
        );
        f.on_link_lost(now + 10_000);
        assert_eq!(
            f.on_link_restored_from(now + 20_000, "aa:aa"),
            vec![FirmwareFlash::read_build_action()]
        );
        assert_eq!(f.status(now).foreign_machine, None);
        f.on_build(1358, now + 20_100);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
    }

    #[test]
    fn the_same_de1_after_a_browser_restart_is_recognised_by_its_serial() {
        // r4 LOW-1: a new web device.id, the same serial → the build counts.
        let (mut f, now) = awaiting_restart(4242, "web-id-old");
        assert_eq!(
            f.on_link_restored_from(now, "web-id-new"),
            vec![
                FirmwareFlash::read_serial_action(),
                FirmwareFlash::read_build_action()
            ]
        );
        f.on_build(1358, now + 50); // held until the serial answers
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        f.on_serial(4242, now + 60);
        assert_eq!(
            f.phase(),
            FlashPhase::Succeeded,
            "same serial, different web id"
        );
        assert!(!f.status(now).identity_unknown);
    }

    #[test]
    fn a_different_serial_can_neither_satisfy_the_wait_nor_release_the_lock() {
        let (mut f, now) = awaiting_restart(4242, "");
        f.on_link_restored_from(now, "");
        f.on_build(1358, now + 50);
        f.on_serial(9999, now + 60);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.locks_writes());
        assert_eq!(
            f.status(now).foreign_machine.as_deref(),
            Some("serial 9999")
        );
        f.on_build(1358, now + 3_000);
        assert_eq!(
            f.phase(),
            FlashPhase::AwaitingPowerCycle,
            "later reads don't count either"
        );
        // Even with a matching Bluetooth id, a different serial is a different DE1.
        let (mut g, now) = awaiting_restart(4242, "AA:AA");
        g.on_link_restored_from(now, "AA:AA");
        g.on_serial(9999, now + 10);
        g.on_build(1358, now + 20);
        assert_eq!(g.phase(), FlashPhase::AwaitingPowerCycle);
    }

    #[test]
    fn an_unknown_serial_and_a_new_web_id_is_unknown_not_different() {
        // Serial 0 at start, and the web never binds an id from an earlier
        // session: can't confirm, so not claimed to be another DE1.
        let (mut f, now) = awaiting_restart(0, "");
        assert_eq!(
            f.on_link_restored_from(now, "web-id-new"),
            vec![FirmwareFlash::read_build_action()]
        );
        assert!(f.status(now).identity_unknown);
        assert_eq!(f.status(now).foreign_machine, None);
        f.on_build(1358, now + 50);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
        // A known serial on our side but the DE1 answers 0: unknown too.
        let (mut g, now) = awaiting_restart(4242, "");
        g.on_link_restored_from(now, "web-id-new");
        g.on_serial(0, now + 10);
        assert!(g.status(now).identity_unknown);
        assert_eq!(g.status(now).foreign_machine, None);
        // …and no serial reply at all falls back after SERIAL_WAIT_MS.
        let (mut h, now) = awaiting_restart(4242, "");
        h.on_link_restored_from(now, "web-id-new");
        h.on_build(1358, now + 50);
        h.on_tick(now + SERIAL_WAIT_MS - 1);
        assert_eq!(h.phase(), FlashPhase::AwaitingPowerCycle);
        h.on_tick(now + SERIAL_WAIT_MS);
        assert_eq!(h.phase(), FlashPhase::Succeeded);
        assert!(h.status(now).identity_unknown);
    }

    #[test]
    fn android_falls_back_to_the_static_bluetooth_address_without_a_serial() {
        let (mut f, now) = awaiting_restart(4242, "AA:AA");
        f.on_link_restored_from(now, "BB:BB");
        f.on_serial(0, now + 10); // no usable serial → the address decides
        assert_eq!(f.status(now).foreign_machine.as_deref(), Some("BB:BB"));
        let (mut g, now) = awaiting_restart(4242, "AA:AA");
        g.on_link_restored_from(now, "AA:AA");
        g.on_tick(now + SERIAL_WAIT_MS); // no serial reply → the address decides
        assert!(!g.status(now).identity_unknown);
        assert_eq!(g.status(now).foreign_machine, None);
    }

    #[test]
    fn the_attempt_log_has_the_image_frames_failures_and_timestamps() {
        let (mut f, mut now) = to_upload(4);
        now += 30;
        f.on_write_ack(now);
        f.on_write_failed("GATT 133", now + 61_000);
        let log = f.take_log();
        let all = log.join("\n");
        assert!(log[0].starts_with("[+00:00] start: build 1358"), "{all}");
        assert!(all.contains("sha256 "), "{all}");
        assert!(all.contains("[+00:01] erase requested"), "{all}");
        assert!(all.contains("A009 00 00 00 01 FF FF FF"), "{all}");
        assert!(
            all.contains("Chunk(1) write failed at write 1 of 4"),
            "{all}"
        );
        assert!(all.contains("GATT 133"), "{all}");
        assert!(all.contains("[+01:03]"), "{all}");
        assert!(f.take_log().is_empty(), "drained");
    }

    // ── Verify ───────────────────────────────────────────────────────────

    #[test]
    fn verify_success_awaits_a_power_cycle_and_keeps_the_lock() {
        let (mut f, now) = to_verify(2);
        assert!(
            f.on_fw_map(&VERIFY, now + 100).is_empty(),
            "echo = still verifying"
        );
        assert_eq!(f.phase(), FlashPhase::Verifying);
        f.on_fw_map(&VERIFY_OK, now + 2_000);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.locks_writes(), "held until the DE1 reports the build");
        assert!(!f.can_cancel());
        assert!(f.can_exit());
        assert_eq!(f.status(now).percent, 100.0);
    }

    #[test]
    fn verify_failure_reports_the_bad_offset() {
        let (mut f, now) = to_verify(2);
        f.on_fw_map(&[0, 0, 0, 1, 0x00, 0x48, 0x00], now + 10);
        assert_eq!(f.phase(), FlashPhase::Failed);
        assert_eq!(
            f.failure,
            Some(FlashFailure::VerifyFailed {
                first_error: 0x4800
            })
        );
        assert!(!f.locks_writes());
    }

    #[test]
    fn a_verify_frame_with_a_window_increment_is_progress_not_a_verdict() {
        let (mut f, now) = to_verify(2);
        f.on_fw_map(&[0, 5, 0, 1, 0x00, 0x48, 0x00], now + 10);
        assert_eq!(f.phase(), FlashPhase::Verifying);
    }

    #[test]
    fn a_frame_before_the_verify_ack_is_ignored() {
        let (mut f, mut now) = to_upload(1);
        now += 30;
        f.on_write_ack(now);
        now += POST_UPLOAD_SETTLE_MS;
        f.on_tick(now);
        f.on_fw_map(&VERIFY_OK, now + 5);
        assert_eq!(
            f.phase(),
            FlashPhase::Verifying,
            "stale verdict before our ack"
        );
    }

    #[test]
    fn a_failed_verify_request_is_never_resent_and_waits_for_the_verdict() {
        let (mut f, mut now) = to_upload(1);
        now += 30;
        f.on_write_ack(now);
        now += POST_UPLOAD_SETTLE_MS;
        let sent = now;
        assert_eq!(
            f.on_tick(now)[1],
            write(WriteTarget::De1FwMapRequest, &VERIFY)
        );
        f.on_write_failed("GATT timeout", now + 5);
        assert_eq!(
            f.phase(),
            FlashPhase::Verifying,
            "still waiting for the DE1"
        );
        assert!(f.locks_writes());
        // Only polls from here: never the verify request again.
        let mut t = now + 5;
        while t + FW_MAP_POLL_INTERVAL_MS < sent + VERIFY_TIMEOUT_MS {
            t += FW_MAP_POLL_INTERVAL_MS;
            for a in f.on_tick(t) {
                assert_eq!(a, FlashAction::ReadFwMap, "no re-send at {t}");
                f.on_read_failed(t);
            }
        }
        assert_eq!(f.phase(), FlashPhase::Verifying);
        f.on_tick(sent + VERIFY_TIMEOUT_MS);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.status(now).verify_unconfirmed);
        assert!(f.locks_writes());
        assert_eq!(f.failure, None, "not a definite failure");
    }

    #[test]
    fn a_verdict_after_a_lost_verify_write_is_unconfirmed_even_if_good() {
        let (mut f, mut now) = to_upload(1);
        now += 30;
        f.on_write_ack(now);
        now += POST_UPLOAD_SETTLE_MS;
        f.on_tick(now);
        f.on_write_failed("GATT timeout", now + 5);
        f.on_fw_map(&VERIFY_OK, now + 400);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.status(now).verify_unconfirmed);
        // A bad verdict is also only unconfirmed: the request may not be ours.
        let (mut g, mut now) = to_upload(1);
        now += 30;
        g.on_write_ack(now);
        now += POST_UPLOAD_SETTLE_MS;
        g.on_tick(now);
        g.on_tick(now + WRITE_ACK_TIMEOUT_MS);
        g.on_fw_map(&[0, 0, 0, 1, 0, 0x48, 0], now + WRITE_ACK_TIMEOUT_MS + 10);
        assert_eq!(g.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(g.status(now).verify_unconfirmed);
        assert_eq!(g.failure, None);
    }

    #[test]
    fn a_verify_timeout_is_unconfirmed_not_failed() {
        let (mut f, now) = to_verify(1);
        // Counted from the request (sent 40 ms before its ACK).
        let sent = now - 40;
        f.on_tick(sent + VERIFY_TIMEOUT_MS - 1);
        assert_eq!(f.phase(), FlashPhase::Verifying);
        let _ = f.take_log();
        f.on_tick(sent + VERIFY_TIMEOUT_MS);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.status(now).verify_unconfirmed);
        assert!(f.locks_writes());
        let log = f.take_log().join("\n");
        assert!(
            log.contains("no verdict 120 s after the verify request"),
            "{log}"
        );
    }

    #[test]
    fn an_unacknowledged_upload_write_times_out() {
        let (mut f, now) = to_upload(4);
        f.on_tick(now + WRITE_ACK_TIMEOUT_MS);
        assert_eq!(
            f.failure,
            Some(FlashFailure::AckTimeout {
                phase: FlashPhase::Uploading
            })
        );
        assert!(!f.locks_writes(), "erase already finished");
    }

    #[test]
    fn a_failed_write_fails_the_flash() {
        let (mut f, now) = to_upload(4);
        f.on_write_failed("GATT error 133", now + 5);
        assert_eq!(f.phase(), FlashPhase::Failed);
        assert!(matches!(
            f.failure,
            Some(FlashFailure::WriteFailed {
                phase: FlashPhase::Uploading,
                ..
            })
        ));
    }

    // ── H2: how long the lock is held ────────────────────────────────────

    #[test]
    fn link_loss_mid_erase_holds_the_lock_until_the_erase_can_have_finished() {
        let mut f = to_erase_acked();
        f.on_link_lost(2_000);
        assert_eq!(
            f.failure,
            Some(FlashFailure::LinkLost {
                phase: FlashPhase::Erasing
            })
        );
        assert!(f.locks_writes(), "the DE1 may still be erasing");
        assert!(f.status(2_000).erase_settling);
        assert!(!f.can_exit());
        assert!(f.on_tick(2_500).is_empty(), "no reads while down");
        // Reconnected: polled, and an erase-complete frame releases early.
        f.on_link_restored(3_000);
        assert_eq!(f.on_tick(3_300), vec![FlashAction::ReadFwMap]);
        f.on_fw_map(&ERASING, 3_320);
        assert!(f.locks_writes());
        f.on_tick(3_600);
        f.on_fw_map(&ERASE_DONE, 3_620);
        assert!(!f.locks_writes());
    }

    #[test]
    fn the_erase_hold_expires_sixty_seconds_after_the_erase_request() {
        let mut f = to_erase_acked();
        f.on_link_lost(2_000);
        f.on_tick(1_010 + ERASE_TIMEOUT_MS - 1);
        assert!(f.locks_writes());
        f.on_tick(1_010 + ERASE_TIMEOUT_MS);
        assert!(!f.locks_writes());
        assert!(f.can_exit());
    }

    #[test]
    fn a_failed_erase_write_holds_too() {
        let (mut g, _) = synthetic_flash(4, 0);
        g.on_write_ack(10);
        g.on_tick(1_010);
        g.on_write_failed("GATT 133", 1_020);
        assert_eq!(g.phase(), FlashPhase::Failed);
        assert!(g.locks_writes(), "the erase request may have landed");
    }

    #[test]
    fn link_loss_before_erase_or_after_it_releases_at_once() {
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_link_lost(5);
        assert!(!f.locks_writes());
        let (mut g, now) = to_upload(4);
        g.on_link_lost(now);
        assert_eq!(
            g.failure,
            Some(FlashFailure::LinkLost {
                phase: FlashPhase::Uploading
            })
        );
        assert!(!g.locks_writes());
    }

    #[test]
    fn link_loss_during_verify_waits_out_the_check_before_the_power_cycle() {
        let (mut f, now) = to_verify(1);
        let sent = now - 40;
        f.on_link_lost(now + 5);
        // Not told to power-cycle while the DE1 may still be checking.
        assert_eq!(f.phase(), FlashPhase::Verifying);
        assert!(f.locks_writes());
        assert!(!f.can_exit());
        assert!(f.on_tick(now + 1_000).is_empty(), "no reads while down");
        f.on_tick(sent + VERIFY_TIMEOUT_MS - 1);
        assert_eq!(f.phase(), FlashPhase::Verifying);
        assert!(
            f.on_tick(sent + VERIFY_TIMEOUT_MS).is_empty(),
            "still down: nothing to read"
        );
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.status(now).verify_unconfirmed);
        assert!(f.locks_writes());
        let back = sent + VERIFY_TIMEOUT_MS + 30_000;
        assert_eq!(
            f.on_link_restored(back),
            vec![FirmwareFlash::read_build_action()]
        );
        f.on_build(1333, back + 100);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle, "still waiting");
        assert_eq!(f.status(now).restarted_on_build, Some(1333));
        assert!(f.locks_writes());
        f.on_link_lost(back + 30_000);
        f.on_link_restored(back + 60_000);
        f.on_build(1358, back + 60_100);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
        assert!(!f.locks_writes());
    }

    #[test]
    fn a_verdict_heard_after_the_link_returns_mid_check_still_counts() {
        let (mut f, now) = to_verify(1);
        f.on_link_lost(now + 5);
        f.on_link_restored(now + 3_000);
        assert_eq!(f.phase(), FlashPhase::Verifying);
        assert_eq!(
            f.on_tick(now + 3_000 + FW_MAP_POLL_INTERVAL_MS),
            vec![FlashAction::ReadFwMap],
            "polling resumes"
        );
        // The request was ACKed before the drop: a verdict is confirmed. The
        // drop may have been a restart, so the build is read at once.
        assert_eq!(
            f.on_fw_map(&VERIFY_OK, now + 3_600),
            vec![FirmwareFlash::read_build_action()]
        );
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(!f.status(now).verify_unconfirmed);
        // A Bluetooth-only drop: still the old build, so still waiting.
        f.on_build(1333, now + 3_700);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert_eq!(f.status(now).restarted_on_build, Some(1333));
    }

    #[test]
    fn a_rejection_heard_after_the_link_returns_mid_check_fails() {
        let (mut f, now) = to_verify(1);
        f.on_link_lost(now + 5);
        f.on_link_restored(now + 3_000);
        f.on_tick(now + 3_000 + FW_MAP_POLL_INTERVAL_MS);
        f.on_fw_map(&[0, 0, 0, 1, 0x00, 0x48, 0x00], now + 3_600);
        assert_eq!(
            f.failure,
            Some(FlashFailure::VerifyFailed {
                first_error: 0x00_4800
            })
        );
    }

    #[test]
    fn a_verify_request_unacked_when_the_link_dropped_is_never_confirmed() {
        let (mut f, mut now) = to_upload(1);
        now += 30;
        f.on_write_ack(now);
        now += POST_UPLOAD_SETTLE_MS;
        f.on_tick(now); // the verify request goes out
        f.on_link_lost(now + 10);
        f.on_write_failed("Device was disconnected", now + 20);
        assert_eq!(
            f.phase(),
            FlashPhase::Verifying,
            "still waiting out the check"
        );
        f.on_link_restored(now + 2_000);
        f.on_tick(now + 2_000 + FW_MAP_POLL_INTERVAL_MS);
        f.on_fw_map(&VERIFY_OK, now + 2_500);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(
            f.status(now).verify_unconfirmed,
            "it may not have been this request's verdict"
        );
    }

    #[test]
    fn success_needs_a_reconnect_and_the_exact_target_build() {
        let (mut f, now) = to_verify(1);
        f.on_fw_map(&VERIFY_OK, now + 10);
        f.on_build(1333, now + 20);
        f.on_build(1358, now + 30);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle, "no restart yet");
        f.on_link_lost(now + 40_000);
        assert!(f.on_tick(now + 50_000).is_empty(), "no reads while down");
        assert_eq!(
            f.on_link_restored(now + 70_000),
            vec![FirmwareFlash::read_build_action()]
        );
        f.on_build(1358, now + 70_500);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
    }

    #[test]
    fn the_build_read_carries_the_mmr_read_request_bytes() {
        assert_eq!(
            FirmwareFlash::read_build_action(),
            FlashAction::ReadBuild {
                data: BUILD_READ.to_vec()
            }
        );
    }

    #[test]
    fn build_reads_repeat_for_a_minute_after_a_reconnect_then_stop() {
        let (mut f, now) = to_verify(1);
        f.on_fw_map(&VERIFY_OK, now + 10);
        f.on_link_lost(now + 1_000);
        f.on_link_restored(now + 2_000);
        f.on_build(1333, now + 2_100);
        assert_eq!(
            f.on_tick(now + 2_000 + BUILD_READ_INTERVAL_MS),
            vec![FirmwareFlash::read_build_action()]
        );
        assert!(f.on_tick(now + 2_000 + BUILD_READ_WINDOW_MS + 1).is_empty());
    }

    #[test]
    fn cancel_works_until_the_verify_request_and_sends_sleep() {
        let (mut f, now) = to_upload(4);
        assert!(f.status(now).can_cancel);
        let a = f.cancel(now).expect("cancellable");
        assert_eq!(a, vec![write(WriteTarget::De1RequestedState, &[0x00])]);
        assert_eq!(f.phase(), FlashPhase::Cancelled);
        assert!(!f.locks_writes(), "erase had finished");
        assert!(
            f.on_write_ack(now + 1).is_empty(),
            "the Sleep ACK does nothing"
        );
        assert_eq!(f.phase(), FlashPhase::Cancelled);

        let (mut v, now) = to_verify(1);
        assert!(v.cancel(now).is_none());
        assert_eq!(v.phase(), FlashPhase::Verifying);
    }

    #[test]
    fn cancel_during_erase_keeps_the_lock_until_the_erase_finishes() {
        let mut f = to_erase_acked();
        let a = f.cancel(1_100).expect("cancellable");
        assert_eq!(a, vec![write(WriteTarget::De1RequestedState, &[0x00])]);
        assert!(f.locks_writes());
        f.on_write_ack(1_120);
        assert_eq!(f.on_tick(1_400), vec![FlashAction::ReadFwMap]);
        f.on_fw_map(&ERASE_DONE, 1_420);
        assert!(!f.locks_writes());
    }

    #[test]
    fn progress_and_eta_follow_the_measured_rate() {
        let (mut f, mut now) = to_upload(1_000);
        let s = f.status(now);
        assert!(s.eta_is_estimate);
        assert!(s.percent >= 1.0 && s.percent < 2.0);
        for _ in 0..500 {
            now += 40;
            f.on_write_ack(now);
        }
        let s = f.status(now);
        assert!(!s.eta_is_estimate);
        assert!((s.percent - 49.5).abs() < 0.6, "{}", s.percent);
        // 500 left at 40 ms each + 5 s verify allowance = 25 s.
        assert_eq!(s.eta_seconds, Some(25));
        assert_eq!(s.chunks_acked, 500);
    }

    #[test]
    fn restore_awaiting_brings_back_the_lock_and_the_power_cycle_step() {
        let mut f = FirmwareFlash::restore_awaiting(1358, false, 1_000).expect("pinned");
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.locks_writes());
        assert!(
            !f.status(1_000).link_up,
            "a fresh shell is not connected yet"
        );
        assert!(
            f.on_tick(1_000 + BUILD_READ_INTERVAL_MS).is_empty(),
            "no reads while disconnected"
        );
        f.on_build(1358, 4_500);
        assert_eq!(
            f.phase(),
            FlashPhase::AwaitingPowerCycle,
            "no build accepted before the link is up"
        );
        // The user connects much later than the old 60 s window: still read.
        let later = 1_000 + 10 * BUILD_READ_WINDOW_MS;
        assert_eq!(
            f.on_link_restored(later),
            vec![FirmwareFlash::read_build_action()]
        );
        f.on_build(1358, later + 200);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
        assert!(FirmwareFlash::restore_awaiting(1234, false, 0).is_none());
    }

    /// A simulated DE1: answers each action the way the real machine does, so
    /// a whole flash runs end to end through the public surface only.
    struct SimDe1 {
        flash_bank: Vec<u8>,
        erased: bool,
        build: u32,
        serial: u32,
        corrupt_at: Option<usize>,
    }

    impl SimDe1 {
        fn verdict(&self, image: &[u8]) -> [u8; 7] {
            let bad = (0..image.len()).step_by(16).find(|&o| {
                self.flash_bank.get(o..o + 16) != Some(&image[o..o + 16])
                    || self.corrupt_at == Some(o)
            });
            match bad {
                None => VERIFY_OK,
                Some(o) => {
                    let o = u32::try_from(o).unwrap().to_be_bytes();
                    [0, 0, 0, 1, o[1], o[2], o[3]]
                }
            }
        }
    }

    fn run_sim(chunks: u32, corrupt_at: Option<usize>) -> (FirmwareFlash, SimDe1) {
        let (mut f, actions) = synthetic_flash(chunks, 0);
        let image = f.image.clone();
        let mut sim = SimDe1 {
            flash_bank: vec![0u8; image.len()],
            erased: false,
            build: 1333,
            serial: 4242,
            corrupt_at,
        };
        let mut queue: std::collections::VecDeque<FlashAction> = actions.into();
        let mut now = 0u64;
        let mut verify_requested = false;
        let mut erase_reads = 0;
        for _ in 0..200_000 {
            if let Some(action) = queue.pop_front() {
                now += 7;
                let next = match action {
                    FlashAction::Write { target, data } => {
                        match target {
                            WriteTarget::De1FwMapRequest if data[2] == 1 => {
                                sim.flash_bank.fill(0xFF);
                                sim.erased = true;
                            }
                            WriteTarget::De1FwMapRequest => verify_requested = true,
                            WriteTarget::De1MmrWrite => {
                                assert!(sim.erased, "upload before erase");
                                assert_eq!(data[0], 0x10);
                                let off = (usize::from(data[1]) << 16)
                                    | (usize::from(data[2]) << 8)
                                    | usize::from(data[3]);
                                sim.flash_bank[off..off + 16].copy_from_slice(&data[4..20]);
                            }
                            _ => {}
                        }
                        f.on_write_ack(now)
                    }
                    FlashAction::SubscribeFwMap => Vec::new(),
                    FlashAction::ReadFwMap => {
                        let frame = if verify_requested {
                            sim.verdict(&image)
                        } else {
                            // The DE1 reports "erasing" for a while first,
                            // then erase-complete (with the FF FF FF echo).
                            erase_reads += 1;
                            if erase_reads < 4 { ERASING } else { ERASE_DONE }
                        };
                        f.on_fw_map(&frame, now)
                    }
                    FlashAction::ReadBuild { data } => {
                        assert_eq!(data, BUILD_READ.to_vec());
                        f.on_build(sim.build, now);
                        Vec::new()
                    }
                    FlashAction::ReadSerial { .. } => {
                        f.on_serial(sim.serial, now);
                        Vec::new()
                    }
                };
                queue.extend(next);
            } else {
                now += 250;
                queue.extend(f.on_tick(now));
            }
            if f.phase() == FlashPhase::AwaitingPowerCycle && !f.status(now).machine_restarted {
                assert!(f.locks_writes());
                // The user power-cycles: the sim boots the verified bank.
                f.on_link_lost(now);
                sim.build = 1358;
                queue.extend(f.on_link_restored(now + 40_000));
                now += 40_000;
            }
            if f.is_terminal() {
                break;
            }
        }
        (f, sim)
    }

    #[test]
    fn a_full_simulated_flash_succeeds_end_to_end() {
        let (f, sim) = run_sim(300, None);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
        assert_eq!(
            sim.flash_bank, f.image,
            "every byte landed at its own address"
        );
        assert_eq!(f.status(0).chunks_acked, 300);
        assert!(!f.locks_writes());
    }

    #[test]
    fn a_simulated_corruption_fails_verify_at_its_offset() {
        let (f, _) = run_sim(300, Some(0x0400));
        assert_eq!(
            f.failure,
            Some(FlashFailure::VerifyFailed {
                first_error: 0x0400
            })
        );
    }

    #[test]
    fn flashable_models_follow_de1app() {
        assert_eq!(FLASHABLE_MODELS, &[1, 2, 3, 4, 5, 6, 7]);
        assert!(
            !FLASHABLE_MODELS
                .iter()
                .any(|&m| de1_protocol::is_bengle_model(m))
        );
    }

    /// The steps the shells' fixture tests replay: start (Sleep), erase,
    /// the first chunk, chunk 0x1234 of a 0x2001-chunk image, verify, cancel,
    /// and the build read.
    pub(crate) fn fixture_steps() -> serde_json::Value {
        let (mut f, first) = synthetic_flash(0x2001, 0);
        let step = |f: &FirmwareFlash, actions: Vec<FlashAction>, now| FlashStep {
            actions,
            status: Some(f.status(now)),
            error: None,
            log: vec!["[+00:00] fixture".to_owned()],
        };
        let start = step(&f, first, 0);
        f.on_write_ack(10);
        let erase_actions = f.on_tick(1_010);
        let erase = step(&f, erase_actions, 1_010);
        f.on_write_ack(1_050);
        let chunk0_actions = f.on_fw_map(&ERASE_DONE, 1_100);
        let chunk0 = step(&f, chunk0_actions, 1_100);
        let only = |a: FlashAction| FlashStep {
            actions: vec![a],
            status: None,
            error: None,
            log: Vec::new(),
        };
        let chunk_1234 = only(FlashAction::Write {
            target: WriteTarget::De1MmrWrite,
            data: f.chunk_frame(0x1234),
        });
        let verify = only(FlashAction::Write {
            target: WriteTarget::De1FwMapRequest,
            data: FWMapRequest::request_first_error().encode().to_vec(),
        });
        let cancel_actions = f.cancel(1_200).unwrap_or_default();
        let cancel = step(&f, cancel_actions, 1_200);
        serde_json::json!({
            "start": start,
            "erase": erase,
            "chunk0": chunk0,
            "chunk0x1234": chunk_1234,
            "verify": verify,
            "cancel": cancel,
            "readBuild": only(FirmwareFlash::read_build_action()),
            "readSerial": only(FirmwareFlash::read_serial_action()),
        })
    }

    fn fixture_path() -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(FLASH_STEP_FIXTURE_PATH)
    }

    #[test]
    fn the_committed_flash_step_fixture_matches_the_core() {
        let committed: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(fixture_path()).expect("fixture committed"),
        )
        .unwrap();
        assert_eq!(
            committed,
            fixture_steps(),
            "regenerate: cargo test -p de1-app write_flash_step_fixture -- --ignored"
        );
    }

    #[test]
    #[ignore = "regenerates the shells' fixture"]
    fn write_flash_step_fixture() {
        let path = fixture_path();
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(
            path,
            serde_json::to_string_pretty(&fixture_steps()).unwrap() + "\n",
        )
        .unwrap();
    }
}

/// The flash as the shells drive it: through [`CremaCore`](crate::CremaCore),
/// with preconditions, the write lock and the build read-back.
#[cfg(test)]
mod core_tests {
    use super::*;
    use crate::{CremaCore, Source};
    use de1_protocol::MmrRegister;
    use std::time::Duration;

    fn mmr_packet(register: MmrRegister, value: u32) -> Vec<u8> {
        let mut packet = vec![0u8; de1_protocol::MMR_PACKET_LEN];
        let a = register.address();
        packet[1..4].copy_from_slice(&a.to_be_bytes()[1..]);
        packet[4..8].copy_from_slice(&value.to_le_bytes());
        packet
    }

    const OK: FlashShellConditions = FlashShellConditions {
        connected: true,
        ready: true,
        guided_brew_active: false,
        power_ok: true,
    };

    /// A connected DE1Pro on build 1333, idle.
    fn ready_core() -> CremaCore {
        let mut core = CremaCore::new();
        core.on_notification(
            Source::De1MmrRead,
            &mmr_packet(MmrRegister::FirmwareVersion, 1333),
            10,
        );
        core.on_notification(
            Source::De1MmrRead,
            &mmr_packet(MmrRegister::MachineModel, 3),
            20,
        );
        core.on_notification(Source::De1State, &[2, 0], 30);
        core
    }

    #[test]
    fn a_ready_idle_supported_machine_has_no_blockers() {
        assert!(ready_core().firmware_flash_blockers(OK).is_empty());
    }

    #[test]
    fn sleep_is_allowed_too() {
        let mut core = ready_core();
        core.on_notification(Source::De1State, &[0, 0], 40);
        assert!(core.firmware_flash_blockers(OK).is_empty());
    }

    #[test]
    fn every_precondition_blocks() {
        let core = CremaCore::new();
        let b = core.firmware_flash_blockers(FlashShellConditions {
            connected: false,
            ready: false,
            guided_brew_active: true,
            power_ok: false,
        });
        for want in [
            FlashBlocker::NotConnected,
            FlashBlocker::BuildUnknown,
            FlashBlocker::ModelUnknown,
            FlashBlocker::StateUnknown,
            FlashBlocker::GuidedBrewActive,
            FlashBlocker::LowPower,
        ] {
            assert!(b.contains(&want), "{want:?} missing from {b:?}");
        }
        let b = ready_core().firmware_flash_blockers(FlashShellConditions { ready: false, ..OK });
        assert_eq!(b, vec![FlashBlocker::NotReady]);
    }

    #[test]
    fn busy_refilling_erroring_and_mirrored_machines_are_blocked() {
        let cases: [(&[u8], FlashBlocker); 6] = [
            (&[4, 5], FlashBlocker::ShotInProgress),
            (&[5, 7], FlashBlocker::ShotInProgress),
            (
                &[10, 8],
                FlashBlocker::MachineBusy {
                    state: MachineState::Descale,
                },
            ),
            (&[17, 0], FlashBlocker::Refilling),
            (&[2, 17], FlashBlocker::Refilling),
            (&[2, 202], FlashBlocker::MachineError),
        ];
        for (state, want) in cases {
            let mut core = ready_core();
            core.on_notification(Source::De1State, state, 50);
            assert!(
                core.firmware_flash_blockers(OK).contains(&want),
                "{state:?} → {want:?}"
            );
        }
        let mut mirror = ready_core();
        mirror.set_read_only(true);
        assert!(
            mirror
                .firmware_flash_blockers(OK)
                .contains(&FlashBlocker::ReadOnlyMirror)
        );
    }

    #[test]
    fn bengle_and_unknown_models_are_blocked() {
        for model in [128u32, 8, 127] {
            let mut core = ready_core();
            core.on_notification(
                Source::De1MmrRead,
                &mmr_packet(MmrRegister::MachineModel, model),
                60,
            );
            assert!(
                core.firmware_flash_blockers(OK)
                    .contains(&FlashBlocker::UnsupportedModel { model }),
                "model {model}"
            );
        }
    }

    #[test]
    fn every_classic_de1_model_is_flashable() {
        // DE1, DE1+ (a user's DE1+ was refused when this followed decaid's
        // list), Pro, XL, Cafe, XXL, XXXL.
        for model in 1u32..=7 {
            let mut core = ready_core();
            core.on_notification(
                Source::De1MmrRead,
                &mmr_packet(MmrRegister::MachineModel, model),
                60,
            );
            assert!(
                !core
                    .firmware_flash_blockers(OK)
                    .iter()
                    .any(|b| matches!(b, FlashBlocker::UnsupportedModel { .. })),
                "model {model}"
            );
        }
    }

    #[test]
    fn start_refuses_when_blocked_or_the_image_is_not_pinned() {
        let mut core = ready_core();
        let step = core.firmware_flash_start(
            vec![0; 64],
            FlashShellConditions {
                power_ok: false,
                ..OK
            },
            100,
        );
        assert!(matches!(step.error, Some(FlashStartError::Blocked { .. })));
        assert!(step.actions.is_empty());
        let step = core.firmware_flash_start(vec![0x42; 4096], OK, 100);
        assert!(matches!(
            step.error,
            Some(FlashStartError::InvalidImage { .. })
        ));
        assert!(step.status.is_none());
        assert!(!core.firmware_locks_writes());
    }

    /// Install a synthetic flash (the manifest digests are of real files, which
    /// are not in the repo) and return its first actions.
    fn install(core: &mut CremaCore, chunks: u32) -> Vec<FlashAction> {
        let (flash, actions) = tests::synthetic_flash(chunks, 100);
        core.firmware_flash = Some(flash);
        actions
    }

    #[test]
    fn a_running_flash_locks_out_setters_reads_uploads_and_core_commands() {
        let mut core = ready_core();
        install(&mut core, 8);
        assert!(core.firmware_locks_writes());
        let out = core.request_machine_state(MachineState::Espresso);
        assert!(out.commands.is_empty());
        assert!(matches!(
            out.events[..],
            [crate::Event::FirmwareLockoutHit { .. }]
        ));
        assert!(
            core.read_mmr(MmrRegister::FirmwareVersion)
                .commands
                .is_empty()
        );
        let profile = de1_domain::builtin_profiles()[0].clone();
        let out = core
            .upload_profile(&profile, Duration::from_millis(150))
            .expect("refused, not an error");
        assert!(out.commands.is_empty(), "L2: upload_profile refuses too");
        assert!(!core.profile_upload_in_progress());
        let out = core.on_notification(Source::De1State, &[2, 0], 200);
        assert!(out.commands.is_empty());
        assert!(
            core.firmware_flash_blockers(OK)
                .contains(&FlashBlocker::FlashInProgress)
        );
        assert!(
            !core.firmware_flash_dismiss(),
            "a flash holding the lock stays"
        );
        assert!(!core.firmware_flash_exit(), "no exit mid-transfer");
    }

    /// Drive a synthetic flash to the power-cycle step through the core.
    fn to_awaiting(core: &mut CremaCore) {
        let (mut flash, _) = tests::synthetic_flash(1, 0);
        flash.on_write_ack(10);
        flash.on_tick(1_010);
        flash.on_write_ack(1_050);
        flash.on_fw_map(&[0, 0, 0, 1, 0xFF, 0xFF, 0xFF], 1_100);
        flash.on_write_ack(1_130);
        flash.on_tick(2_700);
        flash.on_write_ack(2_740);
        flash.on_fw_map(&[0, 0, 0, 1, 0xFF, 0xFF, 0xFD], 3_000);
        assert_eq!(flash.phase(), FlashPhase::AwaitingPowerCycle);
        core.firmware_flash = Some(flash);
    }

    fn refused(out: &crate::CoreOutput) -> bool {
        out.commands.is_empty()
            && out
                .events
                .iter()
                .any(|e| matches!(e, crate::Event::FirmwareLockoutHit { .. }))
    }

    #[test]
    fn reconnect_while_awaiting_the_power_cycle_sends_nothing_but_the_build_read() {
        let mut core = ready_core();
        to_awaiting(&mut core);
        // The shells reset the core on the disconnect; the flash survives.
        core.de1_link_lost();
        core.reset();
        assert!(core.firmware_locks_writes());
        // Everything a reconnect fires is refused.
        assert!(
            refused(&core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), 4_000)),
            "no settings sweep"
        );
        assert!(refused(&core.set_user_present(true)), "no user-present");
        assert!(
            refused(&core.set_usb_charger_on(true)),
            "no charging writes"
        );
        assert!(
            refused(&core.request_machine_state(MachineState::Sleep)),
            "no idle-sleep"
        );
        let profile = de1_domain::builtin_profiles()[0].clone();
        assert!(refused(
            &core
                .upload_profile(&profile, Duration::from_millis(4_000))
                .unwrap()
        ));
        // The flash itself reads the build through the lock.
        let step = core.firmware_flash_link_restored(5_000, "");
        assert!(matches!(step.actions[..], [FlashAction::ReadBuild { .. }]));
        assert!(
            core.firmware_flash_blockers(OK)
                .contains(&FlashBlocker::FlashInProgress),
            "no new flash before the restart is confirmed"
        );
        // The DE1 boots the new build: the lock is released.
        core.on_notification(
            Source::De1MmrRead,
            &mmr_packet(MmrRegister::FirmwareVersion, 1358),
            5_100,
        );
        let status = core.firmware_flash_status(5_200).status.unwrap();
        assert_eq!(status.phase, FlashPhase::Succeeded);
        assert!(!core.firmware_locks_writes());
        assert!(!core.set_user_present(true).commands.is_empty());
    }

    #[test]
    fn reconnect_during_an_interrupted_erase_stays_locked() {
        let mut core = ready_core();
        let (mut flash, _) = tests::synthetic_flash(4, 0);
        flash.on_write_ack(10);
        flash.on_tick(1_010);
        flash.on_write_ack(1_050);
        core.firmware_flash = Some(flash);
        core.de1_link_lost();
        core.reset();
        let status = core.firmware_flash_status(3_000).status.unwrap();
        assert_eq!(status.phase, FlashPhase::Failed);
        assert!(status.erase_settling);
        assert!(core.firmware_locks_writes());
        assert!(refused(&core.set_user_present(true)));
        assert!(!core.firmware_flash_exit());
    }

    #[test]
    fn link_loss_during_verify_stays_locked_through_the_check() {
        let mut core = ready_core();
        let (mut flash, _) = tests::synthetic_flash(1, 0);
        flash.on_write_ack(10);
        flash.on_tick(1_010);
        flash.on_write_ack(1_050);
        flash.on_fw_map(&[0, 0, 0, 1, 0xFF, 0xFF, 0xFF], 1_100);
        flash.on_write_ack(1_130);
        flash.on_tick(2_700); // the verify request goes out
        core.firmware_flash = Some(flash);
        core.de1_link_lost();
        core.reset();
        let status = core.firmware_flash_status(3_000).status.unwrap();
        assert_eq!(
            status.phase,
            FlashPhase::Verifying,
            "the DE1 may still be checking"
        );
        assert!(refused(&core.connect_sweep(
            &de1_domain::ConnectSweepSettings::default(),
            3_100
        )));
        let status = core
            .firmware_flash_tick(2_700 + VERIFY_TIMEOUT_MS)
            .status
            .unwrap();
        assert_eq!(status.phase, FlashPhase::AwaitingPowerCycle);
        assert!(status.verify_unconfirmed);
        assert!(refused(&core.connect_sweep(
            &de1_domain::ConnectSweepSettings::default(),
            2_800 + VERIFY_TIMEOUT_MS
        )));
    }

    #[test]
    fn exit_from_the_power_cycle_step_releases_everything() {
        let mut core = ready_core();
        to_awaiting(&mut core);
        assert!(core.firmware_flash_exit());
        assert!(!core.firmware_locks_writes());
        assert!(core.firmware_flash_status(0).status.is_none());
    }

    #[test]
    fn a_restored_wait_checks_the_serial_through_the_mmr_replies() {
        // After a browser restart: restored, bound to the persisted serial,
        // reconnected with a new web id. The serial and build replies arrive
        // as ordinary MMR notifications.
        for (serial, released) in [(4242, true), (9999, false)] {
            let mut core = ready_core();
            core.firmware_flash_restore_awaiting(1358, false, 1_000);
            core.firmware_flash_bind_machine(4242, "");
            let up = core.firmware_flash_link_restored(60_000, "web-id-new");
            assert!(matches!(
                up.actions[..],
                [
                    FlashAction::ReadSerial { .. },
                    FlashAction::ReadBuild { .. }
                ]
            ));
            core.on_notification(
                Source::De1MmrRead,
                &mmr_packet(MmrRegister::FirmwareVersion, 1358),
                60_050,
            );
            assert!(
                core.firmware_locks_writes(),
                "no build accepted before the serial"
            );
            core.on_notification(
                Source::De1MmrRead,
                &mmr_packet(MmrRegister::SerialNumber, serial),
                60_060,
            );
            assert_eq!(!core.firmware_locks_writes(), released, "serial {serial}");
        }
    }

    #[test]
    fn restore_awaiting_after_a_reload_relocks_until_the_build_is_read() {
        let mut core = ready_core();
        let step = core.firmware_flash_restore_awaiting(1358, true, 1_000);
        let status = step.status.unwrap();
        assert_eq!(status.phase, FlashPhase::AwaitingPowerCycle);
        assert!(status.verify_unconfirmed);
        assert!(core.firmware_locks_writes());
        // A second restore while one exists is a no-op.
        let again = core.firmware_flash_restore_awaiting(1333, false, 1_100);
        assert_eq!(again.status.unwrap().target_build, 1358);
        // Any connect path (fresh connect, reload, auto-reconnect) reports
        // the link up, minutes later: the build read is issued.
        let up = core.firmware_flash_link_restored(300_000, "");
        assert!(matches!(up.actions[..], [FlashAction::ReadBuild { .. }]));
        core.on_notification(
            Source::De1MmrRead,
            &mmr_packet(MmrRegister::FirmwareVersion, 1358),
            2_000,
        );
        assert!(!core.firmware_locks_writes());
    }

    #[test]
    fn cancel_through_the_core_returns_the_sleep_write() {
        let mut core = ready_core();
        install(&mut core, 8);
        let step = core.firmware_flash_cancel(150);
        assert_eq!(
            step.actions,
            vec![FlashAction::Write {
                target: WriteTarget::De1RequestedState,
                data: vec![0x00]
            }]
        );
        assert_eq!(step.status.unwrap().phase, FlashPhase::Cancelled);
    }

    #[test]
    fn the_flash_survives_reset_and_reset_counts_as_a_link_loss() {
        let mut core = ready_core();
        install(&mut core, 8);
        core.reset();
        let status = core.firmware_flash_status(300).status.unwrap();
        assert_eq!(status.phase, FlashPhase::Failed);
        assert_eq!(
            status.failure,
            Some(FlashFailure::LinkLost {
                phase: FlashPhase::Preparing
            })
        );
        assert!(!core.firmware_locks_writes(), "before the erase: released");
        assert!(core.firmware_flash_dismiss());
        assert!(core.firmware_flash_status(300).status.is_none());
    }

    /// Drive a whole flash through the CremaCore surface, the way a shell
    /// would, against a simulated DE1. Returns the bytes written to the
    /// simulated bank and the final status.
    fn drive(
        core: &mut CremaCore,
        first: Vec<FlashAction>,
        image_len: usize,
    ) -> (Vec<u8>, FlashStatus) {
        let mut bank = vec![0u8; image_len];
        let mut verify_requested = false;
        let mut queue: std::collections::VecDeque<FlashAction> = first.into();
        let mut now = 100u64;
        let mut restarted = false;
        for _ in 0..400_000 {
            let step = if let Some(action) = queue.pop_front() {
                now += 5;
                match action {
                    FlashAction::Write { target, data } => {
                        match target {
                            WriteTarget::De1FwMapRequest => verify_requested = data[2] == 0,
                            WriteTarget::De1MmrWrite => {
                                let off = (usize::from(data[1]) << 16)
                                    | (usize::from(data[2]) << 8)
                                    | usize::from(data[3]);
                                bank[off..off + 16].copy_from_slice(&data[4..]);
                            }
                            _ => {}
                        }
                        core.firmware_flash_on_write_ack(now)
                    }
                    FlashAction::SubscribeFwMap => continue,
                    FlashAction::ReadFwMap => {
                        let frame: [u8; 7] = if verify_requested {
                            [0, 0, 0, 1, 0xFF, 0xFF, 0xFD]
                        } else {
                            [0, 0, 0, 1, 0xFF, 0xFF, 0xFF]
                        };
                        core.firmware_flash_on_fw_map(&frame, now)
                    }
                    FlashAction::ReadBuild { .. } => {
                        let target = core.firmware_flash.as_ref().unwrap().target_build();
                        core.on_notification(
                            Source::De1MmrRead,
                            &mmr_packet(MmrRegister::FirmwareVersion, target),
                            now,
                        );
                        core.firmware_flash_status(now)
                    }
                    FlashAction::ReadSerial { .. } => {
                        core.on_notification(
                            Source::De1MmrRead,
                            &mmr_packet(MmrRegister::SerialNumber, 4242),
                            now,
                        );
                        core.firmware_flash_status(now)
                    }
                }
            } else {
                now += 250;
                core.firmware_flash_tick(now)
            };
            queue.extend(step.actions);
            let status = step.status.unwrap();
            if status.phase == FlashPhase::AwaitingPowerCycle && !restarted {
                restarted = true;
                assert!(core.firmware_locks_writes());
                core.de1_link_lost();
                now += 45_000;
                queue.extend(core.firmware_flash_link_restored(now, "").actions);
            }
            if matches!(status.phase, FlashPhase::Succeeded | FlashPhase::Failed) {
                return (bank, status);
            }
        }
        panic!("flash did not finish");
    }

    #[test]
    fn a_simulated_flash_runs_end_to_end_through_the_core() {
        let mut core = ready_core();
        let first = install(&mut core, 512);
        let image = core.firmware_flash.as_ref().unwrap().image.clone();
        let (bank, status) = drive(&mut core, first, image.len());
        assert_eq!(status.phase, FlashPhase::Succeeded, "{status:?}");
        assert_eq!(bank, image);
        assert!(!core.firmware_locks_writes());
    }

    /// The real official images, when a local copy is available (crema never
    /// commits firmware bytes). `CREMA_FIRMWARE_DIR` must hold Decent's files
    /// named `de1plus.dat` (1333) and/or `de1nightly.dat` (1358).
    #[test]
    fn real_images_validate_and_flash_end_to_end_when_present() {
        let Ok(dir) = std::env::var("CREMA_FIRMWARE_DIR") else {
            eprintln!("skipped: CREMA_FIRMWARE_DIR not set (no firmware in the repo)");
            return;
        };
        for (file, build, chunks) in [
            ("de1plus.dat", 1333, 28_544),
            ("de1nightly.dat", 1358, 28_992),
        ] {
            let Ok(image) = std::fs::read(std::path::Path::new(&dir).join(file)) else {
                continue;
            };
            let check = de1_domain::validate_firmware_image(&image);
            assert!(check.ok, "{file}: {:?}", check.error);
            assert_eq!(check.artifact.as_ref().unwrap().build, build);
            let mut core = ready_core();
            let step = core.firmware_flash_start(image.clone(), OK, 100);
            assert!(step.error.is_none(), "{:?}", step.error);
            assert_eq!(step.status.as_ref().unwrap().chunks_total, chunks);
            let (bank, status) = drive(&mut core, step.actions, image.len());
            assert_eq!(status.phase, FlashPhase::Succeeded, "{file}");
            assert_eq!(
                bank, image,
                "{file}: every chunk at its own big-endian offset"
            );
            assert_eq!(status.chunks_acked, chunks);
        }
    }
}
