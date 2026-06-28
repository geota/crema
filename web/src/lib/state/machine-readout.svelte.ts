/**
 * `$lib/state/machine-readout` — a typed view over the orchestrator's
 * `UiSnapshot.de1MachineInfo` MMR-keyed map plus the sibling
 * `de1Firmware` / `de1Calibration` fields.
 *
 * Before this seam, four components reached into `snapshot.de1MachineInfo[MmrRegister.X]`
 * directly: `BrewDashboard` (flush temp), `MachineSection` (cup-warmer
 * temp, GHC mode, CPU/firmware/model), `AdvancedSection` (heater
 * voltage), `CalibrationSection` (flow multiplier). Renaming a typed
 * concept meant updating every keyed read; the snapshot's internal
 * shape leaked to the UI.
 *
 * Behind this seam, the map stays private and consumers read named
 * properties — `cupWarmerTempC`, `flushTempC`, `ghcPresent`, etc. Adding
 * a register the UI needs is a one-line getter here; renaming or
 * restructuring `de1MachineInfo` itself touches one file instead of
 * four.
 */

import { MmrRegister } from '$lib/core/crema-core';
import {
	getCremaUiState,
	type CremaUiState,
	type De1Calibration,
	type UiSnapshot
} from './ui-state.svelte';

/**
 * Typed accessors over the wire-shape `de1MachineInfo` map and its
 * sibling firmware / calibration fields. One instance per app, wired
 * by the orchestrator with a reference to the shell's `CremaUiState`.
 *
 * Every getter reads through the live snapshot — Svelte 5's reactivity
 * propagates through getters in `$derived` consumers, so a component
 * doing `$derived(machine.flushTempC)` re-renders when the orchestrator
 * patches the snapshot.
 */
export class MachineReadout {
	constructor(private readonly state: CremaUiState) {}

	private get snapshot(): UiSnapshot {
		return this.state.current;
	}

	/**
	 * DE1's user-visible firmware build (MMR `0x800010`), e.g. `1352`.
	 * `null` until the connect-phase MMR sweep has completed.
	 */
	get firmwareBuild(): number | null {
		const v = this.snapshot.de1MachineInfo[MmrRegister.FirmwareVersion];
		return v == null ? null : v;
	}

	/**
	 * DE1's human-readable firmware label (e.g. `"v1.43 build 1352"`),
	 * decoded from the `Version` characteristic. `null` until the DE1
	 * connects.
	 */
	get firmwareString(): string | null {
		return this.snapshot.de1Firmware;
	}

	/**
	 * Machine-model identifier (MMR `0x80000C`): 0 = unknown, 1 = DE1,
	 * 2 = DE1+, 3 = DE1PRO, 4 = DE1XL, 5 = DE1CAFE, 6 = DE1XXL,
	 * 7 = DE1XXXL. `null` until the connect-phase MMR sweep has read it.
	 */
	get machineModel(): number | null {
		const v = this.snapshot.de1MachineInfo[MmrRegister.MachineModel];
		return v == null ? null : v;
	}

	/**
	 * DE1 CPU-board version, encoded `cpu_board_version × 1000` (raw
	 * `1100` → PCB v1.1, raw `1300` → PCB v1.3). `null` until read.
	 */
	get cpuBoardVersion(): number | null {
		const v = this.snapshot.de1MachineInfo[MmrRegister.CpuBoardVersion];
		return v == null ? null : v;
	}

	/**
	 * Cup-warmer setpoint, °C. `0` = off. Defaults to `0` when the
	 * register hasn't been read yet — same shape the legacy reader
	 * presented.
	 */
	get cupWarmerTempC(): number {
		return this.snapshot.de1MachineInfo[MmrRegister.CupWarmerTemp] ?? 0;
	}

	/**
	 * Flush-temperature setpoint, °C. `null` when unread / unsupported.
	 * The brew dashboard surfaces this above the Flush button so the
	 * user can confirm the temperature before triggering.
	 */
	get flushTempC(): number | null {
		const v = this.snapshot.de1MachineInfo[MmrRegister.FlushTemp];
		return v == null ? null : v;
	}

	/**
	 * Raw GHC Info bitmask from MMR `0x80381C` (read-only — set by the machine's
	 * firmware, never written by the app). Per the DE1 spec (reaprime
	 * `de1.models.dart`): `0x1` = LED controller present, `0x2` = touch controller
	 * present, `0x4` = active, `0x80000000` = factory mode. `0` = no GHC fitted.
	 */
	get ghcInfo(): number {
		return this.snapshot.de1MachineInfo[MmrRegister.GhcInfo] ?? 0;
	}

	/**
	 * True when a Group Head Controller is fitted — any non-zero {@link ghcInfo},
	 * matching de1app's `ghc_is_installed != 0` (`de1_comms.tcl:827`).
	 */
	get ghcPresent(): boolean {
		return this.ghcInfo !== 0;
	}

	/**
	 * True when the firmware enforces start-from-the-group-head and ignores
	 * tablet/app-initiated starts (UL safety — the app cannot override it). Mirrors
	 * de1app's `ghc_required` (`vars.tcl:3476`): any `GhcInfo` value NOT in
	 * {0, 1, 2, 4}. When true, the UI must tell the user to tap the group head to
	 * start rather than offer a Start button.
	 */
	get ghcRequired(): boolean {
		const v = this.ghcInfo;
		return v !== 0 && v !== 1 && v !== 2 && v !== 4;
	}

	/**
	 * Heater voltage MMR register (`0x803834`). The mains the firmware
	 * thinks it's running on — typically `120` or `230`. `null` until
	 * read.
	 */
	get heaterVoltage(): number | null {
		const v = this.snapshot.de1MachineInfo[MmrRegister.HeaterVoltage];
		return v == null ? null : v;
	}

	/**
	 * Calibration flow multiplier in its raw `int(1000 × m)` form. Divide
	 * by 1000 to get the multiplier the calibration UI surfaces. `null`
	 * until read.
	 */
	get calibrationFlowMultiplier(): number | null {
		const v = this.snapshot.de1MachineInfo[MmrRegister.CalibrationFlowMultiplier];
		return v == null ? null : v;
	}

	/**
	 * Per-sensor calibration map (current / factory offsets). Empty
	 * until the calibration sweep has run.
	 */
	get calibration(): De1Calibration {
		return this.snapshot.de1Calibration;
	}
}

let readout: MachineReadout | undefined;

/**
 * Singleton accessor. Constructs the readout lazily on first call using
 * the shared {@link getCremaUiState} singleton — no separate bind step.
 * Safe to call from any component at module-load time; the singletons
 * resolve eagerly and stay valid for the app's lifetime.
 */
export function getMachineReadout(): MachineReadout {
	if (!readout) readout = new MachineReadout(getCremaUiState());
	return readout;
}
