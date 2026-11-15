<script lang="ts" generics="T">
	/**
	 * `VirtualShotList` — the History list, windowed (#310 P5w, SPEC
	 * decision 5). Only the items in and near the viewport are in the DOM, so
	 * the DOM row count stays bounded at any history size.
	 *
	 * Every item's height comes from its kind (`heightOf`), not from
	 * measuring each item, so where an item sits is arithmetic: scroll memory,
	 * deep links and focus return can reach an item that isn't rendered. The
	 * caller learns each kind's height from `samples`, one item of each kind
	 * drawn hidden and measured, again whenever its size changes (#423: text
	 * scaling grows the rows). One component
	 * takes either scroller: an element of its own (the wide list) or one it
	 * sits in further down (the phone page).
	 *
	 * The input is {@link VirtualItems} (`count` and `at(i)`), so a source
	 * that pages its rows can stand in for an array.
	 */
	import { tick, type Snippet } from 'svelte';
	import {
		observeHeight,
		observeSize,
		offsetsOf,
		revealDelta,
		TEXT_PROBE_PX,
		textWidth,
		windowOf,
		type VirtualItems
	} from './virtual-window';

	let {
		items,
		keyOf,
		heightOf,
		scroller,
		label,
		setSize,
		positionOf,
		item,
		gap = 0,
		overscan = 480,
		samples = [],
		onmeasure,
		ontextwidth
	}: {
		items: VirtualItems<T>;
		/** A key unique in the list; it is also the item's `data-vl-key`. */
		keyOf: (item: T) => string;
		/** The item's height in px, gap included: fixed per kind and layout. */
		heightOf: (item: T) => number;
		/** The element that scrolls the list, or the window. */
		scroller: () => HTMLElement | Window | null | undefined;
		/** The list's accessible name. */
		label: string;
		/** How many items count as list items (headings don't). */
		setSize: number;
		/** The item's 1-based place among the list items, or `null` for one that isn't (a heading). */
		positionOf: (item: T, index: number) => number | null;
		item: Snippet<[T, number]>;
		/** Space below each item, inside its height. */
		gap?: number;
		/** How far beyond the viewport to render, each way, in px. */
		overscan?: number;
		/**
		 * Items drawn hidden and out of the layout, at the list's width, by
		 * `item` (with index -1), to be measured: one of each kind.
		 */
		samples?: readonly T[];
		/** A sample's height, gap included, in whole px: on mount and when it changes. */
		onmeasure?: (sample: T, height: number) => void;
		/**
		 * The list's width as if its text were at the default size (its width
		 * over the text scale): on mount and when either changes. Rows can pick
		 * a layout by it, which no `em` query can do under text scaling.
		 */
		ontextwidth?: (width: number) => void;
	} = $props();

	let el = $state<HTMLElement>();
	/** The viewport's top, in list coordinates, and its height. */
	let viewTop = $state(0);
	let viewHeight = $state(0);
	/** The item holding focus: kept rendered when it scrolls out, so focus isn't lost. */
	let focusedKey = $state<string | null>(null);

	const offsets = $derived(offsetsOf(items.count, (i) => heightOf(items.at(i))));
	/** Each key's index, built when a key has to be found. */
	const keyIndex = $derived.by(() => {
		const m = new Map<string, number>();
		for (let i = 0; i < items.count; i++) m.set(keyOf(items.at(i)), i);
		return m;
	});
	const range = $derived(windowOf(offsets, viewTop, viewHeight, overscan));
	const visible = $derived.by(() => {
		const out: number[] = [];
		for (let i = range.start; i < range.end; i++) out.push(i);
		const kept = focusedKey === null ? undefined : keyIndex.get(focusedKey);
		if (kept !== undefined && (kept < range.start || kept >= range.end)) {
			if (kept < range.start) out.unshift(kept);
			else out.push(kept);
		}
		return out;
	});

	function isWindow(s: HTMLElement | Window): s is Window {
		return (s as Window).window === s;
	}

	/** Read where the viewport is over the list. */
	function measure(): void {
		const s = scroller();
		if (!s || !el) return;
		const listTop = el.getBoundingClientRect().top;
		if (isWindow(s)) {
			viewTop = -listTop;
			viewHeight = s.innerHeight;
		} else {
			viewTop = s.getBoundingClientRect().top + s.clientTop - listTop;
			viewHeight = s.clientHeight;
		}
	}

	$effect(() => {
		const s = scroller();
		if (!s || !el) return;
		measure();
		const onScroll = (): void => measure();
		let raf = 0;
		const later = (): void => {
			if (raf) return;
			raf = requestAnimationFrame(() => {
				raf = 0;
				measure();
			});
		};
		s.addEventListener('scroll', onScroll, { passive: true });
		window.addEventListener('resize', later);
		const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(later);
		ro?.observe(el);
		if (!isWindow(s)) ro?.observe(s);
		return () => {
			s.removeEventListener('scroll', onScroll);
			window.removeEventListener('resize', later);
			ro?.disconnect();
			if (raf) cancelAnimationFrame(raf);
		};
	});

	// Items above the list can change its place (a banner, the search field).
	$effect(() => {
		void offsets;
		measure();
	});

	function scrollTopOf(s: HTMLElement | Window): number {
		return isWindow(s) ? s.scrollY : s.scrollTop;
	}

	function scrollTo(s: HTMLElement | Window, top: number): void {
		if (isWindow(s)) s.scrollTo(s.scrollX, top);
		else s.scrollTop = top;
	}

	/** The index of the item with `key`, or -1. */
	export function indexOfKey(key: string): number {
		return keyIndex.get(key) ?? -1;
	}

	/**
	 * The scroller offset that puts `key`'s top at the viewport's top, or
	 * `null` when the list doesn't hold it: scroll memory's way to an item
	 * that isn't rendered.
	 */
	export function scrollTopForKey(key: string): number | null {
		const s = scroller();
		const i = keyIndex.get(key);
		if (!s || i === undefined) return null;
		measure();
		return scrollTopOf(s) + offsets[i] - viewTop;
	}

	/** Scroll `key` into view, centred, unless it is in view already. False when the list doesn't hold it. */
	export function reveal(key: string): boolean {
		const s = scroller();
		const i = keyIndex.get(key);
		if (!s || i === undefined) return false;
		measure();
		const delta = revealDelta(offsets[i], offsets[i + 1], viewTop, viewHeight);
		if (delta !== null) {
			scrollTo(s, Math.max(0, scrollTopOf(s) + delta));
			measure();
		}
		return true;
	}

	/** Scroll `key` in, then focus it (without scrolling again). False when it isn't there. */
	export async function focusKey(key: string): Promise<boolean> {
		if (!reveal(key)) return false;
		await tick();
		const target = slotOf(key)?.querySelector<HTMLElement>('button, a[href], [tabindex]');
		if (!target) return false;
		target.focus({ preventScroll: true });
		return document.activeElement === target;
	}

	function slotOf(key: string): HTMLElement | null {
		if (!el) return null;
		for (const node of el.children) {
			if (node instanceof HTMLElement && node.dataset.vlKey === key) return node;
		}
		return null;
	}

	function onfocusin(e: FocusEvent): void {
		const slot = (e.target as HTMLElement | null)?.closest<HTMLElement>('[data-vl-key]');
		focusedKey = slot?.dataset.vlKey ?? null;
	}

	function onfocusout(e: FocusEvent): void {
		const next = e.relatedTarget as Node | null;
		if (!next || !el?.contains(next)) focusedKey = null;
	}
