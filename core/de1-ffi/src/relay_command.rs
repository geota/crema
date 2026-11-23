//! The relayed-command codec and refusal rules for the Android shell:
//! UniFFI mirrors of [`de1_relay_app`]'s types and its four calls. Kotlin's
//! `MachineCommandCodec` and `MachineCommand.refusal()` delegate here, so the
//! wire form and the refusal texts are the core's.
//!
//! Kotlin strings cross as UTF-8, which can't carry a lone surrogate; the
//! shell replaces one with U+FFFD before calling (a well-formed string
//! crosses unchanged, and its length in UTF-16 units stays the same).

use de1_relay_app as app;

/// A relayed command ([`app::RelayCommand`]).
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum RelayCommand {
    /// Start a shot through the hub's start sequence.
    StartShot,
    /// End whatever the machine is doing.
    Stop,
    /// Wake a sleeping machine (Stop's wire form).
    Wake,
    /// Put the machine to sleep.
    Sleep,
    /// Start steam.
    StartSteam,
    /// Start hot water.
    StartHotWater,
    /// Start a group flush.
    StartFlush,
    /// Start a steam rinse.
    StartSteamRinse,
    /// Start descaling.
    StartDescale,
    /// Start the cleaning cycle.
    StartClean,
    /// Cancel the maintenance cycle.
    CancelMaintenance,
    /// Tare the scale.
    TareScale,
    /// Select the active profile.
    SetActiveProfile {
        /// The profile id.
        id: String,
    },
    /// Select the active bean, or none.
    SetActiveBean {
        /// The bean id.
        id: Option<String>,
    },
    /// Select the active grinder, or none.
    SetActiveGrinder {
        /// The grinder id.
        id: Option<String>,
        /// The picked record's name.
        name: Option<String>,
    },
    /// Stop on weight.
    SetStopOnWeight {
        /// On.
        on: bool,
    },
    /// Auto-tare.
    SetAutoTare {
        /// On.
        on: bool,
    },
    /// One Quick Controls machine setting (no wire form).
    SetQc {
        /// Which setting.
        field: RelayQcField,
        /// Its value.
        value: f32,
    },
    /// Brew's grind, as text.
    SetGrind {
        /// The grind.
        grind: String,
    },
    /// The live brew override.
    AdjustBrew {
        /// Dose, g.
        dose: f64,
        /// Yield, g.
        yield_out: f64,
        /// Brew temperature, °C.
        brew_temp: f64,
        /// Pre-infusion, s.
        preinf: Option<f64>,
    },
    /// Drop the brew override.
    ResetBrew,
    /// Quick Controls' Save.
    QuickSave {
        /// A copy's name.
        copy_name: Option<String>,
        /// Save as new.
        as_new: bool,
    },
}

/// A Quick Controls machine setting ([`app::QcField`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RelayQcField {
    /// Steam duration.
    SteamTime,
    /// Steam flow.
    SteamFlow,
    /// Steam temperature.
    SteamTemp,
    /// Hot-water temperature.
    HotWaterTemp,
    /// Hot-water volume.
    HotWaterVolume,
    /// Flush duration.
    FlushTime,
    /// Flush temperature.
    FlushTemp,
}

/// A command's `control` frame method and arguments.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RelayWire {
    /// The method name.
    pub method: String,
    /// The argument string.
    pub args: String,
}

/// What a hub makes of a relayed control ([`app::Decoded`]).
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum RelayDecoded {
    /// Run it (after the shell's library check).
    Valid {
        /// The command.
        command: RelayCommand,
    },
    /// Refuse it, with this reason for the remote's user.
    Invalid {
        /// The reason.
        reason: String,
    },
}

impl From<RelayQcField> for app::QcField {
    fn from(f: RelayQcField) -> Self {
        match f {
            RelayQcField::SteamTime => Self::SteamTime,
            RelayQcField::SteamFlow => Self::SteamFlow,
            RelayQcField::SteamTemp => Self::SteamTemp,
            RelayQcField::HotWaterTemp => Self::HotWaterTemp,
            RelayQcField::HotWaterVolume => Self::HotWaterVolume,
            RelayQcField::FlushTime => Self::FlushTime,
            RelayQcField::FlushTemp => Self::FlushTemp,
        }
    }
}

impl From<app::QcField> for RelayQcField {
    fn from(f: app::QcField) -> Self {
        match f {
            app::QcField::SteamTime => Self::SteamTime,
            app::QcField::SteamFlow => Self::SteamFlow,
            app::QcField::SteamTemp => Self::SteamTemp,
            app::QcField::HotWaterTemp => Self::HotWaterTemp,
            app::QcField::HotWaterVolume => Self::HotWaterVolume,
            app::QcField::FlushTime => Self::FlushTime,
            app::QcField::FlushTemp => Self::FlushTemp,
        }
    }
}

