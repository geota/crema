/**
 * `$lib/effect/crema-services.vitest` — the Promise-shaped service facade
 * (architecture review #1). `pnpm check` proves the types line up; this locks
 * the hand-wiring — that each facade method dispatches to the RIGHT service
 * method (a `deleteBean`→`deleteRoaster` transposition typechecks but is wrong)
 * and forwards its arguments — plus the one bit of logic, `isConnected`'s
 * tokens→boolean mapping.
 *
 * Services are spy-backed fakes behind a real `ManagedRuntime`, so the facade's
 * `runtimePromise` boundary is exercised for real.
 */

import { Effect, Layer, ManagedRuntime } from 'effect';
import { describe, expect, it, vi } from 'vitest';
import { createCremaServices } from './crema-services.ts';
import type { AppRuntime } from './runtime.ts';
import { TokenVault } from '../services/token-vault.ts';
import { ShotSync } from '../services/shot-sync.ts';
import { BeanSync } from '../services/bean-sync.ts';
import { UploadQueue } from '../services/upload-queue.ts';
import type { TokenSet } from '$lib/visualizer/oauth';

const aToken = {
	accessToken: 'tok',
	refreshToken: 'rt',
	expiresAt: 0,
	scope: '',
	tokenType: 'Bearer'
} as TokenSet;

const aDrain = { processed: 1, succeeded: 1, dropped: 0, deferred: 0 };

/** A CremaServices facade over a real runtime of spy-backed fake services. */
function harness(tokens: TokenSet | null = aToken) {
	const spy = {
		clearTokens: vi.fn(),
		fetchAccount: vi.fn(),
		testConnection: vi.fn(),
		deleteBean: vi.fn(),
		deleteRoaster: vi.fn(),
		runSync: vi.fn(),
		pullAndReconcile: vi.fn(),
		uploadUnsynced: vi.fn(),
		enqueue: vi.fn(),
		drain: vi.fn()
	};
	const tokenVault = Layer.succeed(TokenVault, {
		getTokens: Effect.succeed(tokens),
		clearTokens: Effect.sync(() => spy.clearTokens())
	} as never);
	const shotSync = Layer.succeed(ShotSync, {
		pullAndReconcileShots: (h: unknown, since: number, opts: unknown) =>
			Effect.sync(() => {
				spy.pullAndReconcile(h, since, opts);
				return { pulled: 2, truncated: false };
			}),
		uploadUnsyncedShots: (h: unknown) => Effect.sync(() => spy.uploadUnsynced(h))
	} as never);
	const beanSync = Layer.succeed(BeanSync, {
		fetchAccount: Effect.sync(() => {
			spy.fetchAccount();
			return { id: 'u', name: 'n', public: true, avatarUrl: '' };
		}),
		testConnection: Effect.sync(() => {
			spy.testConnection();
			return { ok: true, premium: true };
		}),
		deleteBean: (id: string) => Effect.sync(() => spy.deleteBean(id)),
		deleteRoaster: (id: string) => Effect.sync(() => spy.deleteRoaster(id)),
		runSync: (lib: unknown) => Effect.sync(() => spy.runSync(lib))
	} as never);
	const uploadQueue = Layer.succeed(UploadQueue, {
		enqueue: (input: unknown) => Effect.sync(() => spy.enqueue(input)),
		drain: Effect.sync(() => {
			spy.drain();
			return aDrain;
		})
	} as never);
	const runtime = ManagedRuntime.make(
		Layer.mergeAll(tokenVault, shotSync, beanSync, uploadQueue)
	) as unknown as AppRuntime;
	return { services: createCremaServices(runtime), spy };
}

describe('createCremaServices — tokens', () => {
	it('isConnected maps a present token set to true', async () => {
		expect(await harness(aToken).services.tokens.isConnected()).toBe(true);
	});
	it('isConnected maps a null token set to false', async () => {
		expect(await harness(null).services.tokens.isConnected()).toBe(false);
	});
	it('getTokens passes the stored set through', async () => {
		expect(await harness(aToken).services.tokens.getTokens()).toBe(aToken);
	});
	it('clearTokens dispatches to TokenVault.clearTokens', async () => {
		const { services, spy } = harness();
		await services.tokens.clearTokens();
		expect(spy.clearTokens).toHaveBeenCalledTimes(1);
	});
});

describe('createCremaServices — beans (no method transposition)', () => {
	it('deleteBean hits BeanSync.deleteBean (not deleteRoaster) with the id', async () => {
		const { services, spy } = harness();
		await services.beans.deleteBean('bag-1');
		expect(spy.deleteBean).toHaveBeenCalledWith('bag-1');
		expect(spy.deleteRoaster).not.toHaveBeenCalled();
	});
	it('deleteRoaster hits BeanSync.deleteRoaster (not deleteBean) with the id', async () => {
		const { services, spy } = harness();
		await services.beans.deleteRoaster('roaster-1');
		expect(spy.deleteRoaster).toHaveBeenCalledWith('roaster-1');
		expect(spy.deleteBean).not.toHaveBeenCalled();
	});
	it('runSync forwards the library and fetchAccount/testConnection dispatch', async () => {
		const { services, spy } = harness();
		const lib = { tag: 'lib' } as never;
		await services.beans.runSync(lib);
		expect(spy.runSync).toHaveBeenCalledWith(lib);
		await services.beans.fetchAccount();
		await services.beans.testConnection();
		expect(spy.fetchAccount).toHaveBeenCalledTimes(1);
		expect(spy.testConnection).toHaveBeenCalledTimes(1);
	});
});

describe('createCremaServices — shots + queue', () => {
	it('pullAndReconcile forwards history, cursor and opts and returns the result', async () => {
		const { services, spy } = harness();
		const h = { tag: 'history' } as never;
		const opts = { itemsPerPage: 50 } as never;
		const out = await services.shots.pullAndReconcile(h, 1234, opts);
		expect(spy.pullAndReconcile).toHaveBeenCalledWith(h, 1234, opts);
		expect(out).toEqual({ pulled: 2, truncated: false });
	});
	it('uploadUnsynced forwards the history store', async () => {
		const { services, spy } = harness();
		const h = { tag: 'history' } as never;
		await services.shots.uploadUnsynced(h);
		expect(spy.uploadUnsynced).toHaveBeenCalledWith(h);
	});
	it('queue.enqueue forwards the input; queue.drain returns the DrainResult', async () => {
		const { services, spy } = harness();
		const input = { entity: 'shot', id: 's1', op: 'delete', visualizerId: 'v' } as never;
		await services.queue.enqueue(input);
		expect(spy.enqueue).toHaveBeenCalledWith(input);
		expect(await services.queue.drain()).toEqual(aDrain);
	});
});
