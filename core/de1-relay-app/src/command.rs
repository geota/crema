//! Relayed commands: the `control` frame's method and argument string for
//! each, and the argument checks a hub runs on whatever a remote sends.
//!
//! The wire form is byte for byte what the Android shell sent before this
//! moved into the core: `tests/golden/v4/control/` pins it, and the refusal
//! texts a remote shows are unchanged too (see [`crate::jvm`] for the Kotlin
//! number and text rules they keep).
//!
//! Two wire forms stand for more than one command until the protocol can say
//! more:
//! - `machineState IDLE` is both [`RelayCommand::Stop`] and
//!   [`RelayCommand::Wake`]. It decodes as `Stop`, which applies the same write.
//! - `machineState ESPRESSO` (an older build's debug button) decodes as
//!   [`RelayCommand::StartShot`], so a relayed shot always goes through the
//!   start sequence. Nothing encodes to it any more.
//!
//! [`RelayCommand::SetQc`] has no wire form (relay v4 R4: a remote doesn't
//! send the Quick Controls machine settings); it is here for its range check,
//! which the hub runs on its own edits.

use std::sync::LazyLock;

use de1_domain::{
    HOT_WATER_FIRMWARE, QuickControlBounds, StepRange, grind_number, hot_water_bounds,
    profile_bounds::MAX_TEMPERATURE_C, quick_control_bounds,
};

use crate::jvm;

/// A command one device asks the device holding the machine to run.
#[derive(Debug, Clone, PartialEq)]
pub enum RelayCommand {
    /// Start a shot through the hub's start sequence.
    StartShot,
    /// End whatever the machine is doing.
    Stop,
    /// Wake a sleeping machine: the same request as [`RelayCommand::Stop`].
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
    /// Cancel the maintenance cycle the hub is following.
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
        /// The bean id; `None` for no bean.
        id: Option<String>,
    },
    /// Select the active grinder, or none. A pick carries the record's name.
    SetActiveGrinder {
        /// The grinder id; `None` for no grinder.
        id: Option<String>,
        /// The picked record's name.
        name: Option<String>,
    },
    /// Stop on weight, on or off.
    SetStopOnWeight {
        /// On.
        on: bool,
    },
    /// Auto-tare, on or off.
    SetAutoTare {
        /// On.
        on: bool,
    },
    /// One Quick Controls machine setting. No wire form.
    SetQc {
        /// Which setting.
        field: QcField,
        /// Its value.
        value: f32,
    },
    /// Brew's grind, as text.
    SetGrind {
        /// The grind as typed or recorded (`"14.5"`, `"2.1.3"`, `"4⅓"`).
        grind: String,
    },
    /// The live Quick Controls brew override.
    AdjustBrew {
        /// Dose, g.
        dose: f64,
        /// Yield, g (`0` = no weight target).
        yield_out: f64,
        /// Brew temperature, °C.
        brew_temp: f64,
        /// Pre-infusion, s; `None` for none.
        preinf: Option<f64>,
    },
    /// Drop the brew override.
    ResetBrew,
    /// Quick Controls' Save, on the hub's own library.
    QuickSave {
        /// A copy's name (a built-in's copy, or Save as new); `None` to save.
        copy_name: Option<String>,
        /// Save as new rather than a built-in's copy.
        as_new: bool,
    },
}

/// The Quick Controls machine settings, each with its stepper's range.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum QcField {
    /// Steam duration, s.
    SteamTime,
    /// Steam flow, ml/s.
    SteamFlow,
    /// Steam temperature, °C; `0` turns the heater off.
    SteamTemp,
    /// Hot-water temperature, °C.
    HotWaterTemp,
    /// Hot-water volume, ml.
    HotWaterVolume,
    /// Flush duration, s.
    FlushTime,
    /// Flush temperature, °C.
    FlushTemp,
}

/// A command's `control` frame method and argument string.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Wire {
    /// The method name.
    pub method: String,
    /// The argument string (`""` for none).
    pub args: String,
}

/// What a hub makes of a relayed control.
#[derive(Debug, Clone, PartialEq)]
pub enum Decoded {
    /// A command whose arguments passed [`refusal`].
    Valid(RelayCommand),
    /// Why it won't run, worded for the remote's user.
    Invalid(String),
}

