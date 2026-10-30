//! The DE1's fixed GATT layout, exposed as **data**.
//!
//! The DE1 is a single fixed device with one GATT layout. Unlike the
//! multi-vendor scales — whose UUIDs the core reports per *identified* model
//! ([`crate::ScaleId::uuids`]) because the shell can't know them until the core
//! identifies the device — the DE1's UUIDs could live shell-side. They live
//! here instead so the web + Android shells share **one** map rather than each
//! hardcoding the same dozen constants (the cross-shell-drift trap).
//!
//! This stays **sans-IO**: the core never performs BLE. It only *describes* the
//! layout (pure data + a `WriteTarget` → UUID lookup); the shell does the GATT
//! I/O against these UUIDs. Symmetric with the scale side, just without the
//! per-model identification step.

use crate::event::{Source, WriteTarget};
use de1_protocol::MmrRegister;

/// Expand a 16-bit DE1 short into the full lowercase Bluetooth-SIG 128-bit UUID
/// string. Web Bluetooth wants lowercase; `java.util.UUID.fromString` accepts it.
fn short(hex: &str) -> String {
    format!("0000{hex}-0000-1000-8000-00805f9b34fb")
}

/// The DE1's GATT service + characteristic UUIDs (full lowercase 128-bit
/// strings). The shells scan on [`service`](Self::service), subscribe to the
/// notify characteristics, and address writes via [`de1_write_target_uuid`].
#[typeshare::typeshare]
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct De1Uuids {
    /// GATT service (`A000`) — the scan filter.
    pub service: String,
    /// `A001` Version — the BLE + firmware version block (read).
    pub version: String,
    /// `A002` RequestedState — a 1-byte state request is written here.
    pub requested_state: String,
    /// `A00B` ShotSettings — steam / hot-water settings (notify + write).
    pub shot_settings: String,
    /// `A005` ReadFromMMR — a read request is written here; the DE1 answers on
    /// the same characteristic's notify.
    pub mmr_read: String,
    /// `A006` WriteToMMR — an MMR write packet is sent here.
    pub mmr_write: String,
    /// `A00D` ShotSample — the ~4–10 Hz telemetry notify stream.
    pub shot_sample: String,
    /// `A00E` StateInfo — the 2-byte machine state + substate notify.
    pub state_info: String,
    /// `A011` WaterLevels — the tank-level notify (also written to set the
    /// refill threshold).
    pub water_levels: String,
    /// `A012` Calibration — a read request is written here; the DE1 answers on
    /// the same characteristic's notify.
    pub calibration: String,
    /// `A00F` HeaderWrite — the 5-byte profile-upload header (write).
    pub header_write: String,
    /// `A010` FrameWrite — profile frame writes. The DE1 sends NO notification
    /// here (it only ACKs the ATT write) — the shell synthesizes a frame-ack.
    pub frame_write: String,
    /// `A009` FWMapRequest — firmware erase / verify requests (write) and the
    /// DE1's answers (notify + read). Used only by a firmware flash.
    pub fw_map_request: String,
}

/// The DE1's fixed GATT layout. Pure data — safe to call anytime, with or
/// without a connection.
#[must_use]
pub fn de1_uuids() -> De1Uuids {
    De1Uuids {
        service: short("a000"),
        version: short("a001"),
        requested_state: short("a002"),
        shot_settings: short("a00b"),
        mmr_read: short("a005"),
        mmr_write: short("a006"),
        shot_sample: short("a00d"),
        state_info: short("a00e"),
        water_levels: short("a011"),
        calibration: short("a012"),
        header_write: short("a00f"),
        frame_write: short("a010"),
        fw_map_request: short("a009"),
    }
}

/// [`de1_uuids`] as a JSON string — the FFI / wasm boundary shape (the shells
/// parse it into their own [`De1Uuids`] mirror, exactly like `scaleUuids`).
#[must_use]
pub fn de1_uuids_json() -> String {
    serde_json::to_string(&de1_uuids()).unwrap_or_else(|_| "{}".to_string())
}

