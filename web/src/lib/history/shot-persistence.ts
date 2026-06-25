/**
 * `$lib/history/shot-persistence` — the commit-side of a finished espresso shot.
 *
 * Lifts the orchestrator's `ShotCompleted` arm out of `applyCoreOutput` so
 * the steps a shot's completion runs — record the history row, debit the
 * bean, fire the webhook, push to Visualizer, persist the capture slice
 * with its META prelude — live in one place rather than a 187-line switch
 * arm. The orchestrator's caller becomes one line; the live-vs-replay
 * gating, the cross-store reads, and the capture-slice timebase pin all
 * concentrate behind this seam.
 *
 * Replay paths populate `ActiveShot` from the capture file's META before
 * any event fires (see docs/49 §3); the live path populates it at
 * `ShotStarted`. Both converge here on the same `ActiveShotData`, so the
 * commit logic doesn't branch on origin — only on `activeShot.source`
 * for the live-only side effects (bean burn-down, webhook).
 */

import { Effect } from 'effect';
import type { CaptureEntry } from '$lib/capture';
import { getCaptureStore } from '$lib/capture';
import { getBeanStore } from '$lib/bean';
import { getProfileStore } from '$lib/profiles';
import { getActiveShotStore, type ActiveShotData } from '$lib/state/active-shot.svelte';
import { appendSyncLog, directionPushes, readSyncConfig } from '$lib/visualizer';
import type { AppRuntime } from '$lib/effect/runtime';
import type {
	HttpStatusError,
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	TokenRefreshFailedError,
	VisualizerNotFoundError,
	VisualizerPremiumGatedError
} from '$lib/effect/errors';
import { ShotSync } from '$lib/services/shot-sync';
import { UploadQueue } from '$lib/services/upload-queue';
import { getHistoryStore } from './store.svelte';
import type { UiSnapshot } from '$lib/state/ui-state.svelte';

/**
 * How far before `ShotStarted` to begin the persisted capture slice. The
 * Idle→Espresso `MachineStateChanged` arrives a moment before
 * `ShotStarted`; including those entries means a replayed capture
 * produces a clean shot. Generous because the rolling buffer is
 * in-memory and the slice is small.
 */
export const CAPTURE_LEAD_MS = 15_000;

/** The ShotCompleted event content the orchestrator hands us. */
export interface ShotCompletedEvent {
	readonly type: 'ShotCompleted';
	readonly content: {
		readonly duration: number;
		readonly peak_pressure?: number | null;
		readonly peak_temp?: number | null;
		readonly peak_weight?: number | null;
		readonly final_weight?: number | null;
	};
}

/**
 * Everything the commit needs from the orchestrator that isn't a Svelte
 * store. Stores are read directly inside the commit (matching the rest
 * of `$lib`); this carries the cross-references the orchestrator owns.
 */
export interface ShotCompletionContext {
	/** UI snapshot at commit time — telemetry series, `activeProfileName`. */
	readonly snapshot: UiSnapshot;
	/** Core timebase pin for the capture slice's lower bound. `null` when
	 *  no `ShotStarted` was observed — capture persistence is skipped. */
	readonly shotStartedAtMs: number | null;
	/** Core's most-recent notification timestamp; the capture buffer is
	 *  keyed in this timebase. `null` only on the rare pre-notification
	 *  path. */
	readonly lastNotificationAtMs: number | null;
	/** Capture-buffer slicer — wraps `CremaCore.captureSliceJsonl`. */
	sliceJsonl(fromMs: number, toMs: number): Promise<string>;
	/** Fire a webhook (gated by user prefs inside the callback). */
	fireWebhook(eventType: string, payload: object): void;
	/**
	 * The app-wide Effect runtime (Option 3, T-16). The Visualizer push routes
	 * through it (`ShotSync.uploadShot` / `UploadQueue.enqueue`); `null` (no
	 * runtime) skips the push, same fire-and-forget non-fatal posture as before.
	 */
	readonly runtime: AppRuntime | null;
}

