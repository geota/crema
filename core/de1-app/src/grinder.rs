//! The connected grinder: the core owns its driver, its grind detection and
//! the last completed grinds, like it owns the scale's codec. The shell owns
//! the radio and feeds bytes in.
//!
//! Lifecycle, from the shell's side:
//! 1. [`identify_device`] on a scan result (or [`connect_grinder`](CremaCore::connect_grinder)
//!    directly) once the link is up and the services are discovered;
//! 2. subscribe to [`GrinderUuids::notify`], then
//!    [`grinder_subscribed`](CremaCore::grinder_subscribed) and write its
//!    `Command::WriteGrinder` bytes in order;
//! 3. feed every notification as `Source::GrinderNotify`; the core's tick
//!    drives the first-reading and silence deadlines;
//! 4. on `Event::GrinderLinkDead`, drop the link and reconnect (a fresh
//!    driver); on `Event::GrinderUnsupported`, drop it and don't retry;
//! 5. on any drop, [`disconnect_grinder`](CremaCore::disconnect_grinder).
//!
//! The grinder is a device link, not DE1 session state: a DE1 reset keeps it.

use std::collections::VecDeque;

use de1_device::{DeviceKind, DeviceMatch, ScanFilters};
use de1_grinder::{
    GRINDERS, GrindSession, GrindTracker, GrinderCapabilities, GrinderDriver, GrinderEvent,
    GrinderUuids, UnsupportedReason,
};
use de1_scale::Scale;

use crate::CremaCore;
use crate::event::{Command, CoreOutput, Event, Source};

/// How many completed grinds and purges the core keeps.
pub const RECENT_GRINDS: usize = 20;

/// The connected grinder's state in the core.
#[derive(Debug, Clone, Default)]
pub(crate) struct GrinderSlot {
    driver: Option<GrinderDriver>,
    tracker: GrindTracker,
    recent: VecDeque<GrindSession>,
}

/// What a scanned or connected device is, across every kind: scales first
/// (their registry order is load-bearing), then grinders.
#[must_use]
pub fn identify_device(
    advertised_name: &str,
    discovered_services: &[String],
) -> Option<DeviceMatch> {
    if let Some(scale) = Scale::identify(advertised_name, discovered_services) {
        return Some(DeviceMatch {
            kind: DeviceKind::Scale,
            model: scale.label().to_owned(),
        });
    }
    GrinderDriver::identify(advertised_name, discovered_services).map(|g| DeviceMatch {
        kind: DeviceKind::Grinder,
        model: g.model().to_owned(),
    })
}

/// The pre-connect scan filters for one kind (`Some`) or for every kind
/// (`None`, Android's one scanner). Web asks per kind, so the Grinder tab's
/// chooser lists no scales.
#[must_use]
pub fn device_scan_filters(kind: Option<DeviceKind>) -> ScanFilters {
    let mut filters = ScanFilters::default();
    if matches!(kind, None | Some(DeviceKind::Scale)) {
        let scales = Scale::scan_uuids();
        filters.extend(&scales.name_prefixes, &scales.service_uuids);
    }
    if matches!(kind, None | Some(DeviceKind::Grinder)) {
        let grinders = ScanFilters::of(GRINDERS);
        filters.extend(&grinders.name_prefixes, &grinders.service_uuids);
    }
    filters
}

impl CremaCore {
    /// Identify and connect a grinder from its advertised name and the GATT
    /// services the shell discovered. Returns its model id, or `None` when
    /// nothing matched. A second connect replaces the first (one grinder at a
    /// time); a grind it had running is dropped, unreported.
    pub fn connect_grinder(
        &mut self,
        advertised_name: &str,
        discovered_services: &[String],
    ) -> Option<String> {
        let driver = GrinderDriver::identify(advertised_name, discovered_services);
        let model = driver.as_ref().map(|d| d.model().to_owned());
        self.grinder.driver = driver;
        self.grinder.tracker = GrindTracker::new();
        model
    }

