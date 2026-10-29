import { afterEach, describe, expect, it } from 'vitest';
import {
	focusableWithin,
	isOwnActivationKey,
	menuItems,
	menuKeyboard,
	menuKeyNav,
	restoreFocus,
	trapTab
} from './focus.js';

function html(markup: string): HTMLElement {
	const root = document.createElement('div');
	root.innerHTML = markup;
	document.body.appendChild(root);
	return root;
}

function key(target: HTMLElement, k: string, opts: KeyboardEventInit = {}): KeyboardEvent {
	const e = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...opts });
	Object.defineProperty(e, 'currentTarget', { value: target });
	return e;
}

afterEach(() => {
	document.body.innerHTML = '';
});

describe('focusableWithin', () => {
	it('lists tabbable descendants and skips disabled, tabindex=-1, inert and hidden ones', () => {
		const root = html(`
			<button id="a">a</button>
			<button disabled>x</button>
			<input id="b" />
			<input type="hidden" />
			<div tabindex="-1">x</div>
			<div tabindex="0" id="c">c</div>
			<div inert><button>x</button></div>
			<div hidden><a href="#">x</a></div>
			<a href="#" id="d">d</a>
		`);
		expect(focusableWithin(root).map((el) => el.id)).toEqual(['a', 'b', 'c', 'd']);
	});
});

describe('trapTab', () => {
	it('wraps Tab from the last element to the first', () => {
		const root = html('<button id="first">1</button><button id="last">2</button>');
		const last = root.querySelector<HTMLElement>('#last')!;
		last.focus();
		const e = key(root, 'Tab');
		expect(trapTab(e, root)).toBe(true);
		expect(e.defaultPrevented).toBe(true);
		expect(document.activeElement?.id).toBe('first');
	});

	it('wraps Shift+Tab from the first element to the last', () => {
		const root = html('<button id="first">1</button><button id="last">2</button>');
		root.querySelector<HTMLElement>('#first')!.focus();
		expect(trapTab(key(root, 'Tab', { shiftKey: true }), root)).toBe(true);
		expect(document.activeElement?.id).toBe('last');
	});

	it('wraps Shift+Tab from the panel itself to the last element', () => {
		const root = html('<button id="first">1</button><button id="last">2</button>');
		root.tabIndex = -1;
		root.focus();
		expect(trapTab(key(root, 'Tab', { shiftKey: true }), root)).toBe(true);
		expect(document.activeElement?.id).toBe('last');
	});

	it('leaves Tab between inner elements to the browser', () => {
		const root = html('<button id="first">1</button><button id="mid">m</button><button>2</button>');
		root.querySelector<HTMLElement>('#first')!.focus();
		const e = key(root, 'Tab');
		expect(trapTab(e, root)).toBe(false);
		expect(e.defaultPrevented).toBe(false);
	});

	it('pulls escaped focus back inside', () => {
		const outside = html('<button id="out">o</button>');
		const root = html('<button id="first">1</button><button id="last">2</button>');
		outside.querySelector<HTMLElement>('#out')!.focus();
		expect(trapTab(key(root, 'Tab'), root)).toBe(true);
		expect(document.activeElement?.id).toBe('first');
	});

	it('focuses the panel when it has nothing tabbable', () => {
		const root = html('<p>text</p>');
		root.tabIndex = -1;
		expect(trapTab(key(root, 'Tab'), root)).toBe(true);
		expect(document.activeElement).toBe(root);
	});

	it('ignores other keys', () => {
		const root = html('<button>1</button>');
		expect(trapTab(key(root, 'Enter'), root)).toBe(false);
	});
});

describe('menuKeyNav', () => {
	const menu = () =>
		html(`<div role="menu">
			<button role="menuitem" id="m1">1</button>
			<button role="menuitem" id="m2" disabled>2</button>
			<button role="menuitem" id="m3">3</button>
			<button role="menuitem" id="m4" aria-disabled="true">4</button>
		</div>`).firstElementChild as HTMLElement;

	it('menuItems skips disabled items', () => {
		expect(menuItems(menu()).map((el) => el.id)).toEqual(['m1', 'm3']);
	});

	it('moves with ArrowDown / ArrowUp, wrapping, and Home / End', () => {
		const m = menu();
		const items = menuItems(m);
		items[0].focus();
		expect(menuKeyNav(key(m, 'ArrowDown'), items)).toBe(true);
		expect(document.activeElement?.id).toBe('m3');
		menuKeyNav(key(m, 'ArrowDown'), items);
		expect(document.activeElement?.id).toBe('m1');
		menuKeyNav(key(m, 'ArrowUp'), items);
		expect(document.activeElement?.id).toBe('m3');
		menuKeyNav(key(m, 'Home'), items);
		expect(document.activeElement?.id).toBe('m1');
		menuKeyNav(key(m, 'End'), items);
		expect(document.activeElement?.id).toBe('m3');
	});

	it('starts at the first item on ArrowDown when focus is outside the items', () => {
		const m = menu();
		const items = menuItems(m);
		menuKeyNav(key(m, 'ArrowDown'), items);
		expect(document.activeElement?.id).toBe('m1');
	});

	it('does not handle unrelated keys or empty menus', () => {
		const m = menu();
		expect(menuKeyNav(key(m, 'a'), menuItems(m))).toBe(false);
		expect(menuKeyNav(key(m, 'ArrowDown'), [])).toBe(false);
	});
});

