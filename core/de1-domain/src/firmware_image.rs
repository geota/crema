//! DE1 firmware images: the `.dat` header, the pinned manifest of official
//! images crema may flash, and the validation every image must pass before a
//! single byte goes to the machine.
//!
//! ## The image format
//!
//! The file de1app ships as `de1plus/fw/bootfwupdate.dat` (and Decent's update
//! channels serve as `…/<channel>/fw/bootfwupdate.dat`) is a 64-byte
//! little-endian header followed by an encrypted body. The header layout is
//! de1app's `T_FirmwareHeader` (`de1plus/binary.tcl:366-389`), read the same
//! way by decaid (`lib/src/services/firmware/de1_firmware_header.dart:29-45`)
//! and Decenza (`src/core/firmwareheader.h`):
//!
//! | offset | field |
//! |---|---|
//! | 0  | `CheckSum` — checksum of the encrypted body |
//! | 4  | `BoardMarker` — `0xDE100001` for a DE1, `0xBE1C0001` for a Bengle |
//! | 8  | `Version` — the build number the DE1 reports at MMR `0x800010` |
//! | 12 | `ByteCount` — body bytes, ignoring padding |
//! | 16 | `CPUBytes` — the first `CPUBytes` of the body are for the CPU |
//! | 20 | `Unused` — always zero |
//! | 24 | `DCSum` — checksum of the decrypted body |
//! | 28 | `IV[32]` |
//! | 60 | `HSum` — checksum of the header |
//!
//! The checksum algorithms are not public; no reference app recomputes them.
//! Like decaid (`firmware_validator.dart:15-82`) and Decenza
//! (`FIRMWARE_UPDATE.md` "What's validated client-side"), crema pins each
//! official image by **SHA-256 and exact length** in [`firmware_manifest`] and
//! checks the header against the pinned values. The DE1's own verify step
//! (`FirstError == FF FF FD`) stays the authoritative correctness check.
//!
//! ## The manifest
//!
//! Each [`FirmwareArtifact`] names the official Decent URL(s) the image is
//! downloaded from at runtime — crema never bundles or commits firmware bytes.
//! The digests were taken from the files Decent's channels served on
//! [`FIRMWARE_MANIFEST_VERIFIED`]; the 1358 digest equals decaid's manifest
//! entry (`assets/firmware/manifest.json`, `de1-1358`) and de1app `main`'s
//! `bootfwupdate.dat` (`25a66f66`); the 1333 digest equals de1app's
//! `stable` branch file (`c8087ad0`). When Decent publishes a new file on a
//! channel, its digest no longer matches and crema refuses it until the
//! manifest is updated — a deliberate fail-safe.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use typeshare::typeshare;

use crate::firmware_builds::DecentFirmwareChannel;

/// Length of the `.dat` header.
pub const FIRMWARE_HEADER_LEN: usize = 64;

/// Bytes of image carried by one upload write (`firmware_upload_next`,
/// de1app `de1_comms.tcl:1120-1123`).
pub const FIRMWARE_CHUNK_LEN: usize = 16;

/// `BoardMarker` of a classic DE1 image.
pub const DE1_BOARD_MARKER: u32 = 0xDE10_0001;

/// `BoardMarker` of a Bengle image (de1app `fw/benglefw.dat`).
pub const BENGLE_BOARD_MARKER: u32 = 0xBE1C_0001;

/// Revision of [`firmware_manifest`]. Bump on every edit.
pub const FIRMWARE_MANIFEST_REVISION: u32 = 1;

/// The date the manifest's digests were checked against Decent's channels.
pub const FIRMWARE_MANIFEST_VERIFIED: &str = "2026-10-11";

/// The parsed 64-byte `.dat` header.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FirmwareHeader {
    /// Checksum of the encrypted body (algorithm unpublished).
    pub checksum: u32,
    /// `0xDE100001` (DE1) or `0xBE1C0001` (Bengle).
    pub board_marker: u32,
    /// The build number, e.g. 1358.
    pub version: u32,
    /// Body bytes, ignoring padding.
    pub byte_count: u32,
    /// The first `cpu_bytes` of the body are for the CPU; the rest for BLE.
    pub cpu_bytes: u32,
    /// Reserved; always zero.
    pub unused: u32,
    /// Checksum of the decrypted body (algorithm unpublished).
    pub decrypted_checksum: u32,
    /// Checksum of the header (algorithm unpublished).
    pub header_checksum: u32,
}

