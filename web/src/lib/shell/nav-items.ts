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

export interface NavItem {
	id: string;
	/** Phosphor icon name. */
	icon: string;
	label: string;
	/** Resolved pathname, so the links honour any future `paths.base`. */
	href: string;
	/** The digit that jumps here on the rail. */
	kbd: string;
}

export const NAV_ITEMS: readonly NavItem[] = [
	{ id: 'brew', icon: 'coffee', label: 'Brew', href: resolve('/'), kbd: '1' },
	{ id: 'profiles', icon: 'list-bullets', label: 'Profiles', href: resolve('/profiles'), kbd: '2' },
	{ id: 'beans', icon: 'coffee-bean', label: 'Beans', href: resolve('/beans'), kbd: '3' },
	{ id: 'history', icon: 'chart-line', label: 'History', href: resolve('/history'), kbd: '4' },
	{ id: 'scale', icon: 'scales', label: 'Scale', href: resolve('/scale'), kbd: '5' },
	{ id: 'settings', icon: 'gear-six', label: 'Settings', href: resolve('/settings'), kbd: '6' }
];

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
