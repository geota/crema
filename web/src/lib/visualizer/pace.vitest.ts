import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Effect, Layer } from 'effect';
import {
	INLINE_WAIT_MAX_MS,
	PACE_KEY,
	coolingDown,
	noteRateLimited,
	setPacingForTest,
	setWaitForTest,
	takeSlot
} from './pace.ts';
import { visualizerCall } from '../services/visualizer-call.ts';
import { HttpClient, type HttpRequest } from '../services/http-client.ts';
import { TokenVault } from '../services/token-vault.ts';
import { HttpStatusError } from '../effect/errors.ts';

const NOW = 1_700_000_000_000;

beforeEach(() => {
	localStorage.clear();
	setPacingForTest(true);
});
afterEach(() => {
	setPacingForTest(false);
	setWaitForTest(null);
});

describe('the pacer', () => {
	it('gives a burst of 30, then one slot every 4 s', () => {
		const waits = Array.from({ length: 32 }, () => takeSlot(true, NOW));
		expect(waits.slice(0, 30).every((w) => w === 0)).toBe(true);
		expect(waits[30]).toBe(4_000);
		expect(waits[31]).toBeGreaterThan(0);
	});

	it('a call outside a run won\'t wait out a cooldown: it is refused, and takes no slot', () => {
		noteRateLimited(600_000, NOW);
		expect(takeSlot(false, NOW)).toBeNull();
		expect(takeSlot(true, NOW)).toBe(600_000);
		expect(INLINE_WAIT_MAX_MS).toBeLessThan(600_000);
	});

	it('a cooldown survives a relaunch, and the bucket is stored every tenth request', () => {
		for (let i = 0; i < 10; i++) takeSlot(true, NOW);
		expect(localStorage.getItem(PACE_KEY)).not.toBeNull();
		noteRateLimited(60_000, NOW);
		setPacingForTest(true); // a relaunch: the bucket comes from storage
		expect(coolingDown(NOW + 59_000)).toBe(true);
		expect(coolingDown(NOW + 61_000)).toBe(false);
	});
});

describe('visualizerCall paces every request', () => {
	const vault = Layer.succeed(
		TokenVault,
		TokenVault.of({
			getTokens: Effect.succeed(null),
			storeTokens: () => Effect.void,
			clearTokens: Effect.void,
			withFreshToken: ((req: (t: string) => Effect.Effect<unknown, unknown>) => req('tok')) as never,
			changes: undefined as never
		})
	);
	const callWith = (reply: (req: HttpRequest) => Effect.Effect<Response, HttpStatusError>, sent: HttpRequest[]) =>
		Effect.runPromiseExit(
			visualizerCall('/me').pipe(
				Effect.provide(
					Layer.merge(
						vault,
						Layer.succeed(
							HttpClient,
							HttpClient.of({
								request: (req) => {
									sent.push(req);
									return reply(req);
								}
							})
						)
					)
				)
			)
		);

	it('a 429 sets the cooldown, and the next call is refused unsent', async () => {
		const sent: HttpRequest[] = [];
		await callWith((req) => Effect.fail(new HttpStatusError({ status: 429, url: req.url, body: '{}' })), sent);
		expect(coolingDown()).toBe(true);
		const exit = await callWith(() => Effect.succeed(new Response('{}')), sent);
		expect(exit._tag).toBe('Failure');
		expect(sent).toHaveLength(1);
	});

	it('waits its slot out before sending', async () => {
		const slept: number[] = [];
		setWaitForTest((ms) => Effect.sync(() => void slept.push(ms)));
		for (let i = 0; i < 30; i++) takeSlot(true);
		await callWith(() => Effect.succeed(new Response('{}')), []);
		expect(slept).toHaveLength(1);
		expect(slept[0]).toBeGreaterThan(0);
	});
});
