/**
 * `#lib/services/visualizer-sign-in.js` — what happens once the OAuth code
 * exchange has produced a token set: persist it, then refresh the cached
 * Visualizer Premium flag (the bean-sync sentinel probe, `BeanSync.refreshPremium`)
 * so the edit sync knows the new account's tier straight away instead of
 * treating it as free until the next daily check / Test.
 *
 * Run by the `/auth/visualizer/callback` route on its short-lived runtime.
 * The premium refresh never fails; only `storeTokens` can, with
 * `SecretBoxUnavailableError` when the tokens could not be stored encrypted.
 * The caller then shows that error and revokes the just-issued grant.
 */

import { Effect } from 'effect';
import { BeanSync } from './bean-sync.ts';
import { OAuth } from './oauth.ts';
import { TokenVault } from './token-vault.ts';
import type { TokenSet } from '../visualizer/oauth.ts';

export const completeVisualizerSignIn = (tokens: TokenSet) =>
	Effect.gen(function* () {
		const vault = yield* TokenVault;
		const oauth = yield* OAuth;
		// Not stored (final review 05 N-6): revoke the grant just issued, so no
		// live session exists that this device can neither use nor sign out
		// of, then fail so the callback page shows why.
		yield* vault
			.storeTokens(tokens)
			.pipe(Effect.tapError(() => oauth.revokeToken(tokens.accessToken)));
		const beans = yield* BeanSync;
		yield* beans.refreshPremium;
	});
