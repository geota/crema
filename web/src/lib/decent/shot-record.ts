/**
 * `$lib/decent/shot-record` — a Crema {@link StoredShot} as the decaid
 * `ShotRecord` Decent's shot-history ingest accepts.
 *
 * The converter lives in core (`de1_domain::decent_shot_record`, pinned by a
 * golden fixture) so the web and Android shells send the same document; this
 * is the thin typed wrapper over `decentShotRecordJson`, plus the two small
 * shell-side helpers the upload gates use.
 */

import { Data } from 'effect';
import { decentShotRecordJson, machineModelName } from '$lib/wasm/de1_wasm';
import type { ShotMachine, TimedSample } from '$lib/core/crema-core';
import type { StoredShot } from '$lib/history/model';

/** The core could not read the shot (a malformed persisted row) — retrying will not help. */
export class DecentRecordError extends Data.TaggedError('DecentRecordError')<{ readonly detail: string }> {
	get message(): string {
		return `Couldn't prepare the shot for Decent: ${this.detail}`;
	}
}

/**
 * Human model name for the DE1 MMR `MachineModel` register value, via the
 * core's table. `null` for 0 (unknown) and anything past the table — core
 * reports those as `"model N"`, which is not a name worth sending.
 */
export function decentModelName(raw: number | null | undefined): string | null {
	if (raw == null || !Number.isInteger(raw) || raw < 1 || raw > 7) return null;
	return machineModelName(raw);
}

/** Seconds of telemetry — the auto-upload skips flushes shorter than 5 s. */
export function shotDurationSeconds(shot: StoredShot): number {
	return shot.record.duration / 1000;
}

/**
 * The persisted row as the core's `StoredShot` JSON. The web row already is
 * that shape (camelCase serde); only the millisecond fields are rounded (Rust
 * `Duration`s) and a missing tag list defaulted (a Rust `Vec` rejects `null`).
 */
function coreShotJson(shot: StoredShot, samples: readonly TimedSample[], dropRecipe: boolean): string {
	return JSON.stringify({
		...shot,
		tags: shot.tags ?? [],
		...(dropRecipe ? { profile: null, stopReason: null } : {}),
		record: {
			duration: Math.max(0, Math.round(shot.record.duration)),
			samples: samples.map((s) => ({ ...s, elapsed: Math.max(0, Math.round(s.elapsed)) }))
		}
	});
}

/**
 * Build the ShotRecord JSON, ready to POST. `samples` — full-resolution
 * telemetry when the caller has it (the stored row may be downsampled); it
 * replaces `record.samples` before the core call.
 *
 * A row whose recipe snapshot the core cannot read (an old, hand-shaped
 * profile) is retried without it — core then sends the title-only stub
 * decaid accepts — so one stale field does not block the upload for good.
 * Throws {@link DecentRecordError} when the row cannot be read at all.
 */
export function decentShotRecord(
	shot: StoredShot,
	machine: ShotMachine,
	appVersion: string,
	samples?: readonly TimedSample[]
): string {
	const series = samples && samples.length > 0 ? samples : shot.record.samples;
	const machineJson = JSON.stringify(machine);
	try {
		return decentShotRecordJson(coreShotJson(shot, series, false), machineJson, appVersion);
	} catch (first) {
		if (shot.profile == null && shot.stopReason == null) {
			throw new DecentRecordError({ detail: String(first) });
		}
		try {
			return decentShotRecordJson(coreShotJson(shot, series, true), machineJson, appVersion);
		} catch (second) {
			throw new DecentRecordError({ detail: String(second) });
		}
	}
}
