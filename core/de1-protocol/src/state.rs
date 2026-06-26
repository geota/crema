//! `StateInfo` packet (`cuuid_0E` / `A00E`) and the machine state enums.

use crate::error::ProtocolError;
use typeshare::typeshare;

/// DE1 top-level machine state. Discriminants match the firmware `MachineState`
/// enum (see protocol §4.1).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[repr(u8)]
pub enum MachineState {
    Sleep = 0,
    GoingToSleep = 1,
    Idle = 2,
    Busy = 3,
    Espresso = 4,
    Steam = 5,
    HotWater = 6,
    ShortCal = 7,
    SelfTest = 8,
    LongCal = 9,
    Descale = 10,
    FatalError = 11,
    Init = 12,
    /// A "no-op" value, used in RequestedState to mean "no change".
    NoRequest = 13,
    /// A command value: advance to the next espresso frame.
    SkipToNext = 14,
    HotWaterRinse = 15,
    SteamRinse = 16,
    Refill = 17,
    Clean = 18,
    InBootLoader = 19,
    AirPurge = 20,
    /// Scheduled-wake idle; firmware v1293 and later only.
    SchedIdle = 21,
}

impl MachineState {
    /// True for states in which water flows through the group head.
    pub fn is_flow_state(self) -> bool {
        matches!(
            self,
            MachineState::Espresso
                | MachineState::Steam
                | MachineState::HotWater
                | MachineState::HotWaterRinse
        )
    }
}

impl TryFrom<u8> for MachineState {
    type Error = ProtocolError;

    /// Convert a state byte from a packet.
    ///
    /// # Errors
    ///
    /// Returns [`ProtocolError::UnknownMachineState`] if `v` is not a value
    /// this crate recognises (e.g. a state added by newer firmware).
    fn try_from(v: u8) -> Result<Self, Self::Error> {
        use MachineState::*;
        Ok(match v {
            0 => Sleep,
            1 => GoingToSleep,
            2 => Idle,
            3 => Busy,
            4 => Espresso,
            5 => Steam,
            6 => HotWater,
            7 => ShortCal,
            8 => SelfTest,
            9 => LongCal,
            10 => Descale,
            11 => FatalError,
            12 => Init,
            13 => NoRequest,
            14 => SkipToNext,
            15 => HotWaterRinse,
            16 => SteamRinse,
            17 => Refill,
            18 => Clean,
            19 => InBootLoader,
            20 => AirPurge,
            21 => SchedIdle,
            other => return Err(ProtocolError::UnknownMachineState(other)),
        })
    }
}

/// DE1 substate. Discriminants match the firmware enum (see protocol §4.2):
/// `0..=20` are operational substates, `200..=217` are error codes.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[repr(u8)]
pub enum SubState {
    Ready = 0,
    Heating = 1,
    FinalHeating = 2,
    Stabilising = 3,
    Preinfusion = 4,
    Pouring = 5,
    Ending = 6,
    Steaming = 7,
    DescaleInit = 8,
    DescaleFillGroup = 9,
    DescaleReturn = 10,
    DescaleGroup = 11,
    DescaleSteam = 12,
    CleanInit = 13,
    CleanFillGroup = 14,
    CleanSoak = 15,
    CleanGroup = 16,
    Refill = 17,
    PausedSteam = 18,
    UserNotPresent = 19,
    Puffing = 20,
    /// Died with a NaN.
    ErrorNaN = 200,
    /// Died with an infinity.
    ErrorInf = 201,
    /// Generic firmware error.
    ErrorGeneric = 202,
    /// Accelerometer not responding or unlocked.
    ErrorAcc = 203,
    /// Temperature sensor error.
    ErrorTSensor = 204,
    /// Pressure sensor error.
    ErrorPSensor = 205,
    /// Water-level sensor error.
    ErrorWLevel = 206,
    /// DIP switches forced an error state.
    ErrorDip = 207,
    /// An assertion failed.
    ErrorAssertion = 208,
    /// An unsafe value was assigned.
    ErrorUnsafe = 209,
    /// An invalid parameter was supplied.
    ErrorInvalidParm = 210,
    /// External flash access error.
    ErrorFlash = 211,
    /// Out of memory.
    ErrorOom = 212,
    /// A realtime deadline was missed.
    ErrorDeadline = 213,
    /// Current out of bounds (too high).
    ErrorHiCurrent = 214,
    /// Not enough current.
    ErrorLoCurrent = 215,
    /// Boot pressure test failed (no water?).
    ErrorBootFill = 216,
    /// Front power switch is off.
    ErrorNoAc = 217,
}

