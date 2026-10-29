//! Calibration-write guards: per-sensor value bands, the zero / non-finite
//! checks, and the Sleep / Idle machine-state gate that
//! [`CremaCore::write_calibration`](crate::CremaCore::write_calibration) and
//! [`CremaCore::reset_calibration_to_factory`](crate::CremaCore::reset_calibration_to_factory)
//! apply before anything reaches the wire.
//!
//! A refused write emits [`Event::CalibrationRefused`](crate::Event::CalibrationRefused)
//! and no command. The same event reports the firmware's own refusal: a
//! calibration reply whose target byte is 255 (pyDE1 `CalTargets.CalError`,
//! "return value indicating bad request").
//!
//! - **Bands** (`docs/18-calibration-safety.md` §2): both the reported and
//!   the measured value must lie inside the sensor's band — flow 0.1–8 ml/s,
//!   pressure 2–10 bar, temperature 80–100 °C. They catch order-of-magnitude
//!   typos and unit confusion (PSI for bar), not honest small corrections.
//! - **Zero / non-finite** (reaprime `De1Calibration.validateForWrite`):
//!   NaN or ±∞ is refused for every sensor (S32P16 encodes NaN as 0), and a
//!   reported value that encodes as zero is refused for flow and pressure,
//!   whose calibration is a ratio (pyDE1 `c_api.py`: flow and pressure are
//!   "ratiometric", temperature is "differential, degrees C").
//! - **State gate** (`docs/18` §3): de1app only reaches its calibration pages
//!   while the machine is off (`page_to_show_when_off calibrate`); the
//!   firmware re-reads its calibration on every sample, so a write mid-flow
//!   can corrupt a live control loop. Writes and factory resets are admitted
//!   only in Sleep or Idle, and refused before any state is known.

use de1_protocol::{CalTarget, MachineState};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// Allowed flow calibration values, ml/s (de1app clamps entry to 0–8).
pub const FLOW_RANGE: (f32, f32) = (0.1, 8.0);
/// Allowed pressure calibration values, bar (de1app clamps entry to 0–12).
pub const PRESSURE_RANGE: (f32, f32) = (2.0, 10.0);
/// Allowed temperature calibration values, °C (de1app clamps entry to 0–105).
pub const TEMPERATURE_RANGE: (f32, f32) = (80.0, 100.0);

/// The `(min, max)` band both calibration values must lie in for `target`.
#[must_use]
pub const fn calibration_range(target: CalTarget) -> (f32, f32) {
    match target {
        CalTarget::Flow => FLOW_RANGE,
        CalTarget::Pressure => PRESSURE_RANGE,
        CalTarget::Temperature => TEMPERATURE_RANGE,
    }
}

/// Why a calibration write or reset was refused.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum CalibrationRefusal {
    /// A value was NaN or infinite.
    NonFinite,
    /// The reported value encodes as zero on a ratio sensor (flow, pressure).
    ZeroReported,
    /// A value fell outside the sensor's band.
    OutOfRange {
        /// The rejected value.
        value: f32,
        /// Band minimum.
        min: f32,
        /// Band maximum.
        max: f32,
    },
    /// The machine is not in Sleep or Idle.
    MachineNotIdle {
        /// The state the machine last reported.
        state: MachineState,
    },
    /// No machine state has been reported yet (fail closed).
    MachineStateUnknown,
    /// The DE1 answered the request with its calibration error reply
    /// (target byte 255).
    FirmwareRejected,
}

/// The Sleep / Idle gate shared by writes and factory resets.
///
/// # Errors
///
/// [`CalibrationRefusal::MachineStateUnknown`] or
/// [`CalibrationRefusal::MachineNotIdle`].
pub fn check_state(state: Option<MachineState>) -> Result<(), CalibrationRefusal> {
    match state {
        None => Err(CalibrationRefusal::MachineStateUnknown),
        Some(MachineState::Sleep | MachineState::Idle) => Ok(()),
        Some(state) => Err(CalibrationRefusal::MachineNotIdle { state }),
    }
}

/// The value checks for a calibration write (state not included).
///
/// # Errors
///
/// The first failing [`CalibrationRefusal`] in the order non-finite, zero
/// reported (flow / pressure), out of range.
pub fn check_values(
    target: CalTarget,
    reported: f32,
    measured: f32,
) -> Result<(), CalibrationRefusal> {
    if !reported.is_finite() || !measured.is_finite() {
        return Err(CalibrationRefusal::NonFinite);
    }
    // reaprime: `(de1ReportedValue * 65536).round() == 0` for flow/pressure.
    if target != CalTarget::Temperature && (reported * 65_536.0).round() == 0.0 {
        return Err(CalibrationRefusal::ZeroReported);
    }
    let (min, max) = calibration_range(target);
    for value in [reported, measured] {
        if !(min..=max).contains(&value) {
            return Err(CalibrationRefusal::OutOfRange { value, min, max });
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn non_finite_values_are_refused_for_every_sensor() {
        for target in [CalTarget::Flow, CalTarget::Pressure, CalTarget::Temperature] {
            assert_eq!(
                check_values(target, f32::NAN, 1.0),
                Err(CalibrationRefusal::NonFinite)
            );
            assert_eq!(
                check_values(target, 1.0, f32::INFINITY),
                Err(CalibrationRefusal::NonFinite)
            );
        }
    }

    #[test]
    fn a_zero_reported_value_is_refused_for_ratio_sensors_only() {
        assert_eq!(
            check_values(CalTarget::Flow, 0.0, 2.0),
            Err(CalibrationRefusal::ZeroReported)
        );
        assert_eq!(
            check_values(CalTarget::Pressure, 0.000_001, 9.0),
            Err(CalibrationRefusal::ZeroReported),
            "encodes as zero in S32P16"
        );
        // Temperature is an offset: a zero is just out of band, not a ratio fault.
        assert!(matches!(
            check_values(CalTarget::Temperature, 0.0, 92.0),
            Err(CalibrationRefusal::OutOfRange { .. })
        ));
    }

    #[test]
    fn values_outside_the_band_are_refused() {
        // PSI typed for bar.
        assert_eq!(
            check_values(CalTarget::Pressure, 9.0, 130.0),
            Err(CalibrationRefusal::OutOfRange {
                value: 130.0,
                min: 2.0,
                max: 10.0
            })
        );
        assert!(check_values(CalTarget::Flow, 9.0, 2.0).is_err());
        assert!(check_values(CalTarget::Temperature, 92.5, 101.0).is_err());
    }

    #[test]
    fn values_inside_the_band_pass() {
        assert_eq!(check_values(CalTarget::Flow, 2.0, 2.1), Ok(()));
        assert_eq!(check_values(CalTarget::Pressure, 9.0, 8.8), Ok(()));
        assert_eq!(check_values(CalTarget::Temperature, 92.5, 93.0), Ok(()));
    }

    #[test]
    fn only_sleep_and_idle_pass_the_state_gate() {
        assert_eq!(check_state(Some(MachineState::Sleep)), Ok(()));
        assert_eq!(check_state(Some(MachineState::Idle)), Ok(()));
        assert_eq!(
            check_state(None),
            Err(CalibrationRefusal::MachineStateUnknown)
        );
        for state in [
            MachineState::Espresso,
            MachineState::Steam,
            MachineState::HotWater,
            MachineState::Descale,
            MachineState::Refill,
        ] {
            assert_eq!(
                check_state(Some(state)),
                Err(CalibrationRefusal::MachineNotIdle { state })
            );
        }
    }
}
