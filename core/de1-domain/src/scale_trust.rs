//! Whether a scale's weight can stop a session (`.scratch/machine-control/DESIGN.md`
//! §3.4).
//!
//! Trust has two dimensions. [`ScaleLink`] says whether the link is
//! delivering, and [`Baseline`] says whether the session's zero is known.
//! [`weight_authority`] combines them. [`ScaleWatch`] keeps the link's facts
//! and emits the stale and dead episodes once each.
//!
//! Sans-IO: nothing here holds a clock. The caller passes a monotonic `now`.

use std::time::Duration;

use crate::session::{Baseline, Blocked, LastTrusted};

/// A connected scale with no reading for this long is stale.
pub const STALE_AFTER: Duration = Duration::from_secs(1);

/// With no sign of life for this long, a scale's link is presumed dead: the
/// 1 s stale timeout plus a 5 s grace, so a stream that only hiccuped resumes
/// before anything is torn down. From connect for a scale that never reported.
pub const DEAD_AFTER: Duration = Duration::from_secs(6);

/// Consecutive failed scale writes, with no reading in between, that presume
/// the link dead.
pub const MAX_WRITE_FAILURES: u32 = 3;

/// What the scale link is doing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScaleLink {
    /// No scale connected.
    Absent,
    /// Connected (or woken), no valid weight yet.
    Silent {
        /// Since when.
        since: Duration,
    },
    /// A valid weight within [`STALE_AFTER`].
    Fresh {
        /// The last valid weight.
        last_reading: Duration,
    },
    /// No valid weight for over [`STALE_AFTER`].
    Stale {
        /// The last valid weight.
        last_reading: Duration,
    },
    /// No sign of life for [`DEAD_AFTER`], or [`MAX_WRITE_FAILURES`] failed
    /// writes.
    Dead {
        /// The last valid weight, if there was one.
        last_reading: Option<Duration>,
    },
    /// A Half Decent Scale in SoftSleep: silent by design.
    Dormant,
}

impl ScaleLink {
    /// A valid weight arrived within [`STALE_AFTER`].
    #[must_use]
    pub const fn is_fresh(self) -> bool {
        matches!(self, ScaleLink::Fresh { .. })
    }

    /// The link is gone for good, not on a blip: dead, or no scale at all.
    #[must_use]
    pub const fn is_gone(self) -> bool {
        matches!(self, ScaleLink::Dead { .. } | ScaleLink::Absent)
    }
}

/// What the weight may do for the session.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum WeightAuthority {
    /// Weight can stop the session.
    Trusted,
    /// May yet become trusted: the tare is pending, or the first reading.
    Pending,
    /// Flow had reached the cup and the scale can't speak for it now: the
    /// estimate finishes from the last trusted weight.
    Lost(LastTrusted),
    /// The scale went quiet before flow reached the cup, or was disturbed
    /// then.
    Blind,
    /// Weight can't be used for this session.
    Refused(Blocked),
}

/// The authority for an open session (`DESIGN.md` §3.4's table). `flowing`:
/// the session's flow has started.
#[must_use]
pub fn weight_authority(baseline: Baseline, link: ScaleLink, flowing: bool) -> WeightAuthority {
    match baseline {
        Baseline::Refused {
            last: Some(last), ..
        } => WeightAuthority::Lost(last),
        Baseline::Refused {
            why: Blocked::ScaleDisturbed,
            last: None,
        } => WeightAuthority::Blind,
        Baseline::Refused { why, last: None } => WeightAuthority::Refused(why),
        Baseline::Trusted(last) => match link {
            ScaleLink::Fresh { .. } => WeightAuthority::Trusted,
            _ => WeightAuthority::Lost(last),
        },
        Baseline::Unknown => match link {
            ScaleLink::Fresh { .. } | ScaleLink::Silent { .. } | ScaleLink::Dormant => {
                WeightAuthority::Pending
            }
            ScaleLink::Absent => WeightAuthority::Refused(Blocked::NoScale),
            ScaleLink::Stale { .. } | ScaleLink::Dead { .. } => {
                WeightAuthority::Refused(Blocked::ScaleSilent)
            }
        },
        Baseline::Tared => match link {
            ScaleLink::Fresh { .. } => WeightAuthority::Trusted,
            ScaleLink::Silent { .. } | ScaleLink::Dormant if !flowing => WeightAuthority::Pending,
            _ => WeightAuthority::Blind,
        },
    }
}

