//! The unifying [`Scale`] abstraction over every supported scale protocol.

use std::time::Duration;

use typeshare::typeshare;

use crate::acaia::{self, AcaiaDecoder};
use crate::decent_scale::DecentScale;
use crate::smartchef::SmartchefScale;
use crate::{
    atomheart_eclair, bookoo, decent_scale, difluid, eureka_precisa, felicita, hiroia_jimmy, skale,
    smartchef, varia_aku,
};

pub use crate::decent_scale::DecentScaleFirmwareVersion;

/// The BLE UUIDs a scale's transport layer needs.
///
/// `Clone` but not `Copy`: three string slices (48 bytes) exceed the size
/// where implicit copies are a sensible default.
///
/// `#[typeshare]` + serde `Serialize` (behind the `serde` feature): the web
/// shell needs these UUIDs to know which Web Bluetooth characteristics to
/// subscribe to, so they cross the JSON bridge boundary.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct ScaleUuids {
    /// GATT service UUID.
    pub service: &'static str,
    /// Characteristic weight notifications arrive on.
    pub weight_notify: &'static str,
    /// Characteristic commands are written to — equal to `weight_notify` for
    /// scales that use a single characteristic for both.
    pub command_write: &'static str,
}

/// The **pre-connect** BLE scan filter set, shared across shells: the union of
/// every supported scale's advertised-name prefixes and GATT service UUIDs.
///
/// A scan happens *before* the core has identified the scale (so there is no
/// capability to gate on yet), and the core is the authority on which scales
/// exist — it owns [`Scale::identify`] — so it owns the scan filter set. A
/// shell lists `name_prefixes` in its scan filter and `service_uuids` in a Web
/// Bluetooth `optionalServices`, then connects and learns the exact per-model
/// characteristics from [`Scale::uuids`]. This keeps the scan generic instead
/// of each shell hardcoding one scale's UUIDs.
// Serialize only: emitted to the shells as JSON, never deserialised in Rust —
// `Deserialize` can't be derived for the `Vec<&'static str>` fields (a
// deserialised `&str` borrows the input, so it isn't `'static`).
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize))]
pub struct ScaleScanUuids {
    /// Distinct GATT service UUIDs across all supported scales (deduplicated —
    /// several scales share `0000fff0`). For a Web Bluetooth `optionalServices`.
    pub service_uuids: Vec<&'static str>,
    /// Every advertised-name prefix [`Scale::identify`] matches a scale by.
    pub name_prefixes: Vec<&'static str>,
}

/// One row of the scan registry: the advertised-name `prefixes` a scale is
/// recognised by, and the [`Scale::from_label`] `label` that builds it. The
/// single source [`Scale::identify`] and [`Scale::scan_uuids`] both read, so
/// the scan filter set can never drift from what `identify` recognises.
struct ScaleScanEntry {
    prefixes: &'static [&'static str],
    label: &'static str,
}

/// Every scale Crema can identify, in the order [`Scale::identify`] tests them
/// (first prefix match wins — order is load-bearing for overlapping families
/// like Acaia Pyxis vs gen-1). Adding a scale = a row here + a
/// [`Scale::from_label`] arm.
const SCALE_SCAN: &[ScaleScanEntry] = &[
    ScaleScanEntry { prefixes: &["Decent Scale", "ButtsHaus Scale"], label: "Decent Scale" },
    ScaleScanEntry { prefixes: &["Skale"], label: "Skale II" },
    ScaleScanEntry { prefixes: &["FELICITA"], label: "Felicita Arc" },
    ScaleScanEntry { prefixes: &["BOOKOO_SC"], label: "Bookoo" },
    ScaleScanEntry { prefixes: &["PEARLS", "PEARL-", "LUNAR", "PYXIS"], label: "Acaia Pyxis" },
    ScaleScanEntry { prefixes: &["ACAIA", "PROCH"], label: "Acaia" },
    ScaleScanEntry { prefixes: &["ECLAIR"], label: "Atomheart Eclair" },
    ScaleScanEntry { prefixes: &["CFS-9002"], label: "Eureka Precisa" },
    ScaleScanEntry { prefixes: &["LSJ-001"], label: "Solo Barista" },
    ScaleScanEntry { prefixes: &["Microbalance"], label: "Difluid Microbalance" },
    ScaleScanEntry { prefixes: &["smartchef"], label: "Smartchef" },
    ScaleScanEntry { prefixes: &["HIROIA JIMMY"], label: "Hiroia Jimmy" },
    ScaleScanEntry { prefixes: &["AKU MINI", "Varia AKU"], label: "Varia Aku" },
];

/// One decoded scale notification.
///
/// Every scale reports a `weight_g`. A few scales report more in the same
/// notification: the Bookoo carries a native mass-flow rate, its own built-in
/// timer, and its live settings (beeper volume, auto-standby timeout, battery
/// charge, flow smoothing). Those extra fields are `Option` — `None` for the
/// scales that do not report them.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScaleReading {
    /// Weight, grams.
    pub weight_g: f32,
    /// Scale-reported mass-flow rate, grams per second — `None` for scales
    /// that do not report a native flow rate. This is the device's own value,
    /// distinct from any flow the core estimates from the weight series.
    pub flow_g_per_s: Option<f32>,
    /// Scale-reported built-in-timer reading, milliseconds — `None` for scales
    /// that do not report a timer in their weight notification.
    pub timer_ms: Option<u32>,
    /// The scale's live beeper-volume setting `0..=5` — `None` for scales that
    /// do not echo their settings in the weight notification. Only the Bookoo
    /// reports this; it lets a settings control display the real value.
    pub volume: Option<u8>,
    /// The scale's live auto-standby timeout, in minutes — `None` for scales
    /// that do not echo their settings. Only the Bookoo reports this.
    pub standby_minutes: Option<u8>,
    /// The scale's live battery charge percentage — `None` for scales that do
    /// not report it. Only the Bookoo reports this (an *assumed* decode of one
    /// byte; see [`bookoo::BookooPacket::battery_percent`]).
    pub battery_percent: Option<u8>,
    /// Whether the scale's flow smoothing is on — `None` for scales that do
    /// not echo their settings. Only the Bookoo reports this; it lets a
    /// settings toggle reflect the real on/off state.
    pub flow_smoothing: Option<bool>,
    /// The scale's live auto-stop mode id — `None` for scales that do not echo
    /// their settings. Only the Bookoo reports this (`0` = Flow-Stop, `1` =
    /// Cup-Removal); it lets a settings selector reflect the real current mode.
    /// Kept as a raw mode id (not a bool) to match `set_scale_auto_stop`.
    pub auto_stop: Option<u8>,
}

/// The inclusive `[min, max]` bounds of a ranged scale setting.
///
/// A ranged setting (the beeper volume, the auto-standby timeout) carries its
/// real bounds rather than a bare "supported" flag, so the shell can render a
/// control over `[min, max]` directly — no normalization, no per-model
/// hardcoding. The step is implied `1`: every supported ranged setting takes a
/// whole-number value.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct RangeCapability {
    /// The smallest value the setting accepts.
    pub min: u8,
    /// The largest value the setting accepts.
    pub max: u8,
}

/// One selectable display/behaviour mode a scale exposes.
///
/// A "first-class" scale (the Bookoo) lets the user switch the active display
/// mode; this descriptor carries each mode's wire `id` and a human-readable
/// `name`, so the shell renders one control per mode without per-model
/// hardcoding. The shell passes the `id` straight back to `set_scale_mode`.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct ModeInfo {
    /// The mode's wire identifier — passed back unchanged to select this mode.
    pub id: u8,
    /// A human-readable name for the mode, suitable for a button label.
    pub name: String,
}

/// What a connected scale can do, beyond reporting a bare weight.
///
/// Crema is **capability-driven, never device-driven**: the app reads this
/// descriptor and conditionally enables features, so it never branches on a
/// concrete scale model. A plain weight-only scale has every field absent /
/// `false` / empty; a "first-class" scale (the Bookoo today) sets the
/// capabilities it supports.
///
/// Ranged settings carry their real `[min, max]` bounds as a
/// [`RangeCapability`] (`None` = the scale does not expose that setting), so
/// the shell renders a control over the actual range. Toggle settings stay a
/// plain `bool` — there is nothing to parameterize. The selectable display
/// modes are a `Vec<ModeInfo>` (empty = no mode support).
///
/// `#[non_exhaustive]`: more capabilities are added here as later slices land,
/// without breaking callers — a new field is purely additive.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
#[non_exhaustive]
pub struct ScaleCapabilities {
    /// The scale reports its own native mass-flow rate in its weight
    /// notification (surfaced as `ScaleReading::flow_g_per_s`).
    pub reports_flow: bool,
    /// The scale reports its own built-in-timer reading in its weight
    /// notification (surfaced as `ScaleReading::timer_ms`).
    pub reports_timer: bool,
    /// The bounds of the scale's settable beeper volume — `None` when the
    /// scale's volume is not settable. `min` is the quietest step (`0` =
    /// silent), `max` the loudest.
    pub volume: Option<RangeCapability>,
    /// The bounds of the scale's auto-standby timeout, in minutes — `None`
    /// when the scale has no configurable auto-standby.
    pub standby: Option<RangeCapability>,
    /// The scale accepts a command to toggle flow smoothing.
    pub flow_smoothing: bool,
    /// The scale accepts a command to toggle anti-mistouch.
    pub anti_mistouch: bool,
    /// The scale accepts a command to select its auto-stop mode (the Bookoo's
    /// flow-stop / cup-removal setting).
    pub auto_stop: bool,
    /// The selectable display/behaviour modes the scale exposes — empty when
    /// the scale has no switchable modes. Each entry carries the mode's wire
    /// `id` and a display `name`.
    pub modes: Vec<ModeInfo>,
    /// True when the scale's on-scale LCD is settable — drives the
    /// shell's "Enable on-scale LCD" toggle UI. Mirrors
    /// [`Scale::lcd_enable_command`] returning `Some`.
    pub can_lcd: bool,
    /// True when the scale accepts a host-driven power-off command.
    /// Mirrors [`Scale::power_off_command`] returning `Some`.
    pub can_power_off: bool,
    /// True when the scale accepts a beep command. Mirrors
    /// [`Scale::beep_command`] returning `Some`.
    pub can_beep: bool,
    /// True when the scale exposes an explicit "set unit to grams"
    /// command (Eureka, Solo, Difluid). Mirrors
    /// [`Scale::set_unit_grams_command`] returning `Some`.
    pub can_set_unit_grams: bool,
    /// True when the scale exposes a toggle-unit command (Hiroia).
    /// Mirrors [`Scale::toggle_unit_command`] returning `Some`.
    pub can_toggle_unit: bool,
    /// Recommended cadence (milliseconds) between heartbeat writes when
    /// the scale needs periodic keep-alives — `Some(2000)` for the
    /// Decent Scale (keeps its on-scale LCD awake), `None` for every
    /// scale that does not. Mirrors [`Scale::heartbeat_command`]
    /// returning `Some`, with the cadence now living next to the bytes
    /// so the shell no longer carries a parallel constant.
    pub heartbeat_interval_ms: Option<u32>,
}

