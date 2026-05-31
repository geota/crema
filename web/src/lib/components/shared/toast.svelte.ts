/**
 * `$lib/components/shared/toast` — a tiny app-wide toast/snackbar store.
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
}

let nextId = 0;
let items = $state<Toast[]>([]);

/** The live toast queue — call from a reactive context to track it. */
export function getToasts(): readonly Toast[] {
	return items;
}

/** Remove the toast with `id` (the close button + the auto-dismiss timer). */
export function dismissToast(id: number): void {
	items = items.filter((t) => t.id !== id);
}

function show(message: string, kind: ToastKind, durationMs: number): void {
	const id = nextId++;
	items = [...items, { id, kind, message }];
	// `durationMs <= 0` pins the toast until dismissed manually.
	if (durationMs > 0 && typeof setTimeout !== 'undefined') {
		setTimeout(() => dismissToast(id), durationMs);
	}
}

/**
 * Enqueue a toast. Errors linger a touch longer (and read as `role="alert"`),
 * since they often carry something the user needs to act on; info / success
 * are advisory and clear sooner.
 */
export const toast = {
	info: (message: string, durationMs = 4000): void => show(message, 'info', durationMs),
	success: (message: string, durationMs = 4000): void => show(message, 'success', durationMs),
	error: (message: string, durationMs = 7000): void => show(message, 'error', durationMs)
};
