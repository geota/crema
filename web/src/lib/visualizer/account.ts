/**
 * `$lib/visualizer/account` — fetch the authenticated user's profile.
 *
 * Visualizer's OpenAPI exposes `GET /api/me` (under the `Credentials`
 * tag) returning `{ id, name, public, avatar_url }`. The Settings UI
 * uses it to show "Signed in as <name>" once the OAuth dance lands.
 */

import { withFreshToken } from './token-store';

/** Shape of the `/api/me` response per the OpenAPI spec (v1.8.2). */
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
		const body = (await res.json()) as {
			id: string;
			name: string;
			public: boolean;
			avatar_url: string;
		};
		return {
			id: body.id,
			name: body.name,
			public: body.public,
			avatarUrl: body.avatar_url
		};
	});
}
