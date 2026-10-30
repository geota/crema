//! Keeping the DE1's steam / hot-water packet (cuuid_0B) in step with what
//! Crema has loaded: F7's profile-sync model ([`crate::profile_sync`])
//! applied to the packet, on the link session the two syncs share.
//!
//! The machine's own hot-water button pours with whatever the packet holds,
//! so the core keeps the packet current with its inputs rather than writing
//! it only when Crema starts hot water: the steam and hot-water targets, the
//! hot-water dose (resolved by [`de1_domain::hot_water`]), the target yield a
//! ratio applies to, whether a scale is operational, the effective profile's
//! group temperature, eco and maintenance.
//!
//! - **One write path.** [`CremaCore::drive_shot_settings`] is the only code
//!   that emits a cuuid_0B write. It runs after an explicit edit, in the
//!   connect sweep, at the end of every notification, on every tick and
//!   before a hot-water request.
//! - **Settled links only.** Nothing is written or recorded on a read-only
//!   mirror or before the on-ready connect sweep of this link generation
//!   (`LinkSession`), so a scale event while the DE1 is down records nothing.
//! - **Dedupe on the bytes.** The encoded packet written in this generation
//!   is recorded; the same bytes are never written twice. The sweep clears
//!   the record, so every connect writes.
//! - **Busy.** Nothing is written while the machine is in a flow state or its
//!   state is unknown; the write goes out at the first non-flow state. The
//!   hot-water request flushes it after its Idle bounce, and an eco change
//!   (which restores the steam target as a steam session starts) is not held.
//! - **Foreign changes.** A cuuid_0B notification that differs from the
//!   recorded write clears the record once per generation, so the next drive
//!   restores Crema's packet; a second one is reported and left alone. The
//!   reply to the shells' connect-time seed read ([`crate::de1_seed_reads`])
//!   is never foreign: both shells finish it before the link is ready, so it
//!   lands before the on-ready sweep, while nothing is recorded yet. It
//!   updates the baseline.
//! - **Telling the shells.** Whenever the resolved hot-water target changes,
//!   [`Event::HotWaterTarget`] carries it; a shell re-reads
//!   [`CremaCore::hot_water_target_json`] after its own input changes.

use de1_domain::{
    HOT_WATER_FIRMWARE, HotWaterContext, HotWaterTarget, resolve_hot_water, sanitize_hot_water_dose,
};
use de1_protocol::{MachineState, ShotSettings};

use crate::{
    Command, CoreOutput, CremaCore, Event, STEAM_TIMEOUT_MAX_S, SteamHotWaterTargets, WriteTarget,
};

/// The DE1 connection the profile sync and the packet sync share: the link
/// generation (bumped by every drop and teardown, kept across
/// [`CremaCore::reset`]) and whether this generation's on-ready connect
/// sweep has run.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub(crate) struct LinkSession {
    pub(crate) generation: u32,
    pub(crate) settled: bool,
}

/// The states a packet write waits out: water is flowing.
fn in_flow(state: MachineState) -> bool {
    matches!(
        state,
        MachineState::Espresso
            | MachineState::Steam
            | MachineState::HotWater
            | MachineState::HotWaterRinse
            | MachineState::SteamRinse
    )
}

