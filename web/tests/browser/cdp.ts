/**
 * A bare CDP client for the one check Playwright can't drive: a page frozen
 * through `Page.setWebLifecycleState`. A page Playwright is attached to never
 * freezes (it emulates focus, so Chrome keeps it visible, and the freeze is a
 * no-op), so the frozen-owner test launches Chrome itself and speaks CDP over
 * the DevTools WebSocket. The same endpoints serve Android Chrome through
 * `adb forward tcp:<port> localabstract:chrome_devtools_remote`.
 */
import { spawn, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { chromium } from '@playwright/test';

export type RawTab = {
	id: string;
	send<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>;
	/** Evaluates `expr` in the page, awaiting a promise; its value, or throws its exception. */
	eval<T = unknown>(expr: string): Promise<T>;
	close(): Promise<void>;
};

export type RawBrowser = {
	open(url: string): Promise<RawTab>;
	close(): Promise<void>;
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function waitFor<T>(f: () => T | undefined | Promise<T | undefined>, ms: number, what: string): Promise<T> {
	const end = Date.now() + ms;
	for (;;) {
		const v = await f();
		if (v !== undefined) return v;
		if (Date.now() > end) throw new Error(`timed out waiting for ${what}`);
		await sleep(100);
	}
}

/** A CDP endpoint at `http://127.0.0.1:<port>`: open tabs and drive them. */
export function rawBrowser(endpoint: string, onClose: () => Promise<void> = async () => {}): RawBrowser {
	const tabs: RawTab[] = [];
	/** One command on the browser target, on a socket of its own. */
	const browserCall = async <T>(method: string, params: Record<string, unknown>): Promise<T> => {
		const { webSocketDebuggerUrl } = (await (await fetch(`${endpoint}/json/version`)).json()) as { webSocketDebuggerUrl: string };
		// Android gives `ws://127.0.0.1:<port>/devtools/browser`; keep the forwarded port.
		const ws = new WebSocket(webSocketDebuggerUrl.replace(/^ws:\/\/[^/]+/, endpoint.replace(/^http/, 'ws')));
		await new Promise((r, j) => {
			ws.onopen = r;
			ws.onerror = j;
		});
		try {
			return await new Promise<T>((resolve, reject) => {
				ws.onmessage = (e) => {
					const m = JSON.parse(String(e.data));
					if (m.id === 1) (m.error ? reject(new Error(m.error.message)) : resolve(m.result as T));
				};
				ws.send(JSON.stringify({ id: 1, method, params }));
			});
		} finally {
			ws.close();
		}
	};
	return {
		async open(url) {
			// A new tab through the browser's own target domain: Android Chrome
			// has no `/json/new`.
			const targetId = await browserCall<{ targetId: string }>('Target.createTarget', { url: 'about:blank' }).then((r) => r.targetId);
			const target = { id: targetId, webSocketDebuggerUrl: `${endpoint.replace(/^http/, 'ws')}/devtools/page/${targetId}` };
			const ws = new WebSocket(target.webSocketDebuggerUrl);
			await new Promise((r, j) => {
				ws.onopen = r;
				ws.onerror = j;
			});
			let seq = 0;
			const waiting = new Map<number, (m: { result?: unknown; error?: { message: string } }) => void>();
			ws.onmessage = (e) => {
				const m = JSON.parse(String(e.data));
				if (m.id) {
					waiting.get(m.id)?.(m);
					waiting.delete(m.id);
				}
			};
			const send = <T>(method: string, params: Record<string, unknown> = {}) =>
				new Promise<T>((resolve, reject) => {
					const id = ++seq;
					waiting.set(id, (m) => (m.error ? reject(new Error(m.error.message)) : resolve(m.result as T)));
					ws.send(JSON.stringify({ id, method, params }));
				});
			const tab: RawTab = {
				id: target.id,
				send,
				async eval<T>(expr: string) {
					const r = await send<{ result: { value: T }; exceptionDetails?: { exception?: { description?: string }; text: string } }>(
						'Runtime.evaluate',
						{ expression: expr, returnByValue: true, awaitPromise: true }
					);
					if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text);
					return r.result.value;
				},
				async close() {
					ws.close();
					await browserCall('Target.closeTarget', { targetId }).catch(() => {});
				}
			};
			tabs.push(tab);
			await send('Page.navigate', { url });
			await waitFor(async () => {
				const ready = await tab.eval<string>('document.body?.dataset.ready ?? ""').catch(() => '');
				return ready === '1' ? true : undefined;
			}, 15_000, `${url} to load`);
			return tab;
		},
		async close() {
			for (const t of tabs) await t.close().catch(() => {});
			await onClose();
		}
	};
}

/** Launches Playwright's Chromium, headless, on a temp profile, outside Playwright's control. */
export async function launchRaw(): Promise<RawBrowser> {
	const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'crema-w0-raw-'));
	const proc: ChildProcess = spawn(
		chromium.executablePath(),
		[
			'--headless=new',
			`--user-data-dir=${dir}`,
			'--remote-debugging-port=0',
			'--no-first-run',
			'--no-default-browser-check',
			// As Playwright launches it. Without these, macOS Chrome waits on the
			// keychain before its network stack starts, and every load hangs.
			'--use-mock-keychain',
			'--password-store=basic',
			'--disable-features=HttpsUpgrades',
			// CI's Linux runners don't allow Chrome's sandbox; Playwright turns it off too.
			...(process.platform === 'linux' ? ['--no-sandbox', '--disable-dev-shm-usage'] : []),
			'about:blank'
		],
		{ stdio: 'ignore' }
	);
	const portFile = path.join(dir, 'DevToolsActivePort');
	const port = await waitFor(() => (fs.existsSync(portFile) ? fs.readFileSync(portFile, 'utf8').split('\n')[0] || undefined : undefined), 15_000, 'Chrome\'s DevTools port');
	return rawBrowser(`http://127.0.0.1:${port}`, async () => {
		proc.kill();
		await new Promise((r) => proc.once('exit', r));
		// The temp profile only: `dir` is the mkdtemp above.
		fs.rmSync(dir, { recursive: true, force: true });
	});
}
