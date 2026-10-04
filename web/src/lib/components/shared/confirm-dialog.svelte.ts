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
 * `await choiceDialog({ message, primaryLabel, secondaryLabel })` is the
 * three-way sibling: `'primary'` / `'secondary'` for the two buttons, `null`
 * when dismissed (Escape / scrim) — for a question where "neither" must stay
 * distinct from the second answer (e.g. the catalogue clash prompt: Keep mine
 * / Use catalogue / dismiss = apply nothing).
 *
 * One dialog at a time (modal). If a second is requested while one is open the
 * first resolves as cancelled, so its awaiter never hangs. The host traps Tab
 * inside the panel, focuses the primary button (or the input) on open and
 * restores focus to the opener on close.
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
	/**
	 * Type-to-confirm gate (GitHub delete-repo pattern): the confirm button
	 * stays disabled until the user types this exact string. For the
	 * highest-blast-radius actions (erase-all); plain confirms stay frictionless.
	 */
	requireTyped?: string;
}

/** Prompt-only extras. */
export interface PromptOptions extends ConfirmOptions {
	/** Input placeholder. */
	placeholder?: string;
	/** Pre-filled input value. */
	initialValue?: string;
}

/** A two-answer dialog where dismissing is a third, separate outcome. */
export interface ChoiceOptions {
	/** Optional bold heading above the message. */
	title?: string;
	/** The body text. */
	message: string;
	/** The default / safe answer — the filled button, focused on open. */
	primaryLabel: string;
	/** The other answer — the outlined button. */
	secondaryLabel: string;
}

/** Which {@link choiceDialog} button was pressed. */
export type DialogChoice = 'primary' | 'secondary';

/** The currently-open dialog, or `null`. Read by {@link ConfirmDialog}. */
export interface ActiveDialog {
	readonly kind: 'confirm' | 'prompt' | 'choice';
	/** For a choice: `confirmLabel` = primary, `cancelLabel` = secondary. */
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
		prior.resolve(prior.kind === 'confirm' ? false : null);
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

/**
 * Show a two-answer dialog. Resolves `'primary'` / `'secondary'` for the
 * buttons, or `null` when dismissed (Escape / scrim click).
 */
export function choiceDialog(options: ChoiceOptions): Promise<DialogChoice | null> {
	preempt();
	return new Promise<DialogChoice | null>((resolve) => {
		active = {
			kind: 'choice',
			options: {
				title: options.title,
				message: options.message,
				confirmLabel: options.primaryLabel,
				cancelLabel: options.secondaryLabel
			},
			resolve: (v) => resolve(v === 'primary' || v === 'secondary' ? v : null)
		};
	});
}

/** Resolve + close the active dialog. Called by {@link ConfirmDialog}. */
export function resolveActive(value: boolean | string | null): void {
	const a = active;
	active = null;
	a?.resolve(value);
}
