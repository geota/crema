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
import { P3_CHANNEL } from './names.js';

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
		})
});
