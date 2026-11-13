/**
 * `#lib/visualizer/pace.js` — the request pacer every Visualizer call goes
 * through (core `pace`): 30 requests of burst, then one every 4 s, which stays
 * inside Visualizer's 50 a minute and 200 in ten for one device. A 429 sets a
 * cooldown that holds every call, a relaunch included.
 *
 * The bucket lives in memory and is stored every tenth request, on a 429 and
 * when a run stops, so a relaunch doesn't start with a full burst. Another tab,
 * another device or the website aren't covered: that costs a wait, and the 429
 * path handles it.
 */

import { Effect } from 'effect';
import type { PaceBucket } from '#lib/core/crema-core.js';
import { readJson, writeJson, isJsonObject } from '../utils/storage.ts';
import { pullRules } from './pull-run-core.ts';

/** localStorage key (cleared by a factory reset with every other `crema.*` key). */
export const PACE_KEY = 'crema.visualizer.pace.v1';
/** Requests between stores of the bucket. */
const STORE_EVERY = 10;
/**
 * The longest a call that isn't part of a run waits for its slot. Past it the
 * call fails as rate limited, unsent, and its own retry rule takes over: an
 * edit or a drain shouldn't sit through a cooldown.
 */
export const INLINE_WAIT_MAX_MS = 10_000;

const EMPTY: PaceBucket = { tatMs: 0, lastNowMs: 0 };

const isBucket = (v: unknown): v is PaceBucket =>
	isJsonObject(v) && typeof v.tatMs === 'number' && typeof v.lastNowMs === 'number';

let bucket: PaceBucket | null = null;
let sinceStore = 0;
let enabled = true;

function current(): PaceBucket {
	bucket ??= readJson<PaceBucket>(PACE_KEY, EMPTY, { valid: isBucket });
	return bucket;
}

/** Store the bucket now: a run stopped, or a 429 set a cooldown. */
export function storePace(): void {
	if (!bucket) return;
	sinceStore = 0;
	writeJson(PACE_KEY, bucket);
}

/**
 * Take the next request's slot: how long to wait first, ms, or `null` when
 * the caller isn't `patient` and the wait is past {@link INLINE_WAIT_MAX_MS}
 * (the slot isn't taken then).
 */
export function takeSlot(patient: boolean, now: number = Date.now()): number | null {
	if (!enabled) return 0;
	const { bucket: next, waitMs } = pullRules.pace(current(), now);
	if (!patient && waitMs > INLINE_WAIT_MAX_MS) return null;
	bucket = next;
	sinceStore += 1;
	if (sinceStore >= STORE_EVERY) storePace();
	return waitMs;
}

/** A 429 arrived: nothing goes before `now + waitMs`. Stored at once. */
export function noteRateLimited(waitMs: number, now: number = Date.now()): void {
	if (!enabled) return;
	bucket = pullRules.noteRateLimit(current(), now, waitMs);
	storePace();
}

/** Whether a 429's cooldown is still running: drains wait it out. */
export function coolingDown(now: number = Date.now()): boolean {
	return enabled && (current().cooldownUntilMs ?? 0) > now;
}

let sleeper: (ms: number) => Effect.Effect<void> = (ms) => Effect.sleep(ms);

/** Sit a wait out: the pacer's slot, or a run's rate-limit wait. */
export function waitFor(ms: number): Effect.Effect<void> {
	return sleeper(ms);
}

/** Test seam: how waits are slept (a test records them instead). */
export function setWaitForTest(sleep: ((ms: number) => Effect.Effect<void>) | null): void {
	sleeper = sleep ?? ((ms) => Effect.sleep(ms));
}

/** Test seam: turn pacing off (the suite's default) or on, from a fresh bucket. */
export function setPacingForTest(on: boolean): void {
	enabled = on;
	bucket = null;
	sinceStore = 0;
}
