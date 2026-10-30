//! FFI value types: the input discriminator [`Source`], the observed [`Event`]s
//! and [`Command`]s the core emits, and the [`CoreOutput`] envelope.

use de1_domain::idle_sleep::IdleSleepBlock;
use de1_domain::{
    BrewCue, BrewSessionSummary, MaintenanceFailure, MaintenancePhase, ShotDisposition, ShotPhase,
    SteamClogReason, StopReason, WaterSessionKind,
};
use de1_protocol::{CalCommand, CalTarget, MachineState, MmrRegister, SubState};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::error::AppError;

/// Which BLE characteristic an incoming notification came from.
///
/// The shell maps each subscribed characteristic UUID to a `Source` before
/// calling [`CremaCore::on_notification`](crate::CremaCore::on_notification).
/// `#[non_exhaustive]`: more sources (water level, …) arrive in later increments.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Source {
    /// The DE1 machine state / substate characteristic (`cuuid_0E`).
    De1State,
    /// The DE1 shot-telemetry characteristic (`cuuid_0D`).
    De1ShotSample,
    /// The connected scale's weight notification.
    ScaleWeight,
    /// The connected scale's *command* characteristic notification — the
    /// channel commands are written to, which on the Bookoo (`ff12`) also
    /// notifies: the scale pushes its serial / settings responses back on it.
    ScaleCommand,
    /// The DE1 water-tank level characteristic (`cuuid_11`).
    De1WaterLevels,
    /// The DE1 version characteristic (`cuuid_01`) — BLE + firmware versions.
    De1Version,
    /// The DE1 `ReadFromMMR` characteristic (`cuuid_05`) — the memory-mapped
    /// register window answers a read request with a notification here.
    De1MmrRead,
    /// The DE1 `Calibration` characteristic (`cuuid_12`) — sensor calibration
    /// reads (current vs. factory) answer here.
    De1Calibration,
    /// The DE1 `ShotSettings` characteristic (`cuuid_0B`) — steam, hot-water,
    /// and group-temp settings. The firmware notifies on this characteristic
    /// whenever the settings change (either from the on-machine UI or from a
    /// host Write), and a connect-time Read seeds the initial snapshot.
    /// Matches reaprime's `transport.shotSettings` notify stream + the
    /// legacy de1app's `de1_read_hotwater` (`bluetooth.tcl:1707`).
    De1ShotSettings,
    /// **DORMANT (snoop-verified 2026-05-21).** The DE1 `HeaderWrite`
    /// characteristic (`cuuid_0F`) is nominally R/W per the BLE
    /// protocol, but the legacy de1app never Reads it and the
    /// firmware returns all-zero bytes when asked. The Crema BLE shell
    /// no longer triggers a Read of this characteristic (dropped in commit
    /// `49f0803`), so this `Source` arm is never dispatched and
    /// [`Event::ProfileHeaderRead`] is never emitted.
    ///
    /// Kept in the enum for forward-compat: if a future firmware exposes
    /// the loaded-profile buffer on the Read side, the wiring is one
    /// `device.readCharacteristic` call away. `#[non_exhaustive]` makes the
    /// variant additive; removing it later would be a breaking ABI change
    /// for any out-of-workspace consumer.
    De1ProfileHeader,
    /// The DE1 `FrameWrite` characteristic (`cuuid_10`) echo — during a
    /// profile upload the DE1 echoes each frame write back as a
    /// write-response / notification, carrying the same 8-byte payload it
    /// received. The orchestrator's ack matcher reads byte 0 (`FrameToWrite`)
    /// to confirm each frame applied.
    De1FrameAck,
    /// The shell's confirmation that a profile `HeaderWrite` (`cuuid_0F`)
    /// write succeeded. Like [`De1FrameAck`](Self::De1FrameAck) this is
    /// synthesised by the shell from the bytes it just wrote (the DE1 sends
    /// only an ATT write response); the core requires it before any frame
    /// ack, so a header write that failed fails the upload instead of
    /// leaving the DE1 with the previous profile's frame count under the new
    /// frames (reaprime awaits the header write the same way,
    /// `unified_de1.profile.dart`).
    De1HeaderAck,
    /// The connected scale's on-scale button characteristic — today only the
    /// Skale II's `EF82` (`ScaleUuids::button_notify`). The shell subscribes
    /// when the core reports the characteristic and forwards presses here;
    /// the core decodes them via `Scale::parse_button` into
    /// [`Event::ScaleButtonPressed`].
    ScaleButton,
}

