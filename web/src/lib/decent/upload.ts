/**
 * `$lib/decent/upload` — push a stored shot to the linked Decent account.
 *
 * The shot-completion hook fires {@link pushShotToDecent} next to the
 * Visualizer push; History's detail action calls {@link uploadShotToDecent}
 * directly, and the catch-up paths (Settings, History "Upload N") drain the
 * backlog through {@link uploadUnsentDecentShots}. Gates, in order: an
 * account is linked, auto-upload is on (auto path only), the login is still
 * good, the shot is longer than {@link MIN_SHOT_SECONDS} (auto path only —
 * skips flushes; de1app and decaid use the same 5 s floor), Decent has not
 * refused it before (auto path only), and a machine serial is known (stamped
 * on the shot, else the connected DE1 — never for a shot pulled from
 * Visualizer, which was not made on this DE1).
 *
 * Concurrency: one upload per shot at a time — a second caller for the same
 * shot gets the pending promise (so a double tap, or the completion hook
 * racing a manual tap, POSTs once) — and one backlog drain at a time.
 *
 * Retries: a network failure / 5xx is retried twice in-call with an Effect
 * `Schedule` (2 s, then 4 s; not while the browser reports offline). A shot
 * still failing on the network is remembered on the account
 * (`retryShotIds`) and retried by {@link retryPendingDecentUploads} when the
 * connection comes back and on the next launch. A permanent rejection is
 * remembered too (`rejectedShotIds`) so the backlog stops offering it; an
 * auth failure flags the account `needsReauth`. The server's id is bound to
 * the shot as `decentId`.
 */

import { Effect, Schedule } from 'effect';
import { getHistoryStore } from '$lib/history/store.svelte';
import { reportUploadOutcome } from '$lib/history/upload-toast';
import { appendSyncLog } from '$lib/visualizer/sync-config';
import { toCoreShotMachine, type StoredShot } from '$lib/history/model';
import type { ShotMachine, TimedSample } from '$lib/core/crema-core';
import { getMachineReadout } from '$lib/state/machine-readout.svelte';
import {
	getDecentCredentials,
	isDecentLinked,
	readDecentAccount,
	setDecentShotState,
	updateDecentAccount
} from './account';
import {
	DecentAuthError,
	decentShotViewUrl,
	decentUploadShot,
	isDecentApiError,
	isDecentRecoverable,
	type DecentApiError,
	type DecentCredentials,
	type DecentUploadResult,
	type FetchLike
} from './api';
import { DecentRecordError, decentModelName, decentShotRecord, shotDurationSeconds } from './shot-record';

export const MIN_SHOT_SECONDS = 5;
/** In-call retries after the first attempt (so three attempts in all). */
const RETRIES = 2;
/** Failures in a row (rejections, server errors) after which a drain gives up. */
const FAILURE_STREAK_LIMIT = 3;

export type DecentUploadError = DecentApiError | DecentRecordError;

export type DecentUploadOutcome =
	| { kind: 'uploaded'; id: string | null; url: string | null }
	| { kind: 'skipped'; reason: string }
	| { kind: 'failed'; error: DecentUploadError };

export interface DecentUploadOptions {
	/** An explicit tap: skips the auto-upload, length and "refused before" gates. */
	manual?: boolean;
	/** Re-upload over the server's copy of an already-uploaded shot. */
	replace?: boolean;
	/** Full-resolution telemetry when the caller still has it. */
	samples?: readonly TimedSample[];
	fetchFn?: FetchLike;
	appVersion?: string;
}

/** The connected DE1's identity, or `null` before the MMR sweep has read a serial. */
export function liveMachineIdentity(): ShotMachine | null {
	const m = getMachineReadout();
	const serial = m.serialNumber;
	if (serial == null) return null;
	const firmware = m.firmwareString ?? (m.firmwareBuild != null ? String(m.firmwareBuild) : null);
	const model = decentModelName(m.machineModel);
	return {
		serialNumber: String(serial),
		...(firmware ? { firmwareVersion: firmware } : {}),
		...(model ? { model } : {})
	};
}

/** Pulled from Visualizer (`storedShotFromWire` mints `shot:remote:<id>`), not recorded here. */
export function isPulledShot(shot: StoredShot): boolean {
	return shot.id.startsWith('shot:remote:');
}

/** The identity to upload with, and whether it came from the live DE1 (so it gets stamped). */
function machineFor(shot: StoredShot): { machine: ShotMachine; fromLive: boolean } | null {
	if (shot.machine?.serialNumber) return { machine: toCoreShotMachine(shot.machine), fromLive: false };
	if (isPulledShot(shot)) return null;
	const live = liveMachineIdentity();
	return live ? { machine: live, fromLive: true } : null;
}

const isOffline = (): boolean => typeof navigator !== 'undefined' && navigator.onLine === false;

