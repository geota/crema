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
	 */
	readonly src: string;
	/** Lowercase hex payload, no separators. */
	readonly hex: string;
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
 * A rolling capture buffer. The BLE managers feed it on every inbound
 * notification; the orchestrator slices it on `ShotCompleted`.
 */
export class CaptureRecorder {
	private buf: CaptureEntry[] = [];

	/** Append one inbound notification. */
	record(source: NotificationSource, data: Uint8Array, atMs: number): void {
		this.buf.push({
			t: atMs,
			dir: 'in',
			src: SRC_NAME[source],
			hex: toHex(data)
		});
		// Trim in batches so `Array.shift` does not run on every push.
		if (this.buf.length > MAX_ENTRIES + TRIM_SLACK) {
			this.buf = this.buf.slice(-MAX_ENTRIES);
		}
	}

	/** Every entry whose `t` is in `[fromT, toT]`, in chronological order. */
	slice(fromT: number, toT: number): CaptureEntry[] {
		return this.buf.filter((e) => e.t >= fromT && e.t <= toT);
	}

	/** Drop every entry — used on disconnect / replay-start. */
	clear(): void {
		this.buf = [];
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