/// One DE1 notify characteristic the shells subscribe to at connect.
#[typeshare::typeshare]
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct De1Subscription {
    /// Full lowercase 128-bit characteristic UUID.
    pub characteristic: String,
    /// Characteristic name for status lines (`StateInfo`).
    pub name: String,
    /// Its 16-bit short, upper case (`A00E`).
    pub short: String,
    /// Whether a failed subscribe fails the connect. The required ones are
    /// the streams the app can't run without, and together the structural
    /// proof that the device is a DE1: a link that lacks one must never sit
    /// ready with dead notifications.
    pub required: bool,
}

/// One connect-time seed read: a characteristic the DE1 answers on a direct
/// read but does not notify at connect, so subscribing alone leaves its rows
/// blank.
#[typeshare::typeshare]
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct De1SeedRead {
    /// Full lowercase 128-bit characteristic UUID.
    pub characteristic: String,
    /// Label for status lines (`machine state`).
    pub label: String,
    /// Whether the link depends on it. The DE1 answers a StateInfo read even
    /// asleep, so a link that can't produce it (after one retry, the shells'
    /// rule) is hollow and is dropped for the reconnect loop. The others are
    /// best-effort.
    pub required: bool,
}

/// The connect-time notify subscriptions, in subscribe order: the five
/// required streams first (web subscribes them before declaring the GATT
/// verified), then the best-effort ones. Both shells subscribe exactly this
/// set.
#[must_use]
pub fn de1_subscriptions() -> Vec<De1Subscription> {
    let u = de1_uuids();
    let entry = |characteristic: String, name: &str, short: &str, required: bool| De1Subscription {
        characteristic,
        name: name.to_owned(),
        short: short.to_owned(),
        required,
    };
    vec![
        entry(u.state_info, "StateInfo", "A00E", true),
        entry(u.shot_sample, "ShotSample", "A00D", true),
        entry(u.water_levels, "WaterLevels", "A011", true),
        entry(u.mmr_read, "MMR_READ", "A005", true),
        entry(u.shot_settings, "ShotSettings", "A00B", true),
        // The DE1 answers a calibration read on this notify; without it the
        // Settings calibration panel stays blank, but the link still works.
        // (Version, A001, is not subscribed: it is read at connect, a seed
        // read, as web and reaprime do.)
        entry(u.calibration, "Calibration", "A012", false),
    ]
}

/// The connect-time seed reads, in read order.
#[must_use]
pub fn de1_seed_reads() -> Vec<De1SeedRead> {
    let u = de1_uuids();
    let entry = |characteristic: String, label: &str, required: bool| De1SeedRead {
        characteristic,
        label: label.to_owned(),
        required,
    };
    vec![
        entry(u.version, "firmware version", false),
        entry(u.state_info, "machine state", true),
        entry(u.shot_settings, "shot-settings", false),
    ]
}

/// The MMR registers read for the machine's identity after a connect, in
/// read order: the firmware build first (the update check needs it), then
/// the board, model (frame encoding), GHC (the cold-maintenance gate), refill
/// kit, serial (the stolen-machine check), heater voltage, flush temperature
/// and flow multiplier (de1app `later_new_de1_connection_setup`). How often
/// and how many passes stay shell policy.
#[must_use]
pub fn connect_identity_registers() -> Vec<MmrRegister> {
    vec![
        MmrRegister::FirmwareVersion,
        MmrRegister::CpuBoardVersion,
        MmrRegister::MachineModel,
        MmrRegister::GhcInfo,
        MmrRegister::RefillKit,
        MmrRegister::SerialNumber,
        MmrRegister::HeaterVoltage,
        MmrRegister::FlushTemp,
        MmrRegister::CalibrationFlowMultiplier,
    ]
}

