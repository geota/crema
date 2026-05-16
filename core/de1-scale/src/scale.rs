//! The unifying [`Scale`] abstraction over every supported scale protocol.

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
}
