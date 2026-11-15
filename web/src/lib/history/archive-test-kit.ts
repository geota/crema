/**
 * A private file system in memory for the curve archive's tests (#420 S1).
 * Not a test file.
 */
import type { HistoryStoreOptions } from './store.svelte';

/**
 * A private file system in memory, any directory: `create` makes an empty
 * file at once and a writable lands on close, as in a browser. `quota` makes
 * every close in the curve archive fail, as a full disk does.
 */
export function memoryFs() {
	const dirs = new Map<string, Map<string, Uint8Array>>();
	const control = { quota: false, failRemove: false, hold: null as Promise<void> | null, events: [] as string[] };
	const notFound = () => new DOMException('none', 'NotFoundError');
	const dirOf = (dname: string, files: Map<string, Uint8Array>) => ({
		async getFileHandle(name: string, opts?: { create?: boolean }) {
			if (!files.has(name) && !opts?.create) throw notFound();
			if (!files.has(name)) files.set(name, new Uint8Array(0));
			return {
				async createWritable() {
					const parts: Uint8Array[] = [];
					return {
						async write(p: BufferSource) {
							parts.push(new Uint8Array(p as ArrayBuffer));
						},
						async close() {
							if (control.hold && dname === 'crema-history-curves') await control.hold;
							if (control.quota && dname === 'crema-history-curves') throw new DOMException('full', 'QuotaExceededError');
							if (dname === 'crema-history-curves') control.events.push(`close ${name}`);
							const total = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
							let o = 0;
							for (const p of parts) {
								total.set(p, o);
								o += p.length;
							}
							files.set(name, total);
						},
						async abort() {}
					};
				},
				async getFile() {
					const f = files.get(name);
					if (!f) throw notFound();
					return { size: f.length, lastModified: 1, arrayBuffer: async () => f.slice().buffer };
				}
			};
		},
		async removeEntry(name: string) {
			if (control.failRemove) throw new DOMException('held', 'NoModificationAllowedError');
			if (!files.delete(name)) throw notFound();
		},
		async *keys() {
			yield* [...files.keys()];
		}
	});
	const root = {
		async removeEntry(name: string) {
			control.events.push(`remove ${name}`);
			if (!dirs.delete(name)) throw notFound();
		},
		async getDirectoryHandle(name: string, opts?: { create?: boolean }) {
			if (!dirs.has(name)) {
				if (!opts?.create) throw notFound();
				dirs.set(name, new Map());
			}
			return dirOf(name, dirs.get(name)!);
		}
	};
	const fsRoot = async () => root as unknown as FileSystemDirectoryHandle;
	const curves = () => dirs.get('crema-history-curves') ?? new Map<string, Uint8Array>();
	const copyBytes = () => [...(dirs.get('crema-history-snapshot')?.values() ?? [])].reduce((n, b) => n + b.length, 0);
	const copies = () => [...(dirs.get('crema-history-snapshot')?.values() ?? [])].filter((b) => b.length > 0).length;
	const opts: HistoryStoreOptions = { snapshotRoot: fsRoot, archiveRoot: fsRoot, archiveDelayMs: 60_000 };
	return { control, curves, copies, copyBytes, opts, fsRoot };
}

