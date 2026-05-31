/**
 * `$lib/components/shared/confirm-dialog` — a promise-based in-app replacement
 * for native `confirm()` / `prompt()` (FD4).
 *
 * `await confirmDialog({ message })` resolves `true` / `false`;
 * `await promptDialog({ message })` resolves the typed string or `null`. A
 * single {@link ConfirmDialog} host (mounted once in the root layout) renders
 * the active request as a real in-app modal (scrim + `role="dialog"` +
 * `aria-modal` + Escape / scrim cancel), so a call site only swaps
 * `if (confirm(msg))` → `if (await confirmDialog({ message: msg }))`.
 *
 * One dialog at a time (modal). If a second is requested while one is open the
 * first resolves as cancelled, so its awaiter never hangs.
 *
 * (Focus-trap / focus-restore is the deferred half of FD4 — this matches the
 * existing `MainsConfirmModal`, which also doesn't trap focus yet.)
 */

/** Shared options for a confirm or prompt dialog. */
export interface ConfirmOptions {
	/** Optional bold heading above the message. */
	title?: string;
	/** The body text. */
	message: string;
	/** Confirm-button label (default `"Confirm"`, or `"OK"` for a prompt). */
	confirmLabel?: string;
	/** Cancel-button label (default `"Cancel"`). */
	cancelLabel?: string;
	/** Render the confirm button as destructive (`st-btn-danger`). */
	danger?: boolean;
}

/** Prompt-only extras. */
export interface PromptOptions extends ConfirmOptions {
	/** Input placeholder. */
	placeholder?: string;
	/** Pre-filled input value. */
	initialValue?: string;
}

/** The currently-open dialog, or `null`. Read by {@link ConfirmDialog}. */
export interface ActiveDialog {
	readonly kind: 'confirm' | 'prompt';
	readonly options: ConfirmOptions & Partial<PromptOptions>;
	readonly resolve: (value: boolean | string | null) => void;
}

let active = $state<ActiveDialog | null>(null);

/** The live active-dialog handle — call from a reactive context to track it. */
export function getActiveDialog(): ActiveDialog | null {
	return active;
}

/** Cancel any open dialog so a replacement never strands the prior awaiter. */
function preempt(): void {
	const prior = active;
	if (prior) {
		active = null;
		prior.resolve(prior.kind === 'prompt' ? null : false);
	}
}

/**
 * Show a confirm dialog. Resolves `true` if the user confirms, `false` on
 * cancel / Escape / scrim click. The async sibling of native `confirm()`.
 */
export function confirmDialog(options: ConfirmOptions): Promise<boolean> {
	preempt();
	return new Promise<boolean>((resolve) => {
		active = { kind: 'confirm', options, resolve: (v) => resolve(v === true) };
	});
}

/**
 * Show a prompt dialog. Resolves the trimmed-by-the-caller typed string, or
 * `null` on cancel. The async sibling of native `prompt()`.
 */
export function promptDialog(options: PromptOptions): Promise<string | null> {
	preempt();
	return new Promise<string | null>((resolve) => {
		active = {
			kind: 'prompt',
			options,
			resolve: (v) => resolve(typeof v === 'string' ? v : null)
		};
	});
}

/** Resolve + close the active dialog. Called by {@link ConfirmDialog}. */
export function resolveActive(value: boolean | string | null): void {
	const a = active;
	active = null;
	a?.resolve(value);
}
