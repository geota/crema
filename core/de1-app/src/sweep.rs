//! The DE1 connect sweep and the USB-charger minute check — the orchestrator
//! half of [`de1_domain::connect_sweep`], whose module docs carry the list,
//! the registers and the upstream sources.

use std::time::Duration;

use de1_domain::connect_sweep::{
    DEFAULT_ESPRESSO_WARMUP_TIMEOUT_S, DEFAULT_HOT_WATER_IDLE_TEMP_C, DEFAULT_PHASE_1_FLOW_ML_S,
    DEFAULT_PHASE_2_FLOW_ML_S, MAX_TANK_TEMP_C,
};
use de1_domain::{ConnectSweepSettings, UsbChargingMode, usb_charger_decision};
use de1_protocol::{MachineState, MmrRegister, ShotSettings};

use crate::{CoreOutput, CremaCore, mmr_write_command, ms_to_duration};

/// Append `more` to `out`, events then commands, order kept.
fn append(out: &mut CoreOutput, more: CoreOutput) {
    out.events.extend(more.events);
    out.commands.extend(more.commands);
}

/// `value` rounded into `0..=max`, as an MMR byte value.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn whole_degrees(value: f32, max: f32) -> u8 {
    if value.is_finite() {
        value.round().clamp(0.0, max) as u8
    } else {
        0
    }
}

/// Seconds as a `Duration`, negative / non-finite read as zero.
fn secs(value: f32) -> Duration {
    Duration::from_secs_f32(if value.is_finite() {
        value.max(0.0)
    } else {
        0.0
    })
}

impl CremaCore {
    /// Re-assert every machine setting Crema owns a preference for — run by
    /// both shells once the DE1 link is ready. One `CoreOutput` carrying, in
    /// order: USB charger (smart-charging decision), the user-presence
    /// feature flag, fan threshold, the heater tweaks (phase-1 / phase-2
    /// flow, hot-water idle temperature, espresso warm-up timeout, steam
    /// two-tap stop), the refill point, the tank-temperature threshold, the
    /// steam / hot-water packet, steam flow, flush timeout and temperature,
    /// and steam eco when on. See [`de1_domain::connect_sweep`].
    ///
    /// Also remembers the charging mode for
    /// [`usb_charger_tick`](Self::usb_charger_tick) and the tank threshold
    /// for the cold-maintenance restore.
    ///
    /// Refused while a firmware upload is in progress (one
    /// [`crate::Event::FirmwareLockoutHit`], no commands); empty on a
    /// read-only mirror.
    pub fn connect_sweep(&mut self, settings: &ConnectSweepSettings, now_ms: u64) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("connect_sweep") {
            return out;
        }
        self.clock = self.clock.max(ms_to_duration(now_ms));
        let mut out = CoreOutput::default();

