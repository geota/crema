//! A small simulator of the DE1, the cup, the scale, the link and the shell's
//! write lanes, for driving the real [`CremaCore`](de1_app::CremaCore) through
//! its public API. See `.scratch/machine-control/DESIGN.md` §7.
//!
//! The simulator is parameterised ([`De1Params`]) because the machine's
//! behaviour isn't fully known yet: whether `Ending` appears, how late, and
//! whether Preinfusion is reported all vary. [`World`] runs one simulated
//! session on a deterministic 100 ms clock and checks the invariants in
//! [`invariants`] as it goes. There are no threads and no wall clock.
//!
//! Most of the design's invariants need the session and trust types that later
//! PRs add, or don't hold yet: [`Invariant::status`] says which are enforced
//! today and which PR turns the rest on.

pub mod invariants;
pub mod sim;
pub mod world;

pub use invariants::{Invariant, Status, Violation};
pub use sim::{De1Params, Ending, ScaleMode, SimCup, SimDe1, SimLane, SimLink, SimScale};
pub use world::{Coverage, Op, World};
