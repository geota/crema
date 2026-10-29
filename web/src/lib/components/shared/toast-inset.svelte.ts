/**
 * `#lib/components/shared/toast-inset` — keeps the toast stack off the
 * controls that must stay reachable (final cohort review 07, new finding 1).
 *
 * The toast host is fixed to the bottom-right corner, which on Brew is where
 * the Coffee button, Flush and the other foot actions live. A pinned toast
 * (the update offer, the engine-reset notice) sat on the start button until
 * dismissed. A component that owns such a band attaches {@link keepToastsAbove}
 * to it; the host then lifts its stack above the band's top edge, whatever the
 * band's height at the current width (the Brew foot is about 100 px on a
 * landscape tablet and about 200 px at phone width).
 *
 * Runes-in-module, like the toast queue: the inset is module-level `$state`,
 * read through a getter so the host tracks it.
 */
import { untrack } from 'svelte';

/** The live clearance per registered band, in px from the viewport bottom. */
let insets = $state<Record<number, number>>({});
let nextToken = 0;

/**
 * How far above the viewport bottom the toast stack must start so it clears a
 * band whose top edge is at `bandTop` (viewport px). A band scrolled out of
 * view (top at or below the viewport bottom) needs no clearance; one taller
 * than the viewport is capped at the viewport height.
 */
export function clearanceAbove(bandTop: number, viewportHeight: number): number {
	if (!Number.isFinite(bandTop) || !Number.isFinite(viewportHeight)) return 0;
	return Math.round(Math.min(viewportHeight, Math.max(0, viewportHeight - bandTop)));
}

/** The clearance the toast host must leave, in px (0 when nothing registered). */
export function getToastBottomInset(): number {
	let max = 0;
	for (const v of Object.values(insets)) if (v > max) max = v;
	return max;
}

/**
 * Attachment: `<div {@attach keepToastsAbove}>`. While the element is mounted
 * the toast stack sits above its top edge; re-measured when the element or
 * the viewport resizes, or the page scrolls (a sticky band can move).
 */
export function keepToastsAbove(node: HTMLElement): () => void {
	const token = nextToken++;
	let last: number | null = null;
	const measure = (): void => {
		const next = clearanceAbove(node.getBoundingClientRect().top, window.innerHeight);
		if (next === last) return;
		last = next;
		// `untrack`: an attachment runs inside an effect, and reading `insets`
		// while writing it would make that effect re-run itself forever.
		untrack(() => {
			insets = { ...insets, [token]: next };
		});
	};
	measure();
	const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null;
	ro?.observe(node);
	window.addEventListener('resize', measure);
	// Capture: the Brew dashboard scrolls inside its own container, not the window.
	window.addEventListener('scroll', measure, { capture: true, passive: true });
	return () => {
		ro?.disconnect();
		window.removeEventListener('resize', measure);
		window.removeEventListener('scroll', measure, { capture: true });
		untrack(() => {
			const rest = { ...insets };
			delete rest[token];
			insets = rest;
		});
	};
}