/**
 * Commit a finished shot: persist the history row, run live-only side
 * effects, enqueue a Visualizer push, and persist the capture slice.
 * Async work is fire-and-forget — matches the previous in-place
 * behaviour and keeps the orchestrator's event loop non-blocking.
 *
 * Clears the `ActiveShot` store; the orchestrator handles its own
 * timer / brewParams clears since they're orchestrator-owned state.
 */
export function commitShotCompletion(
	event: ShotCompletedEvent,
	ctx: ShotCompletionContext
): void {
	const activeShot = getActiveShotStore().current;
	const record = recordShotHistory(event, activeShot, ctx.snapshot);

	if (record) {
		const isLive = activeShot?.source !== 'replay';
		if (isLive) {
			runLiveOnlySideEffects(event, activeShot, ctx.snapshot, ctx.fireWebhook);
		}
		// Visualizer push — fire-and-forget; recoverable failures land in the
		// retry queue. Runs on the app runtime (T-22) so it sees ShotSync /
		// UploadQueue and the REAL typed errors (no boundary squash); `runFork`
		// is detached, same posture as before. No runtime → skip (non-fatal).
		if (ctx.runtime) ctx.runtime.runFork(pushShotToVisualizer(record.id));
		// Capture persistence — only when we observed a ShotStarted (replays
		// always do; manual / phantom completions might not).
		if (ctx.shotStartedAtMs !== null) {
			const fromMs = ctx.shotStartedAtMs - CAPTURE_LEAD_MS;
			const toMs = ctx.lastNotificationAtMs ?? performance.now();
			void persistCaptureSlice(record.id, fromMs, toMs, ctx.sliceJsonl);
		}
	}

	// The replay path also clears in its `finally`; clear() is idempotent.
	getActiveShotStore().clear();
}

/**
 * Write the history row. Reads bean tags from the live library at commit
 * time (one-shot copy into `shot.tags`); a later edit to the bean's tags
 * does NOT rewrite history — same discipline as `setBeanFromLive`.
 */
function recordShotHistory(
	event: ShotCompletedEvent,
	activeShot: ActiveShotData | null,
	snapshot: UiSnapshot
): { id: string } | null {
	const bean = activeShot?.bean ?? null;
	const brewParams = activeShot?.brewParams ?? null;
	const liveBeanForTags = bean?.beanId ? getBeanStore().getBean(bean.beanId) : null;
	return getHistoryStore().record({
		duration: event.content.duration,
		profileName: activeShot?.profileName ?? snapshot.activeProfileName,
		dose: activeShot?.dose ?? null,
		series: snapshot.shotTelemetry,
		grinderModel: activeShot?.grinderModel ?? null,
		peakPressure: event.content.peak_pressure ?? null,
		peakTemp: event.content.peak_temp ?? null,
		peakWeight: event.content.peak_weight ?? null,
		finalWeight: event.content.final_weight ?? null,
		bean,
		yieldTarget: brewParams?.yieldTarget ?? null,
		brewTemp: brewParams?.brewTemp ?? null,
		preinfuseTarget: brewParams?.preinfuseTarget ?? null,
		stopOnWeight: brewParams?.stopOnWeight,
		autoTare: brewParams?.autoTare,
		tags:
			liveBeanForTags?.tags && liveBeanForTags.tags.length > 0
				? [...liveBeanForTags.tags]
				: []
	});
}

/**
 * Bean burn-down + webhook. Both reach into user state (the user's bag
 * inventory, a user-configured endpoint), so replay paths must skip
 * them — a replay should leave no side effects on user data.
 */
