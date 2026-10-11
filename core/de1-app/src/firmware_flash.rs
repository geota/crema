//! The DE1 firmware flash state machine — pure logic, sans-IO.
//!
//! The shell owns the GATT link, the clock and keeping the process alive; this
//! module owns *every* protocol decision: which bytes go where, in what order,
//! when a phase is complete, and when to give up. It is fed write ACKs,
//! `FWMapRequest` (A009) frames, build-number reads, link events and clock
//! ticks, and answers with [`FlashAction`]s plus a [`FlashStatus`] snapshot.
//!
//! ## The sequence (cited against the three reference apps)
//!
//! 1. **Preconditions** — checked by [`CremaCore::firmware_flash_blockers`]
//!    before anything starts (see that function).
//! 2. **Sleep the machine and lock out other writes.** decaid requests
//!    `sleeping` before erase (`unified_de1.firmware.dart:12`) and holds an
//!    exclusive MMR gate for the whole flash (`firmware_mmr_gate.dart:18-25`);
//!    de1app sends Sleep on the update-mode connect (`de1_comms.tcl:307-312`).
//!    Crema writes `RequestedState = Sleep`, waits for its ACK plus
//!    [`SLEEP_SETTLE_MS`], and from here until verification ends
//!    [`FirmwareFlash::locks_writes`] makes the core drop every other command.
//! 3. **Erase** the spare bank: subscribe to A009, then write
//!    `FWMapRequest{WindowIncrement 0, FWToErase 1, FWToMap 1, FirstError
//!    FF FF FF}` (decaid `unified_de1.firmware.dart:45-52`, `:104-109`; de1app
//!    `de1_comms.tcl:1020-1047` sends the same with FirstError 0, which the DE1
//!    merely echoes). Erase is complete on a frame with `FWToErase == 0 &&
//!    FWToMap == 1` that arrives **after the erase request's own ACK**
//!    (Decenza `04fca499`: a late verify frame from a previous attempt is
//!    byte-identical). Crema races the notification against a fresh read of
//!    A009 every [`FW_MAP_POLL_INTERVAL_MS`] (decaid `a207a74d`,
//!    `unified_de1.firmware.dart:122-173`). If the DE1 says nothing at all for
//!    [`ERASE_FALLBACK_MS`] the upload starts anyway — de1app always waits a
//!    fixed 10 s (`de1_comms.tcl:1048`) and Decenza uses the same 10 s fallback
//!    (`firmwareupdater.cpp:40`); a 1 s wait was the bug behind Decenza's
//!    `0x4800` verify failures. If it keeps reporting "still erasing", crema
//!    waits up to [`ERASE_TIMEOUT_MS`] (decaid's 60 s) and fails.
//! 4. **Upload** the image in 16-byte chunks to `WriteToMMR` (A006), one
//!    write-with-response in flight, the next sent on the previous ACK (de1app
//!    `bluetooth.tcl:3303-3312`, decaid `unified_de1.firmware.dart:228-246`).
//!    Each packet is `[0x10][offset as u24 BIG-endian][16 bytes]` — de1app
//!    `de1_comms.tcl:1120` (`"\x10" + make_U24P0 offset`, `binary.tcl:259-264`
//!    packs high byte first), Decenza `firmwarepackets.h:51-62`. Decenza's
//!    little-endian addresses landed writes on peripheral registers and fired
//!    the pumps (`FIRMWARE_UPDATE.md` lesson 2), so the frame comes from
//!    [`firmware_write_frame`], which is golden-tested.
//! 5. **Verify**: after the last ACK wait [`POST_UPLOAD_SETTLE_MS`] (Decenza),
//!    re-subscribe to A009 (de1app `de1_comms.tcl:1095`, Decenza
//!    `firmwareupdater.cpp:819-822`), write `FWMapRequest{0, 0, 1, FF FF FF}`
//!    (`FWMapRequest::request_first_error`). A frame is a verdict only when
//!    `WindowIncrement == 0 && FWToErase == 0 && FWToMap == 1 && FirstError !=
//!    FF FF FF` (decaid `_isTerminalVerificationResponse`,
//!    `unified_de1.firmware.dart:182-187`; Decenza `04fca499`). `FF FF FD` is
//!    success; anything else is the first bad offset. Bounded by
//!    [`VERIFY_TIMEOUT_MS`] (decaid's 120 s).
//! 6. **Power-cycle**: the DE1 keeps running the old image until it is
//!    switched off and on (de1app pages 4/5, `de1_skin_settings.tcl:1250-1265`;
//!    Decenza `AwaitingReboot`). Crema releases the write lock and waits.
//! 7. **Read back** the build at MMR `0x800010` once the link has dropped and
//!    come back; only an **exact** match with the target build after a restart
//!    is success (Decenza `firmwareupdater.cpp:120-200`).
//!
//! ## Failure is non-destructive
//!
//! Every write lands in the DE1's inactive flash bank; the bootloader switches
//! banks only after a successful verify (Decenza `FIRMWARE_UPDATE.md` "Safety:
//! dual-bank flash"; decaid's real-hardware run of 2026-07-16 failed verify and
//! the machine kept reporting its old build). So a failure or cancel at any
//! step leaves the old firmware running; retry restarts from erase — the
//! protocol has no resume.

