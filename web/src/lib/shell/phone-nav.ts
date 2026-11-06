/**
 * `#lib/shell/phone-nav.js` — how the phone-width list → detail screens are
 * entered and left, the same rule as `#lib/beans/editor-nav`.
 *
 * At phone width (and only there) History's shot detail, Beans' bag detail and
 * Settings' section screens are history entries of their own, so system /
 * browser Back returns to the list. Leaving by the bar's ← steps back from an entry the list
 * pushed. A deep link has no list entry behind it, so ← replaces itself with
 * the list instead of leaving the app.
 *
 * Wider screens never push: the split panes stay plain page state.
 */

import { goto } from '$app/navigation';
import { resolve } from '$app/paths';
import { page } from '$app/state';
import { phoneLayout } from './phone.svelte.js';

/** The shot on a phone detail screen: this entry's, or a `?shot=` deep link's. */
export function phoneShotId(): string | null {
	if (!phoneLayout.current) return null;
	return page.state.historyShot ?? page.url.searchParams.get('shot');
}

/** Open a shot's detail screen. Shallow: the URL stays the list's. */
export function openShot(id: string): void {
	if (!phoneLayout.current) return;
	void goto(page.url.href, { state: { historyShot: id }, shallow: true, persistState: true });
}

/** Leave the shot detail screen for the list. */
export function leaveShot(): void {
	if (page.state.historyShot != null) {
		history.back();
		return;
	}
	const list = new URL(page.url.href);
	list.searchParams.delete('shot');
	void goto(list, { replace: true, reset: false });
}

/** The bag on a phone detail screen, or `null` on the list. */
export function phoneBeanId(): string | null {
	if (!phoneLayout.current) return null;
	return page.state.beanDetail ?? null;
}

/** Open a bag's detail screen. Shallow: the URL stays the list's. */
export function openBean(id: string): void {
	if (!phoneLayout.current) return;
	void goto(page.url.href, { state: { beanDetail: id }, shallow: true, persistState: true });
}

/** Leave the bag detail screen for the list. */
export function leaveBean(): void {
	if (page.state.beanDetail != null) history.back();
}

/** Open a Settings section screen. The hash is the screen. */
export function openSection(id: string): void {
	if (!phoneLayout.current) return;
	void goto(`${resolve('/settings')}#${id}`, {
		state: { fromSettingsList: true },
		persistState: true,
		reset: false
	});
}

/** Leave a Settings section screen for the list. */
export function leaveSection(): void {
	if (page.state.fromSettingsList) history.back();
	else void goto(resolve('/settings'), { replace: true, reset: false });
}

/** Attachment: take focus on mount, so a screen reader announces the new screen. */
export function focusOnMount(node: HTMLElement): void {
	node.focus({ preventScroll: true });
}
