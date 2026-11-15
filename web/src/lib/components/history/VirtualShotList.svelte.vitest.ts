/**
 * #310 P5w: the windowed History list. At the design size (5,000 rows) the
 * DOM holds only the rows near the viewport; scroll memory, a deep link to a
 * row far down and focus return reach rows that aren't rendered; and the
 * rows say where they sit in the whole list. Each case runs in both layouts:
 * the wide list, which scrolls itself, and the phone page, which scrolls the
 * stats and chips above the list with it.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import ScrollMemoryList from '#lib/testing/ScrollMemoryList.svelte';
import { installListLayout } from '#lib/testing/list-layout.js';
import { resetViewMemory, writeScroll } from '#lib/state/view-memory.js';
import type VirtualShotList from './VirtualShotList.svelte';

const VIEW = 600;
const DESIGN_SIZE = 5_000;

interface Item {
	key: string;
	height: number;
	heading?: boolean;
}
type List = ReturnType<typeof VirtualShotList<Item>>;

let undo: () => void;
beforeAll(() => {
	undo = installListLayout(VIEW);
});
afterAll(() => undo());

let app: Record<string, unknown> | null = null;
beforeEach(() => resetViewMemory());
afterEach(() => {
	if (app) unmount(app);
	app = null;
	document.body.innerHTML = '';
});

/** `n` rows of 60 px, every third with a note (78 px), a heading of 34 px every 50 rows when `headings`. */
function items(n: number, opts: { headings?: boolean; from?: number } = {}): Item[] {
	const out: Item[] = [];
	for (let i = 0; i < n; i++) {
		const k = (opts.from ?? 0) + i;
		if (opts.headings && k % 50 === 0) out.push({ key: `head:${k}`, height: 34, heading: true });
		out.push({ key: `shot:${k}`, height: k % 3 === 0 ? 78 : 60 });
	}
	return out;
}

/** Top of `key`'s row in the list, by the same sums the list does. */
function topOf(list: Item[], key: string): number {
	let y = 0;
	for (const it of list) {
		if (it.key === key) return y;
		y += it.height;
	}
	throw new Error(`no ${key}`);
}

const LAYOUTS = [
	{ name: 'wide', header: 0, headings: false },
	{ name: 'phone', header: 320, headings: true }
] as const;

async function frames(n: number): Promise<void> {
	for (let i = 0; i < n; i++) await new Promise((r) => requestAnimationFrame(() => r(undefined)));
}

function host(props: { items: Item[]; memoryKey: string; header: number }): { scroller: HTMLElement; list: () => List } {
	let list: List | null = null;
	app = mount(ScrollMemoryList, {
		target: document.body,
		props: { ...props, onlist: (l: List) => (list = l) }
	});
	flushSync();
	const scroller = document.querySelector<HTMLElement>('[data-test-scroller]')!;
	return { scroller, list: () => list! };
}

function rendered(): string[] {
	return [...document.querySelectorAll<HTMLElement>('[data-scroll-key]')].map((b) => b.dataset.scrollKey!);
}

/** Where `key`'s row sits below the viewport's top, or null when it isn't rendered. */
function placeOf(key: string): number | null {
	const b = document.querySelector<HTMLElement>(`[data-scroll-key="${key}"]`);
	return b ? b.getBoundingClientRect().top : null;
}

