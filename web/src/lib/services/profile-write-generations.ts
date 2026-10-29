/**
 * `#lib/services/profile-write-generations.js` — which profile writes may still
 * reach the DE1 and be acknowledged to the core (cohort review 2026-10-28,
 * #2 N2 / #6 #3).
 *
 * The shell synthesises every profile ack itself (the DE1 sends no frame
 * notification), so the shell is the one place that knows which upload a write
 * belonged to. Without that knowledge a superseded upload's queued frames
 * still went out after the new upload's header and their acks failed the new
 * upload (`HeaderNotAcked`), and the automatic re-upload turned it into a
 * cascade through the whole re-upload budget.
 *
 * The rule:
 * - The profile writes of one `CoreOutput` form a batch. A batch that carries
 *   a header write opens a new generation; the others join the current one.
 * - A write goes out only if its batch is still the current generation and
 *   no earlier write of the batch failed ({@link mayWrite}, checked when the
 *   write reaches the head of the GATT queue).
 * - After the write, {@link settle} says what to do: ack it to the core, drop
 *   it silently (superseded), or report the batch's first failure to the core
 *   (`profileWriteFailed`), which fails the upload at once.
 * - Each batch carries the core's number for its upload (the output's
 *   `ProfileUploadStarted.upload_id`; a batch without a header joins the
 *   current upload). The failure report passes it back, so a report that
 *   arrives after a newer upload started cannot fail the newer one
 *   (round-2 verification V3 #1).
 * - The generation is the shell's own count and the upload id the core's;
 *   both are kept. Upload ids restart at 1 when the wasm bridge rebuilds its
 *   core after a panic, so the generation is what supersedes a batch then
 *   ({@link ProfileWriteGenerations.supersede}).
 *
 * Android's `ProfileWriteGenerations` applies the same rule.
 */

import type { Command, Event } from '#lib/core/crema-core.js';

/** The profile writes of one `CoreOutput`. */
export interface ProfileWriteBatch {
	readonly generation: number;
	/** The core's number for the upload these writes belong to, if known. */
	readonly uploadId: number | null;
	failed: boolean;
}

/** The `upload_id` of the upload an output's `events` start, or `null`. */
export function startedUploadId(events: readonly Event[]): number | null {
	for (const e of events) {
		if (e.type === 'ProfileUploadStarted') return e.content.upload_id;
	}
	return null;
}

/** What the shell does after a profile write returned. */
export type ProfileWriteOutcome = 'ack' | 'drop' | 'failed';

function isProfileTarget(target: string): boolean {
	return target === 'De1ProfileHeader' || target === 'De1ProfileFrame';
}

export class ProfileWriteGenerations {
	#current = 0;
	#currentUploadId: number | null = null;

	/** The live generation (for tests and logs). */
	get current(): number {
		return this.#current;
	}

	/**
	 * Open the batch for one output's `commands`, or `null` when they carry no
	 * profile write. A header write starts a new generation, which supersedes
	 * every earlier batch; `uploadId` is that upload's number
	 * ({@link startedUploadId} of the same output).
	 */
	begin(commands: readonly Command[], uploadId: number | null = null): ProfileWriteBatch | null {
		let profile = false;
		let header = false;
		for (const c of commands) {
			if (c.type !== 'WriteCharacteristic') continue;
			if (c.content.target === 'De1ProfileHeader') header = true;
			if (isProfileTarget(c.content.target)) profile = true;
		}
		if (!profile) return null;
		if (header) {
			this.#current += 1;
			this.#currentUploadId = uploadId;
		}
		return { generation: this.#current, uploadId: this.#currentUploadId, failed: false };
	}

	/** Whether a write of `batch` may go out now. */
	mayWrite(batch: ProfileWriteBatch): boolean {
		return batch.generation === this.#current && !batch.failed;
	}

	/**
	 * Whether `batch` is still the live generation, failed or not: a failure
	 * report for it may still go to the core.
	 */
	isCurrent(batch: ProfileWriteBatch): boolean {
		return batch.generation === this.#current;
	}

	/**
	 * Supersede every open batch without starting a new one. For a wasm core
	 * rebuilt after a panic (`CoreRecoveryWatch`): the fresh core runs
	 * no upload and numbers its uploads from 1 again, so an old batch's id
	 * could name the fresh core's first upload (round-3 clean check C2 P3-1).
	 * Its queued writes, acks and failure report are dropped instead.
	 */
	supersede(): void {
		this.#current += 1;
		this.#currentUploadId = null;
	}

	/**
	 * Record a write's result. `'ack'` — delivered and still current: feed the
	 * synthesised ack. `'failed'` — the current batch's first failure: stop
	 * the batch and tell the core. `'drop'` — superseded, or a later write of
	 * a batch that already failed: nothing.
	 */
	settle(batch: ProfileWriteBatch, delivered: boolean): ProfileWriteOutcome {
		if (batch.generation !== this.#current || batch.failed) return 'drop';
		if (delivered) return 'ack';
		batch.failed = true;
		return 'failed';
	}
}

/** Whether `target` is a profile-upload write. */
export const isProfileWriteTarget = isProfileTarget;
