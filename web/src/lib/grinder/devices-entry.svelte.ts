/**
 * `#lib/grinder/devices-entry.svelte.js` — the connected grinder's opt-in
 * place beside the DE1 and scale: a row in the phone's Devices sheet and a
 * button on the rail (beta).
 *
 * Both show only when the "Show in Devices and the rail" setting is on and
 * one of the user's grinders connects over Bluetooth: a live grinder record
 * that a device links to (`#lib/grinder/connected-link.svelte.js`). The
 * setting itself shows only on that second condition, so someone whose
 * grinders never connect never sees the setting, the row or the button.
 *
 * The status text names the grind, never the battery; nothing here reads the
 * serial or the Wi-Fi name.
 */

import { isLiveGrinder } from '#lib/bean/grinder.js';
import { getBeanStore } from '#lib/bean/store.svelte.js';
import type { Grinder } from '#lib/core/crema-core.js';
import { getSettingsStore } from '#lib/settings/index.js';
import type { ConnectedGrinderView } from '#lib/state/ui-state.svelte.js';
import { catalogueFor } from '#lib/components/grinders/connected-grinder.js';
import { getGrinderLinkStore, type GrinderLinks } from './connected-link.svelte';

/**
 * The live grinder records a device links to, in link order: the user's
 * grinders that connect over Bluetooth. Pure.
 */
export function linkedGrinders(links: GrinderLinks, grinders: readonly Grinder[]): Grinder[] {
	const out: Grinder[] = [];
	for (const link of Object.values(links)) {
		const g = grinders.find((r) => r.id === link.grinderId);
		if (g && isLiveGrinder(g) && !out.includes(g)) out.push(g);
	}
	return out;
}

/** Whether the grinder row and rail button show (the rule above). Pure. */
export function showGrinderInDevices(
	settingOn: boolean,
	links: GrinderLinks,
	grinders: readonly Grinder[]
): boolean {
	return settingOn && linkedGrinders(links, grinders).length > 0;
}

/**
 * The grinder's name in the row and the rail's menu: the connected model's
 * catalogue name ("BOOKOO Motto 80"), else its shown advertised name (the
 * model prefix only), else the linked record this device is, else the first
 * linked record. Pure.
 */
export function grinderDeviceName(
	view: Pick<ConnectedGrinderView, 'model' | 'name' | 'deviceId'>,
	links: GrinderLinks,
	linked: readonly Grinder[]
): string {
	const own = view.deviceId ? links[view.deviceId]?.grinderId : undefined;
	return (
		catalogueFor(view.model)?.name ??
		view.name ??
		linked.find((g) => g.id === own)?.name ??
		linked[0]?.name ??
		'Grinder'
	);
}

/**
 * The detail after "Connected": the current grind setting ("Grind 2.4", or
 * "Grind 480 μm" where the grinder reports a unit), or null before a
 * reading carries one. Pure.
 */
export function grinderDeviceDetail(
	view: Pick<ConnectedGrinderView, 'reading' | 'capabilities'>
): string | null {
	const setting = view.reading?.setting;
	if (!setting) return null;
	const unit = view.capabilities?.setting?.unit ?? '';
	return unit ? `Grind ${setting.value} ${unit}` : `Grind ${setting.value}`;
}

/** Whether one of the user's grinders connects over Bluetooth (the setting shows). Reactive. */
export function hasBluetoothGrinder(): boolean {
	return linkedGrinders(getGrinderLinkStore().links, getBeanStore().allGrinders).length > 0;
}

/** {@link showGrinderInDevices} over the app's stores. Reactive. */
export function grinderInDevices(): boolean {
	return (
		getSettingsStore().current.grinderInDevices === true &&
		showGrinderInDevices(true, getGrinderLinkStore().links, getBeanStore().allGrinders)
	);
}

/** The linked grinder records, over the app's stores. Reactive. */
export function linkedGrinderRecords(): Grinder[] {
	return linkedGrinders(getGrinderLinkStore().links, getBeanStore().allGrinders);
}
