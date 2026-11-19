/**
 * The real upgrade (#310): every web user comes from 0.10.0, which keeps the
 * history in the pre-P3 store (`crema.history.v2` in `localStorage`, the
 * curves in IndexedDB `crema-history-series`). On the production build
 * (`pnpm build`, served as Pages serves it, with its service worker), a
 * profile shaped that way is opened and relaunched:
 *
 * - P3's migration to `crema-history` at the first launch, then the flip to
 *   `history.db` at the next (two launches, by design: the flip needs a
 *   migrated P3 store, and runs before the store boots): every shot, every
 *   field and every curve there, with the count, a search and the stats the
 *   stage-1 index gave;
 * - the pre-P3 store and the P3 store left as they were;
 * - a launch after that changes nothing;
 * - the same with a shot captured during the first launch, and with the tab
 *   killed mid-migration and mid-flip, then relaunched.
 *
 * `history.db` is read through the build's own history Worker, driven by its
 * protocol from a page of the same origin with the app closed, so nothing of
 * the test's own touches the files.
 *
 * 0.10.0 caps the pre-P3 store at 300 rows, so 300 is the real case; 5,000
 * is a stress case no 0.10.0 profile holds.
 *
 * Runs with `pnpm test:pwa`, after `pnpm build`.
 */
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { AddressInfo } from 'node:net';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { rows as benchRows } from '../../bench/rows.ts';

const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const build = path.join(web, 'build');

const MIME: Record<string, string> = {
	'.html': 'text/html; charset=utf-8',
	'.js': 'text/javascript; charset=utf-8',
	'.css': 'text/css; charset=utf-8',
	'.json': 'application/json',
	'.webmanifest': 'application/manifest+json',
	'.wasm': 'application/wasm',
	'.png': 'image/png',
	'.svg': 'image/svg+xml',
	'.ico': 'image/x-icon',
	'.woff2': 'font/woff2'
};

type Site = { url: string; close: () => Promise<void> };

async function serve(): Promise<Site> {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), 'crema-upgrade-'));
	fs.cpSync(build, root, { recursive: true });
	const server = http.createServer((req, res) => {
		const pathname = decodeURIComponent(new URL(req.url ?? '/', 'http://x').pathname);
		let file = path.join(root, path.normalize(pathname));
		if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(root, 'index.html');
		res.writeHead(200, { 'content-type': MIME[path.extname(file)] ?? 'application/octet-stream', 'cache-control': 'no-cache' });
		fs.createReadStream(file).pipe(res);
	});
	await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
	const { port } = server.address() as AddressInfo;
	return {
		url: `http://127.0.0.1:${port}`,
		close: () =>
			new Promise<void>((resolve) => {
				server.closeAllConnections();
				server.close(() => resolve());
				fs.rmSync(root, { recursive: true, force: true });
			})
	};
}

/** The build's history Worker, as `HistoryDbClient` creates it. */
function workerScript(): string {
	const dir = path.join(build, '_app/immutable/workers');
	const f = fs.readdirSync(dir).find((n) => /^worker-.*\.js$/.test(n) && fs.readFileSync(path.join(dir, n), 'utf8').includes('crema-history-rpc'));
	if (!f) throw new Error('no history Worker in the build');
	return `/_app/immutable/workers/${f}`;
}

/** A page of the origin that isn't the app: a static file. */
const BLANK = '/sw-window-count.js';

/** A curve of `n` samples, real-shaped enough for the core's summary. */
function curve(n: number, seed: number): unknown[] {
	const out: unknown[] = [];
	let w = 0;
	for (let i = 0; i < n; i++) {
		const p = Math.min(9, i * 0.3) + ((seed + i) % 7) * 0.01;
		const f = Math.min(2.2, i * 0.08);
		w += f * 0.25;
		out.push({
			elapsed: i * 250,
			sample: {
				sampleTime: i,
				groupPressure: p,
				groupFlow: f,
				headTemp: 92,
				mixTemp: 91.5,
				setMixTemp: 92,
				setHeadTemp: 92,
				setGroupPressure: 9,
				setGroupFlow: 2,
				frameNumber: i < 10 ? 0 : 1,
				steamTemp: 150
			},
			scaleWeight: w,
			scaleFlowWeight: f,
			dispensedVolume: w * 1.1
		});
	}
	return out;
}