/// A structured configuration update decoded from a scale's command
/// characteristic — the device-agnostic surface the orchestrator
/// consumes. Per-device parsers fold into this; the orchestrator
/// translates it into the shell-facing `Event::ScaleConfig`.
///
/// A new scale that exposes a fresh config event (e.g. battery percent
/// on a future scale) lands as a new variant here — the orchestrator's
/// match adds the case at that time.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ScaleConfigUpdate {
    /// Serial / firmware-info reply — fired in response to a
    /// [`Scale::query_serial_command`]. Bookoo's `0x0c` reply today;
    /// other scales may surface analogous info under the same variant.
    SerialInfo {
        /// The scale's firmware version encoding (Bookoo: `major × 100 + minor × 10 + patch`).
        firmware_version: u16,
        /// The scale's serial number, decoded to a display string.
        serial: String,
        /// Whether anti-mistouch protection is currently enabled.
        anti_mistouch: bool,
    },
    /// Configurable-settings reply — fired in response to a
    /// [`Scale::query_settings_command`]. Bookoo's `0x0e` reply today.
    Settings {
        /// The mode the scale is currently displaying (per [`ScaleCapabilities::modes`]).
        active_mode: u8,
        /// Bitmask of currently-enabled modes (bit `n` set ↔ mode `n` enabled).
        enabled_modes: u8,
    },
}

impl From<bookoo::CommandResponse> for ScaleConfigUpdate {
    fn from(response: bookoo::CommandResponse) -> Self {
        match response {
            bookoo::CommandResponse::Serial {
                firmware_version,
                serial,
                anti_mistouch,
            } => ScaleConfigUpdate::SerialInfo {
                firmware_version,
                serial,
                anti_mistouch,
            },
            bookoo::CommandResponse::Settings {
                active_mode,
                enabled_modes,
            } => ScaleConfigUpdate::Settings {
                active_mode,
                enabled_modes,
            },
        }
    }
}

/// A timer command a scale may support.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimerCommand {
    /// Start the timer.
    Start,
    /// Stop the timer.
    Stop,
    /// Reset the timer to zero.
    Reset,
}

/// What the core should do when a weight notification reveals the scale
/// is in a non-grams unit — see [`Scale::unit_recovery`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnitRecovery {
    /// Queue `bytes` and drop the frame: the weight value is bogus in the
    /// scale's current unit (e.g. Hiroia in ounces reports ounce values).
    Drop {
        /// Wire bytes that nudge the scale back to grams.
        bytes: &'static [u8],
    },
    /// Queue `bytes` but keep parsing the frame: the numeric weight is
    /// still valid — only the scale's display unit is off (e.g. Difluid
    /// in ounces still streams grams on the weight characteristic).
    Continue {
        /// Wire bytes that nudge the scale back to grams.
        bytes: &'static [u8],
    },
}

/// A connected coffee scale: weight decoding plus command building, unified
/// across every protocol in this crate.
///
/// Construct one with [`Scale::identify`] (from a BLE scan name) or
/// [`Scale::from_label`]. The type is opaque — drive it through its methods.
/// It is not `Copy`: stateful scales (Acaia) carry a receive buffer.
#[derive(Debug)]
pub struct Scale {
    inner: Inner,
}

/// Per-scale variant and any runtime state it needs.
#[derive(Debug)]
enum Inner {
    /// The Decent Scale carries a small stateful struct ([`DecentScale`])
    /// that tracks the rolling tare counter and the observed firmware
    /// version — both fields the codec layer needs to issue the correct
    /// bytes (a tare with the next counter, or a power-off only when the
    /// firmware supports it).
    Decent(DecentScale),
    Skale,
    Felicita,
    Bookoo,
    AcaiaGen1(AcaiaDecoder),
    AcaiaPyxis(AcaiaDecoder),
    AtomheartEclair,
    EurekaPrecisa,
    SoloBarista,
    Difluid,
    /// The Smartchef has no BLE tare command. The variant carries a small
    /// stateful struct ([`SmartchefScale`]) that holds a software-tare
    /// offset (matches reaprime's `_weightAtTare`), applied to every
    /// reported weight.
    Smartchef(SmartchefScale),
    HiroiaJimmy,
    VariaAku,
}

impl Scale {
    /// Identify a scale from its BLE advertised name, returning a ready-to-use
    /// [`Scale`], or `None` if the name matches no supported scale.
    ///
    /// # Examples
    ///
    /// ```
    /// # use de1_scale::Scale;
    /// assert_eq!(Scale::identify("BOOKOO_SC1234").unwrap().label(), "Bookoo");
    /// assert!(Scale::identify("Some Phone").is_none());
    /// ```
    pub fn identify(advertised_name: &str) -> Option<Scale> {
        // First prefix match wins, in registry order — then build via the same
        // `from_label` the rest of the codebase uses, so identification and the
        // scan filter set (below) share one source of truth.
        SCALE_SCAN
            .iter()
            .find(|e| e.prefixes.iter().any(|p| advertised_name.starts_with(p)))
            .and_then(|e| Self::from_label(e.label))
    }

    /// The pre-connect BLE scan filter set across **all** supported scales —
    /// see [`ScaleScanUuids`]. Derived from the same [`SCALE_SCAN`] registry
    /// `identify` reads (name prefixes) and each variant's [`Scale::uuids`]
    /// (service UUIDs, deduplicated), so it stays generic + drift-free as
    /// scales are added. The shell scans with this, connects, then reads the
    /// connected scale's per-model characteristics from [`Scale::uuids`].
    #[must_use]
    pub fn scan_uuids() -> ScaleScanUuids {
        let mut service_uuids: Vec<&'static str> = Vec::new();
        let mut name_prefixes: Vec<&'static str> = Vec::new();
        for entry in SCALE_SCAN {
            name_prefixes.extend_from_slice(entry.prefixes);
            if let Some(scale) = Self::from_label(entry.label) {
                let service = scale.uuids().service;
                if !service_uuids.contains(&service) {
                    service_uuids.push(service);
                }
            }
        }
        ScaleScanUuids {
            service_uuids,
            name_prefixes,
        }
    }

