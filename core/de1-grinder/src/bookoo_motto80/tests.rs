//! The spec's §3.4 test vectors, plus the capture redaction.
//!
//! The `periodInfo` and `baseInfo` examples are BOOKOO's README §7.2–7.3
//! (MIT); the handshake frames are what the official `encode_fragments`
//! produces for sequence 1.

use super::*;
use crate::driver::GrinderDriver;

const PERIOD: &str = r#"{"broadcast":{"periodInfo":{"feedingRpm":65,"bladeGap":500,"grindRpm":700,"humidity":25,"devState":"IDLE","netState":"CONNECTED","totalGrinds":120,"cupDetect":true,"autoStop":true,"fastClean":true,"brightness":5,"standbySec":60,"selectPreset":-1}}}"#;
const BASE: &str = r#"{"broadcast":{"baseInfo":{"releaseVer":102040416,"snCode":"SN0000000MT12355","wifiName":"BK-WIFI"}}}"#;
const SERIAL: &str = "SN0000000MT12355";
const SSID: &str = "BK-WIFI";

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn period(dev_state: &str) -> String {
    PERIOD.replace(r#""IDLE""#, &format!("\"{dev_state}\""))
}

fn base(release: i64) -> String {
    BASE.replace("102040416", &release.to_string())
}

fn both(release: i64) -> String {
    format!(
        r#"{{"broadcast":{{"baseInfo":{{"releaseVer":{release},"snCode":"{SERIAL}","wifiName":"{SSID}"}},"periodInfo":{{"bladeGap":480,"grindRpm":900,"devState":"GRINDING"}}}}}}"#
    )
}

/// A driver that has sent its handshake at t = 0.
fn connected() -> GrinderDriver {
    let mut driver = GrinderDriver::from_model(MODEL).expect("motto");
    driver.connect_writes(SAFE_PACKET, 0);
    driver
}

fn feed(driver: &mut GrinderDriver, message: &str, packet: usize, now: u64) -> Vec<GrinderEvent> {
    encode(message.as_bytes(), 7, packet)
        .iter()
        .flat_map(|frame| driver.on_notification(frame, now))
        .collect()
}

fn readings(events: &[GrinderEvent]) -> Vec<&GrinderReading> {
    events
        .iter()
        .filter_map(|e| match e {
            GrinderEvent::Reading(r) => Some(r),
            _ => None,
        })
        .collect()
}

fn frame(seq: u16, total: u16, offset: u16, payload: &[u8]) -> Vec<u8> {
    let mut f = vec![0xA5, 0x01];
    f.extend_from_slice(&seq.to_le_bytes());
    f.extend_from_slice(&total.to_le_bytes());
    f.extend_from_slice(&offset.to_le_bytes());
    f.extend_from_slice(payload);
    f
}

// 1. Handshake at 20-byte packets, seq 1.
#[test]
fn the_handshake_is_the_official_four_frames() {
    let mut driver = GrinderDriver::from_model(MODEL).expect("motto");
    let frames: Vec<String> = driver
        .connect_writes(20, 0)
        .iter()
        .map(|f| hex(f))
        .collect();
    assert_eq!(
        frames,
        [
            "a50101002b0000007b2272657175657374223a7b",
            "a50101002b000c002261707048656c6c6f223a7b",
            "a50101002b001800226f70223a2268616e647368",
            "a50101002b002400616b65227d7d7d",
        ]
    );
    assert_eq!(HANDSHAKE.len(), 43);
    // A reconnect builds a new driver; a second handshake on one moves seq on.
    let again = driver.connect_writes(512, 0);
    assert_eq!(again.len(), 1);
    assert_eq!(&again[0][2..4], &2_u16.to_le_bytes());
}

#[test]
fn packet_sizes_are_clamped_to_9_through_512() {
    assert_eq!(encode(HANDSHAKE, 1, 0).len(), 43);
    assert!(encode(HANDSHAKE, 1, 0).iter().all(|f| f.len() == 9));
    assert_eq!(encode(&[b'x'; 4096], 1, 10_000).len(), 9);
    assert!(encode(&[], 1, 20).is_empty());
    assert!(encode(&[b'x'; 4097], 1, 20).is_empty());
}

// 2. The README periodInfo at 20, 182 and 512-byte packets.
#[test]
fn the_readme_period_info_decodes_at_every_packet_size() {
    assert_eq!(PERIOD.len(), 248);
    for packet in [20, 182, 512] {
        let mut driver = connected();
        let events = feed(&mut driver, PERIOD, packet, 1_000);
        assert!(
            matches!(&events[0], GrinderEvent::Ready { info } if info.model == MODEL && info.firmware.is_none()),
            "{events:?}"
        );
        let r = readings(&events);
        assert_eq!(r.len(), 1, "packet {packet}");
        let r = r[0];
        assert_eq!(r.at_ms, 1_000);
        assert_eq!(r.setting.as_ref().map(|s| s.value.as_str()), Some("500"));
        assert_eq!(r.setting.as_ref().and_then(|s| s.number), Some(500.0));
        assert_eq!(r.grind_rpm, Some(700));
        assert_eq!(r.feed_rpm, Some(65));
        assert_eq!(r.humidity_pct, Some(25));
        assert_eq!(r.state, GrinderState::Idle);
        assert_eq!(r.raw_state, "IDLE");
        assert_eq!(r.total_grinds, Some(120));
        assert_eq!(r.preset, Some(PresetSlot::Manual));
        assert_eq!(
            r.settings,
            GrinderSettings {
                cup_detect: Some(true),
                auto_stop: Some(true),
                fast_clean: Some(true),
                brightness: Some(5),
                standby_s: Some(60),
                wifi_connected: Some(true),
            }
        );
        assert_eq!(driver.diagnostics(), GrinderDiagnostics::default());
    }
}

#[test]
fn a_second_reading_is_not_ready_again() {
    let mut driver = connected();
    feed(&mut driver, PERIOD, 512, 100);
    let events = feed(&mut driver, PERIOD, 512, 300);
    assert!(matches!(events.as_slice(), [GrinderEvent::Reading(_)]));
}

#[test]
fn out_of_range_fields_are_left_out_not_the_reading() {
    let mut driver = connected();
    let odd = PERIOD
        .replace(r#""grindRpm":700"#, r#""grindRpm":0"#)
        .replace(r#""bladeGap":500"#, r#""bladeGap":1000"#)
        .replace(r#""humidity":25"#, r#""humidity":"25""#)
        .replace(r#""selectPreset":-1"#, r#""selectPreset":3"#)
        .replace(r#""feedingRpm":65"#, r#""feedingRpm":65.0"#);
    let events = feed(&mut driver, &odd, 512, 0);
    let r = readings(&events)[0];
    assert_eq!(r.grind_rpm, None);
    assert_eq!(r.setting, None);
    assert_eq!(r.humidity_pct, None);
    assert_eq!(r.feed_rpm, Some(65));
    assert_eq!(r.preset, Some(PresetSlot::Slot { index: 3 }));
    assert_eq!(driver.diagnostics().out_of_range_fields, 3);
}

// 3. The README baseInfo is v1.2.4.0416: too old.
#[test]
fn the_readme_base_info_is_firmware_too_old() {
    let mut driver = connected();
    let events = feed(&mut driver, BASE, 20, 0);
    assert_eq!(
        events,
        [GrinderEvent::Unsupported {
            reason: UnsupportedReason::FirmwareTooOld {
                found: "1.2.4.0416".into(),
                min: "1.2.19.0820".into(),
            }
        }]
    );
    // The driver says nothing more.
    assert!(feed(&mut driver, PERIOD, 512, 100).is_empty());
    assert!(driver.tick(60_000).is_empty());
}

// 4. baseInfo and periodInfo in one broadcast.
#[test]
fn base_and_period_info_in_one_broadcast() {
    let mut driver = connected();
    let events = feed(&mut driver, &both(MIN_RELEASE), 20, 400);
    let GrinderEvent::Ready { info } = &events[0] else {
        panic!("{events:?}");
    };
    assert_eq!(info.firmware.as_deref(), Some("1.2.19.0820"));
    assert_eq!(info.serial.as_deref(), Some(SERIAL));
    let r = readings(&events)[0];
    assert_eq!(r.state, GrinderState::Grinding);
    assert_eq!(r.setting.as_ref().map(|s| s.value.as_str()), Some("480"));
    assert_eq!((r.feed_rpm, r.total_grinds, r.preset), (None, None, None));
    // Old firmware in the same message: refused before the reading.
    let mut driver = connected();
    let events = feed(&mut driver, &both(MIN_RELEASE - 1), 20, 400);
    assert!(matches!(
        events.as_slice(),
        [GrinderEvent::Unsupported { .. }]
    ));
}

#[test]
fn base_info_after_ready_is_an_info_event() {
    let mut driver = connected();
    feed(&mut driver, PERIOD, 512, 0);
    let events = feed(&mut driver, &base(103_111_020), 512, 100);
    let [GrinderEvent::Info(info)] = events.as_slice() else {
        panic!("{events:?}");
    };
    assert_eq!(info.firmware.as_deref(), Some("1.3.11.1020"));
    assert_eq!(info.serial.as_deref(), Some(SERIAL));
    // The same again changes nothing.
    assert!(feed(&mut driver, &base(103_111_020), 512, 200).is_empty());
}

// 5. Legacy, unknown and empty states.
#[test]
fn states_match_case_insensitively_with_legacy_spellings() {
    for (raw, state) in [
        ("IDLE", GrinderState::Idle),
        ("Grinding", GrinderState::Grinding),
        ("HighSpeedClean", GrinderState::Purging),
        ("HIGHSPEEDCLEAN", GrinderState::Purging),
        ("HIGH_SPEED_CLEAN", GrinderState::Purging),
        ("ADJUSTMENTPURGE", GrinderState::Purging),
        ("Reverse", GrinderState::Purging),
        ("BootGuide", GrinderState::Busy),
        ("SETTING", GrinderState::Busy),
        ("SHAKING", GrinderState::Busy),
        ("UPDATING", GrinderState::Updating),
        ("WARNING", GrinderState::Warning),
        ("UNKNOWN", GrinderState::Unknown),
        ("FOO", GrinderState::Unknown),
        // Unlisted, odd or long: unknown, and the reading stands (§7.3.1).
        ("   ", GrinderState::Unknown),
        ("NEW_STATE_2027", GrinderState::Unknown),
    ] {
        let mut driver = connected();
        let events = feed(&mut driver, &period(raw), 512, 0);
        let r = readings(&events);
        assert_eq!(r.len(), 1, "{raw}");
        assert_eq!((r[0].state, r[0].raw_state.as_str()), (state, raw));
    }
}

/// Official §7.3.1: an unlisted `devState` must not fail the whole
/// `periodInfo`. A long one is kept truncated to 80 bytes, on a character.
#[test]
fn a_long_state_is_unknown_and_truncated_not_rejected() {
    let long = format!("{}é{}", "X".repeat(79), "Y".repeat(40));
    let mut driver = connected();
    let events = feed(&mut driver, &period(&long), 512, 0);
    let r = readings(&events);
    assert_eq!(r.len(), 1);
    assert_eq!(r[0].state, GrinderState::Unknown);
    assert_eq!(r[0].raw_state, "X".repeat(79), "cut before the two-byte é");
    assert_eq!(r[0].setting.as_ref().map(|s| s.value.as_str()), Some("500"));
    assert_eq!(driver.diagnostics().unreadable_messages, 0);
}

#[test]
fn an_empty_or_missing_state_is_not_a_reading() {
    let mut driver = connected();
    assert!(feed(&mut driver, &period(""), 512, 0).is_empty());
    let no_state = PERIOD.replace(r#""devState":"IDLE","#, "");
    assert!(feed(&mut driver, &no_state, 512, 0).is_empty());
    let numeric = PERIOD.replace(r#""IDLE""#, "1");
    assert!(feed(&mut driver, &numeric, 512, 0).is_empty());
    assert_eq!(driver.diagnostics().unreadable_messages, 3);
    // ...and it never became ready.
    assert_eq!(
        driver.tick(5_000),
        [GrinderEvent::LinkDead {
            reason: GrinderLinkDeadReason::NoFirstReading
        }]
    );
}

#[test]
fn notifications_before_the_handshake_are_ignored() {
    let mut driver = GrinderDriver::from_model(MODEL).expect("motto");
    assert!(feed(&mut driver, PERIOD, 512, 0).is_empty());
    assert!(driver.tick(60_000).is_empty());
}

// 6. Reassembly.
#[test]
fn reassembly_follows_the_official_rules() {
    let msg = PERIOD.as_bytes();
    let total = u16::try_from(msg.len()).expect("fits");
    let mut r = FrameReassembler::default();
    // Out-of-order offset.
    assert_eq!(r.feed(&frame(1, total, 0, &msg[..12]), 0), Ok(None));
    assert_eq!(
        r.feed(&frame(1, total, 24, &msg[24..36]), 0),
        Err(FrameError::OutOfOrder)
    );
    // ...and the partial is gone: a continuation now has nothing to follow.
    assert_eq!(
        r.feed(&frame(1, total, 12, &msg[12..24]), 0),
        Err(FrameError::Orphaned)
    );
    // A different seq or total mid-message.
    assert_eq!(r.feed(&frame(1, total, 0, &msg[..12]), 0), Ok(None));
    assert_eq!(
        r.feed(&frame(2, total, 12, &msg[12..24]), 0),
        Err(FrameError::OutOfOrder)
    );
    assert_eq!(r.feed(&frame(1, total, 0, &msg[..12]), 0), Ok(None));
    assert_eq!(
        r.feed(&frame(1, total + 1, 12, &msg[12..24]), 0),
        Err(FrameError::OutOfOrder)
    );
    // A gap over 2 s expires the partial; exactly 2 s does not.
    assert_eq!(r.feed(&frame(1, 24, 0, &msg[..12]), 0), Ok(None));
    assert_eq!(
        r.feed(&frame(1, 24, 12, &msg[12..24]), 2_001),
        Err(FrameError::Orphaned)
    );
    assert_eq!(r.feed(&frame(1, 24, 0, &msg[..12]), 0), Ok(None));
    assert_eq!(
        r.feed(&frame(1, 24, 12, &msg[12..24]), 2_000),
        Ok(Some(msg[..24].to_vec()))
    );
    // A new offset 0 mid-message starts over.
    assert_eq!(r.feed(&frame(1, 24, 0, b"AAAAAAAAAAAA"), 0), Ok(None));
    assert_eq!(
        r.feed(&frame(9, 3, 0, b"abc"), 0),
        Ok(Some(b"abc".to_vec()))
    );
    // Total length 0 and 4097.
    assert_eq!(r.feed(&frame(1, 0, 0, b"x"), 0), Err(FrameError::BadLength));
    assert_eq!(
        r.feed(&frame(1, 4097, 0, b"x"), 0),
        Err(FrameError::BadLength)
    );
    // Offset at or past the end; a fragment running past it.
    assert_eq!(
        r.feed(&frame(1, 3, 3, b"x"), 0),
        Err(FrameError::OutOfBounds)
    );
    assert_eq!(
        r.feed(&frame(1, 3, 0, b"abcd"), 0),
        Err(FrameError::OutOfBounds)
    );
    // An 8-byte frame, bad magic, bad version.
    assert_eq!(r.feed(&frame(1, 3, 0, b""), 0), Err(FrameError::NoPayload));
    assert_eq!(r.feed(&[0xA5], 0), Err(FrameError::NoPayload));
    let mut bad = frame(1, 1, 0, b"x");
    bad[0] = 0xA4;
    assert_eq!(r.feed(&bad, 0), Err(FrameError::BadMagicOrVersion));
    let mut bad = frame(1, 1, 0, b"x");
    bad[1] = 0x02;
    assert_eq!(r.feed(&bad, 0), Err(FrameError::BadMagicOrVersion));
}

#[test]
fn exactly_4096_bytes_reassemble() {
    let msg = vec![b'7'; 4096];
    let mut r = FrameReassembler::default();
    let frames = encode(&msg, 3, 20);
    assert_eq!(frames.len(), 342);
    let out: Vec<_> = frames
        .iter()
        .map(|f| r.feed(f, 0).expect("valid"))
        .collect();
    assert!(out[..341].iter().all(Option::is_none));
    assert_eq!(out[341].as_deref(), Some(msg.as_slice()));
}

#[test]
fn a_utf8_character_may_span_fragments_but_invalid_utf8_is_dropped() {
    // "€" is three bytes; at 9-byte packets every fragment carries one byte.
    let text = period("GRIND€");
    let mut driver = connected();
    let events = feed(&mut driver, &text, 9, 0);
    assert_eq!(readings(&events)[0].raw_state, "GRIND€");
    let mut bytes = PERIOD.as_bytes().to_vec();
    let at = PERIOD.find("IDLE").expect("state");
    bytes[at] = 0xFF;
    let mut driver = connected();
    let events: Vec<_> = encode(&bytes, 1, 20)
        .iter()
        .flat_map(|f| driver.on_notification(f, 0))
        .collect();
    assert!(events.is_empty());
    assert_eq!(driver.diagnostics().unreadable_messages, 1);
}

/// M1 (official §5.1): a lost notification drops its message, not the link.
/// The README `periodInfo` at 20-byte packets is 21 fragments; without
/// fragment 0 the other 20 are orphans, which don't count.
#[test]
fn a_lost_first_fragment_drops_the_message_not_the_link() {
    let mut driver = connected();
    feed(&mut driver, PERIOD, 512, 100);
    for round in 0..3_u64 {
        let frames = encode(PERIOD.as_bytes(), 7, 20);
        assert_eq!(frames.len(), 21);
        let at = 200 + round * 200;
        for f in &frames[1..] {
            assert!(driver.on_notification(f, at).is_empty(), "round {round}");
        }
        // The next message reads as normal.
        let events = feed(&mut driver, PERIOD, 20, at + 100);
        assert_eq!(readings(&events).len(), 1, "round {round}: {events:?}");
    }
    assert_eq!(driver.diagnostics().malformed_frames, 60);
}

/// Bad messages are what count: orphaned continuations neither add to the
/// run of bad frames nor end it.
#[test]
fn orphans_neither_count_nor_reset_the_run() {
    let mut driver = connected();
    feed(&mut driver, PERIOD, 512, 100);
    for _ in 0..19 {
        assert!(driver.on_notification(b"garbage!!", 200).is_empty());
    }
    for f in &encode(PERIOD.as_bytes(), 7, 20)[1..] {
        assert!(driver.on_notification(f, 200).is_empty());
    }
    assert_eq!(
        driver.on_notification(b"garbage!!", 200),
        [GrinderEvent::LinkDead {
            reason: GrinderLinkDeadReason::Malformed
        }]
    );
}

#[test]
fn twenty_malformed_frames_in_a_row_kill_the_link() {
    let mut driver = connected();
    for _ in 0..19 {
        assert!(driver.on_notification(&[0xA5, 0x01, 0, 0], 0).is_empty());
    }
    // A good frame in between starts the count over.
    feed(&mut driver, PERIOD, 512, 100);
    for _ in 0..19 {
        assert!(driver.on_notification(b"garbage!!", 200).is_empty());
    }
    assert_eq!(
        driver.on_notification(b"garbage!!", 200),
        [GrinderEvent::LinkDead {
            reason: GrinderLinkDeadReason::Malformed
        }]
    );
    assert!(feed(&mut driver, PERIOD, 512, 300).is_empty());
    assert_eq!(driver.diagnostics().malformed_frames, 39);
}

#[test]
fn responses_and_other_messages_are_ignored() {
    let mut driver = connected();
    for msg in [
        r#"{"response":{"geneSetting":{"result":"success","data":{}}}}"#,
        r#"{"broadcast":{"grindPreset":{}}}"#,
        "[1,2,3]",
        r#""text""#,
    ] {
        assert!(feed(&mut driver, msg, 20, 0).is_empty(), "{msg}");
    }
}

// 7. Readiness and silence.
#[test]
fn no_reading_by_5_s_is_no_first_reading() {
    let mut driver = connected();
    assert!(driver.tick(4_999).is_empty());
    assert_eq!(
        driver.tick(5_000),
        [GrinderEvent::LinkDead {
            reason: GrinderLinkDeadReason::NoFirstReading
        }]
    );
    assert!(driver.tick(6_000).is_empty());
    // A reading that turns up late finds the deadline passed.
    let mut driver = connected();
    let events = feed(&mut driver, PERIOD, 512, 5_100);
    assert_eq!(
        events,
        [GrinderEvent::LinkDead {
            reason: GrinderLinkDeadReason::NoFirstReading
        }]
    );
}

#[test]
fn a_reading_at_4_9_s_is_ready_and_5_s_of_silence_after_is_silent() {
    let mut driver = connected();
    let events = feed(&mut driver, PERIOD, 512, 4_900);
    assert!(matches!(events[0], GrinderEvent::Ready { .. }));
    assert!(driver.tick(9_899).is_empty());
    // Malformed frames are not a sign of life.
    assert!(driver.on_notification(b"garbage!!", 9_000).is_empty());
    assert_eq!(
        driver.tick(9_900),
        [GrinderEvent::LinkDead {
            reason: GrinderLinkDeadReason::Silent
        }]
    );
    assert!(driver.tick(20_000).is_empty());
}

// 8. Firmware decode.
#[test]
fn firmware_releases_decode() {
    let show = |v: i64| firmware_from_release(v).map(|f| f.to_string());
    assert_eq!(show(102_190_820).as_deref(), Some("1.2.19.0820"));
    assert_eq!(show(103_111_020).as_deref(), Some("1.3.11.1020"));
    assert_eq!(show(102_040_416).as_deref(), Some("1.2.4.0416"));
    assert_eq!(show(0).as_deref(), Some("0.0.0.0000"));
    assert_eq!(show(-1), None);
    assert_eq!(show(2_147_483_648), None);
    // 0 is a version, and too old; a negative one is no version at all, so
    // readings carry on with the firmware unknown.
    let mut driver = connected();
    assert!(matches!(
        feed(&mut driver, &base(0), 512, 0).as_slice(),
        [GrinderEvent::Unsupported { .. }]
    ));
    let mut driver = connected();
    assert!(feed(&mut driver, &base(-5), 512, 0).is_empty());
    let events = feed(&mut driver, PERIOD, 512, 100);
    assert!(
        matches!(&events[0], GrinderEvent::Ready { info } if info.firmware.is_none() && info.serial.as_deref() == Some(SERIAL))
    );
}

// The capture file never holds the serial or the SSID.
fn captured(driver: &mut GrinderDriver, frames: &[Vec<u8>], now: u64) -> Vec<Vec<u8>> {
    frames
        .iter()
        .flat_map(|f| driver.redact_for_capture(f, now))
        .collect()
}

fn leaks(frames: &[Vec<u8>]) -> bool {
    let all: Vec<u8> = frames.concat();
    [SERIAL, SSID, "MT12355"]
        .iter()
        .any(|s| all.windows(s.len()).any(|w| w == s.as_bytes()))
}

fn reassembled(frames: &[Vec<u8>]) -> Vec<Value> {
    let mut r = FrameReassembler::default();
    frames
        .iter()
        .filter_map(|f| r.feed(f, 0).ok().flatten())
        .map(|m| serde_json::from_slice(&m).expect("json"))
        .collect()
}

#[test]
fn a_captured_base_info_is_redacted_and_still_replays() {
    let mut driver = connected();
    for message in [BASE.to_owned(), both(MIN_RELEASE)] {
        for packet in [20, 182, 512] {
            let out = captured(&mut driver, &encode(message.as_bytes(), 5, packet), 0);
            assert!(!leaks(&out), "{message} at {packet}");
            let docs = reassembled(&out);
            assert_eq!(docs.len(), 1);
            let base = &docs[0]["broadcast"]["baseInfo"];
            assert_eq!(base["snCode"], "REDACTED");
            assert_eq!(base["wifiName"], Value::Null);
            assert!(base["releaseVer"].as_i64().is_some());
            // A replayed capture decodes like the live stream.
            let mut replay = connected();
            let events: Vec<_> = out
                .iter()
                .flat_map(|f| replay.on_notification(f, 10))
                .collect();
            if message.contains("periodInfo") {
                assert!(
                    matches!(&events[0], GrinderEvent::Ready { info } if info.serial.as_deref() == Some("REDACTED"))
                );
            }
        }
    }
}

#[test]
fn private_fields_are_redacted_wherever_they_appear() {
    let mut driver = connected();
    let odd = format!(r#"{{"response":{{"x":[{{"snCode":"{SERIAL}"}}],"wifiName":"{SSID}"}}}}"#);
    let out = captured(&mut driver, &encode(odd.as_bytes(), 1, 20), 0);
    assert!(!leaks(&out));
    assert_eq!(reassembled(&out).len(), 1);
}

#[test]
fn fragments_of_an_unfinished_or_broken_base_info_are_zeroed() {
    let mut driver = connected();
    let frames = encode(BASE.as_bytes(), 5, 20);
    // Superseded by a new message before it completes.
    let mut stream = frames[..5].to_vec();
    stream.extend(encode(PERIOD.as_bytes(), 6, 512));
    // A stray continuation with the serial in it.
    stream.push(frames[6].clone());
    // Expired mid-message.
    let mut out = captured(&mut driver, &stream, 0);
    out.extend(captured(&mut driver, &frames[..7], 1_000));
    out.extend(captured(&mut driver, &frames[7..], 4_000));
    assert!(!leaks(&out));
    let zeroed = out
        .iter()
        .filter(|f| f[HEADER_LEN..].iter().all(|b| *b == 0))
        .count();
    assert_eq!(zeroed, 5 + 1 + 7 + frames[7..].len());
    // Unreadable once complete: invalid UTF-8 inside a base info.
    let mut bytes = BASE.as_bytes().to_vec();
    bytes[3] = 0xFF;
    let out = captured(&mut driver, &encode(&bytes, 1, 20), 5_000);
    assert!(!leaks(&out));
    assert!(out.iter().all(|f| f[HEADER_LEN..].iter().all(|b| *b == 0)));
}

#[test]
fn a_repeated_period_info_is_captured_once_a_second() {
    let mut driver = connected();
    let frames = encode(PERIOD.as_bytes(), 2, 20);
    assert_eq!(frames.len(), 21);
    let first = captured(&mut driver, &frames, 0);
    // One frame per 512 bytes, not 21 notifications.
    assert_eq!(first.len(), 1);
    let recorded: Value = serde_json::from_slice(&first[0][HEADER_LEN..]).expect("json");
    assert_eq!(
        recorded,
        serde_json::from_str::<Value>(PERIOD).expect("json")
    );
    assert!(captured(&mut driver, &frames, 200).is_empty());
    assert!(captured(&mut driver, &frames, 999).is_empty());
    assert_eq!(captured(&mut driver, &frames, 1_000).len(), 1);
    // A change is captured at once.
    let grinding = encode(period("GRINDING").as_bytes(), 3, 20);
    assert_eq!(captured(&mut driver, &grinding, 1_100).len(), 1);
    assert_eq!(captured(&mut driver, &frames, 1_200).len(), 1);
}

#[test]
fn extreme_numbers_are_out_of_range_not_a_panic() {
    for bad in [
        "-9223372036854775808",
        "9223372036854775807",
        "18446744073709551615",
        "-9.3e18",
        "9.3e18",
        "-1e308",
        "9007199254740992",
        "-9007199254740992.0",
    ] {
        let message = PERIOD
            .replace(r#""bladeGap":500"#, &format!(r#""bladeGap":{bad}"#))
            .replace(r#""totalGrinds":120"#, &format!(r#""totalGrinds":{bad}"#));
        let mut driver = connected();
        let r = readings(&feed(&mut driver, &message, 512, 0))[0].clone();
        assert_eq!((r.setting, r.total_grinds), (None, None), "{bad}");
        let mut driver = connected();
        let events = feed(
            &mut driver,
            &base(0).replace(":0,", &format!(":{bad},")),
            512,
            0,
        );
        assert!(events.is_empty(), "{bad}: {events:?}");
    }
    // serde_json refuses a number past f64: the message is unreadable, no panic.
    let mut driver = connected();
    let huge = PERIOD.replace(r#""bladeGap":500"#, r#""bladeGap":1e400"#);
    assert!(feed(&mut driver, &huge, 512, 0).is_empty());
    assert_eq!(driver.diagnostics().unreadable_messages, 1);
    assert_eq!(
        integer(&serde_json::json!(9_007_199_254_740_991_i64)),
        Some(MAX_SAFE_INTEGER)
    );
    assert_eq!(
        integer(&serde_json::json!(-9_007_199_254_740_991.0)),
        Some(-MAX_SAFE_INTEGER)
    );
}

#[test]
fn a_duplicate_key_cannot_smuggle_a_serial_into_the_capture() {
    let mut driver = connected();
    let probe = format!(
        r#"{{"broadcast":{{"baseInfo":{{"snCode":"{SERIAL}","wifiName":"{SSID}"}}}},"broadcast":{{"periodInfo":{{"devState":"IDLE"}}}}}}"#
    );
    for packet in [20, 512] {
        let out = captured(
            &mut driver,
            &encode(probe.as_bytes(), 1, packet),
            u64::try_from(packet).unwrap_or(0) * 10_000,
        );
        assert!(!leaks(&out), "packet {packet}");
        assert_eq!(reassembled(&out).len(), 1);
    }
    let nested = format!(
        r#"{{"broadcast":{{"baseInfo":{{"snCode":"{SERIAL}","snCode":"x","wifiName":"{SSID}","wifiName":null}}}}}}"#
    );
    let out = captured(&mut driver, &encode(nested.as_bytes(), 1, 20), 50_000);
    assert!(!leaks(&out));
}