        self.usb_charging = settings.usb_charging;
        append(&mut out, self.usb_charger_write(settings.battery_percent));
        append(&mut out, self.set_feature_flags(1));
        append(
            &mut out,
            self.set_fan_threshold(whole_degrees(settings.fan_threshold_c, 60.0)),
        );
        // Heater tweaks, de1app `set_heater_tweaks` order.
        append(
            &mut out,
            self.set_phase_1_flow_rate(
                settings
                    .phase_1_flow_ml_s
                    .unwrap_or(DEFAULT_PHASE_1_FLOW_ML_S),
            ),
        );
        append(
            &mut out,
            self.set_phase_2_flow_rate(
                settings
                    .phase_2_flow_ml_s
                    .unwrap_or(DEFAULT_PHASE_2_FLOW_ML_S),
            ),
        );
        append(
            &mut out,
            self.set_hot_water_idle_temp(
                settings
                    .hot_water_idle_temp_c
                    .unwrap_or(DEFAULT_HOT_WATER_IDLE_TEMP_C),
            ),
        );
        append(
            &mut out,
            self.set_espresso_warmup_timeout(secs(
                settings
                    .espresso_warmup_timeout_s
                    .unwrap_or(DEFAULT_ESPRESSO_WARMUP_TIMEOUT_S),
            )),
        );
        append(
            &mut out,
            self.set_steam_two_tap_stop(u8::from(settings.steam_two_tap)),
        );
        append(
            &mut out,
            self.set_refill_threshold(settings.refill_point_mm),
        );
        let tank = whole_degrees(settings.tank_temp_c, MAX_TANK_TEMP_C);
        self.tank_temp_threshold_c = Some(tank);
        append(&mut out, self.set_tank_threshold(tank));
        // The steam / hot-water packet: the user's dials, the rest from the
        // machine's last echo (both shells' old fallbacks: 60 s, 200 ml, 92 °C).
        let echo = self.steam_hotwater_settings.clone();
        let packet = ShotSettings {
            steam_flags: 0,
            steam_temp_c: settings.steam_temp_c,
            steam_timeout_s: settings.steam_timeout_s,
            hot_water_temp_c: settings.hot_water_temp_c,
            hot_water_volume_ml: settings.hot_water_volume_ml,
            hot_water_timeout_s: echo.as_ref().map_or(60.0, |e| e.hot_water_timeout_s),
            espresso_volume_ml: echo.as_ref().map_or(200.0, |e| e.espresso_volume_ml),
            group_temp_c: echo.as_ref().map_or(92.0, |e| e.group_temp_c),
        };
        append(&mut out, self.set_steam_hotwater_settings(packet));
        append(&mut out, self.set_steam_flow(settings.steam_flow_ml_s));
        append(
            &mut out,
            self.set_flush_timeout(secs(settings.flush_timeout_s)),
        );
        append(&mut out, self.set_flush_temp(settings.flush_temp_c));
        // Eco last: it rewrites the steam packet on top of the plain one.
        if settings.steam_eco {
            append(&mut out, self.enable_steam_eco_mode(true, now_ms));
        }
        self.gate_read_only(out)
    }

    /// [`connect_sweep`](Self::connect_sweep) from the shells' JSON
    /// snapshot (a camelCase [`ConnectSweepSettings`]; missing fields take
    /// its defaults). Unparseable JSON yields one
    /// [`crate::Event::DecodeError`] and no writes.
    pub fn connect_sweep_json(&mut self, settings_json: &str, now_ms: u64) -> CoreOutput {
        match serde_json::from_str::<ConnectSweepSettings>(settings_json) {
            Ok(settings) => self.connect_sweep(&settings, now_ms),
            Err(e) => {
                let mut out = CoreOutput::default();
                out.events.push(crate::Event::DecodeError {
                    message: format!("connect sweep settings JSON parse failed: {e}"),
                });
                out
            }
        }
    }

    /// The once-a-minute USB-charger check (de1app `schedule_minute_task` →
    /// `check_battery_charger`): re-decide from the tablet battery and re-send
    /// the result, which also beats the DE1 turning its port back on after
    /// ~10 minutes. Shells call it every
    /// [`USB_CHARGER_CHECK_INTERVAL_MS`](de1_domain::USB_CHARGER_CHECK_INTERVAL_MS)
    /// while the DE1 is connected.
    ///
    /// `mode` is the user's current setting, so a change applies at the next
    /// minute rather than the next connect.
    pub fn usb_charger_tick(
        &mut self,
        mode: UsbChargingMode,
        battery_percent: Option<u8>,
    ) -> CoreOutput {
        self.usb_charging = mode;
        let out = self.usb_charger_write(battery_percent);
        self.gate_read_only(out)
    }

    /// Decide and build the USB-charger write, updating the held latch.
    fn usb_charger_write(&mut self, battery_percent: Option<u8>) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("usb_charger_tick") {
            return out;
        }
        let asleep = self
            .last_state
            .is_some_and(|info| info.state == MachineState::Sleep);
        let (on, discharging) = usb_charger_decision(
            self.usb_charging,
            battery_percent,
            asleep,
            self.usb_discharging,
        );
        self.usb_discharging = discharging;
        mmr_write_command(MmrRegister::UsbChargerOn, u32::from(on), 4)
    }
}

