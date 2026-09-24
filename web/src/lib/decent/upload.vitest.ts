import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ShotMachine, StoredShot } from '$lib/history/model';
import { readDecentAccount, writeDecentAccount, DEFAULT_DECENT_ACCOUNT } from './account';
import type { FetchLike } from './api';

// A tiny in-memory history store — the upload path only needs get/all/bindDecentId.
const shots = new Map<string, StoredShot>();
vi.mock('$lib/history/store.svelte', () => ({
	getHistoryStore: () => ({
		get: (id: string) => shots.get(id),
		get all() {
			return [...shots.values()];
		},
		bindDecentId: (id: string, decentId: string, machine?: ShotMachine) => {
			const s = shots.get(id);
			if (s) shots.set(id, { ...s, decentId, ...(machine && !s.machine ? { machine } : {}) });
		}
	})
}));
let liveSerial: number | null = null;
vi.mock('$lib/state/machine-readout.svelte', () => ({
	getMachineReadout: () => ({
		get serialNumber() {
			return liveSerial;
		},
		firmwareString: 'v1.43 build 1352',
		firmwareBuild: 1352,
		machineModel: 3
	})
}));
vi.mock('$lib/components/shared/toast.svelte', () => ({
	toast: { success: () => {}, error: () => {}, info: () => {} }
}));

const {
	describeDecentDrain,
	retryPendingDecentUploads,
	unsentDecentShots,
	uploadShotToDecent,
	uploadUnsentDecentShots
} = await import('./upload');

function shot(id: string, durationMs: number, over: Partial<StoredShot> = {}): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		profileName: 'P',
		metadata: {},
		record: {
			duration: durationMs,
			samples: [
				{
					elapsed: 0,
					sample: { sampleTime: 0, groupPressure: 0, groupFlow: 0, headTemp: 0, mixTemp: 0, setMixTemp: 0, setHeadTemp: 0, setGroupPressure: 0, setGroupFlow: 0, frameNumber: 0, steamTemp: 0 }
				}
			]
		},
		...over
	};
}

let posts = 0;
const ok: FetchLike = async () => {
	posts += 1;
	return new Response('{"id":"77"}', { status: 200 });
};
const statusFetch =
	(status: number, body = ''): FetchLike =>
	async () => {
		posts += 1;
		return new Response(body, { status });
	};
const offlineFetch: FetchLike = async () => {
	posts += 1;
	throw new TypeError('Failed to fetch');
};
const linked = { ...DEFAULT_DECENT_ACCOUNT, email: 'me@example.com', token: 'tok', serials: ['6262'], autoUpload: true };

function setOnline(on: boolean): void {
	Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => on });
}

beforeEach(() => {
	localStorage.clear();
	shots.clear();
	liveSerial = null;
	posts = 0;
	setOnline(true);
	shots.set('a', shot('a', 30_000, { machine: { serialNumber: '6262' } }));
	shots.set('flush', shot('flush', 2_000, { machine: { serialNumber: '6262' } }));
	shots.set('old', shot('old', 25_000)); // pre-#84 row: no stamped machine
});
afterEach(() => {
	vi.useRealTimers();
});

