/**
 * `$lib/ble/reconnect-timeline` — per-device reconnect timelines, the "why
 * did it take so long to come back" instrument. The web twin of the Android
 * shell's `ReconnectTimeline.kt` (same triggers, phases, outcomes and the
 * same compact line, so a report from either shell reads the same).
 *
 * One timeline covers one reconnect EPISODE: it opens on a trigger (an
 * unexpected drop, the tab coming back into view, a "Retry now" tap), gets a
 * timestamped mark each time the reconnect moves on (backoff wait → GATT
 * connect → subscriptions → READY → post-connect profile sync) and closes with
 * an outcome. A new trigger while an episode is open SUPERSEDES it, so a
 * foreground kick reads as its own clean "foreground → READY in 1.8s".
 *
 * Kept shell-side on purpose: a ring buffer and some subtraction — nothing
 * that earns a typeshare type plus wasm and FFI facades — over timestamps of
 * the shell's own BLE phases.
 */

export type ReconnectTrigger =
	| 'drop'
	| 'foreground'
	| 'bt-on'
	| 'presence'
	| 'launch'
	| 'user-retry';

/**
 * A step of a reconnect. Web Bluetooth resolves services lazily on first use,
 * so the web timeline has no separate `discover` span — it is inside
 * `subscribe` (resolve + startNotifications per characteristic).
 */
export type ReconnectPhase =
	| 'backoff'
	| 'scan'
	| 'gatt'
	| 'discover'
	| 'subscribe'
	| 'ready'
	| 'post-connect';

export type ReconnectOutcome = 'in progress' | 'READY' | 'superseded' | 'cancelled' | 'abandoned';

export interface PhaseMark {
	readonly phase: ReconnectPhase;
	/** Monotonic ms (`performance.now()` in the app). */
	readonly atMs: number;
	readonly attempt: number;
}

export interface ReconnectTimeline {
	readonly id: number;
	/** "DE1" / "Scale". */
	readonly device: string;
	readonly trigger: ReconnectTrigger;
	readonly startedAtMs: number;
	/** Wall-clock start, for display only. */
	readonly startedWallMs: number;
	readonly marks: readonly PhaseMark[];
	readonly attempts: number;
	readonly failures: number;
	readonly lastError: string | null;
	readonly outcome: ReconnectOutcome;
	readonly readyAtMs: number | null;
	readonly postConnectAtMs: number | null;
	readonly endedAtMs: number | null;
}

const PHASE_ORDER: readonly ReconnectPhase[] = [
	'backoff',
	'scan',
	'gatt',
	'discover',
	'subscribe',
	'ready',
	'post-connect'
];

/** Trigger → READY, or null when it never got there. */
export function timeToReadyMs(t: ReconnectTimeline): number | null {
	return t.readyAtMs === null ? null : t.readyAtMs - t.startedAtMs;
}

/**
 * Time spent in each phase, summed across attempts, in phase order. A phase
 * lasts until the next mark (or READY / the episode's end for the last one);
 * `post-connect` runs from READY to the post-connect mark.
 */
export function phaseDurations(t: ReconnectTimeline): [ReconnectPhase, number][] {
	const totals = new Map<ReconnectPhase, number>();
	t.marks.forEach((mark, i) => {
		if (mark.phase === 'ready' || mark.phase === 'post-connect') return;
		const end = t.marks[i + 1]?.atMs ?? t.readyAtMs ?? t.endedAtMs;
		if (end === null || end === undefined) return;
		totals.set(mark.phase, (totals.get(mark.phase) ?? 0) + Math.max(0, end - mark.atMs));
	});
	if (t.readyAtMs !== null && t.postConnectAtMs !== null) {
		totals.set('post-connect', Math.max(0, t.postConnectAtMs - t.readyAtMs));
	}
	return PHASE_ORDER.filter((p) => totals.has(p)).map((p) => [p, totals.get(p)!]);
}

