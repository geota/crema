/**
 * `#lib/bean/bag-empty-prompt.js` — the bean run-out UX.
 *
 * Fired when a pulled shot debits a bag to empty (`beanStore.debitBean` said so):
 * offer to archive the finished bag (and drop it as the active bag so the next
 * shot doesn't bill an empty one), then — if the bag is still unrated — nudge a
 * rating. Called fire-and-forget from the shot-side effect; never throws.
 *
 * A shot can start from the machine's own button while this is open, and the
 * dialog would sit over Stop, so {@link closeBagEmptyPrompt} ends it. Brew's
 * banner still says the bag is empty.
 */
import {
	confirmDialog,
	dismissConfirm,
	type ConfirmOptions
} from '#lib/components/shared/confirm-dialog.svelte.js';
import { goto } from '$app/navigation';
import { resolve } from '$app/paths';
import { getBeanStore } from './store.svelte';

/** The question on screen, `null` when none or once it's been closed. */
let asking: ConfirmOptions | null = null;

/** Ask `options`; `null` when {@link closeBagEmptyPrompt} ended the prompt. */
async function ask(options: ConfirmOptions): Promise<boolean | null> {
	asking = options;
	const answer = await confirmDialog(options);
	if (asking !== options) return null;
	asking = null;
	return answer;
}

/** End the prompt, leaving the bag as it is. A no-op with none open. */
export function closeBagEmptyPrompt(): void {
	const options = asking;
	asking = null;
	if (options) dismissConfirm(options);
}

export async function promptBagEmpty(beanId: string): Promise<void> {
	const store = getBeanStore();
	const bean = store.getBean(beanId);
	if (!bean) return;

	const archive = await ask({
		title: 'Bag empty',
		message: `That was the last of ${bean.name}. Archive this bag?`,
		confirmLabel: 'Archive',
		cancelLabel: 'Keep'
	});
	if (archive === null) return;
	if (archive) {
		store.updateBean(beanId, { archivedAt: Date.now() });
		// Drop it as the active bag so the next shot isn't debited against an
		// empty, archived one — the user picks a fresh bag when they open it.
		if (store.activeBeanId === beanId) store.setActiveBean(null);
	}

	// Nudge a rating while the bag is fresh in memory, only if still unrated.
	if (bean.rating === 0) {
		const rate = await ask({
			title: 'Rate this bag?',
			message: `You haven't rated ${bean.name} yet — how was it?`,
			confirmLabel: 'Rate it',
			cancelLabel: 'Later'
		});
		if (rate) await goto(resolve('beans'));
	}
}
