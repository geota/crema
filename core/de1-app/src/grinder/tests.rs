//! The connected grinder through the core: identify, handshake, readings,
//! grind detection, link health, capture redaction.

use de1_grinder::bookoo_motto80::{self, encode};
use de1_grinder::{GrindEnd, GrinderLinkDeadReason, GrinderState, RunKind};

use super::*;

const NAME: &str = "BOOKOO MT80 1a2b3c4d";
const SERIAL: &str = "SN0000000MT12355";
const SSID: &str = "BK-WIFI";

fn period(state: &str, total: u32) -> String {
    format!(
        r#"{{"broadcast":{{"periodInfo":{{"feedingRpm":40,"bladeGap":480,"grindRpm":900,"humidity":30,"devState":"{state}","netState":"CONNECTED","totalGrinds":{total},"selectPreset":-1}}}}}}"#
    )
}

fn base() -> String {
    format!(
        r#"{{"broadcast":{{"baseInfo":{{"releaseVer":103111020,"snCode":"{SERIAL}","wifiName":"{SSID}"}}}}}}"#
    )
}

fn connected() -> CremaCore {
    let mut core = CremaCore::new();
    assert_eq!(
        core.connect_grinder(NAME, &[]).as_deref(),
        Some(bookoo_motto80::MODEL)
    );
    core.grinder_subscribed(20, 0);
    core
}

/// Feed one message as 20-byte notifications at `now`.
fn feed(core: &mut CremaCore, message: &str, now: u64) -> Vec<Event> {
    encode(message.as_bytes(), 1, 20)
        .iter()
        .flat_map(|f| core.on_notification(Source::GrinderNotify, f, now).events)
        .collect()
}

fn grinds(events: &[Event]) -> Vec<&GrindSession> {
    events
        .iter()
        .filter_map(|e| match e {
            Event::GrindCompleted { session } => Some(session),
            _ => None,
        })
        .collect()
}

#[test]
fn a_motto_is_a_grinder_and_a_bookoo_scale_is_a_scale() {
    let m = identify_device(NAME, &[]).expect("grinder");
    assert_eq!(
        (m.kind, m.model.as_str()),
        (DeviceKind::Grinder, "bookoo-motto-80")
    );
    let m = identify_device("bookoo mt80 00000000", &[]).expect("any case");
    assert_eq!(m.kind, DeviceKind::Grinder);
    let m = identify_device("BOOKOO_SC 1234", &[]).expect("scale");
    assert_eq!((m.kind, m.model.as_str()), (DeviceKind::Scale, "Bookoo"));
    // The service the Motto doesn't advertise still identifies it once found.
    let services = [bookoo_motto80::SERVICE.to_uppercase()];
    let m = identify_device("Phone", &services).expect("by service");
    assert_eq!(m.kind, DeviceKind::Grinder);
    assert!(identify_device("BOOKOO_EM 1234", &[]).is_none());
    assert!(identify_device("BOOKOO MT80", &[]).is_none());
}

#[test]
fn no_name_matches_two_kinds() {
    let scales = device_scan_filters(Some(DeviceKind::Scale));
    let grinders = device_scan_filters(Some(DeviceKind::Grinder));
    for prefix in &grinders.name_prefixes {
        for suffix in ["", "12345678", " x"] {
            let name = format!("{prefix}{suffix}");
            assert!(
                Scale::identify(&name, &[]).is_none(),
                "{name} is a scale too"
            );
        }
    }
    for prefix in &scales.name_prefixes {
        for suffix in ["", "12345678", " x"] {
            let name = format!("{prefix}{suffix}");
            assert!(
                GrinderDriver::identify(&name, &[]).is_none(),
                "{name} is a grinder too"
            );
        }
    }
    for service in &grinders.service_uuids {
        assert!(!scales.service_uuids.contains(service));
    }
}

#[test]
fn scan_filters_per_kind_and_together() {
    let grinders = device_scan_filters(Some(DeviceKind::Grinder));
    assert_eq!(grinders.name_prefixes, ["BOOKOO MT80 "]);
    assert_eq!(grinders.service_uuids, [bookoo_motto80::SERVICE]);
    let scales = device_scan_filters(Some(DeviceKind::Scale));
    assert_eq!(scales.name_prefixes, Scale::scan_uuids().name_prefixes);
    let all = device_scan_filters(None);
    assert_eq!(
        all.name_prefixes.len(),
        scales.name_prefixes.len() + grinders.name_prefixes.len()
    );
    assert!(all.service_uuids.contains(&bookoo_motto80::SERVICE));
}