    /// The shell subscribed to the grinder's notify characteristic: the
    /// connect writes (the Motto's handshake) as `Command::WriteGrinder`, to
    /// write with response in order. `max_packet` is `min(MTU - 3, 512)`, or
    /// 20 when the MTU is unknown (web). Starts the first-reading deadline.
    pub fn grinder_subscribed(&mut self, max_packet: u32, now_ms: u64) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(driver) = &mut self.grinder.driver {
            let max_packet = usize::try_from(max_packet).unwrap_or(usize::MAX);
            for data in driver.connect_writes(max_packet, now_ms) {
                out.commands.push(Command::WriteGrinder { data });
            }
        }
        self.gate_read_only(out)
    }

    /// The grinder link dropped (or the user disconnected): drop the driver.
    /// A grind still running ends as `LinkLost` and is reported.
    pub fn disconnect_grinder(&mut self) -> CoreOutput {
        let mut out = CoreOutput::default();
        self.grinder.driver = None;
        if let Some(session) = self.grinder.tracker.link_lost() {
            self.complete_grind(session, &mut out);
        }
        out
    }

    /// The connected grinder's GATT UUIDs. `None` with no grinder.
    #[must_use]
    pub fn grinder_uuids(&self) -> Option<GrinderUuids> {
        self.grinder.driver.as_ref().map(GrinderDriver::uuids)
    }

    /// What the connected grinder reports and accepts. `None` with no grinder.
    #[must_use]
    pub fn grinder_capabilities(&self) -> Option<GrinderCapabilities> {
        self.grinder
            .driver
            .as_ref()
            .map(GrinderDriver::capabilities)
    }

    /// The last [`RECENT_GRINDS`] completed grinds and purges, oldest first.
    /// In memory only: a grind reaches storage through a shot.
    #[must_use]
    pub fn recent_grinds(&self) -> &VecDeque<GrindSession> {
        &self.grinder.recent
    }

    fn complete_grind(&mut self, session: GrindSession, out: &mut CoreOutput) {
        if self.grinder.recent.len() == RECENT_GRINDS {
            self.grinder.recent.pop_front();
        }
        self.grinder.recent.push_back(session.clone());
        out.events.push(Event::GrindCompleted { session });
    }

    /// Record a grinder notification in the capture, redacted by its driver.
    /// With no driver there is nothing to redact with, so nothing is kept.
    pub(crate) fn capture_grinder(&mut self, data: &[u8], now_ms: u64) {
        if let Some(driver) = &mut self.grinder.driver {
            for frame in driver.redact_for_capture(data, now_ms) {
                self.capture.record(Source::GrinderNotify, &frame, now_ms);
            }
        }
    }

    pub(crate) fn handle_grinder_notify(&mut self, data: &[u8], now_ms: u64, out: &mut CoreOutput) {
        let events = match &mut self.grinder.driver {
            Some(driver) => driver.on_notification(data, now_ms),
            None => return,
        };
        self.map_grinder_events(events, out);
    }

    pub(crate) fn tick_grinder(&mut self, now_ms: u64, out: &mut CoreOutput) {
        let events = match &mut self.grinder.driver {
            Some(driver) => driver.tick(now_ms),
            None => return,
        };
        self.map_grinder_events(events, out);
    }

    fn map_grinder_events(&mut self, events: Vec<GrinderEvent>, out: &mut CoreOutput) {
        for event in events {
            match event {
                GrinderEvent::Ready { info } => out.events.push(Event::GrinderReady { info }),
                GrinderEvent::Reading(reading) => {
                    let completed = self.grinder.tracker.on_reading(&reading);
                    out.events.push(Event::GrinderReading { reading });
                    if let Some(session) = completed {
                        self.complete_grind(session, out);
                    }
                }
                GrinderEvent::Info(info) => out.events.push(Event::GrinderInfoChanged { info }),
                GrinderEvent::Unsupported {
                    reason: UnsupportedReason::FirmwareTooOld { found, min },
                } => out.events.push(Event::GrinderUnsupported {
                    firmware: found,
                    minimum: min,
                }),
                GrinderEvent::LinkDead { reason } => {
                    out.events.push(Event::GrinderLinkDead { reason });
                }
                // A driver event this core doesn't surface yet.
                _ => {}
            }
        }
    }
}

#[cfg(test)]
mod tests;
