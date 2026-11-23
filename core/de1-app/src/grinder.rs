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
use de1_domain::shot_grinder::{
    GRIND_ATTRIBUTION_WINDOW_MS, ObservationSource, ShotGrinderObservation,
};
use de1_grinder::{
    GRINDERS, GrindSession, GrindTracker, GrinderCapabilities, GrinderDriver, GrinderEvent,
    GrinderInfo, GrinderReading, GrinderUuids, PresetSlot, RunKind, UnsupportedReason,
};
use de1_scale::Scale;

use crate::CremaCore;
use crate::event::{Command, CoreOutput, Event, Source};

mod follow;
use follow::{FollowInputs, Steadier, follow_status};
pub use follow::{FollowStatus, GrinderFollowView, STABLE_READINGS};

/// How many completed grinds and purges the core keeps.
pub const RECENT_GRINDS: usize = 20;

/// The connected grinder's state in the core.
#[derive(Debug, Clone, Default)]
pub(crate) struct GrinderSlot {
    driver: Option<GrinderDriver>,
    tracker: GrindTracker,
    recent: VecDeque<GrindSession>,
    /// A reading arrived on this connection.
    live: bool,
    /// The driver trusts its reading as the grind (`physical`), fixed per
    /// connection from its capabilities.
    trusted: bool,
    /// What the grinder said about itself, kept across a drop for the shot
    /// observation.
    info: Option<GrinderInfo>,
    /// The latest reading on this connection.
    last_reading: Option<GrinderReading>,
    /// The grinder record the connected grinder is linked to (the shell's,
    /// per device; owner decision 7).
    linked: Option<String>,
    /// "Pause following" for this session (owner decision 3).
    paused: bool,
    /// A grinder was live this session.
    seen: bool,
    steadier: Steadier,
    /// The follow view last reported to the shells.
    view: GrinderFollowView,
    /// The (grind, Brew's grind then) a `FollowGrind` last asked for, so it
    /// isn't repeated at 5 Hz while the shell applies it.
    asked: Option<(String, Option<String>)>,
    /// The next follow value is a reconnect's: announce it if it changes
    /// Brew's grind (§5.2).
    announce: bool,
    /// The end time of the grind the last kept shot used: no second shot
    /// gets it.
    used_grind_ended_at: Option<u64>,
    /// The last shot taken with a grind, so a shell that discards it can
    /// give the grind back (review #554 S2).
    taken: Option<TakenGrind>,
}