/// One official DE1 firmware image crema knows how to verify.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FirmwareArtifact {
    /// Stable id, e.g. `"de1-1358"`.
    pub id: String,
    /// The build the header carries and the DE1 reports afterwards.
    pub build: u32,
    /// The Decent channel crema labels this image with.
    pub channel: DecentFirmwareChannel,
    /// Every Decent channel serving this exact file on the verified date.
    pub channels: Vec<DecentFirmwareChannel>,
    /// Official download URLs, preferred first.
    pub urls: Vec<String>,
    /// Exact file length in bytes.
    pub byte_length: u32,
    /// Lower-case hex SHA-256 of the whole file.
    pub sha256: String,
    /// Expected header `BoardMarker`.
    pub board_marker: u32,
    /// Expected header `ByteCount`.
    pub body_byte_count: u32,
    /// Expected header `CPUBytes`.
    pub cpu_byte_count: u32,
    /// Number of 16-byte upload writes (`byte_length / 16`).
    pub chunk_count: u32,
    /// Short release notes (from the de1app commit that shipped it).
    pub release_notes: String,
    /// Where the pinned values come from.
    pub provenance: String,
}

/// Why an image was refused.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum FirmwareImageError {
    /// Shorter than the 64-byte header.
    TooShort {
        /// The file's length.
        length: u32,
    },
    /// A Bengle image (`0xBE1C0001`); crema flashes classic DE1s only.
    BengleImage,
    /// Not a DE1 image at all.
    NotADe1Image {
        /// The board marker found.
        board_marker: u32,
    },
    /// A DE1 image, but not one in the pinned manifest (a different build, a
    /// newer file on Decent's channel, or a corrupted download).
    NotInManifest {
        /// The header's build number.
        build: u32,
        /// The file's SHA-256.
        sha256: String,
    },
    /// In the manifest by digest but a structural check failed — should be
    /// impossible; it means the manifest itself is wrong.
    Malformed {
        /// Which check failed.
        reason: String,
    },
}

/// The result of [`validate_firmware_image`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FirmwareImageCheck {
    /// `true` when the image may be flashed.
    pub ok: bool,
    /// The matching manifest entry.
    pub artifact: Option<FirmwareArtifact>,
    /// The parsed header, when the file has one.
    pub header: Option<FirmwareHeader>,
    /// Why the image was refused.
    pub error: Option<FirmwareImageError>,
    /// The file's length.
    pub byte_length: u32,
    /// The file's SHA-256 (lower-case hex).
    pub sha256: String,
}

const URL_STABLE: &str =
    "https://fast.decentespresso.com/download/sync/de1plus/fw/bootfwupdate.dat";
const URL_BETA: &str = "https://fast.decentespresso.com/download/sync/de1beta/fw/bootfwupdate.dat";
const URL_NIGHTLY: &str =
    "https://fast.decentespresso.com/download/sync/de1nightly/fw/bootfwupdate.dat";

struct PinnedImage {
    id: &'static str,
    build: u32,
    channel: DecentFirmwareChannel,
    channels: &'static [DecentFirmwareChannel],
    urls: &'static [&'static str],
    byte_length: u32,
    sha256: &'static str,
    body_byte_count: u32,
    cpu_byte_count: u32,
    release_notes: &'static str,
    provenance: &'static str,
}

/// The official images, revision [`FIRMWARE_MANIFEST_REVISION`]. Same schema
/// fields as decaid's `assets/firmware/manifest.json` (id, build, channel,
/// byteLength, sha256, expected header values, notes, provenance) plus the
/// download URLs.
const PINNED: &[PinnedImage] = &[
    PinnedImage {
        id: "de1-1333",
        build: 1333,
        channel: DecentFirmwareChannel::Stable,
        channels: &[DecentFirmwareChannel::Stable, DecentFirmwareChannel::Beta],
        urls: &[URL_STABLE, URL_BETA],
        byte_length: 456_704,
        sha256: "eef434739ddf7bd988952d9b64c8ba82a1a2ef08ada9c88c2c242896ab5739c6",
        body_byte_count: 454_656,
        cpu_byte_count: 290_912,
        release_notes: "Decent's stable firmware: auto-detect refill kit fixes (v1330) and the \
                        build de1app restored after withdrawing 1363.",
        provenance: "fast.decentespresso.com de1plus + de1beta bootfwupdate.dat (Last-Modified \
                     2025-05-14), probed 2026-10-11; identical to de1app stable (c8087ad0) \
                     de1plus/fw/bootfwupdate.dat",
    },
    PinnedImage {
        id: "de1-1358",
        build: 1358,
        channel: DecentFirmwareChannel::Nightly,
        channels: &[DecentFirmwareChannel::Nightly],
        urls: &[URL_NIGHTLY],
        byte_length: 463_872,
        sha256: "ada25161ebbd661b44b3aab2c7756f42d95064a931462b3d134e8db66d198747",
        body_byte_count: 461_824,
        cpu_byte_count: 298_880,
        release_notes: "Cold maintenance forwards requests through preheat; sleep and air-purge \
                        requests allowed in refill with no refill kit (out of water).",
        provenance: "fast.decentespresso.com de1nightly bootfwupdate.dat (Last-Modified \
                     2026-09-21), probed 2026-10-11; identical to de1app main (25a66f66) and \
                     decaid assets/firmware/de1/de1-1358.bin",
    },
];

