/**
 * `$lib/capture/recorder` — the rolling BLE-capture recorder.
 *
 * Every inbound BLE notification feeds this buffer with a single
 * {@link CaptureEntry} `{ t, dir, src, hex }` — the same JSON Lines schema the
 * Android `BleSessionRecorder`, the Rust `replay.rs` tool and the web replay
 * parser (`$lib/replay/capture`) all consume. The orchestrator slices the
 * buffer at `ShotCompleted` and persists the slice to IndexedDB (see
 * `$lib/capture/store`), so any shot pulled while the app is open is
 * downloadable as a replayable capture file.
 *
 * The buffer is in-memory only, capped to {@link MAX_ENTRIES} (rolls off the
 * oldest in batches of `TRIM_SLACK` to keep `Array.shift` off the hot path).
 * Sized for ~8 minutes at ~35 notifications/s — well past any plausible shot.
 */

import type { NotificationSource } from '$lib/core';

/** Rolling-buffer cap. */
const MAX_ENTRIES = 20_000;
/** Trim the buffer when it exceeds `MAX_ENTRIES + TRIM_SLACK`. */
const TRIM_SLACK = 1_000;

/**
 * One captured BLE event — wire-compatible with `$lib/replay/capture`'s
 * `CaptureLine`. Storing it directly in IndexedDB (structured clone) avoids
 * the JSON round-trip; the same object serialises to one JSONL line on export.
 */
export interface CaptureEntry {
	/** Millisecond timestamp the core was fed (`performance.now()` value). */
	readonly t: number;
	/** Direction — only `'in'` notifications are recorded today. */
	readonly dir: 'in';
	/**
	 * Canonical source name — matches the Kotlin `BleSessionRecorder` and the
	 * Rust `replay.rs` tool: `DE1_STATE`, `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`,
	 * `SCALE_WEIGHT`, `SCALE_FF12`, …
	 *
	 * The special source `META` (introduced 2026-05-22) carries
	 * connect-phase identity (scale advertised name, DE1 firmware, machine
	 * model, …) so the replay tool can call `connectScale(name)` etc.
	 * BEFORE feeding the BLE byte stream — otherwise the bytes can't decode
	 * (scale model needs to be known up front). META entries set
	 * `hex: ''` and stash the payload in {@link meta}.
	 */
	readonly src: string;
	/** Lowercase hex payload, no separators. Empty string for META entries. */
	readonly hex: string;
	/**
	 * Optional structured payload for META entries. Ignored on
	 * regular-source entries. Stays optional so old captures (no META) and
	 * external replay tools that don't understand META still parse.
	 */
	readonly meta?: Record<string, string | number | undefined>;
}

/**
 * Web `NotificationSource` → its canonical capture-file name. The exact
 * inverse of `sourceFromName` in `$lib/replay/capture`, so a recorded capture
 * round-trips cleanly through the replay tool.
 *
 * `De1Version`/`De1MmrRead`/`De1Calibration` are doc-11 read-paths that the
 * replay parser does not yet map back — recording them is harmless (those
 * lines are simply skipped on replay).
 */
const SRC_NAME: Record<NotificationSource, string> = {
	De1State: 'DE1_STATE',
	De1ShotSample: 'DE1_SHOT_SAMPLE',
	De1WaterLevels: 'DE1_WATER_LEVELS',
	De1Version: 'DE1_VERSION',
	De1MmrRead: 'DE1_MMR_READ',
	De1Calibration: 'DE1_CALIBRATION',
	De1ShotSettings: 'DE1_SHOT_SETTINGS',
	De1ProfileHeader: 'DE1_PROFILE_HEADER',
	De1FrameAck: 'DE1_FRAME_ACK',
	ScaleWeight: 'SCALE_WEIGHT',
	ScaleCommand: 'SCALE_FF12'
};

const HEX_DIGITS = '0123456789abcdef';

/** Encode a `Uint8Array` as a no-separator lowercase hex string. */
export function toHex(data: Uint8Array): string {
	let out = '';
	for (const byte of data) {
		out += HEX_DIGITS[byte >> 4] + HEX_DIGITS[byte & 0x0f];
	}
	return out;
}

/**
 * Source names whose latest-seen entry is kept indefinitely (separate from
 * the rolling buffer), so a slice taken long after the connect-phase reads
 * still has the identity bytes the replay tool needs to decode subsequent
 * notifications. Examples: the DE1 firmware Version read (one-shot at
 * connect — never seen again unless reconnect), the StateInfo Read (also
 * one-shot), ShotSettings (one-shot + occasional notify), and every MMR
 * read response (model, serial, GHC info, flush temp, …).
 *
 * `SCALE_WEIGHT` is handled separately — we always keep the FIRST one
 * seen (the wire-signature byte pattern lets the replay sniff the scale
 * model), not the most recent.
 */