/// The core [`Source`] a DE1 characteristic's notifications (and seed-read
/// bytes) feed, or `None` for a characteristic the shell doesn't dispatch.
/// Covers every [`de1_subscriptions`] and [`de1_seed_reads`] entry;
/// case-insensitive. The profile
/// frame ack is not here: the DE1 sends no notification on `FrameWrite`, the
/// shell synthesises it.
#[must_use]
pub fn de1_notification_source(characteristic: &str) -> Option<Source> {
    let u = de1_uuids();
    let table = [
        (u.state_info, Source::De1State),
        (u.shot_sample, Source::De1ShotSample),
        (u.water_levels, Source::De1WaterLevels),
        (u.mmr_read, Source::De1MmrRead),
        (u.shot_settings, Source::De1ShotSettings),
        (u.calibration, Source::De1Calibration),
        (u.version, Source::De1Version),
    ];
    table
        .into_iter()
        .find(|(uuid, _)| uuid.eq_ignore_ascii_case(characteristic))
        .map(|(_, source)| source)
}

/// [`de1_notification_source`] as the source's name (`"De1State"`, the
/// spelling the wasm bridge takes), for the wasm boundary.
#[must_use]
pub fn de1_notification_source_name(characteristic: &str) -> Option<&'static str> {
    Some(match de1_notification_source(characteristic)? {
        Source::De1State => "De1State",
        Source::De1ShotSample => "De1ShotSample",
        Source::De1WaterLevels => "De1WaterLevels",
        Source::De1MmrRead => "De1MmrRead",
        Source::De1ShotSettings => "De1ShotSettings",
        Source::De1Calibration => "De1Calibration",
        Source::De1Version => "De1Version",
        _ => return None,
    })
}

/// [`de1_subscriptions`] as JSON — the FFI / wasm boundary shape.
#[must_use]
pub fn de1_subscriptions_json() -> String {
    serde_json::to_string(&de1_subscriptions()).unwrap_or_else(|_| "[]".to_string())
}

/// [`de1_seed_reads`] as JSON — the FFI / wasm boundary shape.
#[must_use]
pub fn de1_seed_reads_json() -> String {
    serde_json::to_string(&de1_seed_reads()).unwrap_or_else(|_| "[]".to_string())
}

/// [`connect_identity_registers`] as JSON (register names, as the typeshare
/// `MmrRegister` spells them) — the wasm boundary shape.
#[must_use]
pub fn connect_identity_registers_json() -> String {
    serde_json::to_string(&connect_identity_registers()).unwrap_or_else(|_| "[]".to_string())
}

/// The DE1 characteristic UUID a [`WriteTarget`]'s bytes are written to. This
/// is the one-and-only `WriteTarget` → UUID map; the shells delegate here
/// instead of each carrying their own switch.
#[must_use]
pub fn de1_write_target_uuid(target: WriteTarget) -> String {
    let u = de1_uuids();
    match target {
        WriteTarget::De1RequestedState => u.requested_state,
        WriteTarget::De1ShotSettings => u.shot_settings,
        WriteTarget::De1MmrRequest => u.mmr_read,
        WriteTarget::De1MmrWrite => u.mmr_write,
        WriteTarget::De1Calibration => u.calibration,
        WriteTarget::De1WaterLevels => u.water_levels,
        WriteTarget::De1ProfileHeader => u.header_write,
        WriteTarget::De1ProfileFrame => u.frame_write,
        WriteTarget::De1FwMapRequest => u.fw_map_request,
    }
}

