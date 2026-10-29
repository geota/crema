/**
 * Focus and keyboard helpers shared by the app's hand-rolled overlays (the
 * Brew Quick Sheet, the bean drawer) and menus (SplitButton, SortPill, the
 * Beans "Add" menu). Plain DOM functions so they unit-test under jsdom and
 * carry no component state (cohort review 2026-10-25, report 07 #3, #4, #9).
 */

/** Elements that can take keyboard focus in the normal tab order. */
export const FOCUSABLE_SELECTOR = [
	'a[href]',
	'button:not([disabled])',
	'input:not([disabled]):not([type="hidden"])',
	'select:not([disabled])',
	'textarea:not([disabled])',
	'[contenteditable="true"]',
	'[tabindex]:not([tabindex="-1"])'
].join(',');

function isRendered(el: HTMLElement): boolean {
	// `checkVisibility` covers display:none / visibility:hidden ancestors in
	// browsers; jsdom has no layout, so treat elements as rendered there.
	const check = (el as HTMLElement & { checkVisibility?: (o?: object) => boolean })
		.checkVisibility;
	return typeof check === 'function' ? check.call(el, { visibilityProperty: true }) : true;
}

/**
 * The tabbable descendants of `root`, in DOM order. Skips anything inside an
 * `inert` or `hidden` subtree, `tabindex="-1"` elements and unrendered ones.
 */
export function focusableWithin(root: HTMLElement): HTMLElement[] {
	return [...root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)].filter(
		(el) => !el.closest('[inert],[hidden]') && isRendered(el)
	);
}

/**
 * Keep Tab / Shift+Tab inside `root` (a modal dialog's panel). Returns true
 * when it moved focus itself (the event's default is then prevented). Focus
 * that has escaped `root` is pulled back to the first (or, with Shift, the
 * last) tabbable element.
 */
export function trapTab(e: KeyboardEvent, root: HTMLElement): boolean {
	if (e.key !== 'Tab') return false;
	const items = focusableWithin(root);
	const active = root.ownerDocument.activeElement as HTMLElement | null;
	if (items.length === 0) {
		e.preventDefault();
		root.focus();
		return true;
	}
	const first = items[0];
	const last = items[items.length - 1];
	const inside = active != null && root.contains(active);
	if (e.shiftKey) {
		if (!inside || active === first || active === root) {
			e.preventDefault();
			last.focus();
			return true;
		}
	} else if (!inside || active === last) {
		e.preventDefault();
		first.focus();
		return true;
	}
	return false;
}

/**
 * Arrow-key navigation for a `role="menu"` list: ArrowDown / ArrowUp move to
 * the next / previous item (wrapping), Home / End to the first / last.
 * Returns true when the key was handled (the default is then prevented).
 */
export function menuKeyNav(e: KeyboardEvent, items: readonly HTMLElement[]): boolean {
	if (items.length === 0) return false;
	const active = (e.currentTarget as HTMLElement | null)?.ownerDocument?.activeElement ?? null;
	const idx = items.indexOf(active as HTMLElement);
	let next: number;
	switch (e.key) {
		case 'ArrowDown':
			next = idx < 0 ? 0 : (idx + 1) % items.length;
			break;
		case 'ArrowUp':
			next = idx < 0 ? items.length - 1 : (idx - 1 + items.length) % items.length;
			break;
		case 'Home':
			next = 0;
			break;
		case 'End':
			next = items.length - 1;
			break;
		default:
			return false;
	}
	e.preventDefault();
	items[next].focus();
	return true;
}

/** The menu items (plain, radio, checkbox) inside a menu, skipping disabled ones. */
export function menuItems(menu: HTMLElement | null | undefined): HTMLElement[] {
	if (!menu) return [];
	return [
		...menu.querySelectorAll<HTMLElement>(
			'[role="menuitem"],[role="menuitemradio"],[role="menuitemcheckbox"]'
		)
	].filter(
		(el) => !(el as HTMLButtonElement).disabled && el.getAttribute('aria-disabled') !== 'true'
	);
}

/**
 * Whether a keydown is Enter / Space aimed at the element the handler is on
 * (not bubbled up from a nested control). A `role="button"` container that
 * also holds real buttons must only activate on its own keys, or Enter on a
 * nested "Delete" would run the container's action instead (report 07 #4).
 */
export function isOwnActivationKey(e: KeyboardEvent): boolean {
	return (e.key === 'Enter' || e.key === ' ') && e.target === e.currentTarget;
}

/**
 * Focus `el` if it is still in the document; returns whether it did. Used to
 * hand focus back to an overlay's opener after the overlay closes.
 */
export function restoreFocus(el: Element | null | undefined): boolean {
	if (!(el instanceof HTMLElement) || !el.isConnected) return false;
	el.focus();
	return true;
}

export interface MenuKeyboardOptions {
	/** Close the menu (the caller flips its `open` state). */
	onClose: () => void;
	/** The button that opened the menu; focus returns here on Escape / pick. */
	trigger: () => HTMLElement | null | undefined;
}

/**
 * Attachment for a hand-rolled `role="menu"` that is mounted while open
 * (`{#if open}<div role="menu" {@attach menuKeyboard(...)}>`). It brings the
 * keyboard contract screen readers expect from that role (report 07 #9):
 * focus moves to the first item on open; ArrowUp / ArrowDown / Home / End
 * move between items; Escape closes and returns focus to the trigger; Tab
 * closes and lets focus move on. When the menu unmounts with focus still
 * inside it (an item was picked), focus goes back to the trigger.
 */
export function menuKeyboard(opts: MenuKeyboardOptions): (node: HTMLElement) => () => void {
	return (node) => {
		menuItems(node)[0]?.focus();
		function onKeydown(e: KeyboardEvent): void {
			if (e.key === 'Escape') {
				e.preventDefault();
				e.stopPropagation();
				opts.onClose();
				restoreFocus(opts.trigger());
				return;
			}
			if (e.key === 'Tab') {
				opts.onClose();
				return;
			}
			menuKeyNav(e, menuItems(node));
		}
		node.addEventListener('keydown', onKeydown);
		return () => {
			node.removeEventListener('keydown', onKeydown);
			const active = node.ownerDocument.activeElement;
			if (active == null || active === node.ownerDocument.body || node.contains(active)) {
				restoreFocus(opts.trigger());
			}
		};
	};
}
