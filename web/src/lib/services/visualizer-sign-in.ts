/**
 * `$lib/services/visualizer-sign-in` — what happens once the OAuth code
 * exchange has produced a token set: persist it, then refresh the cached
 * Visualizer Premium flag (the bean-sync sentinel probe, `BeanSync.refreshPremium`)
 * so the edit sync knows the new account's tier straight away instead of
 * treating it as free until the next daily check / Test.
 *
 * Run by the `/auth/visualizer/callback` route on its short-lived runtime.
 * The premium refresh never fails; only `storeTokens` can.
 */

import { Effect } from 'effect';
import { BeanSync } from './bean-sync.ts';
import { TokenVault } from './token-vault.ts';
import type { TokenSet } from '../visualizer/oauth.ts';

export const completeVisualizerSignIn = (tokens: TokenSet) =>
	Effect.gen(function* () {
		const vault = yield* TokenVault;
		yield* vault.storeTokens(tokens);
		const beans = yield* BeanSync;
		yield* beans.refreshPremium;
	});
