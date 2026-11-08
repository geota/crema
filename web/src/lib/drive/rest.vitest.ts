/**
 * A Drive upload is resumable (#310 P2w): the session opens with the
 * metadata, and the content goes in one PUT as the Blob it is, so a backup
 * file streams from disk. Without a readable session URL, one multipart POST.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { uploadBackup } from './rest';

afterEach(() => vi.unstubAllGlobals());

type Call = { url: string; init: RequestInit };

function fakeFetch(location: string | null) {
	const calls: Call[] = [];
	vi.stubGlobal(
		'fetch',
		vi.fn(async (url: string, init: RequestInit) => {
			calls.push({ url, init });
			if (url.includes('uploadType=resumable')) {
				return new Response(null, { status: 200, headers: location ? { Location: location } : {} });
			}
			return new Response(JSON.stringify({ id: 'f1', name: 'b.crema.zip', modifiedTime: 'now' }), { status: 200 });
		})
	);
	return calls;
}

describe('uploadBackup', () => {
	it('opens a resumable session, then PUTs the file itself', async () => {
		const calls = fakeFetch('https://upload.example/session-1');
		const file = new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'application/zip' });
		const out = await uploadBackup('tok', 'b.crema.zip', file, 'application/zip');
		expect(out.id).toBe('f1');
		expect(calls).toHaveLength(2);
		const [open, put] = calls;
		expect(open.init.method).toBe('POST');
		expect(JSON.parse(open.init.body as string)).toEqual({ name: 'b.crema.zip', mimeType: 'application/zip' });
		expect((open.init.headers as Record<string, string>)['X-Upload-Content-Length']).toBe('4');
		expect(put.url).toBe('https://upload.example/session-1');
		expect(put.init.method).toBe('PUT');
		expect(put.init.body, 'the file, not a copy').toBe(file);
	});

	it('falls back to one multipart request when the session URL can’t be read', async () => {
		const calls = fakeFetch(null);
		const out = await uploadBackup('tok', 'b.crema.zip', new Uint8Array([9, 9]), 'application/zip');
		expect(out.id).toBe('f1');
		expect(calls).toHaveLength(2);
		expect(calls[1].url).toContain('uploadType=multipart');
		const body = calls[1].init.body as Blob;
		const text = new TextDecoder().decode(new Uint8Array(await body.arrayBuffer()));
		expect(text).toContain('"name":"b.crema.zip"');
	});

	it('a refused session is a failed upload', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: { message: 'no' } }), { status: 403 })));
		await expect(uploadBackup('tok', 'b', 'x')).rejects.toThrow(/upload failed \(403\) — no/);
	});
});
