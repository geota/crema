import { describe, expect, it } from 'vitest';
import {
	DecentAuthError,
	DecentNetworkError,
	DecentNotOnAccountError,
	DecentNotReplacedError,
	DecentRejectedError,
	decentKnownSerial,
	decentLogin,
	decentFetchMachines,
	decentShotViewUrl,
	decentUploadShot,
	type FetchLike
} from './api';

function respond(status: number, body: string): FetchLike {
	return async () => new Response(body, { status });
}

// decentespresso.com's answers, from Decenza's tests/tst_decentshotupload.cpp.
const STORED = '{"ok":true,"stored":true,"id":"srv-1"}';
const REPLACED = '{"ok":true,"stored":true,"replaced":true,"id":"srv-1"}';
const DUPLICATE = '{"ok":true,"stored":false,"duplicate":true,"id":"srv-1"}';
const NOT_YOUR_MACHINE = '{"ok":false,"error":"not your machine"}';
const creds = { email: 'me@example.com', token: 't' };

describe('Decent support API', () => {
	it('sends HTTP Basic and treats "0" as a rejected login', async () => {
		let seenAuth = '';
		const fetchFn: FetchLike = async (_url, init) => {
			seenAuth = (init.headers as Record<string, string>).Authorization;
			return new Response('0', { status: 200 });
		};
		expect(await decentLogin('me@example.com', 'hunter2', fetchFn)).toBeNull();
		expect(seenAuth).toBe(`Basic ${btoa('me@example.com:hunter2')}`);
	});

	it('returns the server token for a good login', async () => {
		expect(await decentLogin('me@example.com', 'pw', respond(200, 'abcdef0123456789\n'))).toBe('abcdef0123456789');
	});

	it('reads the registered-machine list through core, and a "0" as auth', async () => {
		const creds = { email: 'me@example.com', token: 't' };
		await expect(decentFetchMachines(creds, respond(200, '6262 DE1PRO\n\n7000\n6262\n'))).resolves.toEqual([
			{ serial: '6262', sku: 'DE1PRO' },
			{ serial: '7000', sku: '' }
		]);
		await expect(decentFetchMachines(creds, respond(200, '0'))).rejects.toBeInstanceOf(DecentAuthError);
	});

	it('maps upload statuses onto the error taxonomy', async () => {
		const creds = { email: 'me@example.com', token: 't' };
		await expect(decentUploadShot(creds, '{}', {}, respond(200, '{"id": 4242}'))).resolves.toEqual({ id: '4242' });
		await expect(decentUploadShot(creds, '{}', {}, respond(401, ''))).rejects.toBeInstanceOf(DecentAuthError);
		await expect(decentUploadShot(creds, '{}', {}, respond(422, 'serial not on account'))).rejects.toBeInstanceOf(DecentRejectedError);
		await expect(decentUploadShot(creds, '{}', {}, respond(503, ''))).rejects.toBeInstanceOf(DecentNetworkError);
		await expect(
			decentUploadShot(creds, '{}', {}, async () => {
				throw new Error('offline');
			})
		).rejects.toBeInstanceOf(DecentNetworkError);
	});

	it('treats a 2xx "0" upload reply as an auth failure, never an id', async () => {
		const creds = { email: 'me@example.com', token: 't' };
		await expect(decentUploadShot(creds, '{}', {}, respond(200, '0'))).rejects.toBeInstanceOf(DecentAuthError);
		await expect(decentUploadShot(creds, '{}', {}, respond(200, '0\n'))).rejects.toBeInstanceOf(DecentAuthError);
	});

	it('turns a failure while reading the body into a network error', async () => {
		const creds = { email: 'me@example.com', token: 't' };
		const broken: FetchLike = async () =>
			({ status: 200, text: () => Promise.reject(new Error('connection reset')) }) as unknown as Response;
		await expect(decentUploadShot(creds, '{}', {}, broken)).rejects.toMatchObject({
			_tag: 'DecentNetworkError',
			status: null,
			detail: 'connection reset'
		});
	});

	it('asks for a replace when re-uploading', async () => {
		let url = '';
		await decentUploadShot({ email: 'e', token: 't' }, '{}', { replace: true }, async (u) => {
			url = u;
			return new Response(REPLACED, { status: 200 });
		});
		expect(url).toBe('https://decentespresso.com/support/api/shot_upload?replace=1');
	});

	it('builds the public share link only when serial and a real id are known', () => {
		expect(decentShotViewUrl('6262', '99')).toBe('https://decentespresso.com/shot/6262/99');
		expect(decentShotViewUrl(null, '99')).toBeNull();
		expect(decentShotViewUrl('6262', 'uploaded:1700000000000')).toBeNull();
		expect(decentShotViewUrl('6262', null)).toBeNull();
	});
});

describe('Decent upload answers (P1-1 / P1-2)', () => {
	it('takes the API answer as an upload, a first-upload duplicate included', async () => {
		await expect(decentUploadShot(creds, '{}', {}, respond(200, STORED))).resolves.toEqual({ id: 'srv-1' });
		await expect(decentUploadShot(creds, '{}', {}, respond(200, DUPLICATE))).resolves.toEqual({ id: 'srv-1' });
		await expect(decentUploadShot(creds, '{}', { replace: true }, respond(200, REPLACED))).resolves.toEqual({
			id: 'srv-1'
		});
	});

	it('a replace answered "duplicate" is not an upload: Decent kept its earlier copy', async () => {
		const err = await decentUploadShot(creds, '{}', { replace: true }, respond(200, DUPLICATE)).catch((e) => e);
		expect(err).toBeInstanceOf(DecentNotReplacedError);
		expect(err).toMatchObject({ id: 'srv-1' });
		expect(err.message).toBe("Decent kept its earlier copy of this shot — the edit wasn't saved there.");
	});

	it('a 2xx that is not the API answer (captive portal) is a retryable failure', async () => {
		for (const body of ['<html>Sign in to Wi-Fi</html>', '', 'ok', '{"ok":false}']) {
			const err = await decentUploadShot(creds, '{}', {}, respond(200, body)).catch((e) => e);
			expect(err, body).toBeInstanceOf(DecentNetworkError);
			expect(err.status).toBe(200);
			expect(err.message).toContain("wasn't an upload reply");
		}
	});

	it('a 403 is "serial not on the account", with the serial in the reason', async () => {
		const err = await decentUploadShot(creds, '{}', { serial: '6262' }, respond(403, NOT_YOUR_MACHINE)).catch((e) => e);
		expect(err).toBeInstanceOf(DecentNotOnAccountError);
		expect(err.message).toBe(
			"This DE1 (serial 6262) isn't registered to your Decent account — add it at decentespresso.com, then uploads resume."
		);
	});

	it('sends the body as plain JSON — no Content-Encoding (the server CORS preflight does not allow it)', async () => {
		let init: RequestInit | undefined;
		await decentUploadShot(creds, '{"x":1}', {}, async (_u, i) => {
			init = i;
			return new Response(STORED, { status: 200 });
		});
		const headers = init?.headers as Record<string, string>;
		expect(headers['Content-Encoding']).toBeUndefined();
		expect(headers['Content-Type']).toBe('application/json; charset=utf-8');
		expect(init?.body).toBe('{"x":1}');
	});

	it('treats a serial of 0 as unknown', () => {
		expect(decentKnownSerial(0)).toBeNull();
		expect(decentKnownSerial('0')).toBeNull();
		expect(decentKnownSerial(null)).toBeNull();
		expect(decentKnownSerial(6262)).toBe('6262');
	});
});