fn artifact(p: &PinnedImage) -> FirmwareArtifact {
    FirmwareArtifact {
        id: p.id.to_owned(),
        build: p.build,
        channel: p.channel,
        channels: p.channels.to_vec(),
        urls: p.urls.iter().map(|u| (*u).to_owned()).collect(),
        byte_length: p.byte_length,
        sha256: p.sha256.to_owned(),
        board_marker: DE1_BOARD_MARKER,
        body_byte_count: p.body_byte_count,
        cpu_byte_count: p.cpu_byte_count,
        chunk_count: p.byte_length / 16,
        release_notes: p.release_notes.to_owned(),
        provenance: p.provenance.to_owned(),
    }
}

/// Every official image crema can verify, stable first.
#[must_use]
pub fn firmware_manifest() -> Vec<FirmwareArtifact> {
    PINNED.iter().map(artifact).collect()
}

/// [`firmware_manifest`] as JSON, for the shells.
#[must_use]
pub fn firmware_manifest_json() -> String {
    serde_json::to_string(&firmware_manifest()).unwrap_or_else(|_| "[]".to_owned())
}

/// The manifest entry for `channel`, if one is pinned.
#[must_use]
pub fn firmware_artifact_for_channel(channel: DecentFirmwareChannel) -> Option<FirmwareArtifact> {
    PINNED
        .iter()
        .find(|p| p.channels.contains(&channel))
        .map(artifact)
}

fn le_u32(bytes: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]])
}

/// Parse the 64-byte header (little-endian, `binary.tcl:379-389` /
/// `parse_firmware_file_header` reads it `littleeendian`).
///
/// # Errors
///
/// [`FirmwareImageError::TooShort`] if `image` is shorter than the header.
pub fn parse_firmware_header(image: &[u8]) -> Result<FirmwareHeader, FirmwareImageError> {
    if image.len() < FIRMWARE_HEADER_LEN {
        return Err(FirmwareImageError::TooShort {
            length: u32::try_from(image.len()).unwrap_or(u32::MAX),
        });
    }
    Ok(FirmwareHeader {
        checksum: le_u32(image, 0),
        board_marker: le_u32(image, 4),
        version: le_u32(image, 8),
        byte_count: le_u32(image, 12),
        cpu_bytes: le_u32(image, 16),
        unused: le_u32(image, 20),
        decrypted_checksum: le_u32(image, 24),
        header_checksum: le_u32(image, 60),
    })
}

/// Lower-case hex SHA-256 of `bytes`.
#[must_use]
pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

/// The structural checks decaid's `FirmwareValidator` and Decenza's
/// `validateFile()` apply, plus crema's manifest match. Returns the first
/// failing check.
fn structural_problem(image: &[u8], h: &FirmwareHeader, p: &PinnedImage) -> Option<String> {
    let len = u32::try_from(image.len()).unwrap_or(u32::MAX);
    if len != p.byte_length {
        return Some(format!("length {len} != pinned {}", p.byte_length));
    }
    if !image.len().is_multiple_of(FIRMWARE_CHUNK_LEN) {
        return Some(format!("length {len} is not a multiple of 16"));
    }
    if h.board_marker != DE1_BOARD_MARKER {
        return Some(format!("board marker {:#010x}", h.board_marker));
    }
    if h.version != p.build {
        return Some(format!("header build {} != pinned {}", h.version, p.build));
    }
    if h.byte_count != p.body_byte_count {
        return Some(format!(
            "ByteCount {} != pinned {}",
            h.byte_count, p.body_byte_count
        ));
    }
    if h.cpu_bytes != p.cpu_byte_count {
        return Some(format!(
            "CPUBytes {} != pinned {}",
            h.cpu_bytes, p.cpu_byte_count
        ));
    }
    if h.byte_count == 0 || h.cpu_bytes == 0 || h.cpu_bytes > h.byte_count {
        return Some("CPUBytes / ByteCount not sane".to_owned());
    }
    if u64::from(h.byte_count) + 64 > u64::from(len) {
        return Some("ByteCount exceeds the file".to_owned());
    }
    if h.unused != 0 {
        return Some("reserved word is not zero".to_owned());
    }
    if h.checksum == 0 || h.decrypted_checksum == 0 || h.header_checksum == 0 {
        return Some("a checksum field is zero".to_owned());
    }
    if image[28..60].iter().all(|&b| b == 0) {
        return Some("IV is all zero".to_owned());
    }
    None
}

