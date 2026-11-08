/**
 * Which Visualizer account a session is, and what that means for what this
 * device holds. Runs the real sign-in and drain over a fake `/me`, through the
 * core's `account_change_action`.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { Effect, Layer, ManagedRuntime } from 'effect';

const mockHistory = {
	get: vi.fn(),
	bindVisualizerId: vi.fn(),
	seriesReady: vi.fn(async () => {}),
	purgeTombstone: vi.fn()
};
vi.mock('#lib/history/index.js', () => ({ getHistoryStore: () => mockHistory }));

import { HttpStatusError, NetworkError } from '../effect/errors.ts';
import { initTestWasm } from '#lib/testing/test-init.js';
import { BARISTA_PENDING_KEY } from '../visualizer/barista-pending.ts';
import { excludedFromUpload, readSyncConfig, updateSyncConfig, uploadsHeld } from '../visualizer/sync-config.ts';
import {
	expiredTombstones,
	markUnboundTombstone,
	markUploadAttempted,
	parkedTombstones,
	uploadAttempted,
	uploadReturned
} from '../visualizer/upload-marks.ts';
import { BeanSync } from './bean-sync.ts';
import { OAuth } from './oauth.ts';
import { enqueueEntry, readQueue } from './queue-store.ts';
import { ShotSync } from './shot-sync.ts';
import { TokenVault } from './token-vault.ts';
import { UploadQueue, UploadQueueLive } from './upload-queue.ts';
import { answerAccountChange, applyAccount, settleEmptyAccountChange } from './visualizer-account.ts';
import { completeVisualizerSignIn } from './visualizer-sign-in.ts';
import type { TokenSet } from '../visualizer/oauth.ts';

beforeAll(initTestWasm);

const set: TokenSet = {
	accessToken: 'a',
	refreshToken: 'r',
	expiresAt: Date.now() + 3_600_000,
	scope: 'read',
	tokenType: 'Bearer'
};

const account = (id: string) => ({ id, name: 'Ada', public: true, avatar_url: '' });

/** A `BeanSync` whose `/me` answers from `me`, with the probe stubbed out. */
function services(me: () => Effect.Effect<ReturnType<typeof account>, unknown>) {
	const uploads: string[] = [];
	const beans = Layer.succeed(
		BeanSync,
		BeanSync.of({ fetchAccount: Effect.suspend(me), refreshPremium: Effect.succeed(null) } as never)
	);
	const shots = Layer.succeed(
		ShotSync,
		ShotSync.of({
			uploadShot: (shot: { id: string }) => {
				uploads.push(shot.id);
				return Effect.succeed({ visualizerId: 'v' });
			}
		} as never)
	);
	const vault = Layer.succeed(
		TokenVault,
		TokenVault.of({ storeTokens: () => Effect.void } as never)
	);
	const oauth = Layer.succeed(OAuth, OAuth.of({ revokeToken: () => Effect.void } as never));
	const queue = Layer.provide(UploadQueueLive, Layer.merge(beans, shots));
	return { uploads, layer: Layer.mergeAll(beans, shots, vault, oauth, queue) };
}

const signIn = (layer: Layer.Layer<never, never, never>) =>
	Effect.runPromise(completeVisualizerSignIn(set).pipe(Effect.provide(layer as never)) as Effect.Effect<void>);

const drain = (layer: Layer.Layer<never, never, never>) => {
	const runtime = ManagedRuntime.make(layer as never);
	return runtime
		.runPromise(Effect.flatMap(UploadQueue, (q) => q.drain) as Effect.Effect<unknown, never, never>)
		.finally(() => runtime.dispose());
};

beforeEach(() => {
	localStorage.clear();
	mockHistory.get.mockReset();
	mockHistory.get.mockImplementation((id: string) => ({ id, visualizerId: undefined }));
});

describe('a sign-in names its account before anything moves', () => {
	it('the first account is noted, and asks nothing', async () => {
		const { layer } = services(() => Effect.succeed(account('u1')));
		await signIn(layer as never);
		expect(readSyncConfig()).toMatchObject({ syncAccountId: 'u1', accountUnconfirmed: false, accountChangeAsk: false });
		expect(uploadsHeld()).toBe(false);
	});

	it('clears the reconnect banner', async () => {
		updateSyncConfig({ needsReconnect: true, syncAccountId: 'u1' });
		const { layer } = services(() => Effect.succeed(account('u1')));
		await signIn(layer as never);
		expect(readSyncConfig().needsReconnect).toBe(false);
	});

	it('the same account comes back with everything in place', async () => {
		updateSyncConfig({ syncAccountId: 'u1', shotPullCursor: 7 });
		const { layer } = services(() => Effect.succeed(account('u1')));
		await signIn(layer as never);
		expect(readSyncConfig()).toMatchObject({ shotPullCursor: 7, accountChangeAsk: false });
	});

	it('unknown_identity_holds_uploads', async () => {
		updateSyncConfig({ syncAccountId: 'u1' });
		const { layer, uploads } = services(() => Effect.fail(new NetworkError({ cause: 'x', url: '/me' })));
		await signIn(layer as never);
		expect(readSyncConfig().accountUnconfirmed).toBe(true);
		expect(uploadsHeld()).toBe(true);

		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		expect(await drain(layer as never)).toMatchObject({ processed: 0, deferred: 1 });
		expect(uploads).toEqual([]);
	});

	it('a /me with no id is not an answer', async () => {
		const { layer } = services(() => Effect.succeed(account('')));
		await signIn(layer as never);
		expect(readSyncConfig().accountUnconfirmed).toBe(true);
	});

	it('identity_retries_from_drain', async () => {
		updateSyncConfig({ syncAccountId: 'u1', accountUnconfirmed: true });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const { layer, uploads } = services(() => Effect.succeed(account('u1')));
		await drain(layer as never);
		expect(readSyncConfig().accountUnconfirmed).toBe(false);
		expect(uploads).toEqual(['s1']);
	});

	it('a session from before this was recorded gets its id at the drain, with no hold', async () => {
		localStorage.setItem('crema.visualizer.tokens.v1', JSON.stringify(set));
		const { layer } = services(() => Effect.succeed(account('u1')));
		await drain(layer as never);
		expect(readSyncConfig().syncAccountId).toBe('u1');
		expect(uploadsHeld()).toBe(false);
	});

	it('a failed /me for an unrecorded session holds nothing', async () => {
		localStorage.setItem('crema.visualizer.tokens.v1', JSON.stringify(set));
		enqueueEntry({ entity: 'shot', id: 's1', op: 'create' });
		const { layer, uploads } = services(() => Effect.fail(new HttpStatusError({ status: 503, url: '/me' })));
		await drain(layer as never);
		expect(uploads).toEqual(['s1']);
	});
});

