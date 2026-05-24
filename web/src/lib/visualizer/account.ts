/**
 * `$lib/visualizer/account` — fetch the authenticated user's profile.
 *
 * Visualizer's OpenAPI exposes `GET /me` (under the `Credentials` tag)
 * returning `{ id, name, public, avatar_url }`. The Settings UI uses it
 * to show "Signed in as <name>" once the OAuth dance lands.
 *
 * Note: the spec confirms `/me` exposes NO premium flag. We rely on the
 * 403-on-write probe from `$lib/bean/visualizer-sync` to detect free
 * tier — do not add a `premium` field here.
 */

import type { components } from './openapi';
import { withFreshToken } from './token-store';

/** Spec-typed wire response — regenerate via `pnpm openapi`. */
type MeResponse = components['schemas']['MeResponse'];

/** Crema-side projection of the `/me` response (camel-cased). */
export interface VisualizerAccount {
	id: string;
	name: string;
	public: boolean;
	avatarUrl: string;
}

const API_BASE = 'https://visualizer.coffee/api';

/**
 * Fetch the signed-in user's account record. Throws on any non-2xx —
 * callers should treat the error as "show the disconnect / sign-in
 * again" affordance.
 */
export async function fetchAccount(): Promise<VisualizerAccount> {
	return withFreshToken(async (token) => {
		const res = await fetch(`${API_BASE}/me`, {
			headers: {
				Authorization: `Bearer ${token}`,
				Accept: 'application/json'
			}
		});
		if (!res.ok) {
			throw Object.assign(new Error(`HTTP ${res.status}`), {
				status: res.status
			});
		}
		const body = (await res.json()) as MeResponse;
		return {
			id: body.id,
			name: body.name,
			public: body.public,
			avatarUrl: body.avatar_url
		};
	});
}