#[test]
fn an_unknown_device_connects_nothing() {
    let mut core = CremaCore::new();
    assert_eq!(core.connect_grinder("Some Phone", &[]), None);
    assert!(core.grinder_uuids().is_none());
    assert!(core.grinder_capabilities().is_none());
    assert!(core.grinder_subscribed(20, 0).commands.is_empty());
    let out = core.on_notification(Source::GrinderNotify, &[0xA5, 1, 0, 0, 1, 0, 0, 0, b'x'], 0);
    assert!(out.events.is_empty());
    assert!(
        core.on_tick(60_000)
            .events
            .iter()
            .all(|e| !matches!(e, Event::GrinderLinkDead { .. }))
    );
}

#[test]
fn the_handshake_goes_out_as_grinder_writes() {
    let mut core = CremaCore::new();
    core.connect_grinder(NAME, &[]);
    let uuids = core.grinder_uuids().expect("uuids");
    assert_eq!(uuids.notify, bookoo_motto80::TX);
    assert_eq!(uuids.write, bookoo_motto80::RX);
    assert!(uuids.write_with_response);
    let caps = core.grinder_capabilities().expect("caps");
    assert!(caps.reports_state);
    assert_eq!(caps.commands, de1_grinder::GrinderCommands::default());
    let out = core.grinder_subscribed(20, 0);
    let writes: Vec<&[u8]> = out
        .commands
        .iter()
        .map(|c| match c {
            Command::WriteGrinder { data } => data.as_slice(),
            other => panic!("{other:?}"),
        })
        .collect();
    assert_eq!(writes.len(), 4);
    assert_eq!(
        writes[0][..8],
        [0xA5, 0x01, 0x01, 0x00, 0x2B, 0x00, 0x00, 0x00]
    );
    // At a negotiated MTU the handshake is one write.
    let mut core = CremaCore::new();
    core.connect_grinder(NAME, &[]);
    assert_eq!(core.grinder_subscribed(244, 0).commands.len(), 1);
}

#[test]
fn a_read_only_mirror_writes_nothing_to_the_grinder() {
    let mut core = CremaCore::new();
    core.set_read_only(true);
    core.connect_grinder(NAME, &[]);
    assert!(core.grinder_subscribed(20, 0).commands.is_empty());
}

#[test]
fn readings_flow_and_a_grind_is_detected() {
    let mut core = connected();
    let events = feed(&mut core, &base(), 100);
    assert!(events.is_empty(), "{events:?}");
    let events = feed(&mut core, &period("IDLE", 10), 300);
    let Event::GrinderReady { info } = &events[0] else {
        panic!("{events:?}");
    };
    assert_eq!(info.firmware.as_deref(), Some("1.3.11.1020"));
    assert_eq!(info.serial.as_deref(), Some(SERIAL));
    let Event::GrinderReading { reading } = &events[1] else {
        panic!("{events:?}");
    };
    assert_eq!(reading.state, GrinderState::Idle);
    let mut t = 500;
    while t <= 8_500 {
        assert!(grinds(&feed(&mut core, &period("GRINDING", 10), t)).is_empty());
        t += 200;
    }
    let events = feed(&mut core, &period("IDLE", 11), 8_700);
    let done = grinds(&events);
    assert_eq!(done.len(), 1);
    let s = done[0];
    assert_eq!(
        (s.kind, s.started_at, s.ended_at),
        (RunKind::Grind, 500, 8_700)
    );
    assert_eq!(s.setting.as_ref().map(|v| v.value.as_str()), Some("480"));
    assert_eq!(s.grind_rpm, Some(900));
    assert_eq!(s.counted, Some(true));
    assert_eq!(core.recent_grinds().len(), 1);
    // A purge is reported as a purge.
    feed(&mut core, &period("ADJUSTMENTPURGE", 11), 9_000);
    let events = feed(&mut core, &period("IDLE", 11), 11_000);
    assert_eq!(grinds(&events)[0].kind, RunKind::Purge);
    assert_eq!(core.recent_grinds().len(), 2);
}

