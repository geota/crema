/**
 * `#lib/bean/move-shot-bag.js` — moving a logged shot between bags.
 *
 * The grounds came from the bag the user now names, so the debit moves with the
 * attribution. The core plans it (`shot_bag_move`, REVIEW-FINAL-2 MF1): the old
 * bag gets back only what the row took, the new bag is debited where a recorded
 * shot would be (a replay never debits), and the row records what it took. The
 * shell only writes the plan. Android's `setShotBean` runs the same plan.
 */

import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { AnyShot } from '#lib/history/model.js';
import { promptBagEmpty } from './bag-empty-prompt';
import { getBeanStore } from './store.svelte';

export function moveShotBag(shot: AnyShot, newBeanId: string | null): void {
	const plan = getBeanStore().moveShotDebit({
		fromBagId: shot.bean?.beanId ?? null,
		toBagId: newBeanId,
		bagDebit: shot.bagDebit ?? null,
		doseG: shot.metadata?.dose ?? null,
		replay: shot.replay === true
	});
	if (!plan.moves) return;
	getHistoryStore().setBagDebit(shot.id, plan.bagDebit ?? null);
	if (plan.toEmptied && newBeanId) void promptBagEmpty(newBeanId);
}
