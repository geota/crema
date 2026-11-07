/**
 * The Drive picker's lines and listing: every backup is reachable, not just the
 * newest, and a name says as much as it can.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { backupDate, backupSubtitle } from './backup-list';
import { LIST_MAX_PAGES, listBackups, type DriveFile } from './rest';

const file = (name: string, modifiedTime = '2026-11-03T08:00:00.000Z'): DriveFile => ({
	id: name,
	name,
	modifiedTime
});

afterEach(() => vi.unstubAllGlobals());

describe('backupSubtitle', () => {
	it.each([
		[
			'crema-backup-auto-Pixel-Tablet~a1b2c3-20261103-0800-s240c238.crema.zip',
			'Pixel-Tablet · 240 shots, 238 with curves · Daily'
		],
		['crema-backup-auto-Pixel-20261103-0800.crema.zip', 'Pixel · Daily'],
		['crema-backup-Pixel-20261103-0800.crema.zip', 'Pixel · Manual'],
		['crema-backup-web-20261103T0800.crema.zip', 'web · Manual'],
		['something-else.zip', 'Manual']
	])('%s', (name, line) => {
		expect(backupSubtitle(file(name))).toBe(line);
	});
});

describe('backupDate', () => {
	it('shows when it was saved', () => {
		expect(backupDate(file('x'))).toContain('2026');
	});

	it('says so when Drive gave no time it can read', () => {
		expect(backupDate(file('x', ''))).toBe('Unknown date');
	});
});

describe('listBackups', () => {
	it('reads every page, so an older good copy can still be picked', async () => {
		const urls: string[] = [];
		vi.stubGlobal(
			'fetch',
			vi.fn(async (url: string) => {
				urls.push(url);
				const second = url.includes('pageToken=');
				return new Response(
					JSON.stringify(
						second
							? { files: [file('b')] }
							: { files: [file('a')], nextPageToken: 'next page' }
					)
				);
			})
		);
		const files = await listBackups('token');
		expect(files.map((f) => f.name)).toEqual(['a', 'b']);
		expect(urls[1]).toContain('pageToken=next%20page');
	});

	it('fails on a reply with no list rather than calling it empty', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => new Response('{}')));
		await expect(listBackups('token')).rejects.toThrow(/couldn’t read the reply/);
	});

	it('stops when a page token comes round again', async () => {
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => new Response(JSON.stringify({ files: [file('a')], nextPageToken: 'same' })))
		);
		await expect(listBackups('token')).rejects.toThrow(/went round/);
	});

	it('stops after its page cap', async () => {
		let n = 0;
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => new Response(JSON.stringify({ files: [], nextPageToken: `p${n++}` })))
		);
		await expect(listBackups('token')).rejects.toThrow(/didn’t end/);
		expect(n).toBe(LIST_MAX_PAGES);
	});

	it('fails rather than returning part of a listing', async () => {
		let calls = 0;
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => {
				calls += 1;
				return calls === 1
					? new Response(JSON.stringify({ files: [file('a')], nextPageToken: '2' }))
					: new Response('{}', { status: 500 });
			})
		);
		await expect(listBackups('token')).rejects.toThrow(/list failed/);
	});
});
