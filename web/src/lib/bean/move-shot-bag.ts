/**
 * `#lib/bean/move-shot-bag.js` — moving a logged shot between bags.
 *
 * The grounds came from the bag the user now names, so the debit moves with the
 * attribution: the old bag gets back what the row took, the new bag is debited,
 * and the row records what it took this time. Android's `setShotBean` is the
 * same flow.
 */

import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import { promptBagEmpty } from './bag-empty-prompt';
import { getBeanStore } from './store.svelte';

export function moveShotBag(shot: StoredShot, newBeanId: string | null): void {
	const oldBeanId = shot.bean?.beanId ?? null;
	if (oldBeanId === newBeanId) return;
	const library = getBeanStore();
	const dose = shot.metadata?.dose ?? 0;
	if (oldBeanId) library.creditShotDebit(oldBeanId, shot.bagDebit ?? null, dose);
	if (!newBeanId) {
		getHistoryStore().setBagDebit(shot.id, null);
		return;
	}
	const debit = library.debitBean(newBeanId, dose);
	getHistoryStore().setBagDebit(shot.id, debit.taken);
	if (debit.emptied) void promptBagEmpty(newBeanId);
}
