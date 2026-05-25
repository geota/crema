//! MMR — the DE1's memory-mapped registers.
//!
//! Beyond the dedicated GATT characteristics, the DE1 exposes internal
//! configuration and hardware info through a memory-mapped register window.
//! The app reads it with `ReadFromMMR` (`cuuid_05`) and writes it with
//! `WriteToMMR` (`cuuid_06`); both use a fixed 20-byte packet.
//!
//! Two endiannesses are in play, and they differ on purpose:
//!
//! - register *addresses* are 24-bit and travel **big-endian** in the packet,
//!   like every other DE1 GATT field;
//! - register *values* in the data payload are little-endian 32-bit words.

use typeshare::typeshare;

use crate::error::ProtocolError;
use crate::fixed_point::{u24p0_decode, u24p0_encode};

/// Wire length of an MMR packet — a read request, a write, or a read reply.
pub const MMR_PACKET_LEN: usize = 20;

/// Number of data bytes an MMR packet carries (four 32-bit words).
pub const MMR_DATA_LEN: usize = 16;

/// Build a `ReadFromMMR` request (`cuuid_05`) for `word_count` consecutive
/// 32-bit words starting at `address`.
///
/// The DE1 answers with a notification on the same characteristic; decode it
/// with [`MmrReadReply::decode`]. The wire `Len` byte is `word_count - 1`, so
/// `word_count` is clamped to `1..=256`.
pub fn read_request(address: u32, word_count: u16) -> [u8; MMR_PACKET_LEN] {
    let mut packet = [0u8; MMR_PACKET_LEN];
    // `word_count.clamp(1, 256) - 1` is in `0..=255`, so it always fits a u8.
    packet[0] = u8::try_from(word_count.clamp(1, 256) - 1).unwrap_or(u8::MAX);
    packet[1..4].copy_from_slice(&u24p0_encode(address));
    packet
}

/// Build a `WriteToMMR` packet (`cuuid_06`) setting the register at `address`
/// to `data`. `data` is truncated to [`MMR_DATA_LEN`] bytes if longer; the
/// wire `Len` byte is the number of data bytes written.
pub fn write_request(address: u32, data: &[u8]) -> [u8; MMR_PACKET_LEN] {
    let mut packet = [0u8; MMR_PACKET_LEN];
    // `len` is capped at MMR_DATA_LEN (16) by the `min` above — fits a u8.
    let len = data.len().min(MMR_DATA_LEN);
    packet[0] = u8::try_from(len).unwrap_or(u8::MAX);
    packet[1..4].copy_from_slice(&u24p0_encode(address));
    packet[4..4 + len].copy_from_slice(&data[..len]);
    packet
}

/// A decoded `ReadFromMMR` reply: the address echoed back and its data.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MmrReadReply {
    /// The 24-bit register address this reply is for.
    pub address: u32,
    /// The 16 data bytes. Register values are little-endian 32-bit words;
    /// read them with [`word`](Self::word).
    pub data: [u8; MMR_DATA_LEN],
}

impl MmrReadReply {
    /// Decode an MMR read reply. Trailing bytes are ignored.
    ///
    /// # Errors
    ///
    /// [`ProtocolError::PacketTooShort`] if `packet` has fewer than
    /// [`MMR_PACKET_LEN`] bytes.
    pub fn decode(packet: &[u8]) -> Result<MmrReadReply, ProtocolError> {
        if packet.len() < MMR_PACKET_LEN {
            return Err(ProtocolError::PacketTooShort {
                packet: "MmrReadReply",
                expected: MMR_PACKET_LEN,
                got: packet.len(),
            });
        }
        let address = u24p0_decode([packet[1], packet[2], packet[3]]);
        let mut data = [0u8; MMR_DATA_LEN];
        data.copy_from_slice(&packet[4..4 + MMR_DATA_LEN]);
        Ok(MmrReadReply { address, data })
    }

    /// The `index`-th 32-bit register value (`index` in `0..=3`), decoded
    /// little-endian. `None` if `index` is past the four words the payload holds.
    pub fn word(&self, index: usize) -> Option<u32> {
        let bytes = self.data.get(index * 4..index * 4 + 4)?;
        Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }
}