    /// Sniff the first weight-notify packet to guess an advertised-name
    /// prefix for captures that pre-date the META prelude (where the
    /// connect-phase recorded the scale name). Returns a prefix that
    /// [`Scale::identify`] would consume, or `None` if no signature
    /// matches.
    ///
    /// Today only the Bookoo's `03 0b` weight-notify header is
    /// recognised — the documented signature in `bookoo::parse_packet`.
    /// Extend the signature table as more scales make it into Crema's
    /// replay fixtures. Returning `None` is non-fatal at the call site:
    /// the replay proceeds with no scale identified and an empty weight
    /// series.
    ///
    /// # Examples
    ///
    /// ```
    /// # use de1_scale::Scale;
    /// let bookoo_first = [0x03, 0x0b, 0x00, 0x00, 0x00];
    /// assert_eq!(Scale::guess_from_first_weight_packet(&bookoo_first), Some("BOOKOO_SC"));
    /// assert_eq!(Scale::guess_from_first_weight_packet(&[]), None);
    /// assert_eq!(Scale::guess_from_first_weight_packet(&[0xff, 0xff]), None);
    /// ```
    #[must_use]
    pub fn guess_from_first_weight_packet(bytes: &[u8]) -> Option<&'static str> {
        if bytes.len() >= 2 && bytes[0] == 0x03 && bytes[1] == 0x0b {
            return Some("BOOKOO_SC");
        }
        None
    }

    /// Reconstruct a scale from a [`label`](Self::label) string (the inverse of
    /// `label`, for persisting a chosen scale in settings). Returns a fresh
    /// instance with no accumulated state.
    pub fn from_label(label: &str) -> Option<Scale> {
        let inner = match label {
            "Decent Scale" => Inner::Decent(DecentScale::new()),
            "Skale II" => Inner::Skale,
            "Felicita Arc" => Inner::Felicita,
            "Bookoo" => Inner::Bookoo,
            "Acaia" => Inner::AcaiaGen1(AcaiaDecoder::new()),
            "Acaia Pyxis" => Inner::AcaiaPyxis(AcaiaDecoder::new()),
            "Atomheart Eclair" => Inner::AtomheartEclair,
            "Eureka Precisa" => Inner::EurekaPrecisa,
            "Solo Barista" => Inner::SoloBarista,
            "Difluid Microbalance" => Inner::Difluid,
            "Smartchef" => Inner::Smartchef(SmartchefScale::new()),
            "Hiroia Jimmy" => Inner::HiroiaJimmy,
            "Varia Aku" => Inner::VariaAku,
            _ => return None,
        };
        Some(Scale { inner })
    }

    /// A stable, human-readable name for this scale — suitable for display and
    /// as a settings key (round-trips through [`from_label`](Self::from_label)).
    pub fn label(&self) -> &'static str {
        match &self.inner {
            Inner::Decent(_) => "Decent Scale",
            Inner::Skale => "Skale II",
            Inner::Felicita => "Felicita Arc",
            Inner::Bookoo => "Bookoo",
            Inner::AcaiaGen1(_) => "Acaia",
            Inner::AcaiaPyxis(_) => "Acaia Pyxis",
            Inner::AtomheartEclair => "Atomheart Eclair",
            Inner::EurekaPrecisa => "Eureka Precisa",
            Inner::SoloBarista => "Solo Barista",
            Inner::Difluid => "Difluid Microbalance",
            Inner::Smartchef(_) => "Smartchef",
            Inner::HiroiaJimmy => "Hiroia Jimmy",
            Inner::VariaAku => "Varia Aku",
        }
    }

    /// The BLE service and characteristic UUIDs for this scale.
    pub fn uuids(&self) -> ScaleUuids {
        match &self.inner {
            Inner::Decent(_) => ScaleUuids {
                service: decent_scale::SERVICE_UUID,
                weight_notify: decent_scale::READ_NOTIFY_UUID,
                command_write: decent_scale::WRITE_UUID,
            },
            Inner::Skale => ScaleUuids {
                service: skale::SERVICE_UUID,
                weight_notify: skale::WEIGHT_NOTIFY_UUID,
                command_write: skale::COMMAND_UUID,
            },
            Inner::Felicita => ScaleUuids {
                service: felicita::SERVICE_UUID,
                weight_notify: felicita::NOTIFY_COMMAND_UUID,
                command_write: felicita::NOTIFY_COMMAND_UUID,
            },
            Inner::Bookoo => ScaleUuids {
                service: bookoo::SERVICE_UUID,
                weight_notify: bookoo::NOTIFY_UUID,
                command_write: bookoo::COMMAND_UUID,
            },
            Inner::AcaiaGen1(_) => ScaleUuids {
                service: acaia::GEN1_SERVICE_UUID,
                weight_notify: acaia::GEN1_NOTIFY_COMMAND_UUID,
                command_write: acaia::GEN1_NOTIFY_COMMAND_UUID,
            },
            Inner::AcaiaPyxis(_) => ScaleUuids {
                service: acaia::PYXIS_SERVICE_UUID,
                weight_notify: acaia::PYXIS_STATUS_UUID,
                command_write: acaia::PYXIS_COMMAND_UUID,
            },
            Inner::AtomheartEclair => ScaleUuids {
                service: atomheart_eclair::SERVICE_UUID,
                weight_notify: atomheart_eclair::NOTIFY_UUID,
                command_write: atomheart_eclair::COMMAND_UUID,
            },
            Inner::EurekaPrecisa | Inner::SoloBarista => ScaleUuids {
                service: eureka_precisa::SERVICE_UUID,
                weight_notify: eureka_precisa::STATUS_UUID,
                command_write: eureka_precisa::COMMAND_UUID,
            },
            Inner::Difluid => ScaleUuids {
                service: difluid::SERVICE_UUID,
                weight_notify: difluid::NOTIFY_COMMAND_UUID,
                command_write: difluid::NOTIFY_COMMAND_UUID,
            },
            Inner::Smartchef(_) => ScaleUuids {
                service: smartchef::SERVICE_UUID,
                weight_notify: smartchef::STATUS_UUID,
                command_write: smartchef::COMMAND_UUID,
            },
            Inner::HiroiaJimmy => ScaleUuids {
                service: hiroia_jimmy::SERVICE_UUID,
                weight_notify: hiroia_jimmy::STATUS_UUID,
                command_write: hiroia_jimmy::COMMAND_UUID,
            },
            Inner::VariaAku => ScaleUuids {
                service: varia_aku::SERVICE_UUID,
                weight_notify: varia_aku::STATUS_UUID,
                command_write: varia_aku::COMMAND_UUID,
            },
        }
    }

    /// Whether this scale accepts a software tare command.
    ///
    /// Every supported scale reports `true` today — including the Smartchef,
    /// which used to return `false` (no BLE tare command) but now exposes a
    /// software-tare offset (see [`crate::smartchef::SmartchefScale`]).
    pub fn supports_tare(&self) -> bool {
        // No supported scale rejects tare today; kept as a method for the
        // future where a new variant might (e.g. a totally read-only sensor).
        true
    }

    /// Whether this scale is a Decent Scale.
    ///
    /// The Decent Scale needs two pieces of behaviour none of the other
    /// supported scales do — an explicit on-scale LCD enable/disable, and a
    /// periodic [`decent_scale::HEARTBEAT`] to keep the display awake. The
    /// core gates those reactive writes on this check; every other scale
    /// returns `false` here and the writes are silently skipped.
    ///
    /// This is the single deliberate "device-driven" capability in the
    /// otherwise capability-driven scale surface, because the writes are
    /// Decent-specific and have no analogue on other scales — a generic
    /// capability would have only one implementation.
    pub fn is_decent_scale(&self) -> bool {
        matches!(&self.inner, Inner::Decent(_))
    }

    /// Record a Decent-Scale firmware-version observation (the byte
    /// extracted from a `0x0A` reply — see
    /// [`decent_scale::parse_command_response`]). No-op for every other
    /// scale.
    pub fn record_decent_scale_firmware_version(&mut self, version: DecentScaleFirmwareVersion) {
        if let Inner::Decent(state) = &mut self.inner {
            state.record_firmware_version(version);
        }
    }

    /// The currently-observed Decent-Scale firmware version, if any.
    /// `None` for a non-Decent scale or a Decent Scale whose firmware
    /// version has not yet been observed.
    pub fn decent_scale_firmware_version(&self) -> Option<DecentScaleFirmwareVersion> {
        match &self.inner {
            Inner::Decent(state) => state.firmware_version(),
            _ => None,
        }
    }

    /// Whether this scale accepts software timer commands.
    pub fn supports_timer(&self) -> bool {
        !matches!(
            &self.inner,
            Inner::AcaiaGen1(_)
                | Inner::AcaiaPyxis(_)
                | Inner::Smartchef(_)
                | Inner::HiroiaJimmy
                | Inner::VariaAku
        )
    }

    /// What this scale can do beyond reporting a bare weight — see
    /// [`ScaleCapabilities`].
    ///
    /// The app drives feature gating off this descriptor rather than off the
    /// concrete scale model: Crema is capability-driven, never device-driven.
    /// Weight-only scales return [`ScaleCapabilities::default`] (all `false`);
    /// the Bookoo, a "first-class" scale, reports the capabilities it supports.
    ///
    /// The `can_lcd` / `can_power_off` / `can_beep` / `can_set_unit_grams` /
    /// `can_toggle_unit` flags + `heartbeat_interval_ms` mirror the
    /// corresponding `*_command` methods returning `Some` — populated
    /// here in a single place so a shell can gate UI without invoking
    /// each capability and catching the `Unsupported` error.
    pub fn capabilities(&self) -> ScaleCapabilities {
        // The Some-ness of each command method IS the capability flag; derive
        // here so the two surfaces can never drift. `_unit` is irrelevant for
        // the `_lcd` flag — the flag is about whether the capability exists,
        // not what bytes the unit picks.
        let can_lcd = self.lcd_enable_command(true).is_some();
        let can_power_off = self.power_off_command().is_some();
        let can_beep = self.beep_command().is_some();
        let can_set_unit_grams = self.set_unit_grams_command().is_some();
        let can_toggle_unit = self.toggle_unit_command().is_some();
        let heartbeat_interval_ms = if self.heartbeat_command().is_some() {
            // u64 → u32 saturating; the constant is 2_000.
            Some(u32::try_from(decent_scale::HEARTBEAT_INTERVAL_MS).unwrap_or(u32::MAX))
        } else {
            None
        };
        match &self.inner {
            // The Bookoo carries native flow and a built-in timer in its
            // weight notification, and exposes a settable beeper volume
            // (0 = silent), a configurable auto-standby timeout, flow
            // smoothing / anti-mistouch toggles, an auto-stop-mode setting,
            // and three switchable display modes.
            Inner::Bookoo => ScaleCapabilities {
                reports_flow: true,
                reports_timer: true,
                // The hardware accepts volume 0..=5, but levels 4-5 are not
                // perceptibly louder than 3 (confirmed against the official
                // app) — so the exposed range is deliberately clamped to 0..=3.
                volume: Some(RangeCapability { min: 0, max: 3 }),
                standby: Some(RangeCapability { min: 5, max: 30 }),
                flow_smoothing: true,
                anti_mistouch: true,
                auto_stop: true,
                modes: vec![
                    ModeInfo {
                        id: bookoo::BookooMode::FlowRate.index(),
                        name: "Flow Rate".to_owned(),
                    },
                    ModeInfo {
                        id: bookoo::BookooMode::Timer.index(),
                        name: "Timer".to_owned(),
                    },
                    ModeInfo {
                        id: bookoo::BookooMode::Auto.index(),
                        name: "Auto".to_owned(),
                    },
                ],
                can_lcd,
                can_power_off,
                can_beep,
                can_set_unit_grams,
                can_toggle_unit,
                heartbeat_interval_ms,
            },
            // Every other supported scale is weight-only for the
            // first-class flags above (flow / timer / volume / standby /
            // smoothing / anti-mistouch / auto-stop / modes), but each
            // may still light up one or more of the per-command flags
            // derived above.
            Inner::Decent(_)
            | Inner::Skale
            | Inner::Felicita
            | Inner::AcaiaGen1(_)
            | Inner::AcaiaPyxis(_)
            | Inner::AtomheartEclair
            | Inner::EurekaPrecisa
            | Inner::SoloBarista
            | Inner::Difluid
            | Inner::Smartchef(_)
            | Inner::HiroiaJimmy
            | Inner::VariaAku => ScaleCapabilities {
                can_lcd,
                can_power_off,
                can_beep,
                can_set_unit_grams,
                can_toggle_unit,
                heartbeat_interval_ms,
                ..ScaleCapabilities::default()
            },
        }
    }

    /// The wire bytes that put the connected scale's on-scale LCD into
    /// "show weight" mode. `None` when the scale has no settable LCD,
    /// `Some(writes)` otherwise — each entry is one separate write the
    /// core should emit (Skale's enable sequence is 2–3 writes; Decent's
    /// is one).
    ///
    /// `grams` picks between byte variants where the scale's codec
    /// exposes both — Decent has separate `LCD_ENABLE_GRAMS` /
    /// `LCD_ENABLE_OUNCES` packets; Skale only sends the trailing
    /// `ENABLE_GRAMS` byte when grams is requested (no ounces-side
    /// variant exists). Take a bare bool here rather than the shell's
    /// `WeightUnit` enum so `de1-scale` doesn't have to depend on
    /// `de1-domain` (protocol layer stays free of domain types — see
    /// docs/25 naming conventions).
    #[must_use]
    pub fn lcd_enable_command(&self, grams: bool) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::Decent(_) => Some(vec![if grams {
                &decent_scale::LCD_ENABLE_GRAMS
            } else {
                &decent_scale::LCD_ENABLE_OUNCES
            }]),
            Inner::Skale => {
                let mut writes: Vec<&'static [u8]> =
                    vec![&[skale::CMD_SCREEN_ON], &[skale::CMD_DISPLAY_WEIGHT]];
                if grams {
                    writes.push(&[skale::CMD_ENABLE_GRAMS]);
                }
                Some(writes)
            }
            _ => None,
        }
    }

    /// The wire bytes that turn off the connected scale's on-scale LCD.
    /// `None` when the scale has no settable LCD.
    #[must_use]
    pub fn lcd_disable_command(&self) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::Decent(_) => Some(vec![&decent_scale::LCD_DISABLE]),
            Inner::Skale => Some(vec![&[skale::CMD_SCREEN_OFF]]),
            _ => None,
        }
    }

    /// The wire bytes that power-off the connected scale. `None` when
    /// the scale has no host-driven power-off. Used by both the
    /// explicit button-driven path and the optional sleep-on-DE1-sleep
    /// auto-policy.
    #[must_use]
    pub fn power_off_command(&self) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::Decent(_) => Some(vec![&decent_scale::POWER_OFF]),
            Inner::EurekaPrecisa | Inner::SoloBarista => Some(vec![&eureka_precisa::TURN_OFF]),
            _ => None,
        }
    }

    /// The wire bytes that fire a beep on the connected scale. `None`
    /// when the scale has no beep command.
    #[must_use]
    pub fn beep_command(&self) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::EurekaPrecisa | Inner::SoloBarista => Some(vec![&eureka_precisa::BEEP]),
            _ => None,
        }
    }

    /// The wire bytes that explicitly set the scale's display unit to
    /// grams. `None` when the scale has no set-to-grams command —
    /// either because it has no unit toggling at all (Decent, Skale —
    /// their unit lives in the LCD-enable bytes) or because it only
    /// exposes a toggle (Hiroia — see [`toggle_unit_command`](Self::toggle_unit_command)).
    #[must_use]
    pub fn set_unit_grams_command(&self) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::EurekaPrecisa | Inner::SoloBarista => {
                Some(vec![&eureka_precisa::SET_UNIT_GRAMS])
            }
            Inner::Difluid => Some(vec![&difluid::SET_UNIT_GRAMS]),
            _ => None,
        }
    }

    /// The wire bytes that toggle the scale's display unit (grams ↔
    /// ounces/ml). `None` when the scale has no toggle command — most
    /// scales prefer an explicit set (see
    /// [`set_unit_grams_command`](Self::set_unit_grams_command)).
    #[must_use]
    pub fn toggle_unit_command(&self) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::HiroiaJimmy => Some(vec![&hiroia_jimmy::TOGGLE_UNIT]),
            _ => None,
        }
    }

    /// The wire bytes for one keep-alive heartbeat. `None` when the
    /// scale's LCD doesn't need periodic refresh. The Decent Scale's
    /// LCD sleeps after a few seconds of host silence; the shell
    /// schedules the heartbeat clock.
    #[must_use]
    pub fn heartbeat_command(&self) -> Option<Vec<&'static [u8]>> {
        match &self.inner {
            Inner::Decent(_) => Some(vec![&decent_scale::HEARTBEAT]),
            _ => None,
        }
    }

    /// The capability-driven sibling of [`parse_reading`](Self::parse_reading)
    /// for scales whose firmware needs a unit-mode nudge. Returns:
    ///
    ///  - `None` when the scale is in grams, has no settable unit, or
    ///    `data` isn't a recognised weight notification.
    ///  - `Some(Drop { bytes })` when the scale's current unit makes the
    ///    weight value itself bogus — caller queues the bytes and skips
    ///    `parse_reading`.
    ///  - `Some(Continue { bytes })` when the numeric weight is still
    ///    valid (only the display unit is off) — caller queues the bytes
    ///    and parses the frame normally.
    ///
    /// Replaces the previous per-device `is_hiroia_jimmy()` /
    /// `is_difluid()` branches in `handle_scale_weight`. A 13th scale
    /// needing unit recovery is a one-file addition here, not a fresh
    /// branch in `CremaCore`.
    #[must_use]
    pub fn unit_recovery(&self, data: &[u8]) -> Option<UnitRecovery> {
        match &self.inner {
            // Hiroia: mode-byte > 0x08 means non-grams. The reported weight
            // is in that unit (lb / oz / ml), so it must NOT reach the
            // shell — drop the frame after queueing the toggle. Mirrors
            // reaprime (`hiroia_scale.dart:131-139`).
            Inner::HiroiaJimmy if hiroia_jimmy::is_non_grams_mode(data) == Some(true) => {
                Some(UnitRecovery::Drop {
                    bytes: &hiroia_jimmy::TOGGLE_UNIT,
                })
            }
            // Difluid: byte [17] of a weight notification flags non-grams,
            // but the weight bytes themselves remain a valid gram reading —
            // only the on-device display unit lies. Queue the recovery and
            // keep parsing. Mirrors reaprime (`difluid_scale.dart:147-154`).
            Inner::Difluid if difluid::is_grams_unit(data) == Some(false) => {
                Some(UnitRecovery::Continue {
                    bytes: &difluid::SET_UNIT_GRAMS,
                })
            }
            _ => None,
        }
    }

    /// The scale's sensor lag — the delay between coffee landing in the cup
    /// and the scale reporting the new weight. It is one term of the SAW
    /// (stop-at-weight) stop-early prediction.
    ///
    /// Values are from the legacy app (`device_scale.tcl`), itself derived
    /// from James Hoffmann's scale-latency measurements plus BLE delay.
    pub fn sensor_lag(&self) -> Duration {
        let ms: u64 = match &self.inner {
            Inner::HiroiaJimmy => 250,
            Inner::Felicita | Inner::Bookoo | Inner::AtomheartEclair => 500,
            // The legacy switch has no `acaiapyxis` arm (a Pyxis would hit the
            // 0.38 default); the Pyxis is physically an Acaia, so it shares the
            // Acaia sensor lag here — a deliberate correction of that gap.
            Inner::AcaiaGen1(_) | Inner::AcaiaPyxis(_) => 690,
            // Decent, Skale, and the scales without a dedicated entry take the
            // legacy 380 ms default.
            Inner::Decent(_)
            | Inner::Skale
            | Inner::EurekaPrecisa
            | Inner::SoloBarista
            | Inner::Difluid
            | Inner::Smartchef(_)
            | Inner::VariaAku => 380,
        };
        Duration::from_millis(ms)
    }

    /// Decode a weight notification into grams.
    ///
    /// Takes `&mut self` because framed protocols (Acaia) buffer bytes across
    /// notifications; stateless scales ignore the mutability. Returns `None`
    /// if the bytes are not (yet) a complete weight reading.
    pub fn parse_weight(&mut self, data: &[u8]) -> Option<f32> {
        match &mut self.inner {
            Inner::Decent(_) => decent_scale::parse_weight(data),
            Inner::Skale => skale::parse_weight(data),
            Inner::Felicita => felicita::parse_weight(data),
            Inner::Bookoo => bookoo::parse_weight(data),
            Inner::AcaiaGen1(decoder) | Inner::AcaiaPyxis(decoder) => decoder.push(data),
            Inner::AtomheartEclair => atomheart_eclair::parse_weight(data),
            Inner::EurekaPrecisa | Inner::SoloBarista => eureka_precisa::parse_weight(data),
            Inner::Difluid => difluid::parse_weight(data),
            Inner::Smartchef(state) => {
                // Smartchef firmware has no tare command; the user-visible
                // weight is `raw - software_offset`. Record every raw reading
                // so a subsequent `Scale::tare()` can capture it.
                let raw = smartchef::parse_weight(data)?;
                state.record_raw_weight(raw);
                Some(state.apply_offset(raw))
            }
            Inner::HiroiaJimmy => hiroia_jimmy::parse_weight(data),
            Inner::VariaAku => varia_aku::parse_weight(data),
        }
    }

    /// Decode a scale notification into a [`ScaleReading`].
    ///
    /// Like [`parse_weight`](Self::parse_weight) but also carries any extra
    /// fields the scale reports in the same notification — the Bookoo's native
    /// flow rate and built-in timer. For every other scale `flow_g_per_s` and
    /// `timer_ms` are `None`. Returns `None` if the bytes are not (yet) a
    /// complete reading.
    pub fn parse_reading(&mut self, data: &[u8]) -> Option<ScaleReading> {
        if let Inner::Bookoo = &self.inner {
            // The Bookoo's 20-byte notification carries weight, native flow,
            // the built-in timer and its live settings in one packet.
            if let Some(packet) = bookoo::parse_packet(data) {
                return Some(ScaleReading {
                    weight_g: packet.weight_g,
                    flow_g_per_s: Some(packet.flow_g_per_s),
                    timer_ms: Some(packet.timer_ms),
                    volume: Some(packet.volume),
                    standby_minutes: Some(packet.standby_minutes),
                    battery_percent: Some(packet.battery_percent),
                    flow_smoothing: Some(packet.flow_smoothing),
                    auto_stop: Some(packet.auto_stop),
                });
            }
            // Fall through to the weight-only path for any non-20-byte frame.
        }
        // Pull a per-scale battery byte where the wire format carries one in
        // the same notification as weight. Computed before the weight decode
        // so the lookup doesn't borrow `self.inner` past `parse_weight`.
        let felicita_battery = matches!(&self.inner, Inner::Felicita)
            .then(|| felicita::parse_battery_percent(data))
            .flatten();
        // Atomheart Eclair onboard timer — reaprime parses this; legacy
        // drops it. See `atomheart_scale.dart:160-181`.
        let atomheart_timer_ms = matches!(&self.inner, Inner::AtomheartEclair)
            .then(|| atomheart_eclair::parse_timer(data))
            .flatten()
            .and_then(|d| u32::try_from(d.as_millis()).ok());
        // Varia AKU's notifications come in two shapes — weight (command
        // 0x01) and battery (command 0x85). The latter does not carry
        // weight, so the unified path returns `None` from `parse_weight`,
        // and the caller is meant to keep going for the next frame.
        // Capture the battery here so a battery-only frame still surfaces
        // the field; pair it with weight = 0 only if a weight frame also
        // matches.
        let varia_battery = matches!(&self.inner, Inner::VariaAku)
            .then(|| varia_aku::parse_battery_percent(data))
            .flatten();
        // Acaia's framed protocol carries battery on `msgType == 8` rather
        // than the weight frame, but the decoder reads both off the same
        // characteristic — query the running value here.
        let acaia_battery = match &self.inner {
            Inner::AcaiaGen1(decoder) | Inner::AcaiaPyxis(decoder) => decoder.battery_percent(),
            _ => None,
        };
        self.parse_weight(data).map(|weight_g| ScaleReading {
            weight_g,
            flow_g_per_s: None,
            timer_ms: atomheart_timer_ms,
            volume: None,
            standby_minutes: None,
            battery_percent: felicita_battery.or(varia_battery).or(acaia_battery),
            flow_smoothing: None,
            auto_stop: None,
        })
    }

    /// Decode a notification from the scale's *command* characteristic
    /// into a device-agnostic [`ScaleConfigUpdate`].
    ///
    /// Some scales' command characteristic also has the NOTIFY property
    /// and the scale pushes structured responses back on it — the
    /// Bookoo answers a settings / serial query there. Only the Bookoo
    /// decodes such a frame today; every weight-only scale returns
    /// `None`, as does the Bookoo for a frame that is not a recognised
    /// command response. The return is intentionally generic so the
    /// orchestrator doesn't import the per-device type.
    pub fn parse_command_response(&self, data: &[u8]) -> Option<ScaleConfigUpdate> {
        match &self.inner {
            Inner::Bookoo => bookoo::parse_command_response(data).map(ScaleConfigUpdate::from),
            // No other supported scale models a command-channel response.
            Inner::Decent(_)
            | Inner::Skale
            | Inner::Felicita
            | Inner::AcaiaGen1(_)
            | Inner::AcaiaPyxis(_)
            | Inner::AtomheartEclair
            | Inner::EurekaPrecisa
            | Inner::SoloBarista
            | Inner::Difluid
            | Inner::Smartchef(_)
            | Inner::HiroiaJimmy
            | Inner::VariaAku => None,
        }
    }

    /// Decode a notification from the *Decent Scale's* read characteristic
    /// into a [`decent_scale::CommandResponse`].
    ///
    /// The Decent Scale notifies on a single characteristic for both
    /// weight packets and command replies; the `0x0A` (LCD / heartbeat)
    /// reply carries the battery and firmware-version bytes. This method
    /// returns the decoded reply when the frame is one of those replies,
    /// and `None` for a weight packet, a button packet, a tare-ack, or
    /// any non-Decent scale.
    ///
    /// The shell calls this on every notification *after* [`parse_reading`]
    /// returns `None`, so a weight notification still flows through
    /// `parse_reading` as before; only non-weight Decent frames reach
    /// this path.
    ///
    /// [`parse_reading`]: Self::parse_reading
    pub fn parse_decent_scale_command_response(
        &self,
        data: &[u8],
    ) -> Option<decent_scale::CommandResponse> {
        match &self.inner {
            Inner::Decent(_) => decent_scale::parse_command_response(data),
            _ => None,
        }
    }

    /// Inspect a frame that [`parse_reading`] couldn't decode and update
    /// any device-specific state it carries — today that means snapping
    /// the Decent Scale's firmware version from a `0x0A` LCD-ack frame.
    /// Returns silently for every scale that has nothing to absorb (most
    /// of them); the shell calls this once on every non-weight
    /// notification without having to name the device.
    ///
    /// Centralising the device-specific branch here keeps `de1-app`'s
    /// generic `handle_scale_weight` from destructuring
    /// `decent_scale::CommandResponse::LcdAck` — the shell stops naming
    /// Decent entirely.
    ///
    /// [`parse_reading`]: Self::parse_reading
    pub fn absorb_unmatched_frame(&mut self, data: &[u8]) {
        if matches!(self.inner, Inner::Decent(_))
            && let Some(decent_scale::CommandResponse::LcdAck {
                firmware_version, ..
            }) = decent_scale::parse_command_response(data)
        {
            self.record_decent_scale_firmware_version(firmware_version);
        }
    }

    /// Build the command bytes that query the connected scale's current
    /// configurable settings — the response lands on the scale's command
    /// characteristic. `None` when the scale has no queryable settings
    /// surface (every weight-only scale). The orchestrator uses this to
    /// (a) issue a one-shot query on first connect and (b) re-sync after
    /// every config write.
    #[must_use]
    pub fn query_settings_command(&self) -> Option<Vec<u8>> {
        match &self.inner {
            Inner::Bookoo => Some(bookoo::QUERY_SETTINGS.to_vec()),
            _ => None,
        }
    }

    /// Build the command bytes that query the connected scale's serial /
    /// firmware info, including the anti-mistouch state. `None` when the
    /// scale has no such query — every weight-only scale. The orchestrator
    /// issues this once on connect.
    #[must_use]
    pub fn query_serial_command(&self) -> Option<Vec<u8>> {
        match &self.inner {
            Inner::Bookoo => Some(bookoo::QUERY_SERIAL.to_vec()),
            _ => None,
        }
    }

    /// Build the command bytes that set the connected scale's beeper
    /// volume to `level`. `level` is clamped to the scale's
    /// [`ScaleCapabilities::volume`] range; the result is `None` when
    /// the scale exposes no settable volume.
    #[must_use]
    pub fn set_volume_command(&self, level: u8) -> Option<Vec<u8>> {
        let range = self.capabilities().volume?;
        let level = level.clamp(range.min, range.max);
        match &self.inner {
            Inner::Bookoo => Some(bookoo::set_volume(level).to_vec()),
            _ => None,
        }
    }

    /// Build the command bytes that set the connected scale's
    /// auto-standby timeout to `minutes`. `minutes` is clamped to the
    /// scale's [`ScaleCapabilities::standby`] range; `None` when the
    /// scale has no configurable standby.
    #[must_use]
    pub fn set_standby_command(&self, minutes: u8) -> Option<Vec<u8>> {
        let range = self.capabilities().standby?;
        let minutes = minutes.clamp(range.min, range.max);
        match &self.inner {
            Inner::Bookoo => Some(bookoo::set_standby_minutes(minutes).to_vec()),
            _ => None,
        }
    }

    /// Build the command bytes that toggle the connected scale's flow
    /// smoothing. `None` when the scale doesn't expose the setting
    /// ([`ScaleCapabilities::flow_smoothing`] is `false`).
    #[must_use]
    pub fn set_flow_smoothing_command(&self, enabled: bool) -> Option<Vec<u8>> {
        if !self.capabilities().flow_smoothing {
            return None;
        }
        match &self.inner {
            Inner::Bookoo => Some(bookoo::set_flow_smoothing(enabled).to_vec()),
            _ => None,
        }
    }

    /// Build the command bytes that toggle the connected scale's
    /// anti-mistouch protection. `None` when the scale doesn't expose
    /// the setting ([`ScaleCapabilities::anti_mistouch`] is `false`).
    #[must_use]
    pub fn set_anti_mistouch_command(&self, enabled: bool) -> Option<Vec<u8>> {
        if !self.capabilities().anti_mistouch {
            return None;
        }
        match &self.inner {
            Inner::Bookoo => Some(bookoo::set_anti_mistouch(enabled).to_vec()),
            _ => None,
        }
    }

    /// Build the ordered command sequence that switches the connected
    /// scale to the display mode identified by `mode_id` (the value
    /// from a [`ModeInfo::id`] entry on
    /// [`ScaleCapabilities::modes`]).
    ///
    /// Selecting a mode on a Bookoo is three writes — the target mode
    /// is enabled first, then the other two are disabled — so an
    /// enabled mode always exists mid-sequence. The shell must perform
    /// the writes in the returned order. `None` when the scale has no
    /// switchable modes or `mode_id` matches none of them.
    #[must_use]
    pub fn select_mode_command(&self, mode_id: u8) -> Option<Vec<Vec<u8>>> {
        if !self.capabilities().modes.iter().any(|m| m.id == mode_id) {
            return None;
        }
        match &self.inner {
            Inner::Bookoo => {
                let mode = match mode_id {
                    x if x == bookoo::BookooMode::FlowRate.index() => bookoo::BookooMode::FlowRate,
                    x if x == bookoo::BookooMode::Timer.index() => bookoo::BookooMode::Timer,
                    x if x == bookoo::BookooMode::Auto.index() => bookoo::BookooMode::Auto,
                    _ => return None,
                };
                Some(
                    bookoo::select_mode(mode)
                        .iter()
                        .map(|cmd| cmd.to_vec())
                        .collect(),
                )
            }
            _ => None,
        }
    }

    /// Build the command bytes that set the connected scale's
    /// auto-stop mode (`0` = flow-stop, `1` = cup-removal). `None`
    /// when the scale exposes no auto-stop mode setting
    /// ([`ScaleCapabilities::auto_stop`] is `false`) or `mode_id`
    /// maps to no known mode.
    #[must_use]
    pub fn set_auto_stop_command(&self, mode_id: u8) -> Option<Vec<u8>> {
        if !self.capabilities().auto_stop {
            return None;
        }
        match &self.inner {
            Inner::Bookoo => {
                let mode = match mode_id {
                    0 => bookoo::AutoStopMode::FlowStop,
                    1 => bookoo::AutoStopMode::CupRemoval,
                    _ => return None,
                };
                Some(bookoo::set_auto_stop_mode(mode).to_vec())
            }
            _ => None,
        }
    }

    /// Format the Bookoo scale's `u16` encoded firmware version as a
    /// `"M.m.p"` string (e.g. `141` → `"1.4.1"`). Bookoo-only today —
    /// wraps [`bookoo::format_firmware_version`] so a wasm/UniFFI
    /// bridge can format the version without taking a direct dep on
    /// the per-device bookoo module.
    #[must_use]
    pub fn format_bookoo_firmware_version(encoded: u16) -> String {
        bookoo::format_firmware_version(encoded)
    }

    /// Build a tare command to write to the [command characteristic](ScaleUuids).
    /// Returns `None` if the scale tares purely client-side and there is no
    /// command to send (the Smartchef — see [`crate::smartchef::SmartchefScale`]).
    ///
    /// For the Smartchef this call updates the software-tare offset in place
    /// and returns `None`; the next `parse_weight` will report a near-zero
    /// reading without any BLE write. Every other scale returns the wire
    /// bytes the shell should write to [`ScaleUuids::command_write`].
    pub fn tare(&mut self) -> Option<Vec<u8>> {
        Some(match &mut self.inner {
            Inner::Decent(state) => state.next_tare().to_vec(),
            Inner::Skale => vec![skale::CMD_TARE],
            Inner::Felicita => vec![felicita::TARE],
            Inner::Bookoo => bookoo::TARE.to_vec(),
            Inner::AcaiaGen1(_) | Inner::AcaiaPyxis(_) => acaia::TARE.to_vec(),
            Inner::AtomheartEclair => atomheart_eclair::TARE.to_vec(),
            Inner::EurekaPrecisa | Inner::SoloBarista => eureka_precisa::TARE.to_vec(),
            Inner::Difluid => difluid::TARE.to_vec(),
            Inner::Smartchef(state) => {
                // Smartchef firmware has no tare command — record the current
                // raw reading as the new offset and return `None` so the shell
                // skips the write step. The next notification's user-visible
                // weight will be `raw - offset`.
                state.tare();
                return None;
            }
            Inner::HiroiaJimmy => hiroia_jimmy::TARE.to_vec(),
            Inner::VariaAku => varia_aku::TARE.to_vec(),
        })
    }

    /// Build a timer command to write to the [command characteristic](ScaleUuids).
    /// Returns `None` if the scale has no software timer (see
    /// [`supports_timer`](Self::supports_timer)).
    pub fn timer(&self, command: TimerCommand) -> Option<Vec<u8>> {
        use TimerCommand::{Reset, Start, Stop};
        Some(match &self.inner {
            Inner::Decent(_) => match command {
                Start => decent_scale::timer_start().to_vec(),
                Stop => decent_scale::timer_stop().to_vec(),
                Reset => decent_scale::timer_reset().to_vec(),
            },
            Inner::Skale => vec![match command {
                Start => skale::CMD_TIMER_START,
                Stop => skale::CMD_TIMER_STOP,
                Reset => skale::CMD_TIMER_RESET,
            }],
            Inner::Felicita => vec![match command {
                Start => felicita::TIMER_START,
                Stop => felicita::TIMER_STOP,
                Reset => felicita::TIMER_RESET,
            }],
            Inner::Bookoo => match command {
                Start => bookoo::TIMER_START.to_vec(),
                Stop => bookoo::TIMER_STOP.to_vec(),
                Reset => bookoo::TIMER_RESET.to_vec(),
            },
            Inner::AtomheartEclair => match command {
                Start => atomheart_eclair::TIMER_START.to_vec(),
                Stop => atomheart_eclair::TIMER_STOP.to_vec(),
                // The Eclair has no separate reset — tare resets the timer.
                Reset => atomheart_eclair::TARE.to_vec(),
            },
            Inner::EurekaPrecisa | Inner::SoloBarista => match command {
                Start => eureka_precisa::TIMER_START.to_vec(),
                Stop => eureka_precisa::TIMER_STOP.to_vec(),
                Reset => eureka_precisa::TIMER_RESET.to_vec(),
            },
            Inner::Difluid => match command {
                Start => difluid::TIMER_START.to_vec(),
                Stop => difluid::TIMER_STOP.to_vec(),
                Reset => difluid::TIMER_RESET.to_vec(),
            },
            Inner::AcaiaGen1(_)
            | Inner::AcaiaPyxis(_)
            | Inner::Smartchef(_)
            | Inner::HiroiaJimmy
            | Inner::VariaAku => return None,
        })
    }

    /// Whether the connected scale is a Skale II (Atomax).
    ///
    /// The Skale needs an explicit LCD-enable / LCD-disable surface like the
    /// Decent Scale — see [`is_decent_scale`](Self::is_decent_scale). The
    /// shell uses this gate to fire `ED EC` (display-on + display-weight) on
    /// machine Idle entry and `EE` (display-off) on Sleep entry. Every other
    /// scale returns `false`.
    pub fn is_skale(&self) -> bool {
        matches!(&self.inner, Inner::Skale)
    }

    /// Whether the connected scale is a Eureka Precisa (CFS-9002) or its
    /// codec-identical sibling, the Solo Barista (LSJ-001).
    ///
    /// Both scales accept the same 4-byte commands and only differ in the
    /// advertised name. The shell uses this gate to fire the optional
    /// turn-off / beep / set-unit-grams commands behind the user-controlled
    /// "Eureka auto-off on sleep" toggle.
    pub fn is_eureka_precisa(&self) -> bool {
        matches!(&self.inner, Inner::EurekaPrecisa | Inner::SoloBarista)
    }

    /// Whether the connected scale is a Solo Barista (LSJ-001) specifically.
    ///
    /// Returns `false` for an Eureka Precisa even though both share the
    /// codec — kept distinct so a Settings page can branch on the
    /// user-visible scale identity if it ever needs to.
    pub fn is_solo_barista(&self) -> bool {
        matches!(&self.inner, Inner::SoloBarista)
    }

    /// Whether the connected scale is a Hiroia Jimmy.
    ///
    /// The Hiroia accepts a "toggle display unit" command not present on any
    /// other scale; the shell fires it from the weight-notification path
    /// when the scale's mode byte indicates a non-grams unit (matches
    /// reaprime's auto-recovery — `hiroia_scale.dart:131-139`).
    pub fn is_hiroia_jimmy(&self) -> bool {
        matches!(&self.inner, Inner::HiroiaJimmy)
    }

    /// Whether the connected scale is a Difluid Microbalance.
    ///
    /// The Difluid auto-corrects unit drift by re-sending `SET_UNIT_GRAMS`
    /// whenever a weight notification reports a non-grams unit
    /// (`difluid_scale.dart:147-154`). The shell fires the recovery from
    /// `CremaCore::on_notification`'s scale-weight path.
    pub fn is_difluid(&self) -> bool {
        matches!(&self.inner, Inner::Difluid)
    }

    /// Whether the connected scale is a Smartchef.
    ///
    /// The Smartchef firmware has no BLE tare or timer command. The shell
    /// doesn't need to gate any reactive writes on this — the unified
    /// [`tare`](Self::tare) returns `None` and applies the software-tare
    /// offset on the codec side — but the capability check is exposed for
    /// symmetry with the other `is_*` helpers and for tests.
    pub fn is_smartchef(&self) -> bool {
        matches!(&self.inner, Inner::Smartchef(_))
    }

    /// Whether the connected scale is a Varia AKU (Pro / Mini / Plus / Micro).
    ///
    /// Exposed for symmetry; the Varia AKU has no reactive write surface
    /// today beyond what the unified [`tare`](Self::tare) covers.
    pub fn is_varia_aku(&self) -> bool {
        matches!(&self.inner, Inner::VariaAku)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identifies_scales_from_scan_names() {
        assert_eq!(Scale::identify("BOOKOO_SC1234").unwrap().label(), "Bookoo");
        assert_eq!(
            Scale::identify("Decent Scale ABC").unwrap().label(),
            "Decent Scale"
        );
        assert_eq!(Scale::identify("PYXIS-9").unwrap().label(), "Acaia Pyxis");
        assert_eq!(Scale::identify("ACAIA-X").unwrap().label(), "Acaia");
    }

    #[test]
    fn an_unknown_scan_name_is_not_identified() {
        assert!(Scale::identify("Random Phone").is_none());
    }

    #[test]
    fn scan_uuids_cover_every_identifiable_scale_generically() {
        let scan = Scale::scan_uuids();
        // No drift from `identify`: every advertised-name prefix in the scan
        // set actually identifies a scale (both read the SCALE_SCAN registry).
        for prefix in &scan.name_prefixes {
            assert!(
                Scale::identify(prefix).is_some(),
                "scan prefix {prefix:?} should identify a scale"
            );
        }
        // It's generic — not Bookoo-only: prefixes from several families are
        // present (Bookoo, Decent, Acaia, Felicita).
        for p in ["BOOKOO_SC", "Decent Scale", "PYXIS", "FELICITA"] {
            assert!(scan.name_prefixes.contains(&p), "missing prefix {p}");
        }
        // Service UUIDs are deduplicated: the four scales sharing `0000fff0`
        // (decent / eureka / smartchef / varia) collapse to one entry.
        let fff0 = "0000fff0-0000-1000-8000-00805f9b34fb";
        assert_eq!(
            scan.service_uuids.iter().filter(|u| **u == fff0).count(),
            1,
            "the shared fff0 service must appear once"
        );
        // The Bookoo service is present, and every UUID is a 128-bit dashed form.
        assert!(scan.service_uuids.contains(&crate::bookoo::SERVICE_UUID));
        for uuid in &scan.service_uuids {
            assert_eq!(uuid.len(), 36, "service uuid {uuid:?} should be 128-bit");
        }
    }

    #[test]
    fn label_round_trips_through_from_label() {
        for name in [
            "BOOKOO_SC1",
            "Skale-9",
            "FELICITA-A",
            "smartchef-1",
            "ACAIA-1",
        ] {
            let label = Scale::identify(name).unwrap().label();
            assert_eq!(Scale::from_label(label).unwrap().label(), label);
        }
    }

    #[test]
    fn parse_weight_dispatches_to_the_right_codec() {
        let mut bookoo = Scale::from_label("Bookoo").unwrap();
        let packet = [0, 0, 0, 0, 0, 0, b'+', 0x00, 0x07, 0xD0];
        assert_eq!(bookoo.parse_weight(&packet), Some(20.0));
    }

    #[test]
    fn parse_reading_carries_bookoo_native_flow_and_timer() {
        let mut bookoo = Scale::from_label("Bookoo").unwrap();
        // A real 20-byte capture: weight 492.60 g, flow raw 0x312F, timer 0.
        let packet: Vec<u8> = (0.."030b000000012b00c06c2b312f64009601000048".len())
            .step_by(2)
            .map(|i| {
                u8::from_str_radix(&"030b000000012b00c06c2b312f64009601000048"[i..i + 2], 16)
                    .unwrap()
            })
            .collect();
        let reading = bookoo.parse_reading(&packet).expect("20-byte packet");
        assert!((reading.weight_g - 492.60).abs() < 0.001);
        assert_eq!(reading.flow_g_per_s, Some(125.91));
        assert_eq!(reading.timer_ms, Some(0));
        // The same packet's [13-18] = 64 0096 01 00 00: live settings.
        assert_eq!(reading.volume, Some(1));
        assert_eq!(reading.standby_minutes, Some(15));
        assert_eq!(reading.battery_percent, Some(100));
        // [17] = 0x00 -> flow smoothing off.
        assert_eq!(reading.flow_smoothing, Some(false));
        // [18] = 0x00 -> auto-stop mode 0 (Flow-Stop).
        assert_eq!(reading.auto_stop, Some(0));
    }

    #[test]
    fn parse_reading_carries_bookoo_live_settings_from_a_real_capture() {
        let mut bookoo = Scale::from_label("Bookoo").unwrap();
        // Real capture packet: [16] volume 3, [14-15] 0x012C -> 30 min standby.
        let hex = "030b000000012b0000002b000264012c03000140";
        let packet: Vec<u8> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        let reading = bookoo.parse_reading(&packet).expect("20-byte packet");
        assert_eq!(reading.volume, Some(3));
        assert_eq!(reading.standby_minutes, Some(30));
        assert_eq!(reading.battery_percent, Some(100));
        assert_eq!(reading.flow_smoothing, Some(false));
        // [18] = 0x01 -> auto-stop mode 1 (Cup-Removal).
        assert_eq!(reading.auto_stop, Some(1));
    }

    #[test]
    fn parse_reading_carries_bookoo_flow_smoothing_on_from_a_real_capture() {
        let mut bookoo = Scale::from_label("Bookoo").unwrap();
        // Real capture packet recorded after set_flow_smoothing(true):
        // [17] = 0x01 -> flow smoothing on.
        let hex = "030b000000012b0000002b000064012c03010143";
        let packet: Vec<u8> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        let reading = bookoo.parse_reading(&packet).expect("20-byte packet");
        assert_eq!(reading.flow_smoothing, Some(true));
    }

    #[test]
    fn parse_reading_leaves_flow_and_timer_none_for_a_weight_only_scale() {
        let mut decent = Scale::from_label("Decent Scale").unwrap();
        // Decent weight frame: type byte 0xCE, weight 0x07D0 = 2000 -> 200.0 g.
        let frame = [0x03, 0xCE, 0x07, 0xD0, 0x00, 0x00, 0x00];
        let reading = decent.parse_reading(&frame).expect("a weight packet");
        assert_eq!(reading.weight_g, 200.0);
        assert_eq!(reading.flow_g_per_s, None);
        assert_eq!(reading.timer_ms, None);
        // A weight-only scale reports no live settings.
        assert_eq!(reading.volume, None);
        assert_eq!(reading.standby_minutes, None);
        assert_eq!(reading.battery_percent, None);
        assert_eq!(reading.flow_smoothing, None);
        assert_eq!(reading.auto_stop, None);
    }

    #[test]
    fn bookoo_parses_a_command_channel_serial_response() {
        let bookoo = Scale::from_label("Bookoo").unwrap();
        // A real 03 0c serial response: anti-mistouch on.
        let hex = "030c008d534e32343030643636613839653701ce";
        let frame: Vec<u8> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        assert_eq!(
            bookoo.parse_command_response(&frame),
            Some(ScaleConfigUpdate::SerialInfo {
                firmware_version: 141,
                serial: "SN2400d66a89e7".to_owned(),
                anti_mistouch: true,
            })
        );
    }

    #[test]
    fn a_weight_only_scale_parses_no_command_channel_response() {
        let decent = Scale::from_label("Decent Scale").unwrap();
        // The same 03 0e settings frame the Bookoo decodes — a weight-only
        // scale models no command channel, so it returns None.
        let hex = "030e02040000000000000000000000000000000b";
        let frame: Vec<u8> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        assert_eq!(decent.parse_command_response(&frame), None);
    }

    #[test]
    fn parse_weight_reassembles_an_acaia_frame_across_notifications() {
        let mut acaia = Scale::from_label("Acaia").unwrap();
        let frame = [0xEF, 0xDD, 12, 6, 5, 180, 0, 0, 0, 1, 0];
        assert_eq!(acaia.parse_weight(&frame[..4]), None);
        assert_eq!(acaia.parse_weight(&frame[4..]), Some(18.0));
    }

    #[test]
    fn is_decent_scale_only_true_for_a_decent_scale() {
        assert!(Scale::from_label("Decent Scale").unwrap().is_decent_scale());
        for label in [
            "Bookoo",
            "Skale II",
            "Felicita Arc",
            "Acaia",
            "Acaia Pyxis",
            "Atomheart Eclair",
            "Eureka Precisa",
            "Solo Barista",
            "Difluid Microbalance",
            "Smartchef",
            "Hiroia Jimmy",
            "Varia Aku",
        ] {
            assert!(
                !Scale::from_label(label).unwrap().is_decent_scale(),
                "{label} should not report as a Decent Scale"
            );
        }
    }

    #[test]
    fn decent_tare_counter_increments_each_call() {
        let mut decent = Scale::from_label("Decent Scale").unwrap();
        let first = decent.tare().unwrap();
        let second = decent.tare().unwrap();
        // Byte 2 of a Decent tare command is the rolling counter.
        assert_eq!(second[2], first[2].wrapping_add(1));
    }

    #[test]
    fn smartchef_tare_is_software_only_and_returns_no_wire_bytes() {
        // The Smartchef has no BLE tare command; tare is software-side via
        // the offset on `SmartchefScale`. `Scale::tare()` returns `None` to
        // signal "no wire write needed" but `supports_tare()` is still `true`
        // — the user gets a tared reading on the next notification.
        let mut smartchef = Scale::from_label("Smartchef").unwrap();
        assert!(smartchef.supports_tare());
        assert!(!smartchef.supports_timer());
        assert_eq!(smartchef.tare(), None);
        assert_eq!(smartchef.timer(TimerCommand::Start), None);
    }

    #[test]
    fn smartchef_software_tare_zeroes_the_next_reading() {
        // Push a raw 18.0 g reading, tare, push the same raw weight — the
        // user-visible weight after tare should be ~0.
        let mut smartchef = Scale::from_label("Smartchef").unwrap();
        let frame = [0u8, 0, 0, 0, 0, 0x00, 0xB4]; // raw 18.0 g
        assert_eq!(smartchef.parse_weight(&frame), Some(18.0));
        // Tare captures the most recent raw reading as the offset.
        assert_eq!(smartchef.tare(), None);
        // The same raw reading now decodes as zero.
        assert_eq!(smartchef.parse_weight(&frame), Some(0.0));
    }

    #[test]
    fn unit_recovery_is_none_for_a_scale_without_settable_units() {
        let bookoo = Scale::from_label("Bookoo").unwrap();
        // Even a properly-formed Bookoo weight notification carries no
        // unit signal, so the recovery surface is silent.
        assert_eq!(bookoo.unit_recovery(&[0x03, 0x0b, 0x00, 0x00, 0x00]), None);
    }

    #[test]
    fn unit_recovery_drops_the_frame_for_a_hiroia_in_non_grams() {
        let hiroia = Scale::from_label("Hiroia Jimmy").unwrap();
        // Mode-byte > 0x08 → non-grams. The reading is in ounces / lb /
        // ml; the toggle must be queued and the frame dropped.
        let mut packet = [0u8; 16];
        packet[0] = 0x09;
        match hiroia.unit_recovery(&packet) {
            Some(UnitRecovery::Drop { bytes }) => assert_eq!(bytes, &hiroia_jimmy::TOGGLE_UNIT),
            other => panic!("expected Drop, got {other:?}"),
        }
    }

    #[test]
    fn unit_recovery_is_none_for_a_hiroia_already_in_grams() {
        let hiroia = Scale::from_label("Hiroia Jimmy").unwrap();
        let mut packet = [0u8; 16];
        packet[0] = 0x01; // a grams-mode byte
        assert_eq!(hiroia.unit_recovery(&packet), None);
    }

    #[test]
    fn unit_recovery_continues_for_a_difluid_in_non_grams() {
        let difluid = Scale::from_label("Difluid Microbalance").unwrap();
        // Byte [17] non-zero → display unit is off, but the weight bytes
        // are still in grams. Queue + keep parsing.
        let mut packet = [0u8; 20];
        packet[17] = 0x01;
        match difluid.unit_recovery(&packet) {
            Some(UnitRecovery::Continue { bytes }) => {
                assert_eq!(bytes, &difluid::SET_UNIT_GRAMS)
            }
            other => panic!("expected Continue, got {other:?}"),
        }
    }

    #[test]
    fn unit_recovery_is_silent_for_a_decent_scale() {
        // Decent Scale doesn't carry a unit byte the core polices — this
        // surface must stay None so a stray `0x0A` LCD-ack frame doesn't
        // get misclassified.
        let decent = Scale::from_label("Decent Scale").unwrap();
        assert_eq!(decent.unit_recovery(&[0x0a; 7]), None);
    }

    #[test]
    fn bookoo_reports_first_class_capabilities() {
        let bookoo = Scale::from_label("Bookoo").unwrap();
        let caps = bookoo.capabilities();
        assert!(caps.reports_flow);
        assert!(caps.reports_timer);
        assert_eq!(caps.volume, Some(RangeCapability { min: 0, max: 3 }));
        assert_eq!(caps.standby, Some(RangeCapability { min: 5, max: 30 }));
        assert!(caps.flow_smoothing);
        assert!(caps.anti_mistouch);
        assert!(caps.auto_stop);
        assert_eq!(
            caps.modes,
            vec![
                ModeInfo {
                    id: 0,
                    name: "Flow Rate".to_owned()
                },
                ModeInfo {
                    id: 1,
                    name: "Timer".to_owned()
                },
                ModeInfo {
                    id: 2,
                    name: "Auto".to_owned()
                },
            ]
        );
    }

    #[test]
    fn weight_only_scales_report_no_capabilities() {
        // Every non-Bookoo scale is weight-only for this slice.
        // First-class capabilities (flow / timer / volume / standby /
        // smoothing / anti_mistouch / auto_stop / modes) are Bookoo-only;
        // the per-command capability flags (can_lcd / can_power_off / …)
        // legitimately fire for the scales that expose those bytes — see
        // `each_per_command_capability_flag_mirrors_its_command_method`.
        for label in [
            "Decent Scale",
            "Skale II",
            "Felicita Arc",
            "Acaia",
            "Acaia Pyxis",
            "Atomheart Eclair",
            "Eureka Precisa",
            "Solo Barista",
            "Difluid Microbalance",
            "Smartchef",
            "Hiroia Jimmy",
            "Varia Aku",
        ] {
            let caps = Scale::from_label(label).unwrap().capabilities();
            assert!(!caps.reports_flow, "{label} reports_flow");
            assert!(!caps.reports_timer, "{label} reports_timer");
            assert_eq!(caps.volume, None, "{label} volume");
            assert_eq!(caps.standby, None, "{label} standby");
            assert!(!caps.flow_smoothing, "{label} flow_smoothing");
            assert!(!caps.anti_mistouch, "{label} anti_mistouch");
            assert!(!caps.auto_stop, "{label} auto_stop");
            assert!(caps.modes.is_empty(), "{label} modes");
        }
    }

    #[test]
    fn each_per_command_capability_flag_mirrors_its_command_method() {
        // The flag fields on ScaleCapabilities (can_lcd, can_power_off, …)
        // are derived from the corresponding `*_command` methods, so the
        // two surfaces can never drift. One scale per row pins the
        // expected matrix.
        let decent = Scale::from_label("Decent Scale").unwrap().capabilities();
        assert!(decent.can_lcd);
        assert!(decent.can_power_off);
        assert!(!decent.can_beep);
        assert!(!decent.can_set_unit_grams);
        assert!(!decent.can_toggle_unit);
        assert_eq!(decent.heartbeat_interval_ms, Some(2_000));

        let skale = Scale::from_label("Skale II").unwrap().capabilities();
        assert!(skale.can_lcd);
        assert!(!skale.can_power_off);
        assert!(skale.heartbeat_interval_ms.is_none());

        let eureka = Scale::from_label("Eureka Precisa").unwrap().capabilities();
        assert!(!eureka.can_lcd);
        assert!(eureka.can_power_off);
        assert!(eureka.can_beep);
        assert!(eureka.can_set_unit_grams);

        let hiroia = Scale::from_label("Hiroia Jimmy").unwrap().capabilities();
        assert!(!hiroia.can_set_unit_grams);
        assert!(hiroia.can_toggle_unit);

        let difluid = Scale::from_label("Difluid Microbalance")
            .unwrap()
            .capabilities();
        assert!(difluid.can_set_unit_grams);
        assert!(!difluid.can_toggle_unit);

        let felicita = Scale::from_label("Felicita Arc").unwrap().capabilities();
        // A truly weight-only scale: every per-command flag is false.
        assert!(!felicita.can_lcd);
        assert!(!felicita.can_power_off);
        assert!(!felicita.can_beep);
        assert!(!felicita.can_set_unit_grams);
        assert!(!felicita.can_toggle_unit);
        assert!(felicita.heartbeat_interval_ms.is_none());
    }

    #[test]
    fn acaia_supports_tare_but_not_a_timer() {
        let mut acaia = Scale::from_label("Acaia").unwrap();
        assert!(acaia.supports_tare());
        assert!(!acaia.supports_timer());
        assert!(acaia.tare().is_some());
        assert_eq!(acaia.timer(TimerCommand::Reset), None);
    }

    #[test]
    fn uuids_are_exposed_for_the_transport_layer() {
        let uuids = Scale::from_label("Bookoo").unwrap().uuids();
        assert_eq!(uuids.service, bookoo::SERVICE_UUID);
        assert_eq!(uuids.command_write, bookoo::COMMAND_UUID);
    }

    #[test]
    fn sensor_lag_matches_the_per_scale_table() {
        assert_eq!(
            Scale::from_label("Hiroia Jimmy").unwrap().sensor_lag(),
            Duration::from_millis(250)
        );
        assert_eq!(
            Scale::from_label("Bookoo").unwrap().sensor_lag(),
            Duration::from_millis(500)
        );
        assert_eq!(
            Scale::from_label("Acaia Pyxis").unwrap().sensor_lag(),
            Duration::from_millis(690)
        );
        assert_eq!(
            Scale::from_label("Skale II").unwrap().sensor_lag(),
            Duration::from_millis(380)
        );
    }
}
