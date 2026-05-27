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
import { foldReplayMetaJsonl as wasmFoldReplayMetaJsonl } from '$lib/wasm/de1_wasm';

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
	 * advertised name, DE1 firmware version, …) PLUS the at-shot-start
	 * context the shell appends (active profile, BrewParams snapshot,
	 * active bean snapshot, grinder model). External replay tools
	 * ignore it; legacy captures don't have it. `unknown` for the values
	 * because the parser reads each known key defensively — a malformed
	 * line can't crash the replay reader.
	 */
	meta?: Record<string, unknown>;
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
	// ── At-shot-start context ────────────────────────────────────────────
	// The fields below ride a second META line that the shell appends
	// right after the core's connect-phase META prelude (see the writer
	// in `lib/state/app.svelte.ts`'s `ShotCompleted` capture-slice path).
	// They are advisory only — the replay driver doesn't need them to
	// decode the BLE byte stream — but they let an analyst see "this
	// shot was pulled with Profile X against a 36 g yield target on
	// Niche Zero clicks 2.5" without having to cross-reference the
	// `.shot.json` archive.
	/** The active profile's name at shot start, or `undefined` when none. */
	readonly profileName?: string;
	/**
	 * The byte-exact upload payload that was last written to the DE1, as
	 * lowercase no-separator hex. Lets a future replay tool re-derive what
	 * the firmware was executing without hunting for the matching `.tcl`
	 * / `.json`. `undefined` today — the core does not surface the
	 * profile-upload byte stream, and caching it shell-side would mean
	 * plumbing through the BLE write path. Deferred per task spec:
	 * "If the active-profile bytes aren't cheaply available, fall back to
	 * `profileName` only and document the TODO." See the writer call site.
	 */
	readonly profileBytesHex?: string;
	/** Quick-control overrides at shot start — `BrewParams` snapshot. */
	readonly yieldTarget?: number;
	readonly brewTemp?: number;
	readonly preinfuseTarget?: number;
	readonly stopOnWeight?: boolean;
	readonly autoTare?: boolean;
	/** Active bean snapshot at shot start — same shape as the v2 export. */
	readonly bean?: {
		/**
		 * Bean name. Older captures may carry a `type` key instead; the
		 * Rust folder reads either, so this shape only spells the
		 * canonical key.
		 */
		readonly name?: string;
		readonly roaster?: string;
		readonly roastedOn?: string;
		readonly roastLevel?: number;
		readonly notes?: string;
		readonly grinderSetting?: string;
	};
	/** Equipment-level grinder model at shot start (e.g. "Niche Zero"). */
	readonly grinderModel?: string;
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
		case 'DE1_VERSION':
			return 'De1Version';
		case 'DE1_MMR_READ':
			return 'De1MmrRead';
		case 'DE1_CALIBRATION':
			return 'De1Calibration';
		case 'DE1_SHOT_SETTINGS':
			return 'De1ShotSettings';
		case 'DE1_PROFILE_HEADER':
			return 'De1ProfileHeader';
		case 'DE1_FRAME_ACK':
			return 'De1FrameAck';
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
	const metaPayloads: string[] = [];
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
		// `meta` object; collected here in file order so the core's
		// `foldReplayMetaJsonl` folds them with the right precedence
		// (later entries override earlier ones).
		if (entry.src === 'META' && entry.meta) {
			metaPayloads.push(JSON.stringify(entry.meta));
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

	const meta = foldMetaPayloadsViaCore(metaPayloads);
	return { events, meta, linesRead, skipped };
}

/** Read a `File` (e.g. from an `<input type="file">`) and {@link parseCapture} it. */
export async function parseCaptureFile(file: File): Promise<ParsedCapture> {
	return parseCapture(await file.text());
}

/**
 * Hand the collected `src:"META"` payloads to the core's folder and
 * parse the merged `ReplayMeta` back. The folder lives in Rust
 * (`de1_domain::fold_meta_jsonl_json`) so the schema knowledge sits
 * next to its capture-write counterpart and stays in lockstep with the
 * Kotlin shell. Returns an empty `ReplayMeta` when the folder errors
 * (a malformed META payload is non-fatal for replay — the events still
 * decode).
 */
function foldMetaPayloadsViaCore(payloads: readonly string[]): ReplayMeta {
	try {
		const out = wasmFoldReplayMetaJsonl(`[${payloads.join(',')}]`);
		return JSON.parse(out) as ReplayMeta;
	} catch {
		return {};
	}
}