describe('isOwnActivationKey', () => {
	it('is true only for Enter / Space aimed at the handler element itself', () => {
		const card = html('<button id="inner">Pin</button>');
		const inner = card.querySelector<HTMLElement>('#inner')!;
		const own = new KeyboardEvent('keydown', { key: 'Enter' });
		Object.defineProperty(own, 'target', { value: card });
		Object.defineProperty(own, 'currentTarget', { value: card });
		expect(isOwnActivationKey(own)).toBe(true);

		const bubbled = new KeyboardEvent('keydown', { key: 'Enter' });
		Object.defineProperty(bubbled, 'target', { value: inner });
		Object.defineProperty(bubbled, 'currentTarget', { value: card });
		expect(isOwnActivationKey(bubbled)).toBe(false);

		const space = new KeyboardEvent('keydown', { key: ' ' });
		Object.defineProperty(space, 'target', { value: card });
		Object.defineProperty(space, 'currentTarget', { value: card });
		expect(isOwnActivationKey(space)).toBe(true);

		const other = new KeyboardEvent('keydown', { key: 'a' });
		Object.defineProperty(other, 'target', { value: card });
		Object.defineProperty(other, 'currentTarget', { value: card });
		expect(isOwnActivationKey(other)).toBe(false);
	});
});

describe('restoreFocus', () => {
	it('focuses a connected element and refuses a detached one', () => {
		const root = html('<button id="b">b</button>');
		const b = root.querySelector<HTMLElement>('#b')!;
		expect(restoreFocus(b)).toBe(true);
		expect(document.activeElement).toBe(b);
		const detached = document.createElement('button');
		expect(restoreFocus(detached)).toBe(false);
		expect(restoreFocus(null)).toBe(false);
	});
});

describe('menuKeyboard', () => {
	function setup() {
		const root = html(`
			<button id="trigger">open</button>
			<div role="menu" id="menu">
				<button role="menuitem" id="i1">1</button>
				<button role="menuitem" id="i2">2</button>
			</div>`);
		const trigger = root.querySelector<HTMLElement>('#trigger')!;
		const menu = root.querySelector<HTMLElement>('#menu')!;
		let closed = 0;
		const detach = menuKeyboard({ onClose: () => closed++, trigger: () => trigger })(menu);
		return { trigger, menu, detach, closed: () => closed };
	}
	const press = (el: HTMLElement, key: string) =>
		el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));

	it('focuses the first item on open and moves with the arrow keys', () => {
		const { menu } = setup();
		expect(document.activeElement?.id).toBe('i1');
		press(document.activeElement as HTMLElement, 'ArrowDown');
		expect(document.activeElement?.id).toBe('i2');
		press(menu, 'Home');
		expect(document.activeElement?.id).toBe('i1');
	});

	it('closes on Escape and returns focus to the trigger', () => {
		const t = setup();
		press(document.activeElement as HTMLElement, 'Escape');
		expect(t.closed()).toBe(1);
		expect(document.activeElement).toBe(t.trigger);
	});

	it('closes on Tab without stealing focus back', () => {
		const t = setup();
		press(document.activeElement as HTMLElement, 'Tab');
		expect(t.closed()).toBe(1);
	});

	it('returns focus to the trigger when it unmounts with focus inside', () => {
		const t = setup();
		t.detach();
		expect(document.activeElement).toBe(t.trigger);
	});

	it('leaves focus alone when it unmounts after focus moved elsewhere', () => {
		const t = setup();
		const other = html('<button id="other">x</button>').querySelector<HTMLElement>('#other')!;
		other.focus();
		t.detach();
		expect(document.activeElement).toBe(other);
	});
});
