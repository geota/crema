//! What crema knows about DE1 firmware builds — a small, explicitly versioned
//! table of Decent's published builds, and a classifier that says what an
//! installed build *means* against it.
//!
//! ## Why a table, not `installed < latest`
//!
//! DE1 build numbers are not chronological. de1app shipped **1363** in Sep 2023
//! (`de1app:09baaa10`) and reverted to **1333** in Jan 2024 (`c8087ad0`,
//! "before the STANDBY switch bootup message bug"); 1337 … 1358 followed in
//! 2026. A raw numeric compare reads a machine left on 1363 as *newer* than
//! 1358. So every comparison goes through [`KNOWN_FIRMWARE_BUILDS`]: a
//! rolled-back row is [`FirmwareBuildStatus::RolledBack`], and only a build
//! above the newest released row reads as
//! [`FirmwareBuildStatus::NewerThanKnown`].
//!
//! ## Decent's channels (verified 2026-10-11)
//!
//! de1app delivers firmware inside its own app update, synced from
//! `https://fast.decentespresso.com/download/sync/<channel>/fw/bootfwupdate.dat`.
//! Probed 2026-10-11 (HEAD + a 64-byte header read): `de1plus` (stable) and
//! `de1beta` both serve **1333**; only `de1nightly` serves **1358**. The de1app
//! repo agrees: its `stable` and `beta` branches still carry 1333 (`c8087ad0`),
//! `main` carries 1358 (`25a66f66`). Other apps label differently — decaid's
//! manifest calls 1352 and 1358 "stable"; Decenza ships 1352 as Stable and
//! 1358 as Early access — but this table records *Decent's* channels, since
//! that is where a user updating today gets firmware from.
//!
//! ## Maintenance
//!
//! Bump [`KNOWN_FIRMWARE_BUILDS_REVISION`] and [`KNOWN_FIRMWARE_BUILDS_VERIFIED`]
//! on every edit. Sources: `git log -- de1plus/fw/bootfwupdate.dat` in de1app
//! (each row cites its commit), plus the channel probe above. The table starts
//! at the stable build; anything below it classifies as
//! [`FirmwareBuildStatus::OlderThanStable`] without needing a row.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// Revision of [`KNOWN_FIRMWARE_BUILDS`]. Bump on every edit to the table.
pub const KNOWN_FIRMWARE_BUILDS_REVISION: u32 = 1;

/// The date the table's channel columns were last checked against Decent's
/// live update channels (ISO 8601).
pub const KNOWN_FIRMWARE_BUILDS_VERIFIED: &str = "2026-10-11";

/// One of Decent's de1app update channels.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum DecentFirmwareChannel {
    /// `de1plus` — de1app's default (stable) channel.
    Stable,
    /// `de1beta`.
    Beta,
    /// `de1nightly`.
    Nightly,
}

/// Whether a build in the table is a live release or one Decent withdrew.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KnownBuildState {
    /// Published and never withdrawn (it may since have been superseded).
    Released,
    /// Published, then withdrawn — Decent reverted to an older build.
    RolledBack,
}

/// One row of [`KNOWN_FIRMWARE_BUILDS`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KnownFirmwareBuild {
    /// The build number the DE1 reports at MMR `0x800010`.
    pub build: u32,
    /// When de1app first shipped it (commit date, ISO 8601).
    pub released: &'static str,
    /// The de1app commit that shipped it.
    pub de1app_commit: &'static str,
    /// Decent channels serving this build on [`KNOWN_FIRMWARE_BUILDS_VERIFIED`].
    pub channels: &'static [DecentFirmwareChannel],
    /// Released, or rolled back.
    pub state: KnownBuildState,
}

