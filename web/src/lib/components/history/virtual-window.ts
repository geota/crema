/**
 * `virtual-window` — the arithmetic behind `VirtualShotList` (#310 P5w).
 * Every item has a height known from its kind alone, so where an item sits
 * and which items a viewport shows are sums and a binary search, with
 * nothing measured.
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