#[cfg(test)]
mod tests {
    use de1_domain::{ConnectSweepSettings, UsbChargingMode};
    use de1_protocol::{MmrRegister, ShotSettings};

    use crate::{Command, CoreOutput, CremaCore, Source, WriteTarget};

    /// `(register, value)` of every MMR write, in order.
    fn mmr_writes(out: &CoreOutput) -> Vec<(MmrRegister, u32)> {
        out.commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteCharacteristic {
                    target: WriteTarget::De1MmrWrite,
                    data,
                } => {
                    let addr = u32::from_be_bytes([0, data[1], data[2], data[3]]);
                    let value = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
                    MmrRegister::from_address(addr).map(|r| (r, value))
                }
                _ => None,
            })
            .collect()
    }

    fn targets(out: &CoreOutput) -> Vec<WriteTarget> {
        out.commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteCharacteristic { target, .. } => Some(*target),
                Command::WriteScale { .. } => None,
            })
            .collect()
    }

    fn snapshot() -> ConnectSweepSettings {
        ConnectSweepSettings {
            fan_threshold_c: 50.0,
            steam_two_tap: true,
            refill_point_mm: 7.0,
            steam_temp_c: 150.0,
            steam_timeout_s: 90.0,
            hot_water_temp_c: 80.0,
            hot_water_volume_ml: 150.0,
            steam_flow_ml_s: 1.2,
            flush_timeout_s: 4.0,
            flush_temp_c: 90.0,
            steam_eco: false,
            tank_temp_c: 30.0,
            phase_1_flow_ml_s: None,
            phase_2_flow_ml_s: Some(3.5),
            hot_water_idle_temp_c: None,
            espresso_warmup_timeout_s: None,
            usb_charging: UsbChargingMode::Smart,
            battery_percent: Some(40),
        }
    }

    #[test]
    fn the_sweep_asserts_every_owned_setting_in_order() {
        let mut core = CremaCore::new();
        let out = core.connect_sweep(&snapshot(), 1_000);
        assert_eq!(
            mmr_writes(&out),
            vec![
                (MmrRegister::UsbChargerOn, 1), // 40 % <= 55 → charge
                (MmrRegister::FeatureFlags, 1),
                (MmrRegister::FanThreshold, 50),
                (MmrRegister::Phase1FlowRate, 20), // de1app default 2.0 ml/s
                (MmrRegister::Phase2FlowRate, 35),
                (MmrRegister::HotWaterIdleTemp, 990), // de1app default 99.0 °C
                (MmrRegister::HeaterUp2Timeout, 10),  // de1app default 1.0 s
                (MmrRegister::SteamTwoTapStop, 1),
                (MmrRegister::TankTempThreshold, 30),
                (MmrRegister::SteamFlow, 120),
                (MmrRegister::FlushTimeout, 40),
                (MmrRegister::FlushTemp, 900),
            ]
        );
        // The two characteristic writes sit in their de1app places.
        let t = targets(&out);
        let water = t
            .iter()
            .position(|t| *t == WriteTarget::De1WaterLevels)
            .unwrap();
        let steam = t
            .iter()
            .position(|t| *t == WriteTarget::De1ShotSettings)
            .unwrap();
        assert!(water < steam);
        assert_eq!(t.len(), 14);
        // The steam packet carries the user's dials.
        let packet = out
            .commands
            .iter()
            .find_map(|c| match c {
                Command::WriteCharacteristic {
                    target: WriteTarget::De1ShotSettings,
                    data,
                } => ShotSettings::decode(data).ok(),
                _ => None,
            })
            .unwrap();
        assert!((packet.steam_temp_c - 150.0).abs() < 0.5);
        assert!((packet.hot_water_volume_ml - 150.0).abs() < 0.5);
        // The tank threshold is remembered for the cold-maintenance restore.
        assert_eq!(core.tank_temp_threshold_c, Some(30));
    }

    #[test]
    fn tank_threshold_is_clamped_to_45() {
        let mut core = CremaCore::new();
        let mut s = snapshot();
        s.tank_temp_c = 92.0;
        let out = core.connect_sweep(&s, 0);
        assert!(mmr_writes(&out).contains(&(MmrRegister::TankTempThreshold, 45)));
    }

    #[test]
    fn steam_eco_is_armed_by_the_sweep() {
        let mut core = CremaCore::new();
        let mut s = snapshot();
        s.steam_eco = true;
        let _ = core.connect_sweep(&s, 0);
        // Eco engages after the idle delay and rewrites the steam packet.
        let delay = u64::try_from(de1_domain::STEAM_ECO_DELAY.as_millis()).unwrap();
        let out = core.on_tick(delay + 1_000);
        assert!(targets(&out).contains(&WriteTarget::De1ShotSettings));
        // Without it, nothing happens at that point.
        let mut core = CremaCore::new();
        let _ = core.connect_sweep(&snapshot(), 0);
        assert!(core.on_tick(delay + 1_000).commands.is_empty());
    }

    #[test]
    fn usb_charger_follows_de1apps_smart_band_every_minute() {
        let mut core = CremaCore::new();
        let mut s = snapshot();
        s.battery_percent = Some(70);
        let out = core.connect_sweep(&s, 0);
        assert_eq!(mmr_writes(&out)[0], (MmrRegister::UsbChargerOn, 0));
        // Inside the band the "discharging" decision holds…
        let out = core.usb_charger_tick(UsbChargingMode::Smart, Some(60));
        assert_eq!(mmr_writes(&out), vec![(MmrRegister::UsbChargerOn, 0)]);
        // …until the bottom, then it charges and holds that.
        let out = core.usb_charger_tick(UsbChargingMode::Smart, Some(55));
        assert_eq!(mmr_writes(&out), vec![(MmrRegister::UsbChargerOn, 1)]);
        let out = core.usb_charger_tick(UsbChargingMode::Smart, Some(60));
        assert_eq!(mmr_writes(&out), vec![(MmrRegister::UsbChargerOn, 1)]);
        // A mode change applies at the next minute: always-on ignores the
        // battery.
        let out = core.usb_charger_tick(UsbChargingMode::AlwaysOn, Some(100));
        assert_eq!(mmr_writes(&out), vec![(MmrRegister::UsbChargerOn, 1)]);
    }

    #[test]
    fn smart_high_uses_the_sleep_floor_while_the_de1_sleeps() {
        let mut core = CremaCore::new();
        let mut s = snapshot();
        s.usb_charging = UsbChargingMode::SmartHigh;
        s.battery_percent = Some(85);
        let out = core.connect_sweep(&s, 0);
        assert_eq!(mmr_writes(&out)[0], (MmrRegister::UsbChargerOn, 1));
        let _ = core.on_notification(Source::De1State, &[0, 0], 10);
        let out = core.usb_charger_tick(UsbChargingMode::SmartHigh, Some(96));
        assert_eq!(mmr_writes(&out), vec![(MmrRegister::UsbChargerOn, 0)]);
        let out = core.usb_charger_tick(UsbChargingMode::SmartHigh, Some(85));
        assert_eq!(
            mmr_writes(&out),
            vec![(MmrRegister::UsbChargerOn, 0)],
            "asleep: the floor is 15 %"
        );
    }

    #[test]
    fn a_read_only_mirror_sweeps_nothing() {
        let mut core = CremaCore::new();
        core.set_read_only(true);
        assert!(core.connect_sweep(&snapshot(), 0).commands.is_empty());
        assert!(
            core.usb_charger_tick(UsbChargingMode::Smart, Some(10))
                .commands
                .is_empty()
        );
    }
}
