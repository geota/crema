/**
 * `$lib/visualizer` — OAuth 2.0 + PKCE plumbing for Crema ↔ Visualizer.
 *
 * Three layers:
 *   - {@link ./oauth}        — pure flow / crypto / endpoint constants
 *   - {@link ./token-store}  — localStorage persistence + `withFreshToken`
 *   - {@link ./account}      — the `/api/me` helper
 *
 * The bean-sync code in `$lib/bean/visualizer-sync` consumes
 * `withFreshToken` and stays unaware of the redirect dance.
 */

export {
	AUTHORIZE_URL,
	TOKEN_URL,
	REVOKE_URL,
	OAUTH_SCOPES,
	OAUTH_SCOPE,
	clientId,
	isConfigured,
	redirectUri,
	startVisualizerLogin,
	exchangeCodeForToken,
	refreshAccessToken,
	revokeToken,
	takeReturnPath,
	generateCodeVerifier,
	codeChallengeFromVerifier,
	randomState,
	type TokenSet,
	type StartLoginOptions,
	type ExchangeCodeOptions
} from './oauth';

export {
	getStoredTokens,
	storeTokens,
	clearTokens,
	isConnected,
	onTokenChange,
	getFreshAccessToken,
	withFreshToken,
	NotAuthenticatedError
} from './token-store';

export { fetchAccount, type VisualizerAccount } from './account';

export { migrateLegacyBasicAuth } from './migrate-basic-auth';
