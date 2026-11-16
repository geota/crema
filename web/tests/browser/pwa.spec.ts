/**
 * The production service worker (geota/crema#172): the app shell starts
 * offline, the wasm cores are precached, and a new deploy is offered, never
 * applied behind the user's back.
 *
 * Runs against `build/` from `pnpm build` (not the W0 harness), served here by
 * a small static server with adapter-static's SPA fallback, the way
 * Cloudflare Pages serves it. `pnpm test:pwa`; its own Playwright config
 * (`playwright.pwa.config.ts`), so `pnpm test:browser` doesn't need a build.
 *
 * Each test copies `build/` to a temp dir and serves the copy, so a "new
 * deploy" can be made by editing the copy's `sw.js` the way a rebuild does.
 */
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { AddressInfo } from 'node:net';
import { expect, test, type Page } from '@playwright/test';

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

type Site = { url: string; root: string; close: () => Promise<void> };

/** Serve a copy of `build/`: files as they are, any other path the fallback page. */
async function serveCopy(): Promise<Site> {
	const root = fs.mkdtempSync(path.join(os.tmpdir(), 'crema-pwa-'));
	fs.cpSync(build, root, { recursive: true });
	const server = http.createServer((req, res) => {
		const pathname = decodeURIComponent(new URL(req.url ?? '/', 'http://x').pathname);
		let file = path.join(root, path.normalize(pathname));
		if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
			file = path.join(root, 'index.html');
		}
		// Every update check reaches the server, as it does on Pages for sw.js.
		res.writeHead(200, {
			'content-type': MIME[path.extname(file)] ?? 'application/octet-stream',
			'cache-control': 'no-cache'
		});
		fs.createReadStream(file).pipe(res);
	});
	await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
	const { port } = server.address() as AddressInfo;
	return {
		url: `http://127.0.0.1:${port}`,
		root,
		close: () =>
			new Promise<void>((resolve) => {
				server.closeAllConnections();
				server.close(() => resolve());
				fs.rmSync(root, { recursive: true, force: true });
			})
	};
}

/** The wasm files the build ships (the de1 core, and the history store's once W1 lands). */
function builtWasm(): string[] {
	return fs
		.readdirSync(path.join(build, '_app/immutable'), { recursive: true, encoding: 'utf8' })
		.filter((f) => f.endsWith('.wasm'))
		.map((f) => `_app/immutable/${f.split(path.sep).join('/')}`)
		.sort();
}

/** The app shell rendered (the core loaded), not the core-failed page. */
async function expectShell(page: Page): Promise<void> {
	await expect(page.locator('main.shell-content')).toBeVisible({ timeout: 30_000 });
	await expect(page.getByText('Failed to load the core')).toHaveCount(0);
}

/** Wait until a service worker controls the page. */
async function waitForController(page: Page): Promise<void> {
	await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 30_000 });
}

test.beforeAll(() => {
	if (!fs.existsSync(path.join(build, 'sw.js'))) {
		throw new Error(`${build}/sw.js is missing: run \`pnpm build\` before \`pnpm test:pwa\``);
	}
});

test('the shell starts offline from the precache, wasm included', async ({ context, page }) => {
	const site = await serveCopy();
	try {
		await page.goto(site.url);
		await expectShell(page);
		await waitForController(page);

		// Every wasm the build ships is in the precache (none refused for size).
		const wasm = builtWasm();
		expect(wasm.length).toBeGreaterThan(0);
		const cached = await page.evaluate(async () => {
			const names = (await caches.keys()).filter((n) => n.includes('precache'));
			const urls: string[] = [];
			for (const n of names) {
				for (const r of await (await caches.open(n)).keys()) urls.push(new URL(r.url).pathname.slice(1));
			}
			return urls;
		});
		for (const w of wasm) expect(cached).toContain(w);

		await context.setOffline(true);
		await page.reload();
		await expectShell(page);
		// A deep link opens offline too: the worker answers navigations with the shell.
		await page.goto(`${site.url}/history`);
		await expectShell(page);
	} finally {
		await context.setOffline(false);
		await site.close();
	}
});

