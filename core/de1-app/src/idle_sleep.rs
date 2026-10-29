//! The idle-sleep timer ("Sleep after") — the orchestrator half of
//! [`de1_domain::idle_sleep`], whose module docs carry the rules and the
//! reference-app sources.
//!
//! The policy is fed from two directions:
//!
//! - **by the shells**, through [`CremaCore::idle_sleep`]: the user's
//!   timeout, user activity (touch, keys, navigation, foreground return,
//!   mirror relay), the link state and a periodic tick;
//! - **by the core itself**: every machine-state report, the firmware build
//!   and model reads, every state the app requests (a Sleep is adopted as the
//!   episode's send, anything else counts as activity), every profile upload,
//!   and the link dropping. Shots, uploads, guided brews and maintenance
//!   cycles pin the countdown (see [`CremaCore::idle_sleep_busy`]).
//!
//! Only an authoritative core runs it: a read-only mirror (a secondary)
//! never ticks the timer, because only the device holding the BLE link can
//! put the machine to sleep.

use de1_domain::BrewSessionPhase;
use de1_domain::idle_sleep::{BENGLE_MAX_SLEEP_TIMEOUT_MIN, IdleSleepAction, IdleSleepInput};
use de1_protocol::{MachineState, MmrRegister, is_bengle_model, requested_state};

use crate::{Command, CoreOutput, CremaCore, Event, WriteTarget, mmr_write_command};

impl CremaCore {
    /// Feed the idle-sleep timer one input at the shell's `now_ms` (one
    /// clock per shell, used for every idle-sleep input — Android
    /// `elapsedRealtime`, web `Date.now()`). Returns:
    ///
    /// - on `Tick`: the Sleep write plus [`Event::IdleSleepRequested`] when
    ///   the timer fires, [`Event::IdleSleepBlocked`] once when it is expired
    ///   but blocked, [`Event::IdleSleepUnconfirmed`] when a send was never
    ///   confirmed;
    /// - on `Activity`: a throttled `UserPresent = 1` poke when the machine
    ///   is idle on a ready link (presence follows real activity, never a
    ///   timer);
    /// - on `Configure`: the Bengle firmware's own inactivity timeout (MMR
    ///   `InactivitySleepTimeout`) when the value changed and the machine is a
    ///   known Bengle on a ready link;
    /// - otherwise nothing.
    ///
    /// Empty on a read-only mirror (the configuration is still kept).
    pub fn idle_sleep(&mut self, input: IdleSleepInput, now_ms: u64) -> CoreOutput {
        let mut out = CoreOutput::default();
        match input {
            IdleSleepInput::Configure { minutes } => {
                if self.idle_sleep.configure(minutes) && self.idle_sleep.link_ready() {
                    out = self.bengle_sleep_timeout_write();
                }
            }
            IdleSleepInput::Activity { .. } => {
                // No presence poke into a DE1 a firmware flash holds (its
                // transfer, erase hold or reboot window): the activity still
                // counts, but the write would be refused anyway.
                if !self.read_only
                    && self.idle_sleep.activity(now_ms)
                    && !self.firmware_locks_writes()
                {
                    out = self.set_user_present(true);
                }
            }
            IdleSleepInput::Link { ready } => self.idle_sleep.link(ready),
            IdleSleepInput::Tick => {
                if !self.read_only {
                    let busy = self.idle_sleep_busy();
                    let action = self.idle_sleep.tick(now_ms, busy);
                    self.apply_idle_sleep_action(action, &mut out);
                }
            }
        }
        self.gate_read_only(out)
    }

    /// [`idle_sleep`](Self::idle_sleep) from the shells' JSON (an
    /// adjacently tagged [`IdleSleepInput`]: `{"type":"Tick"}`,
    /// `{"type":"Configure","content":{"minutes":60}}`). Unparseable JSON
    /// yields one [`Event::DecodeError`] and nothing else.
    pub fn idle_sleep_json(&mut self, input_json: &str, now_ms: u64) -> CoreOutput {
        match serde_json::from_str::<IdleSleepInput>(input_json) {
            Ok(input) => self.idle_sleep(input, now_ms),
            Err(e) => {
                let mut out = CoreOutput::default();
                out.events.push(Event::DecodeError {
                    message: format!("idle sleep input JSON parse failed: {e}"),
                });
                out
            }
        }
    }