const START_SHOT: &str = "startShot";
const MACHINE_STATE: &str = "machineState";
const CANCEL_MAINTENANCE: &str = "cancelMaintenance";
const TARE_SCALE: &str = "tareScale";
const SET_ACTIVE_PROFILE: &str = "setActiveProfile";
const SET_ACTIVE_BEAN: &str = "setActiveBean";
const SET_STOP_ON_WEIGHT: &str = "setStopOnWeight";
const SET_AUTO_TARE: &str = "setAutoTare";
const QUICK_ADJUST_BREW: &str = "quickAdjustBrew";
const RESET_BREW: &str = "resetBrew";
const SET_ACTIVE_GRINDER: &str = "setActiveGrinder";
const QUICK_SAVE: &str = "quickSave";
const SET_QC_GRIND: &str = "setQcGrind";
/// Splits a grinder pick's id from its name; neither may hold a control character.
const GRINDER_NAME: char = '\n';
const SAVE_COPY: &str = "copy:";
const SAVE_AS_NEW: &str = "new:";

/// Every method a hub accepts.
pub const METHODS: [&str; 13] = [
    START_SHOT,
    MACHINE_STATE,
    CANCEL_MAINTENANCE,
    TARE_SCALE,
    SET_ACTIVE_PROFILE,
    SET_ACTIVE_BEAN,
    SET_STOP_ON_WEIGHT,
    SET_AUTO_TARE,
    QUICK_ADJUST_BREW,
    RESET_BREW,
    SET_ACTIVE_GRINDER,
    QUICK_SAVE,
    SET_QC_GRIND,
];

/// The machine states a `machineState` control may carry (the shells'
/// `MachineRequest` names), and the command each decodes as.
const MACHINE_STATES: [(&str, RelayCommand); 9] = [
    ("IDLE", RelayCommand::Stop),
    ("SLEEP", RelayCommand::Sleep),
    ("STEAM", RelayCommand::StartSteam),
    ("HOT_WATER", RelayCommand::StartHotWater),
    ("FLUSH", RelayCommand::StartFlush),
    ("STEAM_RINSE", RelayCommand::StartSteamRinse),
    ("DESCALE", RelayCommand::StartDescale),
    ("CLEAN", RelayCommand::StartClean),
    ("ESPRESSO", RelayCommand::StartShot),
];

/// The machine states a `machineState` control may carry.
pub fn relayable_states() -> impl Iterator<Item = &'static str> {
    MACHINE_STATES.iter().map(|(name, _)| *name)
}

fn wire(method: &str, args: impl Into<String>) -> Option<Wire> {
    Some(Wire {
        method: method.to_owned(),
        args: args.into(),
    })
}

/// `cmd`'s wire form, or `None` for a Quick Controls machine setting, which
/// a remote keeps read-only. Nothing is checked here: a sender's own command
/// is checked with [`refusal`] where it is built.
#[must_use]
pub fn encode_command(cmd: &RelayCommand) -> Option<Wire> {
    match cmd {
        RelayCommand::StartShot => wire(START_SHOT, ""),
        RelayCommand::Stop | RelayCommand::Wake => wire(MACHINE_STATE, "IDLE"),
        RelayCommand::Sleep => wire(MACHINE_STATE, "SLEEP"),
        RelayCommand::StartSteam => wire(MACHINE_STATE, "STEAM"),
        RelayCommand::StartHotWater => wire(MACHINE_STATE, "HOT_WATER"),
        RelayCommand::StartFlush => wire(MACHINE_STATE, "FLUSH"),
        RelayCommand::StartSteamRinse => wire(MACHINE_STATE, "STEAM_RINSE"),
        RelayCommand::StartDescale => wire(MACHINE_STATE, "DESCALE"),
        RelayCommand::StartClean => wire(MACHINE_STATE, "CLEAN"),
        RelayCommand::CancelMaintenance => wire(CANCEL_MAINTENANCE, ""),
        RelayCommand::TareScale => wire(TARE_SCALE, ""),
        RelayCommand::SetActiveProfile { id } => wire(SET_ACTIVE_PROFILE, id.as_str()),
        // No bean travels as an empty argument.
        RelayCommand::SetActiveBean { id } => wire(SET_ACTIVE_BEAN, id.clone().unwrap_or_default()),
        // `<id>\n<name>`: the hub checks it is the same record (follow-up 19).
        // No grinder travels as an empty argument, like no bean.
        RelayCommand::SetActiveGrinder { id, name } => wire(
            SET_ACTIVE_GRINDER,
            match (id, name) {
                (None, _) => String::new(),
                (Some(id), None) => id.clone(),
                (Some(id), Some(name)) => format!("{id}{GRINDER_NAME}{name}"),
            },
        ),
        // `` = Save; `copy:<name>` = a built-in's copy; `new:<name>` = Save as new.
        RelayCommand::QuickSave { copy_name, as_new } => wire(
            QUICK_SAVE,
            match copy_name {
                None => String::new(),
                Some(name) if *as_new => format!("{SAVE_AS_NEW}{name}"),
                Some(name) => format!("{SAVE_COPY}{name}"),
            },
        ),
        RelayCommand::SetStopOnWeight { on } => wire(SET_STOP_ON_WEIGHT, on.to_string()),
        RelayCommand::SetAutoTare { on } => wire(SET_AUTO_TARE, on.to_string()),
        // No Quick Controls machine setting has a wire form (relay v4 R4).
        RelayCommand::SetQc { .. } => None,
        // The grind as text ("14.5", "2.1.3", "4⅓"), end to end (#488 round 2).
        RelayCommand::SetGrind { grind } => wire(SET_QC_GRIND, grind.as_str()),
        // `dose,yield,temp,preinf`, preinf empty = none; Java's number text.
        RelayCommand::AdjustBrew {
            dose,
            yield_out,
            brew_temp,
            preinf,
        } => wire(
            QUICK_ADJUST_BREW,
            format!(
                "{},{},{},{}",
                jvm::double_to_string(*dose),
                jvm::double_to_string(*yield_out),
                jvm::double_to_string(*brew_temp),
                preinf.map(jvm::double_to_string).unwrap_or_default(),
            ),
        ),
        RelayCommand::ResetBrew => wire(RESET_BREW, ""),
    }
}

