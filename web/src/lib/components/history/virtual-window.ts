/**
 * `virtual-window` — the arithmetic behind `VirtualShotList` (#310 P5w).
 * Every item has a height known from its kind alone, so where an item sits
 * and which items a viewport shows are sums and a binary search. The only
 * thing measured is one hidden sample of each kind (#423), so the heights
 * follow the text size.
 */

/** What the list draws: how many items there are and the `i`-th one. */
export interface VirtualItems<T> {
	readonly count: number;
	at(i: number): T;
}

/** An array as {@link VirtualItems}. */
export function arrayItems<T>(items: readonly T[]): VirtualItems<T> {
	return { count: items.length, at: (i) => items[i] };
}

/** Each item's top edge, and the total height at the end: `count + 1` entries. */
export function offsetsOf(count: number, heightAt: (i: number) => number): Float64Array {
	const out = new Float64Array(count + 1);
	for (let i = 0; i < count; i++) out[i + 1] = out[i] + heightAt(i);
	return out;
}

/** The item whose box holds `y` (clamped to the list). */
export function indexAt(offsets: Float64Array, y: number): number {
	const count = offsets.length - 1;
	if (count <= 0) return 0;
	let lo = 0;
	let hi = count - 1;
	while (lo < hi) {
		const mid = (lo + hi + 1) >> 1;
		if (offsets[mid] <= y) lo = mid;
		else hi = mid - 1;
	}
	return lo;
}

/**
 * The items from `top` to `top + height` (list coordinates), widened by
 * `overscan` pixels each way: `[start, end)`.
 */
export function windowOf(
	offsets: Float64Array,
	top: number,
	height: number,
	overscan: number
): { start: number; end: number } {
	const count = offsets.length - 1;
	if (count <= 0 || height <= 0) return { start: 0, end: Math.min(count, 1) };
	const start = indexAt(offsets, Math.max(0, top - overscan));
	const end = Math.min(count, indexAt(offsets, top + height + overscan) + 1);
	return { start, end };
}

/**
 * How far to scroll so item `[top, bottom)` (list coordinates) is in a
 * viewport now showing `[viewTop, viewTop + viewHeight)`: `null` when it
 * already is; otherwise it's centred.
 */
export function revealDelta(
	top: number,
	bottom: number,
	viewTop: number,
	viewHeight: number
): number | null {
	if (top >= viewTop && bottom <= viewTop + viewHeight) return null;
	return top - viewTop - Math.max(0, (viewHeight - (bottom - top)) / 2);
}

/**
 * A sample's measured box height as an item height: whole px, rounded up so
 * the text fits, plus the gap below. `null` when the sample has no box (not
 * laid out, or no layout at all), so the fallback stays.
 */
export function slotHeight(px: number, gap: number): number | null {
	if (!(px > 0)) return null;
	// The 0.01 keeps float noise (57.000001) from adding a whole pixel.
	return Math.ceil(px - 0.01) + gap;
}

/** Report `node`'s box size now, and again whenever it changes. Returns the stop. */
export function observeSize(node: Element, onSize: (width: number, height: number) => void): () => void {
	const read = (): void => {
		const r = node.getBoundingClientRect();
		onSize(r.width, r.height);
	};
	read();
	if (typeof ResizeObserver === 'undefined') return () => {};
	const ro = new ResizeObserver(read);
	ro.observe(node);
	return () => ro.disconnect();
}

/**
 * Report `node`'s item height ({@link slotHeight}) now, and again whenever
 * its size changes: the text size changing, or a web font arriving. Returns
 * the stop.
 */
export function observeHeight(node: Element, gap: number, onHeight: (px: number) => void): () => void {
	return observeSize(node, (_, height) => {
		const h = slotHeight(height, gap);
		if (h !== null) onHeight(h);
	});
}

/** The font size, in px, of the probe {@link textWidth} reads: its line height, at line-height 1. */
export const TEXT_PROBE_PX = 100;

/**
 * `width` in px as if the text were at its default size: `width` over the
 * text scale, read from the probe's drawn height. Text scaling (Android's,
 * or `text-size-adjust`) grows the glyphs but not `em` lengths, so neither a
 * media nor a container query can see it. `null` without a layout.
 */
export function textWidth(width: number, probeHeight: number): number | null {
	if (!(width > 0) || !(probeHeight > 0)) return null;
	return (width * TEXT_PROBE_PX) / probeHeight;
}