/// A grind a taken shot claimed.
#[derive(Debug, Clone, PartialEq, Eq)]
struct TakenGrind {
    shot_id: String,
    ended_at: u64,
    /// What was used before this shot claimed it.
    before: Option<u64>,
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
        self.grinder.trusted = driver
            .as_ref()
            .and_then(|d| d.capabilities().setting)
            .is_some_and(|s| s.physical);
        self.grinder.driver = driver;
        self.grinder.tracker = GrindTracker::new();
        self.grinder.live = false;
        self.grinder.last_reading = None;
        self.grinder.steadier.relink();
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
        self.grinder.live = false;
        self.grinder.last_reading = None;
        self.grinder.steadier.relink();
        if let Some(session) = self.grinder.tracker.link_lost() {
            self.complete_grind(session, &mut out);
        }
        self.drive_follow(&mut out);
        out
    }

    /// The grinder record the connected grinder is linked to (the shell's
    /// per-device link, §6.1; `None` = not linked). Follow mode needs it to
    /// match Brew's active grinder, and a shot's observation is stamped only
    /// on a shot of that grinder.
    pub fn set_grinder_link(&mut self, grinder_id: Option<String>) -> CoreOutput {
        let mut out = CoreOutput::default();
        self.grinder.linked = grinder_id.filter(|id| !id.trim().is_empty());
        self.grinder.asked = None;
        self.drive_follow(&mut out);
        out
    }

    /// "Pause following for this session" (owner decision 3), or resume.
    /// Resuming takes the grinder's value again.
    pub fn set_grinder_follow_paused(&mut self, paused: bool) -> CoreOutput {
        let mut out = CoreOutput::default();
        self.grinder.paused = paused;
        self.grinder.asked = None;
        self.drive_follow(&mut out);
        out
    }

    /// Follow mode now (§5): what Brew's Grind cell shows.
    #[must_use]
    pub fn grinder_follow(&self) -> GrinderFollowView {
        self.follow_view()
    }

    /// The grinder record whose grind the collar sets: the linked one, while
    /// its grinder is live, trusted and not paused, whether or not it is the
    /// active grinder yet. A bag pick or grinder switch onto it leaves Brew's
    /// grind alone (`BagSelectInput::followed_grinder_id`, §5.3).
    #[must_use]
    pub fn followed_grinder_id(&self) -> Option<String> {
        let g = &self.grinder;
        (g.live && g.trusted && !g.paused)
            .then(|| g.linked.clone())
            .flatten()
    }

    fn follow_view(&self) -> GrinderFollowView {
        let g = &self.grinder;
        let status = follow_status(FollowInputs {
            linked: g.linked.as_deref(),
            active: self
                .shot_selections
                .grinder_id
                .as_deref()
                .filter(|a| !a.trim().is_empty()),
            live: g.live,
            seen: g.seen,
            trusted: g.trusted,
            paused: g.paused,
            steady: g.steadier.steady_this_link,
        });
        let off = status == FollowStatus::Off;
        GrinderFollowView {
            status,
            grinder_id: if off { None } else { g.linked.clone() },
            grind: if off { None } else { g.steadier.steady.clone() },
            moving: (g.live && !off).then(|| g.steadier.moving()),
            grind_rpm: g
                .last_reading
                .as_ref()
                .and_then(|r| r.grind_rpm)
                .filter(|_| !off),
        }
    }

    /// Report a changed follow view, and ask the shell to set Brew's grind
    /// when following and it differs (§5.2, §5.4).
    pub(crate) fn drive_follow(&mut self, out: &mut CoreOutput) {
        let view = self.follow_view();
        if view.status == FollowStatus::Following
            && let Some(grind) = view.grind.clone()
        {
            let brew = self.shot_selections.grind.clone();
            let same = brew
                .as_deref()
                .is_some_and(|b| de1_domain::same_grind(b, &grind));
            if !same {
                let ask = (grind.clone(), brew.clone());
                if self.grinder.asked.as_ref() != Some(&ask) {
                    out.events.push(Event::FollowGrind {
                        grind,
                        announce: self.grinder.announce && brew.is_some(),
                    });
                    self.grinder.asked = Some(ask);
                }
            }
            self.grinder.announce = false;
        }
        if view != self.grinder.view {
            self.grinder.view = view.clone();
            out.events
                .push(Event::GrinderFollowChanged { follow: view });
        }
    }

    /// What the connected grinder reported for a shot starting at
    /// `start_ms` (§4.2), for the shot of grinder record `shot_grinder`:
    /// 1. the newest completed grind (never a purge, decision 9) that ended
    ///    within 10 minutes before the start (decision 8) and that no earlier
    ///    shot used; else
    /// 2. the reading now, when the grinder is live; else nothing.
    ///
    /// Only for a shot of the grinder record the grinder is linked to: a
    /// shot on another grinder gets nothing (it wasn't ground on this one).
    ///
    /// Choosing doesn't use the grind up: the shot claims it when it is taken
    /// ([`Self::claim_taken_grind`]), and a shell that discards the shot gives
    /// it back ([`Self::release_shot_grind`]). The second value is the grind's
    /// end time, to claim with.
    pub(crate) fn grinder_observation_for_shot(
        &mut self,
        start_ms: u64,
        shot_grinder: Option<&str>,
    ) -> (Option<ShotGrinderObservation>, Option<u64>) {
        let observed = self.grinder_observation_inner(start_ms, shot_grinder);
        let ended_at = observed.as_ref().and_then(|(_, at)| *at);
        (observed.map(|(o, _)| o), ended_at)
    }

    fn grinder_observation_inner(
        &self,
        start_ms: u64,
        shot_grinder: Option<&str>,
    ) -> Option<(ShotGrinderObservation, Option<u64>)> {
        let g = &self.grinder;
        let linked = g.linked.as_deref()?;
        if shot_grinder != Some(linked) {
            return None;
        }
        let model = g.info.as_ref().map(|i| i.model.clone());
        let firmware = g.info.as_ref().and_then(|i| i.firmware.clone());
        let grind = g
            .recent
            .iter()
            .rev()
            .find(|s| s.kind == RunKind::Grind)
            .filter(|s| {
                s.ended_at <= start_ms
                    && start_ms - s.ended_at <= GRIND_ATTRIBUTION_WINDOW_MS
                    && g.used_grind_ended_at != Some(s.ended_at)
            });
        if let Some(s) = grind {
            return Some((
                ShotGrinderObservation {
                    source: ObservationSource::LastGrind,
                    model,
                    firmware,
                    setting: s.setting.as_ref().map(|v| v.value.clone()),
                    grind_rpm: s.grind_rpm,
                    feed_rpm: s.feed_rpm,
                    grind_ms: Some(s.duration_ms),
                    ended_before_shot_ms: u32::try_from(start_ms - s.ended_at).ok(),
                    preset: s.preset.map(preset_number),
                    humidity_pct: s.humidity_pct,
                },
                Some(s.ended_at),
            ));
        }
        if !g.live {
            return None;
        }
        let r = g.last_reading.as_ref()?;
        Some((
            ShotGrinderObservation {
                source: ObservationSource::AtShotStart,
                model,
                firmware,
                setting: r.setting.as_ref().map(|v| v.value.clone()),
                grind_rpm: r.grind_rpm,
                feed_rpm: r.feed_rpm,
                grind_ms: None,
                ended_before_shot_ms: None,
                preset: r.preset.map(preset_number),
                humidity_pct: r.humidity_pct,
            },
            None,
        ))
    }

    /// A taken shot (`shot_id`) claims the grind it was stamped with
    /// (`ended_at`): no later shot gets it.
    pub(crate) fn claim_taken_grind(&mut self, shot_id: &str, ended_at: u64) {
        let g = &mut self.grinder;
        g.taken = Some(TakenGrind {
            shot_id: shot_id.to_owned(),
            ended_at,
            before: g.used_grind_ended_at,
        });
        g.used_grind_ended_at = Some(ended_at);
    }

    /// The shell discarded shot `shot_id` (an aborted pull, a cleaning run):
    /// the grind it claimed goes back, so the next shot within the window
    /// gets it (review #554 S2). A no-op for any other shot.
    pub fn release_shot_grind(&mut self, shot_id: &str) {
        let g = &mut self.grinder;
        if let Some(t) = &g.taken
            && t.shot_id == shot_id
            && g.used_grind_ended_at == Some(t.ended_at)
        {
            g.used_grind_ended_at = t.before;
        }
    }

    /// The shell kept shot `shot_id` after all (the discard's Undo): it
    /// claims its grind again. A no-op for any other shot.
    pub fn claim_shot_grind(&mut self, shot_id: &str) {
        let g = &mut self.grinder;
        if let Some(t) = &g.taken
            && t.shot_id == shot_id
        {
            g.used_grind_ended_at = Some(t.ended_at);
        }
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
                GrinderEvent::Ready { info } => {
                    // A grinder seen before this session reconnected: its
                    // value replaces Brew's, announced if it changed (§5.2).
                    self.grinder.announce = self.grinder.seen;
                    self.grinder.live = true;
                    self.grinder.seen = true;
                    self.grinder.asked = None;
                    self.grinder.info = Some(info.clone());
                    out.events.push(Event::GrinderReady { info });
                }
                GrinderEvent::Reading(reading) => {
                    let completed = self.grinder.tracker.on_reading(&reading);
                    if let Some(setting) = &reading.setting {
                        self.grinder.steadier.feed(&setting.value);
                    }
                    self.grinder.last_reading = Some(reading.clone());
                    out.events.push(Event::GrinderReading { reading });
                    if let Some(session) = completed {
                        self.complete_grind(session, out);
                    }
                    self.drive_follow(out);
                }
                GrinderEvent::Info(info) => {
                    self.grinder.info = Some(info.clone());
                    out.events.push(Event::GrinderInfoChanged { info });
                }
                GrinderEvent::Unsupported {
                    reason: UnsupportedReason::FirmwareTooOld { found, min },
                } => out.events.push(Event::GrinderUnsupported {
                    firmware: found,
                    minimum: min,
                }),
                GrinderEvent::LinkDead { reason } => {
                    self.grinder.live = false;
                    out.events.push(Event::GrinderLinkDead { reason });
                    self.drive_follow(out);
                }
                // A driver event this core doesn't surface yet.
                _ => {}
            }
        }
    }
}

/// A preset slot as the shot stores it: `-1` = manual mode.
fn preset_number(slot: PresetSlot) -> i8 {
    match slot {
        PresetSlot::Manual => -1,
        PresetSlot::Slot { index } => i8::try_from(index).unwrap_or(i8::MAX),
    }
}

#[cfg(test)]
mod tests;
