/**
 * `#lib/shell/rail.svelte.js` — the rail's Devices entry (brew-modes spec §1).
 *
 * When the window is tall enough for every rail item plus the DE1 and scale
 * buttons without scrolling, Devices shows as two items, Scale and Grinder.
 * Otherwise it stays one Devices item, which opens the tab used last (as
 * Android's `devicesTab` does).
 */
import { MediaQuery } from 'svelte/reactivity';
import {
	devicesTabOf,
	isDevicesPath,
	type DevicesTab,
	type PageLocation
} from '#lib/components/grinders/devices-link.js';

/**
 * The height the split rail needs: the mark, seven items and the two device
 * buttons, with the rail's padding: 634 px measured at the default font, plus slack.
 */
export const RAIL_SPLIT_QUERY = '(min-height: 680px)';

/** True when the rail has room for Scale and Grinder. */
export const railHasRoom = new MediaQuery(RAIL_SPLIT_QUERY, true);

let lastTab = $state<DevicesTab>('scale');

/** The Devices tab on show last; Scale until the page has been opened. */
export function lastDevicesTab(): DevicesTab {
	return lastTab;
}

/** Remember the tab when `url` is the Devices page. Called after each navigation. */
export function noteDevicesVisit(url: PageLocation): void {
	if (isDevicesPath(url.pathname)) lastTab = devicesTabOf(url.searchParams);
}
