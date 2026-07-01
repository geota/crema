/**
 * `$lib/bean/bag-empty-prompt` — the bean run-out UX.
 *
 * Fired when a pulled shot debits a bag to empty (`beanStore.debitBean`
 * returned `true`): offer to archive the finished bag (and drop it as the
 * active bag so the next shot doesn't bill an empty one), then — if the bag is
 * still unrated — nudge a rating. Called fire-and-forget from the shot-side
 * effect; never throws.
 */
import { confirmDialog } from '$lib/components/shared/confirm-dialog.svelte';
import { goto } from '$app/navigation';
import { resolve } from '$app/paths';
import { getBeanStore } from './store.svelte';

export async function promptBagEmpty(beanId: string): Promise<void> {
	const store = getBeanStore();
	const bean = store.getBean(beanId);
	if (!bean) return;

	const archive = await confirmDialog({
		title: 'Bag empty',
		message: `That was the last of ${bean.name}. Archive this bag?`,
		confirmLabel: 'Archive',
		cancelLabel: 'Keep'
	});
	if (archive) {
		store.updateBean(beanId, { archivedAt: Date.now() });
		// Drop it as the active bag so the next shot isn't debited against an
		// empty, archived one — the user picks a fresh bag when they open it.
		if (store.activeBeanId === beanId) store.setActiveBean(null);
	}

	// Nudge a rating while the bag is fresh in memory, only if still unrated.
	if (bean.rating === 0) {
		const rate = await confirmDialog({
			title: 'Rate this bag?',
			message: `You haven't rated ${bean.name} yet — how was it?`,
			confirmLabel: 'Rate it',
			cancelLabel: 'Later'
		});
		if (rate) await goto(resolve('/beans'));
	}
}