interface Legacy {
	rows: Record<string, unknown>[];
	series: Record<string, unknown[]>;
}

/** 0.10.0's store: rows without samples in the blob, every machine shot's curve in `crema-history-series`. */
function legacy(n: number): Legacy {
	const rows: Record<string, unknown>[] = [];
	const series: Record<string, unknown[]> = {};
	benchRows(n).forEach((r, i) => {
		const { summary: _s, ...rest } = r as Record<string, unknown>;
		const record = (rest.record ?? { duration: 30_000 }) as { duration: number };
		rows.push({ ...rest, record: { duration: record.duration, samples: [] } });
		if (rest.brewMethod == null) series[String(rest.id)] = curve(40 + (i % 30), i);
	});
	return { rows, series };
}

async function plant(page: Page, site: Site, l: Legacy): Promise<void> {
	await page.goto(site.url + BLANK);
	await page.evaluate((rows) => localStorage.setItem('crema.history.v2', JSON.stringify(rows)), l.rows);
	// The curves in parts: one message of every curve is too big to pass.
	const entries = Object.entries(l.series);
	for (let i = 0; i < entries.length; i += 250) {
		await page.evaluate(async (part) => {
			const db = await new Promise<IDBDatabase>((resolve, reject) => {
				const r = indexedDB.open('crema-history-series', 1);
				r.onupgradeneeded = () => r.result.createObjectStore('series');
				r.onsuccess = () => resolve(r.result);
				r.onerror = () => reject(r.error);
			});
			await new Promise<void>((resolve, reject) => {
				const tx = db.transaction('series', 'readwrite');
				for (const [id, s] of part) tx.objectStore('series').put(s, id);
				tx.oncomplete = () => resolve();
				tx.onerror = () => reject(tx.error);
			});
			db.close();
		}, entries.slice(i, i + 250));
	}
}

/** A frozen tab's finished shot, which the first launch records once the history is ready. */
async function plantCapture(page: Page, id: string): Promise<void> {
	await page.evaluate((id) => {
		const shot = {
			id,
			formatVersion: 3,
			completedAt: Date.now(),
			profileName: 'Captured during the upgrade',
			metadata: { notes: 'captured' },
			record: { duration: 30_000, samples: [] as unknown[] }
		};
		for (let i = 0; i < 60; i++) {
			shot.record.samples.push({
				elapsed: i * 250,
				sample: { sampleTime: i, groupPressure: 8, groupFlow: 2, headTemp: 92, mixTemp: 92, setMixTemp: 92, setHeadTemp: 92, setGroupPressure: 9, setGroupFlow: 2, frameNumber: 1, steamTemp: 150 },
				scaleWeight: i * 0.6,
				scaleFlowWeight: 2,
				dispensedVolume: i * 0.7
			});
		}
		localStorage.setItem('crema-frozen-shots', JSON.stringify([{ captured: shot, beanId: null, dose: null, live: false }]));
	}, id);
}

/** {@link stores}, from a page of the origin of its own. */
async function storesNow(ctx: BrowserContext, site: Site): ReturnType<typeof stores> {
	const page = await ctx.newPage();
	await page.goto(site.url + BLANK);
	try {
		return await stores(page);
	} finally {
		await page.close();
	}
}

/** What the profile holds outside `history.db`: the pre-P3 store, P3's rows and meta, the markers. */
async function stores(page: Page): Promise<{ legacy: string; series: string; p3: string | null; marker: unknown; p3Marker: unknown }> {
	return page.evaluate(async () => {
		const open = (name: string) =>
			new Promise<IDBDatabase | null>((resolve) => {
				const r = indexedDB.open(name);
				r.onupgradeneeded = () => {
					r.transaction?.abort();
					resolve(null);
				};
				r.onsuccess = () => resolve(r.result);
				r.onerror = () => resolve(null);
			});
		const all = (db: IDBDatabase, store: string) =>
			new Promise<unknown>((resolve) => {
				const tx = db.transaction(store, 'readonly');
				const k = tx.objectStore(store).getAllKeys();
				const v = tx.objectStore(store).getAll();
				tx.oncomplete = () => resolve({ k: k.result, v: v.result });
			});
		const s = await open('crema-history-series');
		const series = s ? JSON.stringify(await all(s, 'series')) : '';
		s?.close();
		const p = await open('crema-history');
		const p3 = p && p.objectStoreNames.contains('rows') ? JSON.stringify({ rows: await all(p, 'rows'), meta: await all(p, 'meta') }) : null;
		p?.close();
		return {
			legacy: localStorage.getItem('crema.history.v2') ?? '',
			series,
			p3,
			marker: JSON.parse(localStorage.getItem('crema.history.sqlite') ?? 'null'),
			p3Marker: JSON.parse(localStorage.getItem('crema.history.store') ?? 'null')
		};
	});
}

