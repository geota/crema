//! What the connected DE1's firmware build means — a read-only
//! classification of the installed build against crema's known-builds table.
//!
//! Mirrors the legacy de1app's local-comparison approach
//! (`vars.tcl:3787-3797`): read the DE1's installed firmware build number
//! from MMR register `0x800010` (the `firmware_version_number`, "v1352" in
//! user-facing terms) and compare it with a reference. de1app compares against
//! the `.dat` file it bundles; crema compares against
//! [`de1_domain::KNOWN_FIRMWARE_BUILDS`], a versioned table of Decent's builds
//! and the channels that serve them, so it can say *what* a build is
//! ("Decent stable", "newest release", "rolled back") instead of a bare
//! "update available". Build numbers are not chronological (the rolled-back
//! 1363 is numerically above 1358), so the classification never uses raw
//! numeric order alone — see [`de1_domain::classify_firmware_build`].
//!
//! The Version characteristic's BLE block (release / commits / API) is a
//! **different** number from this build — it tracks the BLE firmware tree,
//! not Decent's user-visible build number. The BLE label lives in
//! [`Event::Firmware`](crate::Event::Firmware); this module classifies the
//! build only.
//!
//! This module does **not** apply firmware updates.

use de1_domain::{FirmwareBuildReport, classify_firmware_build};

/// Classify the installed build (`None` until MMR `0x800010` has been read).
/// See [`CremaCore::firmware_build_report`](crate::CremaCore::firmware_build_report).
pub fn report(installed: Option<u32>) -> FirmwareBuildReport {
    classify_firmware_build(installed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use de1_domain::FirmwareBuildStatus;

    #[test]
    fn unknown_when_no_build_observed() {
        assert_eq!(report(None).status, FirmwareBuildStatus::Unknown);
    }

    #[test]
    fn the_rolled_back_1363_is_not_newer_than_1358() {
        assert_eq!(report(Some(1363)).status, FirmwareBuildStatus::RolledBack);
        assert_eq!(report(Some(1358)).status, FirmwareBuildStatus::Newest);
    }

    #[test]
    fn decent_stable_is_1333() {
        assert_eq!(report(Some(1333)).status, FirmwareBuildStatus::Stable);
    }
}
