/**
 * `#lib/security/credential-status.js` — are any stored sign-ins on this
 * device NOT wrapped by the secret box? Drives the Settings → Sharing notice
 * (review 05 P3-6: the plaintext fallback must be visible, not silent).
 */

import { isWrapped } from './secret-box';

const VISUALIZER_TOKENS_KEY = 'crema.visualizer.tokens.v1';
const DECENT_ACCOUNT_KEY = 'crema.decent.v1';

function field(key: string, name: string): string | null {
	try {
		const raw = typeof localStorage === 'undefined' ? null : localStorage.getItem(key);
		if (!raw) return null;
		const v = (JSON.parse(raw) as Record<string, unknown> | null)?.[name];
		return typeof v === 'string' && v.length > 0 ? v : null;
	} catch {
		return null;
	}
}

/** True when a stored Visualizer or Decent token sits in localStorage as plaintext. */
export function credentialsStoredUnencrypted(): boolean {
	return [field(VISUALIZER_TOKENS_KEY, 'accessToken'), field(DECENT_ACCOUNT_KEY, 'token')].some(
		(v) => v !== null && !isWrapped(v)
	);
}