/** The app, launched, until its history has settled: P3 done, and, when `flipped`, the marker done. */
async function launch(ctx: BrowserContext, site: Site, until: 'p3' | 'flipped', killWhen?: (page: Page) => Promise<boolean>): Promise<Page | null> {
	const page = await ctx.newPage();
	await page.goto(site.url);
	const settled = async () => {
		const s = await page.evaluate(() => ({
			p3: JSON.parse(localStorage.getItem('crema.history.store') ?? 'null')?.state ?? null,
			sqlite: JSON.parse(localStorage.getItem('crema.history.sqlite') ?? 'null')?.state ?? null
		}));
		return until === 'p3' ? s.p3 === 'done' : s.sqlite === 'done';
	};
	const t0 = Date.now();
	for (;;) {
		if (killWhen && (await killWhen(page).catch(() => false))) {
			await page.close();
			return null;
		}
		if (await settled().catch(() => false)) break;
		if (Date.now() - t0 > 120_000) throw new Error(`the history never reached ${until}`);
		await page.waitForTimeout(killWhen ? 5 : 200);
	}
	// Let the boot's own writes land (a recorded capture, its curve).
	await page.waitForTimeout(1_500);
	return page;
}

/** `history.db` (or the stage-1 index), read through the build's Worker with the app closed. */
async function readTruth(ctx: BrowserContext, site: Site, stage: 1 | 2): Promise<{ rows: Record<string, string>; count: number; search: number; stats: unknown; details: Record<string, number> }> {
	const page = await ctx.newPage();
	await page.goto(site.url + BLANK);
	const out = await page.evaluate(
		async ({ script, stage }) => {
			const marker = JSON.parse(localStorage.getItem('crema.history.sqlite') ?? 'null');
			const file = stage === 1 ? 'history-index.db' : marker.generation === 0 ? 'history.db' : `history-${marker.generation}.db`;
			const target =
				stage === 1
					? { pool: 'index', generation: 0, file, role: 'index', create: true, journal: 'exclusiveWal' }
					: { pool: 'db', generation: marker.poolGeneration, file, role: 'authority', create: false, journal: 'exclusiveWal' };
			const expect = stage === 1 ? { role: 'index', file } : { role: 'authority', file, generation: marker.poolGeneration };
			const w = new Worker(script, { type: 'module' });
			const tab = 'upgrade-probe';
			let seq = 0;
			const waiting = new Map<string, (v: { ok: boolean; value?: unknown; error?: unknown }) => void>();
			let ready: () => void = () => {};
			const owned = new Promise<void>((r) => (ready = r));
			w.onmessage = (e) => {
				const m = e.data as { t: string; owner?: boolean; info?: { health: string }; id?: string; ok?: boolean; value?: unknown; error?: unknown };
				if (m.t === 'state' && m.owner && m.info?.health === 'ok') ready();
				if (m.t === 'reply' && m.id) waiting.get(m.id)?.({ ok: !!m.ok, value: m.value, error: m.error });
			};
			w.postMessage({ t: 'config', tab, build: 'upgrade-probe', stage, target });
			w.postMessage({ t: 'acquire' });
			await Promise.race([owned, new Promise((_, rej) => setTimeout(() => rej(new Error('the history never opened')), 60_000))]);
			const read = (name: string, arg?: unknown) =>
				new Promise<unknown>((resolve, reject) => {
					const id = `${tab}:${++seq}`;
					waiting.set(id, (r) => (r.ok ? resolve(r.value) : reject(new Error(JSON.stringify(r.error)))));
					w.postMessage({ t: 'req', v: 2, build: 'upgrade-probe', stage, tab, seq, id, expect, op: 'read', name, arg });
				});
			const ALL = { text: '', sinceMs: null, profile: null, bean: null, method: null, tagsAll: [], pinnedOnly: false, sort: { key: 'date', dir: 'desc' } };
			// The index answers once it has caught up with the P3 store.
			for (let i = 0; i < 300; i++) {
				try {
					await read('count', ALL);
					break;
				} catch {
					await new Promise((r) => setTimeout(r, 200));
				}
			}
			const rows: Record<string, string> = {};
			let after = 0;
			for (;;) {
				const page = (await read('syncRows', { afterRid: after, limit: 1000 })) as { rows: { rid: number; id: string }[] };
				if (page.rows.length === 0) break;
				const ids = page.rows.map((r) => r.id);
				for (let i = 0; i < ids.length; i += 200) {
					for (const g of (await read('get', ids.slice(i, i + 200))) as { id: string; rowJson: string }[]) rows[g.id] = g.rowJson;
				}
				after = page.rows[page.rows.length - 1].rid;
			}
			const count = Number(await read('count', ALL));
			const search = Number(await read('count', { ...ALL, text: 'kenya' }));
			const stats = await read('stats', ALL);
			w.postMessage({ t: 'stop', n: 1 });
			await new Promise((r) => setTimeout(r, 300));
			w.terminate();
			// Every curve, by id, from `details` (where P3 put them, shared by both stages).
			const details: Record<string, number> = {};
			const db = await new Promise<IDBDatabase>((resolve, reject) => {
				const r = indexedDB.open('crema-history');
				r.onsuccess = () => resolve(r.result);
				r.onerror = () => reject(r.error);
			});
			await new Promise<void>((resolve) => {
				const tx = db.transaction('details', 'readonly');
				const all = tx.objectStore('details').getAll();
				tx.oncomplete = () => {
					for (const d of all.result as { id: string; samples: unknown[] }[]) details[d.id] = d.samples.length;
					resolve();
				};
			});
			db.close();
			return { rows, count, search, stats, details };
		},
		{ script: workerScript(), stage }
	);
	await page.close();
	return out;
}

