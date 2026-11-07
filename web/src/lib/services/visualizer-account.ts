/**
 * `#lib/services/visualizer-account.js` — which Visualizer account a session is,
 * and what that means for what this device holds.
 *
 * The cursor, marks and queue belong to one account (`syncAccountId`, which
 * outlives a sign-out). After a sign-in, `/me` must answer before anything goes
 * up or comes down, and the drain keeps asking until it does. A different
 * account starts the cursor over and asks before uploading the shots already
 * here. Android follows the same rule in `VisualizerSync`; the core decides
 * (`account_change_action`).
 */

import { Effect } from 'effect';
import { BeanSync } from './bean-sync.ts';
import { settleQueueForAccount } from './queue-store.ts';
import { accountChangeAction as wasmAccountChangeAction } from '#lib/wasm/de1_wasm.js';
import { clearBaristaPending } from '../visualizer/barista-pending.ts';
import { readSyncConfig, updateSyncConfig } from '../visualizer/sync-config.ts';

/**
 * Settle what `/me` naming `id` means. A different account resets the cursor,
 * drops the pending marks and what was queued for the old account, keeps its
 * remote deletes for if it comes back, and holds uploads for the user's answer.
 */
export function applyAccount(id: string): void {
	const config = readSyncConfig();
	const change = wasmAccountChangeAction(config.syncAccountId ?? undefined, id);
	if (change === 'Same' && !config.accountUnconfirmed) return;
	if (change !== 'Changed') {
		updateSyncConfig({ syncAccountId: id, accountUnconfirmed: false });
		return;
	}
	clearBaristaPending();
	settleQueueForAccount(config.syncAccountId, id);
	updateSyncConfig({
		syncAccountId: id,
		accountUnconfirmed: false,
		accountChangeAsk: true,
		uploadExcludedBefore: null,
		shotPullCursor: null
	});
}

/**
 * The user's answer to "upload your shots to this different account?". No, or
 * dismissing it, keeps the shots that exist now off the account. New ones flow,
 * so uploads can't stay held for good.
 */
export function answerAccountChange(upload: boolean): void {
	updateSyncConfig({
		accountChangeAsk: false,
		uploadExcludedBefore: upload ? null : Date.now()
	});
}

/**
 * A different account with nothing to upload has nothing to ask. Series that never
 * loaded say nothing about the backlog, so the ask stands.
 */
export async function settleEmptyAccountChange(history: {
	seriesReady(): Promise<boolean>;
	readonly visualizerBacklog: readonly unknown[];
}): Promise<void> {
	const loaded = await history.seriesReady();
	if (loaded && readSyncConfig().accountChangeAsk && history.visualizerBacklog.length === 0) {
		answerAccountChange(true);
	}
}

/**
 * Ask `/me` who this session is. `false` when it didn't answer or named no one:
 * any hold stays, and the next drain asks again.
 */
export const confirmAccount: Effect.Effect<boolean, never, BeanSync> = Effect.gen(function* () {
	const beans = yield* BeanSync;
	const account = yield* beans.fetchAccount.pipe(
		Effect.map((a): { id: string } | null => a),
		Effect.catch(() => Effect.succeed(null))
	);
	if (!account?.id) return false;
	applyAccount(account.id);
	return true;
});
