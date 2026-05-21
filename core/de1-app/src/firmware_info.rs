//! Firmware-update availability check — a read-only comparison between the
//! installed DE1 firmware and the latest version Crema was compiled against.
//!
//! Mirrors the legacy de1app's local-check approach (`vars.tcl:3787-3797`):
//! legacy ships a firmware `.dat` file alongside the app, parses a numeric
//! version out of its header, and compares to the DE1's reported version. We
//! skip the bundled file (Crema is a PWA / app shell, not an install tree)
//! and use a hardcoded constant — [`LATEST_KNOWN_FIRMWARE`] — bumped per
//! Crema release whenever a new DE1 firmware drops.
//!
//! This module does **not** apply firmware updates; see
//! `docs/17-firmware-update-plan.md` for that plan, deferred to v2.

use de1_protocol::Version;
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// A DE1 CPU-board firmware identity, taken from the `Version` characteristic's
/// CPU block: the release number and the commits-since-release count.
///
/// Cross-FFI as plain scalars per the workspace convention.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct KnownFirmware {
    /// CPU firmware release number, e.g. `1.0` or `1.4`.
    pub release: f32,
    /// Commits since the tagged release — the build number that increments per
    /// firmware ship.
    pub commits: u16,
}

/// The latest DE1 firmware version Crema was compiled against — bumped per
/// Crema release.
///
/// Source: the legacy de1app's most recent firmware-release commit at the time
/// this constant was last updated (`de1app:74cacdcd`, "DE1 firmware v1352").
pub const LATEST_KNOWN_FIRMWARE: KnownFirmware = KnownFirmware {
    release: 1.0,
    commits: 1352,
};

/// What the firmware-update check found.
///
/// `#[non_exhaustive]` so we can grow the enum (e.g. a future "checking…" /
/// "fetched-from-manifest" variant) without breaking shell `match`es.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
#[non_exhaustive]
pub enum FirmwareUpdateStatus {
    /// No DE1 connected, or the `Version` characteristic has not yet been
    /// read. The shell should render "Connect a DE1 to check" or similar.
    Unknown,
    /// The installed firmware matches [`LATEST_KNOWN_FIRMWARE`].
    UpToDate {
        /// What Crema thinks is installed.
        installed: KnownFirmware,
    },
    /// The installed firmware is older than [`LATEST_KNOWN_FIRMWARE`].
    UpdateAvailable {
        /// What is installed today.
        installed: KnownFirmware,
        /// The latest version Crema knows about.
        latest: KnownFirmware,
    },
    /// The installed firmware is newer than [`LATEST_KNOWN_FIRMWARE`] — most
    /// likely the user updated through the legacy de1app since this Crema
    /// build shipped. Informational; no action needed.
    NewerInstalled {
        /// What is installed today.
        installed: KnownFirmware,
        /// The (older) version Crema knows about.
        latest: KnownFirmware,
    },
}

/// Compare an installed [`Version`] against [`LATEST_KNOWN_FIRMWARE`].
///
/// Returns [`FirmwareUpdateStatus::Unknown`] when `installed` is `None`.
/// Otherwise compares the **BLE block**'s `(release, commits)`
/// lexicographically: release first, then commits. The BLE block is the
/// primary firmware identity per the legacy `de1_version_string`
/// (`vars.tcl:3867`); the FW/CPU block is often all-zero on real DE1s.
///
/// **Known limitation**: Decent's user-facing firmware build number (e.g.
/// "v1352") comes from MMR register `0x800010`, not from the Version
/// characteristic. Until the MMR-read flow is wired end-to-end on the BLE
/// side (`web/src/lib/ble/de1.ts` does not yet write to / subscribe on the
/// MMR channels), this comparison is approximate — it confirms the BLE
/// firmware tree version, not the headline build number.
pub fn compare(installed: Option<&Version>) -> FirmwareUpdateStatus {
    let Some(v) = installed else {
        return FirmwareUpdateStatus::Unknown;
    };
    let installed = KnownFirmware {
        release: v.ble.release,
        commits: v.ble.commits,
    };
    let latest = LATEST_KNOWN_FIRMWARE;
    match installed.release.partial_cmp(&latest.release) {
        Some(std::cmp::Ordering::Greater) => {
            FirmwareUpdateStatus::NewerInstalled { installed, latest }
        }
        Some(std::cmp::Ordering::Less) => {
            FirmwareUpdateStatus::UpdateAvailable { installed, latest }
        }
        // Same release (or NaN, which round-tripped fixed-point cannot be):
        // compare commits.
        Some(std::cmp::Ordering::Equal) | None => {
            match installed.commits.cmp(&latest.commits) {
                std::cmp::Ordering::Less => {
                    FirmwareUpdateStatus::UpdateAvailable { installed, latest }
                }
                std::cmp::Ordering::Equal => FirmwareUpdateStatus::UpToDate { installed },
                std::cmp::Ordering::Greater => {
                    FirmwareUpdateStatus::NewerInstalled { installed, latest }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use de1_protocol::VersionBlock;

    fn version(release: f32, commits: u16) -> Version {
        // Put the values in the BLE block (the primary identity); leave the
        // FW block zeroed to match a typical real-DE1 reply.
        let ble = VersionBlock {
            api_version: 4,
            release,
            commits,
            changes: 0,
            sha: 0xDEAD_BEEF,
        };
        let fw = VersionBlock {
            api_version: 0,
            release: 0.0,
            commits: 0,
            changes: 0,
            sha: 0,
        };
        Version { ble, fw }
    }

    #[test]
    fn unknown_when_no_version_observed() {
        assert_eq!(compare(None), FirmwareUpdateStatus::Unknown);
    }

    #[test]
    fn up_to_date_when_versions_match() {
        let v = version(LATEST_KNOWN_FIRMWARE.release, LATEST_KNOWN_FIRMWARE.commits);
        assert!(matches!(
            compare(Some(&v)),
            FirmwareUpdateStatus::UpToDate { .. }
        ));
    }

    #[test]
    fn update_available_when_commits_older() {
        let v = version(LATEST_KNOWN_FIRMWARE.release, LATEST_KNOWN_FIRMWARE.commits - 1);
        let status = compare(Some(&v));
        match status {
            FirmwareUpdateStatus::UpdateAvailable { installed, latest } => {
                assert_eq!(installed.commits, LATEST_KNOWN_FIRMWARE.commits - 1);
                assert_eq!(latest.commits, LATEST_KNOWN_FIRMWARE.commits);
            }
            other => panic!("expected UpdateAvailable, got {other:?}"),
        }
    }

    #[test]
    fn newer_installed_when_commits_ahead() {
        let v = version(LATEST_KNOWN_FIRMWARE.release, LATEST_KNOWN_FIRMWARE.commits + 1);
        assert!(matches!(
            compare(Some(&v)),
            FirmwareUpdateStatus::NewerInstalled { .. }
        ));
    }

    #[test]
    fn release_dominates_commits_in_comparison() {
        // Older release, ahead on commits — still an update.
        let older_release = version(LATEST_KNOWN_FIRMWARE.release - 0.5, u16::MAX);
        assert!(matches!(
            compare(Some(&older_release)),
            FirmwareUpdateStatus::UpdateAvailable { .. }
        ));
        // Newer release, behind on commits — still newer.
        let newer_release = version(LATEST_KNOWN_FIRMWARE.release + 0.5, 0);
        assert!(matches!(
            compare(Some(&newer_release)),
            FirmwareUpdateStatus::NewerInstalled { .. }
        ));
    }
}