/** A stored row's fields, as the old copy had them: what the migration keeps of a 0.10.0 row. */
function fieldsOf(rowJson: string): Record<string, unknown> {
	const { summary: _s, record, ...rest } = JSON.parse(rowJson) as Record<string, unknown> & { record: { duration: number } };
	return { ...rest, record: { duration: record.duration, samples: [] } };
}

/**
 * `v` as text with keys sorted and null fields left out: the migration's
 * coercion (`coerceStoredShot`) spells out the optional fields a 0.10.0 row
 * left out as null, and no tags as `[]`, which are the same values.
 */
const canonical = (v: unknown): string =>
	JSON.stringify(v, (_k, x) =>
		x && typeof x === 'object' && !Array.isArray(x)
			? Object.fromEntries(
					Object.keys(x)
						.sort()
						.filter((k) => x[k] !== null && !(Array.isArray(x[k]) && x[k].length === 0 && k === 'tags'))
						.map((k) => [k, x[k]])
				)
			: x
	);

test.beforeAll(() => {
	if (!fs.existsSync(path.join(build, 'sw.js'))) throw new Error(`${build}/sw.js is missing: run \`pnpm build\` first`);
});

test.beforeEach(({ browserName }) => {
	// One profile's OPFS per test; Playwright's WebKit shares one OPFS between profiles (tests/browser/README).
	test.skip(browserName !== 'chromium', 'the upgrade on OPFS, in Chrome');
});