impl From<RelayCommand> for app::RelayCommand {
    fn from(c: RelayCommand) -> Self {
        use RelayCommand as F;
        match c {
            F::StartShot => Self::StartShot,
            F::Stop => Self::Stop,
            F::Wake => Self::Wake,
            F::Sleep => Self::Sleep,
            F::StartSteam => Self::StartSteam,
            F::StartHotWater => Self::StartHotWater,
            F::StartFlush => Self::StartFlush,
            F::StartSteamRinse => Self::StartSteamRinse,
            F::StartDescale => Self::StartDescale,
            F::StartClean => Self::StartClean,
            F::CancelMaintenance => Self::CancelMaintenance,
            F::TareScale => Self::TareScale,
            F::SetActiveProfile { id } => Self::SetActiveProfile { id },
            F::SetActiveBean { id } => Self::SetActiveBean { id },
            F::SetActiveGrinder { id, name } => Self::SetActiveGrinder { id, name },
            F::SetStopOnWeight { on } => Self::SetStopOnWeight { on },
            F::SetAutoTare { on } => Self::SetAutoTare { on },
            F::SetQc { field, value } => Self::SetQc {
                field: field.into(),
                value,
            },
            F::SetGrind { grind } => Self::SetGrind { grind },
            F::AdjustBrew {
                dose,
                yield_out,
                brew_temp,
                preinf,
            } => Self::AdjustBrew {
                dose,
                yield_out,
                brew_temp,
                preinf,
            },
            F::ResetBrew => Self::ResetBrew,
            F::QuickSave { copy_name, as_new } => Self::QuickSave { copy_name, as_new },
        }
    }
}

impl From<app::RelayCommand> for RelayCommand {
    fn from(c: app::RelayCommand) -> Self {
        use app::RelayCommand as A;
        match c {
            A::StartShot => Self::StartShot,
            A::Stop => Self::Stop,
            A::Wake => Self::Wake,
            A::Sleep => Self::Sleep,
            A::StartSteam => Self::StartSteam,
            A::StartHotWater => Self::StartHotWater,
            A::StartFlush => Self::StartFlush,
            A::StartSteamRinse => Self::StartSteamRinse,
            A::StartDescale => Self::StartDescale,
            A::StartClean => Self::StartClean,
            A::CancelMaintenance => Self::CancelMaintenance,
            A::TareScale => Self::TareScale,
            A::SetActiveProfile { id } => Self::SetActiveProfile { id },
            A::SetActiveBean { id } => Self::SetActiveBean { id },
            A::SetActiveGrinder { id, name } => Self::SetActiveGrinder { id, name },
            A::SetStopOnWeight { on } => Self::SetStopOnWeight { on },
            A::SetAutoTare { on } => Self::SetAutoTare { on },
            A::SetQc { field, value } => Self::SetQc {
                field: field.into(),
                value,
            },
            A::SetGrind { grind } => Self::SetGrind { grind },
            A::AdjustBrew {
                dose,
                yield_out,
                brew_temp,
                preinf,
            } => Self::AdjustBrew {
                dose,
                yield_out,
                brew_temp,
                preinf,
            },
            A::ResetBrew => Self::ResetBrew,
            A::QuickSave { copy_name, as_new } => Self::QuickSave { copy_name, as_new },
        }
    }
}

/// `command`'s `control` frame method and arguments, or `None` for a Quick
/// Controls machine setting (no wire form). See [`app::encode_command`].
#[uniffi::export]
pub fn relay_encode_command(command: RelayCommand) -> Option<RelayWire> {
    app::encode_command(&command.into()).map(|w| RelayWire {
        method: w.method,
        args: w.args,
    })
}

/// Parse and check a relayed control. See [`app::decode_command`].
#[uniffi::export]
pub fn relay_decode_command(method: String, args: String) -> RelayDecoded {
    match app::decode_command(&method, &args) {
        app::Decoded::Valid(cmd) => RelayDecoded::Valid {
            command: cmd.into(),
        },
        app::Decoded::Invalid(reason) => RelayDecoded::Invalid { reason },
    }
}

/// Why `command`'s arguments can't be run, or `None`. See [`app::refusal`].
#[uniffi::export]
pub fn relay_command_refusal(command: RelayCommand) -> Option<String> {
    app::refusal(&command.into())
}

/// Every method a hub accepts.
#[uniffi::export]
pub fn relay_command_methods() -> Vec<String> {
    app::METHODS.iter().map(|m| (*m).to_owned()).collect()
}

/// The machine states a `machineState` control may carry.
#[uniffi::export]
pub fn relay_machine_states() -> Vec<String> {
    app::relayable_states().map(str::to_owned).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_command_crosses_both_ways() {
        let cmd = RelayCommand::SetActiveGrinder {
            id: Some("grinder:niche-zero".into()),
            name: Some("Niche Zero".into()),
        };
        let wire = relay_encode_command(cmd.clone()).expect("wire form");
        assert_eq!(wire.args, "grinder:niche-zero\nNiche Zero");
        assert_eq!(
            relay_decode_command(wire.method, wire.args),
            RelayDecoded::Valid { command: cmd }
        );
        assert_eq!(
            relay_command_refusal(RelayCommand::SetQc {
                field: RelayQcField::SteamTime,
                value: 61.0
            })
            .as_deref(),
            Some("steam time out of range: 61.0")
        );
        assert_eq!(relay_command_methods().len(), 13);
        assert_eq!(relay_machine_states().len(), 9);
    }
}