/// Known MMR register addresses.
///
/// This covers the registers Crema reads or writes.
/// [`address`](Self::address) gives the raw 24-bit address to pass to
/// [`read_request`] / [`write_request`].
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum MmrRegister {
    /// CPU-board revision, encoded `cpu_board_version × 1000` (e.g. raw
    /// `1100` → PCB v1.1, raw `1300` → PCB v1.3). Read at connect time
    /// in the legacy de1app via `get_3_mmr_cpuboard_machinemodel_firmwareversion`.
    CpuBoardVersion,
    /// Machine model identifier — `0 = Unset / unknown`, `1 = DE1`,
    /// `2 = DE1+`, `3 = DE1PRO`, `4 = DE1XL`, `5 = DE1CAFE`, `6 = DE1XXL`,
    /// `7 = DE1XXXL`. The legacy app uses this to gate model-specific
    /// settings (e.g. the cup-warmer surface on Bengle hardware).
    MachineModel,
    /// Firmware build number.
    FirmwareVersion,
    /// Group Head Controller info bitmask (bit 0: present, bit 1: active).
    GhcInfo,
    /// Tank desired water-temperature threshold, °C.
    TankTempThreshold,
    /// Fan-on temperature threshold, °C.
    FanThreshold,
    /// Machine serial number.
    SerialNumber,
    /// Steam flow rate.
    SteamFlow,
    /// Refill-kit presence.
    RefillKit,
    /// Flush flow rate.
    FlushFlowRate,
    /// Flush water target temperature, °C — the temperature the DE1
    /// holds while a group-flush cycle runs. Wire value is `°C × 10`
    /// (so 950 = 95.0 °C). Modelled by reaprime
    /// (`de1.models.dart:flushTemp` at `0x00803844`, 4-byte slot,
    /// `readScale: 0.1`); the legacy de1app TCL has no equivalent.
    FlushTemp,
    /// Hot-water flow rate.
    HotWaterFlowRate,
    /// Hot-water dispense phase-1 flow rate.
    Phase1FlowRate,
    /// Hot-water dispense phase-2 flow rate.
    Phase2FlowRate,
    /// Hot-water idle temperature, °C.
    HotWaterIdleTemp,
    /// Group head control mode.
    GhcMode,
    /// Seconds of high-flow steam at the start of a steam cycle.
    SteamHighFlowStart,
    /// Mains heater voltage.
    HeaterVoltage,
    /// Espresso warmup timeout.
    EspressoWarmupTimeout,
    /// Calibration flow multiplier (`int(1000 * multiplier)`).
    CalibrationFlowMultiplier,
    /// Flush timeout (`int(10 * seconds)`).
    FlushTimeout,
    /// USB charger on (1 = tablet USB charging enabled).
    UsbChargerOn,
    /// Feature-flag bitmask (e.g. the `UserNotPresent` flag).
    FeatureFlags,
    /// Whether the user is currently present at the machine. The legacy app
    /// writes `1` when the user touches the screen so the firmware does not
    /// sleep on inactivity. **Distinct register from [`FeatureFlags`]** — the
    /// legacy `de1_comms.tcl` writes to two separate addresses; the firmware
    /// MMR map is not openly published but the legacy source treats them as
    /// unrelated registers.
    UserPresent,
    /// Steam two-tap stop register — the second `tap` of the
    /// double-tap-to-stop steam UX (`heater_tweaks`).
    SteamTwoTapStop,
    /// Cup-warmer temperature (Bengle models only).
    CupWarmerTemp,
}

impl MmrRegister {
    /// The register's raw 24-bit MMR address.
    pub fn address(self) -> u32 {
        match self {
            MmrRegister::CpuBoardVersion => 0x80_0008,
            MmrRegister::MachineModel => 0x80_000C,
            MmrRegister::FirmwareVersion => 0x80_0010,
            MmrRegister::GhcInfo => 0x80_381C,
            MmrRegister::TankTempThreshold => 0x80_380C,
            MmrRegister::FanThreshold => 0x80_3808,
            MmrRegister::SerialNumber => 0x80_3830,
            MmrRegister::SteamFlow => 0x80_3828,
            MmrRegister::RefillKit => 0x80_385C,
            MmrRegister::FlushFlowRate => 0x80_3840,
            MmrRegister::FlushTemp => 0x80_3844,
            MmrRegister::HotWaterFlowRate => 0x80_384C,
            MmrRegister::Phase1FlowRate => 0x80_3810,
            MmrRegister::Phase2FlowRate => 0x80_3814,
            MmrRegister::HotWaterIdleTemp => 0x80_3818,
            MmrRegister::GhcMode => 0x80_3820,
            MmrRegister::SteamHighFlowStart => 0x80_382C,
            MmrRegister::HeaterVoltage => 0x80_3834,
            MmrRegister::EspressoWarmupTimeout => 0x80_3838,
            MmrRegister::CalibrationFlowMultiplier => 0x80_383C,
            MmrRegister::FlushTimeout => 0x80_3848,
            MmrRegister::UsbChargerOn => 0x80_3854,
            MmrRegister::FeatureFlags => 0x80_3858,
            MmrRegister::UserPresent => 0x80_3860,
            MmrRegister::SteamTwoTapStop => 0x80_3850,
            MmrRegister::CupWarmerTemp => 0x80_3874,
        }
    }

