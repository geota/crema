//! Command and settings packets the app writes to the DE1.
//!
//! Covers `RequestedState` (`cuuid_02`), `WaterLevels` (`cuuid_11`), and the
//! steam / hot-water `ShotSettings` (`cuuid_0B`). See `docs/02-ble-protocol.md` §7.

use crate::error::ProtocolError;
use crate::fixed_point::{u8p0_decode, u8p0_encode, u16p8_decode, u16p8_encode};
use crate::state::MachineState;

/// Encode a `RequestedState` write (`cuuid_02`) — the single byte is the
/// desired [`MachineState`]. For example: wake → `Idle`, sleep → `Sleep`,
/// start espresso → `Espresso`, stop a shot → `Idle`.
pub fn requested_state(state: MachineState) -> u8 {
    state as u8
}

/// Wire length of a [`WaterLevels`] packet.
pub const WATER_LEVELS_LEN: usize = 4;

/// The water-tank level packet (`WaterLevels` / `cuuid_11`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WaterLevels {
    /// Current tank water level, mm. On a write this should be 0; on a
    /// notification it carries the measured level.
    pub current_mm: f32,
    /// Refill threshold, mm — the level at or below which a refill is wanted.
    pub refill_threshold_mm: f32,
}

impl WaterLevels {
    /// Encode to the 4-byte `WaterLevels` packet.
    pub fn encode(&self) -> [u8; WATER_LEVELS_LEN] {
        let level = u16p8_encode(self.current_mm).to_be_bytes();
        let threshold = u16p8_encode(self.refill_threshold_mm).to_be_bytes();
        [level[0], level[1], threshold[0], threshold[1]]
    }

    /// Decode a `WaterLevels` notification. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`WATER_LEVELS_LEN`] bytes.
    pub fn decode(data: &[u8]) -> Result<WaterLevels, ProtocolError> {
        if data.len() < WATER_LEVELS_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "WaterLevels",
                expected: WATER_LEVELS_LEN,
                got: data.len(),
            });
        }
        Ok(WaterLevels {
            current_mm: u16p8_decode(u16::from_be_bytes([data[0], data[1]])),
            refill_threshold_mm: u16p8_decode(u16::from_be_bytes([data[2], data[3]])),
        })
    }
}

/// Wire length of a [`ShotSettings`] packet.
pub const SHOT_SETTINGS_LEN: usize = 9;

/// Steam and hot-water settings (`ShotSettings` / `cuuid_0B`).
#[derive(Debug, Clone, PartialEq)]
pub struct ShotSettings {
    /// Steam-control flag bits. The legacy app writes 0; the bit semantics are
    /// undocumented, so this codec passes the byte through verbatim.
    pub steam_flags: u8,
    /// Target steam temperature, °C.
    pub steam_temp_c: f32,
    /// Steam timeout, seconds.
    pub steam_timeout_s: f32,
    /// Target hot-water temperature, °C.
    pub hot_water_temp_c: f32,
    /// Hot-water volume, mL.
    pub hot_water_volume_ml: f32,
    /// Hot-water maximum time, seconds.
    pub hot_water_timeout_s: f32,
    /// Typical espresso volume, mL.
    pub espresso_volume_ml: f32,
    /// Espresso group target temperature, °C.
    pub group_temp_c: f32,
}

impl Default for ShotSettings {
    /// Representative defaults, mirroring the legacy app's steam / hot-water
    /// settings: 150 °C steam, 85 °C / 200 mL hot water, 92 °C group.
    fn default() -> ShotSettings {
        ShotSettings {
            steam_flags: 0,
            steam_temp_c: 150.0,
            steam_timeout_s: 120.0,
            hot_water_temp_c: 85.0,
            hot_water_volume_ml: 200.0,
            hot_water_timeout_s: 30.0,
            espresso_volume_ml: 36.0,
            group_temp_c: 92.0,
        }
    }
}

impl ShotSettings {
    /// Encode to the 9-byte `ShotSettings` packet.
    pub fn encode(&self) -> [u8; SHOT_SETTINGS_LEN] {
        let group = u16p8_encode(self.group_temp_c).to_be_bytes();
        [
            self.steam_flags,
            u8p0_encode(self.steam_temp_c),
            u8p0_encode(self.steam_timeout_s),
            u8p0_encode(self.hot_water_temp_c),
            u8p0_encode(self.hot_water_volume_ml),
            u8p0_encode(self.hot_water_timeout_s),
            u8p0_encode(self.espresso_volume_ml),
            group[0],
            group[1],
        ]
    }

    /// Decode a `ShotSettings` packet. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `data` has fewer than
    /// [`SHOT_SETTINGS_LEN`] bytes.
    pub fn decode(data: &[u8]) -> Result<ShotSettings, ProtocolError> {
        if data.len() < SHOT_SETTINGS_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "ShotSettings",
                expected: SHOT_SETTINGS_LEN,
                got: data.len(),
            });
        }
        Ok(ShotSettings {
            steam_flags: data[0],
            steam_temp_c: u8p0_decode(data[1]),
            steam_timeout_s: u8p0_decode(data[2]),
            hot_water_temp_c: u8p0_decode(data[3]),
            hot_water_volume_ml: u8p0_decode(data[4]),
            hot_water_timeout_s: u8p0_decode(data[5]),
            espresso_volume_ml: u8p0_decode(data[6]),
            group_temp_c: u16p8_decode(u16::from_be_bytes([data[7], data[8]])),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requested_state_is_the_machine_state_byte() {
        assert_eq!(requested_state(MachineState::Espresso), 4);
        assert_eq!(requested_state(MachineState::Idle), 2);
        assert_eq!(requested_state(MachineState::Sleep), 0);
    }

    #[test]
    fn water_levels_encode_to_the_expected_bytes() {
        let levels = WaterLevels {
            current_mm: 100.0,
            refill_threshold_mm: 70.0,
        };
        // 100*256 = 0x6400, 70*256 = 0x4600.
        assert_eq!(levels.encode(), [0x64, 0x00, 0x46, 0x00]);
    }

    #[test]
    fn water_levels_round_trip() {
        let levels = WaterLevels {
            current_mm: 0.0,
            refill_threshold_mm: 55.0,
        };
        assert_eq!(WaterLevels::decode(&levels.encode()), Ok(levels));
    }

    #[test]
    fn water_levels_rejects_a_short_packet() {
        assert!(WaterLevels::decode(&[0x64, 0x00]).is_err());
    }

    #[test]
    fn shot_settings_round_trip() {
        let settings = ShotSettings {
            steam_flags: 0,
            steam_temp_c: 150.0,
            steam_timeout_s: 120.0,
            hot_water_temp_c: 85.0,
            hot_water_volume_ml: 200.0,
            hot_water_timeout_s: 30.0,
            espresso_volume_ml: 36.0,
            group_temp_c: 92.0,
        };
        assert_eq!(ShotSettings::decode(&settings.encode()), Ok(settings));
    }

    #[test]
    fn shot_settings_rejects_a_short_packet() {
        assert!(ShotSettings::decode(&[0; 8]).is_err());
    }
}
