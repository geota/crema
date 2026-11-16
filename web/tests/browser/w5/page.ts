/**
 * The W5 test page (#310 PLAN W5): the app's boot (`stage.ts`), its flip in a
 * real history Worker (`flip.ts` over `sahpool`, from `crema-index` into
 * `crema-db`), the done-record at the OPFS root and the marker in
 * `localStorage`, then the app's client on the stage the boot chose. The
 * P3 store is a `crema-history` this page seeds with B1's rows. Every flip
 * step is reported to Playwright (`__w5Step`), which may kill the tab there.
 */
import type { ChannelLike, LockManagerLike } from '../../../src/lib/history-db/ports.ts';
import { HistoryDbClient, type ClientStatus, type WorkerLike } from '../../../src/lib/history-db/client.ts';
import { chooseStage, type StageChoice } from '../../../src/lib/history-db/stage.ts';
import { opfsDoneRecordReader, readMarker } from '../../../src/lib/history-db/marker.ts';
import { canonical } from '../../../src/lib/history-db/flip.ts';
import { HistoryDb, META_MIGRATION, shotOfRow, type RowRecord } from '../../../src/lib/history/history-db.ts';
import { rows as benchRows } from '../../../bench/rows.ts';

declare global {
	interface Window {
		__w5Step?: (step: string) => Promise<void>;
	}
}

const makeWorker = () =>
	new Worker(new URL('../../../src/lib/history-db/worker.ts', import.meta.url), { type: 'module' }) as unknown as WorkerLike;
const channel = (name: string) => new BroadcastChannel(name) as unknown as ChannelLike;
const locks = navigator.locks as unknown as LockManagerLike;

let client: HistoryDbClient | null = null;
let choice: StageChoice | null = null;

const ALL = { text: '', sinceMs: null, profile: null, bean: null, method: null, tagsAll: [], pinnedOnly: false, sort: { key: 'date', dir: 'desc' } };

function until(c: HistoryDbClient, health: ClientStatus['health'], ms: number): Promise<void> {
	return new Promise((resolve, reject) => {
		if (c.status.health === health) return resolve();
		const off = c.onStatus((s) => {
			if (s.health === health) {
				off();
				resolve();
			}
		});
		setTimeout(() => {
			off();
			reject(new Error(`health ${c.status.health} (${c.status.reason}) never became ${health}`));
		}, ms);
	});
}

async function allRows(c: HistoryDbClient): Promise<Map<string, string>> {
	const out = new Map<string, string>();
	let after = 0;
	for (;;) {
		const page = await c.read<{ rows: { rid: number; id: string }[] }>('syncRows', { afterRid: after, limit: 1000 });
		if (page.rows.length === 0) break;
		const ids = page.rows.map((r) => r.id);
		for (let i = 0; i < ids.length; i += 200) {
			for (const g of await c.read<{ id: string; rowJson: string }[]>('get', ids.slice(i, i + 200))) out.set(g.id, canonical(JSON.parse(g.rowJson)));
		}
		after = page.rows[page.rows.length - 1].rid;
	}
	return out;
}

async function dirNames(name: string): Promise<string[] | null> {
	const root = await navigator.storage.getDirectory();
	try {
		const d = await root.getDirectoryHandle(name);
		const out: string[] = [];
		for await (const k of (d as unknown as { keys(): AsyncIterable<string> }).keys()) out.push(k);
		return out.sort();
	} catch {
		return null;
	}
}

const h5 = {
	/** `crema-history` with `n` B1 rows, migrated, as P3w leaves it. */
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
	/** A fresh boot's choice (the service worker's window count is `windows`). */
	async boot(o: { windows?: number | null; allowFlip?: boolean } = {}) {
		const t0 = performance.now();
		choice = await chooseStage({
			storage: localStorage,
			locks,
			doneRecord: opfsDoneRecordReader(),
			windowCount: async () => (o.windows === undefined ? 1 : o.windows),
			makeWorker,
			channel,
			now: () => Date.now(),
			build: 'w5-test',
			journal: 'exclusiveWal',
			allowFlip: o.allowFlip ?? true,
			onFlipStep: (s) => void window.__w5Step?.(s),
			log: (l) => console.info(l)
		});
		return { stage: choice.stage, reason: choice.reason, flip: choice.flip ?? null, file: choice.target.file, ms: performance.now() - t0 };
	},
	/** The client on the chosen stage, until it answers. */
	async open(ms = 60_000) {
		const c = new HistoryDbClient({
			stage: choice!.stage,
			expect: choice!.expect,
			target: choice!.target,
			build: 'w5-test',
			makeWorker,
			channel,
			locks,
			storage: localStorage
		});
		client = c;
		c.start();
		await until(c, 'ok', ms);
		return c.status.health;
	},
	health: () => client?.status ?? null,
	count: () => client!.read('count', ALL),
	stats: () => client!.read('stats', ALL),
	search: (text: string) => client!.read('count', { ...ALL, text }),
	/** Rows of `history.db` (or the index) that differ from the P3 store's, deep, and both counts. */
	async compare(): Promise<{ db: number; p3: number; differ: string[] }> {
		const got = await allRows(client!);
		const p3 = new HistoryDb({ expectExisting: () => true });
		const rows = await p3.allRows();
		p3.close();
		const differ: string[] = [];
		for (const r of rows) if (got.get(r.id) !== canonical(r)) differ.push(r.id);
		return { db: got.size, p3: rows.length, differ: differ.slice(0, 10) };
	},
	marker: () => readMarker(localStorage),
	doneRecord: async () => (await opfsDoneRecordReader()!.read()) ?? null,
	files: () => dirNames('.crema-history-db'),
	stop: () => client?.stop()
};

(window as unknown as { h5: typeof h5 }).h5 = h5;
document.body.dataset.ready = '1';
