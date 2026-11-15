/**
 * #310 P4w: rapid selection shows the right shot. A whole shot loads after
 * its row is picked; a slow load for an earlier pick must never land on a
 * later one's page.
 */
import { describe, expect, it } from 'vitest';
import { LatestShotLoader } from './shot-loader';
import type { StoredShot } from './model';

/** A load that ends when the test says. */
function deferredLoads() {
	const pending = new Map<string, { resolve: (s: StoredShot | undefined) => void; reject: (e: unknown) => void }>();
	const load = (id: string) =>
		new Promise<StoredShot | undefined>((resolve, reject) => {
			pending.set(id, { resolve, reject });
		});
	return { load, pending };
}

const shot = (id: string) => ({ id, record: { duration: 1, samples: [{ elapsed: 0 }] } }) as unknown as StoredShot;

describe('LatestShotLoader', () => {
	it('picks A, B, C fast: only C’s answer is given, whatever order the loads end in', async () => {
		const { load, pending } = deferredLoads();
		const loader = new LatestShotLoader(load);
		const a = loader.request('a');
		const b = loader.request('b');
		const c = loader.request('c');
		pending.get('c')!.resolve(shot('c'));
		pending.get('a')!.resolve(shot('a'));
		pending.get('b')!.reject(new Error('slow and failed'));
		expect(await c).toMatchObject({ kind: 'loaded', id: 'c', shot: { id: 'c' } });
		expect(await a).toEqual({ kind: 'stale', id: 'a' });
		expect(await b).toEqual({ kind: 'stale', id: 'b' });
	});

	it('a view that goes makes its load stale', async () => {
		const { load, pending } = deferredLoads();
		const loader = new LatestShotLoader(load);
		const a = loader.request('a');
		loader.cancel();
		pending.get('a')!.resolve(shot('a'));
		expect(await a).toEqual({ kind: 'stale', id: 'a' });
	});

	it('a failed load is said, so the view shows it and never an empty curve', async () => {
		const loader = new LatestShotLoader(async () => {
			throw new Error('unreadable');
		});
		expect(await loader.request('a')).toMatchObject({ kind: 'failed', id: 'a' });
	});

	it('a shot that isn’t the one asked for is never given as it', async () => {
		const loader = new LatestShotLoader(async () => shot('other'));
		expect(await loader.request('a')).toEqual({ kind: 'loaded', id: 'a', shot: undefined });
	});
});
