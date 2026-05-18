//! The unifying [`Scale`] abstraction over every supported scale protocol.

use typeshare::typeshare;

use crate::acaia::{self, AcaiaDecoder};
use crate::{
    atomheart_eclair, bookoo, decent_scale, difluid, eureka_precisa, felicita, hiroia_jimmy, skale,
    smartchef, varia_aku,
};

/// The Decent Scale's tare counter starts here and wraps (`255` → `0`).
const DECENT_TARE_COUNTER_INIT: u8 = 253;

/// The BLE UUIDs a scale's transport layer needs.
///
/// `Clone` but not `Copy`: three string slices (48 bytes) exceed the size
/// where implicit copies are a sensible default.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScaleUuids {
    /// GATT service UUID.
    pub service: &'static str,
    /// Characteristic weight notifications arrive on.
    pub weight_notify: &'static str,
    /// Characteristic commands are written to — equal to `weight_notify` for
    /// scales that use a single characteristic for both.
    pub command_write: &'static str,
}

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
    pub standby_minutes: Option<RangeCapability>,
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
    Decent { tare_counter: u8 },
    Skale,
    Felicita,
    Bookoo,
    AcaiaGen1(AcaiaDecoder),
    AcaiaPyxis(AcaiaDecoder),
    AtomheartEclair,
    EurekaPrecisa,
    SoloBarista,
    Difluid,
    Smartchef,
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
        let any = |prefixes: &[&str]| prefixes.iter().any(|p| advertised_name.starts_with(p));
        let inner = if any(&["Decent Scale", "ButtsHaus Scale"]) {
            Inner::Decent {
                tare_counter: DECENT_TARE_COUNTER_INIT,
            }
        } else if advertised_name.starts_with("Skale") {
            Inner::Skale
        } else if advertised_name.starts_with("FELICITA") {
            Inner::Felicita
        } else if advertised_name.starts_with("BOOKOO_SC") {
            Inner::Bookoo
        } else if any(&["PEARLS", "PEARL-", "LUNAR", "PYXIS"]) {
            Inner::AcaiaPyxis(AcaiaDecoder::new())
        } else if any(&["ACAIA", "PROCH"]) {
            Inner::AcaiaGen1(AcaiaDecoder::new())
        } else if advertised_name.starts_with("ECLAIR") {
            Inner::AtomheartEclair
        } else if advertised_name.starts_with("CFS-9002") {
            Inner::EurekaPrecisa
        } else if advertised_name.starts_with("LSJ-001") {
            Inner::SoloBarista
        } else if advertised_name.starts_with("Microbalance") {
            Inner::Difluid
        } else if advertised_name.starts_with("smartchef") {
            Inner::Smartchef
        } else if advertised_name.starts_with("HIROIA JIMMY") {
            Inner::HiroiaJimmy
        } else if any(&["AKU MINI", "Varia AKU"]) {
            Inner::VariaAku
        } else {
            return None;
        };
        Some(Scale { inner })
    }

    /// Reconstruct a scale from a [`label`](Self::label) string (the inverse of
    /// `label`, for persisting a chosen scale in settings). Returns a fresh
    /// instance with no accumulated state.
    pub fn from_label(label: &str) -> Option<Scale> {
        let inner = match label {
            "Decent Scale" => Inner::Decent {
                tare_counter: DECENT_TARE_COUNTER_INIT,
            },
            "Skale II" => Inner::Skale,
            "Felicita Arc" => Inner::Felicita,
            "Bookoo" => Inner::Bookoo,
            "Acaia" => Inner::AcaiaGen1(AcaiaDecoder::new()),
            "Acaia Pyxis" => Inner::AcaiaPyxis(AcaiaDecoder::new()),
            "Atomheart Eclair" => Inner::AtomheartEclair,
            "Eureka Precisa" => Inner::EurekaPrecisa,
            "Solo Barista" => Inner::SoloBarista,
            "Difluid Microbalance" => Inner::Difluid,
            "Smartchef" => Inner::Smartchef,
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
            Inner::Decent { .. } => "Decent Scale",
            Inner::Skale => "Skale II",
            Inner::Felicita => "Felicita Arc",
            Inner::Bookoo => "Bookoo",
            Inner::AcaiaGen1(_) => "Acaia",
            Inner::AcaiaPyxis(_) => "Acaia Pyxis",
            Inner::AtomheartEclair => "Atomheart Eclair",
            Inner::EurekaPrecisa => "Eureka Precisa",
            Inner::SoloBarista => "Solo Barista",
            Inner::Difluid => "Difluid Microbalance",
            Inner::Smartchef => "Smartchef",
            Inner::HiroiaJimmy => "Hiroia Jimmy",
            Inner::VariaAku => "Varia Aku",
        }
    }

    /// The BLE service and characteristic UUIDs for this scale.
    pub fn uuids(&self) -> ScaleUuids {
        match &self.inner {
            Inner::Decent { .. } => ScaleUuids {
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
            Inner::Smartchef => ScaleUuids {
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

    /// Whether this scale accepts a software tare command. `false` only for
    /// Smartchef, whose tare is a physical button.
    pub fn supports_tare(&self) -> bool {
        !matches!(&self.inner, Inner::Smartchef)
    }

    /// Whether this scale accepts software timer commands.
    pub fn supports_timer(&self) -> bool {
        !matches!(
            &self.inner,
            Inner::AcaiaGen1(_)
                | Inner::AcaiaPyxis(_)
                | Inner::Smartchef
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
    pub fn capabilities(&self) -> ScaleCapabilities {
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
                standby_minutes: Some(RangeCapability { min: 5, max: 30 }),
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
            },
            // Every other supported scale is weight-only for this slice.
            Inner::Decent { .. }
            | Inner::Skale
            | Inner::Felicita
            | Inner::AcaiaGen1(_)
            | Inner::AcaiaPyxis(_)
            | Inner::AtomheartEclair
            | Inner::EurekaPrecisa
            | Inner::SoloBarista
            | Inner::Difluid
            | Inner::Smartchef
            | Inner::HiroiaJimmy
            | Inner::VariaAku => ScaleCapabilities::default(),
        }
    }

    /// The scale's sensor lag, in seconds — the delay between coffee landing
    /// in the cup and the scale reporting the new weight. It is one term of
    /// the SAW (stop-at-weight) stop-early prediction.
    ///
    /// Values are from the legacy app (`device_scale.tcl`), itself derived
    /// from James Hoffmann's scale-latency measurements plus BLE delay.
    pub fn sensor_lag_seconds(&self) -> f32 {
        match &self.inner {
            Inner::HiroiaJimmy => 0.25,
            Inner::Felicita | Inner::Bookoo | Inner::AtomheartEclair => 0.50,
            // The legacy switch has no `acaiapyxis` arm (a Pyxis would hit the
            // 0.38 default); the Pyxis is physically an Acaia, so it shares the
            // Acaia sensor lag here — a deliberate correction of that gap.
            Inner::AcaiaGen1(_) | Inner::AcaiaPyxis(_) => 0.69,
            // Decent, Skale, and the scales without a dedicated entry take the
            // legacy 0.38 s default.
            Inner::Decent { .. }
            | Inner::Skale
            | Inner::EurekaPrecisa
            | Inner::SoloBarista
            | Inner::Difluid
            | Inner::Smartchef
            | Inner::VariaAku => 0.38,
        }
    }

    /// Decode a weight notification into grams.
    ///
    /// Takes `&mut self` because framed protocols (Acaia) buffer bytes across
    /// notifications; stateless scales ignore the mutability. Returns `None`
    /// if the bytes are not (yet) a complete weight reading.
    pub fn parse_weight(&mut self, data: &[u8]) -> Option<f32> {
        match &mut self.inner {
            Inner::Decent { .. } => decent_scale::parse_weight(data),
            Inner::Skale => skale::parse_weight(data),
            Inner::Felicita => felicita::parse_weight(data),
            Inner::Bookoo => bookoo::parse_weight(data),
            Inner::AcaiaGen1(decoder) | Inner::AcaiaPyxis(decoder) => decoder.push(data),
            Inner::AtomheartEclair => atomheart_eclair::parse_weight(data),
            Inner::EurekaPrecisa | Inner::SoloBarista => eureka_precisa::parse_weight(data),
            Inner::Difluid => difluid::parse_weight(data),
            Inner::Smartchef => smartchef::parse_weight(data),
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
        self.parse_weight(data).map(|weight_g| ScaleReading {
            weight_g,
            flow_g_per_s: None,
            timer_ms: None,
            volume: None,
            standby_minutes: None,
            battery_percent: None,
            flow_smoothing: None,
            auto_stop: None,
        })
    }

    /// Build a tare command to write to the [command characteristic](ScaleUuids).
    /// Returns `None` if the scale has no software tare (see
    /// [`supports_tare`](Self::supports_tare)).
    pub fn tare(&mut self) -> Option<Vec<u8>> {
        Some(match &mut self.inner {
            Inner::Decent { tare_counter } => {
                let command = decent_scale::tare(*tare_counter).to_vec();
                *tare_counter = tare_counter.wrapping_add(1);
                command
            }
            Inner::Skale => vec![skale::CMD_TARE],
            Inner::Felicita => vec![felicita::TARE],
            Inner::Bookoo => bookoo::TARE.to_vec(),
            Inner::AcaiaGen1(_) | Inner::AcaiaPyxis(_) => acaia::TARE.to_vec(),
            Inner::AtomheartEclair => atomheart_eclair::TARE.to_vec(),
            Inner::EurekaPrecisa | Inner::SoloBarista => eureka_precisa::TARE.to_vec(),
            Inner::Difluid => difluid::TARE.to_vec(),
            Inner::Smartchef => return None,
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
            Inner::Decent { .. } => match command {
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
            | Inner::Smartchef
            | Inner::HiroiaJimmy
            | Inner::VariaAku => return None,
        })
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
                u8::from_str_radix(
                    &"030b000000012b00c06c2b312f64009601000048"[i..i + 2],
                    16,
                )
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
    fn parse_weight_reassembles_an_acaia_frame_across_notifications() {
        let mut acaia = Scale::from_label("Acaia").unwrap();
        let frame = [0xEF, 0xDD, 12, 6, 5, 180, 0, 0, 0, 1, 0];
        assert_eq!(acaia.parse_weight(&frame[..4]), None);
        assert_eq!(acaia.parse_weight(&frame[4..]), Some(18.0));
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
    fn smartchef_supports_no_commands() {
        let mut smartchef = Scale::from_label("Smartchef").unwrap();
        assert!(!smartchef.supports_tare());
        assert!(!smartchef.supports_timer());
        assert_eq!(smartchef.tare(), None);
        assert_eq!(smartchef.timer(TimerCommand::Start), None);
    }

    #[test]
    fn bookoo_reports_first_class_capabilities() {
        let bookoo = Scale::from_label("Bookoo").unwrap();
        let caps = bookoo.capabilities();
        assert!(caps.reports_flow);
        assert!(caps.reports_timer);
        assert_eq!(caps.volume, Some(RangeCapability { min: 0, max: 3 }));
        assert_eq!(
            caps.standby_minutes,
            Some(RangeCapability { min: 5, max: 30 })
        );
        assert!(caps.flow_smoothing);
        assert!(caps.anti_mistouch);
        assert!(caps.auto_stop);
        assert_eq!(
            caps.modes,
            vec![
                ModeInfo { id: 0, name: "Flow Rate".to_owned() },
                ModeInfo { id: 1, name: "Timer".to_owned() },
                ModeInfo { id: 2, name: "Auto".to_owned() },
            ]
        );
    }

    #[test]
    fn weight_only_scales_report_no_capabilities() {
        // Every non-Bookoo scale is weight-only for this slice.
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
            assert_eq!(
                caps,
                ScaleCapabilities::default(),
                "{label} should report no capabilities"
            );
        }
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
            Scale::from_label("Hiroia Jimmy")
                .unwrap()
                .sensor_lag_seconds(),
            0.25
        );
        assert_eq!(
            Scale::from_label("Bookoo").unwrap().sensor_lag_seconds(),
            0.50
        );
        assert_eq!(
            Scale::from_label("Acaia Pyxis")
                .unwrap()
                .sensor_lag_seconds(),
            0.69
        );
        assert_eq!(
            Scale::from_label("Skale II").unwrap().sensor_lag_seconds(),
            0.38
        );
    }
}
