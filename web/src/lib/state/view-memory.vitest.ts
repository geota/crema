import { afterEach, describe, expect, it } from 'vitest';
import {
	captureScroll,
	flushViewMemory,
	readScroll,
	recallView,
	rememberView,
	resetViewMemory,
	resolveScroll,
	writeScroll
} from './view-memory';

/** jsdom has no layout: give an element a fixed client rect. */
function place(el: Element, top: number, height: number): void {
	el.getBoundingClientRect = () =>
		({ top, bottom: top + height, height, left: 0, right: 100, width: 100, x: 0, y: top }) as DOMRect;
}

/**
 * A 400px-tall scroller (client top 100) holding `keys` as 100px rows, laid
 * out as if scrolled by `scrollTop`.
 */
function list(keys: string[], scrollTop: number): HTMLElement {
	const box = document.createElement('div');
	place(box, 100, 400);
	keys.forEach((k, i) => {
		const row = document.createElement('div');
		row.dataset.scrollKey = k;
		place(row, 100 + i * 100 - scrollTop, 100);
		box.appendChild(row);
	});
	box.scrollTop = scrollTop;
	document.body.appendChild(box);
	return box;
}

const KEYS = Array.from({ length: 20 }, (_, i) => `bean:${i}`);

afterEach(() => {
	document.body.innerHTML = '';
	resetViewMemory();
});

describe('view values', () => {
	it('falls back to the defaults, then returns what was remembered', () => {
		expect(recallView('beans', { status: 'all', q: '' })).toEqual({ status: 'all', q: '' });
		rememberView('beans', { status: 'archived', q: 'kenya' });
		expect(recallView('beans', { status: 'all', q: '' })).toEqual({ status: 'archived', q: 'kenya' });
	});

	it('ignores unknown keys and values of the wrong type', () => {
		rememberView('history', { range: 30, tags: 'x', stale: true, selectedId: 'shot:1' });
		expect(
			recallView<{ range: string; tags: string[]; selectedId: string | null }>('history', {
				range: 'all',
				tags: [],
				selectedId: null
			})
		).toEqual({ range: 'all', tags: [], selectedId: 'shot:1' });
	});

	it('survives a reload through sessionStorage', () => {
		rememberView('profiles', { tag: 'light' });
		writeScroll('profiles|light', { top: 640, anchors: [{ key: 'p:3', offset: -12 }] });
		flushViewMemory();
		resetViewMemory({ keepStorage: true });
		expect(recallView('profiles', { tag: 'all' })).toEqual({ tag: 'light' });
		expect(readScroll('profiles|light')).toEqual({ top: 640, anchors: [{ key: 'p:3', offset: -12 }] });
	});
});

describe('scroll snapshots', () => {
	it('captures the visible rows, top first, with their offsets', () => {
		const box = list(KEYS, 750);
		const snap = captureScroll(box);
		expect(snap.top).toBe(750);
		// Rows 7..11 intersect the 400px viewport (row 7 is half scrolled off).
		expect(snap.anchors.map((a) => a.key)).toEqual(['bean:7', 'bean:8', 'bean:9', 'bean:10', 'bean:11']);
		expect(snap.anchors[0].offset).toBe(-50);
	});

	it('restores to the same row after the list changed above it', () => {
		const snap = captureScroll(list(KEYS, 750));
		document.body.innerHTML = '';
		// Two new rows were added at the top: bean:7 is now row 9.
		const box = list(['new:a', 'new:b', ...KEYS], 0);
		expect(resolveScroll(box, snap)).toEqual({ top: 950, anchored: true });
	});

	it('lands next to an anchor that was removed (archived from its detail)', () => {
		const snap = captureScroll(list(KEYS, 750));
		document.body.innerHTML = '';
		const box = list(
			KEYS.filter((k) => k !== 'bean:7'),
			0
		);
		// bean:8 (the next visible row, offset 50) is now row 7 → 700 - 50.
		expect(resolveScroll(box, snap)).toEqual({ top: 650, anchored: true });
	});

	it('falls back to the raw offset when no anchor is left', () => {
		const snap = captureScroll(list(KEYS, 750));
		document.body.innerHTML = '';
		const box = list(['other:1', 'other:2'], 0);
		expect(resolveScroll(box, snap)).toEqual({ top: 750, anchored: false });
	});

	it('captures window scrolling against the viewport', () => {
		KEYS.slice(0, 3).forEach((k, i) => {
			const row = document.createElement('div');
			row.dataset.scrollKey = k;
			place(row, i === 0 ? -200 : i * 300, 280);
			document.body.appendChild(row);
		});
		const snap = captureScroll(window);
		expect(snap.anchors.map((a) => a.key)).toEqual(['bean:0', 'bean:1', 'bean:2']);
		expect(snap.anchors[0].offset).toBe(-200);
	});
});