test('Google Fonts are runtime-cached and served offline', async ({ context, page }) => {
	// Stand-ins for Google's servers, so the test needs no internet. In
	// Chromium, context routes also see the worker's own fetches.
	const css = 'https://fonts.googleapis.com/css2?family=Newsreader';
	const font = 'https://fonts.gstatic.com/s/crema-test/v1/test.woff2';
	await context.route('https://fonts.googleapis.com/**', (r) =>
		r.fulfill({ status: 200, contentType: 'text/css', body: '/* stand-in */' })
	);
	await context.route('https://fonts.gstatic.com/**', (r) =>
		r.fulfill({ status: 200, contentType: 'font/woff2', body: Buffer.from('wOF2') })
	);
	const site = await serveCopy();
	try {
		await page.goto(site.url);
		await expectShell(page);
		await waitForController(page);

		// Through the worker now: the stylesheet (StaleWhileRevalidate) and a
		// font file (CacheFirst, opaque responses cacheable).
		const fetchAll = (urls: string[]) =>
			page.evaluate(
				(us) =>
					Promise.all(
						us.map((u) =>
							fetch(u, { mode: 'no-cors' }).then(
								() => 'ok',
								() => 'failed'
							)
						)
					),
				urls
			);
		expect(await fetchAll([css, font])).toEqual(['ok', 'ok']);
		await expect
			.poll(() =>
				page.evaluate(async () => {
					const urls = async (name: string) =>
						(await caches.has(name)) ? (await (await caches.open(name)).keys()).map((r) => r.url) : [];
					return {
						stylesheets: await urls('google-fonts-stylesheets'),
						webfonts: await urls('google-fonts-webfonts')
					};
				})
			)
			.toEqual({ stylesheets: [css], webfonts: [font] });

		await context.unrouteAll();
		await context.setOffline(true);
		// Cached: served offline. Not cached: fails (the network is really gone).
		expect(await fetchAll([css, font, font.replace('test.woff2', 'other.woff2')])).toEqual([
			'ok',
			'ok',
			'failed'
		]);
	} finally {
		await context.setOffline(false);
		await site.close();
	}
});

test('a new deploy is offered, waits for the user, and applies on Reload', async ({ page }) => {
	const site = await serveCopy();
	try {
		await page.goto(site.url);
		await expectShell(page);
		await waitForController(page);
		// Marks this page load: a reload clears it.
		await page.evaluate(() => {
			(window as unknown as { __loaded: number }).__loaded = 1;
		});

		// A new deploy: a rebuild changes the fallback page's revision in sw.js.
		const swPath = path.join(site.root, 'sw.js');
		const sw = fs.readFileSync(swPath, 'utf8');
		const next = sw.replace(/(url:\s*["'`]index\.html["'`],\s*revision:\s*["'`])[0-9a-f]+/, '$1' + 'f'.repeat(32));
		expect(next).not.toBe(sw);
		fs.writeFileSync(swPath, next);
		await page.evaluate(async () => {
			await (await navigator.serviceWorker.getRegistration())?.update();
		});

		const offer = page.getByText('Crema has been updated. Reload when you are ready.');
		await expect(offer).toBeVisible({ timeout: 30_000 });
		// The new worker waits (no skipWaiting) and the page is not reloaded.
		await page.waitForTimeout(3_000);
		expect(
			await page.evaluate(async () => ({
				loaded: (window as unknown as { __loaded?: number }).__loaded,
				waiting: (await navigator.serviceWorker.getRegistration())?.waiting?.state ?? null
			}))
		).toEqual({ loaded: 1, waiting: 'installed' });
		await expect(offer).toBeVisible();

		// Reload hands over to the new worker and reloads once.
		await Promise.all([
			page.waitForEvent('load'),
			page.getByRole('button', { name: 'Reload' }).click()
		]);
		await expectShell(page);
		const state = () =>
			page.evaluate(async () => {
				const reg = await navigator.serviceWorker.getRegistration();
				return {
					loaded: (window as unknown as { __loaded?: number }).__loaded ?? null,
					waiting: reg?.waiting ?? null,
					active: reg?.active?.state ?? null
				};
			});
		// WebKit leaves a page that loaded mid-activation reading `activating`
		// (the same on the 1.x plugin's worker), so `activated` is checked on
		// the next load below.
		expect((await state()).waiting).toBeNull();
		expect((await state()).loaded).toBeNull();
		await expect(offer).toHaveCount(0);

		// Applied, not offered again: the next launch runs the new build quietly.
		await page.reload();
		await expectShell(page);
		await expect.poll(state).toEqual({ loaded: null, waiting: null, active: 'activated' });
		await page.waitForTimeout(1_000);
		await expect(offer).toHaveCount(0);
	} finally {
		await site.close();
	}
});

test('the service worker counts the open windows for the history flip (#310 M2)', async ({ context, page }) => {
	const site = await serveCopy();
	const count = (p: Page) =>
		p.evaluate(
			() =>
				new Promise<number | null>((resolve) => {
					void navigator.serviceWorker.getRegistration().then((reg) => {
						const sw = reg?.active;
						if (!sw) return resolve(null);
						const ch = new MessageChannel();
						ch.port1.onmessage = (e) => resolve((e.data as { count: number | null }).count);
						sw.postMessage({ type: 'CREMA_WINDOW_COUNT' }, [ch.port2]);
					});
				})
		);
	try {
		await page.goto(site.url);
		await expectShell(page);
		await waitForController(page);
		expect(await count(page)).toBe(1);
		const second = await context.newPage();
		await second.goto(site.url);
		await expectShell(second);
		expect(await count(page)).toBe(2);
		await second.close();
		expect(await count(page)).toBe(1);
	} finally {
		await site.close();
	}
});
