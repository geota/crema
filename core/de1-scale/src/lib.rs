//! # de1-scale
//!
//! Bluetooth LE codecs for the coffee scales Crema can use during a shot.
//!
//! Sans-IO, like `de1-protocol`: each module decodes a scale's
//! weight-notification bytes into grams and builds its command packets (tare,
//! timer). BLE transport lives in the Android shell.
//!
//! ## Using a scale
//!
//! [`Scale`] is the unifying abstraction over every protocol here. Identify a
//! scale from its BLE advertised name with [`Scale::identify`], then drive it
//! through [`Scale::parse_reading`] (or [`Scale::parse_weight`] for weight
//! alone), [`Scale::tare`] and [`Scale::timer`].
//!
//! The per-scale modules ([`bookoo`], [`acaia`], …) hold the concrete codecs
//! and are public for direct use or testing. Most expose a stateless
//! `parse_weight(&[u8]) -> Option<f32>`; [`acaia`] needs a stateful
//! [`acaia::AcaiaDecoder`] because one weight frame may span several BLE
//! notifications.

pub mod acaia;
pub mod atomheart_eclair;
pub mod bookoo;
pub mod decent_scale;
pub mod difluid;
pub mod eureka_precisa;
pub mod felicita;
pub mod hiroia_jimmy;
pub mod scale;
pub mod skale;
pub mod smartchef;
pub mod solo_barista;
pub mod varia_aku;

pub use scale::{
    DecentScaleFirmwareVersion, ModeInfo, RangeCapability, Scale, ScaleCapabilities,
    ScaleConfigUpdate, ScaleReading, ScaleUuids, TimerCommand, UnitRecovery,
};
