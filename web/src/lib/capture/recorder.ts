/**
 * `$lib/capture/recorder` — capture-entry shape + the JSONL download
 * serializer. Once a thick rolling-buffer / identity-keeper / META-prelude
 * implementation, now type-only — the rolling buffer, the per-source
 * identity dictionary, and the META construction all live in the Rust core
 * (`core/de1-app/src/capture.rs`) and are reached via the wasm bridge's
 * `captureSliceJsonl`. Pushed there so the web shell and Android share one
 * byte-for-byte implementation, and so the recorder side-effect rides the
 * same wasm boundary crossing as the decode rather than adding a second one.
 *
 * Two surfaces survive on this side:
 *  - {@link CaptureEntry} — the JSONL line shape that IndexedDB (`./store`)
 *    structured-clones and the {@link captureJsonl} helper serialises. Used
 *    by `ShotDetail.svelte`'s download path.
 *  - {@link captureJsonl} — the JSONL-line serializer for the download.
 *    The slice is built by `CremaCore.captureSliceJsonl` (which already
 *    returns JSONL bytes); this helper exists only for the post-load
 *    download path, where the IndexedDB read returns a `CaptureEntry[]`
 *    and the user clicks "Download" to save it.
 */

/**
 * One captured BLE event — wire-compatible with the JSONL the Rust core
 * produces (`core/de1-app/src/capture.rs`), the Android `BleSessionRecorder`,
 * the Rust `examples/replay.rs` tool, and the web shell's
 * `lib/replay/capture` parser. Storing it directly in IndexedDB (structured
 * clone) avoids the JSON round-trip; the same object serialises to one JSONL
 * line on export.
 */
export interface CaptureEntry {
	/** Millisecond timestamp the core was fed (`performance.now()` value). */
	readonly t: number;
	/** Direction — only `'in'` notifications are recorded today. */
	readonly dir: 'in';
	/**
	 * Canonical source name — matches the Kotlin `BleSessionRecorder`, the
	 * Rust `replay.rs` tool, and the core's `source_name`: `DE1_STATE`,
	 * `DE1_SHOT_SAMPLE`, `DE1_WATER_LEVELS`, `SCALE_WEIGHT`, `SCALE_FF12`, …
	 *
	 * The special source `META` carries connect-phase identity (scale
	 * advertised name, DE1 firmware, machine model, …) so the replay tool
	 * can call `connectScale(name)` etc. BEFORE feeding the BLE byte stream
	 * — otherwise the bytes can't decode (scale model needs to be known up
	 * front). META entries set `hex: ''` and stash the payload in
	 * {@link meta}.
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
 * Serialise a capture slice as JSON Lines — the downloadable file format.
 * One JSON object per line, trailing newline. Matches the bytes the core
 * emits in `CremaCore.captureSliceJsonl`.
 */
export function captureJsonl(entries: readonly CaptureEntry[]): string {
	return entries.map((e) => JSON.stringify(e)).join('\n') + '\n';
}
