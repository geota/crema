/**
 * `$lib/replay` — a developer/admin capture-replay tool.
 *
 * Loads a recorded BLE capture file (JSON Lines, one event per line) and plays
 * its inbound notifications back through the Crema core with real-time timing,
 * so a previously-exported shot can be watched in the web UI with no live
 * machine. {@link parseCapture} / {@link parseCaptureFile} turn the file into
 * replayable events; the paced playback (timestamp-driven, interruptible) lives
 * in `$lib/services/replay`'s `replayCaptureProgram` (T-28), wired to the core
 * by `CremaApp.replayCapture` — see `lib/state/app`.
 */

export {
	parseCapture,
	parseCaptureFile,
	sourceFromName,
	type CaptureEvent,
	type ParsedCapture,
	type ReplayMeta
} from './capture';
