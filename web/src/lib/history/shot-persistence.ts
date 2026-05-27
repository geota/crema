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

import type { CaptureEntry } from '$lib/capture';
import { getCaptureStore } from '$lib/capture';
import { getBeanStore, VisualizerError } from '$lib/bean';
import { getProfileStore } from '$lib/profiles';
import { getSettingsStore } from '$lib/settings';
import { getActiveShotStore, type ActiveShotData } from '$lib/state/active-shot.svelte';
import {
	appendSyncLog,
	directionPushes,
	enqueue as enqueueSyncOp,
	readSyncConfig,
	uploadShot
} from '$lib/visualizer';
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
		// Visualizer push — fire-and-forget; failures land in the retry queue.
		tryUploadShot(record.id);
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
		console.log('[capture gate] captureSliceJsonl returned empty', { fromMs, toMs, shotId });
		return;
	}
	const entries: CaptureEntry[] = jsonl
		.split('\n')
		.filter((line) => line.length > 0)
		.map((line) => JSON.parse(line) as CaptureEntry);
	if (shotMeta) entries.push(shotMeta);
	if (entries.length > 0) {
		console.log('[capture gate] persisting', { shotId, entryCount: entries.length });
		void getCaptureStore().put(shotId, entries);
	} else {
		console.log('[capture gate] parsed entries empty', { shotId });
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

/**
 * Fire-and-forget Visualizer shot upload, gated on the user's shot sync
 * direction + the `visualizerAutoUpload` setting. Recoverable failures
 * route through the persistent retry queue; auth + premium errors
 * bypass the queue (the user has to fix something).
 */
function tryUploadShot(shotId: string): void {
	const config = readSyncConfig();
	if (!directionPushes(config.direction.shots)) return;
	if (!getSettingsStore().current.visualizerAutoUpload) return;
	const shot = getHistoryStore().get(shotId);
	if (!shot) return;
	void uploadShot(shot)
		.then(({ visualizerId }) => {
			getHistoryStore().bindVisualizerId(shotId, visualizerId);
			appendSyncLog({
				direction: 'push',
				entity: 'shot',
				id: shotId,
				name: shot.profileName ?? 'Shot',
				at: Date.now()
			});
		})
		.catch((e) => {
			const recoverable =
				e instanceof VisualizerError
					? e.kind === 'network' || (e.status >= 500 && e.status < 600) || e.status === 408
					: true;
			if (recoverable) {
				enqueueSyncOp({
					entity: 'shot',
					id: shotId,
					op: 'create',
					error: e instanceof Error ? e.message : String(e)
				});
			}
			appendSyncLog({
				direction: 'skip',
				entity: 'shot',
				id: shotId,
				name: shot.profileName ?? 'Shot',
				at: Date.now(),
				error: e instanceof Error ? e.message : String(e)
			});
		});
}