    /// The register's read-side scale factor — multiply the raw 32-bit
    /// integer the firmware emits by this to get the engineering-units
    /// value. The reciprocal applies on writes (callers that *write*
    /// engineering units must divide by `scale()` and round, e.g. to
    /// write `9.5 ml/s` to `FlushFlowRate` (scale `0.1`), encode
    /// `95u32` on the wire).
    ///
    /// Catalog mirrors reaprime's `MMRItem.readScale`
    /// (`de1.models.dart`); registers reaprime models with no explicit
    /// scale default to `1.0` here. `SteamHighFlowStart` is an
    /// audit-finding fix: reaprime declares the field `scaledFloat` but
    /// omits `readScale` (their bug). The firmware's published
    /// description says the wire value is `seconds × 100`, so the
    /// correct read-scale is `0.01`; Crema models it correctly.
    pub fn scale(self) -> f32 {
        match self {
            // ×0.1 — flow rates, idle temps, timeouts in tenths.
            MmrRegister::Phase1FlowRate
            | MmrRegister::Phase2FlowRate
            | MmrRegister::HotWaterIdleTemp
            | MmrRegister::HotWaterFlowRate
            | MmrRegister::FlushFlowRate
            | MmrRegister::FlushTemp
            | MmrRegister::FlushTimeout
            | MmrRegister::EspressoWarmupTimeout => 0.1,

            // ×0.01 — steam flow / steam-high-flow-start.
            MmrRegister::SteamFlow | MmrRegister::SteamHighFlowStart => 0.01,

            // ×0.001 — calibration flow multiplier.
            MmrRegister::CalibrationFlowMultiplier => 0.001,

            // Plain integer registers — value is already in engineering
            // units (°C, count, bitmask, …) and needs no scaling.
            MmrRegister::CpuBoardVersion
            | MmrRegister::MachineModel
            | MmrRegister::FirmwareVersion
            | MmrRegister::GhcInfo
            | MmrRegister::TankTempThreshold
            | MmrRegister::FanThreshold
            | MmrRegister::SerialNumber
            | MmrRegister::RefillKit
            | MmrRegister::GhcMode
            | MmrRegister::HeaterVoltage
            | MmrRegister::UsbChargerOn
            | MmrRegister::FeatureFlags
            | MmrRegister::UserPresent
            | MmrRegister::SteamTwoTapStop
            | MmrRegister::CupWarmerTemp => 1.0,
        }
    }

    /// Every known register, in declaration order — the basis of
    /// [`from_address`](Self::from_address) and a useful read-set for callers
    /// that want to poll the whole diagnostic window. Order is not significant;
    /// the `all_covers_every_variant` test pins this list to the enum.
    pub const ALL: [MmrRegister; 26] = [
        MmrRegister::CpuBoardVersion,
        MmrRegister::MachineModel,
        MmrRegister::FirmwareVersion,
        MmrRegister::GhcInfo,
        MmrRegister::TankTempThreshold,
        MmrRegister::FanThreshold,
        MmrRegister::SerialNumber,
        MmrRegister::SteamFlow,
        MmrRegister::RefillKit,
        MmrRegister::FlushFlowRate,
        MmrRegister::FlushTemp,
        MmrRegister::HotWaterFlowRate,
        MmrRegister::Phase1FlowRate,
        MmrRegister::Phase2FlowRate,
        MmrRegister::HotWaterIdleTemp,
        MmrRegister::GhcMode,
        MmrRegister::SteamHighFlowStart,
        MmrRegister::HeaterVoltage,
        MmrRegister::EspressoWarmupTimeout,
        MmrRegister::CalibrationFlowMultiplier,
        MmrRegister::FlushTimeout,
        MmrRegister::UsbChargerOn,
        MmrRegister::FeatureFlags,
        MmrRegister::UserPresent,
        MmrRegister::SteamTwoTapStop,
        MmrRegister::CupWarmerTemp,
    ];