impl CremaCore {
    /// Set the steam / hot-water targets the shells own: the steam
    /// temperature and timeout, the hot-water temperature and the hot-water
    /// dose. The packet carries eight fields; the core writes it
    /// read-modify-write ([`drive_shot_settings`](Self::drive_shot_settings)):
    /// the hot-water volume and timeout come from the resolved dose, the
    /// group target is the effective desired profile's temperature (de1app
    /// and Decenza send the profile's temperature; see
    /// [`set_desired_profile`](Self::set_desired_profile)), and every other
    /// field (the flag byte, the espresso volume, and the group target with
    /// no desired profile) is kept from the last packet the machine reported
    /// or Crema wrote — reaprime re-reads the characteristic before every
    /// write and keeps all fields, flags included (`de1_controller.dart`
    /// `_writeSteamSettings`). Before any read, the legacy defaults stand in
    /// ([`ShotSettings::default`]: flags 0, 200 ml espresso, 92 °C, as
    /// de1app and Decenza send).
    ///
    /// The only way the shells set this packet, and what the connect sweep
    /// uses. The dose is clamped on the way in
    /// ([`sanitize_hot_water_dose`]). The write waits for a settled link and
    /// a non-flow state; until then the targets are kept, and the core's
    /// read-back projection ([`Event::ShotSettingsRead`]) shows them.
    pub fn set_steam_hotwater_targets(&mut self, targets: &SteamHotWaterTargets) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_hotwater_targets") {
            return out;
        }
        self.store_steam_hotwater_targets(targets);
        let mut out = CoreOutput::default();
        // The shells' machine snapshot (which outranks the QC dial in
        // `resolve_mode_targets`) follows the core's projection (issue 43):
        // whether the DE1 echoes a host cuuid_0B write is unconfirmed (the
        // hot-water modes hardware run, step 9). A write projects itself; a
        // held one is projected here.
        if !self.drive_shot_settings(&mut out, false)
            && let Some(event) = self.shot_settings_projection()
        {
            out.events.push(event);
        }
        out
    }

    /// [`set_steam_hotwater_targets`](Self::set_steam_hotwater_targets) from
    /// the shells' camelCase `SteamHotWaterTargets` JSON, the face both
    /// bridges export. Unparseable JSON yields one [`Event::DecodeError`]
    /// and changes nothing.
    pub fn set_steam_hotwater_targets_json(&mut self, targets_json: &str) -> CoreOutput {
        match serde_json::from_str::<SteamHotWaterTargets>(targets_json) {
            Ok(targets) => self.set_steam_hotwater_targets(&targets),
            Err(e) => {
                let mut out = CoreOutput::default();
                out.events.push(Event::DecodeError {
                    message: format!("steam / hot-water targets JSON parse failed: {e}"),
                });
                out
            }
        }
    }

    /// Keep `targets`, the dose clamped.
    pub(crate) fn store_steam_hotwater_targets(&mut self, targets: &SteamHotWaterTargets) {
        self.steam_hotwater_targets = Some(SteamHotWaterTargets {
            hot_water: sanitize_hot_water_dose(&targets.hot_water, &HOT_WATER_FIRMWARE),
            ..targets.clone()
        });
    }

    /// A scale is connected, has sent a reading since it connected (or since
    /// [`reset`](Self::reset)), and isn't stale (de1app `is_operational`): the
    /// packet carries the 250 ml backstop and the core stops by weight.
    pub(crate) fn scale_operational(&self) -> bool {
        self.scale.is_some() && self.scale_reading_seen && !self.scale_stale_reported
    }

    /// The resolved hot-water target: the dose over the next shot's target
    /// yield and whether a scale is operational. `None` until a dose is set.
    #[must_use]
    pub fn hot_water_target(&self) -> Option<HotWaterTarget> {
        let targets = self.steam_hotwater_targets.as_ref()?;
        Some(resolve_hot_water(
            &targets.hot_water,
            &HotWaterContext {
                target_yield_g: self.effective_target_weight(),
                scale_operational: self.scale_operational(),
            },
            &HOT_WATER_FIRMWARE,
        ))
    }

    /// [`hot_water_target`](Self::hot_water_target) as JSON (`null` until a
    /// dose is set). Shells read it at start-up and after an input change of
    /// their own (a profile select, a yield or dose edit, a scale event);
    /// other changes arrive as [`Event::HotWaterTarget`].
    #[must_use]
    pub fn hot_water_target_json(&self) -> String {
        serde_json::to_string(&self.hot_water_target()).unwrap_or_else(|_| "null".to_owned())
    }

    /// The machine is busy for a packet write: water is flowing, or its
    /// state is not known yet.
    fn packet_busy(&self) -> bool {
        self.last_state.is_none_or(|s| in_flow(s.state))
    }

    /// The packet Crema wants on the machine, before the eco and
    /// maintenance steam overrides, or `None` with no targets.
    fn shot_settings_intent(&self) -> Option<(ShotSettings, HotWaterTarget)> {
        let targets = self.steam_hotwater_targets.as_ref()?;
        let hot_water = self.hot_water_target()?;
        let baseline = self.steam_hotwater_settings.clone().unwrap_or_default();
        let packet = Self::clamp_shot_settings(ShotSettings {
            steam_temp_c: targets.steam_temp_c,
            steam_timeout_s: targets.steam_timeout_s,
            hot_water_temp_c: targets.hot_water_temp_c,
            hot_water_volume_ml: hot_water.wire_volume_ml,
            hot_water_timeout_s: hot_water.wire_timeout_s,
            group_temp_c: self.profile_group_temp_c().unwrap_or(baseline.group_temp_c),
            ..baseline
        });
        Some((packet, hot_water))
    }

    /// The packet to write: the intent with the steam target set for eco and
    /// held off during a maintenance cycle (de1app `dd5ebda4`; wins over
    /// eco), and a steam timeout of 0 sent as 255 s — de1app: "a setting of
    /// zero would mean, turn off steam immediately, so we set it instead to
    /// the maximum allowed" (`binary.tcl`).
    pub(crate) fn desired_shot_settings(&self) -> Option<ShotSettings> {
        let (mut packet, _) = self.shot_settings_intent()?;
        if self.steam.is_eco_mode() {
            packet.steam_temp_c = self.steam_eco_temp;
        }
        if self.maintenance_steam_off {
            packet.steam_temp_c = 0.0;
        }
        if packet.steam_timeout_s.is_nan() || packet.steam_timeout_s.round() < 1.0 {
            packet.steam_timeout_s = STEAM_TIMEOUT_MAX_S;
        }
        Some(packet)
    }

    /// The core's read-back of what the packet holds for the user: the
    /// targets, not the wire-only eco, maintenance and steam-timeout
    /// rewrites; the hot-water volume is the amount poured, not the scale
    /// backstop.
    fn shot_settings_projection(&self) -> Option<Event> {
        let (packet, hot_water) = self.shot_settings_intent()?;
        Some(Event::ShotSettingsRead {
            steam_temp: packet.steam_temp_c,
            steam_timeout: packet.steam_timeout_s,
            hot_water_temp: packet.hot_water_temp_c,
            hot_water_volume: hot_water.volume_ml.unwrap_or(packet.hot_water_volume_ml),
            hot_water_timeout: packet.hot_water_timeout_s,
            espresso_volume: packet.espresso_volume_ml,
            group_temp: packet.group_temp_c,
        })
    }

    /// Emit [`Event::HotWaterTarget`] when the resolved target changed since
    /// the last one this generation.
    fn emit_hot_water_target(&mut self, out: &mut CoreOutput) {
        let target = self.hot_water_target();
        if target.is_some() && target != self.last_hot_water_target {
            self.last_hot_water_target = target;
            if let Some(target) = target {
                out.events.push(Event::HotWaterTarget { target });
            }
        }
    }

    /// Write the packet if it changed — the only code that emits a cuuid_0B
    /// write (see the module docs). `flush` writes through a busy state: the
    /// hot-water request's Idle bounce has just ended the flow, and an eco
    /// change restores the steam target as a steam session starts. Returns
    /// whether it wrote.
    pub(crate) fn drive_shot_settings(&mut self, out: &mut CoreOutput, flush: bool) -> bool {
        self.emit_hot_water_target(out);
        if self.read_only || !self.link.settled || self.firmware_locks_writes() {
            return false;
        }
        let Some(packet) = self.desired_shot_settings() else {
            return false;
        };
        let bytes = packet.encode();
        if self.shot_settings_written == Some(bytes) || (!flush && self.packet_busy()) {
            return false;
        }
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1ShotSettings,
            data: bytes.to_vec(),
        });
        if let Some(event) = self.shot_settings_projection() {
            out.events.push(event);
        }
        self.shot_settings_written = Some(bytes);
        self.steam_hotwater_settings = Some(packet);
        true
    }

    /// A new link generation began (a drop or a teardown): what was written
    /// is forgotten (so the seed read's reply isn't foreign), the
    /// foreign-restore budget is back, and the target is told afresh.
    pub(crate) fn packet_sync_new_generation(&mut self) {
        self.shot_settings_written = None;
        self.foreign_restore_used = false;
        self.last_hot_water_target = None;
    }

    /// Decode and process a `ShotSettings` notification — the reply to the
    /// connect-time seed read of `cuuid_0B`, a change from the machine's own
    /// UI or another client, or (unconfirmed, the hardware run's step 9) an
    /// echo of Crema's own write. It becomes the baseline whose fields Crema
    /// doesn't own are kept, and emits one [`Event::ShotSettingsRead`]. Bytes
    /// that differ from what Crema wrote this generation are a foreign
    /// change, restored once (see the module docs); an exact echo matches
    /// the record and is not.
    pub(crate) fn handle_shot_settings_read(&mut self, data: &[u8], out: &mut CoreOutput) {
        let mut settings = match ShotSettings::decode(data) {
            Ok(s) => s,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        if let Some(written) = self.shot_settings_written
            && written != settings.encode()
        {
            let restore = !std::mem::replace(&mut self.foreign_restore_used, true);
            if restore {
                self.shot_settings_written = None;
            }
            out.events
                .push(Event::ShotSettingsForeignChange { restored: restore });
        }
        // A 0 °C steam target read back during a maintenance cycle is our own
        // temporary override, not the user's setting — show the real one, so
        // the shells' snapshot doesn't adopt it.
        let shown_steam = match &self.steam_hotwater_targets {
            Some(t) if self.maintenance_steam_off && settings.steam_temp_c == 0.0 => t.steam_temp_c,
            _ => settings.steam_temp_c,
        };
        out.events.push(Event::ShotSettingsRead {
            steam_temp: shown_steam,
            steam_timeout: settings.steam_timeout_s,
            hot_water_temp: settings.hot_water_temp_c,
            hot_water_volume: settings.hot_water_volume_ml,
            hot_water_timeout: settings.hot_water_timeout_s,
            espresso_volume: settings.espresso_volume_ml,
            group_temp: settings.group_temp_c,
        });
        settings.steam_temp_c = shown_steam;
        self.steam_hotwater_settings = Some(settings);
    }
}
