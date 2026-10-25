import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ShotMachine, StoredShot } from '#lib/history/model.js';
import { forgetSerialsNotOnAccount, readDecentAccount, writeDecentAccount, DEFAULT_DECENT_ACCOUNT } from './account';
import type { FetchLike } from './api';

// A tiny in-memory history store — the upload path only needs get/all/bindDecentId.
const shots = new Map<string, StoredShot>();
vi.mock('#lib/history/store.svelte.js', () => ({
	getHistoryStore: () => ({
		get: (id: string) => shots.get(id),
		get all() {
			return [...shots.values()];
		},
		bindDecentId: (id: string, decentId: string, machine?: ShotMachine) => {
			const s = shots.get(id);
			if (s) shots.set(id, { ...s, decentId, ...(machine ? { machine } : {}) });
		}
	})
}));
let liveSerial: number | null = null;
vi.mock('#lib/state/machine-readout.svelte.js', () => ({
	getMachineReadout: () => ({
		get serialNumber() {
			return liveSerial;
		},
		firmwareString: 'v1.43 build 1352',
		firmwareBuild: 1352,
		machineModel: 3
	})
}));
const toasts = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }));
vi.mock('#lib/components/shared/toast.svelte.js', () => ({ toast: toasts }));

const {
	SERIAL_UNREAD_REASON,
	describeDecentDrain,
	resetDecentNotReplacedNotice,
	retryPendingDecentUploads,
	scheduleDecentReplaceAfterEdit,
	unsentDecentShots,
	uploadAndReportDecent,
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
// decentespresso.com's answer to a stored shot (Decenza tst_decentshotupload).
const ok: FetchLike = async () => {
	posts += 1;
	return new Response('{"ok":true,"stored":true,"id":"77"}', { status: 200 });
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
	forgetSerialsNotOnAccount();
	resetDecentNotReplacedNotice();
	toasts.success.mockClear();
	toasts.error.mockClear();
	toasts.info.mockClear();
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
		// The CPU build number alone, as decaid stamps it — not the Version label.
		expect(shots.get('old')?.machine).toEqual({ serialNumber: '6262', firmwareVersion: '1352', model: 'DE1PRO' });
	});
	it('uploads machine.firmwareVersion as the build number, normalising a legacy label', async () => {
		writeDecentAccount(linked);
		const bodies: Array<{ machine: Record<string, unknown> }> = [];
		const capture: FetchLike = async (_url, init) => {
			bodies.push(JSON.parse(String(init?.body)));
			return new Response('{"id":"77"}', { status: 200 });
		};
		shots.set('legacy', {
			...shot('legacy', 30_000),
			machine: { serialNumber: '6262', firmwareVersion: 'v1.43 build 1352', model: 'DE1PRO' }
		});
		expect(await uploadShotToDecent('legacy', { fetchFn: capture, appVersion: 't', manual: true })).toMatchObject({ kind: 'uploaded' });
		expect(bodies.at(-1)?.machine).toEqual({ serialNumber: '6262', firmwareVersion: '1352', model: 'DE1PRO' });
		shots.set('ble', {
			...shot('ble', 30_000),
			machine: { serialNumber: '6262', firmwareVersion: 'v1.0.142 (API 4)', model: 'DE1PRO' }
		});
		expect(await uploadShotToDecent('ble', { fetchFn: capture, appVersion: 't', manual: true })).toMatchObject({ kind: 'uploaded' });
		expect(bodies.at(-1)?.machine).toEqual({ serialNumber: '6262', model: 'DE1PRO' });
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

describe('Brew Log rows never reach the Decent account (issue #10)', () => {
	const brew = (id: string, over: Partial<StoredShot> = {}): StoredShot =>
		shot(id, 185_000, { brewMethod: 'pourover', record: { duration: 185_000, samples: [] }, ...over });
	it('skips a brew on the auto AND manual paths, POSTs nothing and stamps no machine', async () => {
		writeDecentAccount(linked);
		liveSerial = 6262; // a connected DE1 must not be borrowed for a pourover
		shots.set('v60', brew('v60'));
		for (const manual of [false, true]) {
			expect(await uploadShotToDecent('v60', { fetchFn: ok, appVersion: 't', manual })).toMatchObject({
				kind: 'skipped',
				reason: 'Brew Log entries stay on this device'
			});
		}
		expect(posts).toBe(0);
		expect(shots.get('v60')?.machine).toBeUndefined();
		expect(shots.get('v60')?.decentId).toBeUndefined();
	});
	it('keeps brews out of the backlog — even one carrying a machine stamp', async () => {
		shots.set('v60', brew('v60'));
		shots.set('stamped', brew('stamped', { brewMethod: 'espresso', machine: { serialNumber: '6262' } }));
		const ids = unsentDecentShots([...shots.values()], { hasLiveMachine: false, rejectedShotIds: [] }).map((s) => s.id);
		expect(ids).not.toContain('v60');
		expect(ids).not.toContain('stamped');
		expect(ids).toEqual(['a']);
		writeDecentAccount(linked);
		const r = await uploadUnsentDecentShots({ fetchFn: ok });
		expect(r.uploaded).toBe(ids.length);
		expect(shots.get('v60')?.decentId).toBeUndefined();
	});
	it('treats a GUIDED brew (weight series, no DE1 samples) the same — skipped and never queued', async () => {
		const series = {
			samples: [
				{ elapsedMs: 0, weightG: 0 },
				{ elapsedMs: 45_000, weightG: 45 },
				{ elapsedMs: 180_000, weightG: 250, flowGS: 0.4 }
			],
			stageMarks: [
				{ elapsedMs: 0, stepIndex: 0 },
				{ elapsedMs: 45_000, stepIndex: 1 }
			]
		};
		shots.set('guided', brew('guided', { recipeName: 'Morning V60', brewSeries: series }));
		writeDecentAccount(linked);
		liveSerial = 6262;
		for (const manual of [false, true]) {
			expect(await uploadShotToDecent('guided', { fetchFn: ok, appVersion: 't', manual })).toMatchObject({
				kind: 'skipped',
				reason: 'Brew Log entries stay on this device'
			});
		}
		const ids = unsentDecentShots([...shots.values()], { hasLiveMachine: true, rejectedShotIds: [] }).map((s) => s.id);
		expect(ids).not.toContain('guided');
		expect(posts).toBe(0);
		expect(shots.get('guided')?.machine).toBeUndefined();
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

describe('scheduleDecentReplaceAfterEdit (shot-upload 435acabb)', () => {
	const urls: string[] = [];
	const capture: FetchLike = async (input) => {
		posts += 1;
		urls.push(String(input));
		return new Response('{"id":"77"}', { status: 200 });
	};
	const flush = async () => {
		for (let i = 0; i < 20; i++) await Promise.resolve();
	};
	beforeEach(() => {
		urls.length = 0;
		vi.useFakeTimers();
	});

	it('a burst of edits to an uploaded shot queues exactly one replace upload', async () => {
		writeDecentAccount(linked);
		shots.set('a', { ...shots.get('a')!, decentId: '77' });
		expect(scheduleDecentReplaceAfterEdit('a', { fetchFn: capture, appVersion: 't' })).toBe(true);
		vi.advanceTimersByTime(500);
		scheduleDecentReplaceAfterEdit('a', { fetchFn: capture, appVersion: 't' });
		scheduleDecentReplaceAfterEdit('a', { fetchFn: capture, appVersion: 't' });
		expect(posts).toBe(0); // debounced
		await vi.advanceTimersByTimeAsync(1_500);
		await flush();
		expect(posts).toBe(1);
		expect(urls[0]).toMatch(/\/support\/api\/shot_upload\?replace=1$/);
	});

	it('does nothing for a never-uploaded shot, with auto-upload off, or unlinked', async () => {
		writeDecentAccount(linked);
		expect(scheduleDecentReplaceAfterEdit('a', { fetchFn: capture })).toBe(false); // no decentId
		shots.set('a', { ...shots.get('a')!, decentId: '77' });
		writeDecentAccount({ ...linked, autoUpload: false });
		expect(scheduleDecentReplaceAfterEdit('a', { fetchFn: capture })).toBe(false);
		writeDecentAccount(DEFAULT_DECENT_ACCOUNT);
		expect(scheduleDecentReplaceAfterEdit('a', { fetchFn: capture })).toBe(false);
		await vi.advanceTimersByTimeAsync(2_000);
		expect(posts).toBe(0);
	});
});

describe('a serial Decent says is not on the account (HTTP 403, de1app 5b09f6aa)', () => {
	const forbidden = statusFetch(403, '{"ok":false,"error":"not your machine"}');
	const REASON =
		"This DE1 (serial 6262) isn't registered to your Decent account — add it at decentespresso.com, then uploads resume.";

	it('POSTs once, parks nothing, and names the serial', async () => {
		writeDecentAccount(linked);
		const r = await uploadShotToDecent('a', { fetchFn: forbidden, appVersion: 't' });
		expect(r).toMatchObject({ kind: 'failed', error: { _tag: 'DecentNotOnAccountError', serial: '6262' } });
		expect(posts).toBe(1); // no in-call retry
		const account = readDecentAccount();
		expect(account.retryShotIds).toEqual([]);
		expect(account.rejectedShotIds).toEqual([]);
		expect(account.needsReauth).toBe(false);
		expect(account.lastUpload).toMatchObject({ ok: false, message: REASON });
		// Still in the backlog: it uploads once the machine is added.
		expect(unsentDecentShots([...shots.values()]).map((s) => s.id)).toContain('a');
	});

	it('shows the reason once: later automatic uploads for that serial skip without a POST', async () => {
		writeDecentAccount(linked);
		shots.set('b', shot('b', 30_000, { machine: { serialNumber: '6262' } }));
		await uploadAndReportDecent('a', { fetchFn: forbidden, appVersion: 't' });
		await uploadAndReportDecent('b', { fetchFn: forbidden, appVersion: 't' });
		expect(posts).toBe(1);
		expect(toasts.error).toHaveBeenCalledTimes(1);
		expect(toasts.error.mock.calls[0][0]).toContain(REASON);
		expect(await uploadShotToDecent('b', { fetchFn: forbidden, appVersion: 't' })).toEqual({
			kind: 'skipped',
			reason: REASON,
			notOnAccount: '6262'
		});
		// An explicit tap tries again (the owner may have just added the machine).
		expect(await uploadShotToDecent('b', { fetchFn: ok, appVersion: 't', manual: true })).toMatchObject({
			kind: 'uploaded'
		});
	});

	it('a backlog stops cleanly for that serial and carries on for another machine', async () => {
		writeDecentAccount(linked);
		for (const [id, sn] of [
			['b', '6262'],
			['c', '6262'],
			['d', '7000']
		] as const) {
			shots.set(id, shot(id, 30_000, { machine: { serialNumber: sn }, completedAt: 1_700_000_000_000 + id.charCodeAt(0) }));
		}
		const bySerial: FetchLike = async (_u, init) => {
			posts += 1;
			const sn = JSON.parse(String(init.body)).machine.serialNumber;
			return sn === '6262'
				? new Response('{"ok":false,"error":"not your machine"}', { status: 403 })
				: new Response('{"ok":true,"stored":true,"id":"9"}', { status: 200 });
		};
		const r = await uploadUnsentDecentShots({ fetchFn: bySerial });
		// a, b, c are 6262 (one POST, then held back); d is 7000.
		expect(posts).toBe(2);
		expect(r).toMatchObject({ uploaded: 1, failed: 0, stopped: null, notOnAccount: [{ serial: '6262', shots: 3 }] });
		expect(shots.get('d')?.decentId).toBe('9');
		const notice = describeDecentDrain(r);
		expect(notice?.kind).toBe('error');
		expect(notice?.message).toBe(`1 shot uploaded to Decent — 3 shots held back: ${REASON}`);
		expect(readDecentAccount().retryShotIds).toEqual([]);
	});

	it('a 403 drops the shot from the reconnect retry set', async () => {
		writeDecentAccount({ ...linked, retryShotIds: ['a'] });
		await uploadShotToDecent('a', { fetchFn: forbidden, appVersion: 't', manual: true });
		expect(readDecentAccount().retryShotIds).toEqual([]);
	});

	it('signing in again forgets it', async () => {
		writeDecentAccount(linked);
		await uploadShotToDecent('a', { fetchFn: forbidden, appVersion: 't' });
		const { linkDecentAccount } = await import('./account');
		await linkDecentAccount({ email: 'me@example.com', token: 'tok', serials: ['6262'] });
		expect(await uploadShotToDecent('a', { fetchFn: ok, appVersion: 't' })).toMatchObject({ kind: 'uploaded' });
	});
});

describe('serial 0 is unknown (Decenza 6c83ca06)', () => {
	it('never uploads as "0": waits with its own reason', async () => {
		writeDecentAccount(linked);
		liveSerial = 0;
		expect(await uploadShotToDecent('old', { fetchFn: ok, appVersion: 't', manual: true })).toEqual({
			kind: 'skipped',
			reason: SERIAL_UNREAD_REASON
		});
		expect(SERIAL_UNREAD_REASON).toBe("Crema couldn't read this DE1's serial number yet");
		expect(posts).toBe(0);
		expect(shots.get('old')?.machine).toBeUndefined();
		// Not in the backlog either: there is no serial to file it under.
		expect(unsentDecentShots([...shots.values()]).map((s) => s.id)).not.toContain('old');
	});

	it('a shot stamped "0" borrows the connected DE1 and is restamped', async () => {
		writeDecentAccount(linked);
		shots.set('zero', shot('zero', 30_000, { machine: { serialNumber: '0' } }));
		expect(await uploadShotToDecent('zero', { fetchFn: ok, appVersion: 't' })).toEqual({
			kind: 'skipped',
			reason: SERIAL_UNREAD_REASON
		});
		expect(unsentDecentShots([...shots.values()], { hasLiveMachine: false, rejectedShotIds: [] }).map((s) => s.id)).not.toContain('zero');
		liveSerial = 6262;
		const bodies: Array<{ machine: { serialNumber: string } }> = [];
		const capture: FetchLike = async (_u, init) => {
			bodies.push(JSON.parse(String(init.body)));
			return new Response('{"ok":true,"stored":true,"id":"5"}', { status: 200 });
		};
		expect(await uploadShotToDecent('zero', { fetchFn: capture, appVersion: 't' })).toMatchObject({
			kind: 'uploaded',
			url: 'https://decentespresso.com/shot/6262/5'
		});
		expect(bodies[0].machine.serialNumber).toBe('6262');
		expect(shots.get('zero')?.machine?.serialNumber).toBe('6262');
	});
});

describe('replace answered "duplicate" (Decenza f5c3ce3e)', () => {
	const DUPLICATE = '{"ok":true,"stored":false,"duplicate":true,"id":"77"}';
	const flush = async () => {
		for (let i = 0; i < 20; i++) await Promise.resolve();
	};

	it('is not an upload: the earlier upload stands, nothing is parked, the reason is recorded', async () => {
		writeDecentAccount(linked);
		shots.set('a', { ...shots.get('a')!, decentId: '77' });
		const r = await uploadShotToDecent('a', { fetchFn: statusFetch(200, DUPLICATE), appVersion: 't', manual: true, replace: true });
		expect(r).toMatchObject({ kind: 'failed', error: { _tag: 'DecentNotReplacedError', id: '77' } });
		expect(posts).toBe(1);
		expect(shots.get('a')?.decentId).toBe('77');
		const account = readDecentAccount();
		expect(account).toMatchObject({ retryShotIds: [], rejectedShotIds: [], needsReauth: false });
		expect(account.lastUpload).toMatchObject({ ok: false, message: expect.stringContaining('kept its earlier copy') });
	});

	it('a first upload answered "duplicate" is an upload', async () => {
		writeDecentAccount(linked);
		expect(await uploadShotToDecent('a', { fetchFn: statusFetch(200, DUPLICATE), appVersion: 't' })).toMatchObject({
			kind: 'uploaded',
			id: '77'
		});
	});

	it('an edit whose replace Decent kept the earlier copy for is toasted once per session', async () => {
		vi.useFakeTimers();
		writeDecentAccount(linked);
		shots.set('a', { ...shots.get('a')!, decentId: '77' });
		const dup = statusFetch(200, DUPLICATE);
		for (let i = 0; i < 2; i++) {
			scheduleDecentReplaceAfterEdit('a', { fetchFn: dup, appVersion: 't' });
			await vi.advanceTimersByTimeAsync(1_500);
			await flush();
		}
		expect(posts).toBe(2);
		expect(toasts.error).toHaveBeenCalledTimes(1);
		expect(toasts.error.mock.calls[0][0]).toContain('Decent kept its earlier copy');
		expect(toasts.success).not.toHaveBeenCalled();
	});
});

describe('a 2xx that is not the API answer (captive portal)', () => {
	it('is retried in-call and parked for retry, never recorded as an upload', async () => {
		vi.useFakeTimers();
		writeDecentAccount(linked);
		const p = uploadShotToDecent('a', { fetchFn: statusFetch(200, '<html>Sign in to Wi-Fi</html>'), appVersion: 't' });
		await vi.advanceTimersByTimeAsync(10_000);
		expect(await p).toMatchObject({ kind: 'failed', error: { _tag: 'DecentNetworkError', status: 200 } });
		expect(posts).toBe(3);
		expect(shots.get('a')?.decentId).toBeUndefined();
		expect(readDecentAccount().retryShotIds).toEqual(['a']);
	});
});