/// Something the core observed that the UI may want to react to.
///
/// Serialized adjacently tagged — a JSON event reads `{"type":"ShotStarted"}`
/// or `{"type":"Telemetry","content":{"elapsed":…}}`.
///
/// Numeric fields stay as flat scalars (`u32`, `f32`) so they cross the FFI
/// boundary as plain numbers — `Duration` does not transmit through typeshare
/// to TS / Kotlin. The unit each scalar carries is documented in its
/// doc-comment.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
#[non_exhaustive]
pub enum Event {
    /// The DE1's machine state or substate changed.
    MachineStateChanged {
        /// New top-level machine state.
        state: MachineState,
        /// New substate within `state`.
        substate: SubState,
    },
    /// The machine fault the shells should surface changed — entering an
    /// `Error*` substate (`message` = the readable copy from
    /// [`SubState::error_message`]) or leaving it (`message: None`).
    ///
    /// The core decides this rather than each shell mapping the substate,
    /// because one fault is not trustworthy on sight: `ErrorNoAc` (217, "the
    /// front power switch is off") is reported spuriously for a few seconds
    /// while a DE1 wakes or heats. It is surfaced only on firmware ≥ 1337 and
    /// only once the episode has persisted for 6 s, latched until the
    /// substate leaves it (Decenza `98215217`, `b8d625ba`). Every other fault
    /// is surfaced immediately. Edge-triggered: one event per change, never
    /// one per notification — so a webhook keyed on it fires once per episode.
    MachineErrorChanged {
        /// Top-level machine state at the change.
        state: MachineState,
        /// Substate at the change (the fault itself while `message` is set).
        substate: SubState,
        /// Readable fault text, or `None` once the fault has cleared.
        message: Option<String>,
    },
    /// A maintenance cycle (Descale / Clean / AirPurge) the core is
    /// following moved on — see [`MaintenancePhase`]. Edge-triggered: one
    /// event per change of phase, step, cycle, or whole second remaining.
    ///
    /// The descale fields come from the DE1's fixed 720 s step schedule
    /// (Decenza `b1ceab8c`, [`de1_domain::DESCALE_SCHEDULE`]); they are `0`
    /// for Clean / AirPurge and outside a descale step.
    ///
    /// `cold_workaround` is `true` when the core loaded the 1 °C maintenance
    /// profile because old firmware (< 1356, or unknown) drops a cold
    /// request. The DE1 then holds that profile, not the user's: the shell
    /// must forget any "already loaded" profile cache, and on `Finished` /
    /// `Cancelled` re-upload its active profile.
    MaintenanceProgress {
        /// The maintenance state being run.
        state: MachineState,
        /// Where the cycle stands.
        phase: MaintenancePhase,
        /// Descale step, 1-based (`1..=step_count`); `0` when not in a step.
        step_index: u8,
        /// Number of descale steps (5); `0` for Clean / AirPurge.
        step_count: u8,
        /// Descale fraction done, `0.0..=0.999` (`1.0` only on `Finished`).
        progress: f32,
        /// Whole seconds left in the descale cycle.
        seconds_remaining: u32,
        /// Descale pass, from 1 (`0` when not descaling).
        cycle: u8,
        /// The 1 °C cold-maintenance profile was loaded for this cycle.
        cold_workaround: bool,
        /// Why the cycle never started; set only on
        /// [`MaintenancePhase::Failed`].
        failure: Option<MaintenanceFailure>,
    },
    /// The idle-sleep timer ("Sleep after") sent Sleep — see
    /// [`de1_domain::idle_sleep`]. `attempt` is 1 for the episode's send, 2
    /// for the one retry of a send the machine never confirmed. The shells
    /// log it; the Sleep report that follows raises the screensaver like any
    /// sleep the user didn't ask for.
    IdleSleepRequested {
        /// Which send of this idle episode.
        attempt: u8,
    },
    /// The idle-sleep timer has expired but can't send Sleep yet; it retries
    /// on every tick. Edge-triggered: once per reason, not once per tick.
    IdleSleepBlocked {
        /// Why it can't send.
        reason: IdleSleepBlock,
        /// The machine state at the time, when known.
        state: Option<MachineState>,
    },
    /// The machine never reported the timer's Sleep (sent twice); the
    /// countdown re-armed in full rather than re-sending every tick.
    IdleSleepUnconfirmed,
    /// An espresso shot began.
    ShotStarted,
    /// The shot moved to a new phase.
    ShotPhaseChanged {
        /// The phase now in effect.
        phase: ShotPhase,
    },
    /// The DE1 advanced to a new profile frame.
    ShotFrameChanged {
        /// Zero-based frame index.
        frame: u8,
    },
    /// A telemetry sample arrived from the DE1.
    Telemetry {
        /// Milliseconds since the shot began; `0` if no shot is in progress.
        elapsed: u32,
        /// Group pressure, bar.
        group_pressure: f32,
        /// Group flow, ml/s.
        group_flow: f32,
        /// Group-head temperature, °C.
        head_temp: f32,
        /// Mix temperature, °C — the DE1's blended "group" water temperature.
        mix_temp: f32,
        /// Steam-heater temperature, °C.
        steam_temp: f32,
        /// Running shot volume dispensed so far, ml — integrated from the
        /// telemetry flow stream against the DE1's `sample_time` ticks
        /// (BLE-jitter-immune when the line-frequency is known, host-clock
        /// fallback otherwise). Resets to 0 on every `ShotStarted`.
        dispensed_volume: f32,
        /// Target group-head temperature, °C — the heater setpoint for the
        /// active frame. Legacy "Goal temperature" curve on the live shot
        /// chart (`de1_de1.tcl:540` maps `goal_temperature ← SetHeadTemp`).
        set_head_temp: f32,
        /// Target group pressure, bar — the pump pressure setpoint for the
        /// active frame. Legacy "Goal pressure" curve.
        set_group_pressure: f32,
        /// Target group flow, ml/s — the pump flow setpoint for the active
        /// frame. Legacy "Goal flow" curve.
        set_group_flow: f32,
        /// Puck resistance — `group_pressure / group_flow²`, the
        /// de1app/DSx derived "resistance" metric (the R4 read-path).
        /// `None` when group flow is too low to divide by meaningfully —
        /// the near-zero-flow region produces noisy spikes that have no
        /// useful interpretation. Units are `bar / (ml/s)²`. Surfaced on
        /// the event itself so every shell consumes the same value
        /// (previously each shell duplicated the formula + threshold).
        resistance: Option<f32>,
        /// Scale-derived puck resistance — `group_pressure /
        /// weight_flow²` where `weight_flow` is the scale's mass-flow
        /// rate (g/s). The truer extraction signal during the pour
        /// because it measures what exits the puck into the cup, not
        /// what the pump pushes in. `None` when no scale is paired (no
        /// estimate yet), when the scale's flow is below the resistance
        /// floor, or when any input is non-finite. Units: `bar / (g/s)²`.
        /// The chart prefers this over [`Self::resistance`] per-sample
        /// (with the DE1-flow value as fallback when this is `None`)
        /// so shots with and without a scale render coherently. The
        /// legacy de1app TCL computes the same metric — see
        /// `de1plus/gui.tcl:3414-3416`.
        resistance_weight: Option<f32>,
        /// The water this sample adds to the filter / descale counters, ml —
        /// the volume integrator's increment (gap-capped at
        /// `MAX_COUNTED_GAP_S`), only in the states that count
        /// (`counts_toward_water`: espresso and hot water). `None` otherwise.
        /// The shells add it to their persisted litre total; the counting
        /// rule lives here.
        counted_ml: Option<f32>,
    },
    /// A weight reading arrived from the scale, smoothed by the flow estimator.
    ScaleReading {
        /// Robust current weight, grams.
        weight: f32,
        /// Robust mass-flow rate, grams per second — the core's own estimate
        /// from the weight series, available for every supported scale.
        flow: f32,
        /// The scale's own native mass-flow rate, grams per second, when the
        /// scale reports one (the Bookoo does); `None` otherwise. Distinct
        /// from the core-computed `flow` — surfaced as raw data.
        device_flow: Option<f32>,
        /// The scale's own built-in-timer reading, milliseconds, when the
        /// scale reports one (the Bookoo does); `None` otherwise.
        device_timer: Option<u32>,
        /// The scale's live beeper-volume setting `0..=5`, when the scale
        /// echoes its settings in the weight notification (the Bookoo does);
        /// `None` otherwise. Lets a settings control display the real value.
        device_volume: Option<u8>,
        /// The scale's live auto-standby timeout, minutes, when the scale
        /// echoes its settings (the Bookoo does); `None` otherwise.
        device_standby: Option<u8>,
        /// The scale's live battery charge percentage, when the scale reports
        /// it (the Bookoo does); `None` otherwise.
        device_battery: Option<u8>,
        /// Whether the scale's flow smoothing is on, when the scale echoes its
        /// settings in the weight notification (the Bookoo does); `None`
        /// otherwise. Lets a settings toggle reflect the real on/off state.
        device_flow_smoothing: Option<bool>,
        /// The scale's live auto-stop mode id (`0` = Flow-Stop, `1` =
        /// Cup-Removal), when the scale echoes its settings in the weight
        /// notification (the Bookoo does); `None` otherwise. Lets a settings
        /// selector reflect the real current mode.
        device_auto_stop: Option<u8>,
    },
    /// The DE1 reported its water-tank level.
    WaterLevel {
        /// Current tank water DEPTH, mm: the raw sensor reading plus the
        /// +5 mm sensor offset (`de1_domain::water_tank_depth_mm`, applied
        /// once, in core), slosh-smoothed. Pass it to `water_tank_ml` /
        /// `water_tank_percent` as-is; never add the offset again.
        level: f32,
        /// The machine's refill threshold in RAW sensor mm, as on the wire; a
        /// refill is wanted when the raw level is at or below it. Convert it
        /// with `water_tank_depth_mm` before comparing it with `level`.
        refill_threshold: f32,
    },
    /// The tank dropped to the user's low-water threshold (default 110 ml,
    /// `0` = off; `CremaCore::set_water_warn_ml`). Once per dip: re-armed by
    /// a refill 55 ml above the threshold, a threshold change, or a new link.
    /// The shell shows the warning in the user's tank unit.
    WaterLow {
        /// The tank DEPTH, mm, as in [`Event::WaterLevel`].
        level: f32,
        /// The tank volume, ml.
        ml: f32,
    },
    /// Auto-stop decided the shot should end. The accompanying [`Command`]
    /// carries the actual stop write.
    StopTriggered {
        /// Why the shot was stopped.
        reason: StopReason,
    },
    /// The auto-stop watcher (re)composed its targets — what will end this
    /// shot automatically. Emitted when arming and again whenever a mid-shot
    /// refresh changes the picture (a scale registering late, a toggle), so
    /// the shells' event logs show exactly what stop-at-weight is armed
    /// with. Issue #15's failures were silent precisely because arming state
    /// was invisible.
    /// What will end the next (or current) shot — the core's own projection,
    /// emitted whenever it changes.
    ///
    /// Between shots this is the *prospective* picture: what would arm if a
    /// shot started right now. From flow start it is the live armed picture.
    /// [`armed`](Self::StopTargetsArmed::armed) says which.
    ///
    /// Shells must render THIS rather than re-deriving the answer from their
    /// own copy of the inputs. The demotion rule is shared
    /// ([`de1_domain::volume_stop_arms`]), but the inputs were not: a shell
    /// evaluating it over its own active-profile yield can disagree with the
    /// core evaluating it over `profile_target_weight`, and did — the Brew
    /// card showed a confident 36 g target while the core had armed nothing
    /// at all (geota/crema#56).
    ///
    /// All three fields `None` is a meaningful, deliberately-emitted state:
    /// *nothing* will stop this shot.
    ///
    /// The variant keeps its historical name for wire compatibility with
    /// mirrored peers; it reports projections as well as armings.
    StopTargetsArmed {
        /// SAW target, grams; `None` = the weight leg is not armed.
        weight: Option<f32>,
        /// SAV target, ml; `None` = the volume leg is not armed
        /// (demoted behind a scale, or unset).
        volume: Option<f32>,
        /// Max shot time, seconds; `None` = no time cap.
        max_time: Option<f32>,
        /// `true` once an `AutoStop` actually holds these targets (the shot is
        /// running); `false` while this is the between-shots projection.
        armed: bool,
        /// The weight target that WOULD apply, regardless of whether it can
        /// fire. Lets a shell keep showing the row the user configured, greyed
        /// with a reason, rather than silently dropping it.
        weight_configured: Option<f32>,
        /// Why [`weight`] is not armed despite [`weight_configured`] being set
        /// — `"no-scale"` or `"untared-cup"`. `None` when the leg is armed,
        /// nothing is configured, or the user turned the stop off (a choice,
        /// not an impediment).
        weight_blocked: Option<String>,
    },
    /// A commanded stop has not been honoured — the machine is still in the
    /// shot — so the stop write is being re-commanded (issue #15: one lost
    /// or stalled BLE write must not let the shot pour past its target).
    StopRetried {
        /// The stop reason being enforced.
        reason: StopReason,
        /// 1-based retry attempt.
        attempt: u32,
    },
    /// The espresso shot finished.
    ShotCompleted {
        /// Total shot duration, milliseconds.
        duration: u32,
        /// Number of telemetry samples recorded.
        sample_count: u32,
        /// Peak group pressure observed across every Telemetry sample of the
        /// shot, bar. `None` when no telemetry arrived. Computed once at the
        /// core boundary — previously each shell re-iterated the buffered
        /// series for it.
        peak_pressure: Option<f32>,
        /// Peak group-head temperature observed across every Telemetry
        /// sample of the shot, °C. `None` when no telemetry arrived.
        peak_temp: Option<f32>,
        /// Peak scale weight observed across every ScaleReading of the shot,
        /// grams. `None` when no scale was paired (no readings arrived).
        peak_weight: Option<f32>,
        /// The final scale weight observed before the shot ended, grams —
        /// the "shot yield". `None` when no scale was paired (no readings
        /// arrived).
        final_weight: Option<f32>,
        /// What the shell should do with this shot — record it, discard it
        /// as an aborted pull (behind an undo), or skip it entirely as a
        /// cleaning run. Classified at the core boundary
        /// ([`de1_domain::shot_disposition`]) from the duration, the final
        /// weight, and the active profile's beverage type (latched from
        /// profile uploads / [`set_active_profile`]
        /// (crate::CremaCore::set_active_profile)). `serde(default)`
        /// (= `Record`) so a version-skewed consumer parsing an older
        /// core's event stream still decodes.
        #[serde(default)]
        disposition: ShotDisposition,
        /// True when a weight/volume/max-time stop target was armed for
        /// this shot, yet the app never triggered its own auto-stop — the
        /// DE1's own profile frame timing ended the pour on its own instead
        /// (e.g. a Quick Controls yield bump past what the profile's frames
        /// can physically produce in their configured duration). `false`
        /// for an ordinary on-target stop AND for a shot with no target
        /// armed at all (nothing was expected to fire). `serde(default)` (=
        /// `false`) so a version-skewed consumer parsing an older core's
        /// event stream still decodes.
        #[serde(default)]
        ended_without_triggering_stop: bool,
    },
    /// A hot-water or flush session began (the DE1 entered the `HotWater` or
    /// `HotWaterRinse` state).
    WaterSessionStarted {
        /// Whether this is a hot-water pour or a flush.
        kind: WaterSessionKind,
    },
    /// A hot-water or flush session finished.
    WaterSessionCompleted {
        /// Whether this was a hot-water pour or a flush.
        kind: WaterSessionKind,
        /// Total session duration, milliseconds.
        duration: u32,
    },
    /// A guided brew session's clock started — the Start tap, or the
    /// first sustained pour when armed for start-on-pour (issue #10).
    /// Purely scale/timer-driven: no DE1 involvement anywhere in the
    /// brew-session events.
    BrewSessionStarted {
        /// The recipe's brew method (`"pourover"`, …).
        method: String,
        /// The recipe's display name.
        recipe_name: String,
    },
    /// A guided brew session advanced to a new recipe step. Fires for
    /// step 0 at start; shells chime/haptic here for auto-advanced
    /// boundaries.
    BrewStepChanged {
        /// Zero-based index into the recipe's steps.
        step_index: u32,
        /// Session time at the boundary, milliseconds.
        at_ms: u32,
    },
    /// A guided brew cue is due — the shell renders it as sound/haptic
    /// (Settings-gated). Never accompanied by any machine write.
    BrewCueDue {
        /// Which cue.
        cue: BrewCue,
        /// The step the cue belongs to.
        step_index: u32,
    },
    /// A guided brew session ended — the last step finished, or the
    /// user tapped Finish. The summary carries everything the shell
    /// needs to pre-fill the log form and persist the row.
    BrewSessionCompleted {
        /// The measured session summary, weight series included.
        summary: BrewSessionSummary,
    },
    /// A steam session began (the DE1 entered the `Steam` state).
    SteamSessionStarted,
    /// A steam session finished.
    SteamSessionCompleted {
        /// Total session duration, milliseconds.
        duration: u32,
        /// Number of steam telemetry samples recorded.
        sample_count: u32,
    },
    /// The just-finished steam session's telemetry suggests a clogged steam
    /// wand. Accompanies the [`Event::SteamSessionCompleted`] for the session.
    SteamClogSuspected {
        /// Which threshold the steam telemetry tripped.
        reason: SteamClogReason,
    },
    /// Steam eco mode engaged or disengaged. When it changes, an accompanying
    /// [`Command`] rewrites the DE1's steam target temperature.
    SteamEcoModeChanged {
        /// `true` when the steam target dropped to the lower eco temperature.
        eco: bool,
    },
    /// The connected scale stopped reporting weight — no reading has arrived
    /// for roughly a second. Emitted once per stale episode; a fresh reading
    /// re-arms it.
    ScaleStale,
    /// The scale link looks dead: still no reading 5 s after
    /// [`Event::ScaleStale`], or three scale writes in a row failed with no
    /// reading in between. Emitted once per episode — a fresh reading re-arms
    /// it. The shell drops the link at the transport (not a user disconnect)
    /// so its reconnect loop recovers it, but only while its link still
    /// claims ready.
    ScaleLinkDead {
        /// Which rule tripped.
        reason: ScaleLinkDeadReason,
    },
    /// The untared-cup guard latched a virtual zero: a heavy reading right
    /// after first flow held still, so it was a cup landing after the tare —
    /// every later reading this shot is netted against it and stop-at-weight
    /// keeps working, exactly as if the tare had caught it. The shell should
    /// surface an informational notice.
    SawAutoZeroed {
        /// The latched cup baseline, grams.
        offset_g: f32,
    },
    /// Stop-at-weight was suppressed for the rest of this shot: within the
    /// first seconds of flow the scale showed an implausibly heavy reading —
    /// the cup was placed after the tare (or never tared), so every reading
    /// carries the cup's mass and the weight stop would fire nonsensically
    /// (Decenza `weightprocessor.cpp:242-253`) — and the reading never held
    /// still long enough to latch a virtual zero ([`Event::SawAutoZeroed`]).
    /// Volume / max-time stops still bound the shot. The shell should
    /// surface this as a warning.
    SawSuppressedUntaredCup {
        /// The offending reading, grams.
        weight_g: f32,
    },
    /// A manual tare arrived while a shot was pouring and was refused.
    ///
    /// Zeroing the scale mid-extraction destroys the weight curve and, far
    /// worse, leaves stop-at-weight chasing a target it can no longer reach —
    /// the shot runs away. The request is dropped rather than obeyed, and this
    /// event tells the user why their tap did nothing (geota/crema#50).
    ///
    /// Only the MANUAL entry point is guarded. The automatic tare at shot
    /// start goes through the shared command helper and is unaffected.
    TareRefusedMidShot,
    /// The user pressed a button on the scale itself (today only the
    /// Skale II's button characteristic). Surfaced for the shells to log or
    /// map to an action; de1app likewise subscribes and logs the press
    /// (`bluetooth.tcl:2825-2828`) without hard-wiring a behaviour.
    ScaleButtonPressed {
        /// The raw button byte the scale reported.
        button: u8,
    },
    /// The connected scale reported its dynamic configuration on its command
    /// characteristic (the Bookoo's `ff12` channel).
    ///
    /// The scale answers a serial query (`0x0a`, also echoed after every
    /// anti-mistouch write) with the `anti_mistouch` state plus its `serial`
    /// and `firmware_version`; it answers a settings query (`0x0f`) with the
    /// `active_mode` index and `enabled_modes` bitmask. Any one notification
    /// carries only one response type, so the fields the other response would
    /// fill are `None` — the shell folds in whichever fields are present and
    /// keeps its last value for the rest, the two-way pattern the Bookoo's
    /// weight-stream settings already use.
    ScaleConfig {
        /// The scale's live anti-mistouch state — `Some` only for a `03 0c`
        /// serial response, `None` for a `03 0e` settings response.
        anti_mistouch: Option<bool>,
        /// The scale's active display-mode index (`0` = Flow-Rate, `1` =
        /// Timer, `2` = Auto) — `Some` only for a `03 0e` settings response.
        active_mode: Option<u8>,
        /// The scale's enabled-modes bitmask (bit `n` set when mode `n` is
        /// enabled) — `Some` only for a `03 0e` settings response.
        enabled_modes: Option<u8>,
        /// The scale's serial number — `Some` only for a `03 0c` serial
        /// response.
        serial: Option<String>,
        /// The scale's firmware version, formatted for display (e.g.
        /// `"1.4.1"`) — `Some` only for a `03 0c` serial response. The scale's
        /// codec formats it, so no scale-specific encoding reaches the shells.
        firmware: Option<String>,
    },
    /// The DE1 reported its firmware version (the `Version` characteristic).
    /// Carries the **BLE-block** identity — the primary firmware identity per
    /// the legacy `de1_version_string` (`vars.tcl:3867`). The CPU/FW block is
    /// often all-zero on real DE1s and is not surfaced here; `firmware_string`
    /// appends its values only when the FW block carries a distinct, non-zero
    /// `Sha`.
    Firmware {
        /// The BLE-firmware release number.
        release: f32,
        /// The BLE-firmware commits-since-release count.
        commits: u16,
        /// The BLE-firmware API version.
        api_version: u8,
        /// A human-readable firmware label, e.g. `"v1.0.142 (API 4)"`.
        firmware_string: String,
    },
    /// A DE1 memory-mapped register was read back. Emitted when a
    /// `ReadFromMMR` reply decodes to a register Crema models; one event per
    /// register the reply carries.
    MmrValue {
        /// Which register this value is for.
        register: MmrRegister,
        /// The register's raw 32-bit value, little-endian. Some registers are
        /// scaled (e.g. `CalibrationFlowMultiplier` is `int(1000 ×
        /// multiplier)`); the raw word is surfaced and the reader scales it.
        value: u32,
    },
    /// A DE1 sensor calibration value was read back from the `Calibration`
    /// characteristic — only a reply with `WriteKey == 0`, never an echo of
    /// our own read / write request (Decenza `7fed369d`).
    Calibration {
        /// Which sensor the calibration applies to.
        target: CalTarget,
        /// The command the reply answers: [`CalCommand::ReadFactory`] is the
        /// factory slot, anything else the current (in-use) one (de1app
        /// `calibration_ble_received`).
        command: CalCommand,
        /// The `DE1ReportedVal` field — carries nothing on a value reply
        /// (de1app, decaid and Decenza all ignore it); kept for diagnostics.
        de1_reported: f32,
        /// The machine's stored calibration value (`MeasuredVal`) — the one
        /// every reference app reads.
        measured: f32,
    },
    /// A calibration write or factory reset was refused — by the core's
    /// guards before anything reached the wire, or by the DE1's own error
    /// reply ([`CalibrationRefusal::FirmwareRejected`](crate::CalibrationRefusal::FirmwareRejected)).
    /// See [`calibration_guard`](crate::calibration_guard).
    CalibrationRefused {
        /// The sensor the refused request targeted; `None` for the
        /// firmware's error reply, which does not say.
        target: Option<CalTarget>,
        /// Why it was refused.
        reason: crate::CalibrationRefusal,
    },
    /// A write was refused because a firmware upload is locking out other
    /// writes. v1 carries the lockout guard as a stub
    /// ([`firmware_locks_writes`](crate::CremaCore::firmware_locks_writes))
    /// that always returns `false`; v2 will return `true` for the
    /// `Erase..Verifying` phases of a firmware upload. The event names the
    /// refused method so the shell can show a transient toast.
    FirmwareLockoutHit {
        /// Name of the [`CremaCore`](crate::CremaCore) method that was
        /// refused — e.g. `"set_refill_threshold"`.
        method: String,
    },
    /// **DORMANT — see [`Source::De1ProfileHeader`].** This
    /// event would carry the DE1's reported `ShotHeader` if the firmware
    /// supported reading the loaded-profile buffer on `cuuid_0F`. It does
    /// not (snoop-verified 2026-05-21), so the BLE shell no longer triggers
    /// the Read and this variant is never emitted in the current code path.
    ///
    /// Kept for forward-compat — the decode path
    /// ([`CremaCore::handle_profile_header_read`](crate::CremaCore)) and
    /// every consumer (UI, tests) remain in place; flipping the BLE shell
    /// back to issuing a Read is one line in `web/src/lib/ble/de1.ts`.
    ProfileHeaderRead {
        /// Total number of frames in the loaded profile.
        frame_count: u8,
        /// How many leading frames count as preinfusion.
        preinfuse_frame_count: u8,
        /// Minimum pressure allowed in flow-priority frames, bar.
        minimum_pressure: f32,
        /// Maximum flow allowed in pressure-priority frames, ml/s.
        maximum_flow: f32,
    },
    /// The DE1's steam + hot-water + group-temp `ShotSettings` were read,
    /// either at connect-time or in response to a setting change. Mirrors
    /// the legacy de1app's `de1_read_hotwater` flow (`bluetooth.tcl:1707`)
    /// and reaprime's `shotSettings` notify stream.
    ShotSettingsRead {
        /// Target steam temperature, °C.
        steam_temp: f32,
        /// Steam timeout, seconds.
        steam_timeout: f32,
        /// Target hot-water temperature, °C.
        hot_water_temp: f32,
        /// Hot-water volume, ml.
        hot_water_volume: f32,
        /// Hot-water timeout, seconds.
        hot_water_timeout: f32,
        /// Espresso target volume, ml.
        espresso_volume: f32,
        /// Espresso group target temperature, °C.
        group_temp: f32,
    },
    /// The resolved hot-water target changed (the dose, the target yield a
    /// ratio applies to, or whether a scale is operational). The shells
    /// render the hot-water readout, chips and mode card from it; its wire
    /// fields equal the packet the core writes. A remote ignores its own
    /// read-only core's and shows the hub's.
    HotWaterTarget {
        /// The target.
        target: de1_domain::HotWaterTarget,
    },
    /// A cuuid_0B notification differed from the packet Crema wrote this link
    /// generation (another client, or the firmware). The first one is
    /// restored on the next write; a second is left alone, so Crema can't
    /// loop against firmware that normalises a field.
    ShotSettingsForeignChange {
        /// Crema will restore its packet.
        restored: bool,
    },
    /// A profile upload has begun. Carries the frame counts the orchestrator
    /// expects acks for (the header ack, then frames + extensions + tail).
    ProfileUploadStarted {
        /// Title of the profile being uploaded — propagated for the
        /// shell's UI ("Uploading <title>…") and for the active-profile
        /// tracking that pairs with this event.
        title: String,
        /// Number of normal frames the profile has.
        frame_count: u8,
        /// Number of extension frames (one per step that has a limiter).
        extension_frame_count: u8,
        /// This upload's number, unique for the core's lifetime (kept across
        /// [`reset`](crate::CremaCore::reset)). The shell records it with
        /// the upload's writes and passes it back to
        /// [`profile_write_failed`](crate::CremaCore::profile_write_failed)
        /// and `on_profile_write_ack`, so a late report or ack cannot reach a
        /// newer upload. A wasm bridge that rebuilds its core after a panic
        /// starts again at 1: the web shell then supersedes every open batch.
        upload_id: u32,
    },
    /// An upload is waiting for the DE1's machine model before it writes
    /// anything: the model picks the frame encoding (classic DE1 or Bengle
    /// BLE v2), so no upload goes out on a guess. The core has asked for the
    /// MachineModel register; the upload starts when the reply lands
    /// ([`ProfileUploadStarted`](Self::ProfileUploadStarted)) or fails with
    /// [`ProfileUploadFailure::MachineModelUnknown`] after
    /// [`MACHINE_MODEL_WAIT`](crate::MACHINE_MODEL_WAIT).
    ProfileUploadWaitingForModel {
        /// Title of the profile waiting to upload.
        title: String,
    },
    /// One profile frame was acknowledged by the DE1.
    ProfileUploadProgress {
        /// The step index just acknowledged. For an extension-frame ack
        /// this is the step the extension extends (raw `FrameToWrite` byte
        /// minus 32); for the tail it equals `frame_count`.
        frame: u8,
        /// Whether the just-acked write was an extension frame.
        extension: bool,
        /// Total number of acks the upload expects
        /// (`frame_count + extension_count + 1`).
        total_acks: u16,
        /// Number of acks received so far, including this one.
        acks_received: u16,
    },
    /// The whole profile uploaded successfully — every expected ack
    /// arrived in order. Carries the title for the shell's
    /// active-profile bookkeeping.
    ProfileUploadCompleted {
        /// Title of the profile that finished uploading.
        title: String,
    },
    /// The profile upload failed. The core has discarded its in-progress
    /// state.
    ///
    /// Once the header was dispatched, what the DE1 holds is **unknown**:
    /// frames are indexed slot writes with no commit step, so an interrupted
    /// upload can leave the new header's frame count over a mix of new and
    /// old frames (Decenza `aa8232e6`). The core's profile sync retries a
    /// failed sync upload on its backoff and puts the desired profile back
    /// after any other failed upload, once the machine is idle (see
    /// [`ProfileSyncWarning`](Self::ProfileSyncWarning)).
    ProfileUploadFailed {
        /// Why the upload failed.
        reason: ProfileUploadFailure,
    },
    /// A profile-sync upload landed: the DE1 now holds the effective profile
    /// with this fingerprint (the shell's desired one, unless it changed
    /// meanwhile — the newer one follows). A shot start waits for its own.
    ProfileSyncHolds {
        /// The fingerprint of the profile the DE1 now holds.
        fingerprint: String,
    },
    /// The profile sync's one non-blocking warning: `active` when a sync
    /// upload failed and retries are running (3 s, 10 s, then every 30 s);
    /// cleared (`active: false`) by the next success or a disconnect.
    /// Raised once per streak of failures (reaprime `onUploadError`).
    ProfileSyncWarning {
        /// Whether the warning is raised.
        active: bool,
    },
    /// An incoming notification could not be decoded.
    DecodeError {
        /// Human-readable description of the failure.
        message: String,
    },
}