/** 2 s, then 4 s — the same backoff as before, now an Effect `Schedule`. */
const retrySchedule = Schedule.exponential('2 seconds').pipe(Schedule.intersect(Schedule.recurs(RETRIES)));

/**
 * POST with the in-call retry. Only the api's typed failures are expected
 * (`isDecentApiError`); anything else is a bug and surfaces as a defect
 * (the promise rejects) rather than being retried as if it were a network
 * blip. No retry while the browser reports offline — it cannot succeed.
 */
function postWithRetry(
	creds: DecentCredentials,
	record: string,
	replace: boolean,
	fetchFn: FetchLike | undefined
): Effect.Effect<DecentUploadResult, DecentApiError> {
	const attempt = Effect.tryPromise({
		try: () => decentUploadShot(creds, record, { replace }, fetchFn),
		catch: (e) => e
	}).pipe(Effect.catchAll((e) => (isDecentApiError(e) ? Effect.fail(e) : Effect.die(e))));
	return attempt.pipe(
		Effect.retry({ schedule: retrySchedule, while: (e) => isDecentRecoverable(e) && !isOffline() })
	);
}

function logFailure(shot: StoredShot, error: DecentUploadError): void {
	appendSyncLog({
		destination: 'decent',
		direction: 'skip',
		entity: 'shot',
		id: shot.id,
		name: shot.profileName ?? 'Shot',
		at: Date.now(),
		error: error.message
	});
}

/** Record a failure on the account + log, and answer `failed`. */
function failed(shot: StoredShot, error: DecentUploadError): DecentUploadOutcome {
	const lastUpload = { at: Date.now(), ok: false, message: error.message };
	switch (error._tag) {
		case 'DecentAuthError':
			updateDecentAccount({ needsReauth: true, lastUpload });
			break;
		case 'DecentNetworkError':
			setDecentShotState(shot.id, 'retry');
			updateDecentAccount({ lastUpload });
			break;
		case 'DecentRejectedError':
		case 'DecentRecordError':
			setDecentShotState(shot.id, 'rejected');
			updateDecentAccount({ lastUpload });
			break;
	}
	logFailure(shot, error);
	return { kind: 'failed', error };
}

const skipped = (reason: string): DecentUploadOutcome => ({ kind: 'skipped', reason });

async function runUpload(shotId: string, opts: DecentUploadOptions): Promise<DecentUploadOutcome> {
	const account = readDecentAccount();
	if (!isDecentLinked(account)) return skipped('No Decent account linked');
	if (!opts.manual && !account.autoUpload) return skipped('Auto-upload is off');
	if (account.needsReauth) return skipped('Decent login needs signing in again');
	// `null` here means the stored token could not be unwrapped; the account is
	// now flagged `needsReauth`, so later pushes skip quietly and Settings asks
	// the user to sign in again. Answer this one as an auth failure.
	const creds = await getDecentCredentials(account);
	if (!creds) return { kind: 'failed', error: new DecentAuthError({ status: 0 }) };

	// Read the shot only now, after the await above and right before the POST,
	// so the gates and the record see the latest row (a racing upload may have
	// bound it, or the user deleted it).
	const history = getHistoryStore();
	const shot = history.get(shotId);
	if (!shot || shot.deletedAt) return skipped('Shot not found');
	if (shot.decentId && !opts.replace) return skipped('Already on Decent');
	if (!opts.manual && shotDurationSeconds(shot) < MIN_SHOT_SECONDS) {
		return skipped(`Shorter than ${MIN_SHOT_SECONDS} s`);
	}
	if (!opts.manual && account.rejectedShotIds.includes(shotId)) {
		return skipped('Decent refused this shot before — upload it from History to try again');
	}
	const target = machineFor(shot);
	if (!target) {
		return skipped(
			isPulledShot(shot)
				? 'Pulled from Visualizer — not recorded on this DE1'
				: 'No DE1 serial number known — connect the machine first'
		);
	}

	let record: string;
	try {
		record = decentShotRecord(shot, target.machine, opts.appVersion ?? __APP_VERSION__, opts.samples);
	} catch (e) {
		if (e instanceof DecentRecordError) return failed(shot, e);
		throw e;
	}

	const result = await Effect.runPromise(
		Effect.either(postWithRetry(creds, record, !!opts.replace, opts.fetchFn))
	);
	if (result._tag === 'Left') return failed(shot, result.left);

	const id = result.right.id;
	const url = decentShotViewUrl(target.machine.serialNumber, id);
	// A pre-#84 row uploaded with the live DE1's serial keeps that identity,
	// so the share link (`/shot/<serial>/<id>`) works later.
	history.bindDecentId(shotId, id ?? `uploaded:${Date.now()}`, target.fromLive ? target.machine : undefined);
	setDecentShotState(shotId, 'clear');
	updateDecentAccount({
		needsReauth: false,
		lastUpload: { at: Date.now(), ok: true, message: 'Uploaded', ...(url ? { url } : {}) }
	});
	appendSyncLog({
		destination: 'decent',
		direction: 'push',
		entity: 'shot',
		id: shotId,
		name: shot.profileName ?? 'Shot',
		at: Date.now()
	});
	return { kind: 'uploaded', id, url };
}

