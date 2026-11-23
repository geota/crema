//! The sans-IO grinder driver contract: what the core calls, what it gets back.

use de1_device::{DeviceKind, RegistryRow};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bookoo_motto80::{self, Motto80};
use crate::capabilities::{GrinderCapabilities, RpmObservation, SettingKind, SettingObservation};
use crate::reading::{GrinderInfo, GrinderReading};

/// Every grinder Crema can identify, in match order.
pub const GRINDERS: &[RegistryRow] = &[RegistryRow {
    kind: DeviceKind::Grinder,
    model: bookoo_motto80::MODEL,
    name_prefixes: &[bookoo_motto80::NAME_PREFIX],
    services: &[bookoo_motto80::SERVICE],
}];

/// The GATT UUIDs a shell needs for a connected grinder.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct GrinderUuids {
    /// The grinder's service.
    pub service: &'static str,
    /// Subscribe to this one; its notifications go to the core as
    /// `Source::GrinderNotify`.
    pub notify: &'static str,
    /// `Command::WriteGrinder` bytes go here.
    pub write: &'static str,
    /// Write with response (true) or without.
    pub write_with_response: bool,
}

/// Why a grinder link counts as dead. The shell drops the link and
/// reconnects.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum GrinderLinkDeadReason {
    /// Nothing valid arrived within 5 s of the handshake.
    NoFirstReading,
    /// No valid reading for 5 s.
    Silent,
    /// Too many malformed frames in a row.
    Malformed,
}

/// Why Crema won't use a grinder. The shell disconnects and does not retry.
#[derive(Debug, Clone, PartialEq, Eq)]
#[non_exhaustive]
pub enum UnsupportedReason {
    /// Its firmware is older than the protocol needs.
    FirmwareTooOld {
        /// The firmware it reported, for display.
        found: String,
        /// The oldest firmware that works, for display.
        min: String,
    },
}

/// What a driver reports.
#[derive(Debug, Clone, PartialEq)]
#[non_exhaustive]
pub enum GrinderEvent {
    /// The first valid reading after connect arrived (it follows as a
    /// [`Reading`](Self::Reading)).
    Ready {
        /// What the grinder has said about itself so far.
        info: GrinderInfo,
    },
    /// A decoded reading.
    Reading(GrinderReading),
    /// The grinder's info changed after [`Ready`](Self::Ready).
    Info(GrinderInfo),
    /// The grinder can't be used. The driver says nothing more.
    Unsupported {
        /// Why.
        reason: UnsupportedReason,
    },
    /// The link looks dead. The driver says nothing more.
    LinkDead {
        /// Why.
        reason: GrinderLinkDeadReason,
    },
}

/// Counters for the Devices page and bug reports.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct GrinderDiagnostics {
    /// Frames the reassembler rejected.
    pub malformed_frames: u32,
    /// Fields present but mistyped or out of range (each left out).
    pub out_of_range_fields: u32,
    /// Complete messages that weren't readable JSON, or a reading without a
    /// state.
    pub unreadable_messages: u32,
}

#[derive(Debug, Clone)]
enum Model {
    Motto80(Box<Motto80>),
}

/// One connection to one grinder. The core builds one per connect and drops
/// it on disconnect; nothing carries over.
#[derive(Debug, Clone)]
pub struct GrinderDriver {
    model: Model,
}

impl GrinderDriver {
    /// A driver for a model id from [`GRINDERS`].
    #[must_use]
    pub fn from_model(model: &str) -> Option<Self> {
        let model = match model {
            bookoo_motto80::MODEL => Model::Motto80(Box::new(Motto80::new())),
            _ => return None,
        };
        Some(GrinderDriver { model })
    }

    /// The grinder a connected device is: a discovered service that belongs
    /// to one model, else the advertised name (case-insensitive prefix).
    #[must_use]
    pub fn identify(advertised_name: &str, discovered_services: &[String]) -> Option<Self> {
        let by_service = GRINDERS.iter().find(|row| {
            row.services.iter().any(|s| {
                discovered_services
                    .iter()
                    .any(|d| d.eq_ignore_ascii_case(s))
            })
        });
        by_service
            .or_else(|| RegistryRow::find(GRINDERS, advertised_name))
            .and_then(|row| Self::from_model(row.model))
    }

    /// The model id.
    #[must_use]
    pub fn model(&self) -> &'static str {
        match self.model {
            Model::Motto80(_) => bookoo_motto80::MODEL,
        }
    }

    /// The GATT UUIDs.
    #[must_use]
    pub fn uuids(&self) -> GrinderUuids {
        match self.model {
            Model::Motto80(_) => GrinderUuids {
                service: bookoo_motto80::SERVICE,
                notify: bookoo_motto80::TX,
                write: bookoo_motto80::RX,
                write_with_response: true,
            },
        }
    }

    /// What it reports and accepts.
    #[must_use]
    pub fn capabilities(&self) -> GrinderCapabilities {
        match self.model {
            Model::Motto80(_) => GrinderCapabilities {
                setting: Some(SettingObservation {
                    kind: SettingKind::Numeric {
                        min: 0.0,
                        max: 999.0,
                        step: 1.0,
                    },
                    unit: "μm".to_owned(),
                    calibrated: true,
                    // Owner decision 2 (2026-11-22): both decaid Motto drivers
                    // and Sipquel record `bladeGap` as the grind, so Crema does.
                    physical: true,
                }),
                grind_rpm: Some(RpmObservation {
                    min: 500,
                    max: 1_500,
                    measured: false,
                }),
                feed_rpm: Some(RpmObservation {
                    min: 10,
                    max: 65,
                    measured: false,
                }),
                reports_state: true,
                reports_grind_count: true,
                reports_humidity: true,
                reports_weight: false,
                commands: crate::capabilities::GrinderCommands::default(),
            },
        }
    }

    /// What it has said about itself.
    #[must_use]
    pub fn info(&self) -> GrinderInfo {
        match &self.model {
            Model::Motto80(m) => m.info(),
        }
    }

    /// Counters for diagnostics.
    #[must_use]
    pub fn diagnostics(&self) -> GrinderDiagnostics {
        match &self.model {
            Model::Motto80(m) => m.diagnostics(),
        }
    }

    /// The bytes to write, in order, right after subscribing (the Motto's
    /// handshake). `max_packet` is `min(MTU - 3, 512)`, or 20 when the MTU is
    /// unknown. Starts the first-reading deadline.
    pub fn connect_writes(&mut self, max_packet: usize, now_ms: u64) -> Vec<Vec<u8>> {
        match &mut self.model {
            Model::Motto80(m) => m.connect_writes(max_packet, now_ms),
        }
    }

    /// Feed one notification. Never panics.
    pub fn on_notification(&mut self, bytes: &[u8], now_ms: u64) -> Vec<GrinderEvent> {
        match &mut self.model {
            Model::Motto80(m) => m.on_notification(bytes, now_ms),
        }
    }

    /// Advance the timers (first reading, silence).
    pub fn tick(&mut self, now_ms: u64) -> Vec<GrinderEvent> {
        match &mut self.model {
            Model::Motto80(m) => m.tick(now_ms),
        }
    }

    /// What to record in the capture file for one notification: zero or more
    /// frames with every private field redacted. Kept apart from decoding.
    pub fn redact_for_capture(&mut self, bytes: &[u8], now_ms: u64) -> Vec<Vec<u8>> {
        match &mut self.model {
            Model::Motto80(m) => m.redact_for_capture(bytes, now_ms),
        }
    }
}
