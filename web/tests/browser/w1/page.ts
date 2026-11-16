/**
 * The W1 test page (#310 PLAN W1): the app's own `HistoryDbClient` and history
 * Worker (`src/lib/history-db/`), with real Web Locks, `BroadcastChannel`,
 * `localStorage` and OPFS. Query: `?stage=1|2&g=<pool generation>`. Stage 2
 * opens `history.db` in `crema-db` generation `g`, which a test makes first
 * (W0's harness page); stage 1 creates `history-index.db` in `crema-index`.
 */
import type { ChannelLike, LockManagerLike } from '../../../src/lib/history-db/ports.ts';
import { HistoryDbClient, type ClientStatus, type WorkerLike } from '../../../src/lib/history-db/client.ts';
import type { Stage, Write } from '../../../src/lib/history-db/protocol.ts';
import { PENDING_KEY_PREFIX } from '../../../src/lib/history-db/names.ts';

const q = new URLSearchParams(location.search);
const stage = Number(q.get('stage') ?? 2) as Stage;
const g = Number(q.get('g') ?? 0);

// Stage 1's owner keeps the index in step with the P3 store, and answers only
// once it has read it (W2): an empty, migrated `crema-history`, as a fresh
// install's P3w leaves it.
if (stage === 1) {
	await new Promise<void>((resolve, reject) => {
		const req = indexedDB.open('crema-history', 1);
		req.onupgradeneeded = () => {
			for (const name of ['rows', 'details']) req.result.createObjectStore(name, { keyPath: 'id' });
			req.result.createObjectStore('meta');
		};
		req.onerror = () => reject(req.error);
		req.onsuccess = () => {
			const db = req.result;
			const tx = db.transaction('meta', 'readwrite');
			const meta = tx.objectStore('meta');
			const got = meta.get('migration');
			got.onsuccess = () => {
				if (got.result === undefined) meta.put({ state: 'done', sourceHash: '0:0', sourceIds: [], doneAt: 1 }, 'migration');
			};
			tx.oncomplete = () => {
				db.close();
				resolve();
			};
			tx.onerror = () => reject(tx.error);
		};
	});
}

const client = new HistoryDbClient({
	stage,
	expect: stage === 2 ? { role: 'authority', file: 'history.db', generation: g } : { role: 'index', file: 'history-index.db' },
	target:
		stage === 2
			? { pool: 'db', generation: g, file: 'history.db', role: 'authority', create: false, journal: 'truncate' }
			: { pool: 'index', generation: g, file: 'history-index.db', role: 'index', create: true, journal: 'exclusiveWal' },
	build: 'w1-test',
	makeWorker: () =>
		new Worker(new URL('../../../src/lib/history-db/worker.ts', import.meta.url), { type: 'module' }) as unknown as WorkerLike,
	channel: (name) => new BroadcastChannel(name) as unknown as ChannelLike,
	locks: navigator.locks as unknown as LockManagerLike,
	storage: localStorage,
	events: {
		addEventListener: (t, f) => (t === 'visibilitychange' ? document : window).addEventListener(t, f as EventListener),
		removeEventListener: (t, f) => (t === 'visibilitychange' ? document : window).removeEventListener(t, f as EventListener),
		visibility: () => document.visibilityState
	}
});

const statuses: ClientStatus[] = [];
client.onStatus((s) => statuses.push(s));
client.start();

const err = (e: unknown) => ({ ok: false, code: (e as { code?: string }).code ?? 'js', message: String((e as Error).message ?? e) });

const h1 = {
	tab: client.tab,
	status: () => client.status,
	statuses: () => statuses,
	read: (name: string, arg?: unknown) =>
		client.read(name as never, arg).then((value) => ({ ok: true, value }), err),
	write: (w: Write) => client.write(w).then((value) => ({ ok: true, value }), err),
	capture: (rowJson: string) => client.capture(rowJson).then((value) => ({ ok: true, value }), err),
	release: () => client.release(),
	acquire: () => client.acquire(),
	stop: () => client.stop(),
	pending: () => Object.keys(localStorage).filter((k) => k.startsWith(PENDING_KEY_PREFIX)),
	/** Waits until `pred` holds of the status, at most `ms`. */
	until: (want: Partial<ClientStatus>, ms = 20_000) =>
		new Promise<ClientStatus>((resolve, reject) => {
			const ok = (s: ClientStatus) => Object.entries(want).every(([k, v]) => (s as never)[k] === v);
			if (ok(client.status)) return resolve(client.status);
			const off = client.onStatus((s) => {
				if (ok(s)) {
					off();
					resolve(s);
				}
			});
			setTimeout(() => {
				off();
				reject(new Error(`status ${JSON.stringify(client.status)} never matched ${JSON.stringify(want)}`));
			}, ms);
		})
};

declare global {
	interface Window {
		h1: typeof h1;
	}
}
window.h1 = h1;
document.body.dataset.ready = '1';
