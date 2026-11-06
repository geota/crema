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
 */
import type { Component } from 'svelte';

/** One round action in the bar. Give `onclick` for a button, `href` for a link. */
export interface PhoneBarAction {
	/** Stable key, unique within the bar. */
	id: string;
	/** Accessible name: the bar shows only the glyph. */
	label: string;
	/** A `phosphor-svelte` icon component. */
	icon: Component;
	onclick?: () => void;
	href?: string;
	/** Copper glyph, as the Bluetooth action when a machine is connected. */
	accent?: boolean;
	/** Set when the action opens a sheet, so assistive tech announces it. */
	haspopup?: 'dialog';
}

/** The bar's two shared actions, placed by name: the Devices sheet and Settings. */
export type PhoneBarBuiltin = 'devices' | 'settings';

export interface PhoneBarConfig {
	/** The screen's title. Omit for the nav item's label. */
	title?: string;
	/** The actions, left to right. Omit for {@link DEFAULT_PHONE_BAR_ACTIONS}. */
	actions?: readonly (PhoneBarAction | PhoneBarBuiltin)[];
}

/** What a screen without its own actions shows. */
export const DEFAULT_PHONE_BAR_ACTIONS: readonly PhoneBarBuiltin[] = ['devices', 'settings'];

let current = $state.raw<{ owner: object; config: PhoneBarConfig } | null>(null);

/** The mounted route's config, or `null` when no route has set one. */
export function phoneBarConfig(): PhoneBarConfig | null {
	return current?.config ?? null;
}

/**
 * Set the bar for as long as the calling component is mounted. Call it during
 * init; `config` is re-read whenever a value it uses changes. Clearing checks
 * the owner because on navigation the next route may claim the bar first.
 */
export function usePhoneBar(config: () => PhoneBarConfig): void {
	const owner = {};
	$effect.pre(() => {
		current = { owner, config: config() };
	});
	$effect.pre(() => () => {
		if (current?.owner === owner) current = null;
	});
}