/// Decent's DE1 firmware builds from the current stable build up, plus the
/// rolled-back 1363. Revision [`KNOWN_FIRMWARE_BUILDS_REVISION`].
pub const KNOWN_FIRMWARE_BUILDS: &[KnownFirmwareBuild] = &[
    // Shipped 2022-11-14 (360f4def); restored 2024-01-18 when 1363 was
    // reverted (c8087ad0). Still what stable and beta serve.
    KnownFirmwareBuild {
        build: 1333,
        released: "2022-11-14",
        de1app_commit: "360f4def",
        channels: &[DecentFirmwareChannel::Stable, DecentFirmwareChannel::Beta],
        state: KnownBuildState::Released,
    },
    KnownFirmwareBuild {
        build: 1337,
        released: "2026-03-23",
        de1app_commit: "d2b0180c",
        channels: &[],
        state: KnownBuildState::Released,
    },
    KnownFirmwareBuild {
        build: 1341,
        released: "2026-03-24",
        de1app_commit: "e8a133f8",
        channels: &[],
        state: KnownBuildState::Released,
    },
    KnownFirmwareBuild {
        build: 1342,
        released: "2026-03-25",
        de1app_commit: "fa8eb78a",
        channels: &[],
        state: KnownBuildState::Released,
    },
    KnownFirmwareBuild {
        build: 1346,
        released: "2026-04-01",
        de1app_commit: "e9233f42",
        channels: &[],
        state: KnownBuildState::Released,
    },
    KnownFirmwareBuild {
        build: 1352,
        released: "2026-04-20",
        de1app_commit: "74cacdcd",
        channels: &[],
        state: KnownBuildState::Released,
    },
    KnownFirmwareBuild {
        build: 1358,
        released: "2026-08-10",
        de1app_commit: "25a66f66",
        channels: &[DecentFirmwareChannel::Nightly],
        state: KnownBuildState::Released,
    },
    // Shipped 2023-09-18, reverted to 1333 on 2024-01-18 (c8087ad0). Its
    // number is higher than every 2026 build, but it is not newer.
    KnownFirmwareBuild {
        build: 1363,
        released: "2023-09-18",
        de1app_commit: "09baaa10",
        channels: &[],
        state: KnownBuildState::RolledBack,
    },
];

/// What an installed build means against [`KNOWN_FIRMWARE_BUILDS`]. Each
/// variant is a status key the shells map to localized copy.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FirmwareBuildStatus {
    /// No build to classify: no DE1 connected, or MMR `0x800010` not read yet
    /// (or it read 0).
    Unknown,
    /// The newest released build in the table.
    Newest,
    /// The build Decent's stable channel serves (and not also the newest).
    Stable,
    /// Newer than Decent's stable build, older than the newest release.
    BetweenStableAndNewest,
    /// Older than Decent's stable build.
    OlderThanStable,
    /// A build Decent published and then withdrew (e.g. 1363). Not "newer",
    /// whatever its number.
    RolledBack,
    /// Above the newest released build and not a known rolled-back one —
    /// released after this table was written.
    NewerThanKnown,
}

/// The classification plus the reference points the shells need to explain
/// it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FirmwareBuildReport {
    /// What the installed build means.
    pub status: FirmwareBuildStatus,
    /// The installed build, when known.
    pub installed: Option<u32>,
    /// The newest released build crema knows about.
    pub newest_build: u32,
    /// The build Decent's stable channel serves.
    pub stable_build: u32,
    /// The build Decent's beta channel serves.
    pub beta_build: u32,
    /// The build Decent's nightly channel serves.
    pub nightly_build: u32,
    /// The Decent channels serving the installed build today (empty when
    /// none do, or the build is unknown).
    pub channels: Vec<DecentFirmwareChannel>,
    /// [`KNOWN_FIRMWARE_BUILDS_REVISION`].
    pub table_revision: u32,
    /// [`KNOWN_FIRMWARE_BUILDS_VERIFIED`].
    pub table_verified: String,
}

/// The table row for `build`, if it has one.
#[must_use]
pub fn known_firmware_build(build: u32) -> Option<&'static KnownFirmwareBuild> {
    KNOWN_FIRMWARE_BUILDS.iter().find(|row| row.build == build)
}

/// The newest released (not rolled-back) build in the table.
#[must_use]
pub fn newest_known_firmware_build() -> u32 {
    KNOWN_FIRMWARE_BUILDS
        .iter()
        .filter(|row| row.state == KnownBuildState::Released)
        .map(|row| row.build)
        .max()
        .unwrap_or(0)
}

/// The build `channel` serves, per the table.
#[must_use]
pub fn decent_channel_build(channel: DecentFirmwareChannel) -> u32 {
    KNOWN_FIRMWARE_BUILDS
        .iter()
        .find(|row| row.channels.contains(&channel))
        .map_or(0, |row| row.build)
}

