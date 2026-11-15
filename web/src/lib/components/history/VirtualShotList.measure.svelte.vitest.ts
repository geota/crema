/**
 * #423: the windowed list's item heights follow the text size. One hidden
 * sample of each kind is measured on mount and again when its size changes
 * (a ResizeObserver: text scaling, a web font arriving); until a sample has a
 * box, the kind keeps its fallback height. A probe reports the list's width
 * over the text scale, which no `em` query can see under text scaling.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import MeasuredList from '#lib/testing/MeasuredList.svelte';

/** Each sample's drawn height by kind (0: not laid out), and the probe's. */
let sampleHeight: Record<string, number> = {};
let probeHeight = 0;
const LIST_WIDTH = 462;

/** A ResizeObserver the test fires by hand. */
class FakeResizeObserver {
	static live = new Set<FakeResizeObserver>();
	readonly targets = new Set<Element>();
	constructor(private readonly cb: ResizeObserverCallback) {
		FakeResizeObserver.live.add(this);
	}
	observe(el: Element): void {
		this.targets.add(el);
	}
	unobserve(el: Element): void {
		this.targets.delete(el);
	}
	disconnect(): void {
		this.targets.clear();
		FakeResizeObserver.live.delete(this);
	}
	/** As the browser does after a layout that resized `el`. */
	static resized(el: Element): void {
		for (const ro of FakeResizeObserver.live) {
			if (ro.targets.has(el)) ro.cb([], ro as unknown as ResizeObserver);
		}
	}
}

function rect(width: number, height: number): DOMRect {
	return { top: 0, left: 0, bottom: height, right: width, width, height, x: 0, y: 0, toJSON: () => ({}) } as DOMRect;
}

let app: Record<string, unknown> | null = null;
let restore: (() => void) | null = null;

beforeEach(() => {
	sampleHeight = {};
	probeHeight = 0;
	const g = globalThis as { ResizeObserver?: unknown };
	const roBefore = g.ResizeObserver;
	g.ResizeObserver = FakeResizeObserver;
	const rectBefore = Object.getOwnPropertyDescriptor(Element.prototype, 'getBoundingClientRect')!;
	Object.defineProperty(Element.prototype, 'getBoundingClientRect', {
		configurable: true,
		value(this: Element): DOMRect {
			if (this.classList.contains('vl-sample')) {
				const kind = this.querySelector<HTMLElement>('[data-kind]')?.dataset.kind ?? '';
				return rect(LIST_WIDTH, sampleHeight[kind] ?? 0);
			}
			if (this.classList.contains('vl-probe')) return rect(probeHeight > 0 ? LIST_WIDTH : 0, probeHeight);
			return rect(0, 0);
		}
	});
	// A viewport for the host's scroller, so every item renders.
	const heightBefore = Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight')!;
	Object.defineProperty(Element.prototype, 'clientHeight', {
		configurable: true,
		get(this: Element): number {
			return this.hasAttribute('data-test-view') ? 600 : 0;
		}
	});
	restore = () => {
		g.ResizeObserver = roBefore;
		Object.defineProperty(Element.prototype, 'getBoundingClientRect', rectBefore);
		Object.defineProperty(Element.prototype, 'clientHeight', heightBefore);
	};
});

afterEach(() => {
	if (app) unmount(app);
	app = null;
	restore?.();
	FakeResizeObserver.live.clear();
	document.body.innerHTML = '';
});

const ITEMS = [
	{ key: 'a', kind: 'row' as const },
	{ key: 'b', kind: 'note' as const },
	{ key: 'c', kind: 'row' as const }
];

function host(ontextwidth?: (w: number) => void): void {
	app = mount(MeasuredList, { target: document.body, props: { items: ITEMS, ontextwidth } });
	flushSync();
}

/** Each rendered item's height, and the list's. */
function layout(): { items: number[]; total: number } {
	const items = [...document.querySelectorAll<HTMLElement>('.vl-item')].map((el) => parseFloat(el.style.height));
	return { items, total: parseFloat(document.querySelector<HTMLElement>('.vl')!.style.height) };
}

function sample(kind: string): HTMLElement {
	return document.querySelector<HTMLElement>(`.vl-samples [data-kind="${kind}"]`)!.closest<HTMLElement>('.vl-sample')!;
}

describe('measured row heights', () => {
	it('keeps the fallback heights while the samples have no box', () => {
		host();
		expect(layout()).toEqual({ items: [60, 78, 60], total: 198 });
		// The samples are drawn, hidden and out of the list's items.
		const samples = document.querySelector('.vl-samples')!;
		expect(samples.getAttribute('aria-hidden')).toBe('true');
		expect(samples.hasAttribute('inert')).toBe(true);
		expect(samples.querySelectorAll('.vl-sample')).toHaveLength(2);
		expect(document.querySelectorAll('[role=listitem]')).toHaveLength(3);
	});

	it('uses each sample\'s height, rounded up to whole px, plus the gap', () => {
		sampleHeight = { row: 56.79, note: 74.73 };
		host();
		expect(layout()).toEqual({ items: [59, 77, 59], total: 195 });
	});

	it('measures again when a sample resizes, as text scaling does', () => {
		sampleHeight = { row: 56.79, note: 74.73 };
		host();
		sampleHeight = { row: 114.8, note: 125.49 };
		FakeResizeObserver.resized(sample('row'));
		flushSync();
		expect(layout()).toEqual({ items: [117, 77, 117], total: 311 });
		FakeResizeObserver.resized(sample('note'));
		flushSync();
		expect(layout()).toEqual({ items: [117, 128, 117], total: 362 });
	});

	it('stops watching the samples when the list goes', () => {
		host();
		expect(FakeResizeObserver.live.size).toBeGreaterThan(0);
		unmount(app!);
		app = null;
		expect(FakeResizeObserver.live.size).toBe(0);
	});
});

describe('the text-width probe', () => {
	it('reports the list width over the text scale, again when the text changes', () => {
		const widths: number[] = [];
		probeHeight = 100;
		host((w) => widths.push(w));
		expect(widths.at(-1)).toBe(LIST_WIDTH);
		probeHeight = 200;
		FakeResizeObserver.resized(document.querySelector('.vl-probe')!);
		expect(widths.at(-1)).toBe(LIST_WIDTH / 2);
	});

	it('reports nothing without a layout, and is not drawn unless asked for', () => {
		const widths: number[] = [];
		host((w) => widths.push(w));
		expect(widths).toEqual([]);
		unmount(app!);
		app = null;
		host();
		expect(document.querySelector('.vl-probe')).toBeNull();
	});
});