    /// Work that must never be cut off by an idle sleep: a shot (from
    /// Espresso entry to its recorded completion), a profile upload, a
    /// guided brew (armed, running or paused), a maintenance cycle the core
    /// is following (Descale / Clean / AirPurge, including the
    /// cold-firmware preheat hold), and a firmware flash for as long as it
    /// holds the DE1 (transfer, erase hold, power-cycle wait).
    ///
    /// The flash pins the countdown like the others rather than merely
    /// having its Sleep refused: a refused expiry would leave the policy
    /// believing it had sent (then "unconfirmed", then a retry), while a pin
    /// keeps restarting the countdown, so it starts over from the moment the
    /// lock releases — the flash's own Sleep (before flashing, after a cancel)
    /// is never doubled and nothing fires the instant the lock lets go.
    pub(crate) fn idle_sleep_busy(&self) -> bool {
        self.firmware_locks_writes()
            || self.shot_started.is_some()
            || self.post_shot_settle.is_some()
            || self.profile_upload_in_progress()
            || self.brew_session.as_ref().is_some_and(|session| {
                matches!(
                    session.phase(),
                    BrewSessionPhase::Armed | BrewSessionPhase::Running | BrewSessionPhase::Paused
                )
            })
            || self.maintenance.is_some()
    }

    /// The Bengle firmware's own inactivity timeout, set to the same minutes
    /// as the app's timer (de1app `set_sleep_timeout_minutes`, called on
    /// connect with `screen_saver_delay`; decaid
    /// `PresenceController._pushFirmwareDesiredState`). The firmware only
    /// acts on it while no tablet is connected, so it covers a tablet that
    /// is off or out of range. Empty unless the model is a known Bengle and
    /// the timer is configured.
    pub(crate) fn bengle_sleep_timeout_write(&self) -> CoreOutput {
        let (Some(minutes), Some(model)) = (self.idle_sleep.minutes(), self.de1_machine_model)
        else {
            return CoreOutput::default();
        };
        if !is_bengle_model(model) {
            return CoreOutput::default();
        }
        if let Some(out) = self.refuse_if_firmware_locked("idle_sleep") {
            return out;
        }
        mmr_write_command(
            MmrRegister::InactivitySleepTimeout,
            minutes.min(BENGLE_MAX_SLEEP_TIMEOUT_MIN),
            4,
        )
    }

    fn apply_idle_sleep_action(&mut self, action: IdleSleepAction, out: &mut CoreOutput) {
        match action {
            IdleSleepAction::None => {}
            IdleSleepAction::Sleep { attempt } => {
                if let Some(locked) = self.refuse_if_firmware_locked("idle_sleep") {
                    out.events.extend(locked.events);
                    return;
                }
                out.commands.push(Command::WriteCharacteristic {
                    target: WriteTarget::De1RequestedState,
                    data: vec![requested_state(MachineState::Sleep)],
                });
                out.events.push(Event::IdleSleepRequested { attempt });
            }
            IdleSleepAction::Blocked { reason, state } => {
                out.events.push(Event::IdleSleepBlocked { reason, state });
            }
            IdleSleepAction::Unconfirmed => out.events.push(Event::IdleSleepUnconfirmed),
        }
    }
}

#[cfg(test)]
mod tests {
    use de1_domain::idle_sleep::{IdleActivity, IdleSleepBlock, IdleSleepInput};
    use de1_protocol::{MachineState, MmrRegister};

    use crate::{Command, CoreOutput, CremaCore, Event, Source, WriteTarget};

    const MIN: u64 = 60_000;
    const T0: u64 = 10_000_000;

    fn state_bytes(state: MachineState, substate: u8) -> [u8; 2] {
        [state as u8, substate]
    }