/** Uploads in flight, by shot id — a second caller joins the first. */
const inFlight = new Map<string, Promise<DecentUploadOutcome>>();

/**
 * Upload one shot. A call for a shot that is already uploading returns that
 * upload's promise (with the first caller's options), so the shot is POSTed
 * once. See {@link DecentUploadOptions}.
 */
export function uploadShotToDecent(
	shotId: string,
	opts: DecentUploadOptions = {}
): Promise<DecentUploadOutcome> {
	const pending = inFlight.get(shotId);
	if (pending) return pending;
	const run = runUpload(shotId, opts).finally(() => inFlight.delete(shotId));
	inFlight.set(shotId, run);
	return run;
}

/** Report an outcome to the per-shot toast batch (`$lib/history/upload-toast`). */
export function reportDecentOutcome(shotId: string, outcome: DecentUploadOutcome): void {
	if (outcome.kind === 'uploaded') reportUploadOutcome(shotId, 'Decent', { kind: 'uploaded' });
	else if (outcome.kind === 'failed') reportUploadOutcome(shotId, 'Decent', { kind: 'failed', message: outcome.error.message });
	else reportUploadOutcome(shotId, 'Decent', { kind: 'skipped', message: outcome.reason });
}

/**
 * Upload + report, never rejecting: an unexpected throw is reported as a
 * failure so the shot's toast batch does not wait out its timeout.
 */
export async function uploadAndReportDecent(shotId: string, opts: DecentUploadOptions = {}): Promise<void> {
	try {
		reportDecentOutcome(shotId, await uploadShotToDecent(shotId, opts));
	} catch (e) {
		console.warn('[Crema] Decent upload threw:', e);
		reportUploadOutcome(shotId, 'Decent', { kind: 'failed', message: e instanceof Error ? e.message : String(e) });
	}
}

/**
 * The shot-completion push, as a never-failing Effect so the orchestrator's
 * `runFork` stays a clean detached fire (same posture as `pushShotToVisualizer`).
 * Answers the shot's toast batch — a silent miss reads as "synced".
 */
export function pushShotToDecent(shotId: string): Effect.Effect<void> {
	return Effect.promise(() => uploadAndReportDecent(shotId));
}

/** What the backlog filter needs to know besides the shots. */
export interface DecentBacklogContext {
	/** A DE1 is connected with a known serial (old rows without a stamped machine borrow it). */
	hasLiveMachine: boolean;
	/** Shots Decent refused for good — see `DecentAccountState.rejectedShotIds`. */
	rejectedShotIds: readonly string[];
}

/**
 * Shots a catch-up would actually upload — the Settings / History backlog.
 * Excludes: already uploaded, deleted, flushes under 5 s, shots Decent
 * refused, shots pulled from Visualizer without a stamped machine, and
 * unstamped rows while no DE1 serial is known.
 */
export function unsentDecentShots(
	shots: readonly StoredShot[],
	ctx: DecentBacklogContext = {
		hasLiveMachine: liveMachineIdentity() !== null,
		rejectedShotIds: readDecentAccount().rejectedShotIds
	}
): StoredShot[] {
	const rejected = new Set(ctx.rejectedShotIds);
	return shots.filter(
		(s) =>
			!s.decentId &&
			!s.deletedAt &&
			shotDurationSeconds(s) >= MIN_SHOT_SECONDS &&
			!rejected.has(s.id) &&
			(s.machine?.serialNumber ? true : !isPulledShot(s) && ctx.hasLiveMachine)
	);
}

/** Why a drain stopped early: auth failed, the network is gone, or too many failures in a row. */
export type DecentDrainStop = 'auth' | 'offline' | 'failures';

export interface DecentDrainResult {
	uploaded: number;
	failed: number;
	skipped: number;
	/** `null` = ran to the end. */
	stopped: DecentDrainStop | null;
	/** The last failure's message, for the notice. */
	lastError: string | null;
}

/** One drain at a time, across Settings catch-up, History "Upload N" and the reconnect retry. */
let drainRun: Promise<DecentDrainResult> | null = null;

function withDrainLock(run: () => Promise<DecentDrainResult>): Promise<DecentDrainResult> {
	if (drainRun) return drainRun;
	const current = run().finally(() => {
		if (drainRun === current) drainRun = null;
	});
	drainRun = current;
	return current;
}

