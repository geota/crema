/**
 * Fixtures for the W0 browser tests (#310 PLAN W0).
 *
 * - `profile`: a browser of the test's own. On desktop, a persistent context
 *   in a fresh temp dir, so OPFS is real files on disk and nothing is shared.
 *   On Android (`android-chrome`), Chrome's own profile over CDP.
 * - `tab()`: a new page on the harness with its Worker started.
 * - `gen()`: a pool generation no other test uses, so tests that share a
 *   profile (Android) never share a pool directory.
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test as base, chromium, expect, type BrowserContext, type CDPSession, type Page } from '@playwright/test';

export { expect };

export type W0Event = { event: string; [k: string]: unknown };
export type Outcome<T = unknown> = { ok: boolean; value?: T; error?: { code?: string; message?: string; [k: string]: unknown } };

export type Tab = {
	page: Page;
	/** The Worker's start: `{ sqlite, specs, ms }`. */
	info: { sqlite: string; specs: unknown[]; ms: number };
	events: W0Event[];
	/** One Worker op; throws on its error. */
	call<T = unknown>(op: string, args?: Record<string, unknown>): Promise<T>;
	/** One Worker op, its failure as data. */
	try<T = unknown>(op: string, args?: Record<string, unknown>): Promise<Outcome<T>>;
	/** Resolves at the first event that `pred` accepts, from now or already seen after `since`. */
	waitEvent(pred: (e: W0Event) => boolean, timeoutMs?: number, since?: number): Promise<W0Event>;
	/** A CDP session on the page (Chromium only). */
	cdp(): Promise<CDPSession>;
	/** Kills the renderer through CDP `Page.crash`, Worker and all (Chromium only). */
	crash(): Promise<void>;
};

export const HARNESS = '/tests/browser/harness/index.html';
/** Where the harness is served (playwright.config.ts); on Android through `adb reverse`. */
export const BASE = 'http://127.0.0.1:4318';

export function isAndroid(projectName: string): boolean {
	return projectName === 'android-chrome';
}

const used = new Set<number>();

async function openTab(ctx: BrowserContext): Promise<Tab> {
	const page = await ctx.newPage();
	const events: W0Event[] = [];
	const waiters: { pred: (e: W0Event) => boolean; resolve: (e: W0Event) => void }[] = [];
	await page.exposeBinding('__w0Event', (_src, e: W0Event) => {
		events.push(e);
		for (const w of [...waiters]) {
			if (w.pred(e)) {
				waiters.splice(waiters.indexOf(w), 1);
				w.resolve(e);
			}
		}
	});
	await page.goto(BASE + HARNESS);
	await page.waitForFunction(() => document.body.dataset.ready === '1');
	const info = (await page.evaluate(() => window.w0.start())) as Tab['info'];
	let session: CDPSession | null = null;
	return {
		page,
		info,
		events,
		call: <T>(op: string, args?: Record<string, unknown>) =>
			page.evaluate(([op, args]) => window.w0.call(op, args), [op, args] as const) as Promise<T>,
		try: <T>(op: string, args?: Record<string, unknown>) =>
			page.evaluate(([op, args]) => window.w0.try(op, args), [op, args] as const) as Promise<Outcome<T>>,
		waitEvent(pred, timeoutMs = 30_000, since = events.length) {
			const seen = events.slice(since).find(pred);
			if (seen) return Promise.resolve(seen);
			return new Promise((resolve, reject) => {
				const w = { pred, resolve };
				waiters.push(w);
				setTimeout(() => {
					const i = waiters.indexOf(w);
					if (i >= 0) {
						waiters.splice(i, 1);
						reject(new Error('no such event in time'));
					}
				}, timeoutMs);
			});
		},
		async cdp() {
			session ??= await ctx.newCDPSession(page);
			return session;
		},
		async crash() {
			session ??= await ctx.newCDPSession(page);
			// The command never answers: the renderer is gone.
			const crashed = page.waitForEvent('crash', { timeout: 15_000 });
			void session.send('Page.crash').catch(() => {});
			await crashed;
		}
	};
}

