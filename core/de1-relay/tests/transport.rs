//! AC-12 (record tampering) and AC-14 (rekeying).

mod common;

use common::*;
use de1_relay::{
    HubConnection, Phase, RelayEvent, RemoteConnection, WsIn, test_support::test_seed, ws_close,
};

/// Two records from the remote, not yet delivered.
fn two_records(link: &mut Link) -> (Vec<u8>, Vec<u8>) {
    let a = link.remote.send(b"first", T0).unwrap().send.remove(0).bytes;
    let b = link
        .remote
        .send(b"second", T0)
        .unwrap()
        .send
        .remove(0)
        .bytes;
    (a, b)
}

fn assert_decrypt_failure(link: &mut Link, step: de1_relay::Step) {
    assert_eq!(step.close.as_ref().map(|c| c.code), Some(ws_close::AUTH));
    assert!(step.events.contains(&RelayEvent::Failed {
        error: "Decrypt".into()
    }));
    assert_eq!(link.hub.phase(), Phase::Failed);
    // Nothing after it is delivered.
    let after = link.remote.send(b"later", T0).unwrap().send.remove(0).bytes;
    let s = link.hub.on_ws(WsIn::Binary(&after), T0);
    assert!(s.is_empty());
}

#[test]
fn duplicate_closes() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let (a, _) = two_records(&mut link);
    let s = link.hub.on_ws(WsIn::Binary(&a), T0);
    assert_eq!(s.deliver, vec![b"first".to_vec()]);
    let s = link.hub.on_ws(WsIn::Binary(&a), T0);
    assert!(s.deliver.is_empty());
    assert_decrypt_failure(&mut link, s);
}

#[test]
fn swap_closes() {
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let (a, b) = two_records(&mut link);
    let s = link.hub.on_ws(WsIn::Binary(&b), T0);
    assert!(s.deliver.is_empty());
    assert_decrypt_failure(&mut link, s);
    let s = link.hub.on_ws(WsIn::Binary(&a), T0);
    assert!(s.is_empty());
}

#[test]
fn bitflip_closes() {
    for bit in [8, 8 * 2 + 3, 8 * 10 + 7] {
        let mut link = established(&remote_keys(), &hub_keys(), 1);
        let (mut a, _) = two_records(&mut link);
        let i = (bit / 8) % a.len();
        a[i.max(1)] ^= 1 << (bit % 8);
        let s = link.hub.on_ws(WsIn::Binary(&a), T0);
        assert!(s.deliver.is_empty());
        assert_decrypt_failure(&mut link, s);
    }
}

#[test]
fn truncation_closes() {
    // A truncated record.
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let (mut a, _) = two_records(&mut link);
    a.truncate(a.len() - 1);
    let s = link.hub.on_ws(WsIn::Binary(&a), T0);
    assert_decrypt_failure(&mut link, s);
    // A record dropped from the stream: the next one fails.
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let (_, b) = two_records(&mut link);
    let s = link.hub.on_ws(WsIn::Binary(&b), T0);
    assert_decrypt_failure(&mut link, s);
    // Shorter than a tag.
    let mut link = established(&remote_keys(), &hub_keys(), 1);
    let s = link
        .hub
        .on_ws(WsIn::Binary(&[de1_relay::kind::RECORD, 1, 2, 3]), T0);
    assert_decrypt_failure(&mut link, s);
}

#[test]
fn cross_session_closes() {
    let (r, h) = (remote_keys(), hub_keys());
    let mut a = established(&r, &h, 1);
    let mut b = established(&r, &h, 2);
    let (rec, _) = two_records(&mut a);
    let s = b.hub.on_ws(WsIn::Binary(&rec), T0);
    assert!(s.deliver.is_empty());
    assert_decrypt_failure(&mut b, s);
}

fn rekeying_link(hub_interval: u64, remote_interval: u64) -> Link {
    let (r, h) = (remote_keys(), hub_keys());
    let pins = new_pins(vec![r.public()]);
    let mut hub = HubConnection::new(
        &h,
        new_gate(),
        pins.clone(),
        "Hub",
        test_seed("hub-conn", 2),
        T0,
    );
    hub.set_rekey_interval_for_tests(hub_interval);
    let (mut remote, first) =
        RemoteConnection::resume(&r, h.public(), test_seed("remote-conn", 2), T0);
    remote.set_rekey_interval_for_tests(remote_interval);
    let mut link = Link::with(hub, remote, first, new_gate(), pins, T0);
    link.pump();
    assert_eq!(link.hub.phase(), Phase::Established);
    link
}

/// Rekey on both sides every 4 records: traffic continues across several
/// rekeys in both directions; a peer that skips the rekey fails at the next record.
#[test]
fn rekeys_on_schedule() {
    let mut link = rekeying_link(4, 4);
    for i in 0..13_u32 {
        link.hub_send(format!("h{i}").as_bytes());
        link.remote_send(format!("r{i}").as_bytes());
    }
    let expect = |p: &str| {
        (0..13)
            .map(|i| format!("{p}{i}").into_bytes())
            .collect::<Vec<_>>()
    };
    assert_eq!(link.remote_delivered(), expect("h"));
    assert_eq!(link.hub_delivered(), expect("r"));
    // A 200 000-byte message spans 4 records, across a rekey boundary.
    let big = vec![9_u8; 200_000];
    link.hub_send(&big);
    assert_eq!(link.remote_delivered().last().unwrap(), &big);

    // The remote never rekeys its receiving key: records 0..3 decrypt, record 4 fails.
    let mut link = rekeying_link(4, 1_000_000);
    for i in 0..4_u32 {
        link.hub_send(format!("m{i}").as_bytes());
    }
    assert_eq!(link.remote_delivered().len(), 4);
    assert_eq!(link.remote.phase(), Phase::Established);
    link.hub_send(b"after the rekey");
    assert_eq!(link.remote_delivered().len(), 4);
    assert_eq!(link.remote_close(), Some(ws_close::AUTH));
    assert_eq!(link.remote.phase(), Phase::Failed);
}