/// Validate `image` against [`firmware_manifest`]: it must be byte-for-byte
/// one of the pinned official images (SHA-256 + length) and its header must
/// carry the pinned values.
#[must_use]
pub fn validate_firmware_image(image: &[u8]) -> FirmwareImageCheck {
    let byte_length = u32::try_from(image.len()).unwrap_or(u32::MAX);
    let sha256 = sha256_hex(image);
    let refuse = |header: Option<FirmwareHeader>, error: FirmwareImageError, sha: String| {
        FirmwareImageCheck {
            ok: false,
            artifact: None,
            header,
            error: Some(error),
            byte_length,
            sha256: sha,
        }
    };
    let header = match parse_firmware_header(image) {
        Ok(h) => h,
        Err(e) => return refuse(None, e, sha256),
    };
    if header.board_marker == BENGLE_BOARD_MARKER {
        return refuse(Some(header), FirmwareImageError::BengleImage, sha256);
    }
    if header.board_marker != DE1_BOARD_MARKER {
        return refuse(
            Some(header),
            FirmwareImageError::NotADe1Image {
                board_marker: header.board_marker,
            },
            sha256,
        );
    }
    let Some(pinned) = PINNED.iter().find(|p| p.sha256 == sha256) else {
        let error = FirmwareImageError::NotInManifest {
            build: header.version,
            sha256: sha256.clone(),
        };
        return refuse(Some(header), error, sha256);
    };
    if let Some(reason) = structural_problem(image, &header, pinned) {
        return refuse(
            Some(header),
            FirmwareImageError::Malformed { reason },
            sha256,
        );
    }
    FirmwareImageCheck {
        ok: true,
        artifact: Some(artifact(pinned)),
        header: Some(header),
        error: None,
        byte_length,
        sha256,
    }
}

