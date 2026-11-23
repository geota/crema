//! Device kinds and the scan registry rows a driver crate publishes.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// What a Bluetooth device is, beside the DE1.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[non_exhaustive]
pub enum DeviceKind {
    /// A coffee scale (`de1-scale`).
    Scale,
    /// A grinder (`de1-grinder`).
    Grinder,
}

/// One device model a scan can recognise: the advertised-name prefixes it is
/// found by and the GATT services a shell must be allowed to open on it.
///
/// A driver crate publishes a slice of these; the app core joins every kind's
/// rows into one scan (scales first, then grinders).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RegistryRow {
    /// Which kind of device this row finds.
    pub kind: DeviceKind,
    /// The model id the kind's driver is built from.
    pub model: &'static str,
    /// Advertised-name prefixes, matched case-insensitively.
    pub name_prefixes: &'static [&'static str],
    /// GATT services the driver touches (a Web Bluetooth `optionalServices`
    /// must list each one).
    pub services: &'static [&'static str],
}

impl RegistryRow {
    /// Whether `advertised_name` starts with one of this row's prefixes,
    /// ignoring ASCII case.
    #[must_use]
    pub fn matches_name(&self, advertised_name: &str) -> bool {
        self.name_prefixes.iter().any(|prefix| {
            advertised_name
                .get(..prefix.len())
                .is_some_and(|head| head.eq_ignore_ascii_case(prefix))
        })
    }

    /// The first row in `rows` whose prefixes match `advertised_name`.
    #[must_use]
    pub fn find<'a>(rows: &'a [RegistryRow], advertised_name: &str) -> Option<&'a RegistryRow> {
        rows.iter().find(|row| row.matches_name(advertised_name))
    }
}

/// A scan match: the kind and the model id its driver is built from.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceMatch {
    /// Which kind of device matched.
    pub kind: DeviceKind,
    /// The model id (a scale's label, a grinder's catalogue id).
    pub model: String,
}

/// The pre-connect scan filter set: advertised-name prefixes for the scan and
/// every service a connected device may need (a Web Bluetooth
/// `optionalServices`). Deduplicated, in registry order.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
pub struct ScanFilters {
    /// Advertised-name prefixes, matched case-insensitively.
    pub name_prefixes: Vec<&'static str>,
    /// GATT service UUIDs.
    pub service_uuids: Vec<&'static str>,
}

impl ScanFilters {
    /// Add `prefixes` and `services`, skipping ones already present.
    pub fn extend(&mut self, prefixes: &[&'static str], services: &[&'static str]) {
        for prefix in prefixes {
            if !self.name_prefixes.contains(prefix) {
                self.name_prefixes.push(prefix);
            }
        }
        for service in services {
            if !self.service_uuids.contains(service) {
                self.service_uuids.push(service);
            }
        }
    }

    /// The filters for `rows`.
    #[must_use]
    pub fn of(rows: &[RegistryRow]) -> ScanFilters {
        let mut filters = ScanFilters::default();
        for row in rows {
            filters.extend(row.name_prefixes, row.services);
        }
        filters
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ROWS: &[RegistryRow] = &[
        RegistryRow {
            kind: DeviceKind::Grinder,
            model: "a",
            name_prefixes: &["ABC "],
            services: &["s1"],
        },
        RegistryRow {
            kind: DeviceKind::Grinder,
            model: "b",
            name_prefixes: &["AB", "XY"],
            services: &["s1", "s2"],
        },
    ];

    #[test]
    fn a_name_matches_the_first_row_ignoring_case() {
        assert_eq!(
            RegistryRow::find(ROWS, "abc 1234").map(|r| r.model),
            Some("a")
        );
        assert_eq!(RegistryRow::find(ROWS, "ABX").map(|r| r.model), Some("b"));
        assert_eq!(RegistryRow::find(ROWS, "xy").map(|r| r.model), Some("b"));
        assert!(RegistryRow::find(ROWS, "A").is_none());
        assert!(RegistryRow::find(ROWS, "").is_none());
    }

    #[test]
    fn a_multibyte_name_never_panics() {
        assert!(RegistryRow::find(ROWS, "Aé").is_none());
        assert!(RegistryRow::find(ROWS, "éééé").is_none());
    }

    #[test]
    fn filters_are_deduplicated_in_order() {
        let filters = ScanFilters::of(ROWS);
        assert_eq!(filters.name_prefixes, ["ABC ", "AB", "XY"]);
        assert_eq!(filters.service_uuids, ["s1", "s2"]);
    }
}