use de1_domain::{
    FIRMWARE_CHUNK_LEN, FirmwareArtifact, FirmwareImageError, validate_firmware_image,
};
use de1_protocol::{
    FIRST_ERROR_NONE, FIRST_ERROR_REQUEST, FWMapRequest, MachineState, firmware_write_frame,
    requested_state,
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
pub const ERASE_FALLBACK_MS: u64 = 10_000;

/// The DE1 keeps reporting "still erasing": give up after this (decaid
/// `firmwareEraseTimeout`, `unified_de1.dart:74`).
pub const ERASE_TIMEOUT_MS: u64 = 60_000;

/// Fresh-read cadence of A009 during erase and verify (decaid
/// `_firmwareMapPollInterval`, `unified_de1.firmware.dart:3`).
pub const FW_MAP_POLL_INTERVAL_MS: u64 = 250;

/// A poll read with no answer for this long is abandoned and re-issued
/// (decaid `_firmwareMapPollReadTimeout`, `AI_BLE_NOTES.md:651`).
pub const FW_MAP_POLL_READ_TIMEOUT_MS: u64 = 2_000;

/// Any single write unacknowledged this long fails the flash. The shells'
/// own write timeouts (Android 5 s + one retry) normally fire first.
pub const WRITE_ACK_TIMEOUT_MS: u64 = 15_000;

/// Pause between the last chunk's ACK and the verify request (Decenza
/// `m_postUploadSettleMs`, `FIRMWARE_UPDATE.md` log: "settling 1500 ms").
pub const POST_UPLOAD_SETTLE_MS: u64 = 1_500;

/// Verification bound (decaid `firmwareVerificationTimeout`,
/// `unified_de1.dart:75`; Decenza uses 60 s).
pub const VERIFY_TIMEOUT_MS: u64 = 120_000;

/// While waiting for the power-cycle, re-read the build this often once the
/// machine is back (de1app's Bengle monitor polls every 3 s, `bengle.tcl:613`).
pub const BUILD_READ_INTERVAL_MS: u64 = 3_000;

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
    /// Verified; the DE1 must be switched off and on to run it.
    AwaitingPowerCycle,
    /// The DE1 restarted on the new build.
    Succeeded,
    /// Stopped by an error; the old firmware is still running.
    Failed,
    /// Stopped by the user; the old firmware is still running.
    Cancelled,
}

