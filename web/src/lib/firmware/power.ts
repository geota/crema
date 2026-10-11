/**
 * `$lib/firmware/power` — the "power ok" precondition for a firmware flash:
 * charging, or battery at 50 % or more. None of the reference apps checks
 * this; crema adds it for a ~20-minute radio-heavy job. A browser without the
 * Battery Status API (or a desktop that reports no battery) counts as ok.
 */

/** Battery fraction (0–1) below which an unplugged device may not flash. */
export const MIN_BATTERY_FRACTION = 0.5;

interface BatteryLike {
	level: number;
	charging: boolean;
}

/** Pure rule, for tests. */
export function powerOk(battery: BatteryLike | null): boolean {
	if (battery === null) return true;
	return battery.charging || battery.level >= MIN_BATTERY_FRACTION;
}

/** Read the battery and apply {@link powerOk}. */
export async function readPowerOk(): Promise<boolean> {
	try {
		const nav = globalThis.navigator as
			| (Navigator & { getBattery?: () => Promise<BatteryLike> })
			| undefined;
		if (!nav?.getBattery) return true;
		return powerOk(await nav.getBattery());
	} catch {
		return true;
	}
}