impl SubState {
    /// True for the `Error_*` substates (firmware fault codes, `>= 200`).
    pub fn is_error(self) -> bool {
        (self as u8) >= 200
    }

    /// Human-readable English message for an error substate, or `None` for
    /// a healthy (non-error) one. Ported from the legacy de1app
    /// `de1plus/machine.tcl` substate table; the bare `Error_*`-style
    /// labels are expanded into one-line sentences. Locale-independent —
    /// every shell renders the same source-of-truth here so the Android
    /// shell inherits the table without porting it to Kotlin.
    #[must_use]
    pub fn error_message(self) -> Option<&'static str> {
        use SubState::{
            ErrorAcc, ErrorAssertion, ErrorBootFill, ErrorDeadline, ErrorDip, ErrorFlash,
            ErrorGeneric, ErrorHiCurrent, ErrorInf, ErrorInvalidParm, ErrorLoCurrent, ErrorNaN,
            ErrorNoAc, ErrorOom, ErrorPSensor, ErrorTSensor, ErrorUnsafe, ErrorWLevel,
        };
        Some(match self {
            ErrorNaN => "Firmware error: a calculation produced an invalid number (NaN).",
            ErrorInf => "Firmware error: a calculation produced an infinite value.",
            ErrorGeneric => "The machine reported a generic firmware error.",
            ErrorAcc => "Accelerometer not responding — the machine may have been moved or tipped.",
            ErrorTSensor => "A temperature sensor failed or is reading out of range.",
            ErrorPSensor => "The pressure sensor failed or is reading out of range.",
            ErrorWLevel => "The water-level sensor failed or is reading out of range.",
            ErrorDip => "The DIP switches forced the machine into an error state.",
            ErrorAssertion => "Firmware error: an internal assertion failed.",
            ErrorUnsafe => "Firmware error: an unsafe value was assigned.",
            ErrorInvalidParm => "Firmware error: an invalid parameter was supplied.",
            ErrorFlash => "The machine could not access its internal flash storage.",
            ErrorOom => "Firmware error: the machine ran out of memory.",
            ErrorDeadline => "Firmware error: a realtime deadline was missed.",
            ErrorHiCurrent => "Heater current too high — the machine shut down for safety.",
            ErrorLoCurrent => "Heater current too low — check the mains power supply.",
            ErrorBootFill => "The boot pressure test failed — the machine may be out of water.",
            ErrorNoAc => "The front power switch is off — turn the machine on at the switch.",
            _ => return None,
        })
    }

    /// The wire-side variant name (the same name the FFI / wasm bridge
    /// emits for serde): `Ready`, `ErrorTSensor`, …. Stable across
    /// shells; the shell `MACHINE_ERROR_TEXT` table keyed off this.
    #[must_use]
    pub fn variant_name(self) -> &'static str {
        use SubState::*;
        match self {
            Ready => "Ready",
            Heating => "Heating",
            FinalHeating => "FinalHeating",
            Stabilising => "Stabilising",
            Preinfusion => "Preinfusion",
            Pouring => "Pouring",
            Ending => "Ending",
            Steaming => "Steaming",
            DescaleInit => "DescaleInit",
            DescaleFillGroup => "DescaleFillGroup",
            DescaleReturn => "DescaleReturn",
            DescaleGroup => "DescaleGroup",
            DescaleSteam => "DescaleSteam",
            CleanInit => "CleanInit",
            CleanFillGroup => "CleanFillGroup",
            CleanSoak => "CleanSoak",
            CleanGroup => "CleanGroup",
            Refill => "Refill",
            PausedSteam => "PausedSteam",
            UserNotPresent => "UserNotPresent",
            Puffing => "Puffing",
            ErrorNaN => "ErrorNaN",
            ErrorInf => "ErrorInf",
            ErrorGeneric => "ErrorGeneric",
            ErrorAcc => "ErrorAcc",
            ErrorTSensor => "ErrorTSensor",
            ErrorPSensor => "ErrorPSensor",
            ErrorWLevel => "ErrorWLevel",
            ErrorDip => "ErrorDip",
            ErrorAssertion => "ErrorAssertion",
            ErrorUnsafe => "ErrorUnsafe",
            ErrorInvalidParm => "ErrorInvalidParm",
            ErrorFlash => "ErrorFlash",
            ErrorOom => "ErrorOom",
            ErrorDeadline => "ErrorDeadline",
            ErrorHiCurrent => "ErrorHiCurrent",
            ErrorLoCurrent => "ErrorLoCurrent",
            ErrorBootFill => "ErrorBootFill",
            ErrorNoAc => "ErrorNoAc",
        }
    }

    /// Look up the human-readable error message by the wire-side variant
    /// name a notification carries. Returns `None` for unknown / healthy
    /// substates so the shell renders nothing rather than guessing.
    #[must_use]
    pub fn error_message_for_name(name: &str) -> Option<&'static str> {
        use SubState::*;
        let sub = match name {
            "ErrorNaN" => ErrorNaN,
            "ErrorInf" => ErrorInf,
            "ErrorGeneric" => ErrorGeneric,
            "ErrorAcc" => ErrorAcc,
            "ErrorTSensor" => ErrorTSensor,
            "ErrorPSensor" => ErrorPSensor,
            "ErrorWLevel" => ErrorWLevel,
            "ErrorDip" => ErrorDip,
            "ErrorAssertion" => ErrorAssertion,
            "ErrorUnsafe" => ErrorUnsafe,
            "ErrorInvalidParm" => ErrorInvalidParm,
            "ErrorFlash" => ErrorFlash,
            "ErrorOom" => ErrorOom,
            "ErrorDeadline" => ErrorDeadline,
            "ErrorHiCurrent" => ErrorHiCurrent,
            "ErrorLoCurrent" => ErrorLoCurrent,
            "ErrorBootFill" => ErrorBootFill,
            "ErrorNoAc" => ErrorNoAc,
            _ => return None,
        };
        sub.error_message()
    }
}

