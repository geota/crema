/**
 * `$lib/replay/capture` — parse a recorded BLE capture file into replayable
 * notification events.
 *
 * The project records BLE sessions to JSON Lines — one event per line:
 * `{"t": <ms>, "dir": "in"|"out", "src": "<string>", "hex": "<hex payload>"}`.
 * This module reads such a file, keeps only the inbound (`dir:"in"`)
 * notifications whose `src` the web core models, decodes the hex payload to
 * bytes, and yields a flat list of {@link CaptureEvent}s ready to feed through
 * `core.onNotification`.
 *
 * The `src` → {@link NotificationSource} mapping mirrors the Rust replay tool's
 * `source_from_name` (`core/de1-app/examples/replay.rs`) exactly — that mapping
 * is authoritative. A `dir:"in"` line whose `src` is not one the web core
 * models (e.g. `SCALE_FF12`, which the web `NotificationSource` union does not
 * include) is skipped, and `dir:"out"` lines are skipped — the core is not
 * driven by writes.
 */

import type { NotificationSource } from '$lib/core';

/**
 * One raw capture line — the JSON Lines schema the Android `BleSessionRecorder`
 * and the Rust `replay.rs` tool both use.
 */
interface CaptureLine {
	/** Millisecond timestamp (Android `SystemClock.elapsedRealtime()`). */
	t: number;
	/** `"in"` (a notification received) or `"out"` (a command written). */
	dir: string;
	/** `NotificationSource` enum name (inbound) or characteristic label (outbound). */
	src: string;
	/** Lowercase hex payload, no separators. */
	hex: string;
	/**
	 * Optional structured payload for `src:"META"` entries — the Crema-
	 * specific prelude that carries connect-phase identity (scale
	 * advertised name, DE1 firmware version, …). External replay tools
	 * ignore it; legacy captures don't have it.
	 */
	meta?: Record<string, string | number | undefined>;
}

/**
 * One decoded inbound notification, ready to replay through
 * `core.onNotification(source, data, t)`.
 */
export interface CaptureEvent {
	/** Millisecond timestamp from the capture — the core's clock value. */
	readonly t: number;
	/** Which characteristic the notification came from. */
	readonly source: NotificationSource;
	/** The decoded payload bytes. */
	readonly data: Uint8Array;
}

/** The outcome of parsing a capture file. */
export interface ParsedCapture {
	/** The replayable inbound events, in file order. */
	readonly events: readonly CaptureEvent[];
	/**
	 * Connect-phase identity collected from `src:"META"` entries. The replay
	 * tool reads these BEFORE iterating `events` so it can `connectScale(name)`
	 * etc. and the bytes that follow decode properly. Empty for legacy
	 * captures that pre-date the META prelude.
	 */
	readonly meta: ReplayMeta;
	/** Total non-blank lines read. */
	readonly linesRead: number;
	/** Lines skipped — unparseable, `dir:"out"`, unmapped `src`, or bad hex. */
	readonly skipped: number;
}

/** Merged metadata from one or more `src:"META"` prelude entries. */
export interface ReplayMeta {
	/** The scale's BLE advertised name (`"BOOKOO_SC"`, `"ACAIA…"`, …). */
	readonly scaleName?: string;
	/** The DE1's firmware build number (the integer MMR `FirmwareVersion`). */
	readonly de1FirmwareVersion?: number;
	/** The DE1's machine-model identifier (the integer MMR `MachineModel`). */
	readonly de1MachineModel?: number;
	/** The DE1's serial number (raw u32). */
	readonly de1SerialNumber?: number;
}

/**
 * Map a capture `src` string — the Kotlin `NotificationSource` enum name — to
 * the web core's {@link NotificationSource}. Returns `undefined` for a name the
 * web core does not model.
 *
 * Mirrors `source_from_name` in `core/de1-app/examples/replay.rs`. The one
 * difference: the Rust tool maps `SCALE_FF12` → `Source::ScaleCommand`, but the
 * web `NotificationSource` union does not expose the scale command source as an
 * inbound notification path the same way — actually it does (`'ScaleCommand'`),
 * so `SCALE_FF12` is mapped too, keeping parity with the Rust tool.
 */
export function sourceFromName(name: string): NotificationSource | undefined {
	switch (name) {
		case 'DE1_STATE':
			return 'De1State';
		case 'DE1_SHOT_SAMPLE':
			return 'De1ShotSample';
		case 'DE1_WATER_LEVELS':
			return 'De1WaterLevels';
		case 'SCALE_WEIGHT':
			return 'ScaleWeight';
		case 'SCALE_FF12':
			return 'ScaleCommand';
		default:
			return undefined;
	}
}

/**
 * Decode a lowercase-hex string with no separators into a `Uint8Array`.
 * Returns `undefined` for an odd-length string or an invalid digit pair.
 */
function decodeHex(hex: string): Uint8Array | undefined {
	if (hex.length % 2 !== 0) return undefined;
	const out = new Uint8Array(hex.length / 2);
	for (let i = 0; i < out.length; i++) {
		const byte = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
		if (Number.isNaN(byte)) return undefined;
		out[i] = byte;
	}
	return out;
}

/**
 * Parse a capture file's text into a {@link ParsedCapture}.
 *
 * Reads every non-blank line as a {@link CaptureLine}; keeps `dir:"in"` lines
 * whose `src` maps to a web {@link NotificationSource} and whose `hex` decodes;
 * skips everything else (unparseable lines, `dir:"out"`, unmapped `src`, bad
 * hex). The events keep file order — the capture is already chronological.
 */
export function parseCapture(text: string): ParsedCapture {
	const events: CaptureEvent[] = [];
	const meta: ReplayMeta = {};
	let linesRead = 0;
	let skipped = 0;

	for (const raw of text.split('\n')) {
		const line = raw.trim();
		if (line === '') continue;
		linesRead++;

		let entry: CaptureLine;
		try {
			entry = JSON.parse(line) as CaptureLine;
		} catch {
			// A malformed line is a recorder/file problem — skip it.
			skipped++;
			continue;
		}

		if (entry.dir !== 'in') {
			// Outbound writes are context only — the core is not driven by them.
			skipped++;
			continue;
		}

		// META prelude entries carry identity payloads in the optional
		// `meta` object; later entries override earlier ones (a session
		// that re-paired a different scale mid-stream would land here).
		if (entry.src === 'META' && entry.meta) {
			const m = entry.meta;
			if (typeof m.scaleName === 'string') {
				(meta as { scaleName?: string }).scaleName = m.scaleName;
			}
			if (typeof m.de1FirmwareVersion === 'number') {
				(meta as { de1FirmwareVersion?: number }).de1FirmwareVersion = m.de1FirmwareVersion;
			}
			if (typeof m.de1MachineModel === 'number') {
				(meta as { de1MachineModel?: number }).de1MachineModel = m.de1MachineModel;
			}
			if (typeof m.de1SerialNumber === 'number') {
				(meta as { de1SerialNumber?: number }).de1SerialNumber = m.de1SerialNumber;
			}
			continue;
		}

		const source = sourceFromName(entry.src);
		if (source === undefined) {
			// Inbound traffic the web core does not model — skip it.
			skipped++;
			continue;
		}

		const data = decodeHex(entry.hex);
		if (data === undefined) {
			skipped++;
			continue;
		}

		events.push({ t: entry.t, source, data });
	}

	return { events, meta, linesRead, skipped };
}

/** Read a `File` (e.g. from an `<input type="file">`) and {@link parseCapture} it. */
export async function parseCaptureFile(file: File): Promise<ParsedCapture> {
	return parseCapture(await file.text());
}