export const test = base.extend<{
	profile: BrowserContext;
	tab: () => Promise<Tab>;
	gen: () => number;
}>({
	profile: async ({ playwright, browserName, baseURL }, use, testInfo) => {
		if (isAndroid(testInfo.project.name)) {
			const browser = await chromium.connectOverCDP(
				process.env.W0_ANDROID_CDP ?? 'http://127.0.0.1:9222'
			);
			const ctx = browser.contexts()[0];
			const before = new Set(ctx.pages());
			await use(ctx);
			for (const p of ctx.pages()) if (!before.has(p)) await p.close().catch(() => {});
			await browser.close().catch(() => {});
			return;
		}
		const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'crema-w0-'));
		const ctx = await playwright[browserName].launchPersistentContext(dir, { headless: true, baseURL, ...(browserName === 'chromium' ? { channel: process.env.W0_CHANNEL ?? 'chromium' } : {}) });
		// A page that stays open: the browser quits when its last tab closes,
		// and the durability test closes every tab it crashes.
		const keeper = ctx.pages()[0] ?? (await ctx.newPage());
		await use(ctx);
		await keeper.close().catch(() => {});
		await ctx.close();
		// The temp profile only: `dir` is the mkdtemp above.
		fs.rmSync(dir, { recursive: true, force: true });
	},
	tab: async ({ profile }, use) => {
		await use(() => openTab(profile));
	},
	// eslint-disable-next-line no-empty-pattern
	gen: async ({}, use) => {
		await use(() => {
			for (;;) {
				const g = 1000 + Math.floor(Math.random() * 1_000_000_000);
				if (!used.has(g)) {
					used.add(g);
					return g;
				}
			}
		});
	}
});

export const dbDir = (g: number) => `.crema-history-db-${g}`;
export const indexDir = (g: number) => `.crema-history-index-${g}`;

/** A sleep in the test process. */
export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));


export type Listed = { path: string; size: number; sha: string };

/**
 * Every OPFS file under the test's own pool directories (`dbDir(g)`,
 * `indexDir(g)`), with sizes and hashes; a directory that doesn't exist lists
 * nothing.
 *
 * The Worker walks only these. Tests that run at once may share an OPFS
 * (Android; WebKit, which keeps one OPFS for every profile on the machine),
 * and WebKit fails a read with `NotFoundError` when the file is written after
 * `getFile()`: a walk of the whole root reads other tests' files while they
 * write them, and failed now and then (#310).
 */
export async function listFiles(t: Tab, dir: string, ...more: string[]): Promise<Listed[]> {
	return t.call<Listed[]>('opfsList', { dirs: [dir, ...more] });
}

/** Every file in `before` is in `after`, byte for byte. */
export function expectKept(before: Listed[], after: Listed[], what: string) {
	const now = new Map(after.map((e) => [e.path, e]));
	for (const e of before) {
		const a = now.get(e.path);
		expect(a, `${what}: ${e.path} is gone`).toBeDefined();
		expect(a!.sha, `${what}: ${e.path} changed`).toBe(e.sha);
	}
}

export type Health = { health: 'ok' | 'lost' | 'unavailable' | 'too_new'; reason: string; attempts?: number };

/**
 * A stage-2 boot with the marker `done` (SPEC §8's table), the smallest
 * model of what W1 will do: install `crema-db` with the backoff, look for the
 * marker's file in `poolInfo().files` (REVIEW-444 note 7: `cantOpen` doesn't
 * tell absent from unopenable), then open it with `create: false`.
 * Throwaway: W1 writes the real one.
 */
export async function bootStage2(
	t: Tab,
	generation: number,
	opts: { file?: string; journal?: string; budgetMs?: number } = {}
): Promise<Health> {
	const file = opts.file ?? 'history.db';
	const inst = await t.call<{ ok: boolean; attempts: number; error?: { code: string } }>('installRetry', {
		pool: 'db',
		generation,
		budgetMs: opts.budgetMs ?? 30_000
	});
	if (!inst.ok) return { health: 'unavailable', reason: inst.error!.code, attempts: inst.attempts };
	const info = await t.call<{ files: string[] }>('poolInfo', { pool: 'db' });
	if (!info.files.includes(`/${file}`)) return { health: 'lost', reason: 'absent', attempts: inst.attempts };
	const o = await t.try('open', {
		h: 'boot',
		opts: { place: 'db', file, role: 'authority', journal: opts.journal ?? 'truncate' }
	});
	if (o.ok) return { health: 'ok', reason: 'opened', attempts: inst.attempts };
	const code = String(o.error?.code);
	if (code === 'corrupt' || code === 'foreign') return { health: 'lost', reason: code, attempts: inst.attempts };
	if (code === 'tooNew' || code === 'readOnlyTooNew') return { health: 'too_new', reason: code };
	return { health: 'unavailable', reason: code, attempts: inst.attempts };
}

/** A truth file `history.db` in `crema-db` generation `g`, with `k` rows at version 0, left open as `h`. */
export async function newTruth(t: Tab, g: number, opts: { file?: string; k?: number; h?: string; journal?: string } = {}) {
	await t.call('install', { pool: 'db', generation: g });
	await t.call('recover', {
		h: opts.h ?? 'db',
		opts: { place: 'db', file: opts.file ?? 'history.db', generation: 1, journal: opts.journal ?? 'truncate' }
	});
	await t.call('writeVersion', { h: opts.h ?? 'db', v: 0, k: opts.k ?? 20 });
}