describe('uploadShotToDecent gates', () => {
	it('skips when no account is linked', async () => {
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped' });
	});
	it('auto path honours the auto-upload toggle and the 5 s floor; manual does not', async () => {
		writeDecentAccount({ ...linked, autoUpload: false });
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped', reason: 'Auto-upload is off' });
		writeDecentAccount(linked);
		expect(await uploadShotToDecent('flush', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped', reason: 'Shorter than 5 s' });
		expect(await uploadShotToDecent('flush', { fetchFn: ok, appVersion: 't', manual: true })).toMatchObject({ kind: 'uploaded', id: '77' });
	});
	it('needs a serial — the stamped one, else the connected DE1, which is then stamped', async () => {
		writeDecentAccount(linked);
		expect(await uploadShotToDecent('old', { fetchFn: ok, appVersion: 't' })).toMatchObject({
			kind: 'skipped',
			reason: 'No DE1 serial number known — connect the machine first'
		});
		liveSerial = 6262;
		expect(await uploadShotToDecent('old', { fetchFn: ok, appVersion: 't' })).toMatchObject({
			kind: 'uploaded',
			url: 'https://decentespresso.com/shot/6262/77'
		});
		expect(shots.get('old')?.machine).toEqual({ serialNumber: '6262', firmwareVersion: 'v1.43 build 1352', model: 'DE1PRO' });
	});
	it('never stamps the live serial onto a shot pulled from Visualizer', async () => {
		writeDecentAccount(linked);
		liveSerial = 6262;
		shots.set('shot:remote:9', shot('shot:remote:9', 30_000, { visualizerId: '9' }));
		expect(await uploadShotToDecent('shot:remote:9', { fetchFn: ok, appVersion: 't', manual: true })).toMatchObject({
			kind: 'skipped',
			reason: 'Pulled from Visualizer — not recorded on this DE1'
		});
		expect(posts).toBe(0);
	});
});

describe('uploadShotToDecent outcomes', () => {
	it('binds the server id, records the last upload with its URL, and skips a re-upload', async () => {
		writeDecentAccount(linked);
		const r = await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' });
		expect(r).toEqual({ kind: 'uploaded', id: '77', url: 'https://decentespresso.com/shot/6262/77' });
		expect(shots.get('a')?.decentId).toBe('77');
		expect(readDecentAccount().lastUpload).toMatchObject({ ok: true, url: expect.stringContaining('/shot/6262/77') });
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped', reason: 'Already on Decent' });
	});
	it('flags the account for re-auth on 401 — and on a 2xx "0" — without retrying', async () => {
		for (const f of [statusFetch(401), statusFetch(200, '0')]) {
			writeDecentAccount(linked);
			posts = 0;
			const r = await uploadShotToDecent('a', { fetchFn: f, appVersion: 't' });
			expect(r).toMatchObject({ kind: 'failed', error: { _tag: 'DecentAuthError' } });
			expect(posts).toBe(1);
			expect(readDecentAccount().needsReauth).toBe(true);
			expect(shots.get('a')?.decentId).toBeUndefined();
		}
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped' });
	});
	it('remembers a permanent rejection: out of the backlog, skipped on the auto path, retried by hand', async () => {
		writeDecentAccount(linked);
		const r = await uploadShotToDecent('a', { fetchFn: statusFetch(422, 'serial mismatch'), appVersion: 't' });
		expect(r).toMatchObject({ kind: 'failed', error: { _tag: 'DecentRejectedError', status: 422 } });
		expect(posts).toBe(1);
		expect(readDecentAccount().rejectedShotIds).toEqual(['a']);
		expect(unsentDecentShots([...shots.values()]).map((s) => s.id)).not.toContain('a');
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped' });
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't', manual: true })).toMatchObject({ kind: 'uploaded' });
		expect(readDecentAccount().rejectedShotIds).toEqual([]);
	});
	it('asks for re-auth when the stored token can no longer be unwrapped', async () => {
		// A wrapped token whose key is gone (jsdom has no IndexedDB → no key).
		writeDecentAccount({ ...linked, token: 'sb1:AAAA:BBBB' });
		const r = await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' });
		expect(r).toMatchObject({ kind: 'failed', error: { _tag: 'DecentAuthError' } });
		expect(posts).toBe(0);
		expect(readDecentAccount()).toMatchObject({ needsReauth: true, lastUpload: { ok: false } });
		// Later pushes skip quietly instead of failing again.
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'skipped' });
	});
});

describe('concurrency', () => {
	it('POSTs once for concurrent uploads of the same shot', async () => {
		writeDecentAccount(linked);
		let release: () => void = () => {};
		const gate = new Promise<void>((r) => (release = r));
		const slow: FetchLike = async () => {
			posts += 1;
			await gate;
			return new Response('{"id":"77"}', { status: 200 });
		};
		const first = uploadShotToDecent('a', { fetchFn: slow, appVersion: 't' });
		const second = uploadShotToDecent('a', { fetchFn: slow, appVersion: 't', manual: true });
		expect(second).toBe(first);
		release();
		expect(await first).toMatchObject({ kind: 'uploaded' });
		expect(posts).toBe(1);
		// Once settled, a new call runs again (and finds the shot uploaded).
		expect(await uploadShotToDecent('a', { fetchFn: slow, appVersion: 't' })).toMatchObject({ reason: 'Already on Decent' });
	});

	it('runs one backlog drain at a time — a second caller joins the first', async () => {
		writeDecentAccount(linked);
		shots.set('b', shot('b', 30_000, { machine: { serialNumber: '6262' } }));
		const one = uploadUnsentDecentShots({ fetchFn: ok });
		const two = uploadUnsentDecentShots({ fetchFn: ok });
		expect(two).toBe(one);
		expect(await one).toMatchObject({ uploaded: 2, failed: 0, stopped: null });
		expect(posts).toBe(2);
	});
});