describe('a different account', () => {
	it('reconnect_other_account_asks_first', async () => {
		updateSyncConfig({ syncAccountId: 'u1', shotPullCursor: 99 });
		localStorage.setItem(BARISTA_PENDING_KEY, JSON.stringify(['x']));
		enqueueEntry({ entity: 'shot', id: 'waiting', op: 'create' });
		enqueueEntry({ entity: 'shot', id: 'edited', op: 'update' });
		enqueueEntry({ entity: 'shot', id: 'gone', op: 'delete', visualizerId: 'v-gone' });

		const { layer, uploads } = services(() => Effect.succeed(account('u2')));
		await signIn(layer as never);

		const config = readSyncConfig();
		expect(config).toMatchObject({ syncAccountId: 'u2', accountChangeAsk: true, shotPullCursor: null });
		expect(localStorage.getItem(BARISTA_PENDING_KEY)).toBe('[]');
		expect(readQueue().entries.map((e) => [e.id, e.parkedFor])).toEqual([['gone', 'u1']]);

		expect(uploadsHeld()).toBe(true);
		await drain(layer as never);
		expect(uploads).toEqual([]);
	});

	it('parks unbound tombstones, drops attempt marks, and brings them back with their account', () => {
		updateSyncConfig({ syncAccountId: 'u1' });
		markUploadAttempted('gone');
		markUnboundTombstone('gone', true);
		uploadReturned('gone');
		applyAccount('u2');
		expect(uploadAttempted('gone')).toBe(false);
		expect(parkedTombstones().has('gone')).toBe(true);
		expect(expiredTombstones(99, 0)).toEqual([]);

		applyAccount('u1');
		expect(parkedTombstones().has('gone')).toBe(false);
		expect(expiredTombstones(99, 0)).toEqual(['gone']);
	});

	it('a parked delete waits, and runs once its own account is back', async () => {
		updateSyncConfig({ syncAccountId: 'u1' });
		enqueueEntry({ entity: 'shot', id: 'gone', op: 'delete', visualizerId: 'v-gone' });
		applyAccount('u2');
		answerAccountChange(true);
		const deleted: string[] = [];
		const beans = Layer.succeed(BeanSync, BeanSync.of({} as never));
		const shots = Layer.succeed(
			ShotSync,
			ShotSync.of({
				deleteShot: (id: string) => {
					deleted.push(id);
					return Effect.void;
				}
			} as never)
		);
		const queue = Layer.provide(UploadQueueLive, Layer.merge(beans, shots));
		await drain(queue as never);
		expect(deleted).toEqual([]);

		applyAccount('u1');
		answerAccountChange(true);
		expect(readQueue().entries[0]?.parkedFor).toBeNull();
		await drain(queue as never);
		expect(deleted).toEqual(['v-gone']);
	});

	it('decline_upload_lets_new_shots_flow', () => {
		vi.useFakeTimers();
		try {
			vi.setSystemTime(10_000);
			updateSyncConfig({ syncAccountId: 'u1' });
			applyAccount('u2');
			expect(uploadsHeld()).toBe(true);

			answerAccountChange(false);
			expect(uploadsHeld()).toBe(false);
			expect(excludedFromUpload({ completedAt: 9_999 })).toBe(true);
			expect(excludedFromUpload({ completedAt: 10_001 })).toBe(false);
		} finally {
			vi.useRealTimers();
		}
	});

	it('yes keeps nothing back', () => {
		updateSyncConfig({ syncAccountId: 'u1' });
		applyAccount('u2');
		answerAccountChange(true);
		expect(uploadsHeld()).toBe(false);
		expect(excludedFromUpload({ completedAt: 1 })).toBe(false);
	});

	it('with nothing to upload there is nothing to ask', async () => {
		updateSyncConfig({ syncAccountId: 'u1' });
		applyAccount('u2');
		await settleEmptyAccountChange({ seriesReady: async () => true, visualizerBacklog: [] });
		expect(readSyncConfig().accountChangeAsk).toBe(false);
	});

	it('series that never loaded leave the ask standing', async () => {
		updateSyncConfig({ syncAccountId: 'u1' });
		applyAccount('u2');
		await settleEmptyAccountChange({ seriesReady: async () => false, visualizerBacklog: [] });
		expect(readSyncConfig().accountChangeAsk).toBe(true);
		expect(uploadsHeld()).toBe(true);
	});

	it('a later change starts over', () => {
		updateSyncConfig({ syncAccountId: 'u1', uploadExcludedBefore: 5 });
		applyAccount('u2');
		expect(readSyncConfig().uploadExcludedBefore).toBeNull();
	});
});
