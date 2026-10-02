//! Half Decent Scale SoftSleep wired through the DE1 sleep / wake policy
//! (decaid `f6c91efe`, `38f5c0a8`, `46e8c224`).

use de1_scale::decent_hds::{SOFT_SLEEP_ENTER, SOFT_SLEEP_EXIT, VOLTAGE_PROBE};
use de1_scale::decent_scale::{LCD_DISABLE, LCD_ENABLE_GRAMS, POWER_OFF};

use crate::{Command, CoreOutput, CremaCore, Event, Source};

/// A 7-byte `0x0A` status reply with the BCD firmware major in byte 5.
fn status(major_bcd: u8) -> [u8; 7] {
    [0x03, 0x0A, 0x01, 0x01, 0x50, major_bcd, 0x1E]
}

const VOLTAGE_REPLY: [u8; 7] = [0x03, 0x22, 0x00, 0x29, 0x00, 0x00, 0x08];

fn scale_writes(out: &CoreOutput) -> Vec<Vec<u8>> {
    out.commands
        .iter()
        .filter_map(|c| match c {
            Command::WriteScale { data } => Some(data.clone()),
            _ => None,
        })
        .collect()
}

/// A core with a Decent Scale connected and negotiated from `major_bcd`,
/// the DE1 idle.
fn negotiated(major_bcd: u8, voltage: bool) -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("Decent Scale", &[]);
    let _ = core.on_notification(Source::De1State, &[2, 0], 0);
    let out = core.on_notification(Source::ScaleWeight, &status(major_bcd), 10);
    assert_eq!(
        scale_writes(&out),
        vec![VOLTAGE_PROBE.to_vec()],
        "probe once"
    );
    if voltage {
        let _ = core.on_notification(Source::ScaleWeight, &VOLTAGE_REPLY, 20);
    }
    core
}

#[test]
fn firmware_3_hds_negotiates_and_wakes_once() {
    let mut core = CremaCore::new();
    core.connect_scale("Decent Scale", &[]);
    let out = core.on_notification(Source::ScaleWeight, &status(0x03), 10);
    assert_eq!(scale_writes(&out), vec![VOLTAGE_PROBE.to_vec()]);
    let out = core.on_notification(Source::ScaleWeight, &VOLTAGE_REPLY, 20);
    assert_eq!(scale_writes(&out), vec![SOFT_SLEEP_EXIT.to_vec()]);
    // Later status replies (heartbeat acks) neither re-probe nor re-wake.
    let out = core.on_notification(Source::ScaleWeight, &status(0x03), 30);
    assert!(scale_writes(&out).is_empty());
}

#[test]
fn de1_sleep_soft_sleeps_an_hds_instead_of_lcd_off_or_power_off() {
    let mut core = negotiated(0x03, true);
    core.set_auto_off_scale_on_sleep(true);
    let out = core.on_notification(Source::De1State, &[0, 0], 1_000);
    let writes = scale_writes(&out);
    assert_eq!(writes, vec![SOFT_SLEEP_ENTER.to_vec()]);
    assert!(!writes.contains(&LCD_DISABLE.to_vec()));
    assert!(!writes.contains(&POWER_OFF.to_vec()));
    // No heartbeat pokes it awake, and its silence is not a dead link.
    assert!(core.scale_heartbeat().unwrap().commands.is_empty());
    let out = core.on_tick(600_000);
    assert!(!out.events.iter().any(|e| matches!(e, Event::ScaleStale)));
    // The DE1 waking wakes the scale: 0A 04 00, then the display.
    let out = core.on_notification(Source::De1State, &[2, 0], 601_000);
    assert_eq!(
        scale_writes(&out),
        vec![SOFT_SLEEP_EXIT.to_vec(), LCD_ENABLE_GRAMS.to_vec()]
    );
    assert!(!core.scale_heartbeat().unwrap().commands.is_empty());
}

#[test]
fn pre_3_hds_keeps_lcd_off_and_the_opt_in_power_off() {
    let mut core = negotiated(0x02, true);
    core.set_auto_off_scale_on_sleep(true);
    let out = core.on_notification(Source::De1State, &[0, 0], 1_000);
    assert_eq!(
        scale_writes(&out),
        vec![LCD_DISABLE.to_vec(), POWER_OFF.to_vec()]
    );
}

#[test]
fn an_unproven_scale_gets_no_soft_sleep() {
    // Firmware byte reads as 3 but the voltage probe was never answered —
    // an original v1.2 scale, whose marker is 0x03.
    let mut core = negotiated(0x03, false);
    let out = core.on_notification(Source::De1State, &[0, 0], 1_000);
    assert_eq!(scale_writes(&out), vec![LCD_DISABLE.to_vec()]);
}

#[test]
fn soft_sleep_state_survives_a_de1_reset_and_clears_on_scale_disconnect() {
    let mut core = negotiated(0x03, true);
    let _ = core.on_notification(Source::De1State, &[0, 0], 1_000);
    let _ = core.reset();
    assert!(core.scale_heartbeat().unwrap().commands.is_empty());
    core.disconnect_scale();
    core.connect_scale("Decent Scale", &[]);
    assert!(!core.scale_heartbeat().unwrap().commands.is_empty());
}
