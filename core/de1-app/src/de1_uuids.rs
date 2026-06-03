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

use crate::event::WriteTarget;

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
    }
}

/// [`de1_uuids`] as a JSON string — the FFI / wasm boundary shape (the shells
/// parse it into their own [`De1Uuids`] mirror, exactly like `scaleUuids`).
#[must_use]
pub fn de1_uuids_json() -> String {
    serde_json::to_string(&de1_uuids()).unwrap_or_else(|_| "{}".to_string())
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
        assert_eq!(de1_write_target_uuid(WriteTarget::De1RequestedState), u.requested_state);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1ShotSettings), u.shot_settings);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1MmrRequest), u.mmr_read);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1MmrWrite), u.mmr_write);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1Calibration), u.calibration);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1WaterLevels), u.water_levels);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1ProfileHeader), u.header_write);
        assert_eq!(de1_write_target_uuid(WriteTarget::De1ProfileFrame), u.frame_write);
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
    fn json_round_trips_to_the_struct() {
        let json = de1_uuids_json();
        let parsed: De1Uuids = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, de1_uuids());
        // camelCase on the wire, mirrored by both shells.
        assert!(json.contains("\"requestedState\""));
        assert!(json.contains("\"shotSample\""));
    }
}