impl TryFrom<u8> for SubState {
    type Error = ProtocolError;

    /// Convert a substate byte from a packet.
    ///
    /// # Errors
    ///
    /// Returns [`ProtocolError::UnknownSubState`] if `v` is not a value this
    /// crate recognises.
    fn try_from(v: u8) -> Result<Self, Self::Error> {
        use SubState::*;
        Ok(match v {
            0 => Ready,
            1 => Heating,
            2 => FinalHeating,
            3 => Stabilising,
            4 => Preinfusion,
            5 => Pouring,
            6 => Ending,
            7 => Steaming,
            8 => DescaleInit,
            9 => DescaleFillGroup,
            10 => DescaleReturn,
            11 => DescaleGroup,
            12 => DescaleSteam,
            13 => CleanInit,
            14 => CleanFillGroup,
            15 => CleanSoak,
            16 => CleanGroup,
            17 => Refill,
            18 => PausedSteam,
            19 => UserNotPresent,
            20 => Puffing,
            200 => ErrorNaN,
            201 => ErrorInf,
            202 => ErrorGeneric,
            203 => ErrorAcc,
            204 => ErrorTSensor,
            205 => ErrorPSensor,
            206 => ErrorWLevel,
            207 => ErrorDip,
            208 => ErrorAssertion,
            209 => ErrorUnsafe,
            210 => ErrorInvalidParm,
            211 => ErrorFlash,
            212 => ErrorOom,
            213 => ErrorDeadline,
            214 => ErrorHiCurrent,
            215 => ErrorLoCurrent,
            216 => ErrorBootFill,
            217 => ErrorNoAc,
            other => return Err(ProtocolError::UnknownSubState(other)),
        })
    }
}

/// Wire length of a [`StateInfo`] packet.
pub const STATE_INFO_LEN: usize = 2;

/// The DE1 state notification (`cuuid_0E` / `A00E`): a top-level state plus a
/// substate.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct StateInfo {
    /// Top-level machine state.
    pub state: MachineState,
    /// Substate within `state`.
    pub substate: SubState,
}

