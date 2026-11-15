/**
 * `#lib/testing/list-layout` — jsdom has no layout, so a windowed list can't
 * tell where its viewport is. This lays out what `ScrollMemoryList` renders
 * (#310 P5w): the element marked `data-test-scroller` is a `view`-px
 * viewport at client top 0, with `data-test-header` px above the list; the
 * list (`.vl`) and its items (`.vl-item`, placed by their inline `top` and
 * `height`) move with the scroller's `scrollTop`, which clamps and fires
 * `scroll` as a browser's does. NOT used by the app.
 */

function rect(top: number, height: number): DOMRect {
	return { top, bottom: top + height, height, left: 0, right: 400, width: 400, x: 0, y: top, toJSON: () => ({}) } as DOMRect;
}

const offsets = new WeakMap<Element, number>();

function scrollerOf(el: Element): HTMLElement | null {
	return el.closest<HTMLElement>('[data-test-scroller]');
}

function contentHeight(sc: HTMLElement): number {
	const vl = sc.querySelector<HTMLElement>('.vl');
	return Number(sc.dataset.testHeader ?? 0) + parseFloat(vl?.style.height || '0');
}

/** Lay out test lists in a `view`-px viewport; returns the undo. */
export function installListLayout(view = 600): () => void {
	const proto = HTMLElement.prototype;
	const rectBefore = Object.getOwnPropertyDescriptor(Element.prototype, 'getBoundingClientRect');
	const topBefore = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop');
	const heightBefore = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight');
	Object.defineProperty(proto, 'scrollTop', {
		configurable: true,
		get(this: HTMLElement) {
			return offsets.get(this) ?? 0;
		},
		set(this: HTMLElement, v: number) {
			const max = this.hasAttribute('data-test-scroller') ? Math.max(0, contentHeight(this) - view) : 0;
			const next = Math.min(max, Math.max(0, v));
			if (next === (offsets.get(this) ?? 0)) return;
			offsets.set(this, next);
			this.dispatchEvent(new Event('scroll'));
		}
	});
	Object.defineProperty(proto, 'clientHeight', {
		configurable: true,
		get(this: HTMLElement) {
			return this.hasAttribute('data-test-scroller') ? view : 0;
		}
	});
	Object.defineProperty(proto, 'getBoundingClientRect', {
		configurable: true,
		value(this: HTMLElement): DOMRect {
			if (this.hasAttribute('data-test-scroller')) return rect(0, view);
			const sc = scrollerOf(this);
			if (!sc) return rect(0, 0);
			const listTop = Number(sc.dataset.testHeader ?? 0) - sc.scrollTop;
			if (this.classList.contains('vl')) return rect(listTop, parseFloat(this.style.height || '0'));
			const slot = this.closest<HTMLElement>('.vl-item');
			if (slot) return rect(listTop + parseFloat(slot.style.top), parseFloat(slot.style.height));
			return rect(0, 0);
		}
	});
	return () => {
		delete (proto as unknown as Record<string, unknown>).scrollTop;
		delete (proto as unknown as Record<string, unknown>).clientHeight;
		delete (proto as unknown as Record<string, unknown>).getBoundingClientRect;
		if (rectBefore) Object.defineProperty(Element.prototype, 'getBoundingClientRect', rectBefore);
		if (topBefore) Object.defineProperty(Element.prototype, 'scrollTop', topBefore);
		if (heightBefore) Object.defineProperty(Element.prototype, 'clientHeight', heightBefore);
	};
}