/// Parse a relayed control and check its arguments ([`refusal`]). Nothing
/// here runs it, and nothing here knows the hub's library: whether an id is
/// one of its records is the shell's check.
#[must_use]
pub fn decode_command(method: &str, args: &str) -> Decoded {
    match parse(method, args) {
        None => Decoded::Invalid(parse_refusal(method, args)),
        Some(cmd) => match refusal(&cmd) {
            Some(reason) => Decoded::Invalid(reason),
            None => Decoded::Valid(cmd),
        },
    }
}

fn parse(method: &str, args: &str) -> Option<RelayCommand> {
    let no_args = |cmd: RelayCommand| args.is_empty().then_some(cmd);
    let flag = || match args {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    };
    match method {
        START_SHOT => no_args(RelayCommand::StartShot),
        CANCEL_MAINTENANCE => no_args(RelayCommand::CancelMaintenance),
        TARE_SCALE => no_args(RelayCommand::TareScale),
        RESET_BREW => no_args(RelayCommand::ResetBrew),
        MACHINE_STATE => MACHINE_STATES
            .iter()
            .find(|(name, _)| *name == args)
            .map(|(_, cmd)| cmd.clone()),
        SET_ACTIVE_PROFILE => Some(RelayCommand::SetActiveProfile {
            id: args.to_owned(),
        }),
        SET_ACTIVE_BEAN => Some(RelayCommand::SetActiveBean {
            id: (!args.is_empty()).then(|| args.to_owned()),
        }),
        SET_ACTIVE_GRINDER => Some(grinder_pick(args)),
        SET_QC_GRIND => Some(RelayCommand::SetGrind {
            grind: args.to_owned(),
        }),
        QUICK_SAVE => quick_save(args),
        SET_STOP_ON_WEIGHT => flag().map(|on| RelayCommand::SetStopOnWeight { on }),
        SET_AUTO_TARE => flag().map(|on| RelayCommand::SetAutoTare { on }),
        QUICK_ADJUST_BREW => brew(args),
        _ => None,
    }
}

/// Why [`parse`] found nothing to run.
fn parse_refusal(method: &str, args: &str) -> String {
    match method {
        MACHINE_STATE => format!("machine state not relayable: {}", jvm::take_utf16(args, 32)),
        SET_STOP_ON_WEIGHT | SET_AUTO_TARE => format!("{method} needs true or false"),
        QUICK_ADJUST_BREW => "quickAdjustBrew needs dose,yield,temp[,preinf]".to_owned(),
        QUICK_SAVE => "quickSave takes nothing, copy:<name> or new:<name>".to_owned(),
        _ if METHODS.contains(&method) => format!("{method} takes no arguments"),
        _ => format!("unknown relayed control: {}", jvm::take_utf16(method, 32)),
    }
}