impl StateInfo {
    /// Parse a `StateInfo` notification. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// Returns [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`STATE_INFO_LEN`] bytes, or [`ProtocolError::UnknownMachineState`] /
    /// [`ProtocolError::UnknownSubState`] if a byte is unrecognised.
    ///
    /// # Examples
    ///
    /// ```
    /// # use de1_protocol::{MachineState, StateInfo, SubState};
    /// let info = StateInfo::parse(&[4, 5]).unwrap();
    /// assert_eq!(info.state, MachineState::Espresso);
    /// assert_eq!(info.substate, SubState::Pouring);
    /// ```
    pub fn parse(data: &[u8]) -> Result<StateInfo, ProtocolError> {
        if data.len() < STATE_INFO_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "StateInfo",
                expected: STATE_INFO_LEN,
                got: data.len(),
            });
        }
        Ok(StateInfo {
            state: MachineState::try_from(data[0])?,
            substate: SubState::try_from(data[1])?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    mod machine_state {
        use super::*;

        #[test]
        fn known_bytes_map_to_states() {
            assert_eq!(MachineState::try_from(0), Ok(MachineState::Sleep));
            assert_eq!(MachineState::try_from(4), Ok(MachineState::Espresso));
            assert_eq!(MachineState::try_from(21), Ok(MachineState::SchedIdle));
        }

        #[test]
        fn an_unknown_byte_is_an_error() {
            assert_eq!(
                MachineState::try_from(99),
                Err(ProtocolError::UnknownMachineState(99))
            );
        }

        #[test]
        fn flow_states_are_recognised() {
            assert!(MachineState::Espresso.is_flow_state());
            assert!(MachineState::HotWaterRinse.is_flow_state());
        }

        #[test]
        fn non_flow_states_are_not_recognised_as_flow() {
            assert!(!MachineState::Idle.is_flow_state());
            assert!(!MachineState::Sleep.is_flow_state());
        }
    }

    mod sub_state {
        use super::*;

        #[test]
        fn known_bytes_map_to_substates() {
            assert_eq!(SubState::try_from(5), Ok(SubState::Pouring));
            assert_eq!(SubState::try_from(217), Ok(SubState::ErrorNoAc));
        }

        #[test]
        fn an_unknown_byte_is_an_error() {
            assert_eq!(
                SubState::try_from(99),
                Err(ProtocolError::UnknownSubState(99))
            );
        }

        #[test]
        fn error_substates_are_classified_as_errors() {
            assert!(SubState::ErrorNaN.is_error());
            assert!(SubState::ErrorNoAc.is_error());
            assert!(!SubState::Pouring.is_error());
        }

        #[test]
        fn error_message_present_for_every_error_substate_and_none_for_healthy() {
            // Every byte the protocol recognises as an error must carry a
            // message — the shell relies on this for the error banner.
            for byte in 200u8..=217 {
                let sub = SubState::try_from(byte).expect("known error byte");
                assert!(
                    sub.error_message().is_some(),
                    "{sub:?} ({byte}) has no error message"
                );
            }
            // Healthy substates have no message; the shell renders no
            // banner in that case.
            for byte in 0u8..=20 {
                let sub = SubState::try_from(byte).expect("known healthy byte");
                assert!(sub.error_message().is_none(), "{sub:?} unexpected message");
            }
        }

        #[test]
        fn error_message_for_name_round_trips_through_variant_name() {
            for byte in 200u8..=217 {
                let sub = SubState::try_from(byte).unwrap();
                let by_name = SubState::error_message_for_name(sub.variant_name());
                assert_eq!(by_name, sub.error_message());
            }
            assert!(SubState::error_message_for_name("Pouring").is_none());
            assert!(SubState::error_message_for_name("BogusName").is_none());
        }
    }

    mod state_info {
        use super::*;

        #[test]
        fn parses_state_and_substate() {
            let info = StateInfo::parse(&[4, 4]).unwrap();
            assert_eq!(info.state, MachineState::Espresso);
            assert_eq!(info.substate, SubState::Preinfusion);
        }

        #[test]
        fn ignores_bytes_past_the_fixed_layout() {
            assert!(StateInfo::parse(&[2, 0, 0xFF]).is_ok());
        }

        #[test]
        fn rejects_a_packet_shorter_than_two_bytes() {
            assert_eq!(
                StateInfo::parse(&[4]),
                Err(ProtocolError::PacketTooShort {
                    packet: "StateInfo",
                    expected: 2,
                    got: 1,
                })
            );
        }
    }
}
