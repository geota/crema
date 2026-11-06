/**
 * `#lib/shell/phone-bar.svelte.js` — what the phone top bar shows for the
 * screen on display.
 *
 * A route calls {@link usePhoneBar} during component init. The bar reads the
 * config while the route is mounted and falls back to the default once it
 * unmounts, so a route never has to clean up after itself:
 *
 * ```ts
 * usePhoneBar(() => ({
 *   title: 'Beans',
 *   actions: [
 *     { id: 'import', label: 'Import beans', icon: UploadSimpleIcon, onclick: openImport },
 *     'settings'
 *   ]
 * }));
 * ```
 *
 * The getter is reactive, so an action can follow the route's state. Without a
 * config the bar shows the nav item's label and {@link DEFAULT_PHONE_BAR_ACTIONS}.
 *
 * A pushed screen sets `back` and the bar becomes Android's `CremaPhoneBackBar`:
 * ← plus the screen's name, in place of the mark and title. The name is then
 * the screen's `<h1>`, so only set `back` at phone width and hide the page's own.
 */
import type { Component } from 'svelte';

/** One round action in the bar. Give `onclick` for a button, `href` for a link. */
export interface PhoneBarAction {
	/** Stable key, unique within the bar. */
	id: string;
	/** Accessible name: the bar shows only the glyph. */
	label: string;
	/** A `phosphor-svelte` icon component. Omit for a `text` action. */
	icon?: Component;
	/** A worded action in place of a glyph, as an editor's Cancel and Save. */
	text?: string;
	/** Draw a `text` action as the filled pill. */
	primary?: boolean;
	onclick?: () => void;
	href?: string;
	/** Copper glyph, as the Bluetooth action when a machine is connected. */
	accent?: boolean;
	/** Set when the action opens a sheet, so assistive tech announces it. */
	haspopup?: 'dialog';
	disabled?: boolean;
}

/** The bar's two shared actions, placed by name: the Devices sheet and Settings. */
export type PhoneBarBuiltin = 'devices' | 'settings';

/** Makes the bar a pushed screen's back bar. */
export interface PhoneBarBack {
	/** Accessible name of the ← button. */
	label: string;
	onBack: () => void;
	/** Small line above the title, Android's `Beans ›` on an editor. */
	breadcrumb?: string;
	/** Small line under the title, as a bag's roaster. */
	subtitle?: string;
}

export interface PhoneBarConfig {
	/** The screen's title. Omit for the nav item's label. */
	title?: string;
	back?: PhoneBarBack;
	/** The actions, left to right. Omit for {@link DEFAULT_PHONE_BAR_ACTIONS}. */
	actions?: readonly (PhoneBarAction | PhoneBarBuiltin)[];
}

/** What a screen without its own actions shows: Android keeps Bluetooth on Brew. */
export const DEFAULT_PHONE_BAR_ACTIONS: readonly PhoneBarBuiltin[] = ['settings'];

/**
 * Every mounted route's claim, newest last. A plain array, not state: the next
 * route mounts inside a navigation's batch, where the outgoing route's teardown
 * would read `$state` from before that batch and write a stale list back.
 */
const claims: { owner: object; config: PhoneBarConfig }[] = [];
/** The claim on show. */
let shown = $state.raw<PhoneBarConfig | null>(null);
let devicesOpen = $state(false);

/** Whether the shell's Devices sheet is open. */
export function phoneDevicesOpen(): boolean {
	return devicesOpen;
}

/** Open or close the Devices sheet, as Scale's Connect scale does. */
export function setPhoneDevicesOpen(open: boolean): void {
	devicesOpen = open;
}

/** The mounted route's config, or `null` when no route has set one. */
export function phoneBarConfig(): PhoneBarConfig | null {
	return shown;
}

/**
 * Set the bar for as long as the calling component is mounted. Call it during
 * init; `config` is re-read whenever a value it uses changes.
 *
 * Claims stack because a navigation mounts the next route before the last one
 * goes: the outgoing route's config can re-run in that window (`page.state`
 * has just changed), and it must neither take the bar back nor clear it.
 */
export function usePhoneBar(config: () => PhoneBarConfig): void {
	const owner = {};
	$effect.pre(() => {
		const next = config();
		const claim = claims.find((c) => c.owner === owner);
		if (claim) claim.config = next;
		else claims.push({ owner, config: next });
		shown = claims.at(-1)?.config ?? null;
	});
	$effect.pre(() => () => {
		const index = claims.findIndex((c) => c.owner === owner);
		if (index !== -1) claims.splice(index, 1);
		shown = claims.at(-1)?.config ?? null;
	});
}
