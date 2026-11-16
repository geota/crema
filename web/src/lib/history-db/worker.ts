/**
 * `#lib/history-db/worker.js` — the history Worker (#310 PLAN W1): the owner's
 * side (`owner.ts`) over the wasm store, on OPFS through `sahpool`. One per
 * tab, started by `HistoryDbClient`; the one holding the owner lock serves
 * every tab. On stage 1 it keeps the index in step with the P3 store, which it
 * reads itself (`index-sync.ts`, W2).
 */
import { serveWorker, type WorkerScope } from './owner.js';
import { wasmEngine } from './wasm-engine.js';
import type { LockManagerLike } from './ports.js';
import type { ChannelLike } from './ports.js';
import { IndexSync } from './index-sync.js';
import { p3Source } from './p3-source.js';
import { DONE_RECORD_NAME, P3_CHANNEL } from './names.js';

/**
 * The done-record (M8), at the OPFS root, written whole and flushed through a
 * sync access handle (a Worker's, which every browser with `sahpool` has).
 */
async function writeDoneRecord(text: string): Promise<void> {
	const root = await navigator.storage.getDirectory();
	const file = await root.getFileHandle(DONE_RECORD_NAME, { create: true });
	const h = await (file as unknown as { createSyncAccessHandle(): Promise<SyncHandle> }).createSyncAccessHandle();
	try {
		const bytes = new TextEncoder().encode(text);
		h.truncate(0);
		const wrote = h.write(bytes, { at: 0 });
		if (wrote !== bytes.length) throw new Error(`the done-record took ${wrote} of ${bytes.length} bytes`);
		h.flush();
	} finally {
		h.close();
	}
}

interface SyncHandle {
	truncate(n: number): void;
	write(b: Uint8Array, o: { at: number }): number;
	flush(): void;
	close(): void;
}

const channel = (name: string) => new BroadcastChannel(name) as unknown as ChannelLike;

serveWorker({
	scope: self as unknown as WorkerScope,
	engine: wasmEngine(),
	locks: navigator.locks as unknown as LockManagerLike,
	channel,
	indexSync: (d) =>
		new IndexSync({
			...d,
			source: p3Source(),
			channel: channel(P3_CHANNEL),
			log: (line) => console.info(`history-index: ${line}`)
		}),
	flip: {
		source: () => p3Source(),
		writeDoneRecord,
		estimate: async () => (navigator.storage?.estimate ? navigator.storage.estimate() : null),
		log: (line) => console.info(`history-flip: ${line}`)
	}
});