/// Why [`Event::ScaleLinkDead`] fired.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum ScaleLinkDeadReason {
    /// No weight reading for the stale timeout plus the 5 s grace.
    Silent,
    /// Three scale writes in a row failed with no reading in between.
    WritesFailing,
}

/// Why a profile upload failed.
///
/// `#[non_exhaustive]` so additional categories (e.g. a firmware-side
/// "shot in progress" rejection signalled through some future cuuid_10
/// packet) can be added without breaking the FFI surface.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum ProfileUploadFailure {
    /// The profile had no steps.
    Empty,
    /// The profile had more than 20 steps (the DE1 frame limit).
    TooManySteps {
        /// How many steps the rejected profile had.
        count: u32,
    },
    /// A frame ack arrived for a frame number the orchestrator did not
    /// expect — either out-of-order or for a step the profile does not have.
    UnexpectedAck {
        /// The `FrameToWrite` byte the core expected next, raw (`≥ 32` for
        /// an expected extension ack, `== frame_count` for the tail).
        expected: u8,
        /// The `FrameToWrite` byte the ack actually carried.
        got: u8,
    },
    /// A frame ack arrived before the shell confirmed the header write, or
    /// the header confirmation never arrived within
    /// [`PROFILE_UPLOAD_ACK_TIMEOUT`](crate::PROFILE_UPLOAD_ACK_TIMEOUT).
    /// The header write failed, so the DE1 may hold the previous profile's
    /// frame count.
    HeaderNotAcked,
    /// No ack arrived within
    /// [`PROFILE_UPLOAD_ACK_TIMEOUT`](crate::PROFILE_UPLOAD_ACK_TIMEOUT) of
    /// the most recent ack (or upload start, for the first ack).
    AckTimeout {
        /// The `FrameToWrite` byte the core was waiting for when the
        /// timeout fired.
        awaiting: u8,
    },
    /// The shell called
    /// [`cancel_profile_upload`](crate::CremaCore::cancel_profile_upload)
    /// mid-upload, or a new upload superseded this one.
    Aborted,
    /// A profile write of this upload did not reach the DE1 (the shell
    /// reported it through
    /// [`profile_write_failed`](crate::CremaCore::profile_write_failed)).
    /// The shell stops the upload's remaining writes.
    WriteFailed,
    /// The machine model never arrived within
    /// [`MACHINE_MODEL_WAIT`](crate::MACHINE_MODEL_WAIT), so the frame
    /// encoding (classic DE1 or Bengle) is unknown and nothing was written.
    MachineModelUnknown,
    /// An unexpected [`AppError`] variant — the bridge couldn't classify it.
    /// The `message` carries the `AppError`'s `Display` output so the shell
    /// can surface it in a toast / log without losing the underlying cause.
    ///
    /// Previously such variants were silently coerced to
    /// [`ProfileUploadFailure::Empty`], misclassifying real failures.
    Internal {
        /// Human-readable `Display` of the underlying error.
        message: String,
    },
}