function secs(ms: number): string {
	return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * One compact line for the event log / diagnostics, e.g.
 * `reconnect DE1 · foreground → READY in 1.8s · 1 attempt · gatt 1.1s · subscribe 0.4s · post-connect 0.3s`.
 */
export function compactLine(t: ReconnectTimeline): string {
	let out = `reconnect ${t.device} · ${t.trigger} → `;
	const ready = timeToReadyMs(t);
	if (ready !== null) out += `READY in ${secs(ready)}`;
	else if (t.endedAtMs !== null) out += `${t.outcome} after ${secs(t.endedAtMs - t.startedAtMs)}`;
	else out += t.outcome;
	out += ` · ${t.attempts} ${t.attempts === 1 ? 'attempt' : 'attempts'}`;
	if (t.failures > 0) out += ` (${t.failures} failed)`;
	for (const [phase, ms] of phaseDurations(t)) out += ` · ${phase} ${secs(ms)}`;
	if (t.outcome !== 'READY' && t.lastError !== null) out += ` · last error: ${t.lastError}`;
	return out;
}

export const DEFAULT_TIMELINE_CAPACITY = 20;
const DEFAULT_MAX_MARKS = 64;
const MAX_ERROR_CHARS = 160;

/**
 * Records {@link ReconnectTimeline}s for any number of devices. Every mutator
 * is a no-op when the device has no open episode, so a first connect (no
 * trigger) leaves no record.
 */
export class ReconnectTimelineRecorder {
	private list: ReconnectTimeline[] = [];
	private nextId = 1;
	private readonly open = new Map<string, number>();
	private readonly awaitingPostConnect = new Map<string, number>();
	private readonly listeners = new Set<(timelines: readonly ReconnectTimeline[]) => void>();

	/** Called once per finished episode (the app logs its compact line). */
	onFinished: (t: ReconnectTimeline) => void = () => {};

	constructor(
		private readonly nowMs: () => number = () => performance.now(),
		private readonly wallMs: () => number = () => Date.now(),
		readonly capacity: number = DEFAULT_TIMELINE_CAPACITY,
		private readonly maxMarks: number = DEFAULT_MAX_MARKS
	) {}

	/** Newest first; at most {@link capacity}; includes open episodes. */
	get timelines(): readonly ReconnectTimeline[] {
		return this.list;
	}

	/** Listen for changes; returns the unsubscribe. Fires once immediately. */
	subscribe(listener: (timelines: readonly ReconnectTimeline[]) => void): () => void {
		this.listeners.add(listener);
		listener(this.list);
		return () => this.listeners.delete(listener);
	}

	isOpen(device: string): boolean {
		return this.open.has(device);
	}

	/** Open a new episode; a still-open one for the device becomes `superseded`. */
	begin(device: string, trigger: ReconnectTrigger): void {
		const now = this.nowMs();
		const prev = this.open.get(device);
		if (prev !== undefined) this.finish(prev, 'superseded', now);
		this.flushPostConnect(device);
		const t: ReconnectTimeline = {
			id: this.nextId++,
			device,
			trigger,
			startedAtMs: now,
			startedWallMs: this.wallMs(),
			marks: [],
			attempts: 0,
			failures: 0,
			lastError: null,
			outcome: 'in progress',
			readyAtMs: null,
			postConnectAtMs: null,
			endedAtMs: null
		};
		this.open.set(device, t.id);
		this.list = [t, ...this.list].slice(0, this.capacity);
		this.publish();
	}

	mark(device: string, phase: ReconnectPhase): void {
		const id = this.open.get(device);
		if (id === undefined) return;
		const now = this.nowMs();
		this.update(id, (t) => {
			const mark: PhaseMark = { phase, atMs: now, attempt: t.attempts };
			if (t.marks.length >= this.maxMarks) {
				// A days-long lurk would grow without bound: keep how it started
				// and the most recent marks.
				const head = Math.floor(this.maxMarks / 4);
				const tail = this.maxMarks - head - 1;
				return { ...t, marks: [...t.marks.slice(0, head), ...t.marks.slice(-tail), mark] };
			}
			return { ...t, marks: [...t.marks, mark] };
		});
	}

	attemptStarted(device: string): void {
		const id = this.open.get(device);
		if (id === undefined) return;
		this.update(id, (t) => ({ ...t, attempts: t.attempts + 1 }));
	}

	attemptFailed(device: string, error: string | null): void {
		const id = this.open.get(device);
		if (id === undefined) return;
		this.update(id, (t) => ({
			...t,
			failures: t.failures + 1,
			lastError: error === null ? null : error.slice(0, MAX_ERROR_CHARS)
		}));
	}

	/**
	 * The link is READY: closes the episode. With `expectPostConnect` the log
	 * line waits for {@link postConnectDone} (flushed by the next `begin`).
	 */
	ready(device: string, expectPostConnect = false): void {
		const id = this.open.get(device);
		if (id === undefined) return;
		this.open.delete(device);
		const now = this.nowMs();
		this.update(id, (t) => ({
			...t,
			marks: [...t.marks, { phase: 'ready', atMs: now, attempt: t.attempts }],
			outcome: 'READY',
			readyAtMs: now,
			endedAtMs: now
		}));
		if (expectPostConnect) this.awaitingPostConnect.set(device, id);
		else this.emit(id);
	}

	postConnectDone(device: string): void {
		const id = this.awaitingPostConnect.get(device);
		if (id === undefined) return;
		this.awaitingPostConnect.delete(device);
		const now = this.nowMs();
		this.update(id, (t) => ({
			...t,
			postConnectAtMs: now,
			marks: [...t.marks, { phase: 'post-connect', atMs: now, attempt: t.attempts }]
		}));
		this.emit(id);
	}

	/** Close the device's open episode as `cancelled` / `abandoned`. */
	end(device: string, outcome: 'cancelled' | 'abandoned'): void {
		const id = this.open.get(device);
		if (id === undefined) return;
		this.finish(id, outcome, this.nowMs());
	}

	private finish(id: number, outcome: ReconnectOutcome, now: number): void {
		for (const [device, openId] of this.open) if (openId === id) this.open.delete(device);
		this.update(id, (t) => ({ ...t, outcome, endedAtMs: now }));
		this.emit(id);
	}

	private flushPostConnect(device: string): void {
		const id = this.awaitingPostConnect.get(device);
		if (id === undefined) return;
		this.awaitingPostConnect.delete(device);
		this.emit(id);
	}

	private emit(id: number): void {
		const t = this.list.find((x) => x.id === id);
		if (t === undefined) return;
		try {
			this.onFinished(t);
		} catch {
			// A logging failure must never break the reconnect bookkeeping.
		}
	}

	private update(id: number, f: (t: ReconnectTimeline) => ReconnectTimeline): void {
		this.list = this.list.map((t) => (t.id === id ? f(t) : t));
		this.publish();
	}

	private publish(): void {
		for (const l of this.listeners) l(this.list);
	}
}

/** The app-wide recorder the BLE transports write to and Settings reads. */
export const reconnectTimelines = new ReconnectTimelineRecorder();