#[test]
fn only_the_last_twenty_runs_are_kept() {
    let mut core = connected();
    let mut t = 0;
    for i in 0..25 {
        feed(&mut core, &period("GRINDING", i), t);
        feed(&mut core, &period("IDLE", i + 1), t + 1_000);
        t += 2_000;
    }
    assert_eq!(core.recent_grinds().len(), RECENT_GRINDS);
    assert_eq!(core.recent_grinds()[0].started_at, 5 * 2_000);
}

#[test]
fn silence_kills_the_link_and_a_drop_ends_the_grind() {
    let mut core = connected();
    feed(&mut core, &period("GRINDING", 3), 1_000);
    feed(&mut core, &period("GRINDING", 3), 3_000);
    assert!(
        !core
            .on_tick(7_999)
            .events
            .iter()
            .any(|e| matches!(e, Event::GrinderLinkDead { .. }))
    );
    let out = core.on_tick(8_000);
    assert!(out.events.contains(&Event::GrinderLinkDead {
        reason: GrinderLinkDeadReason::Silent
    }));
    let out = core.disconnect_grinder();
    let done = grinds(&out.events);
    assert_eq!(done.len(), 1);
    assert_eq!(
        (done[0].ended_at, done[0].ended_by),
        (3_000, GrindEnd::LinkLost)
    );
    assert!(core.grinder_uuids().is_none());
    assert!(core.disconnect_grinder().events.is_empty());
}

#[test]
fn no_first_reading_is_reported_on_the_tick() {
    let mut core = connected();
    assert!(core.on_tick(4_000).events.is_empty());
    assert!(
        core.on_tick(5_000)
            .events
            .contains(&Event::GrinderLinkDead {
                reason: GrinderLinkDeadReason::NoFirstReading
            })
    );
}

#[test]
fn old_firmware_is_unsupported() {
    let mut core = connected();
    let old = base().replace("103111020", "102040416");
    let events = feed(&mut core, &old, 100);
    assert_eq!(
        events,
        [Event::GrinderUnsupported {
            firmware: "1.2.4.0416".into(),
            minimum: "1.2.19.0820".into(),
        }]
    );
}

#[test]
fn a_de1_reset_keeps_the_grinder() {
    let mut core = connected();
    feed(&mut core, &period("IDLE", 1), 100);
    feed(&mut core, &period("GRINDING", 1), 300);
    core.reset();
    assert!(core.grinder_uuids().is_some());
    let events = feed(&mut core, &period("IDLE", 2), 2_000);
    assert_eq!(grinds(&events).len(), 1);
}

#[test]
fn the_capture_never_holds_the_serial_or_the_ssid() {
    let mut core = connected();
    feed(&mut core, &base(), 100);
    for i in 0..10 {
        feed(&mut core, &period("IDLE", 1), 200 + i * 200);
    }
    let jsonl = core.capture_slice_jsonl(0, 10_000);
    let lines: Vec<&str> = jsonl.lines().filter(|l| l.contains("GRINDER_TX")).collect();
    // The base info and two copies of an unchanged reading (once a second).
    assert_eq!(lines.len(), 3, "{jsonl}");
    let hex = |s: &str| s.bytes().map(|b| format!("{b:02x}")).collect::<String>();
    for secret in [SERIAL, SSID, "MT12355"] {
        assert!(!jsonl.contains(&hex(secret)), "{secret} leaked");
    }
    assert!(jsonl.contains(&hex("REDACTED")));
}

