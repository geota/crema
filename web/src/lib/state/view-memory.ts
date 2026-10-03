/**
 * `$lib/state/view-memory` — where list pages keep their place (#123).
 *
 * Leaving a list for a detail, an editor or another page and coming back used
 * to reset it: the filters, search and sort were component state (gone when
 * the route unmounts), and the scroll position was either SvelteKit's pixel
 * offset on Back (wrong once the filters reset or the list changed) or nothing
 * at all (the profile editor returns with a fresh navigation).
 *
 * This module is the pure half — framework-free so it is unit-testable:
 *
 *  - **View values** ({@link recallView} / {@link rememberView}): a page's
 *    filter / search / sort snapshot, by namespace.
 *  - **Scroll snapshots** ({@link captureScroll} / {@link resolveScroll}):
 *    where a scroller is, recorded as the visible items' keys
 *    (`data-scroll-key`) and their offsets plus the raw `scrollTop`. Restoring
 *    lands on the same item even when the list changed underneath — an item
 *    archived or deleted from its detail falls through to the next visible
 *    one, so you land next to where it was — and only falls back to the pixel
 *    offset when none of them is still there.
 *
 * Both live in a module-level map (survives route changes for the session)
 * mirrored to `sessionStorage` (survives a reload / the PWA being evicted).
 * The Svelte glue — persisting on change, restoring after navigation — is
 * `$lib/state/use-view-memory.svelte`.
 */

/** One visible item: its key and its top edge relative to the viewport top. */
export interface ScrollAnchor {
	key: string;
	offset: number;
}

/** Where a scroller was. */
export interface ScrollSnapshot {
	/** Raw scroll offset — the fallback when no anchor is still rendered. */
	top: number;
	/** The visible items, top first. */
	anchors: ScrollAnchor[];
}

/** A scroll container: an element, or the window (document scrolling). */
export type Scroller = HTMLElement | Window;

const STORAGE_KEY = 'crema.viewMemory.v1';
/** How many visible items a snapshot keeps as anchors. */
const MAX_ANCHORS = 8;

interface Stored {
	views: Record<string, unknown>;
	scrolls: Record<string, ScrollSnapshot>;
}

let memory: Stored | null = null;
let flushTimer: ReturnType<typeof setTimeout> | null = null;

function load(): Stored {
	if (memory) return memory;
	memory = { views: {}, scrolls: {} };
	try {
		const raw = typeof sessionStorage === 'undefined' ? null : sessionStorage.getItem(STORAGE_KEY);
		if (raw) {
			const parsed = JSON.parse(raw) as Partial<Stored>;
			if (parsed && typeof parsed === 'object') {
				memory.views = parsed.views ?? {};
				memory.scrolls = parsed.scrolls ?? {};
			}
		}
	} catch {
		// Private mode / quota / corrupt JSON — start empty, in-memory only.
	}
	return memory;
}

function scheduleFlush(): void {
	if (typeof sessionStorage === 'undefined' || flushTimer) return;
	flushTimer = setTimeout(flushViewMemory, 250);
}

/** Write the memory to `sessionStorage` now (also called on page hide). */
export function flushViewMemory(): void {
	if (flushTimer) {
		clearTimeout(flushTimer);
		flushTimer = null;
	}
	if (!memory || typeof sessionStorage === 'undefined') return;
	try {
		sessionStorage.setItem(STORAGE_KEY, JSON.stringify(memory));
	} catch {
		// Non-fatal: the in-memory copy still serves this session.
	}
}

/**
 * Test seam: forget the in-memory copy — and the stored one too, unless
 * `keepStorage` (which simulates a reload).
 */
export function resetViewMemory(opts: { keepStorage?: boolean } = {}): void {
	memory = null;
	if (flushTimer) clearTimeout(flushTimer);
	flushTimer = null;
	if (opts.keepStorage) return;
	try {
		sessionStorage?.removeItem(STORAGE_KEY);
	} catch {
		// ignore
	}
}

// ── View values ─────────────────────────────────────────────────────────

/**
 * The page's remembered view values merged over `defaults`. Only keys present
 * in `defaults` are taken, and only when the stored value has the same JSON
 * type (a stale shape from an older build never leaks in).
 */
