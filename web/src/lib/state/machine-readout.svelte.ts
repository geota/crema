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
 * properties — `cupWarmerTempC`, `flushTempC`, `ghcOn`, etc. Adding
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
	 * Raw GHC info bitmask from MMR `0x803818`. `0` = no GHC, non-zero
	 * = GHC present and possibly active. See {@link ghcOn} for the
	 * boolean view.
	 */
	get ghcMode(): number {
		return this.snapshot.de1MachineInfo[MmrRegister.GhcMode] ?? 0;
	}

	/** True when GHC mode is non-zero (touch-on-the-machine enabled). */
	get ghcOn(): boolean {
		return this.ghcMode > 0;
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