/// `` = none, `<id>\n<name>` = that grinder; a bare `<id>` (no name) is
/// decoded and then refused.
fn grinder_pick(args: &str) -> RelayCommand {
    if args.is_empty() {
        return RelayCommand::SetActiveGrinder {
            id: None,
            name: None,
        };
    }
    match args.split_once(GRINDER_NAME) {
        None => RelayCommand::SetActiveGrinder {
            id: Some(args.to_owned()),
            name: None,
        },
        Some((id, name)) => RelayCommand::SetActiveGrinder {
            id: Some(id.to_owned()),
            name: Some(name.to_owned()),
        },
    }
}

/// `` = Save, `copy:<name>` = a named copy, `new:<name>` = Save as new.
fn quick_save(args: &str) -> Option<RelayCommand> {
    let (copy_name, as_new) = if args.is_empty() {
        (None, false)
    } else if let Some(name) = args.strip_prefix(SAVE_COPY) {
        (Some(name.to_owned()), false)
    } else {
        (Some(args.strip_prefix(SAVE_AS_NEW)?.to_owned()), true)
    };
    Some(RelayCommand::QuickSave { copy_name, as_new })
}

/// `dose,yield,temp[,preinf]`, preinf empty or absent = none.
fn brew(args: &str) -> Option<RelayCommand> {
    let p: Vec<&str> = args.split(',').collect();
    if !(3..=4).contains(&p.len()) {
        return None;
    }
    let preinf = match p.get(3).filter(|t| !t.is_empty()) {
        Some(t) => Some(jvm::parse_double(t)?),
        None => None,
    };
    Some(RelayCommand::AdjustBrew {
        dose: jvm::parse_double(p[0])?,
        yield_out: jvm::parse_double(p[1])?,
        brew_temp: jvm::parse_double(p[2])?,
        preinf,
    })
}

const MAX_ID_LENGTH: usize = 128;
const MAX_NAME_LENGTH: usize = 128;
const MAX_PREINF_S: f64 = 300.0;
/// A sanity bound, not the scale: a typed grind outside the active scale is kept (#488).
const MAX_GRIND: f64 = 10_000.0;
/// The longest grind text: the domain's `MAX_GRIND_NUMBER_LEN` (it has no separate text limit).
const MAX_GRIND_LENGTH: usize = de1_domain::MAX_GRIND_NUMBER_LEN;
/// The hot-water temperature stepper's range, °C (the shells' `QcBounds`).
const WATER_TEMP_C: (f64, f64) = (40.0, 98.0);

/// The Quick Controls table (default grind scale; the grind isn't checked
/// against it) and the hot-water volume range, built once.
static BOUNDS: LazyLock<(QuickControlBounds, StepRange)> = LazyLock::new(|| {
    (
        quick_control_bounds(),
        hot_water_bounds(&HOT_WATER_FIRMWARE).volume_ml,
    )
});

/// Why `cmd`'s arguments can't be run, or `None` to run it. The ranges are
/// the Quick Controls' own (the domain's table); a brew temperature's is the
/// domain's temperature ceiling; pre-infusion keeps the wire ceiling, since
/// its dial widens per profile. The hub clamps an accepted override into its
/// active profile's range afterwards.
#[must_use]
pub fn refusal(cmd: &RelayCommand) -> Option<String> {
    match cmd {
        RelayCommand::SetActiveProfile { id } => id_refusal(id),
        RelayCommand::SetActiveBean { id } => id.as_deref().and_then(id_refusal),
        RelayCommand::SetActiveGrinder { id: None, .. } => None,
        RelayCommand::SetActiveGrinder {
            id: Some(id),
            name: None,
        } => id_refusal(id).or_else(|| Some("a grinder pick needs its name".to_owned())),
        RelayCommand::SetActiveGrinder {
            id: Some(id),
            name: Some(name),
        } => id_refusal(id).or_else(|| name_refusal(name)),
        RelayCommand::SetQc { field, value } => qc_refusal(*field, *value),
        RelayCommand::SetGrind { grind } => grind_refusal(grind),
        RelayCommand::AdjustBrew {
            dose,
            yield_out,
            brew_temp,
            preinf,
        } => {
            let q = &BOUNDS.0;
            let in_range = within_step(*dose, &q.dose_g)
                && (*yield_out == 0.0 || within_step(*yield_out, &q.override_yield_g))
                && within(*brew_temp, 0.0, f64::from(MAX_TEMPERATURE_C))
                && preinf.is_none_or(|p| within(p, 0.0, MAX_PREINF_S));
            (!in_range).then(|| "brew override out of range".to_owned())
        }
        RelayCommand::QuickSave { copy_name, as_new } => match copy_name {
            None => as_new.then(|| "Save as new needs a name".to_owned()),
            Some(name) if jvm::is_blank(name) => Some("a copy needs a name".to_owned()),
            Some(name) if too_long_or_control(name, MAX_NAME_LENGTH) => {
                Some("malformed name".to_owned())
            }
            Some(_) => None,
        },
        RelayCommand::StartShot
        | RelayCommand::Stop
        | RelayCommand::Wake
        | RelayCommand::Sleep
        | RelayCommand::StartSteam
        | RelayCommand::StartHotWater
        | RelayCommand::StartFlush
        | RelayCommand::StartSteamRinse
        | RelayCommand::StartDescale
        | RelayCommand::StartClean
        | RelayCommand::CancelMaintenance
        | RelayCommand::TareScale
        | RelayCommand::SetStopOnWeight { .. }
        | RelayCommand::SetAutoTare { .. }
        | RelayCommand::ResetBrew => None,
    }
}

