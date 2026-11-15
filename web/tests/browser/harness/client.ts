/**
 * The W0 test page's main thread (#310 PLAN W0): starts the test Worker and
 * exposes `window.w0` for the Playwright specs. Throwaway, like the Worker.
 *
 * It also carries the smallest stage-2 client the frozen-owner check needs
 * (SPEC §8, "Owner can't answer"): a ping on `crema-history-rpc`, read-only
 * after 5 s without an answer, and new shots into the pending journal under
 * `crema.history.sqlite.pending.<id>`. W1 writes the real one.
 */
import { PENDING_KEY_PREFIX } from '../../../src/lib/history-db/names.ts';

type Reply = { id: number; ok: boolean; value?: unknown; error?: Record<string, unknown> };
type Event = { event: string; [k: string]: unknown };

declare global {
	interface Window {
		w0: W0;
		/** Set by Playwright's `exposeBinding`: each Worker event, as it arrives. */
		__w0Event?: (e: Event) => void;
	}
}

export class W0Error extends Error {
	constructor(public readonly detail: Record<string, unknown>) {
		super(JSON.stringify(detail));
	}
}

export class W0 {
	worker: Worker | null = null;
	events: Event[] = [];
	private seq = 0;
	private waiting = new Map<number, (r: Reply) => void>();
	private channel: BroadcastChannel | null = null;
	/** The stage-2 client's state: `ok` while the owner answers, `readOnly` after 5 s of silence. */
	clientState: 'ok' | 'readOnly' = 'ok';

	/** Starts the Worker and loads the wasm: `{ sqlite, specs, ms }`. */
	async start(): Promise<unknown> {
		const t0 = performance.now();
		const up = new Promise<void>((resolve) => {
			this.worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
			this.worker.onmessage = (e: MessageEvent<Reply | Event>) => {
				const m = e.data;
				if ('event' in m) {
					if (m.event === 'up') resolve();
					this.events.push(m);
					window.__w0Event?.(m);
					return;
				}
				this.waiting.get(m.id)?.(m);
				this.waiting.delete(m.id);
			};
		});
		await up;
		const info = (await this.call('init')) as Record<string, unknown>;
		return { ...info, ms: performance.now() - t0 };
	}

	/** One op in the Worker; throws its error (`W0Error.detail`) on failure. */
	call(op: string, args?: Record<string, unknown>): Promise<unknown> {
		const w = this.worker;
		if (!w) return Promise.reject(new Error('no worker'));
		const id = ++this.seq;
		return new Promise((resolve, reject) => {
			this.waiting.set(id, (r) => (r.ok ? resolve(r.value) : reject(new W0Error(r.error ?? {}))));
			w.postMessage({ id, op, args });
		});
	}

	/** Like `call`, but resolves `{ ok, value | error }` instead of throwing. */
	async try(op: string, args?: Record<string, unknown>): Promise<{ ok: boolean; value?: unknown; error?: unknown }> {
		try {
			return { ok: true, value: await this.call(op, args) };
		} catch (e) {
			return { ok: false, error: e instanceof W0Error ? e.detail : { code: 'js', message: String(e) } };
		}
	}

	terminate(): void {
		this.worker?.terminate();
		this.worker = null;
	}

	/** Answers pings on `crema-history-rpc` from this page's main thread, and from its Worker. */
	async serve(tab: string): Promise<void> {
		this.channel = new BroadcastChannel('crema-history-rpc');
		this.channel.onmessage = (e) => {
			if (e.data?.ping) this.channel!.postMessage({ pong: e.data.ping, from: 'page', tab });
		};
		await this.call('serve', { tab });
	}

	/** Pings the owner: every answer within `ms`, by where it came from. */
	async ask(ms: number): Promise<string[]> {
		return (await this.askTimed(ms)).map((a) => a.from);
	}

	/** Pings the owner: every answer within `ms`, where it came from and after how long. */
	askTimed(ms: number): Promise<{ from: string; ms: number }[]> {
		const ch = new BroadcastChannel('crema-history-rpc');
		const ping = `${Date.now()}-${Math.random()}`;
		const t0 = performance.now();
		const got: { from: string; ms: number }[] = [];
		ch.onmessage = (e) => {
			if (e.data?.pong === ping) got.push({ from: String(e.data.from), ms: Math.round(performance.now() - t0) });
		};
		ch.postMessage({ ping });
		return new Promise((resolve) =>
			setTimeout(() => {
				ch.close();
				resolve(got);
			}, ms)
		);
	}

	/**
	 * The stage-2 client's check: no answer from the owner's Worker in 5 s is
	 * read-only (SPEC §8). Only the Worker's answer counts: in Chromium a
	 * frozen page's main thread still gets `BroadcastChannel` messages while
	 * its Worker, which holds the database, is frozen (frozen.spec.ts).
	 */
	async checkOwner(ms = 5_000): Promise<'ok' | 'readOnly'> {
		const answers = await this.ask(ms);
		this.clientState = answers.includes('worker') ? 'ok' : 'readOnly';
		return this.clientState;
	}

	/** A new shot while read-only goes to the pending journal, one key per entry (M5). */
	capture(row: { id: string; [k: string]: unknown }): 'sent' | 'journaled' {
		if (this.clientState === 'ok') return 'sent';
		localStorage.setItem(PENDING_KEY_PREFIX + row.id, JSON.stringify(row));
		return 'journaled';
	}

	/** The pending journal's keys. */
	pending(): string[] {
		return Object.keys(localStorage).filter((k) => k.startsWith(PENDING_KEY_PREFIX));
	}

	/**
	 * The owner's drain (SPEC §8): each entry inserted if its id is absent,
	 * and its key removed only after that commit. Returns the ids inserted.
	 */
	async drain(h: string): Promise<string[]> {
		const done: string[] = [];
		for (const key of this.pending()) {
			const row = JSON.parse(localStorage.getItem(key)!);
			const have = (await this.call('call', { h, m: 'get', a: [row.id] })) as unknown[];
			if (!have.length) {
				await this.call('call', { h, m: 'upsert', a: [{ rowJson: JSON.stringify(row) }] });
				done.push(row.id);
			}
			localStorage.removeItem(key);
		}
		return done;
	}
}

window.w0 = new W0();
document.body.dataset.ready = '1';