impl From<AppError> for ProfileUploadFailure {
    /// Classify an upload [`AppError`] into the shell-facing failure reason.
    /// Shared by both bridges (wasm + uniffi) so their mappings can't drift
    /// (issue 15 / F27).
    fn from(err: AppError) -> ProfileUploadFailure {
        match err {
            AppError::ProfileUpload(de1_domain::DomainError::NoSteps) => {
                ProfileUploadFailure::Empty
            }
            AppError::ProfileUpload(de1_domain::DomainError::TooManySteps { count }) => {
                ProfileUploadFailure::TooManySteps {
                    count: u32::try_from(count).unwrap_or(u32::MAX),
                }
            }
            // Any other `DomainError` or `AppError` shouldn't reach this path;
            // surface its `Display` rather than silently coercing to `Empty`.
            e => ProfileUploadFailure::Internal {
                message: e.to_string(),
            },
        }
    }
}

/// A writable DE1 GATT characteristic. The shell maps this to a UUID.
///
/// `#[non_exhaustive]`: profile-upload targets arrive later.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum WriteTarget {
    /// The DE1 `RequestedState` characteristic (`cuuid_02`).
    De1RequestedState,
    /// The DE1 steam / hot-water `ShotSettings` characteristic (`cuuid_0B`).
    De1ShotSettings,
    /// The DE1 `ReadFromMMR` characteristic (`cuuid_05`) — an MMR read request
    /// is *written* here; the DE1 answers on the same characteristic's notify.
    De1MmrRequest,
    /// The DE1 `WriteToMMR` characteristic (`cuuid_06`) — an MMR write packet
    /// is sent here. Sibling of [`De1MmrRequest`] on the read side; the two
    /// use distinct UUIDs even though they share a packet layout.
    De1MmrWrite,
    /// The DE1 `Calibration` characteristic (`cuuid_12`) — a calibration read
    /// request is *written* here; the DE1 answers on the same characteristic.
    De1Calibration,
    /// The DE1 `WaterLevels` characteristic (`cuuid_11`) — the same
    /// characteristic the DE1 notifies tank level on; a 4-byte
    /// `WaterLevels` packet is *written* here to set the refill threshold.
    De1WaterLevels,
    /// The DE1 `HeaderWrite` characteristic (`cuuid_0F`) — the 5-byte
    /// `ShotHeader` packet is *written* here at the start of a profile
    /// upload.
    De1ProfileHeader,
    /// The DE1 `FrameWrite` characteristic (`cuuid_10`) — each 8-byte
    /// frame packet (normal frames, extension frames, and the tail) is
    /// *written* here in upload order during a profile upload. The DE1
    /// echoes each write back as a `Source::De1FrameAck` notification.
    De1ProfileFrame,
    /// The DE1 `FWMapRequest` characteristic (`cuuid_09` / `A009`) — erase
    /// and verify requests during a firmware flash
    /// ([`FlashAction::Write`](crate::firmware_flash::FlashAction::Write));
    /// the DE1 answers on the same characteristic's notify / read. Never
    /// emitted in a [`CoreOutput`].
    De1FwMapRequest,
}