function runLiveOnlySideEffects(
	event: ShotCompletedEvent,
	activeShot: ActiveShotData | null,
	snapshot: UiSnapshot,
	fireWebhook: (eventType: string, payload: object) => void
): void {
	if (activeShot?.bean?.beanId && activeShot.dose) {
		getBeanStore().debitFromActive(activeShot.dose);
	}
	const finalWeight = event.content.final_weight ?? null;
	const dose = activeShot?.dose ?? null;
	const brewRatio =
		finalWeight !== null && dose !== null && dose > 0 ? finalWeight / dose : null;
	const profiles = getProfileStore();
	const activeProfile = profiles.activeId ? profiles.get(profiles.activeId) : undefined;
	fireWebhook('shotCompleted', {
		profileName: activeShot?.profileName ?? snapshot.activeProfileName,
		profileId: activeProfile?.id ?? null,
		duration: event.content.duration,
		finalWeight,
		peakPressure: event.content.peak_pressure ?? null,
		peakTemp: event.content.peak_temp ?? null,
		brewRatio
	});
}

/**
 * Slice the core's rolling buffer over `[fromMs, toMs]`, parse the JSONL
 * back into `CaptureEntry[]`, append the at-shot-start META line, and
 * persist under the shot's id. Replayed shots go through the same path —
 * the bytes the core re-recorded match the bytes we fed in.
 */
async function persistCaptureSlice(
	shotId: string,
	fromMs: number,
	toMs: number,
	sliceJsonl: (fromMs: number, toMs: number) => Promise<string>
): Promise<void> {
	const shotMeta = buildAtShotStartMeta(toMs);
	const jsonl = await sliceJsonl(fromMs, toMs);
	if (!jsonl) {
		return;
	}
	// GEN3: parse per-line so ONE malformed JSONL line can't throw out of the
	// whole function (it's `void`-ed at the call site, which would silently cost
	// the shot its entire replay capture) — skip the bad line and keep the rest.
	const entries: CaptureEntry[] = [];
	for (const line of jsonl.split('\n')) {
		if (line.length === 0) continue;
		try {
			entries.push(JSON.parse(line) as CaptureEntry);
		} catch (err) {
			console.warn('[capture gate] skipping malformed capture line', { shotId, err });
		}
	}
	if (shotMeta) entries.push(shotMeta);
	if (entries.length > 0) {
		// GEN3: surface a rejected `put` instead of dropping it on the floor.
		void getCaptureStore()
			.put(shotId, entries)
			.catch((err) => console.warn('[capture gate] failed to persist capture', { shotId, err }));
	}
}

/**
 * The at-shot-start META `CaptureEntry` appended after the core's
 * connect-phase META prelude. Two META lines is intentional: the
 * core's is wire-side identity the replay driver needs BEFORE the
 * bytes that follow; this one is advisory state an analyst reads.
 * `foldMeta` in `lib/replay/capture.ts` merges both onto one
 * `ReplayMeta` view.
 *
 * `profileBytesHex` is deliberately absent — the core doesn't surface
 * the byte-exact upload payload and caching it shell-side is multi-file
 * plumbing deferred per the task spec.
 */
function buildAtShotStartMeta(tMs: number): CaptureEntry | null {
	const active = getActiveShotStore().current;
	if (!active) return null;

	const meta: Record<string, unknown> = {};
	if (active.profileName) meta.profileName = active.profileName;
	const brewParams = active.brewParams;
	if (brewParams) {
		meta.yieldTarget = brewParams.yieldTarget;
		meta.brewTemp = brewParams.brewTemp;
		meta.preinfuseTarget = brewParams.preinfuseTarget;
		meta.stopOnWeight = brewParams.stopOnWeight;
		meta.autoTare = brewParams.autoTare;
	}
	if (active.bean) {
		const bean: Record<string, unknown> = {};
		const name = active.bean.name?.trim() ?? '';
		const roaster = active.bean.roasterName?.trim() ?? '';
		if (name) bean.name = name;
		if (roaster) bean.roaster = roaster;
		if (active.bean.roastedOn) bean.roastedOn = active.bean.roastedOn;
		if (active.bean.roastLevel != null) bean.roastLevel = active.bean.roastLevel;
		if (active.bean.notes) bean.notes = active.bean.notes;
		if (active.bean.grinderSetting) bean.grinderSetting = active.bean.grinderSetting;
		if (Object.keys(bean).length > 0) meta.bean = bean;
	}
	if (active.grinderModel) meta.grinderModel = active.grinderModel;
	if (Object.keys(meta).length === 0) return null;
	return { t: Math.round(tMs), dir: 'in', src: 'META', hex: '', meta };
}

