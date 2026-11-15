/**
 * `#lib/history-db/worker.js` — the history Worker (#310 PLAN W1): the owner's
 * side (`owner.ts`) over the wasm store, on OPFS through `sahpool`. One per
 * tab, started by `HistoryDbClient`; the one holding the owner lock serves
 * every tab.
 */
import { serveWorker, type WorkerScope } from './owner.js';
import { wasmEngine } from './wasm-engine.js';
import type { LockManagerLike } from './ports.js';
import type { ChannelLike } from './ports.js';

serveWorker({
	scope: self as unknown as WorkerScope,
	engine: wasmEngine(),
	locks: navigator.locks as unknown as LockManagerLike,
	channel: (name) => new BroadcastChannel(name) as unknown as ChannelLike
});