/// The authority a session would start with now, for the projection between
/// sessions: what arms if a shot started.
#[must_use]
pub fn prospective_authority(link: ScaleLink) -> WeightAuthority {
    match link {
        ScaleLink::Fresh { .. } => WeightAuthority::Trusted,
        other => weight_authority(Baseline::Unknown, other, false),
    }
}

/// A scale watch's episode events.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct WatchEvents {
    /// A reading stopped arriving: once per stale episode.
    pub stale: bool,
    /// Still nothing at [`DEAD_AFTER`]: once until a reading arrives.
    pub dead: bool,
}

/// The facts behind [`ScaleLink`].
#[derive(Debug, Clone, Copy, Default)]
pub struct ScaleWatch {
    /// When the scale connected or woke, if it's connected.
    silent_since: Option<Duration>,
    /// The last valid weight since then.
    last_reading: Option<Duration>,
    /// The last frame of any kind, which proves the link is alive.
    last_alive: Option<Duration>,
    /// Failed writes since the last reading.
    write_failures: u32,
    /// [`MAX_WRITE_FAILURES`] reached since the last reading.
    writes_dead: bool,
    /// The stale episode was reported.
    stale_reported: bool,
    /// A dead link was reported, and no reading has come since. Kept across
    /// a reconnect, so a scale that never reports isn't torn down every 6 s.
    dead_reported: bool,
}

impl ScaleWatch {
    /// A new watch with no scale.
    #[must_use]
    pub fn new() -> ScaleWatch {
        ScaleWatch::default()
    }

    /// A scale connected at `now`. The dead timeout starts here.
    pub fn connect(&mut self, now: Duration) {
        *self = ScaleWatch {
            silent_since: Some(now),
            dead_reported: self.dead_reported,
            ..ScaleWatch::default()
        };
    }

    /// The scale went away.
    pub fn disconnect(&mut self) {
        *self = ScaleWatch {
            dead_reported: self.dead_reported,
            ..ScaleWatch::default()
        };
    }

    /// The scale was asked to wake from sleep at `now`, or the core was
    /// rebuilt around it: it's silent until it reports again, and the dead
    /// timeout restarts.
    pub fn woken(&mut self, now: Duration) {
        if self.silent_since.is_some() {
            self.silent_since = Some(now);
            self.last_reading = None;
            self.last_alive = None;
            self.stale_reported = false;
        }
    }

    /// A valid weight at `now`.
    pub fn reading(&mut self, now: Duration) {
        if self.silent_since.is_none() {
            return;
        }
        self.last_reading = Some(now);
        self.last_alive = Some(now);
        self.write_failures = 0;
        self.writes_dead = false;
        self.stale_reported = false;
        self.dead_reported = false;
    }

    /// A frame with no valid weight (a unit-recovery frame): the link is
    /// alive, but the weight isn't fresh.
    pub fn alive(&mut self, now: Duration) {
        if self.silent_since.is_some() {
            self.last_alive = Some(now);
        }
    }

    /// A write the shell sent. Returns whether this failure made
    /// [`MAX_WRITE_FAILURES`] in a row, which marks the link dead and starts
    /// the count over.
    pub fn write_result(&mut self, ok: bool) -> bool {
        if self.silent_since.is_none() {
            return false;
        }
        if ok {
            self.write_failures = 0;
            return false;
        }
        self.write_failures += 1;
        if self.write_failures < MAX_WRITE_FAILURES {
            return false;
        }
        self.write_failures = 0;
        self.writes_dead = true;
        true
    }