const IDENTITY_SOURCES: ReadonlySet<string> = new Set([
	'DE1_VERSION',
	'DE1_STATE',
	'DE1_SHOT_SETTINGS',
	'DE1_MMR_READ'
]);

/**
 * A rolling capture buffer. The BLE managers feed it on every inbound
 * notification; the orchestrator slices it on `ShotCompleted`.
 *
 * Alongside the rolling buffer, the recorder keeps a small dictionary of
 * **identifier entries** — the latest connect-phase reads (DE1 firmware
 * version, machine state, shot settings, MMR values) plus the FIRST scale
 * weight ever seen. These survive the rolling-cap trim and get prepended
 * to any slice that wouldn't otherwise include them, so a replay always
 * has the bytes needed to identify the connected hardware regardless of
 * how long ago the connect happened.
 */
export class CaptureRecorder {
	private buf: CaptureEntry[] = [];
	/** Latest-seen entry per `IDENTITY_SOURCES` source (plus one bucket per MMR register). */
	private identityLatest: Map<string, CaptureEntry> = new Map();
	/** First SCALE_WEIGHT ever recorded — used by replay for scale-type sniffing. */
	private firstScaleWeight: CaptureEntry | null = null;

	/** Append one inbound notification. */
	record(source: NotificationSource, data: Uint8Array, atMs: number): void {
		const src = SRC_NAME[source];
		const entry: CaptureEntry = {
			t: atMs,
			dir: 'in',
			src,
			hex: toHex(data)
		};
		this.buf.push(entry);
		// Identity sources — keep the most recent regardless of rolling trim.
		// MMR responses bucket by register byte (data[0..3] is the address,
		// little-endian), so a sweep of multiple registers keeps each
		// independently rather than overwriting them.
		if (IDENTITY_SOURCES.has(src)) {
			const key = src === 'DE1_MMR_READ' ? `${src}:${data[0]},${data[1]},${data[2]}` : src;
			this.identityLatest.set(key, entry);
		}
		// First scale weight — kept so a replay can sniff scale type by
		// wire-signature even after the rolling buffer has churned past it.
		if (src === 'SCALE_WEIGHT' && this.firstScaleWeight === null) {
			this.firstScaleWeight = entry;
		}
		// Trim in batches so `Array.shift` does not run on every push.
		if (this.buf.length > MAX_ENTRIES + TRIM_SLACK) {
			this.buf = this.buf.slice(-MAX_ENTRIES);
		}
	}

	/**
	 * Every entry whose `t` is in `[fromT, toT]`, in chronological order —
	 * plus any identity entries (latest connect-phase reads + first scale
	 * weight) older than `fromT`, prepended with timestamps tucked just
	 * before `fromT` so they replay first.
	 */
	slice(fromT: number, toT: number): CaptureEntry[] {
		const inWindow = this.buf.filter((e) => e.t >= fromT && e.t <= toT);
		// Collect identity entries that are NOT already in the window —
		// always-keep entries from before the window start. Adjust their
		// timestamps to land just before `fromT` so chronological replay
		// hits them first; preserve the relative order across identities
		// by spacing them 1 ms apart.
		const prelude: CaptureEntry[] = [];
		const inWindowHexSet = new Set(inWindow.map((e) => `${e.src}|${e.hex}`));
		const candidates: CaptureEntry[] = [];
		for (const entry of this.identityLatest.values()) {
			if (entry.t < fromT && !inWindowHexSet.has(`${entry.src}|${entry.hex}`)) {
				candidates.push(entry);
			}
		}
		if (
			this.firstScaleWeight &&
			this.firstScaleWeight.t < fromT &&
			!inWindowHexSet.has(`SCALE_WEIGHT|${this.firstScaleWeight.hex}`)
		) {
			candidates.push(this.firstScaleWeight);
		}
		// Keep their original relative order, just bump timestamps so they
		// land before the window.
		candidates.sort((a, b) => a.t - b.t);
		for (let i = 0; i < candidates.length; i++) {
			prelude.push({ ...candidates[i], t: fromT - (candidates.length - i) });
		}
		return [...prelude, ...inWindow];
	}

	/** Drop every entry — used on disconnect / replay-start. */
	clear(): void {
		this.buf = [];
		this.identityLatest.clear();
		this.firstScaleWeight = null;
	}
}

/** Serialise a capture slice as JSON Lines — the downloadable file format. */
export function captureJsonl(entries: readonly CaptureEntry[]): string {
	return entries.map((e) => JSON.stringify(e)).join('\n') + '\n';
}

/** Process-wide recorder singleton — one rolling buffer per app session. */
let recorder: CaptureRecorder | undefined;
export function getCaptureRecorder(): CaptureRecorder {
	if (!recorder) recorder = new CaptureRecorder();
	return recorder;
}