</script>

<div
	bind:this={el}
	class="vl"
	role="list"
	aria-label={label}
	style:height="{offsets[items.count]}px"
	{onfocusin}
	{onfocusout}
>
	{#each visible as i (keyOf(items.at(i)))}
		{@const it = items.at(i)}
		{@const pos = positionOf(it, i)}
		<div
			class="vl-item"
			data-vl-key={keyOf(it)}
			role={pos === null ? 'none' : 'listitem'}
			aria-setsize={pos === null ? undefined : setSize}
			aria-posinset={pos ?? undefined}
			style:top="{offsets[i]}px"
			style:height="{offsets[i + 1] - offsets[i]}px"
			style:padding-bottom="{gap}px"
		>
			{@render item(it, i)}
		</div>
	{/each}
	{#if samples.length > 0 || ontextwidth}
		<div class="vl-samples" aria-hidden="true" inert>
			{#if ontextwidth}
				<div
					class="vl-probe"
					style:font-size="{TEXT_PROBE_PX}px"
					{@attach (node) =>
						observeSize(node, (w, h) => {
							const tw = textWidth(w, h);
							if (tw !== null) ontextwidth?.(tw);
						})}
				>
					M
				</div>
			{/if}
			{#each samples as sample (keyOf(sample))}
				<div class="vl-sample" {@attach (node) => observeHeight(node, gap, (h) => onmeasure?.(sample, h))}>
					{@render item(sample, -1)}
				</div>
			{/each}
		</div>
	{/if}
</div>

<style>
	.vl {
		position: relative;
		flex: none;
		width: 100%;
	}
	.vl-item {
		position: absolute;
		left: 0;
		right: 0;
		display: grid;
		/* The list's width, not the row's content: a long name ellipsizes
		   rather than widening the row past the screen. */
		grid-template-columns: minmax(0, 1fr);
		box-sizing: border-box;
	}
	/* Laid out at the list's width, but adds nothing to its height or scroll. */
	.vl-samples {
		position: absolute;
		top: 0;
		left: 0;
		right: 0;
		height: 0;
		overflow: hidden;
		visibility: hidden;
		pointer-events: none;
	}
	/* As an item lays out its row, minus the fixed height and the gap. */
	.vl-sample {
		display: grid;
		grid-template-columns: minmax(0, 1fr);
	}
	/* One line of text whose height is its font size times the text scale. */
	.vl-probe {
		line-height: 1;
		white-space: nowrap;
	}
</style>