for (const n of [300, 5_000]) {
	test(`0.10.0 with ${n.toLocaleString('en')} shots: P3, then history.db, every shot, field and curve; nothing else changed; a later launch changes nothing`, async ({ context }) => {
		test.setTimeout(300_000);
		const site = await serve();
		try {
			const l = legacy(n);
			const setup = await context.newPage();
			await plant(setup, site, l);
			const before = await stores(setup);
			await setup.close();

			// First launch: P3's migration (stage 1 needs it before a flip can run).
			const first = await launch(context, site, 'p3');
			await first!.close();
			const afterP3 = await storesNow(context, site);
			expect(afterP3.legacy).toBe(before.legacy);
			expect(afterP3.series).toBe(before.series);
			expect(afterP3.p3).not.toBeNull();
			const index = await readTruth(context, site, 1);
			expect(index.count).toBe(n);

			// The launch after: the flip.
			const second = await launch(context, site, 'flipped');
			await second!.close();
			const truth = await readTruth(context, site, 2);
			expect(Object.keys(truth.rows).length).toBe(n);
			for (const r of l.rows) {
				const got = truth.rows[String(r.id)];
				expect(got, String(r.id)).toBeDefined();
				expect(canonical(fieldsOf(got))).toBe(canonical(r));
				const want = l.series[String(r.id)]?.length ?? 0;
				expect(truth.details[String(r.id)] ?? 0, String(r.id)).toBe(want);
				expect((JSON.parse(got) as { summary: { sampleCount: number } }).summary.sampleCount).toBe(want);
			}
			expect(truth.count).toBe(index.count);
			expect(truth.search).toBe(index.search);
			expect(truth.stats).toEqual(index.stats);
			const afterFlip = await storesNow(context, site);
			expect(afterFlip.legacy).toBe(before.legacy);
			expect(afterFlip.series).toBe(before.series);
			expect(afterFlip.p3).toBe(afterP3.p3);

			// A third launch: nothing moves.
			const third = await launch(context, site, 'flipped');
			await third!.close();
			const again = await readTruth(context, site, 2);
			expect(again.rows).toEqual(truth.rows);
			const afterThird = await storesNow(context, site);
			expect(afterThird).toEqual(afterFlip);
		} finally {
			await site.close();
		}
	});
}

test('0.10.0, a shot captured during the first launch: it lands, and is on history.db after the flip', async ({ context }) => {
	test.setTimeout(300_000);
	const site = await serve();
	try {
		const l = legacy(300);
		const setup = await context.newPage();
		await plant(setup, site, l);
		await plantCapture(setup, 'captured-during-upgrade');
		await setup.close();
		(await launch(context, site, 'p3'))!.close();
		(await launch(context, site, 'flipped'))!.close();
		const truth = await readTruth(context, site, 2);
		expect(Object.keys(truth.rows).length).toBe(301);
		expect(truth.rows['captured-during-upgrade']).toBeDefined();
		expect(truth.details['captured-during-upgrade']).toBe(60);
	} finally {
		await site.close();
	}
});

test('0.10.0, the tab killed mid-migration and mid-flip, then relaunched: every shot and curve on history.db', async ({ context }) => {
	test.setTimeout(300_000);
	const site = await serve();
	try {
		const l = legacy(5_000);
		const setup = await context.newPage();
		await plant(setup, site, l);
		await setup.close();
		// Killed once P3's migration has begun writing, before it's done.
		const killed = await launch(context, site, 'p3', async (p) =>
			p.evaluate(async () => {
				const dbs = (await indexedDB.databases?.()) ?? [];
				return dbs.some((d) => d.name === 'crema-history') && JSON.parse(localStorage.getItem('crema.history.store') ?? 'null')?.state !== 'done';
			})
		);
		expect(killed).toBeNull();
		(await launch(context, site, 'p3'))!.close();
		// Killed once the flip's copy is in crema-db, before the marker.
		const killedFlip = await launch(context, site, 'flipped', async (p) =>
			p.evaluate(async () => {
				const root = await navigator.storage.getDirectory();
				for await (const name of (root as unknown as { keys(): AsyncIterable<string> }).keys()) if (name === '.crema-history-db') return localStorage.getItem('crema.history.sqlite') === null || JSON.parse(localStorage.getItem('crema.history.sqlite')!).state !== 'done';
				return false;
			})
		);
		expect(killedFlip).toBeNull();
		(await launch(context, site, 'flipped'))!.close();
		const truth = await readTruth(context, site, 2);
		expect(Object.keys(truth.rows).length).toBe(5_000);
		for (const r of l.rows) {
			const id = String(r.id);
			expect(canonical(fieldsOf(truth.rows[id]))).toBe(canonical(r));
			expect(truth.details[id] ?? 0).toBe(l.series[id]?.length ?? 0);
		}
	} finally {
		await site.close();
	}
});
