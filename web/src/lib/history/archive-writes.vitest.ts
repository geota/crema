/**
 * #420: a factory reset stops the curve archive's writes, and waits for the
 * one in flight, before it removes the directory, so no write lands after
 * and brings a shot's curve back into an erased browser.
 */
import { expect, it } from 'vitest';
import { realSamples } from '#lib/testing/real-shots.js';
import { CurveArchive, archiveNameOf } from './curve-archive';
import { archiveWritesStopped, stopArchiveWrites } from './archive-writes';

it('a write in flight lands before the stop ends, and a write after it writes nothing', async () => {
	const files = new Map<string, Uint8Array>();
	let release = () => {};
	const held = new Promise<void>((r) => (release = r));
	const dir = {
		async getFileHandle(name: string, opts?: { create?: boolean }) {
			if (!files.has(name) && !opts?.create) throw new DOMException('none', 'NotFoundError');
			return {
				async getFile() {
					const f = files.get(name);
					if (!f) throw new DOMException('none', 'NotFoundError');
					return { size: f.length, arrayBuffer: async () => f.slice().buffer };
				},
				async createWritable() {
					let bytes = new Uint8Array(0);
					return {
						async write(p: BufferSource) {
							bytes = new Uint8Array(p as ArrayBuffer);
						},
						async close() {
							await held;
							files.set(name, bytes);
						}
					};
				}
			};
		}
	};
	const root = async () => ({ getDirectoryHandle: async () => dir }) as unknown as FileSystemDirectoryHandle;
	const archive = new CurveArchive(root);
	const first = archive.write('shot:a', { samples: realSamples(5), brewSeries: null });
	await new Promise((r) => setTimeout(r, 20));
	let stopped = false;
	const stop = stopArchiveWrites().then(() => (stopped = true));
	await new Promise((r) => setTimeout(r, 10));
	expect(stopped, 'the stop waits for the write in flight').toBe(false);
	release();
	await stop;
	expect(await first).toBe('written');
	expect(archiveWritesStopped()).toBe(true);
	files.clear();
	expect(await archive.write('shot:b', { samples: realSamples(5), brewSeries: null })).toBe('none');
	expect(files.has(archiveNameOf('shot:b'))).toBe(false);
});
