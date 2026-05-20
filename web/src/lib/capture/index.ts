/**
 * `$lib/capture` — record raw BLE notifications during a session and persist
 * each completed shot's slice as a replayable JSONL capture.
 *
 * Two pieces:
 *   - {@link CaptureRecorder} — a rolling in-memory buffer of `{t,dir,src,hex}`
 *     entries, fed at the `core.onNotification` boundary by the BLE managers.
 *   - {@link CaptureStore} — an IndexedDB-backed per-shot store, keyed by
 *     `StoredShot.id`. Persists each shot's slice so it can be downloaded as
 *     a JSONL capture that drops straight into Advanced → Replay.
 *
 * Singletons via {@link getCaptureRecorder} / {@link getCaptureStore}.
 */

export {
	CaptureRecorder,
	getCaptureRecorder,
	captureJsonl,
	toHex,
	type CaptureEntry
} from './recorder';
export { CaptureStore, getCaptureStore } from './store';
