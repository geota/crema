/**
 * `#lib/components/shared/toast` — a tiny app-wide toast/snackbar store.
 *
 * The in-app replacement for `alert()` on async-error / info paths (FD4): a
 * native `alert()` blocks the main thread, can't be styled, and reads
 * out-of-place in a PWA. `toast.error(msg)` / `.info(msg)` / `.success(msg)`
 * enqueue a transient notice that {@link ToastHost} (mounted once in the root
 * layout) renders bottom-right and auto-dismisses.
 *
 * Runes-in-module: the queue is module-level `$state`, exposed via a getter so
 * the host tracks it reactively (a `$state` binding can't be exported and stay
 * live across modules — the getter pattern is the Svelte 5 idiom).
 */

/** Visual + a11y severity of a toast. */
export type ToastKind = 'info' | 'success' | 'error';

/** One live toast. */
export interface Toast {
	/** Monotonic id — the `{#each}` key + the dismiss handle. */
	readonly id: number;
	/** Severity, driving colour + `role` (`alert` for errors, else `status`). */
	readonly kind: ToastKind;
	/** The message body. */
	readonly message: string;
	/** Optional action button (e.g. "Undo") — runs, then dismisses. */
	readonly action?: { readonly label: string; readonly run: () => void };
}

let nextId = 0;
let items = $state<Toast[]>([]);
/** Per-toast `onDismiss` hooks, kept out of the reactive queue. */
const dismissHooks = new Map<number, () => void>();

/** The live toast queue — call from a reactive context to track it. */
export function getToasts(): readonly Toast[] {
	return items;
}

/**
 * Remove the toast with `id` (the close button, the action button, the
 * auto-dismiss timer, or the owner withdrawing it). Its `onDismiss` hook runs
 * once, whichever of these removed it; an id already gone is a no-op.
 */
export function dismissToast(id: number): void {
	if (!items.some((t) => t.id === id)) return;
	items = items.filter((t) => t.id !== id);
	const hook = dismissHooks.get(id);
	dismissHooks.delete(id);
	hook?.();
}

/** Extra options for {@link toast.action}. */
export interface ToastOptions {
	/** Runs once when the toast leaves the queue, however it was removed. */
	readonly onDismiss?: () => void;
}

function show(
	message: string,
	kind: ToastKind,
	durationMs: number,
	action?: Toast['action'],
	onDismiss?: () => void
): number {
	const id = nextId++;
	items = [...items, { id, kind, message, ...(action ? { action } : {}) }];
	if (onDismiss) dismissHooks.set(id, onDismiss);
	// `durationMs <= 0` pins the toast until dismissed manually.
	if (durationMs > 0 && typeof setTimeout !== 'undefined') {
		setTimeout(() => dismissToast(id), durationMs);
	}
	return id;
}

/**
 * Enqueue a toast. Errors linger a touch longer (and read as `role="alert"`),
 * since they often carry something the user needs to act on; info / success
 * are advisory and clear sooner.
 */
export const toast = {
	info: (message: string, durationMs = 4000): void => void show(message, 'info', durationMs),
	success: (message: string, durationMs = 4000): void => void show(message, 'success', durationMs),
	error: (message: string, durationMs = 7000): void => void show(message, 'error', durationMs),
	/** A toast with one action button (e.g. "Undo") — info unless `kind`
	 *  says otherwise (an `error` reads as `role="alert"`); lingers longer
	 *  so the user has time to react; the action runs, then dismisses.
	 *  `options.onDismiss` tells the owner when the toast is gone (e.g.
	 *  closed with ×). Returns the toast's id, for {@link dismissToast}. */
	action: (
		message: string,
		label: string,
		run: () => void,
		durationMs = 8000,
		kind: ToastKind = 'info',
		options: ToastOptions = {}
	): number => show(message, kind, durationMs, { label, run }, options.onDismiss)
};
