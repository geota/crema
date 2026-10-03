/**
 * `$lib/state/use-view-memory.svelte` — the Svelte glue over
 * `$lib/state/view-memory` (#123). Call these during a page's component init.
 *
 *  - {@link persistView}: keep the page's filter / search / sort values in the
 *    view memory as they change; seed the `$state` from `recallView(...)`.
 *  - {@link useScrollMemory}: remember a scroller's place per list identity +
 *    filter and restore it — after every navigation that lands on the page
 *    (SvelteKit's `afterNavigate`, i.e. after its own scroll handling, so a
 *    fresh `goto` back from an editor no longer lands at the top) and whenever
 *    the key changes in place (a filter / tab switch restores that view's
 *    place, or the top for one never visited).
 *
 * Drawers, sheets and dialogs render over the list without unmounting it, so
 * they need nothing; the memory covers the cases where the list does go away
 * (route editors, other pages) or where its content changes underneath.
 */

import { afterNavigate, beforeNavigate } from '$app/navigation';
import { untrack } from 'svelte';
import {
	applyScroll,
	captureScroll,
	flushViewMemory,
	hasKeyedItems,
	readScroll,
	rememberView,
	resolveScroll,
	scrollTopOf,
	writeScroll,
	type Scroller
} from './view-memory';

/** Keep `read()`'s values remembered under `ns` while the page is mounted. */
export function persistView(ns: string, read: () => Record<string, unknown>): void {
	$effect(() => {
		rememberView(ns, $state.snapshot(read()) as Record<string, unknown>);
	});
}

export interface ScrollMemoryOptions {
	/** The list's identity + everything that changes its rows (filters, tab, sort). */
	key: () => string;
	/** The scrolling element; omit when the page scrolls the window. May be unbound at first. */
	container?: () => HTMLElement | null | undefined;
	/** How long a restore waits for the list's rows to render (data still loading). */
	waitMs?: number;
	/**
	 * Remember the raw offset only — for an outer page scroller whose keyed
	 * rows sit inside an inner scroller of their own (History's narrow layout).
	 */
	pixelOnly?: boolean;
}

/** Remember and restore a scroller's place, keyed by list identity + filter. */
export function useScrollMemory(opts: ScrollMemoryOptions): void {
	const waitMs = opts.waitMs ?? 2000;
	/** The key whose rows the scroller shows now. */
	let current: string | null = null;
	/** Saving is off while a restore is in flight (it would record the half-way state). */
	let armed = false;
	let token = 0;

	const scroller = (): Scroller | null =>
		opts.container ? (opts.container() ?? null) : typeof window === 'undefined' ? null : window;

	function save(): void {
		const s = scroller();
		if (!armed || !s || current === null) return;
		// Rows not rendered yet (or already torn down): don't clobber the memory with "top".
		if (!opts.pixelOnly && !hasKeyedItems(s)) return;
		writeScroll(current, opts.pixelOnly ? { top: scrollTopOf(s), anchors: [] } : captureScroll(s));
	}

	/** Frames the target must hold still before a restore counts as settled. */
	const SETTLE_FRAMES = 8;

	/**
	 * Restore `current`'s place; with no memory for it, go to `fallbackTop`
	 * (null = leave as is). Rows can still be arriving (store load) and cards
	 * can still be growing (charts, images) after the first frame, so the
	 * anchor is re-resolved every frame until the target holds still for
	 * {@link SETTLE_FRAMES} frames (or `waitMs` passes). The user scrolling
	 * meanwhile ends it at once: their scroll wins.
	 */
	function restore(fallbackTop: number | null): void {
		const mine = ++token;
		armed = false;
		const started = performance.now();
		let lastTop = -1;
		let still = 0;
		const s0 = scroller();
		const giveUp = (): void => {
			if (mine === token) {
				token++;
				armed = true;
			}
		};
		const target = s0 && s0 !== window ? s0 : window;
		const events = ['wheel', 'touchstart', 'keydown', 'pointerdown'] as const;
		const detach = (): void => {
			for (const e of events) target.removeEventListener(e, giveUp);
		};
		for (const e of events) target.addEventListener(e, giveUp, { passive: true, once: true });
		const finish = (): void => {
			detach();
			armed = true;
		};
		const again = (): void => {
			requestAnimationFrame(step);
		};
		const step = (): void => {
			if (mine !== token) {
				detach();
				return;
			}
			const s = scroller();
			const key = current;
			const waiting = performance.now() - started < waitMs;
			if (!s || key === null) {
				if (waiting) again();
				else finish();
				return;
			}
			const snap = readScroll(key);
			if (!snap) {
				if (fallbackTop !== null) applyScroll(s, fallbackTop);
				finish();
				return;
			}
			// The rows may still be loading (IndexedDB / first render): wait for them.
			if (!opts.pixelOnly && !hasKeyedItems(s) && waiting) {
				again();
				return;
			}
			const { top } = resolveScroll(s, snap);
			applyScroll(s, top);
			const reached = Math.abs(scrollTopOf(s) - top) <= 2;
			still = reached && Math.abs(top - lastTop) <= 1 ? still + 1 : 0;
			lastTop = top;
			if (still < SETTLE_FRAMES && waiting) {
				again();
				return;
			}
			finish();
		};
		step();
	}

	// Track scrolling (rAF-throttled) and flush on page hide.
	$effect(() => {
		const s = scroller();
		if (!s) return;
		let raf = 0;
		const onScroll = (): void => {
			if (raf) return;
			raf = requestAnimationFrame(() => {
				raf = 0;
				save();
			});
		};
		const onHide = (): void => {
			save();
			flushViewMemory();
		};
		s.addEventListener('scroll', onScroll, { passive: true });
		window.addEventListener('pagehide', onHide);
		return () => {
			s.removeEventListener('scroll', onScroll);
			window.removeEventListener('pagehide', onHide);
			if (raf) cancelAnimationFrame(raf);
		};
	});

	// The key: the first one is restored after navigation (below); a change in
	// place (filter / tab / sort) restores that view's own place, or the top.
	$effect(() => {
		const k = opts.key();
		untrack(() => {
			if (current === k) return;
			const first = current === null;
			current = k;
			// An element scroller is ours alone (SvelteKit only manages the
			// window), so it can restore as soon as it mounts.
			if (first) {
				if (opts.container) restore(null);
			} else {
				restore(0);
			}
		});
	});

	afterNavigate(() => restore(null));
	beforeNavigate(() => {
		save();
		flushViewMemory();
	});
}