describe.each(LAYOUTS)('$name layout', ({ header, headings }) => {
	it('keeps the DOM bounded at the design size, wherever it is scrolled', async () => {
		const list = items(DESIGN_SIZE, { headings });
		const { scroller } = host({ items: list, memoryKey: 'k', header });
		const counts: number[] = [];
		for (const top of [0, 50_000, 150_000, Number.MAX_SAFE_INTEGER]) {
			scroller.scrollTop = top;
			flushSync();
			counts.push(rendered().length);
		}
		// About a viewport plus 480 px each way, at 60 px a row.
		for (const c of counts) {
			expect(c).toBeGreaterThan(5);
			expect(c).toBeLessThan(40);
		}
		// The end is reachable, and is the last row.
		expect(rendered().at(-1)).toBe(`shot:${DESIGN_SIZE - 1}`);
	});

	it('says where each row sits in the whole list; headings are not list items', async () => {
		const list = items(DESIGN_SIZE, { headings });
		const { scroller } = host({ items: list, memoryKey: 'k', header });
		scroller.scrollTop = topOf(list, 'shot:4321') + header;
		flushSync();
		const row = document.querySelector('[data-scroll-key="shot:4321"]')!.closest('[role=listitem]')!;
		expect(row.getAttribute('aria-setsize')).toBe(String(DESIGN_SIZE));
		expect(row.getAttribute('aria-posinset')).toBe('4322');
		expect(document.querySelector('[role=list]')!.getAttribute('aria-label')).toBe('Shots');
		if (headings) {
			scroller.scrollTop = topOf(list, 'head:4300') + header;
			flushSync();
			const head = document.querySelector('h2')!.closest('.vl-item')!;
			expect(head.getAttribute('role')).toBe('none');
			expect(head.hasAttribute('aria-posinset')).toBe(false);
		}
	});

	it('restores scroll memory to a row that is not rendered', async () => {
		const list = items(DESIGN_SIZE, { headings });
		// Saved earlier: row 2,000 sat 12 px below the viewport top.
		writeScroll('history/list', { top: 1, anchors: [{ key: 'shot:2000', offset: 12 }] });
		const { scroller } = host({ items: list, memoryKey: 'history/list', header });
		await frames(12);
		flushSync();
		expect(scroller.scrollTop).toBe(header + topOf(list, 'shot:2000') - 12);
		expect(placeOf('shot:2000')).toBe(12);
	});

	it('lands on the same row when rows were added above it since', async () => {
		const later = [...items(25, { from: 10_000 }), ...items(DESIGN_SIZE, { headings })];
		writeScroll('history/list', { top: 120_000, anchors: [{ key: 'shot:3000', offset: 40 }] });
		host({ items: later, memoryKey: 'history/list', header });
		await frames(12);
		flushSync();
		expect(placeOf('shot:3000')).toBe(40);
	});

	it('falls through to the next anchor when the first row is gone', async () => {
		const list = items(DESIGN_SIZE, { headings }).filter((i) => i.key !== 'shot:3000');
		writeScroll('history/list', {
			top: 0,
			anchors: [
				{ key: 'shot:3000', offset: 0 },
				{ key: 'shot:3001', offset: 60 }
			]
		});
		host({ items: list, memoryKey: 'history/list', header });
		await frames(12);
		flushSync();
		expect(placeOf('shot:3001')).toBe(60);
	});

	it('brings a row far down into view for a deep link, centred', async () => {
		const list = items(DESIGN_SIZE, { headings });
		const { list: vl } = host({ items: list, memoryKey: 'k', header });
		expect(rendered()).not.toContain('shot:4500');
		expect(vl().reveal('shot:4500')).toBe(true);
		flushSync();
		const at = placeOf('shot:4500');
		expect(at).not.toBeNull();
		expect(at!).toBeGreaterThan(VIEW / 2 - 60);
		expect(at!).toBeLessThan(VIEW / 2);
		// A row already in view stays where it is.
		const before = document.querySelector<HTMLElement>('[data-test-scroller]')!.scrollTop;
		vl().reveal('shot:4501');
		expect(document.querySelector<HTMLElement>('[data-test-scroller]')!.scrollTop).toBe(before);
		expect(vl().reveal('shot:none')).toBe(false);
	});

	it('scrolls a row in before focusing it (focus return), and keeps it while focused', async () => {
		const list = items(DESIGN_SIZE, { headings });
		const { scroller, list: vl } = host({ items: list, memoryKey: 'k', header });
		expect(await vl().focusKey('shot:3999')).toBe(true);
		const focused = document.activeElement as HTMLElement;
		expect(focused.dataset.scrollKey).toBe('shot:3999');
		// Scrolled far away, the focused row stays in the DOM, so focus isn't lost.
		scroller.scrollTop = 0;
		flushSync();
		await tick();
		expect(rendered()).toContain('shot:3999');
		expect(document.activeElement).toBe(focused);
		expect(rendered().length).toBeLessThan(40);
	});
});