/// Classify an installed DE1 firmware build (MMR `0x800010`) against
/// [`KNOWN_FIRMWARE_BUILDS`].
///
/// `None` and `Some(0)` are [`FirmwareBuildStatus::Unknown`]. A rolled-back
/// row wins over any numeric position; then the newest release, the stable
/// build, and the ranges either side of them.
#[must_use]
pub fn classify_firmware_build(installed: Option<u32>) -> FirmwareBuildReport {
    let installed = installed.filter(|&b| b != 0);
    let newest = newest_known_firmware_build();
    let stable = decent_channel_build(DecentFirmwareChannel::Stable);
    let row = installed.and_then(known_firmware_build);
    let status = match installed {
        None => FirmwareBuildStatus::Unknown,
        Some(_) if row.is_some_and(|r| r.state == KnownBuildState::RolledBack) => {
            FirmwareBuildStatus::RolledBack
        }
        Some(b) if b > newest => FirmwareBuildStatus::NewerThanKnown,
        Some(b) if b == newest => FirmwareBuildStatus::Newest,
        Some(b) if b == stable => FirmwareBuildStatus::Stable,
        Some(b) if b > stable => FirmwareBuildStatus::BetweenStableAndNewest,
        Some(_) => FirmwareBuildStatus::OlderThanStable,
    };
    FirmwareBuildReport {
        status,
        installed,
        newest_build: newest,
        stable_build: stable,
        beta_build: decent_channel_build(DecentFirmwareChannel::Beta),
        nightly_build: decent_channel_build(DecentFirmwareChannel::Nightly),
        channels: row.map(|r| r.channels.to_vec()).unwrap_or_default(),
        table_revision: KNOWN_FIRMWARE_BUILDS_REVISION,
        table_verified: KNOWN_FIRMWARE_BUILDS_VERIFIED.to_string(),
    }
}

