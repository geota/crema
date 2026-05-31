/**
 * `$lib/services/webhooks.vitest` — vitest for the `Webhooks` service (docs/53
 * T-15). Previously verified only by the docs/55 "webhook Send test + a live
 * event to a webhook.site URL" manual step; `webhooks.ts` reads `$lib/settings`
 * so it's unreachable under node:test.
 *
 * Covers: the `fire` settings gate (enabled + non-empty URL + per-event toggle),
 * the no-cors / text-plain delivery shape, fire's never-fail posture (a transport
 * error becomes a log line, not a rejection), and `sendTest`'s CORS-first →
 * opaque-fallback ladder. Run: `pnpm test:vitest`.
 */

import { Effect, Layer } from 'effect';
import { beforeEach, describe, expect, it, vi } from 'vitest';

// Controllable settings (vi.hoisted so the mock factory can close over it).
const state = vi.hoisted(() => ({
	prefs: {
		webhookEnabled: true,
		webhookUrl: 'https://hook.example/x',
		webhookEvents: { shotCompleted: true } as Record<string, boolean>
	}
}));
vi.mock('$lib/settings', () => ({ getSettingsStore: () => ({ current: state.prefs }) }));

import { Webhooks, WebhooksLive } from './webhooks.ts';
import { HttpClient, type HttpRequest } from './http-client.ts';
import { HttpStatusError, NetworkError } from '../effect/errors.ts';

type Reply = { ok: true; status?: number } | { ok: false; status: number } | { network: true };

function mkHttp(replies: Reply[]) {
	let i = 0;
	const calls: HttpRequest[] = [];
	const layer = Layer.succeed(
		HttpClient,
		HttpClient.of({
			request: (req) => {
				calls.push(req);
				const r = replies[i++] ?? { ok: true as const };
				if ('network' in r) return Effect.fail(new NetworkError({ cause: new Error('offline'), url: req.url }));
				if (!r.ok) return Effect.fail(new HttpStatusError({ status: r.status, url: req.url }));
				return Effect.succeed(new Response(null, { status: r.status ?? 200 }));
			}
		})
	);
	return { layer, calls };
}

const run = <A, E>(program: Effect.Effect<A, E, Webhooks>, http: Layer.Layer<HttpClient>) =>
	Effect.runPromise(Effect.provide(program, Layer.provide(WebhooksLive, http)));

beforeEach(() => {
	state.prefs = {
		webhookEnabled: true,
		webhookUrl: 'https://hook.example/x',
		webhookEvents: { shotCompleted: true }
	};
});

describe('Webhooks.fire — gating', () => {
	it('delivers no-cors text/plain when enabled for the event', async () => {
		const { layer, calls } = mkHttp([{ ok: true }]);
		await run(Webhooks.pipe(Effect.flatMap((w) => w.fire('shotCompleted', { a: 1 }))), layer);
		expect(calls).toHaveLength(1);
		expect(calls[0].method).toBe('POST');
		expect(calls[0].mode).toBe('no-cors');
		expect(calls[0].headers?.['Content-Type']).toBe('text/plain');
		const sent = JSON.parse(calls[0].body as string);
		expect(sent.type).toBe('shotCompleted');
		expect(sent.payload).toEqual({ a: 1 });
	});

	it('does nothing when webhooks are disabled', async () => {
		state.prefs.webhookEnabled = false;
		const { layer, calls } = mkHttp([{ ok: true }]);
		await run(Webhooks.pipe(Effect.flatMap((w) => w.fire('shotCompleted', {}))), layer);
		expect(calls).toHaveLength(0);
	});

	it('does nothing when the URL is empty', async () => {
		state.prefs.webhookUrl = '   ';
		const { layer, calls } = mkHttp([{ ok: true }]);
		await run(Webhooks.pipe(Effect.flatMap((w) => w.fire('shotCompleted', {}))), layer);
		expect(calls).toHaveLength(0);
	});

	it('does nothing when the per-event toggle is off', async () => {
		state.prefs.webhookEvents = { shotCompleted: false };
		const { layer, calls } = mkHttp([{ ok: true }]);
		await run(Webhooks.pipe(Effect.flatMap((w) => w.fire('shotCompleted', {}))), layer);
		expect(calls).toHaveLength(0);
	});

	it('never rejects when delivery fails (logs instead)', async () => {
		const { layer, calls } = mkHttp([{ network: true }]);
		// Resolves to void despite the transport error — fire's channel is `never`.
		await expect(
			run(Webhooks.pipe(Effect.flatMap((w) => w.fire('shotCompleted', {}))), layer)
		).resolves.toBeUndefined();
		expect(calls).toHaveLength(1);
	});
});

describe('Webhooks.sendTest — CORS then opaque fallback', () => {
	it('reports the HTTP status on a CORS success', async () => {
		const { layer, calls } = mkHttp([{ ok: true, status: 200 }]);
		const r = await run(Webhooks.pipe(Effect.flatMap((w) => w.sendTest('https://h/x'))), layer);
		expect(r.ok).toBe(true);
		expect(r.message).toContain('HTTP 200');
		expect(calls).toHaveLength(1); // no opaque retry needed
	});

	it('reports a non-2xx CORS status without retrying', async () => {
		const { layer, calls } = mkHttp([{ ok: false, status: 404 }]);
		const r = await run(Webhooks.pipe(Effect.flatMap((w) => w.sendTest('https://h/x'))), layer);
		expect(r.ok).toBe(false);
		expect(r.message).toContain('HTTP 404');
		expect(calls).toHaveLength(1);
	});

	it('falls back to an opaque no-cors delivery when CORS is blocked', async () => {
		// First (CORS) request fails with a transport error → opaque retry succeeds.
		const { layer, calls } = mkHttp([{ network: true }, { ok: true }]);
		const r = await run(Webhooks.pipe(Effect.flatMap((w) => w.sendTest('https://h/x'))), layer);
		expect(r.ok).toBe(true);
		expect(r.message).toContain('opaque');
		expect(calls).toHaveLength(2);
		expect(calls[1].mode).toBe('no-cors');
	});

	it('reports failure when both CORS and opaque delivery fail', async () => {
		const { layer } = mkHttp([{ network: true }, { network: true }]);
		const r = await run(Webhooks.pipe(Effect.flatMap((w) => w.sendTest('https://h/x'))), layer);
		expect(r.ok).toBe(false);
	});

	it('reports "No URL" for an empty url without any request', async () => {
		const { layer, calls } = mkHttp([]);
		const r = await run(Webhooks.pipe(Effect.flatMap((w) => w.sendTest('   '))), layer);
		expect(r.ok).toBe(false);
		expect(calls).toHaveLength(0);
	});
});
