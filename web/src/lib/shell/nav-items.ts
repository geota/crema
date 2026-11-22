/**
 * `#lib/shell/nav-items.js` — the app's primary destinations, one list for the
 * two navs: the rail (`CremaSidebar`) above 600 px and the tab bar
 * (`PhoneTabBar`) at phone width.
 *
 * The array is the rail's order. The tab bar uses {@link PHONE_TABS}, the
 * order of Android's `CremaPhoneDestinations`; Settings is not a tab there, it
 * is the gear in `PhoneTopBar`.
 */

import { resolve } from '$app/paths';
import {
	devicesHref,
	devicesTabOf,
	isDevicesPath,
	type DevicesTab,
	type PageLocation
} from '#lib/components/grinders/devices-link.js';

export interface NavItem {
	id: string;
	/** Phosphor icon name. */
	icon: string;
	label: string;
	/** Resolved pathname, so the links honour any future `paths.base`. */
	href: string;
	/** The digit that jumps here on the rail. */
	kbd: string;
	/** The Devices tab a split rail item opens (`railItems`). */
	devicesTab?: DevicesTab;
}

export const NAV_ITEMS: readonly NavItem[] = [
	{ id: 'brew', icon: 'coffee', label: 'Brew', href: resolve('/'), kbd: '1' },
	{ id: 'profiles', icon: 'list-bullets', label: 'Profiles', href: resolve('/profiles'), kbd: '2' },
	{ id: 'beans', icon: 'coffee-bean', label: 'Beans', href: resolve('/beans'), kbd: '3' },
	{ id: 'history', icon: 'chart-line', label: 'History', href: resolve('/history'), kbd: '4' },
	{ id: 'scale', icon: 'scales', label: 'Devices', href: resolve('/scale'), kbd: '5' },
	{ id: 'settings', icon: 'gear-six', label: 'Settings', href: resolve('/settings'), kbd: '6' }
];

/** The rail's Scale and Grinder items, in place of Devices when there's room. */
const DEVICES_SPLIT: readonly NavItem[] = [
	{ id: 'devices-scale', icon: 'scales', label: 'Scale', href: devicesHref('scale'), kbd: '', devicesTab: 'scale' },
	{ id: 'devices-grinder', icon: 'funnel', label: 'Grinder', href: devicesHref('grinder'), kbd: '', devicesTab: 'grinder' }
];

/**
 * The rail's items (brew-modes spec §1). With `split`, Devices becomes Scale
 * and Grinder; otherwise it is one Devices item opening `lastTab`. The digits
 * follow the order on show, so they always match the rail top to bottom.
 */
export function railItems(split: boolean, lastTab: DevicesTab): NavItem[] {
	return NAV_ITEMS.flatMap((it): readonly NavItem[] => {
		if (it.id !== 'scale') return [it];
		return split ? DEVICES_SPLIT : [{ ...it, href: devicesHref(lastTab) }];
	}).map((it, i) => ({ ...it, kbd: String(i + 1) }));
}

/**
 * Is `url` this rail item's page? A split item matches only its own Devices
 * tab; the others match their path exactly, as the rail always has.
 */
export function railItemActive(item: NavItem, url: PageLocation): boolean {
	if (item.devicesTab) {
		return isDevicesPath(url.pathname) && devicesTabOf(url.searchParams) === item.devicesTab;
	}
	return url.pathname === item.href.split('?')[0];
}

/** Android's bottom-nav order. */
const PHONE_TAB_ORDER = ['brew', 'scale', 'profiles', 'beans', 'history'];

export const PHONE_TABS: readonly NavItem[] = PHONE_TAB_ORDER.map(
	(id) => NAV_ITEMS.find((it) => it.id === id) as NavItem
);

/** Is `pathname` this tab or one of its pushed routes (`/beans/…` for Beans)? */
export function tabIsActive(tab: NavItem, pathname: string): boolean {
	if (tab.href === resolve('/')) return pathname === tab.href;
	return pathname === tab.href || pathname.startsWith(`${tab.href}/`);
}

/**
 * Does this route show the tab bar? Every route except the pushed editors
 * under `/profiles` and `/beans`, which have their own back and full-height
 * forms (Android hides its bar on `profile-edit` / `bean-edit` too).
 */
export function showsPhoneTabs(routeId: string | null): boolean {
	return !routeId || !/^\/(profiles|beans)\/./.test(routeId);
}
