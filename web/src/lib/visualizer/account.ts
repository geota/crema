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

import { decodeResponse, VisualizerAccountSchema } from '$lib/effect/schema/visualizer';
import { withFreshToken } from './token-store';

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
		// Validate the body instead of trusting an `as` cast: a malformed /me
		// is a real error (surface it), not a silent {id: undefined, …}.
		const body = decodeResponse(VisualizerAccountSchema, await res.json(), 'GET /me');
		if (!body) {
			throw Object.assign(new Error('Visualizer /me returned an unexpected shape.'), {
				status: res.status
			});
		}
		return {
			id: body.id,
			name: body.name,
			public: body.public,
			avatarUrl: body.avatar_url
		};
	});
}
