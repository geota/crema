//! Firmware-update availability check — a read-only comparison between the
//! installed DE1 firmware build number and the latest version Crema was
//! compiled against.
//!
//! Mirrors the legacy de1app's local-comparison approach
//! (`vars.tcl:3787-3797`): legacy reads the DE1's installed firmware build
//! number from MMR register `0x800010` (the `firmware_version_number`,
//! incremented for every build — "v1352" in user-facing terms) and compares
//! it to the version bundled with the app. Crema replaces the bundled `.dat`
//! file with a hardcoded constant — [`LATEST_KNOWN_FIRMWARE_BUILD`] — bumped
//! per Crema release whenever a new DE1 firmware drops.
//!
//! The Version characteristic's BLE block (release / commits / API) is a
//! **different** number from this build — it tracks the BLE firmware tree,
//! not Decent's user-visible build number. The BLE label lives in
//! [`Event::Firmware`](crate::Event::Firmware); this module compares the
//! build only.
//!
//! This module does **not** apply firmware updates; that plan is deferred
//! to v2.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// The latest DE1 firmware build number Crema was compiled against — bumped
/// per Crema release.
///
/// Source: the legacy de1app's most recent firmware-release commit at the time
/// this constant was last updated (`de1app:74cacdcd`, "DE1 firmware v1352").
/// On the wire this is the value the DE1 returns for MMR register `0x800010`
/// (`MmrRegister::FirmwareVersion`).
pub const LATEST_KNOWN_FIRMWARE_BUILD: u16 = 1352;

/// What the firmware-update check found.
///
/// `#[non_exhaustive]` so we can grow the enum (e.g. a future "checking…" /
/// "fetched-from-manifest" variant) without breaking shell `match`es.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
#[non_exhaustive]
pub enum FirmwareUpdateStatus {
    /// No DE1 connected, or MMR register `0x800010` (FirmwareVersion) has not
    /// yet been read. The shell should render "Connect a DE1 to check" or
    /// similar.
    Unknown,
    /// The installed build matches [`LATEST_KNOWN_FIRMWARE_BUILD`].
    UpToDate {
        /// The installed build number.
        installed: u16,
    },
    /// The installed build is older than [`LATEST_KNOWN_FIRMWARE_BUILD`].
    UpdateAvailable {
        /// The installed build number.
        installed: u16,
        /// The latest build Crema knows about.
        latest: u16,
    },
    /// The installed build is newer than [`LATEST_KNOWN_FIRMWARE_BUILD`] —
    /// likely the user updated through the legacy de1app since this Crema
    /// build shipped. Informational; no action needed.
    NewerInstalled {
        /// The installed build number.
        installed: u16,
        /// The (older) build Crema knows about.
        latest: u16,
    },
}

/// Compare an installed firmware build number against
/// [`LATEST_KNOWN_FIRMWARE_BUILD`].
///
/// Returns [`FirmwareUpdateStatus::Unknown`] when `installed` is `None`. The
/// `installed` value comes from `MmrRegister::FirmwareVersion` (MMR register
/// `0x800010`); see [`CremaCore::firmware_update_status`](crate::CremaCore::firmware_update_status).
pub fn compare(installed: Option<u16>) -> FirmwareUpdateStatus {
    let Some(installed) = installed else {
        return FirmwareUpdateStatus::Unknown;
    };
    let latest = LATEST_KNOWN_FIRMWARE_BUILD;
    match installed.cmp(&latest) {
        std::cmp::Ordering::Less => FirmwareUpdateStatus::UpdateAvailable { installed, latest },
        std::cmp::Ordering::Equal => FirmwareUpdateStatus::UpToDate { installed },
        std::cmp::Ordering::Greater => FirmwareUpdateStatus::NewerInstalled { installed, latest },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unknown_when_no_build_observed() {
        assert_eq!(compare(None), FirmwareUpdateStatus::Unknown);
    }

    #[test]
    fn up_to_date_when_builds_match() {
        assert_eq!(
            compare(Some(LATEST_KNOWN_FIRMWARE_BUILD)),
            FirmwareUpdateStatus::UpToDate {
                installed: LATEST_KNOWN_FIRMWARE_BUILD,
            }
        );
    }

    #[test]
    fn update_available_when_older() {
        let installed = LATEST_KNOWN_FIRMWARE_BUILD - 1;
        assert_eq!(
            compare(Some(installed)),
            FirmwareUpdateStatus::UpdateAvailable {
                installed,
                latest: LATEST_KNOWN_FIRMWARE_BUILD,
            }
        );
    }

    #[test]
    fn newer_installed_when_ahead() {
        let installed = LATEST_KNOWN_FIRMWARE_BUILD + 1;
        assert_eq!(
            compare(Some(installed)),
            FirmwareUpdateStatus::NewerInstalled {
                installed,
                latest: LATEST_KNOWN_FIRMWARE_BUILD,
            }
        );
    }
}