    /// Feed one StateInfo notification.
    fn report(core: &mut CremaCore, state: MachineState, substate: u8) -> CoreOutput {
        core.on_notification(Source::De1State, &state_bytes(state, substate), 1)
    }

    /// Feed one MMR read reply for `register`.
    fn mmr_reply(core: &mut CremaCore, register: MmrRegister, value: u32) -> CoreOutput {
        let addr = register.address().to_be_bytes();
        let mut data = vec![4, addr[1], addr[2], addr[3]];
        data.extend_from_slice(&value.to_le_bytes());
        data.resize(20, 0);
        core.on_notification(Source::De1MmrRead, &data, 1)
    }

    fn tick(core: &mut CremaCore, now: u64) -> CoreOutput {
        core.idle_sleep(IdleSleepInput::Tick, now)
    }

    fn sleep_writes(out: &CoreOutput) -> usize {
        out.commands
            .iter()
            .filter(|c| {
                matches!(c, Command::WriteCharacteristic {
                    target: WriteTarget::De1RequestedState,
                    data,
                } if data == &vec![MachineState::Sleep as u8])
            })
            .count()
    }

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

    /// A core configured for 60 min, linked, Idle on firmware 1400, with the
    /// countdown started at `T0`.
    fn ready_core() -> CremaCore {
        let mut core = CremaCore::new();
        core.idle_sleep(IdleSleepInput::Configure { minutes: 60 }, T0);
        core.idle_sleep(IdleSleepInput::Link { ready: true }, T0);
        mmr_reply(&mut core, MmrRegister::FirmwareVersion, 1400);
        mmr_reply(&mut core, MmrRegister::MachineModel, 1);
        report(&mut core, MachineState::Idle, 0);
        assert!(tick(&mut core, T0).commands.is_empty());
        core
    }

