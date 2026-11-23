//! A device's firmware version.

use std::fmt;

/// A four-part firmware version, `major.minor.patch.build`, ordered part by
/// part. Displayed with the build padded to four digits (`1.2.19.0820`), the
/// way the BOOKOO Motto 80 documentation writes its releases.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct FirmwareVersion {
    /// Major version.
    pub major: u32,
    /// Minor version.
    pub minor: u32,
    /// Patch version.
    pub patch: u32,
    /// Build number.
    pub build: u32,
}

impl FirmwareVersion {
    /// A version from its four parts.
    #[must_use]
    pub const fn new(major: u32, minor: u32, patch: u32, build: u32) -> Self {
        FirmwareVersion {
            major,
            minor,
            patch,
            build,
        }
    }
}

impl fmt::Display for FirmwareVersion {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}.{}.{}.{:04}",
            self.major, self.minor, self.patch, self.build
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_order_part_by_part() {
        assert!(FirmwareVersion::new(1, 2, 19, 820) > FirmwareVersion::new(1, 2, 4, 416));
        assert!(FirmwareVersion::new(1, 3, 0, 0) > FirmwareVersion::new(1, 2, 99, 9999));
        assert!(FirmwareVersion::new(1, 2, 19, 819) < FirmwareVersion::new(1, 2, 19, 820));
    }

    #[test]
    fn the_build_is_padded_to_four_digits() {
        assert_eq!(
            FirmwareVersion::new(1, 2, 19, 820).to_string(),
            "1.2.19.0820"
        );
        assert_eq!(FirmwareVersion::new(0, 0, 0, 0).to_string(), "0.0.0.0000");
    }
}