/** Every failure `ShotSync.uploadShot` can surface. */
type ShotPushError =
	| NetworkError
	| HttpStatusError
	| NotAuthenticatedError
	| TokenRefreshFailedError
	| VisualizerPremiumGatedError
	| VisualizerNotFoundError
	| ResponseDecodeError;

/**
 * Whether a `ShotSync.uploadShot` failure is worth a time-based retry:
 * transport / 5xx / 408 are recoverable; auth / premium / not-found / decode
 * need user action. Matches `UploadQueue`'s own classifier — same recoverable
 * set, now switched on the REAL tagged error rather than the old squashed
 * boundary shape.
 */
function isRecoverable(e: ShotPushError): boolean {
	switch (e._tag) {
		case 'NetworkError':
			return true;
		case 'HttpStatusError':
			return e.status === 0 || e.status === 408 || (e.status >= 500 && e.status < 600);
		default:
			return false;
	}
}

/** Human-readable one-liner for the sync-log entry / queued retry. */
function describePushError(e: ShotPushError): string {
	switch (e._tag) {
		case 'HttpStatusError':
			return `HTTP ${e.status}: ${e.body ?? ''}`.trim();
		case 'NetworkError':
			return `Network error: ${e.cause instanceof Error ? e.cause.message : String(e.cause)}`;
		case 'VisualizerPremiumGatedError':
			return 'Premium subscription required for writes.';
		case 'VisualizerNotFoundError':
			return `Visualizer row not found: ${e.visualizerId}`;
		case 'NotAuthenticatedError':
			return 'Not signed in to Visualizer.';
		case 'TokenRefreshFailedError':
			return 'Visualizer session expired.';
		case 'ResponseDecodeError':
			return 'Unexpected Visualizer response.';
	}
}

/**
 * Fire-and-forget Visualizer shot push as an Effect (T-22), gated on the user's
 * shot sync direction + the `visualizerAutoUpload` setting. `ShotSync.uploadShot`
 * does the authenticated POST + soft follow-up PATCH; on success it binds the
 * returned id + logs the push.
 *
 * Recoverable failures route through the persistent retry queue; auth / premium
 * / not-found / decode bypass it (the user has to fix something). Because this
 * runs *on the app runtime* — not across the Promise boundary — the classifier
 * + log see the original `Data.TaggedError`s directly via `catchAll`, retiring
 * the `isRecoverableUploadError` string-`_tag` probe the boundary forced (R-05).
 *
 * The error channel is `never`: every failure is caught here (queued and/or
 * logged), so the orchestrator's `runFork` stays a clean detached fire.
 */
export function pushShotToVisualizer(
	shotId: string
): Effect.Effect<void, never, ShotSync | UploadQueue> {
	return Effect.gen(function* () {
		const config = readSyncConfig();
		if (!directionPushes(config.direction.shots)) return;
		if (!config.autoUpload) return;
		const shot = getHistoryStore().get(shotId);
		if (!shot) return;

		const shotSync = yield* ShotSync;
		const queue = yield* UploadQueue;
		const name = shot.profileName ?? 'Shot';

		yield* shotSync.uploadShot(shot).pipe(
			Effect.flatMap(({ visualizerId }) =>
				Effect.sync(() => {
					getHistoryStore().bindVisualizerId(shotId, visualizerId);
					appendSyncLog({ direction: 'push', entity: 'shot', id: shotId, name, at: Date.now() });
				})
			),
			Effect.catchAll((e) =>
				Effect.gen(function* () {
					if (isRecoverable(e)) {
						yield* queue.enqueue({
							entity: 'shot',
							id: shotId,
							op: 'create',
							error: describePushError(e)
						});
					}
					appendSyncLog({
						direction: 'skip',
						entity: 'shot',
						id: shotId,
						name,
						at: Date.now(),
						error: describePushError(e)
					});
				})
			)
		);
	});
}
