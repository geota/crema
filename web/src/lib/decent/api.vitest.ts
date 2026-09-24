import { describe, expect, it } from 'vitest';
import {
	DecentAuthError,
	DecentNetworkError,
	DecentRejectedError,
	decentLogin,
	decentFetchMachines,
	decentShotViewUrl,
	decentUploadShot,
	type FetchLike
} from './api';

function respond(status: number, body: string): FetchLike {
	return async () => new Response(body, { status });
}

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
			return new Response('ok', { status: 200 });
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
