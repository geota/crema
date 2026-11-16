/**
 * The W2 test page (#310 PLAN W2): the app's stage-1 `HistoryDbClient` and
 * history Worker over a `crema-history` this page seeds with B1's real-shaped
 * rows (`bench/rows.ts`), so the owner's Worker builds `history-index.db` from
 * it and keeps it in step. `window.h2` times the History list's reads through
 * the Worker, as the window makes them. Query: `?g=<index pool generation>`.
 */
import type { ChannelLike, LockManagerLike } from '../../../src/lib/history-db/ports.ts';
import { HistoryDbClient, type ClientStatus, type WorkerLike } from '../../../src/lib/history-db/client.ts';
import { HistoryDb, META_MIGRATION, shotOfRow, type RowRecord } from '../../../src/lib/history/history-db.ts';
import { rows as benchRows } from '../../../bench/rows.ts';

const q = new URLSearchParams(location.search);
const g = Number(q.get('g') ?? 0);

let client: HistoryDbClient | null = null;

function start(): HistoryDbClient {
	client = new HistoryDbClient({
		stage: 1,
		expect: { role: 'index', file: 'history-index.db' },
		target: { pool: 'index', generation: g, file: 'history-index.db', role: 'index', create: true, journal: 'exclusiveWal' },
		build: 'w2-test',
		makeWorker: () =>
			new Worker(new URL('../../../src/lib/history-db/worker.ts', import.meta.url), { type: 'module' }) as unknown as WorkerLike,
		channel: (name) => new BroadcastChannel(name) as unknown as ChannelLike,
		locks: navigator.locks as unknown as LockManagerLike,
		storage: localStorage
	});
	client.start();
	return client;
}

/** Waits until the client's health is `health`. */
function until(c: HistoryDbClient, health: ClientStatus['health'], ms: number): Promise<number> {
	const t0 = performance.now();
	return new Promise((resolve, reject) => {
		if (c.status.health === health) return resolve(0);
		const off = c.onStatus((s) => {
			if (s.health === health) {
				off();
				resolve(performance.now() - t0);
			}
		});
		setTimeout(() => {
			off();
			reject(new Error(`health ${c.status.health} (${c.status.reason}) never became ${health}`));
		}, ms);
	});
}

const ALL = { text: '', sinceMs: null, profile: null, bean: null, method: null, tagsAll: [], pinnedOnly: false, sort: { key: 'date', dir: 'desc' } };

async function timed<T>(f: () => Promise<T>): Promise<[number, T]> {
	const t0 = performance.now();
	const v = await f();
	return [performance.now() - t0, v];
}

const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

const h2 = {
	/** `crema-history` with `n` rows, migrated, as P3w leaves it. */
	async seed(n: number): Promise<void> {
		const db = new HistoryDb({ expectExisting: () => false });
		const rows = benchRows(n) as unknown as RowRecord[];
		for (let i = 0; i < rows.length; i += 1_000) {
			await db.putShots(
				rows.slice(i, i + 1_000).map((r) => {
					const { shot, summary } = shotOfRow(r);
					return { shot, details: false, summary };
				}),
				() => {
					throw new Error('rows only');
				}
			);
		}
		await db.setMeta(META_MIGRATION, { state: 'done', sourceHash: '0:0', sourceIds: [], doneAt: 1 });
		db.close();
	},
	/**
	 * Starts the client and waits for the index to answer. While it isn't
	 * yet (a full diff), a cheap read goes every few ms: the longest answer is
	 * how long a tab's read waited (the longest index call plus the Worker's
	 * own work beside it, reading the P3 store). The owner's upkeep times its
	 * longest index call itself (B1's longest transaction).
	 */
	async open(ms = 60_000): Promise<{ readyMs: number; waitMs: number; probes: number; longestCallMs: number }> {
		const c = start();
		const t0 = performance.now();
		let longest = 0;
		let probes = 0;
		let done = false;
		const probe = async () => {
			while (!done) {
				const [t] = await timed(() => c.read('indexState').catch(() => null));
				if (c.status.owner) {
					longest = Math.max(longest, t);
					probes++;
				}
				await new Promise((r) => setTimeout(r, 5));
			}
		};
		void probe();
		await until(c, 'ok', ms);
		done = true;
		const readyMs = performance.now() - t0;
		const sync = (await c.read('indexSync')) as { longestCallMs: number };
		return { readyMs, waitMs: longest, probes, longestCallMs: sync.longestCallMs };
	},
	/** From the client's start to the first page, over an index already built (B1's "open to first page"). */
	async openToFirstPage(): Promise<number> {
		const t0 = performance.now();
		const c = start();
		await until(c, 'ok', 30_000);
		await c.read('page', { query: ALL, anchor: 'start', limit: 64 });
		return performance.now() - t0;
	},
	/** B1's lines through the Worker, in ms. */
	async reads(): Promise<Record<string, number>> {
		const c = client!;
		const out: Record<string, number> = {};
		const sorts = ['date', 'rating', 'profile', 'bean'].flatMap((key) => (['desc', 'asc'] as const).map((dir) => ({ key, dir })));
		let worst = 0;
		for (const sort of sorts) {
			const query = { ...ALL, sort };
			const [t] = await timed(async () => {
				await c.read('count', query);
				return c.read('page', { query, anchor: 'start', limit: 64 });
			});
			worst = Math.max(worst, t);
		}
		out.first = worst;
		type Page = { last: string; rows: unknown[] };
		let page = (await c.read('page', { query: ALL, anchor: 'start', limit: 64 })) as Page;
		const next: number[] = [];
		for (let i = 0; i < 20; i++) {
			const [t, p] = await timed(() => c.read('page', { query: ALL, anchor: { after: page.last }, limit: 64 }) as Promise<Page>);
			next.push(t);
			page = p;
		}
		out.next = median(next);
		const count = Number(await c.read('count', ALL));
		[out.jump] = await timed(() => c.read('page', { query: ALL, anchor: { offset: Math.max(0, count - 64) }, limit: 64 }));
		const search: number[] = [];
		for (const text of ['turbo', 'kenya', 'sour', 'bloom']) {
			const query = { ...ALL, text };
			const [t] = await timed(async () => {
				await c.read('count', query);
				return c.read('page', { query, anchor: 'start', limit: 64 });
			});
			search.push(t);
		}
		out.search = Math.max(...search);
		[out.stats] = await timed(() => c.read('stats', ALL));
		[out.facets] = await timed(() => c.read('facets', ALL));
		return out;
	},
	count: () => client!.read('count', ALL),
	status: () => client?.status ?? null,
	stop: () => client?.stop()
};

(window as unknown as { h2: typeof h2 }).h2 = h2;
document.body.dataset.ready = '1';