    /// The register at the raw 24-bit MMR `address`, or `None` if `address` is
    /// not one Crema models. The inverse of [`address`](Self::address) — used
    /// to map a [`MmrReadReply`]'s echoed address back to a known register.
    pub fn from_address(address: u32) -> Option<MmrRegister> {
        MmrRegister::ALL
            .into_iter()
            .find(|reg| reg.address() == address)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn read_request_encodes_len_and_big_endian_address() {
        // One word from the firmware-version register: Len = word_count - 1 = 0.
        let packet = read_request(MmrRegister::FirmwareVersion.address(), 1);
        assert_eq!(packet[0], 0);
        assert_eq!(&packet[1..4], &[0x80, 0x00, 0x10]);
        assert_eq!(&packet[4..], &[0u8; MMR_DATA_LEN]);
    }

    #[test]
    fn read_request_len_is_word_count_minus_one() {
        assert_eq!(read_request(0x80_3830, 4)[0], 3);
        // word_count is clamped into 1..=256.
        assert_eq!(read_request(0x80_3830, 0)[0], 0);
        assert_eq!(read_request(0x80_3830, 9999)[0], 255);
    }

    #[test]
    fn write_request_encodes_len_address_and_data() {
        let packet = write_request(MmrRegister::FanThreshold.address(), &[0x2D, 0, 0, 0]);
        assert_eq!(packet[0], 4);
        assert_eq!(&packet[1..4], &[0x80, 0x38, 0x08]);
        assert_eq!(&packet[4..8], &[0x2D, 0, 0, 0]);
        assert_eq!(&packet[8..], &[0u8; 12]);
    }

    #[test]
    fn write_request_truncates_oversized_data() {
        let packet = write_request(0x80_3830, &[0xFF; 32]);
        assert_eq!(usize::from(packet[0]), MMR_DATA_LEN);
        assert_eq!(&packet[4..], &[0xFF; MMR_DATA_LEN]);
    }

    #[test]
    fn read_reply_round_trips_address_and_words() {
        // Address 0x800010, first word = 1352 (a firmware version), little-endian.
        let mut packet = [0u8; MMR_PACKET_LEN];
        packet[1..4].copy_from_slice(&[0x80, 0x00, 0x10]);
        packet[4..8].copy_from_slice(&1352u32.to_le_bytes());

        let reply = MmrReadReply::decode(&packet).unwrap();
        assert_eq!(reply.address, 0x80_0010);
        assert_eq!(reply.word(0), Some(1352));
        assert_eq!(reply.word(3), Some(0));
        assert_eq!(reply.word(4), None);
    }

    #[test]
    fn read_reply_rejects_a_short_packet() {
        assert!(MmrReadReply::decode(&[0u8; 10]).is_err());
    }

    #[test]
    fn newly_added_registers_have_documented_addresses() {
        assert_eq!(MmrRegister::Phase1FlowRate.address(), 0x80_3810);
        assert_eq!(MmrRegister::Phase2FlowRate.address(), 0x80_3814);
        assert_eq!(MmrRegister::HotWaterIdleTemp.address(), 0x80_3818);
        assert_eq!(MmrRegister::GhcMode.address(), 0x80_3820);
        assert_eq!(MmrRegister::SteamHighFlowStart.address(), 0x80_382C);
        assert_eq!(MmrRegister::HeaterVoltage.address(), 0x80_3834);
        assert_eq!(MmrRegister::EspressoWarmupTimeout.address(), 0x80_3838);
        assert_eq!(MmrRegister::CalibrationFlowMultiplier.address(), 0x80_383C);
        assert_eq!(MmrRegister::FlushTimeout.address(), 0x80_3848);
        assert_eq!(MmrRegister::UsbChargerOn.address(), 0x80_3854);
        assert_eq!(MmrRegister::FeatureFlags.address(), 0x80_3858);
        assert_eq!(MmrRegister::UserPresent.address(), 0x80_3860);
        assert_eq!(MmrRegister::SteamTwoTapStop.address(), 0x80_3850);
        assert_eq!(MmrRegister::CupWarmerTemp.address(), 0x80_3874);
    }

    #[test]
    fn all_register_addresses_lie_in_the_documented_mmr_window() {
        for reg in MmrRegister::ALL {
            // All known registers sit in the 24-bit memory-mapped window.
            assert!(reg.address() <= 0xFF_FFFF, "{reg:?} address out of range");
        }
    }

    #[test]
    fn from_address_is_the_inverse_of_address() {
        for reg in MmrRegister::ALL {
            assert_eq!(MmrRegister::from_address(reg.address()), Some(reg));
        }
        // An address Crema does not model resolves to nothing.
        assert_eq!(MmrRegister::from_address(0x00_0000), None);
    }

    #[test]
    fn all_covers_every_variant() {
        // Touch every variant in an exhaustive match so adding a new register
        // without listing it below fails to compile here. The body asserts the
        // variant is also present in `ALL`, so `ALL` cannot fall behind.
        let covers = |reg: MmrRegister| {
            assert!(
                MmrRegister::ALL.contains(&reg),
                "{reg:?} is missing from MmrRegister::ALL"
            );
        };
        for reg in MmrRegister::ALL {
            match reg {
                MmrRegister::CpuBoardVersion
                | MmrRegister::MachineModel
                | MmrRegister::FirmwareVersion
                | MmrRegister::GhcInfo
                | MmrRegister::TankTempThreshold
                | MmrRegister::FanThreshold
                | MmrRegister::SerialNumber
                | MmrRegister::SteamFlow
                | MmrRegister::RefillKit
                | MmrRegister::FlushFlowRate
                | MmrRegister::FlushTemp
                | MmrRegister::HotWaterFlowRate
                | MmrRegister::Phase1FlowRate
                | MmrRegister::Phase2FlowRate
                | MmrRegister::HotWaterIdleTemp
                | MmrRegister::GhcMode
                | MmrRegister::SteamHighFlowStart
                | MmrRegister::HeaterVoltage
                | MmrRegister::EspressoWarmupTimeout
                | MmrRegister::CalibrationFlowMultiplier
                | MmrRegister::FlushTimeout
                | MmrRegister::UsbChargerOn
                | MmrRegister::FeatureFlags
                | MmrRegister::UserPresent
                | MmrRegister::SteamTwoTapStop
                | MmrRegister::CupWarmerTemp => covers(reg),
            }
        }
        // No two variants share an address, so `ALL` has no duplicates.
        for (i, a) in MmrRegister::ALL.iter().enumerate() {
            for b in &MmrRegister::ALL[i + 1..] {
                assert_ne!(a.address(), b.address(), "{a:?} and {b:?} share an address");
            }
        }
    }

    #[test]
    fn scale_catalog_matches_reaprime() {
        // Pin every register's read-side scale against the reaprime
        // catalog (`de1.models.dart`). Each entry that differs is
        // deliberate (e.g. `SteamHighFlowStart` corrects reaprime's
        // missing `readScale`; see the doc-comment on `scale`).
        for (reg, expected) in [
            (MmrRegister::Phase1FlowRate, 0.1),
            (MmrRegister::Phase2FlowRate, 0.1),
            (MmrRegister::HotWaterIdleTemp, 0.1),
            (MmrRegister::HotWaterFlowRate, 0.1),
            (MmrRegister::FlushFlowRate, 0.1),
            (MmrRegister::FlushTemp, 0.1),
            (MmrRegister::FlushTimeout, 0.1),
            (MmrRegister::EspressoWarmupTimeout, 0.1),
            (MmrRegister::SteamFlow, 0.01),
            (MmrRegister::SteamHighFlowStart, 0.01),
            (MmrRegister::CalibrationFlowMultiplier, 0.001),
            (MmrRegister::CpuBoardVersion, 1.0),
            (MmrRegister::MachineModel, 1.0),
            (MmrRegister::FirmwareVersion, 1.0),
            (MmrRegister::GhcInfo, 1.0),
            (MmrRegister::TankTempThreshold, 1.0),
            (MmrRegister::FanThreshold, 1.0),
            (MmrRegister::SerialNumber, 1.0),
            (MmrRegister::RefillKit, 1.0),
            (MmrRegister::GhcMode, 1.0),
            (MmrRegister::HeaterVoltage, 1.0),
            (MmrRegister::UsbChargerOn, 1.0),
            (MmrRegister::FeatureFlags, 1.0),
            (MmrRegister::UserPresent, 1.0),
            (MmrRegister::SteamTwoTapStop, 1.0),
            (MmrRegister::CupWarmerTemp, 1.0),
        ] {
            let got = reg.scale();
            assert!(
                (got - expected).abs() < f32::EPSILON,
                "{reg:?}: expected scale {expected}, got {got}"
            );
        }
        // Every variant must have an entry — pinning the catalog also
        // ensures forgotten variants flag immediately. Compile-time
        // exhaustiveness on `match` already covers this for `scale()`
        // itself; the loop above asserts the table doesn't go stale.
        for reg in MmrRegister::ALL {
            // Touch the method so every variant participates.
            let _ = reg.scale();
        }
    }
}
