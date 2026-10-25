/**
 * `#lib/firmware/source` + `power` — the runtime firmware source and the
 * power precondition. Validation runs through the real wasm manifest check.
 */

import { beforeAll, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { DecentFirmwareChannel } from '#lib/core/crema-core.js';
import { downloadOfficialImage, imageProblem, pinnedFirmware, verifyImageFor } from './source';
import { powerOk } from './power';

beforeAll(initTestWasm);

describe('pinnedFirmware', () => {
	it('lists Stable 1333 and Nightly 1358 with Decent URLs', () => {
		const m = pinnedFirmware();
		expect(m.map((a) => [a.build, a.channel])).toEqual([
			[1333, DecentFirmwareChannel.Stable],
			[1358, DecentFirmwareChannel.Nightly]
		]);
		expect(m[1].urls[0]).toBe(
			'https://fast.decentespresso.com/download/sync/de1nightly/fw/bootfwupdate.dat'
		);
		expect(m[1].chunkCount).toBe(28_992);
	});
});

describe('verifyImageFor', () => {
	const nightly = () => pinnedFirmware()[1];

	it('refuses a foreign file with a plain reason', () => {
		const v = verifyImageFor(nightly(), new Uint8Array(4096).fill(0x42));
		expect(v.ok).toBe(false);
		if (!v.ok) expect(v.problem).toContain("isn't DE1 firmware");
	});

	it('refuses an unpinned DE1 image, naming its build', () => {
		const img = new Uint8Array(463_872).fill(0xa5);
		const dv = new DataView(img.buffer);
		dv.setUint32(4, 0xde100001, true);
		dv.setUint32(8, 1358, true);
		const v = verifyImageFor(nightly(), img);
		expect(v.ok).toBe(false);
		if (!v.ok) expect(v.problem).toContain('build 1358');
	});

	it('refuses a Bengle image', () => {
		const img = new Uint8Array(1024);
		new DataView(img.buffer).setUint32(4, 0xbe1c0001, true);
		const v = verifyImageFor(nightly(), img);
		if (!v.ok) expect(v.problem).toContain('Bengle');
	});

	it('maps every error type to copy', () => {
		expect(imageProblem({ ok: false, byteLength: 3, sha256: '', error: { type: 'TooShort', content: { length: 3 } } })).toContain('too small');
	});
});

describe('downloadOfficialImage', () => {
	it('reports "blocked" when the browser refuses the request (CORS)', async () => {
		const fetchFn = (async () => {
			throw new TypeError('Failed to fetch');
		}) as unknown as typeof fetch;
		const r = await downloadOfficialImage(pinnedFirmware()[0], fetchFn);
		expect(r).toEqual({ kind: 'blocked', detail: 'Failed to fetch' });
	});

	it('rejects a downloaded file that does not match the manifest', async () => {
		const fetchFn = (async () =>
			new Response(new Uint8Array(4096).fill(1))) as unknown as typeof fetch;
		const r = await downloadOfficialImage(pinnedFirmware()[0], fetchFn);
		expect(r.kind).toBe('rejected');
	});

	it('tries the next official URL after an HTTP error', async () => {
		const seen: string[] = [];
		const fetchFn = (async (url: string) => {
			seen.push(url);
			return new Response('nope', { status: 503 });
		}) as unknown as typeof fetch;
		const r = await downloadOfficialImage(pinnedFirmware()[0], fetchFn);
		expect(seen).toEqual(pinnedFirmware()[0].urls);
		expect(r).toEqual({ kind: 'blocked', detail: 'HTTP 503' });
	});

	it('accepts the real file when available (CREMA_FIRMWARE_DIR)', async () => {
		const dir = process.env.CREMA_FIRMWARE_DIR;
		if (!dir) return;
		const { readFile } = await import('node:fs/promises');
		const bytes = new Uint8Array(await readFile(`${dir}/de1plus.dat`));
		const fetchFn = (async () => new Response(bytes)) as unknown as typeof fetch;
		const r = await downloadOfficialImage(pinnedFirmware()[0], fetchFn);
		expect(r.kind).toBe('ok');
	});
});

describe('powerOk', () => {
	it('charging, or at least 50 %, or no battery API', () => {
		expect(powerOk(null)).toBe(true);
		expect(powerOk({ level: 0.2, charging: true })).toBe(true);
		expect(powerOk({ level: 0.5, charging: false })).toBe(true);
		expect(powerOk({ level: 0.49, charging: false })).toBe(false);
	});
});