describe('uploadUnsentDecentShots', () => {
	beforeEach(() => {
		for (const id of ['b', 'c', 'd', 'e']) {
			shots.set(id, shot(id, 30_000, { machine: { serialNumber: '6262' }, completedAt: 1_700_000_000_000 + id.charCodeAt(0) }));
		}
	});
	it('stops at the first failure with no HTTP status (offline)', async () => {
		writeDecentAccount(linked);
		setOnline(false); // also skips the in-call retry: it cannot succeed offline
		const r = await uploadUnsentDecentShots({ fetchFn: offlineFetch });
		expect(r).toMatchObject({ uploaded: 0, failed: 1, stopped: 'offline' });
		expect(posts).toBe(1);
		expect(readDecentAccount().retryShotIds).toHaveLength(1);
		expect(describeDecentDrain(r)?.kind).toBe('error');
	});
	it('stops after three failures in a row', async () => {
		writeDecentAccount(linked);
		const r = await uploadUnsentDecentShots({ fetchFn: statusFetch(422, 'nope') });
		expect(r).toMatchObject({ uploaded: 0, failed: 3, stopped: 'failures', lastError: expect.stringContaining('422') });
		expect(posts).toBe(3);
	});
	it('stops on auth', async () => {
		writeDecentAccount(linked);
		const r = await uploadUnsentDecentShots({ fetchFn: statusFetch(401) });
		expect(r).toMatchObject({ failed: 1, stopped: 'auth' });
	});
	it('excludes shots it could not send: no machine without a live DE1, pulled, refused, flushes', () => {
		shots.set('shot:remote:1', shot('shot:remote:1', 30_000));
		const ids = (hasLiveMachine: boolean) =>
			unsentDecentShots([...shots.values()], { hasLiveMachine, rejectedShotIds: ['e'] }).map((s) => s.id);
		expect(ids(false).sort()).toEqual(['a', 'b', 'c', 'd']);
		expect(ids(true).sort()).toEqual(['a', 'b', 'c', 'd', 'old']);
	});
});

describe('network retry', () => {
	it('retries a network failure with backoff (2 s, then 4 s) inside the call', async () => {
		vi.useFakeTimers();
		writeDecentAccount(linked);
		let n = 0;
		const flaky: FetchLike = async () => {
			posts += 1;
			n += 1;
			return n < 3 ? new Response('', { status: 503 }) : new Response('{"id":"5"}', { status: 200 });
		};
		const p = uploadShotToDecent('a', { fetchFn: flaky, appVersion: 't' });
		await vi.advanceTimersByTimeAsync(0);
		expect(posts).toBe(1);
		await vi.advanceTimersByTimeAsync(1999);
		expect(posts).toBe(1);
		await vi.advanceTimersByTimeAsync(1);
		expect(posts).toBe(2);
		await vi.advanceTimersByTimeAsync(3999);
		expect(posts).toBe(2);
		await vi.advanceTimersByTimeAsync(1);
		expect(await p).toMatchObject({ kind: 'uploaded', id: '5' });
		expect(posts).toBe(3);
	});

	it('remembers a shot that still failed on the network and retries it when back online', async () => {
		writeDecentAccount(linked);
		setOnline(false);
		expect(await uploadShotToDecent('a', { fetchFn: offlineFetch, appVersion: 't' })).toMatchObject({ kind: 'failed' });
		expect(readDecentAccount().retryShotIds).toEqual(['a']);
		expect(await retryPendingDecentUploads({ fetchFn: ok })).toBeNull(); // still offline
		setOnline(true);
		expect(await retryPendingDecentUploads({ fetchFn: ok })).toMatchObject({ uploaded: 1 });
		expect(shots.get('a')?.decentId).toBe('77');
		expect(readDecentAccount().retryShotIds).toEqual([]);
	});
});