/// A BLE write the shell should perform on the core's behalf.
///
/// The core owns the protocol, so a command carries the exact bytes; the shell
/// only needs a characteristic-UUID map, no protocol logic.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
#[non_exhaustive]
pub enum Command {
    /// Write `data` to a DE1 GATT characteristic.
    WriteCharacteristic {
        /// Which characteristic to write.
        target: WriteTarget,
        /// The bytes to write.
        data: Vec<u8>,
    },
    /// Write `data` to the connected scale's command characteristic.
    WriteScale {
        /// The bytes to write.
        data: Vec<u8>,
    },
}

/// The result of feeding the core one input: what it observed (`events`) and
/// what it wants the shell to do (`commands`).
///
/// `#[non_exhaustive]` so further fields can be added without breaking the
/// bridge crates.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
pub struct CoreOutput {
    /// The events observed, in order.
    pub events: Vec<Event>,
    /// BLE writes the shell should perform, in order.
    pub commands: Vec<Command>,
}

#[cfg(test)]
impl CoreOutput {
    /// The `upload_id` of the upload this output started (tests only).
    ///
    /// # Panics
    ///
    /// When the output starts no upload.
    pub(crate) fn started_upload_id(&self) -> u32 {
        self.events
            .iter()
            .find_map(|e| match e {
                Event::ProfileUploadStarted { upload_id, .. } => Some(*upload_id),
                _ => None,
            })
            .expect("the output starts an upload")
    }
}

impl CoreOutput {
    /// Serialize to JSON — the form the FFI bridges hand to the native shells.
    ///
    /// # Errors
    ///
    /// [`AppError::Serialization`] if serialization fails; it should not for a
    /// well-formed `CoreOutput`.
    pub fn to_json(&self) -> Result<String, AppError> {
        serde_json::to_string(self).map_err(|e| AppError::Serialization(e.to_string()))
    }
}