export function recallView<T extends Record<string, unknown>>(ns: string, defaults: T): T {
	const stored = load().views[ns];
	if (!stored || typeof stored !== 'object') return { ...defaults };
	const out: Record<string, unknown> = { ...defaults };
	for (const [k, def] of Object.entries(defaults)) {
		const v = (stored as Record<string, unknown>)[k];
		if (v === undefined) continue;
		const sameType =
			def === null || v === null
				? true
				: Array.isArray(def)
					? Array.isArray(v)
					: typeof v === typeof def;
		if (sameType) out[k] = v;
	}
	return out as T;
}

/** Remember the page's view values (a plain, JSON-safe snapshot). */
export function rememberView(ns: string, values: Record<string, unknown>): void {
	load().views[ns] = values;
	scheduleFlush();
}

// ── Scroll snapshots ────────────────────────────────────────────────────

export function readScroll(key: string): ScrollSnapshot | null {
	return load().scrolls[key] ?? null;
}

export function writeScroll(key: string, snap: ScrollSnapshot): void {
	load().scrolls[key] = snap;
	scheduleFlush();
}

function isWindow(s: Scroller): s is Window {
	return (s as Window).window === s;
}

/** The scroller's current offset. */
export function scrollTopOf(s: Scroller): number {
	return isWindow(s) ? s.scrollY : s.scrollTop;
}

/** The viewport's top / bottom edges in client coordinates. */
function viewportOf(s: Scroller): { top: number; bottom: number } {
	if (isWindow(s)) return { top: 0, bottom: s.innerHeight };
	const r = s.getBoundingClientRect();
	return { top: r.top, bottom: r.bottom };
}

/** Where to look for keyed items: the element itself, or the whole document. */
function rootOf(s: Scroller): ParentNode {
	return isWindow(s) ? s.document : s;
}

/**
 * Snapshot `scroller`: its offset and the keyed items (`[data-scroll-key]`)
 * currently in view, top first.
 */
export function captureScroll(scroller: Scroller): ScrollSnapshot {
	const view = viewportOf(scroller);
	const anchors: ScrollAnchor[] = [];
	for (const el of rootOf(scroller).querySelectorAll<HTMLElement>('[data-scroll-key]')) {
		const key = el.dataset.scrollKey;
		if (!key) continue;
		const r = el.getBoundingClientRect();
		if (r.bottom <= view.top || r.top >= view.bottom || r.height === 0) continue;
		anchors.push({ key, offset: r.top - view.top });
		if (anchors.length >= MAX_ANCHORS) break;
	}
	return { top: scrollTopOf(scroller), anchors };
}

/** Is any keyed item rendered in `scroller` yet? */
export function hasKeyedItems(scroller: Scroller): boolean {
	return rootOf(scroller).querySelector('[data-scroll-key]') !== null;
}

function findKeyed(root: ParentNode, key: string): HTMLElement | null {
	for (const el of root.querySelectorAll<HTMLElement>('[data-scroll-key]')) {
		if (el.dataset.scrollKey === key) return el;
	}
	return null;
}

/**
 * The scroll offset that puts `snap`'s first still-rendered anchor back where
 * it was, else the raw `top`. `anchored` says which one it is.
 */
export function resolveScroll(
	scroller: Scroller,
	snap: ScrollSnapshot
): { top: number; anchored: boolean } {
	const view = viewportOf(scroller);
	const root = rootOf(scroller);
	const current = scrollTopOf(scroller);
	for (const a of snap.anchors) {
		const el = findKeyed(root, a.key);
		if (!el) continue;
		const r = el.getBoundingClientRect();
		return { top: Math.max(0, current + (r.top - view.top) - a.offset), anchored: true };
	}
	return { top: Math.max(0, snap.top), anchored: false };
}

/** Scroll `scroller` to `top` (the app sets no smooth `scroll-behavior`). */
export function applyScroll(scroller: Scroller, top: number): void {
	if (isWindow(scroller)) scroller.scrollTo(scroller.scrollX, top);
	else scroller.scrollTop = top;
}