/// Why a flash failed. Every failure leaves the old firmware running.
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
    /// No verdict within [`VERIFY_TIMEOUT_MS`].
    VerifyTimeout,
    /// The link dropped during verification and the DE1 came back on a
    /// different build.
    VerifyUnconfirmed {
        /// The build the DE1 reported after reconnecting.
        reported_build: u32,
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
    /// Write `data` with response to `target` and report the ACK.
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
    /// Read MMR `0x800010` through the normal path
    /// ([`CremaCore::read_mmr`] with `FirmwareVersion`).
    ReadBuild,
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
    /// Whether other writes are locked out.
    pub locks_writes: bool,
    /// Why it failed.
    pub failure: Option<FlashFailure>,
    /// The link dropped during verification; the build read decides.
    pub verify_unconfirmed: bool,
    /// The machine has dropped off since verification (i.e. it restarted).
    pub machine_restarted: bool,
    /// After restarting the DE1 still reported this (old) build.
    pub restarted_on_build: Option<u32>,
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
    /// A flash is already running.
    FlashInProgress,
    /// MMR `0x800010` has not been read.
    BuildUnknown,
    /// MMR `MachineModel` has not been read.
    ModelUnknown,
    /// Not a model the reference apps flash (Bengle, or a model decaid's
    /// manifest does not list).
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
        seen_busy: bool,
    },
    Upload,
    Settle {
        since: u64,
    },
    Verify {
        acked_at: Option<u64>,
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
    upload_ended_ms: Option<u64>,
    ended_ms: Option<u64>,
    poll_sent_at: Option<u64>,
    last_poll_ms: u64,
    failure: Option<FlashFailure>,
    verify_unconfirmed: bool,
    link_up: bool,
    machine_restarted: bool,
    restarted_on_build: Option<u32>,
    last_build_read_ms: u64,
    /// A `VerifyUnconfirmed` failure may still turn out fine: if a later
    /// restart reports the target build, the flash took (Decenza's
    /// retroactive success, `firmwareupdater.cpp:140-160`).
    watch_retroactive: bool,
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
        let chunk_count = u32::try_from(image.len() / FIRMWARE_CHUNK_LEN).unwrap_or(u32::MAX);
        let mut flash = FirmwareFlash {
            image,
            artifact,
            chunk_count,
            stage: Stage::Sleep { acked_at: None },
            pending: None,
            chunks_acked: 0,
            started_ms: now_ms,
            upload_started_ms: None,
            upload_ended_ms: None,
            ended_ms: None,
            poll_sent_at: None,
            last_poll_ms: 0,
            failure: None,
            verify_unconfirmed: false,
            link_up: true,
            machine_restarted: false,
            restarted_on_build: None,
            last_build_read_ms: 0,
            watch_retroactive: false,
        };
        let action = flash.send(Pending::Sleep, now_ms);
        Ok((flash, vec![action]))
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

    /// Whether other writes must be dropped: from the Sleep request until the
    /// verdict (decaid's exclusive gate spans the same window).
    #[must_use]
    pub fn locks_writes(&self) -> bool {
        matches!(
            self.stage,
            Stage::Sleep { .. }
                | Stage::Erase { .. }
                | Stage::Upload
                | Stage::Settle { .. }
                | Stage::Verify { .. }
        )
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

    fn fail(&mut self, failure: FlashFailure, now_ms: u64) {
        self.stage = Stage::Failed;
        self.failure = Some(failure);
        self.pending = None;
        self.poll_sent_at = None;
        self.ended_ms = Some(now_ms);
    }

    fn start_upload(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.stage = Stage::Upload;
        self.poll_sent_at = None;
        self.upload_started_ms = Some(now_ms);
        vec![self.send(Pending::Chunk(0), now_ms)]
    }

    /// The pending write was acknowledged.
    pub fn on_write_ack(&mut self, now_ms: u64) -> Vec<FlashAction> {
        let Some((what, _)) = self.pending.take() else {
            return Vec::new();
        };
        match (what, self.stage) {
            (Pending::Sleep, Stage::Sleep { .. }) => {
                self.stage = Stage::Sleep {
                    acked_at: Some(now_ms),
                };
                Vec::new()
            }
            (Pending::Erase, Stage::Erase { seen_busy, .. }) => {
                self.stage = Stage::Erase {
                    acked_at: Some(now_ms),
                    seen_busy,
                };
                self.last_poll_ms = now_ms;
                Vec::new()
            }
            (Pending::Chunk(i), Stage::Upload) => {
                self.chunks_acked = i + 1;
                if self.chunks_acked >= self.chunk_count {
                    self.upload_ended_ms = Some(now_ms);
                    self.stage = Stage::Settle { since: now_ms };
                    Vec::new()
                } else {
                    vec![self.send(Pending::Chunk(i + 1), now_ms)]
                }
            }
            (Pending::Verify, Stage::Verify { .. }) => {
                self.stage = Stage::Verify {
                    acked_at: Some(now_ms),
                };
                self.last_poll_ms = now_ms;
                Vec::new()
            }
            // An ACK that no longer matches the stage (e.g. after a cancel).
            _ => Vec::new(),
        }
    }

    /// The pending write failed.
    pub fn on_write_failed(&mut self, message: &str, now_ms: u64) {
        if self.pending.take().is_some() && self.locks_writes() {
            let phase = self.phase();
            self.fail(
                FlashFailure::WriteFailed {
                    phase,
                    message: message.to_owned(),
                },
                now_ms,
            );
        }
    }

    /// A poll read failed (logged by the shell; the next tick retries).
    pub fn on_read_failed(&mut self) {
        self.poll_sent_at = None;
    }

    /// An A009 frame arrived (notification or poll read).
    pub fn on_fw_map(&mut self, data: &[u8], now_ms: u64) -> Vec<FlashAction> {
        self.poll_sent_at = None;
        let Ok(frame) = FWMapRequest::decode(data) else {
            return Vec::new();
        };
        match self.stage {
            Stage::Erase {
                acked_at: Some(_),
                seen_busy,
            } => {
                if frame.fw_to_erase == 1 {
                    if !seen_busy && let Stage::Erase { seen_busy, .. } = &mut self.stage {
                        *seen_busy = true;
                    }
                    return Vec::new();
                }
                // Erase complete: FWToErase 0, FWToMap 1 (de1app
                // `de1_comms.tcl:572-575`; decaid `_isEraseComplete`). The
                // FirstError bytes are an echo of the request — ignored
                // (Decenza `FIRMWARE_UPDATE.md:179`).
                if frame.window_increment == 0 && frame.fw_to_erase == 0 && frame.fw_to_map == 1 {
                    return self.start_upload(now_ms);
                }
                Vec::new()
            }
            Stage::Verify { acked_at: Some(_) } => {
                let terminal = frame.window_increment == 0
                    && frame.fw_to_erase == 0
                    && frame.fw_to_map == 1
                    && frame.first_error != FIRST_ERROR_REQUEST;
                if !terminal {
                    return Vec::new();
                }
                if frame.first_error == FIRST_ERROR_NONE {
                    self.stage = Stage::AwaitPowerCycle;
                    self.pending = None;
                } else {
                    self.fail(
                        FlashFailure::VerifyFailed {
                            first_error: frame.first_error,
                        },
                        now_ms,
                    );
                }
                Vec::new()
            }
            // Before the request's own ACK, or outside erase/verify: a stale
            // frame (a late verdict from a previous attempt is byte-identical
            // to erase-complete — Decenza `04fca499`).
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

    /// The clock advanced.
    pub fn on_tick(&mut self, now_ms: u64) -> Vec<FlashAction> {
        if let Some((_, sent)) = self.pending
            && self.locks_writes()
            && now_ms.saturating_sub(sent) >= WRITE_ACK_TIMEOUT_MS
        {
            let phase = self.phase();
            self.fail(FlashFailure::AckTimeout { phase }, now_ms);
            return Vec::new();
        }
        match self.stage {
            Stage::Sleep { acked_at: Some(at) } if now_ms.saturating_sub(at) >= SLEEP_SETTLE_MS => {
                self.stage = Stage::Erase {
                    acked_at: None,
                    seen_busy: false,
                };
                vec![
                    FlashAction::SubscribeFwMap,
                    self.send(Pending::Erase, now_ms),
                ]
            }
            Stage::Erase {
                acked_at: Some(at),
                seen_busy,
            } => {
                let waited = now_ms.saturating_sub(at);
                if !seen_busy && waited >= ERASE_FALLBACK_MS {
                    return self.start_upload(now_ms);
                }
                if seen_busy && waited >= ERASE_TIMEOUT_MS {
                    self.fail(FlashFailure::EraseTimeout, now_ms);
                    return Vec::new();
                }
                if self.poll_due(now_ms) {
                    vec![FlashAction::ReadFwMap]
                } else {
                    Vec::new()
                }
            }
            Stage::Settle { since } if now_ms.saturating_sub(since) >= POST_UPLOAD_SETTLE_MS => {
                self.stage = Stage::Verify { acked_at: None };
                vec![
                    FlashAction::SubscribeFwMap,
                    self.send(Pending::Verify, now_ms),
                ]
            }
            Stage::Verify { acked_at: Some(at) } => {
                if now_ms.saturating_sub(at) >= VERIFY_TIMEOUT_MS {
                    self.fail(FlashFailure::VerifyTimeout, now_ms);
                    return Vec::new();
                }
                if self.poll_due(now_ms) {
                    vec![FlashAction::ReadFwMap]
                } else {
                    Vec::new()
                }
            }
            Stage::AwaitPowerCycle | Stage::Failed
                if self.machine_restarted
                    && self.link_up
                    && (self.stage == Stage::AwaitPowerCycle || self.watch_retroactive)
                    && now_ms.saturating_sub(self.last_build_read_ms) >= BUILD_READ_INTERVAL_MS =>
            {
                self.last_build_read_ms = now_ms;
                vec![FlashAction::ReadBuild]
            }
            _ => Vec::new(),
        }
    }

    /// The DE1 link dropped.
    pub fn on_link_lost(&mut self, now_ms: u64) {
        self.link_up = false;
        match self.stage {
            Stage::Sleep { .. } | Stage::Erase { .. } | Stage::Upload | Stage::Settle { .. } => {
                let phase = self.phase();
                self.fail(FlashFailure::LinkLost { phase }, now_ms);
            }
            Stage::Verify { .. } => {
                // The verdict may have been lost with the link — Decenza's
                // "ambiguous verify": the build after a restart decides.
                self.verify_unconfirmed = true;
                self.stage = Stage::AwaitPowerCycle;
                self.pending = None;
                self.poll_sent_at = None;
                self.machine_restarted = true;
            }
            Stage::AwaitPowerCycle => self.machine_restarted = true,
            Stage::Failed if self.watch_retroactive => self.machine_restarted = true,
            _ => {}
        }
    }

    /// The DE1 link is back. Re-reads the build straight away when waiting
    /// on a restart.
    pub fn on_link_restored(&mut self, now_ms: u64) -> Vec<FlashAction> {
        self.link_up = true;
        let waiting = self.stage == Stage::AwaitPowerCycle
            || (self.stage == Stage::Failed && self.watch_retroactive);
        if waiting && self.machine_restarted {
            self.last_build_read_ms = now_ms;
            return vec![FlashAction::ReadBuild];
        }
        Vec::new()
    }

    /// The DE1 reported its build (MMR `0x800010`). Only a read after a
    /// restart counts — before it, the machine still runs the old image (and
    /// for a reflash of the same build an early read would match falsely).
    pub fn on_build(&mut self, build: u32, now_ms: u64) {
        if !self.machine_restarted || !self.link_up {
            return;
        }
        match self.stage {
            Stage::AwaitPowerCycle => {
                if build == self.artifact.build {
                    self.stage = Stage::Succeeded;
                    self.restarted_on_build = None;
                    self.ended_ms = Some(now_ms);
                } else if self.verify_unconfirmed {
                    self.fail(
                        FlashFailure::VerifyUnconfirmed {
                            reported_build: build,
                        },
                        now_ms,
                    );
                    self.watch_retroactive = true;
                    self.machine_restarted = false;
                } else {
                    self.restarted_on_build = Some(build);
                }
            }
            Stage::Failed if self.watch_retroactive && build == self.artifact.build => {
                self.stage = Stage::Succeeded;
                self.failure = None;
                self.watch_retroactive = false;
                self.ended_ms = Some(now_ms);
            }
            _ => {}
        }
    }

    /// Whether a cancel is still harmless: up to the verify request. After
    /// it the DE1 may already be committing the verified bank.
    #[must_use]
    pub fn can_cancel(&self) -> bool {
        matches!(
            self.stage,
            Stage::Sleep { .. } | Stage::Erase { .. } | Stage::Upload | Stage::Settle { .. }
        )
    }

    /// Stop. Returns whether anything was cancelled.
    pub fn cancel(&mut self, now_ms: u64) -> bool {
        if !self.can_cancel() {
            return false;
        }
        self.stage = Stage::Cancelled;
        self.pending = None;
        self.poll_sent_at = None;
        self.ended_ms = Some(now_ms);
        true
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
            failure: self.failure.clone(),
            verify_unconfirmed: self.verify_unconfirmed,
            machine_restarted: self.machine_restarted,
            restarted_on_build: self.restarted_on_build,
        }
    }
}

/// Models the reference apps flash. decaid's manifest lists DE1Pro (3), DE1XL
/// (4), DE1XXL (6) and DE1XXXL (7) (`assets/firmware/manifest.json`
/// `supportedModels`; `DecentMachineModel`, `de1.models.dart:388-397`) and
/// refuses others (`firmware_validator.dart:120-124`). de1app hands the same
/// `bootfwupdate.dat` to every non-Bengle model (`fwfile`,
/// `de1_comms.tcl:947-956`); crema follows the stricter decaid list. Bengle
/// (≥ 128) needs a different image and an extra `FWUpgrade` state (de1app
/// `bengle.tcl:494-524`) and is excluded.
pub const FLASHABLE_MODELS: &[u32] = &[3, 4, 6, 7];

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a flash around a synthetic image, bypassing the manifest digest
    /// (no firmware bytes live in the repo).
    pub(crate) fn synthetic_flash(chunks: u32, now: u64) -> (FirmwareFlash, Vec<FlashAction>) {
        let mut image = vec![0u8; chunks as usize * 16];
        for (i, b) in image.iter_mut().enumerate() {
            *b = u8::try_from(i % 251).unwrap();
        }
        let artifact = de1_domain::firmware_manifest()[1].clone();
        let mut flash = FirmwareFlash {
            image,
            artifact,
            chunk_count: chunks,
            stage: Stage::Sleep { acked_at: None },
            pending: None,
            chunks_acked: 0,
            started_ms: now,
            upload_started_ms: None,
            upload_ended_ms: None,
            ended_ms: None,
            poll_sent_at: None,
            last_poll_ms: 0,
            failure: None,
            verify_unconfirmed: false,
            link_up: true,
            machine_restarted: false,
            restarted_on_build: None,
            last_build_read_ms: 0,
            watch_retroactive: false,
        };
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
    fn verify_success_awaits_a_power_cycle_and_releases_the_lock() {
        let (mut f, now) = to_verify(2);
        // The echoed request (FF FF FF) is "still verifying", not a verdict.
        assert!(f.on_fw_map(&VERIFY, now + 100).is_empty());
        assert_eq!(f.phase(), FlashPhase::Verifying);
        f.on_fw_map(&VERIFY_OK, now + 2_000);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(!f.locks_writes());
        assert!(!f.can_cancel());
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
    fn a_stale_erase_complete_before_the_erase_ack_is_ignored() {
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_write_ack(10);
        f.on_tick(1_010);
        // Late verdict from a previous attempt, before our erase ACK.
        assert!(f.on_fw_map(&ERASE_DONE, 1_020).is_empty());
        assert_eq!(f.phase(), FlashPhase::Erasing);
    }

    #[test]
    fn erase_polls_every_250_ms_and_ignores_still_erasing() {
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_write_ack(10);
        f.on_tick(1_010);
        f.on_write_ack(1_050);
        assert!(f.on_tick(1_200).is_empty());
        assert_eq!(f.on_tick(1_300), vec![FlashAction::ReadFwMap]);
        assert!(f.on_tick(1_400).is_empty(), "one poll in flight");
        assert!(f.on_fw_map(&ERASING, 1_450).is_empty());
        assert_eq!(f.phase(), FlashPhase::Erasing);
        assert_eq!(f.on_tick(1_560), vec![FlashAction::ReadFwMap]);
        f.on_read_failed();
        assert_eq!(f.on_tick(1_820), vec![FlashAction::ReadFwMap]);
    }

    #[test]
    fn a_silent_erase_falls_back_to_uploading_after_ten_seconds() {
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_write_ack(10);
        f.on_tick(1_010);
        f.on_write_ack(1_050);
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
        let (mut f, _) = synthetic_flash(4, 0);
        f.on_write_ack(10);
        f.on_tick(1_010);
        f.on_write_ack(1_050);
        f.on_fw_map(&ERASING, 1_300);
        f.on_tick(1_050 + ERASE_FALLBACK_MS + 1);
        assert_eq!(f.phase(), FlashPhase::Erasing);
        f.on_tick(1_050 + ERASE_TIMEOUT_MS);
        assert_eq!(f.failure, Some(FlashFailure::EraseTimeout));
    }

    #[test]
    fn verify_times_out_after_120_seconds() {
        let (mut f, now) = to_verify(1);
        f.on_tick(now + VERIFY_TIMEOUT_MS - 1);
        assert_eq!(f.phase(), FlashPhase::Verifying);
        f.on_tick(now + VERIFY_TIMEOUT_MS);
        assert_eq!(f.failure, Some(FlashFailure::VerifyTimeout));
    }

    #[test]
    fn an_unacknowledged_write_times_out() {
        let (mut f, now) = to_upload(4);
        f.on_tick(now + WRITE_ACK_TIMEOUT_MS);
        assert_eq!(
            f.failure,
            Some(FlashFailure::AckTimeout {
                phase: FlashPhase::Uploading
            })
        );
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

    #[test]
    fn link_loss_before_verify_fails_and_releases_the_lock() {
        let (mut f, now) = to_upload(4);
        f.on_link_lost(now);
        assert_eq!(
            f.failure,
            Some(FlashFailure::LinkLost {
                phase: FlashPhase::Uploading
            })
        );
        assert!(!f.locks_writes());
    }

    #[test]
    fn cancel_works_until_the_verify_request_and_not_after() {
        let (mut f, now) = to_upload(4);
        assert!(f.status(now).can_cancel);
        assert!(f.cancel(now));
        assert_eq!(f.phase(), FlashPhase::Cancelled);
        assert!(!f.locks_writes());
        assert!(
            f.on_write_ack(now + 1).is_empty(),
            "a late ack does nothing"
        );

        let (mut v, now) = to_verify(1);
        assert!(!v.cancel(now));
        assert_eq!(v.phase(), FlashPhase::Verifying);
    }

    #[test]
    fn success_needs_a_restart_and_the_exact_target_build() {
        let (mut f, now) = to_verify(1);
        f.on_fw_map(&VERIFY_OK, now + 10);
        // A read before any restart is the old firmware — ignored.
        f.on_build(1333, now + 20);
        f.on_build(1358, now + 30);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        f.on_link_lost(now + 40_000);
        assert!(f.on_tick(now + 50_000).is_empty(), "no reads while down");
        assert_eq!(
            f.on_link_restored(now + 70_000),
            vec![FlashAction::ReadBuild]
        );
        f.on_build(1358, now + 70_500);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
    }

    #[test]
    fn a_restart_on_the_old_build_keeps_waiting_and_says_so() {
        let (mut f, now) = to_verify(1);
        f.on_fw_map(&VERIFY_OK, now + 10);
        f.on_link_lost(now + 1_000);
        f.on_link_restored(now + 2_000);
        f.on_build(1333, now + 2_100);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert_eq!(f.status(now).restarted_on_build, Some(1333));
        assert_eq!(
            f.on_tick(now + 2_000 + BUILD_READ_INTERVAL_MS),
            vec![FlashAction::ReadBuild]
        );
    }

    #[test]
    fn link_loss_during_verify_is_decided_by_the_build_after_restart() {
        let (mut f, now) = to_verify(1);
        f.on_link_lost(now + 5);
        assert_eq!(f.phase(), FlashPhase::AwaitingPowerCycle);
        assert!(f.status(now).verify_unconfirmed);
        f.on_link_restored(now + 30_000);
        f.on_build(1333, now + 30_100);
        assert_eq!(
            f.failure,
            Some(FlashFailure::VerifyUnconfirmed {
                reported_build: 1333
            })
        );
        // Power-cycled again later and it booted the new build: it took.
        f.on_link_lost(now + 60_000);
        f.on_link_restored(now + 90_000);
        f.on_build(1358, now + 90_100);
        assert_eq!(f.phase(), FlashPhase::Succeeded);
        assert_eq!(f.failure, None);
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

    /// A simulated DE1: answers each action the way the real machine does, so
    /// a whole flash runs end to end through the public surface only.
    struct SimDe1 {
        flash_bank: Vec<u8>,
        erased: bool,
        build: u32,
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
            corrupt_at,
        };
        let mut queue: std::collections::VecDeque<FlashAction> = actions.into();
        let mut now = 0u64;
        let mut verify_requested = false;
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
                            ERASE_DONE
                        };
                        f.on_fw_map(&frame, now)
                    }
                    FlashAction::ReadBuild => {
                        f.on_build(sim.build, now);
                        Vec::new()
                    }
                };
                queue.extend(next);
            } else {
                now += 250;
                queue.extend(f.on_tick(now));
            }
            if f.phase() == FlashPhase::AwaitingPowerCycle && !f.status(now).machine_restarted {
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
    fn flashable_models_follow_decaid() {
        assert_eq!(FLASHABLE_MODELS, &[3, 4, 6, 7]);
        assert!(
            !FLASHABLE_MODELS
                .iter()
                .any(|&m| de1_protocol::is_bengle_model(m))
        );
    }
}

/// The flash as the shells drive it: through [`CremaCore`](crate::CremaCore),
/// with preconditions, the write lock and the build read-back.
#[cfg(test)]
mod core_tests {
    use super::*;
    use crate::{CremaCore, Source};
    use de1_protocol::MmrRegister;

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
    fn bengle_and_unlisted_models_are_blocked() {
        for model in [128u32, 1, 2, 5] {
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
    fn a_running_flash_locks_out_setters_and_core_commands() {
        let mut core = ready_core();
        install(&mut core, 8);
        assert!(core.firmware_locks_writes());
        let out = core.request_machine_state(MachineState::Espresso);
        assert!(out.commands.is_empty());
        assert!(matches!(
            out.events[..],
            [crate::Event::FirmwareLockoutHit { .. }]
        ));
        // Output paths that are not setters are gated too.
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
    }

    #[test]
    fn the_flash_survives_reset_and_reset_counts_as_a_link_loss() {
        let mut core = ready_core();
        install(&mut core, 8);
        core.reset();
        let step = core.firmware_flash_status(300);
        let status = step.status.expect("kept across reset");
        assert_eq!(status.phase, FlashPhase::Failed);
        assert_eq!(
            status.failure,
            Some(FlashFailure::LinkLost {
                phase: FlashPhase::Preparing
            })
        );
        assert!(!core.firmware_locks_writes());
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
                    FlashAction::ReadBuild => {
                        let target = core.firmware_flash.as_ref().unwrap().target_build();
                        core.on_notification(
                            Source::De1MmrRead,
                            &mmr_packet(MmrRegister::FirmwareVersion, target),
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
                core.de1_link_lost();
                now += 45_000;
                queue.extend(core.firmware_flash_link_restored(now).actions);
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