/// What `on_grinder_notification` hands a shell for its own capture file
/// (Android's) is exactly what the core recorded: redacted, re-serialized,
/// never the serial or the Wi-Fi name, and nothing on a read-only mirror.
#[test]
fn the_exported_capture_is_the_recorded_one_and_holds_no_secret() {
    let mut core = connected();
    let mut exported = Vec::new();
    let mut events = Vec::new();
    for (i, message) in [base(), period("IDLE", 1), period("IDLE", 1)]
        .iter()
        .enumerate()
    {
        for fragment in encode(message.as_bytes(), 1, 20) {
            let (out, frames) = core.on_grinder_notification(&fragment, 100 + i as u64 * 200);
            events.extend(out.events);
            exported.extend(frames);
        }
    }
    assert!(
        matches!(&events[0], Event::GrinderReady { .. }),
        "decoded as before: {events:?}"
    );
    // The base info and one reading: the repeat within a second is skipped.
    let recorded: Vec<String> = core
        .capture_slice_jsonl(0, 10_000)
        .lines()
        .filter(|l| l.contains("GRINDER_TX"))
        .map(str::to_owned)
        .collect();
    let hex = |b: &[u8]| b.iter().map(|x| format!("{x:02x}")).collect::<String>();
    assert_eq!(recorded.len(), exported.len());
    for (line, frame) in recorded.iter().zip(&exported) {
        assert!(
            line.contains(&format!("\"hex\":\"{}\"", hex(frame))),
            "{line}"
        );
    }
    let all: Vec<u8> = exported.concat();
    for secret in [SERIAL, SSID, "MT12355"] {
        assert!(
            !all.windows(secret.len()).any(|w| w == secret.as_bytes()),
            "{secret} exported"
        );
    }
    assert!(all.windows(8).any(|w| w == b"REDACTED"));

    // A read-only mirror records nothing, so it exports nothing.
    let mut mirror = connected();
    mirror.set_read_only(true);
    for fragment in encode(base().as_bytes(), 1, 20) {
        assert!(mirror.on_grinder_notification(&fragment, 100).1.is_empty());
    }
    assert!(!mirror.capture_slice_jsonl(0, 10_000).contains("GRINDER_TX"));
}

// ── Follow mode and the shot observation (G3) ─────────────────────────────

fn selections(grinder: &str, grind: Option<&str>) -> de1_domain::ShotSelections {
    de1_domain::ShotSelections {
        grinder_id: Some(grinder.into()),
        grind: grind.map(str::to_owned),
        ..de1_domain::ShotSelections::default()
    }
}

fn follow_grinds(events: &[Event]) -> Vec<(String, bool)> {
    events
        .iter()
        .filter_map(|e| match e {
            Event::FollowGrind { grind, announce } => Some((grind.clone(), *announce)),
            _ => None,
        })
        .collect()
}