impl QcField {
    /// The setting's name in a refusal.
    #[must_use]
    pub fn label(self) -> &'static str {
        match self {
            Self::SteamTime => "steam time",
            Self::SteamFlow => "steam flow",
            Self::SteamTemp => "steam temperature",
            Self::HotWaterTemp => "hot-water temperature",
            Self::HotWaterVolume => "hot-water volume",
            Self::FlushTime => "flush time",
            Self::FlushTemp => "flush temperature",
        }
    }

    /// The stepper's range: the Quick Controls table's, hot water's own.
    fn range(self) -> (f64, f64) {
        let (q, volume) = &*BOUNDS;
        let step = |r: &StepRange| (f64::from(r.min), f64::from(r.max));
        match self {
            Self::SteamTime => step(&q.steam_time_s),
            Self::SteamFlow => step(&q.steam_flow_ml_s),
            Self::SteamTemp => step(&q.steam_temp_c),
            Self::HotWaterTemp => WATER_TEMP_C,
            Self::HotWaterVolume => step(volume),
            Self::FlushTime => step(&q.flush_time_s),
            Self::FlushTemp => step(&q.flush_temp_c),
        }
    }
}

/// Why `value` is out of bounds for `field`, or `None`. Steam temperature
/// may also be 0 (heater off).
fn qc_refusal(field: QcField, value: f32) -> Option<String> {
    if field == QcField::SteamTemp && value == 0.0 {
        return None;
    }
    let (min, max) = field.range();
    (!within(f64::from(value), min, max)).then(|| {
        format!(
            "{} out of range: {}",
            field.label(),
            jvm::float_to_string(value)
        )
    })
}

/// A grind is text: required, no longer than a grind needs, with no control
/// or format characters anywhere (the bidi controls among them), and when it
/// reads as a number (the domain's `grind_number`: never an exponent), within
/// the sanity bound. The hub stores it trimmed.
fn grind_refusal(grind: &str) -> Option<String> {
    let text = jvm::trim(grind);
    if text.is_empty() {
        return Some("a grind is required".to_owned());
    }
    if jvm::utf16_len(text) > MAX_GRIND_LENGTH
        || grind
            .chars()
            .any(|c| jvm::is_iso_control(c) || jvm::is_bmp_format(c))
    {
        return Some("malformed grind".to_owned());
    }
    match grind_number(text) {
        Some(n) if !within(n, 0.0, MAX_GRIND) => Some(format!("grind out of range: {text}")),
        _ => None,
    }
}

fn too_long_or_control(s: &str, max: usize) -> bool {
    jvm::utf16_len(s) > max || s.chars().any(jvm::is_iso_control)
}

fn id_refusal(id: &str) -> Option<String> {
    if id.is_empty() {
        Some("an id is required".to_owned())
    } else if too_long_or_control(id, MAX_ID_LENGTH) {
        Some("malformed id".to_owned())
    } else {
        None
    }
}

fn name_refusal(name: &str) -> Option<String> {
    if jvm::is_blank(name) {
        Some("a name is required".to_owned())
    } else if too_long_or_control(name, MAX_NAME_LENGTH) {
        Some("malformed name".to_owned())
    } else {
        None
    }
}

fn within(v: f64, min: f64, max: f64) -> bool {
    v.is_finite() && v >= min && v <= max
}

fn within_step(v: f64, r: &StepRange) -> bool {
    within(v, f64::from(r.min), f64::from(r.max))
}