    /// The link at `now`. `dormant`: the scale sleeps by design.
    #[must_use]
    pub fn link(&self, now: Duration, dormant: bool) -> ScaleLink {
        let Some(since) = self.silent_since else {
            return ScaleLink::Absent;
        };
        if dormant {
            return ScaleLink::Dormant;
        }
        if self.writes_dead {
            return ScaleLink::Dead {
                last_reading: self.last_reading,
            };
        }
        // The last frame proves the link alive; before any, the connect.
        let alive = self.last_alive.unwrap_or(since);
        let dead = now.saturating_sub(alive) >= DEAD_AFTER;
        match self.last_reading {
            Some(at) if now.saturating_sub(at) < STALE_AFTER => {
                ScaleLink::Fresh { last_reading: at }
            }
            Some(at) if dead => ScaleLink::Dead {
                last_reading: Some(at),
            },
            Some(at) => ScaleLink::Stale { last_reading: at },
            None if dead => ScaleLink::Dead { last_reading: None },
            None => ScaleLink::Silent { since },
        }
    }

    /// Check for a new episode at `now`: stale once per episode, dead once
    /// until a reading arrives. A link dead on failed writes is reported by
    /// [`write_result`](Self::write_result), not here.
    pub fn poll(&mut self, now: Duration, dormant: bool) -> WatchEvents {
        let mut events = WatchEvents::default();
        if self.writes_dead {
            return events;
        }
        match self.link(now, dormant) {
            ScaleLink::Stale { .. } if !self.stale_reported => {
                self.stale_reported = true;
                events.stale = true;
            }
            ScaleLink::Dead { last_reading } => {
                if last_reading.is_some() && !self.stale_reported {
                    self.stale_reported = true;
                    events.stale = true;
                }
                if !self.dead_reported {
                    self.dead_reported = true;
                    events.dead = true;
                }
            }
            _ => {}
        }
        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    #[test]
    fn a_reading_is_fresh_for_a_second_then_stale_then_dead() {
        let mut watch = ScaleWatch::new();
        watch.connect(ms(0));
        watch.reading(ms(1_000));
        assert!(watch.link(ms(1_999), false).is_fresh());
        assert_eq!(
            watch.link(ms(2_000), false),
            ScaleLink::Stale {
                last_reading: ms(1_000)
            }
        );
        assert_eq!(
            watch.link(ms(7_000), false),
            ScaleLink::Dead {
                last_reading: Some(ms(1_000))
            }
        );
    }

    #[test]
    fn a_scale_that_never_reports_is_silent_then_dead_from_connect() {
        let mut watch = ScaleWatch::new();
        assert_eq!(watch.link(ms(0), false), ScaleLink::Absent);
        watch.connect(ms(1_000));
        assert_eq!(
            watch.link(ms(6_999), false),
            ScaleLink::Silent { since: ms(1_000) }
        );
        assert_eq!(
            watch.link(ms(7_000), false),
            ScaleLink::Dead { last_reading: None }
        );
    }

    #[test]
    fn a_frame_with_no_weight_keeps_the_link_alive_but_not_fresh() {
        let mut watch = ScaleWatch::new();
        watch.connect(ms(0));
        watch.reading(ms(0));
        watch.alive(ms(5_000));
        assert_eq!(
            watch.link(ms(9_000), false),
            ScaleLink::Stale {
                last_reading: ms(0)
            }
        );
        assert!(matches!(
            watch.link(ms(11_000), false),
            ScaleLink::Dead { .. }
        ));
    }

    #[test]
    fn stale_and_dead_fire_once_each_and_dead_survives_a_reconnect() {
        let mut watch = ScaleWatch::new();
        watch.connect(ms(0));
        watch.reading(ms(0));
        assert_eq!(
            watch.poll(ms(1_000), false),
            WatchEvents {
                stale: true,
                dead: false
            }
        );
        assert_eq!(watch.poll(ms(2_000), false), WatchEvents::default());
        assert_eq!(
            watch.poll(ms(6_000), false),
            WatchEvents {
                stale: false,
                dead: true
            }
        );
        assert_eq!(watch.poll(ms(7_000), false), WatchEvents::default());
        // The shell reconnects; the scale still says nothing.
        watch.connect(ms(8_000));
        assert_eq!(watch.poll(ms(20_000), false), WatchEvents::default());
        // A reading re-arms both.
        watch.reading(ms(21_000));
        assert!(watch.poll(ms(22_000), false).stale);
        assert!(watch.poll(ms(27_000), false).dead);
    }

    #[test]
    fn three_failed_writes_are_dead_until_a_reading() {
        let mut watch = ScaleWatch::new();
        watch.connect(ms(0));
        watch.reading(ms(0));
        assert!(!watch.write_result(false));
        assert!(!watch.write_result(true), "a success starts the count over");
        assert!(!watch.write_result(false));
        assert!(!watch.write_result(false));
        assert!(watch.write_result(false));
        assert!(matches!(watch.link(ms(100), false), ScaleLink::Dead { .. }));
        watch.reading(ms(200));
        assert!(watch.link(ms(300), false).is_fresh());
    }

    #[test]
    fn a_sleeping_scale_is_dormant_and_waking_restarts_the_clock() {
        let mut watch = ScaleWatch::new();
        watch.connect(ms(0));
        watch.reading(ms(0));
        assert_eq!(watch.link(ms(60_000), true), ScaleLink::Dormant);
        assert_eq!(watch.poll(ms(60_000), true), WatchEvents::default());
        watch.woken(ms(60_000));
        assert_eq!(
            watch.link(ms(65_999), false),
            ScaleLink::Silent { since: ms(60_000) }
        );
    }

    #[test]
    fn the_authority_table() {
        use Baseline::{Tared, Unknown};
        use WeightAuthority::{Blind, Lost, Pending, Refused, Trusted};
        let last = LastTrusted {
            weight_g: 20.0,
            pour_ml: 30.0,
            at: ms(0),
        };
        let fresh = ScaleLink::Fresh {
            last_reading: ms(0),
        };
        let silent = ScaleLink::Silent { since: ms(0) };
        let stale = ScaleLink::Stale {
            last_reading: ms(0),
        };
        let dead = ScaleLink::Dead { last_reading: None };
        let cases = [
            (Unknown, fresh, true, Pending),
            (Unknown, silent, true, Pending),
            (Unknown, ScaleLink::Dormant, true, Pending),
            (Unknown, stale, true, Refused(Blocked::ScaleSilent)),
            (Unknown, dead, true, Refused(Blocked::ScaleSilent)),
            (Unknown, ScaleLink::Absent, true, Refused(Blocked::NoScale)),
            (Tared, fresh, true, Trusted),
            (Tared, silent, false, Pending),
            (Tared, silent, true, Blind),
            (Tared, ScaleLink::Dormant, true, Blind),
            (Tared, stale, false, Blind),
            (Tared, dead, true, Blind),
            (Tared, ScaleLink::Absent, true, Blind),
            (Baseline::Trusted(last), fresh, true, Trusted),
            (Baseline::Trusted(last), silent, true, Lost(last)),
            (
                Baseline::Trusted(last),
                ScaleLink::Dormant,
                true,
                Lost(last),
            ),
            (Baseline::Trusted(last), stale, true, Lost(last)),
            (Baseline::Trusted(last), dead, true, Lost(last)),
            (Baseline::Trusted(last), ScaleLink::Absent, true, Lost(last)),
        ];
        for (baseline, link, flowing, authority) in cases {
            assert_eq!(
                weight_authority(baseline, link, flowing),
                authority,
                "{baseline:?} {link:?} flowing {flowing}"
            );
        }
        let refused = Baseline::Refused {
            why: Blocked::BeganUnseen,
            last: None,
        };
        let rezeroed = Baseline::Refused {
            why: Blocked::ScaleRezeroed,
            last: Some(last),
        };
        let disturbed_early = Baseline::Refused {
            why: Blocked::ScaleDisturbed,
            last: None,
        };
        for link in [fresh, silent, stale, dead, ScaleLink::Absent] {
            assert_eq!(
                weight_authority(refused, link, true),
                Refused(Blocked::BeganUnseen)
            );
            assert_eq!(weight_authority(rezeroed, link, true), Lost(last));
            assert_eq!(weight_authority(disturbed_early, link, true), Blind);
        }
    }

    #[test]
    fn between_sessions_a_fresh_scale_would_arm_weight() {
        assert_eq!(
            prospective_authority(ScaleLink::Fresh {
                last_reading: ms(0)
            }),
            WeightAuthority::Trusted
        );
        assert_eq!(
            prospective_authority(ScaleLink::Silent { since: ms(0) }),
            WeightAuthority::Pending
        );
        assert_eq!(
            prospective_authority(ScaleLink::Absent),
            WeightAuthority::Refused(Blocked::NoScale)
        );
    }
}
