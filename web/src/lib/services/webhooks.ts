/**
 * `$lib/services/webhooks` — outgoing webhook fan-out (docs/53 §1.3, §2.4
 * PR 3.7, T-15).
 *
 * The Effect-native home for `state/app.svelte.ts`'s `fireWebhook` +
 * `sendTestWebhook`. Consumes `HttpClient`.
 *
 *  - `fire(eventType, payload)` — gated on the user's webhook settings
 *    (enabled + non-empty URL + the per-event toggle), fire-and-forget. The
 *    error channel is `never`: webhooks have no retry and a failure is just an
 *    `Effect.logError` line (the observability story), never surfaced to the
 *    user. Delivered `mode: 'no-cors'` with a `text/plain` JSON body so it
 *    reaches receivers without CORS headers — the response is opaque, which
 *    `HttpClient` treats as a successful dispatch.
 *  - `sendTest(url)` — the Advanced → Webhooks "Send test" button. Tries a
 *    real CORS request first (so it can report an HTTP status), falling back to
 *    the opaque `no-cors` delivery when CORS is blocked. Always succeeds at the
 *    Effect level (returns a `{ ok, message }` outcome).
 *
 * `WebhooksLive` is the production implementation, composed into `AppLayer`;
 * `app.svelte.ts` calls `Webhooks.fire()` / `Webhooks.sendTest()`. Reads the
 * settings store (`$lib/settings`), so not node:test-able.
 */

import { Context, Effect, Layer } from 'effect';
import { HttpClient } from './http-client.ts';
import type { HttpStatusError, NetworkError } from '../effect/errors.ts';
import { getSettingsStore } from '$lib/settings';

/** Matches today's `AbortSignal.timeout(5000)` on every webhook fetch. */
const TIMEOUT_MS = 5000;

/** The outcome the Settings "Send test" button surfaces inline. */
export interface WebhookTestResult {
	readonly ok: boolean;
	readonly message: string;
}

function describe(e: NetworkError | HttpStatusError): string {
	if (e._tag === 'HttpStatusError') return `HTTP ${e.status}`;
	return e.cause instanceof Error ? e.cause.message : String(e.cause);
}

/**
 * SEC2: the same allow-list the Settings UI enforces (`webhookUrlValid` in
 * `AdvancedSection.svelte`) — `https://…`, or `http://localhost` / `127.0.0.1`
 * / `[::1]` for dev. `fire()` / `sendTest()` re-check it so a URL that slipped
 * past the UI (an import, a hand-edited localStorage row) can't make the shell
 * POST to an arbitrary scheme.
 */
function isAllowedWebhookUrl(url: string): boolean {
	return (
		url.startsWith('https://') ||
		url.startsWith('http://localhost') ||
		url.startsWith('http://127.0.0.1') ||
		url.startsWith('http://[::1]')
	);
}

export class Webhooks extends Context.Tag('crema/Webhooks')<
	Webhooks,
	{
		/**
		 * Fire the webhook for `eventType` with `payload`, if the user has it
		 * enabled for this event. Fire-and-forget: failures are logged, never
		 * raised (`never` error channel).
		 */
		readonly fire: (eventType: string, payload: object) => Effect.Effect<void>;
		/** Send a one-shot test delivery to `url`, reporting a readable outcome. */
		readonly sendTest: (url: string) => Effect.Effect<WebhookTestResult>;
	}
>() {}

export const WebhooksLive = Layer.effect(
	Webhooks,
	Effect.gen(function* () {
		const http = yield* HttpClient;

		const fire = (eventType: string, payload: object): Effect.Effect<void> =>
			Effect.gen(function* () {
				const prefs = getSettingsStore().current;
				if (!prefs.webhookEnabled) return;
				const url = prefs.webhookUrl.trim();
				if (url.length === 0) return;
				if (!isAllowedWebhookUrl(url)) return; // SEC2: enforce the scheme at the send boundary
				const enabled = prefs.webhookEvents[eventType as keyof typeof prefs.webhookEvents];
				if (!enabled) return;
				const body = JSON.stringify({ type: eventType, payload, timestamp: Date.now() });
				yield* http
					.request({
						url,
						method: 'POST',
						mode: 'no-cors',
						headers: { 'Content-Type': 'text/plain' },
						body,
						timeoutMs: TIMEOUT_MS
					})
					.pipe(
						Effect.asVoid,
						// No retry — a failed webhook is the user's endpoint's problem; a
						// log line (BLE debug panel, once wired) is the whole story.
						Effect.catchAll((cause) =>
							Effect.logError(`webhook ${eventType} failed: ${describe(cause)}`)
						)
					);
			});

		const sendTest = (url: string): Effect.Effect<WebhookTestResult> =>
			Effect.gen(function* () {
				const trimmed = url.trim();
				if (trimmed.length === 0) {
					return { ok: false, message: 'No URL configured.' };
				}
				if (!isAllowedWebhookUrl(trimmed)) {
					return { ok: false, message: 'Webhook URL must be https:// (or http://localhost for dev).' };
				}
				const body = JSON.stringify({
					type: 'test',
					payload: { message: 'Hello from Crema' },
					timestamp: Date.now()
				});
				// 1) Real CORS request — reports an actual HTTP status when the
				//    endpoint supports CORS.
				const cors = yield* Effect.either(
					http.request({
						url: trimmed,
						method: 'POST',
						headers: { 'Content-Type': 'application/json' },
						body,
						timeoutMs: TIMEOUT_MS
					})
				);
				if (cors._tag === 'Right') {
					return { ok: true, message: `Sent (HTTP ${cors.right.status})` };
				}
				// A non-2xx response is a real, readable status — report it, no retry.
				if (cors.left._tag === 'HttpStatusError') {
					return { ok: false, message: `HTTP ${cors.left.status}` };
				}
				// 2) CORS blocked (preflight failed → the POST never left) — retry
				//    opaque, exactly how `fire` delivers live events.
				const opaque = yield* Effect.either(
					http.request({
						url: trimmed,
						method: 'POST',
						mode: 'no-cors',
						headers: { 'Content-Type': 'text/plain' },
						body,
						timeoutMs: TIMEOUT_MS
					})
				);
				if (opaque._tag === 'Right') {
					return {
						ok: true,
						message: 'Sent — opaque response; delivery not confirmable (endpoint lacks CORS)'
					};
				}
				return { ok: false, message: describe(opaque.left) };
			});

		return Webhooks.of({ fire, sendTest });
	})
);