/// [`de1_write_target_uuid`] keyed by the [`WriteTarget`]'s serde name (the
/// string the shells already hold on the typeshare enum), for the FFI / wasm
/// boundary where passing the enum is awkward. `None` for an unknown name.
#[must_use]
pub fn de1_write_target_uuid_by_name(name: &str) -> Option<String> {
    let target: WriteTarget =
        serde_json::from_value(serde_json::Value::String(name.to_string())).ok()?;
    Some(de1_write_target_uuid(target))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn service_and_a_few_characteristics_have_the_expected_shorts() {
        let u = de1_uuids();
        assert_eq!(u.service, "0000a000-0000-1000-8000-00805f9b34fb");
        assert_eq!(u.requested_state, "0000a002-0000-1000-8000-00805f9b34fb");
        assert_eq!(u.frame_write, "0000a010-0000-1000-8000-00805f9b34fb");
        assert_eq!(u.state_info, "0000a00e-0000-1000-8000-00805f9b34fb");
    }

    #[test]
    fn every_write_target_maps_to_its_matching_characteristic() {
        let u = de1_uuids();
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1RequestedState),
            u.requested_state
        );
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1ShotSettings),
            u.shot_settings
        );
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1MmrRequest),
            u.mmr_read
        );
        assert_eq!(de1_write_target_uuid(WriteTarget::De1MmrWrite), u.mmr_write);
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1Calibration),
            u.calibration
        );
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1WaterLevels),
            u.water_levels
        );
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1ProfileHeader),
            u.header_write
        );
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1ProfileFrame),
            u.frame_write
        );
        assert_eq!(
            de1_write_target_uuid(WriteTarget::De1FwMapRequest),
            "0000a009-0000-1000-8000-00805f9b34fb"
        );
    }

    #[test]
    fn by_name_matches_the_typed_lookup_and_rejects_unknowns() {
        assert_eq!(
            de1_write_target_uuid_by_name("De1ProfileFrame"),
            Some(de1_write_target_uuid(WriteTarget::De1ProfileFrame))
        );
        assert_eq!(de1_write_target_uuid_by_name("NotATarget"), None);
    }

    #[test]
    fn the_five_required_subscriptions_come_first() {
        let subs = de1_subscriptions();
        let u = de1_uuids();
        let required: Vec<&str> = subs
            .iter()
            .filter(|s| s.required)
            .map(|s| s.characteristic.as_str())
            .collect();
        assert_eq!(
            required,
            [
                u.state_info.as_str(),
                u.shot_sample.as_str(),
                u.water_levels.as_str(),
                u.mmr_read.as_str(),
                u.shot_settings.as_str()
            ]
        );
        assert!(subs.iter().take(5).all(|s| s.required));
        assert!(subs.iter().skip(5).all(|s| !s.required));
    }

    #[test]
    fn every_subscription_and_seed_read_has_a_notification_source() {
        for s in de1_subscriptions() {
            assert!(
                de1_notification_source_name(&s.characteristic).is_some(),
                "{}",
                s.name
            );
        }
        for s in de1_seed_reads() {
            assert!(
                de1_notification_source(&s.characteristic).is_some(),
                "{}",
                s.label
            );
        }
        assert_eq!(
            de1_notification_source(&de1_uuids().state_info.to_uppercase()),
            Some(Source::De1State)
        );
        // No dispatch for the frame-write characteristic: the shell
        // synthesises the ack.
        assert_eq!(de1_notification_source(&de1_uuids().frame_write), None);
    }

    #[test]
    fn only_the_machine_state_seed_read_is_required() {
        let seeds = de1_seed_reads();
        let required: Vec<&str> = seeds
            .iter()
            .filter(|s| s.required)
            .map(|s| s.label.as_str())
            .collect();
        assert_eq!(required, ["machine state"]);
    }

    #[test]
    fn identity_reads_start_with_the_firmware_build() {
        let regs = connect_identity_registers();
        assert_eq!(regs[0], MmrRegister::FirmwareVersion);
        assert!(regs.contains(&MmrRegister::SerialNumber));
        assert!(regs.contains(&MmrRegister::GhcInfo));
        assert!(connect_identity_registers_json().starts_with("[\"FirmwareVersion\""));
    }

    #[test]
    fn json_round_trips_to_the_struct() {
        let json = de1_uuids_json();
        let parsed: De1Uuids = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, de1_uuids());
        // camelCase on the wire, mirrored by both shells.
        assert!(json.contains("\"requestedState\""));
        assert!(json.contains("\"shotSample\""));
    }
}