    #[test]
    fn expiry_writes_one_sleep_and_says_so() {
        let mut core = ready_core();
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 59 * MIN)), 0);
        let out = tick(&mut core, T0 + 60 * MIN);
        assert_eq!(sleep_writes(&out), 1);
        assert!(
            out.events
                .contains(&Event::IdleSleepRequested { attempt: 1 })
        );
        report(&mut core, MachineState::Sleep, 0);
        for m in 61..400 {
            assert_eq!(sleep_writes(&tick(&mut core, T0 + m * MIN)), 0);
        }
    }

    #[test]
    fn activity_resets_and_pokes_presence_throttled() {
        let mut core = ready_core();
        let touch = IdleSleepInput::Activity {
            kind: IdleActivity::Touch,
        };
        let out = core.idle_sleep(touch, T0 + 50 * MIN);
        assert_eq!(mmr_writes(&out), vec![(MmrRegister::UserPresent, 1)]);
        assert!(
            core.idle_sleep(touch, T0 + 50 * MIN + 1_000)
                .commands
                .is_empty()
        );
        // The countdown runs from the last touch (+50 min + 1 s).
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 110 * MIN)), 0);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 111 * MIN)), 1);
    }

    #[test]
    fn a_tick_alone_never_pokes_presence() {
        let mut core = ready_core();
        for m in 0..59 {
            assert!(mmr_writes(&tick(&mut core, T0 + m * MIN)).is_empty());
        }
    }

    #[test]
    fn a_blocked_expiry_retries_until_the_refill_ends() {
        let mut core = ready_core();
        mmr_reply(&mut core, MmrRegister::FirmwareVersion, 1300);
        report(&mut core, MachineState::Refill, 17);
        let out = tick(&mut core, T0 + 60 * MIN);
        assert_eq!(sleep_writes(&out), 0);
        assert!(out.events.contains(&Event::IdleSleepBlocked {
            reason: IdleSleepBlock::RefillLatch,
            state: Some(MachineState::Refill),
        }));
        // Retried silently on every tick.
        let quiet = tick(&mut core, T0 + 60 * MIN + 30_000);
        assert!(quiet.events.is_empty() && quiet.commands.is_empty());
        report(&mut core, MachineState::Idle, 0);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 61 * MIN)), 1);
    }

    #[test]
    fn nothing_while_the_link_is_down() {
        let mut core = ready_core();
        core.de1_link_lost();
        for m in 60..200 {
            assert_eq!(sleep_writes(&tick(&mut core, T0 + m * MIN)), 0);
        }
        // A full reset (a disconnect) is link-down too.
        let mut core = ready_core();
        core.reset();
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 60 * MIN)), 0);
        // …and the timer survives it: once the link is back and the machine
        // reports Idle, the expired timer sends.
        core.idle_sleep(IdleSleepInput::Link { ready: true }, T0 + 61 * MIN);
        report(&mut core, MachineState::Idle, 0);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 61 * MIN)), 1);
    }

    #[test]
    fn shots_uploads_and_guided_brews_pin_the_countdown() {
        let mut core = ready_core();
        report(&mut core, MachineState::Espresso, 4);
        for m in 1..200 {
            assert_eq!(sleep_writes(&tick(&mut core, T0 + m * MIN)), 0);
        }
        // A guided brew armed at the scale — no machine state involved.
        let mut core = ready_core();
        let recipe = de1_domain::brew_builtin::builtin_brew_recipes()[0].clone();
        let json = serde_json::to_string(&recipe).unwrap();
        core.brew_session_arm(&json, false);
        assert!(core.idle_sleep_busy());
        for m in 1..200 {
            assert_eq!(sleep_writes(&tick(&mut core, T0 + m * MIN)), 0);
        }
    }

    #[test]
    fn an_app_sleep_is_not_doubled_by_the_timer() {
        let mut core = ready_core();
        // Sleep-on-quit or the power button, just before expiry.
        let out = core.request_machine_state(MachineState::Sleep);
        assert_eq!(sleep_writes(&out), 1);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 60 * MIN)), 0);
        report(&mut core, MachineState::Sleep, 0);
        for m in 61..300 {
            assert_eq!(sleep_writes(&tick(&mut core, T0 + m * MIN)), 0);
        }
    }

    #[test]
    fn a_requested_state_counts_as_activity() {
        let mut core = ready_core();
        core.request_machine_state(MachineState::Idle);
        tick(&mut core, T0 + 50 * MIN);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 109 * MIN)), 0);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 110 * MIN)), 1);
    }

    #[test]
    fn a_ghc_wake_restarts_the_timer() {
        let mut core = ready_core();
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 60 * MIN)), 1);
        report(&mut core, MachineState::Sleep, 0);
        report(&mut core, MachineState::Idle, 1);
        tick(&mut core, T0 + 200 * MIN);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 259 * MIN)), 0);
        assert_eq!(sleep_writes(&tick(&mut core, T0 + 260 * MIN)), 1);
    }

    #[test]
    fn never_is_off() {
        let mut core = ready_core();
        core.idle_sleep(IdleSleepInput::Configure { minutes: 0 }, T0);
        for m in 0..1000 {
            assert_eq!(sleep_writes(&tick(&mut core, T0 + m * MIN)), 0);
        }
    }

    #[test]
    fn a_mirror_never_runs_the_timer() {
        let mut core = ready_core();
        core.set_read_only(true);
        for m in 0..300 {
            let out = tick(&mut core, T0 + m * MIN);
            assert!(out.commands.is_empty() && out.events.is_empty());
        }
        let touch = IdleSleepInput::Activity {
            kind: IdleActivity::Mirror,
        };
        assert!(core.idle_sleep(touch, T0 + 301 * MIN).commands.is_empty());
    }

    #[test]
    fn a_bengle_gets_the_firmware_timeout_on_model_read_and_on_change() {
        let mut core = CremaCore::new();
        core.idle_sleep(IdleSleepInput::Configure { minutes: 60 }, T0);
        core.idle_sleep(IdleSleepInput::Link { ready: true }, T0);
        let out = mmr_reply(&mut core, MmrRegister::MachineModel, 128);
        assert_eq!(
            mmr_writes(&out),
            vec![(MmrRegister::InactivitySleepTimeout, 60)]
        );
        let out = core.idle_sleep(IdleSleepInput::Configure { minutes: 0 }, T0);
        assert_eq!(
            mmr_writes(&out),
            vec![(MmrRegister::InactivitySleepTimeout, 0)]
        );
        // Unchanged: nothing re-sent.
        let out = core.idle_sleep(IdleSleepInput::Configure { minutes: 0 }, T0);
        assert!(out.commands.is_empty());
    }

    #[test]
    fn a_classic_de1_never_gets_the_bengle_register() {
        let mut core = CremaCore::new();
        core.idle_sleep(IdleSleepInput::Configure { minutes: 60 }, T0);
        core.idle_sleep(IdleSleepInput::Link { ready: true }, T0);
        let out = mmr_reply(&mut core, MmrRegister::MachineModel, 2);
        assert!(mmr_writes(&out).is_empty());
        let out = core.idle_sleep(IdleSleepInput::Configure { minutes: 30 }, T0);
        assert!(out.commands.is_empty());
    }

    #[test]
    fn bad_json_is_a_decode_error() {
        let mut core = CremaCore::new();
        let out = core.idle_sleep_json("{nope", T0);
        assert!(matches!(out.events[0], Event::DecodeError { .. }));
        let out = core.idle_sleep_json(r#"{"type":"Configure","content":{"minutes":30}}"#, T0);
        assert!(out.events.is_empty());
    }

    #[test]
    fn a_reconnect_sends_no_wake() {
        // Decenza c7c03bad: a reconnect must never switch a sleeping machine
        // on. The connect sweep and the idle-sleep link input write no
        // RequestedState at all.
        let mut core = CremaCore::new();
        core.idle_sleep(IdleSleepInput::Configure { minutes: 60 }, T0);
        report(&mut core, MachineState::Sleep, 0);
        core.de1_link_lost();
        let mut all = core.idle_sleep(IdleSleepInput::Link { ready: true }, T0);
        let sweep = core.connect_sweep(&de1_domain::ConnectSweepSettings::default(), T0);
        all.commands.extend(sweep.commands);
        all.commands
            .extend(report(&mut core, MachineState::Sleep, 0).commands);
        all.commands
            .extend(tick(&mut core, T0 + 100 * MIN).commands);
        assert!(!all.commands.iter().any(|c| matches!(
            c,
            Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                ..
            }
        )));
    }

    #[test]
    fn a_firmware_flash_pins_the_idle_timer_and_it_restarts_on_release() {
        let mut core = CremaCore::new();
        report(&mut core, MachineState::Idle, 0);
        core.idle_sleep(IdleSleepInput::Configure { minutes: 15 }, T0);
        core.idle_sleep(IdleSleepInput::Link { ready: true }, T0);
        // A power-cycle wait holds the DE1 (as after a verify or a restore).
        core.firmware_flash_restore_awaiting(1358, false, 1_000);
        assert!(core.firmware_locks_writes());
        let mut now = T0;
        for _ in 0..80 {
            now += 30_000; // 40 min of ticks, well past 15
            let out = tick(&mut core, now);
            assert_eq!(sleep_writes(&out), 0, "no idle Sleep mid-flash");
            assert!(
                !out.events
                    .iter()
                    .any(|e| matches!(e, Event::FirmwareLockoutHit { .. })),
                "pinned, not refused"
            );
        }
        // Activity during the flash: no UserPresent poke.
        let poke = core.idle_sleep(
            IdleSleepInput::Activity {
                kind: IdleActivity::Touch,
            },
            now,
        );
        assert!(poke.commands.is_empty(), "no presence poke into a held DE1");
        // The lock releases (the user exits the power-cycle step).
        assert!(core.firmware_flash_exit());
        let out = tick(&mut core, now + 30_000);
        assert_eq!(
            sleep_writes(&out),
            0,
            "the countdown starts over at release"
        );
        let out = tick(&mut core, now + 14 * MIN);
        assert_eq!(sleep_writes(&out), 0);
        let out = tick(&mut core, now + 15 * MIN + 30_000);
        assert_eq!(sleep_writes(&out), 1, "normal behaviour after release");
    }
}
