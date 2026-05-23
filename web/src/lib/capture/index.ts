/**
 * `$lib/capture` — record raw BLE notifications during a session and persist
 * each completed shot's slice as a replayable JSONL capture.
 *
 * The recording / slicing / META-prelude logic lives in the Rust core
 * (`core/de1-app/src/capture.rs`) — reached via `CremaCore.captureSliceJsonl`
 * — so web + Android share one implementation. What's left on this side:
 *   - The {@link CaptureEntry} shape and the {@link captureJsonl} JSONL
 *     serializer (used by the per-shot download path in
 *     `components/history/ShotDetail.svelte`).
 *   - {@link CaptureStore} — the IndexedDB-backed per-shot store, keyed by
 *     `StoredShot.id`. Persists each shot's slice so it can be downloaded
 *     as a JSONL capture that drops straight into Advanced → Replay.
 */

export { captureJsonl, type CaptureEntry } from './recorder';
export { CaptureStore, getCaptureStore } from './store';