fn gap(state: &str, gap: u32, total: u32) -> String {
    period(state, total).replace(r#""bladeGap":480"#, &format!(r#""bladeGap":{gap}"#))
}

/// Linked, active and steady: Brew's grind follows, once per value.
fn following() -> CremaCore {
    let mut core = connected();
    core.set_shot_selections(selections("grinder:motto", Some("500")));
    core.set_grinder_link(Some("grinder:motto".into()));
    core
}

#[test]
fn brews_grind_follows_a_steady_dial_once_per_value() {
    let mut core = following();
    let mut t = 100;
    let mut asked = Vec::new();
    for _ in 0..4 {
        asked.extend(follow_grinds(&feed(&mut core, &gap("IDLE", 480, 1), t)));
        t += 200;
    }
    assert!(asked.is_empty(), "not steady yet");
    assert_eq!(core.grinder_follow().status, FollowStatus::Waiting);
    asked.extend(follow_grinds(&feed(&mut core, &gap("IDLE", 480, 1), t)));
    assert_eq!(asked, [("480".to_owned(), false)]);
    assert_eq!(core.grinder_follow().status, FollowStatus::Following);
    assert_eq!(core.grinder_follow().grind.as_deref(), Some("480"));
    // Not repeated while the shell applies it…
    t += 200;
    assert!(follow_grinds(&feed(&mut core, &gap("IDLE", 480, 1), t)).is_empty());
    // …and once applied, nothing more.
    core.set_shot_selections(selections("grinder:motto", Some("480")));
    t += 200;
    assert!(follow_grinds(&feed(&mut core, &gap("IDLE", 480, 1), t)).is_empty());
    // A typed value loses to the collar (§5.4).
    core.set_shot_selections(selections("grinder:motto", Some("470")));
    assert_eq!(
        follow_grinds(&core.on_tick(t + 100).events),
        [("480".to_owned(), false)]
    );
    core.set_shot_selections(selections("grinder:motto", Some("480")));
    // Turning the collar: moving, then the new value once it holds.
    t += 200;
    feed(&mut core, &gap("IDLE", 485, 1), t);
    assert_eq!(core.grinder_follow().moving, Some(true));
    let mut asked = Vec::new();
    for _ in 0..5 {
        t += 200;
        asked.extend(follow_grinds(&feed(&mut core, &gap("IDLE", 490, 1), t)));
    }
    assert_eq!(asked, [("490".to_owned(), false)]);
}

#[test]
fn paused_or_another_active_grinder_never_moves_brews_grind() {
    let mut core = following();
    core.set_grinder_follow_paused(true);
    let mut t = 100;
    let mut events = Vec::new();
    for _ in 0..6 {
        events.extend(feed(&mut core, &gap("IDLE", 480, 1), t));
        t += 200;
    }
    assert!(follow_grinds(&events).is_empty());
    assert_eq!(core.grinder_follow().status, FollowStatus::Paused);
    assert_eq!(core.followed_grinder_id(), None);
    // Resuming takes the grinder's value.
    let out = core.set_grinder_follow_paused(false);
    assert_eq!(follow_grinds(&out.events), [("480".to_owned(), false)]);
    assert_eq!(core.followed_grinder_id().as_deref(), Some("grinder:motto"));
    // Another grinder active: only the chip (decision 4), never a switch.
    core.set_shot_selections(selections("grinder:niche", Some("14")));
    let out = core.on_tick(t);
    assert!(follow_grinds(&out.events).is_empty());
    let view = core.grinder_follow();
    assert_eq!(
        (view.status, view.grinder_id.as_deref()),
        (FollowStatus::Suggest, Some("grinder:motto"))
    );
    assert!(out.events.iter().any(|e| matches!(e, Event::GrinderFollowChanged { follow } if follow.status == FollowStatus::Suggest)));
    // Not linked: off.
    core.set_grinder_link(None);
    assert_eq!(core.grinder_follow(), GrinderFollowView::default());
}

#[test]
fn a_drop_goes_stale_and_a_reconnect_announces_a_changed_value() {
    let mut core = following();
    let mut t = 100;
    for _ in 0..5 {
        feed(&mut core, &gap("IDLE", 480, 1), t);
        t += 200;
    }
    core.set_shot_selections(selections("grinder:motto", Some("480")));
    core.disconnect_grinder();
    let view = core.grinder_follow();
    assert_eq!(
        (view.status, view.grind.as_deref()),
        (FollowStatus::Stale, Some("480"))
    );
    assert_eq!(core.followed_grinder_id(), None);
    // Back, with the collar moved meanwhile.
    core.connect_grinder(NAME, &[]);
    core.grinder_subscribed(20, t);
    let mut asked = Vec::new();
    for _ in 0..5 {
        t += 200;
        asked.extend(follow_grinds(&feed(&mut core, &gap("IDLE", 500, 1), t)));
    }
    assert_eq!(asked, [("500".to_owned(), true)]);
    // Unchanged on reconnect: nothing to say.
    core.set_shot_selections(selections("grinder:motto", Some("500")));
    core.disconnect_grinder();
    core.connect_grinder(NAME, &[]);
    core.grinder_subscribed(20, t);
    let mut asked = Vec::new();
    for _ in 0..5 {
        t += 200;
        asked.extend(follow_grinds(&feed(&mut core, &gap("IDLE", 500, 1), t)));
    }
    assert!(asked.is_empty());
}

#[test]
fn a_shot_gets_the_grind_before_it_once_then_the_reading() {
    let mut core = following();
    feed(&mut core, &gap("IDLE", 480, 10), 100);
    for i in 0..30 {
        feed(&mut core, &gap("GRINDING", 480, 10), 300 + i * 200);
    }
    feed(&mut core, &gap("IDLE", 480, 11), 6_300);
    // A purge after it is never the shot's (decision 9).
    feed(&mut core, &gap("ADJUSTMENTPURGE", 480, 11), 7_000);
    feed(&mut core, &gap("IDLE", 480, 11), 9_000);
    let start = 60_000;
    let o = observe(&mut core, start, Some("grinder:motto")).expect("an observation");
    assert_eq!(
        o.source,
        de1_domain::shot_grinder::ObservationSource::LastGrind
    );
    assert_eq!(o.setting.as_deref(), Some("480"));
    assert_eq!(o.grind_rpm, Some(900));
    assert_eq!(o.grind_ms, Some(6_000));
    assert_eq!(o.ended_before_shot_ms, Some(53_700));
    assert_eq!(o.preset, Some(-1));
    assert_eq!(o.model.as_deref(), Some("bookoo-motto-80"));
    // The next shot doesn't reuse it: the reading at its start.
    let o = observe(&mut core, start + 1_000, Some("grinder:motto")).expect("the reading");
    assert_eq!(
        o.source,
        de1_domain::shot_grinder::ObservationSource::AtShotStart
    );
    assert_eq!((o.grind_ms, o.ended_before_shot_ms), (None, None));
    // A shot on another grinder gets nothing from this one.
    assert_eq!(observe(&mut core, start, Some("grinder:niche")), None);
}

/// What a shot starting at `start` gets, claimed as its take would.
fn observe(
    core: &mut CremaCore,
    start: u64,
    grinder: Option<&str>,
) -> Option<de1_domain::shot_grinder::ShotGrinderObservation> {
    let (o, at) = core.grinder_observation_for_shot(start, grinder);
    if let Some(at) = at {
        core.claim_taken_grind(&format!("shot:{start}"), at);
    }
    o
}

/// A grind from `from` to `to` at 5 Hz, then idle.
fn grind(core: &mut CremaCore, from: u64, to: u64) {
    let mut t = from;
    while t < to {
        feed(core, &gap("GRINDING", 480, 10), t);
        t += 200;
    }
    feed(core, &gap("IDLE", 480, 11), to);
}

#[test]
fn a_grind_older_than_ten_minutes_is_not_the_shots() {
    let mut core = following();
    grind(&mut core, 100, 5_100);
    let late = 5_100 + de1_domain::shot_grinder::GRIND_ATTRIBUTION_WINDOW_MS + 1;
    let o = observe(&mut core, late, Some("grinder:motto")).expect("reading");
    assert_eq!(
        o.source,
        de1_domain::shot_grinder::ObservationSource::AtShotStart
    );
    // Dropped and gone: a grind before the drop still counts, nothing else.
    let mut core = following();
    grind(&mut core, 100, 5_100);
    core.disconnect_grinder();
    let o = observe(&mut core, 60_000, Some("grinder:motto")).expect("the grind");
    assert_eq!(
        o.source,
        de1_domain::shot_grinder::ObservationSource::LastGrind
    );
    assert_eq!(observe(&mut core, 61_000, Some("grinder:motto")), None);
}

/// Review #554 S2: a discarded shot gives its grind back; the discard's
/// Undo claims it again.
#[test]
fn a_discarded_shot_gives_its_grind_back() {
    let mut core = following();
    grind(&mut core, 100, 5_100);
    let first = observe(&mut core, 30_000, Some("grinder:motto")).expect("the grind");
    assert_eq!(
        first.source,
        de1_domain::shot_grinder::ObservationSource::LastGrind
    );
    // The aborted pull is discarded: the retry gets the grind.
    core.release_shot_grind("shot:30000");
    let retry = observe(&mut core, 40_000, Some("grinder:motto")).expect("the grind again");
    assert_eq!(
        retry.source,
        de1_domain::shot_grinder::ObservationSource::LastGrind
    );
    // Another shot's id changes nothing.
    core.release_shot_grind("shot:30000");
    let next = observe(&mut core, 50_000, Some("grinder:motto")).expect("the reading");
    assert_eq!(
        next.source,
        de1_domain::shot_grinder::ObservationSource::AtShotStart
    );
    // Undo of a discard claims it back.
    let mut core = following();
    grind(&mut core, 100, 5_100);
    observe(&mut core, 30_000, Some("grinder:motto"));
    core.release_shot_grind("shot:30000");
    core.claim_shot_grind("shot:30000");
    let after = observe(&mut core, 40_000, Some("grinder:motto")).expect("the reading");
    assert_eq!(
        after.source,
        de1_domain::shot_grinder::ObservationSource::AtShotStart
    );
}