/** Is a backlog drain running right now? */
export function isDecentDrainRunning(): boolean {
	return drainRun !== null;
}

async function drain(
	ids: readonly string[],
	opts: DecentUploadOptions & { onProgress?: (done: number, total: number) => void }
): Promise<DecentDrainResult> {
	const out: DecentDrainResult = { uploaded: 0, failed: 0, skipped: 0, stopped: null, lastError: null };
	let streak = 0;
	for (let i = 0; i < ids.length; i++) {
		let outcome: DecentUploadOutcome;
		try {
			outcome = await uploadShotToDecent(ids[i], opts);
		} catch (e) {
			console.warn('[Crema] Decent upload threw:', e);
			outcome = {
				kind: 'failed',
				error: new DecentRecordError({ detail: e instanceof Error ? e.message : String(e) })
			};
		}
		if (outcome.kind === 'uploaded') {
			out.uploaded += 1;
			streak = 0;
		} else if (outcome.kind === 'skipped') {
			out.skipped += 1;
		} else {
			const err = outcome.error;
			out.failed += 1;
			out.lastError = err.message;
			if (err._tag === 'DecentAuthError') {
				out.stopped = 'auth';
			} else if (err._tag === 'DecentNetworkError' && err.status === null) {
				// No HTTP status = no connection: every remaining shot would fail the same way.
				out.stopped = 'offline';
			} else if (++streak >= FAILURE_STREAK_LIMIT) {
				out.stopped = 'failures';
			}
		}
		opts.onProgress?.(i + 1, ids.length);
		if (out.stopped) break;
	}
	return out;
}

/**
 * Drain the backlog ({@link unsentDecentShots}) oldest-first, as manual
 * uploads. Stops at the first auth failure, the first failure with no HTTP
 * status (offline), or after {@link FAILURE_STREAK_LIMIT} failures in a row
 * (rejections or server errors). A drain already running (another surface
 * started it) is joined instead of started twice.
 */
export function uploadUnsentDecentShots(
	opts: { fetchFn?: FetchLike; onProgress?: (done: number, total: number) => void } = {}
): Promise<DecentDrainResult> {
	return withDrainLock(() => {
		const backlog = unsentDecentShots(getHistoryStore().all).sort((a, b) => a.completedAt - b.completedAt);
		return drain(
			backlog.map((s) => s.id),
			{ manual: true, fetchFn: opts.fetchFn, onProgress: opts.onProgress }
		);
	});
}

/**
 * Retry the automatic uploads that failed on the network (`retryShotIds`) —
 * called when the browser comes back online and at launch. Same gates as
 * the auto path; a shot the gates now skip is dropped from the set. `null`
 * when there is nothing to do (not linked, auto-upload off, re-auth needed,
 * offline, or an empty set).
 */
export async function retryPendingDecentUploads(
	opts: { fetchFn?: FetchLike } = {}
): Promise<DecentDrainResult | null> {
	const account = readDecentAccount();
	if (!isDecentLinked(account) || !account.autoUpload || account.needsReauth) return null;
	if (account.retryShotIds.length === 0 || isOffline()) return null;
	const ids = [...account.retryShotIds];
	const result = await withDrainLock(() => drain(ids, { fetchFn: opts.fetchFn }));
	// Anything the gates skipped (deleted, already uploaded, …) will never go — forget it.
	const history = getHistoryStore();
	for (const id of ids) {
		const shot = history.get(id);
		if (!shot || shot.deletedAt || shot.decentId) setDecentShotState(id, 'clear');
	}
	return result;
}

/** A one-line notice for a finished drain (Settings catch-up, History "Upload N"). */
export function describeDecentDrain(
	r: DecentDrainResult
): { kind: 'success' | 'error' | 'info'; message: string } | null {
	const done = `${r.uploaded} shot${r.uploaded === 1 ? '' : 's'} uploaded to Decent`;
	if (r.stopped === 'auth') {
		return { kind: 'error', message: `${done} — stopped: the Decent login stopped working, sign in again in Settings.` };
	}
	if (r.stopped === 'offline') {
		return { kind: 'error', message: `${done} — stopped: no connection to decentespresso.com.` };
	}
	if (r.stopped === 'failures') {
		return {
			kind: 'error',
			message: `${done} — stopped after ${FAILURE_STREAK_LIMIT} failures in a row${r.lastError ? `: ${r.lastError}` : ''}`
		};
	}
	if (r.failed > 0) return { kind: 'error', message: `${done}, ${r.failed} failed${r.lastError ? `: ${r.lastError}` : ''}` };
	if (r.uploaded > 0) return { kind: 'success', message: done };
	if (r.skipped > 0) return { kind: 'info', message: `Nothing uploaded to Decent — ${r.skipped} skipped.` };
	return null;
}