/// [`classify_firmware_build`] as JSON, for the wasm + uniffi bridges.
#[must_use]
pub fn classify_firmware_build_json(installed: Option<u32>) -> String {
    // Serializing this plain struct cannot fail; degrade to a minimal
    // Unknown report rather than panic.
    serde_json::to_string(&classify_firmware_build(installed))
        .unwrap_or_else(|_| r#"{"status":"Unknown"}"#.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn status(build: u32) -> FirmwareBuildStatus {
        classify_firmware_build(Some(build)).status
    }

    #[test]
    fn the_table_matches_decents_channels_on_the_verified_date() {
        assert_eq!(KNOWN_FIRMWARE_BUILDS_REVISION, 1);
        assert_eq!(KNOWN_FIRMWARE_BUILDS_VERIFIED, "2026-10-11");
        assert_eq!(decent_channel_build(DecentFirmwareChannel::Stable), 1333);
        assert_eq!(decent_channel_build(DecentFirmwareChannel::Beta), 1333);
        assert_eq!(decent_channel_build(DecentFirmwareChannel::Nightly), 1358);
        assert_eq!(newest_known_firmware_build(), 1358);
    }

    #[test]
    fn the_table_is_well_formed() {
        // Sorted, no duplicates.
        for pair in KNOWN_FIRMWARE_BUILDS.windows(2) {
            assert!(
                pair[0].build < pair[1].build,
                "{} !< {}",
                pair[0].build,
                pair[1].build
            );
        }
        // Each channel serves exactly one build, and never a rolled-back one.
        for channel in [
            DecentFirmwareChannel::Stable,
            DecentFirmwareChannel::Beta,
            DecentFirmwareChannel::Nightly,
        ] {
            let rows: Vec<_> = KNOWN_FIRMWARE_BUILDS
                .iter()
                .filter(|r| r.channels.contains(&channel))
                .collect();
            assert_eq!(rows.len(), 1, "{channel:?}");
            assert_eq!(rows[0].state, KnownBuildState::Released);
        }
        // The table starts at the stable build.
        assert_eq!(
            KNOWN_FIRMWARE_BUILDS[0].build,
            decent_channel_build(DecentFirmwareChannel::Stable)
        );
    }

    #[test]
    fn missing_build_is_unknown() {
        let report = classify_firmware_build(None);
        assert_eq!(report.status, FirmwareBuildStatus::Unknown);
        assert_eq!(report.installed, None);
        assert!(report.channels.is_empty());
        // The reference points are still filled in for the copy.
        assert_eq!(report.newest_build, 1358);
        assert_eq!(report.stable_build, 1333);
    }

    #[test]
    fn a_zero_build_is_unknown() {
        assert_eq!(status(0), FirmwareBuildStatus::Unknown);
        assert_eq!(classify_firmware_build(Some(0)).installed, None);
    }

    #[test]
    fn build_1358_is_the_newest_and_nightly_only() {
        let report = classify_firmware_build(Some(1358));
        assert_eq!(report.status, FirmwareBuildStatus::Newest);
        assert_eq!(report.installed, Some(1358));
        assert_eq!(report.channels, vec![DecentFirmwareChannel::Nightly]);
    }

    #[test]
    fn build_1333_is_decent_stable_and_beta() {
        let report = classify_firmware_build(Some(1333));
        assert_eq!(report.status, FirmwareBuildStatus::Stable);
        assert_eq!(
            report.channels,
            vec![DecentFirmwareChannel::Stable, DecentFirmwareChannel::Beta]
        );
    }

    #[test]
    fn builds_between_stable_and_newest() {
        for build in [1337, 1341, 1342, 1346, 1352] {
            let report = classify_firmware_build(Some(build));
            assert_eq!(
                report.status,
                FirmwareBuildStatus::BetweenStableAndNewest,
                "{build}"
            );
            assert!(report.channels.is_empty());
        }
        // A build between the anchors with no row of its own (decaid's test
        // machine ran 1356) is still between them.
        assert_eq!(status(1356), FirmwareBuildStatus::BetweenStableAndNewest);
    }

    #[test]
    fn builds_below_stable_are_older() {
        for build in [1238, 1293, 1320, 1330, 1332] {
            assert_eq!(
                status(build),
                FirmwareBuildStatus::OlderThanStable,
                "{build}"
            );
        }
    }

    #[test]
    fn build_1363_is_rolled_back_not_newer() {
        let report = classify_firmware_build(Some(1363));
        assert_eq!(report.status, FirmwareBuildStatus::RolledBack);
        assert_eq!(report.installed, Some(1363));
        assert!(report.channels.is_empty());
    }

    #[test]
    fn every_rolled_back_row_classifies_as_rolled_back() {
        for row in KNOWN_FIRMWARE_BUILDS
            .iter()
            .filter(|r| r.state == KnownBuildState::RolledBack)
        {
            assert_eq!(
                status(row.build),
                FirmwareBuildStatus::RolledBack,
                "{}",
                row.build
            );
        }
    }

    #[test]
    fn builds_above_the_newest_release_are_newer_than_known() {
        // Below the rolled-back 1363 and above it alike.
        for build in [1359, 1362, 1364, 1400, u32::from(u16::MAX)] {
            let report = classify_firmware_build(Some(build));
            assert_eq!(
                report.status,
                FirmwareBuildStatus::NewerThanKnown,
                "{build}"
            );
            assert_eq!(report.installed, Some(build));
        }
    }

    #[test]
    fn json_facade_uses_camel_case_and_variant_names() {
        let json: serde_json::Value =
            serde_json::from_str(&classify_firmware_build_json(Some(1363))).unwrap();
        assert_eq!(json["status"], "RolledBack");
        assert_eq!(json["installed"], 1363);
        assert_eq!(json["newestBuild"], 1358);
        assert_eq!(json["stableBuild"], 1333);
        assert_eq!(json["betaBuild"], 1333);
        assert_eq!(json["nightlyBuild"], 1358);
        assert_eq!(json["tableRevision"], 1);
        assert_eq!(json["tableVerified"], "2026-10-11");

        let json: serde_json::Value =
            serde_json::from_str(&classify_firmware_build_json(Some(1358))).unwrap();
        assert_eq!(json["channels"], serde_json::json!(["Nightly"]));

        let json: serde_json::Value =
            serde_json::from_str(&classify_firmware_build_json(None)).unwrap();
        assert_eq!(json["status"], "Unknown");
        assert!(json["installed"].is_null());
    }
}
