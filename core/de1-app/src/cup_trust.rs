//! Whether the cup's weight can stop the session, reading by reading
//! (`.scratch/machine-control/DESIGN.md` §3.4, §3.5): the gate that says flow
//! has reached the cup, the `last` trusted weight the estimate counts from, a
//! scale returning after a gap, and one disturbed mid-flow.
//!
//! The rules are the session's ([`de1_domain::session`]); this module feeds
//! them the core's readings.

use std::time::Duration;

use de1_domain::session::{
    ActiveMode, Baseline, Blocked, EMPTY_CUP_G, GATE_FLOW_G_S, GATE_HOLD, GATE_WEIGHT_G,
    LastTrusted,
};

use crate::{CremaCore, UntaredSettle};

/// `last` pairs a reading only with a sample under this old, or a fresh
/// weight would pair with a stale volume that jumps when samples return.
pub(crate) const LAST_SAMPLE_FRESH: Duration = Duration::from_secs(1);

/// A returning scale may read this far under `last`, grams.
pub(crate) const RETURN_BELOW_G: f32 = 3.0;

/// And this far over `last` plus twice the water poured since, grams.
pub(crate) const RETURN_ABOVE_G: f32 = 10.0;

impl CremaCore {
    /// The mode whose cup the scale weighs now: an espresso past first flow,
    /// or hot water past its pre-flow substates.
    pub(crate) fn weighing_mode(&self) -> Option<ActiveMode> {
        match self.session?.mode {
            ActiveMode::Espresso if self.flow_started.is_some() => Some(ActiveMode::Espresso),
            ActiveMode::HotWater if self.flow_running() => Some(ActiveMode::HotWater),
            _ => None,
        }
    }

    /// The first reading after a gap decides, once, on the raw net reading:
    /// within `last.weight − 3 g` and `last.weight + 2 × poured since + 10 g`
    /// it's adopted and weight is trusted again. Outside, the scale re-zeroed
    /// or something landed on it: weight is off for the session and the
    /// estimate continues.
    pub(crate) fn check_returning_scale(&mut self, net_g: f32) {
        let pour_ml = self.volume_integrator.pour_ml();
        let Some(session) = self.session.as_mut() else {
            return;
        };
        let Baseline::Trusted(last) = session.baseline else {
            return;
        };
        let poured = (pour_ml - last.pour_ml).max(0.0);
        let low = last.weight_g - RETURN_BELOW_G;
        let high = last.weight_g + 2.0 * poured + RETURN_ABOVE_G;
        if !(low..=high).contains(&net_g) {
            session.refuse(Blocked::ScaleRezeroed);
        }
    }

    /// The gate and `last`, on a reading the spike gate passed (`DESIGN.md`
    /// §3.4).
    ///
    /// - `Tared` becomes `Trusted` once weight is at least 5 g and scale flow
    ///   at least 0.5 g/s, held for 1 s: a bump can't open it.
    /// - `Trusted` refreshes `last` on each reading, but only beside a sample
    ///   under 1 s old, and falls back to `Tared` under 3 g.
    pub(crate) fn update_trust(&mut self, net_g: f32, flow_g_s: f32, now: Duration) {
        if self.weighing_mode().is_none() {
            self.gate_since = None;
            return;
        }
        let fresh_sample = self
            .last_sample_at
            .is_some_and(|at| now.saturating_sub(at) < LAST_SAMPLE_FRESH);
        let last = LastTrusted {
            weight_g: net_g,
            pour_ml: self.volume_integrator.pour_ml(),
            at: now,
        };
        let Some(session) = self.session.as_mut() else {
            return;
        };
        match session.baseline {
            Baseline::Tared => {
                if net_g >= GATE_WEIGHT_G && flow_g_s >= GATE_FLOW_G_S {
                    let since = *self.gate_since.get_or_insert(now);
                    if now.saturating_sub(since) >= GATE_HOLD && fresh_sample {
                        session.trusted(last);
                    }
                } else {
                    self.gate_since = None;
                }
            }
            Baseline::Trusted(_) if net_g < EMPTY_CUP_G => {
                session.emptied();
                self.gate_since = None;
            }
            Baseline::Trusted(_) if fresh_sample => session.trusted(last),
            _ => {}
        }
    }

    /// The step detector fired on a flowing session (`DESIGN.md` §3.5).
    ///
    /// - After the gate: weight is refused for the session and the estimate
    ///   finishes from `last`.
    /// - Before it, in espresso: the untared-cup settle tells a load at rest
    ///   from a hand. Returns `true`: the reading is held while it settles.
    /// - Before it, in hot water: `Blind`, so the `V` leg takes over.
    pub(crate) fn on_disturbed(
        &mut self,
        from_net: Option<f32>,
        raw_g: f32,
        now: Duration,
    ) -> bool {
        let mode = self.weighing_mode();
        let Some(session) = self.session.as_mut() else {
            return false;
        };
        match session.baseline {
            Baseline::Trusted(_) => {
                session.refuse(Blocked::ScaleDisturbed);
                false
            }
            Baseline::Tared if mode == Some(ActiveMode::Espresso) => {
                let Some(from) = from_net else {
                    return false;
                };
                self.untared_settle = Some(UntaredSettle {
                    started: now,
                    band_started: now,
                    band_min: raw_g,
                    band_max: raw_g,
                    band_count: 1,
                    disturbed_from: Some(from),
                });
                true
            }
            Baseline::Tared => {
                session.refuse(Blocked::ScaleDisturbed);
                false
            }
            _ => false,
        }
    }
}
