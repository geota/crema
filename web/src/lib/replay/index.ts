/**
 * `$lib/replay` — a developer/admin capture-replay tool.
 *
 * Loads a recorded BLE capture file (JSON Lines, one event per line) and plays
 * its inbound notifications back through the Crema core with real-time timing,
 * so a previously-exported shot can be watched in the web UI with no live
 * machine. {@link parseCapture} / {@link parseCaptureFile} turn the file into
 * replayable events; {@link replayEvents} paces them by their timestamps.
 *
 * `CremaApp.replayCapture` wires the two to the core — see `lib/state/app`.
 */

export {
	parseCapture,
	parseCaptureFile,
	sourceFromName,
	type CaptureEvent,
	type ParsedCapture
} from './capture';
export {
	replayEvents,
	ReplayAbortedError,
	MAX_GAP_MS,
	type ReplayOptions
} from './driver';
