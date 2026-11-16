/**
 * The R1w test page (#310 PLAN R1w): the backup seam's SQLite source over the
 * app's own `HistoryDbClient` and history Worker, with real Web Locks,
 * `BroadcastChannel`, OPFS and IndexedDB. Query: `?g=<pool generation>`: it
 * opens `history.db` in `crema-db` generation `g`, which a test makes first
 * (W0's harness page), with its details in this profile's `crema-history`.
 *
 * `window.r1w.restore` can stop after its `n`th row batch has landed, until
 * `go()`, so a test can kill the owner tab between batches.
 */
import type { ChannelLike, LockManagerLike } from '../../../src/lib/history-db/ports.ts';
import { HistoryDbClient, type ClientStatus, type WorkerLike } from '../../../src/lib/history-db/client.ts';
import type { Write } from '../../../src/lib/history-db/protocol.ts';
import { HistoryDb, detailsOf, rowOf } from '../../../src/lib/history/history-db.ts';
import type { StoredShot } from '../../../src/lib/history/model.ts';
import type { ShotSummary } from '../../../src/lib/core/crema-core.ts';
import { SqliteSource, type HistoryDbPort } from '../../../src/lib/history-source/sqlite.ts';
import { clientPort } from '../../../src/lib/history-source/port.ts';
import { idbDetails } from '../../../src/lib/history-source/details.ts';
import type { RestoreShotsInput } from '../../../src/lib/history-source/types.ts';

const g = Number(new URLSearchParams(location.search).get('g') ?? 0);

const client = new HistoryDbClient({
	stage: 2,
	expect: { role: 'authority', file: 'history.db', generation: g },
	target: { pool: 'db', generation: g, file: 'history.db', role: 'authority', create: false, journal: 'truncate' },
	build: 'r1w-test',
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
client.start();

/** The page has no core wasm: a summary from the counts alone, which is all a restore's rows need here. */
const summarize = (s: StoredShot): ShotSummary =>
	({ v: 1, sampleCount: s.record.samples.length, brewSampleCount: 0, peakPressure: 0, peakTemp: 0 }) as ShotSummary;

// Stage 2 finds `crema-history` there; this profile is the test's own, so make it.
const details = idbDetails(new HistoryDb({ expectExisting: () => false }));

/** Stop after the `at`th upsert lands, until `go`. */
const gate = { at: 0, n: 0, paused: false, go: () => {} };
const base = clientPort(client);
const port: HistoryDbPort = {
	...base,
	async write(w: Write) {
		const out = await base.write(w);
		if (w.kind === 'upsert' && gate.at > 0 && ++gate.n === gate.at) {
			gate.paused = true;
			await new Promise<void>((r) => (gate.go = r));
			gate.paused = false;
		}
		return out;
	}
};
const source = new SqliteSource({ db: port, details, summarize, batch: 2 });

const err = (e: unknown) => ({ ok: false, code: (e as { code?: string }).code ?? 'js', message: String((e as Error).message ?? e) });

const r1w = {
	tab: client.tab,
	status: () => client.status,
	/** `shots`, whole: details, then rows, as a capture writes them. */
	async seed(shots: StoredShot[]) {
		await details.put(shots.map(detailsOf), 'keep');
		return client.write({ kind: 'upsert', rows: shots.map((s) => ({ rowJson: JSON.stringify(rowOf(s, summarize(s))) })) });
	},
	/** Step 7 of a restore; with `pauseAfter`, stops after that many row batches until {@link go}. */
	async restore(input: Omit<RestoreShotsInput, 'backupIds'> & { backupIds: string[] }, pauseAfter = 0) {
		gate.at = pauseAfter;
		gate.n = 0;
		try {
			return { ok: true, value: await source.restoreShots({ ...input, backupIds: new Set(input.backupIds) }) };
		} catch (e) {
			return err(e);
		}
	},
	paused: () => gate.paused,
	go: () => gate.go(),
	/** Every row, by id: its rating. */
	async rows(): Promise<Record<string, number>> {
		const out: Record<string, number> = {};
		const ids = [...(await source.listedIds())];
		for (let i = 0; i < ids.length; i += 50) {
			const got = await client.read<{ id: string; rowJson: string }[]>('get', ids.slice(i, i + 50));
			for (const r of got) out[r.id] = (JSON.parse(r.rowJson) as StoredShot).metadata.rating ?? 0;
		}
		return out;
	},
	/** Which of `ids` have details. */
	async withDetails(ids: string[]) {
		return [...(await details.get(ids)).keys()].sort();
	},
	stop: () => client.stop(),
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

(window as unknown as { r1w: typeof r1w }).r1w = r1w;
document.body.dataset.ready = '1';