/// [`validate_firmware_image`] as JSON, for the shells.
#[must_use]
pub fn validate_firmware_image_json(image: &[u8]) -> String {
    serde_json::to_string(&validate_firmware_image(image))
        .unwrap_or_else(|_| r#"{"ok":false}"#.to_owned())
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A synthetic image whose header carries `pinned`'s values. Its digest
    /// cannot match the real file, so tests that need a manifest hit go
    /// through [`validate_with`] below.
    pub(crate) fn synthetic_image(build: u32, len: usize, body: u32, cpu: u32) -> Vec<u8> {
        let mut image = vec![0xA5u8; len];
        let words = [
            0x1234_5678u32,
            DE1_BOARD_MARKER,
            build,
            body,
            cpu,
            0,
            0x9ABC_DEF0,
        ];
        for (i, w) in words.iter().enumerate() {
            image[i * 4..i * 4 + 4].copy_from_slice(&w.to_le_bytes());
        }
        image[60..64].copy_from_slice(&0x0F0F_0F0Fu32.to_le_bytes());
        image
    }

    fn pinned(build: u32) -> &'static PinnedImage {
        PINNED.iter().find(|p| p.build == build).unwrap()
    }

    #[test]
    fn the_manifest_pins_stable_1333_and_nightly_1358() {
        assert_eq!(FIRMWARE_MANIFEST_REVISION, 1);
        let m = firmware_manifest();
        assert_eq!(m.len(), 2);
        assert_eq!(m[0].build, 1333);
        assert_eq!(m[0].channel, DecentFirmwareChannel::Stable);
        assert_eq!(m[0].chunk_count, 28_544);
        assert_eq!(m[1].build, 1358);
        assert_eq!(m[1].channel, DecentFirmwareChannel::Nightly);
        assert_eq!(m[1].chunk_count, 28_992);
        for a in &m {
            assert_eq!(a.sha256.len(), 64);
            assert!(
                a.sha256
                    .chars()
                    .all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase())
            );
            assert_eq!(a.byte_length % 16, 0);
            assert!(
                a.urls
                    .iter()
                    .all(|u| u.starts_with("https://fast.decentespresso.com/"))
            );
            assert!(a.cpu_byte_count < a.body_byte_count);
            assert!(a.body_byte_count + 64 <= a.byte_length);
        }
    }

    #[test]
    fn manifest_builds_agree_with_the_known_builds_table() {
        use crate::firmware_builds::decent_channel_build;
        for channel in [
            DecentFirmwareChannel::Stable,
            DecentFirmwareChannel::Beta,
            DecentFirmwareChannel::Nightly,
        ] {
            assert_eq!(
                firmware_artifact_for_channel(channel).map(|a| a.build),
                Some(decent_channel_build(channel)),
                "{channel:?}"
            );
        }
    }

    #[test]
    fn header_parses_little_endian() {
        let image = synthetic_image(1358, 128, 64, 32);
        let h = parse_firmware_header(&image).unwrap();
        assert_eq!(h.board_marker, 0xDE10_0001);
        assert_eq!(h.version, 1358);
        assert_eq!(h.byte_count, 64);
        assert_eq!(h.cpu_bytes, 32);
        assert_eq!(h.header_checksum, 0x0F0F_0F0F);
        // Byte 4..8 on the wire is 01 00 10 DE.
        assert_eq!(&image[4..8], &[0x01, 0x00, 0x10, 0xDE]);
    }

    #[test]
    fn a_short_file_is_refused() {
        let check = validate_firmware_image(&[0u8; 10]);
        assert!(!check.ok);
        assert_eq!(
            check.error,
            Some(FirmwareImageError::TooShort { length: 10 })
        );
    }

    #[test]
    fn a_bengle_image_is_refused() {
        let mut image = synthetic_image(393, 256, 128, 64);
        image[4..8].copy_from_slice(&BENGLE_BOARD_MARKER.to_le_bytes());
        assert_eq!(
            validate_firmware_image(&image).error,
            Some(FirmwareImageError::BengleImage)
        );
    }

    #[test]
    fn a_foreign_file_is_refused() {
        let image = vec![0x42u8; 4096];
        assert!(matches!(
            validate_firmware_image(&image).error,
            Some(FirmwareImageError::NotADe1Image { .. })
        ));
    }

    #[test]
    fn a_de1_image_outside_the_manifest_is_refused_with_its_build() {
        let image = synthetic_image(1358, 463_872, 461_824, 298_880);
        let check = validate_firmware_image(&image);
        assert!(!check.ok);
        match check.error {
            Some(FirmwareImageError::NotInManifest { build, sha256 }) => {
                assert_eq!(build, 1358);
                assert_eq!(sha256, check.sha256);
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn structural_checks_catch_each_mismatch() {
        let p = pinned(1358);
        let good = synthetic_image(1358, 463_872, 461_824, 298_880);
        let h = parse_firmware_header(&good).unwrap();
        assert_eq!(structural_problem(&good, &h, p), None);

        let short = &good[..good.len() - 16];
        assert!(structural_problem(short, &h, p).unwrap().contains("length"));

        let wrong_build = synthetic_image(1352, 463_872, 461_824, 298_880);
        let hb = parse_firmware_header(&wrong_build).unwrap();
        assert!(
            structural_problem(&wrong_build, &hb, p)
                .unwrap()
                .contains("build")
        );

        let wrong_cpu = synthetic_image(1358, 463_872, 461_824, 298_592);
        let hc = parse_firmware_header(&wrong_cpu).unwrap();
        assert!(
            structural_problem(&wrong_cpu, &hc, p)
                .unwrap()
                .contains("CPUBytes")
        );

        let mut zero_iv = good.clone();
        zero_iv[28..60].fill(0);
        assert!(structural_problem(&zero_iv, &h, p).unwrap().contains("IV"));

        let mut zero_sum = good.clone();
        zero_sum[0..4].fill(0);
        let hz = parse_firmware_header(&zero_sum).unwrap();
        assert!(
            structural_problem(&zero_sum, &hz, p)
                .unwrap()
                .contains("checksum")
        );
    }

    #[test]
    fn sha256_matches_a_known_vector() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
