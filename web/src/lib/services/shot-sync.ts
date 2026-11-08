/**
 * `#lib/services/shot-sync` — the `ShotSync` service (docs/53 §1.3, §2.4, T-12).
 *
 * Syncs Crema's local shot library against Visualizer's `/shots*` endpoints (per the
 * vendored OpenAPI spec at `../visualizer/openapi.json`). It consumes
 * `HttpClient` + `TokenVault`:
 *
 *  - every authenticated call funnels through `TokenVault.withFreshToken`
 *    (proactive refresh + one-shot 401 retry), exactly as the old `call()`
 *    funnelled through `withFreshToken`;
 *  - `HttpClient.request` raises `HttpStatusError` on any non-2xx, which `call`
 *    maps back onto the Visualizer error taxonomy: a premium 402/403 → premium-gated,
 *    404 → not-found, 401 handled upstream by `TokenVault`, everything else
 *    propagates as `HttpStatusError` (the old `'other'` bucket).
 *
 * **Behavior is preserved bit-for-bit** with the old module (plan §4): same wire
 * bytes, same premium-gate probe, the soft follow-up PATCH on upload, the
 * `iterate` pagination walk's stop condition, the per-detail
 * re-throw-on-auth / swallow-others, and the snapshot-vs-live-bean discipline.
 * The pure converters (`wireShotFromDetail`, `samplesFromVisualizerDetail`, the
 * inline-bean / grinder / tag resolvers, `buildShotPayload`) are copied verbatim
 * from the old module; the pinned-digest signature helpers stay in
 * `../visualizer/shot-sync-signatures.ts` (untouched).
 *
 * `ShotSyncLive` is the production implementation, composed into `AppLayer`.
 *
 * **No automated behavioral coverage**: `shot-sync.test.ts` drives the wasm
 * bundle directly, not this module, and `ShotSync` is store-coupled
 * (`#lib/bean`, `$lib/history`, `$lib/settings`) so it isn't node:test-able.
 * Correctness rests on the careful port below + a manual browser smoke test
 * (connect a real Visualizer account; upload + delete a shot; pull/reconcile).
 */

import { Context, Effect, Layer, Option } from 'effect';
import { HttpClient } from './http-client.ts';
import { TokenVault } from './token-vault.ts';
import {
	NetworkError,
	NotAuthenticatedError,
	ResponseDecodeError,
	ShotWithoutSeriesError,
	VisualizerNotFoundError
} from '../effect/errors.ts';
import {
	API_BASE,
	describeVisualizerError,
	isAccountBlocked,
	isRateLimited,
	isRecoverable,
	pendingEditFailure,
	quotaLimit,
	rowFailureOf,
	refusedUpload,
	quotaNotice,
	RATE_LIMIT_NOTICE,
	retryDelayMs,
	visualizerCall,
	type VisualizerCallError,
	type VisualizerCallOptions
} from './visualizer-call.ts';
import {
	ShotUploadResultSchema,
	ShotListResponseSchema,
	decodeResponse
} from '../effect/schema/visualizer.ts';
import { getBeanStore } from '#lib/bean/store.svelte.js';
import { readSyncSettings } from '#lib/bean/visualizer-sync.js';
import { historyFullNotice } from '#lib/history/brew-date.js';
import { effectiveGrindSetting } from '#lib/history/index.js';
import { exportStoredShotAsV2Json } from '#lib/history/v2-export.js';
import type { ShotPatchInputs, TimedSample } from '#lib/core/index.js';
import {
	ListStep,
	PartialReason,
	SkipReason,
	type KeyCandidate,
	type KnownRow,
	type ListRow,
	type PullTally,
	type RowAction,
	type RowFailure,
	type RowOutcome,
	type RunOutcome,
	type ShotMergeInput,
	type ShotPatch as RemoteShotPatch,
	type TagMarks
} from '#lib/core/crema-core.js';
import { pullRules } from '#lib/visualizer/pull-run-core.js';
import { noteRateLimited, storePace, waitFor } from '#lib/visualizer/pace.js';
import { acquireRunLock } from '#lib/visualizer/run-lock.js';
import { readPoison, writePoison } from '#lib/visualizer/poison.js';
import { restoreFrozen, restoringInThisTab } from '#lib/restore/tab-safety.js';
import { coolingDown } from '#lib/visualizer/pace.js';
import { isBrewLog, refusedNoSamples, type ShotBean, type StoredShot } from '#lib/history/model.js';
import type { HistoryStore } from '#lib/history/store.svelte.js';
import { getSettingsStore } from '#lib/settings/index.js';
import type { components } from '#lib/visualizer/openapi.js';
import {
	reconcileShots,
	signatureForShot,
	storedShotFromWire,
	type WireShot
} from '#lib/visualizer/shot-sync-signatures.js';
import {
	appendSyncLog,
	excludedFromUpload,
	readSyncConfig,
	updateSyncConfig,
	uploadsHeld
} from '#lib/visualizer/sync-config.js';
import {
	hasTagMarks,
	markShotEdit,
	pendingEdit,
	pendingEdits,
	settlePendingEdit,
	type PendingEdit
} from '#lib/visualizer/pending-edits.js';
import {
	mergeRemoteShot as wasmMergeRemoteShot,
	normalizeTags as wasmNormalizeTags,
	settleTagPush as wasmSettleTagPush,
	tagPushList as wasmTagPushList,
	samplesFromVisualizerDetail as wasmSamplesFromVisualizerDetail,
	wireShotFromDetail as wasmWireShotFromDetail,
	parseV2Profile as wasmParseV2Profile,
	visualizerShotPatchJson as wasmVisualizerShotPatchJson
} from '#lib/wasm/de1_wasm.js';
import { enqueueEntry as enqueueSyncOp } from './queue-store.ts';
import {
	beginPullRun,
	clearUploadAttempted,
	expiredTombstones,
	markUploadAttempted,
	parkedTombstones,
	remoteKeptOut,
	uploadReturned
} from '#lib/visualizer/upload-marks.js';
import { ACCOUNT_HELD_NOTICE } from './account-held.ts';

// Spec-typed aliases — these ride DIRECTLY on the wire so they stay
// in lock-step with the OpenAPI spec (regenerate via `pnpm openapi`).
type ShotSummary = components['schemas']['ShotSummary'];
type Paging = components['schemas']['Paging'];
type ShotDetail = components['schemas']['ShotDetail'];
type ShotUpdateRequest = components['schemas']['ShotUpdateRequest'];

export type { ShotSummary, ShotDetail, Paging };

// ── Settings-aware payload builder (copied verbatim from the old module) ──

/**
 * Build the JSON payload `POST /shots/upload` accepts. Crema's
 * `exportStoredShotAsV2Json` produces the Decent v2 shape; applies the four
 * `visualizer*` user settings (privacy, includeProfile, includeNotes) so the
 * user's choices ride on the wire, plus a Crema-side escape-valve block so the
 * round-trip back can re-bind.
 */
function buildShotPayload(shot: StoredShot): Record<string, unknown> {
	const cfg = readSyncConfig();
	// The upload document carries the EFFECTIVE grind (issue #16) — the
	// shot's recorded/edited value, else the bean snapshot's reference —
	// exactly what History displays. The stored row keeps the RAW per-shot
	// value only, so persistence/backups never materialise the fallback.
	// Android's WireShot upload does the same.
	const json = JSON.parse(
		exportStoredShotAsV2Json(
			{
				...shot,
				metadata: { ...(shot.metadata ?? {}), grinderSetting: effectiveGrindSetting(shot) }
			},
			// A RE-upload (already bound) keeps the post-flow tail: Visualizer
			// de-dupes by a SHA over the telemetry columns, so reproducing the
			// original series updates the SAME remote row in place (fixing the
			// date + journal fields) instead of minting a duplicate.
			{ fullSeries: shot.visualizerId != null }
		)
	) as Record<string, unknown>;

	if (!cfg.includeProfile) {
		delete json.profile;
	}
	if (!cfg.includeNotes) {
		const metadata = (json.metadata as Record<string, unknown> | undefined) ?? null;
		if (metadata && 'notes' in metadata) {
			metadata.notes = null;
		}
		// The v2 document carries the notes text in two more slots —
		// `meta.shot.notes` (rides the raw file Visualizer keeps
		// downloadable) and the journal block its parser captures
		// (`app.data.settings.espresso_notes`). An opt-out must strip both.
		const metaShot = ((json.meta as Record<string, unknown> | undefined)?.shot ?? null) as Record<
			string,
			unknown
		> | null;
		if (metaShot && 'notes' in metaShot) {
			metaShot.notes = '';
		}
		const settings = (((json.app as Record<string, unknown> | undefined)?.data as
			| Record<string, unknown>
			| undefined)?.settings ?? null) as Record<string, unknown> | null;
		if (settings) {
			delete settings.espresso_notes;
		}
	}

	// Per-shot override wins; absent/null inherits the sync-config default.
	json.privacy = shot.privacy ?? cfg.privacy;
	json.metadata = {
		...((json.metadata as Record<string, unknown> | undefined) ?? {}),
		crema: {
			localId: shot.id,
			signature: signatureForShot(shot),
			appVersion: '0.0.1'
		}
	};

	return json;
}

// ── Wire / Crema boundary helpers (copied verbatim from the old module) ───

/**
 * Convert a full `ShotDetail` (returned from `GET /shots/{id}`) plus the
 * originating summary's `clock` / `updated_at` into Crema's {@link WireShot}
 * (unix MS, normalised field names). Visualizer's default detail flattens
 * scoring + journal fields onto the shot row; Beanconqueror's variant nests them
 * under `meta.visualizer` — both shapes are read defensively in the Rust core
 * (`de1_domain::visualizer_wire::wire_shot_from_detail`, CORE1). `clock` /
 * `updated_at` ride in as unix SECONDS; the core converts to ms.
 */
function wireShotFromDetail(summary: ShotSummary, detail: ShotDetail): WireShot {
	const raw = wasmWireShotFromDetail(
		JSON.stringify({
			summary: { id: summary.id, clock: summary.clock, updated_at: summary.updated_at },
			detail
		})
	);
	return JSON.parse(raw) as WireShot;
}

/**
 * Reconstruct Crema's per-sample telemetry from a Visualizer shot detail.
 *
 * Visualizer stores the curve as parallel columns — a time axis plus one array
 * per channel — zipped by index into the row-shaped {@link TimedSample} the
 * history chart + peak derivation consume (time axis SECONDS → ms). A detail
 * with no time axis (a Beanconqueror row, or a curveless shot) yields `[]`.
 * Delegates to `de1_domain::visualizer_wire::samples_from_visualizer_detail`
 * (CORE1) so every shell zips the columns identically.
 */
function samplesFromVisualizerDetail(detail: ShotDetail): TimedSample[] {
	return JSON.parse(wasmSamplesFromVisualizerDetail(JSON.stringify(detail))) as TimedSample[];
}

/** A shot's barista as the PATCH sends it: trimmed, `""` when unset or cleared. */
function baristaOf(shot: StoredShot): string {
	return shot.metadata.barista?.trim() ?? '';
}

// ── PATCH-body resolvers ──────────────────────────────────────────────────
//
// The body ASSEMBLY (inline-bean field mapping, rating→flavor, key naming,
// blank-skipping) lives in the core — `de1_domain::visualizer_shot_patch`,
// review #42: this module and Android's `VisualizerSync` had drifted (the
// Kotlin builder sent only `bean_brand`/`bean_type`, and the shells
// disagreed on what a cleared rating does to `flavor`). What remains here
// are the SHELL-side resolvers: config lookups and the live-vs-snapshot
// reading discipline the core can't know about.

/**
 * Resolve the shot's equipment-level grinder model for the post-upload PATCH.
 * Cascade (per #81): snapshot {@link StoredShot.grinderModel} wins, then the
 * equipment-level `settings.prefs.grinderModel` default (legacy fallback), then
 * `null` (omit the field so a blank doesn't clobber a Visualizer-side edit).
 */
export function resolveGrinderModel(
	shot: StoredShot,
	settingsDefault: string | undefined | null
): string | null {
	const fromShot = shot.grinderModel?.trim();
	if (fromShot) return fromShot;
	const fromSettings = settingsDefault?.trim();
	if (fromSettings) return fromSettings;
	return null;
}

/**
 * Resolve the `coffee_bag_id` link pointer for a shot's post-upload PATCH. The
 * snapshot is the content source of truth — but the bag's *Visualizer id* is a
 * stable mutable link, so we read it from the LIVE bean every time. This is THE
 * ONLY place the live bean is read on the upload path.
 */
function resolveCoffeeBagId(shot: StoredShot): string | null {
	const liveBeanId = shot.bean?.beanId ?? null;
	if (!liveBeanId) return null;
	try {
		const liveBean = getBeanStore().getBean(liveBeanId);
		return liveBean?.visualizerId ?? null;
	} catch {
		return null;
	}
}

/**
 * Resolve the shot's `tag_list` for the post-upload PATCH. Trim + dedup
 * (case-sensitive, preserve first-seen order). Returns `null` when empty so the
 * caller can skip the field entirely — sending `[]` would clobber tags the user
 * added on Visualizer's web side.
 */
function resolveTagList(shot: StoredShot): string[] | null {
	const merged: string[] = [];
	const seen = new Set<string>();
	for (const raw of shot.tags ?? []) {
		const t = raw.trim();
		if (!t || seen.has(t)) continue;
		seen.add(t);
		merged.push(t);
	}
	return merged.length > 0 ? merged : null;
}

// ── Call-error policy local to ShotSync ───────────────────────────────────
// The shared call + taxonomy + `isRecoverable` + `describeVisualizerError` live
// in `visualizer-call.ts`. These two predicates have a single caller each
// (`pullAllShotsSince` / `uploadUnsyncedShots`), so they stay local rather than
// being hoisted into a shared module they'd be the only outside user of.

/** `'auth'`-equivalent failures — re-thrown rather than swallowed on a pull. */
function isAuthError(e: VisualizerCallError): boolean {
	return (
		e._tag === 'NotAuthenticatedError' ||
		e._tag === 'TokenRefreshFailedError' ||
		(e._tag === 'HttpStatusError' && e.status === 401)
	);
}

/**
 * Auth / premium failures, a blocked account, the free-plan daily quota and the
 * rate limit abort the unsynced-shots loop: every further POST would hit the
 * same wall (and a backlog catch-up would also trip the 50 requests/min limit).
 */
function shouldAbortLoop(e: VisualizerCallError | ResponseDecodeError): boolean {
	return (
		e._tag === 'NotAuthenticatedError' ||
		e._tag === 'TokenRefreshFailedError' ||
		(e._tag === 'HttpStatusError' && e.status === 401) ||
		e._tag === 'VisualizerPremiumGatedError' ||
		isAccountBlocked(e) ||
		quotaLimit(e) != null ||
		isRateLimited(e)
	);
}

/** Why an unsynced-shots pass stopped before the end of the backlog. */
export type UnsyncedStop =
	| { reason: 'quota'; limit: number }
	| { reason: 'rate-limited' }
	| { reason: 'auth' }
	| { reason: 'premium' }
	| { reason: 'blocked'; message: string }
	| { reason: 'held' };

/** The outcome of `ShotSync.uploadUnsyncedShots`. */
export interface UnsyncedUploadResult {
	uploaded: number;
	/** Set when the pass stopped early; the rest stay unsynced for the next pass. */
	stopped: UnsyncedStop | null;
}

function stopFor(e: VisualizerCallError | ResponseDecodeError): UnsyncedStop {
	const limit = quotaLimit(e);
	if (limit != null) return { reason: 'quota', limit };
	if (isRateLimited(e)) return { reason: 'rate-limited' };
	if (e._tag === 'VisualizerPremiumGatedError') return { reason: 'premium' };
	if (isAccountBlocked(e)) return { reason: 'blocked', message: describeVisualizerError(e) };
	return { reason: 'auth' };
}

/**
 * The user notice for an unsynced-shots pass that stopped on the daily quota, the
 * rate limit or a blocked account (null otherwise — auth / premium already
 * surface elsewhere).
 */
export function describeUnsyncedStop(
	result: UnsyncedUploadResult | null | undefined
): { kind: 'info' | 'error'; message: string } | null {
	const stop = result?.stopped;
	if (!stop) return null;
	if (stop.reason === 'quota') return { kind: 'info', message: quotaNotice(stop.limit) };
	if (stop.reason === 'rate-limited') return { kind: 'info', message: RATE_LIMIT_NOTICE };
	if (stop.reason === 'blocked') return { kind: 'error', message: stop.message };
	if (stop.reason === 'held') return { kind: 'info', message: ACCOUNT_HELD_NOTICE };
	return null;
}

// ── The pull's merge ────────────────────────────────────────────────────

/** What the core's `merge_remote_shot` reads of a local row. */
function mergeInputOf(shot: StoredShot, edit: PendingEdit | undefined): ShotMergeInput {
	return {
		tags: [...(shot.tags ?? [])],
		tagsPending: hasTagMarks(edit?.tags),
		barista: shot.metadata.barista?.trim() || undefined,
		baristaPending: edit?.barista === true,
		hasSeries: shot.record.samples.length > 0,
		hasProfile: shot.profile != null,
		isBrew: isBrewLog(shot)
	};
}

/** Every bound row as a pull begins, for the check at apply time. */
function mergeInputsOf(history: HistoryStore): Map<string, ShotMergeInput> {
	const edits = pendingEdits();
	const out = new Map<string, ShotMergeInput>();
	for (const shot of history.rawAll) {
		if (shot.visualizerId && !isBrewLog(shot)) out.set(shot.id, mergeInputOf(shot, edits.get(shot.id)));
	}
	return out;
}

/**
 * Whether a row changed while its copy was fetched (the core's
 * `edited_during_fetch`): its tags, its barista, or what is pending on them.
 */
function editedDuringFetch(was: ShotMergeInput, now: ShotMergeInput): boolean {
	return (
		JSON.stringify(was.tags ?? []) !== JSON.stringify(now.tags ?? []) ||
		was.tagsPending !== now.tagsPending ||
		(was.barista ?? '') !== (now.barista ?? '') ||
		was.baristaPending !== now.baristaPending
	);
}

// ── Public surface ──────────────────────────────────────────────────────

export interface ShotPatch {
	rating?: number | null;
	notes?: string;
	coffeeBagId?: string | null;
	tagList?: string[];
	/**
	 * The frozen-at-completion bean snapshot for the inline-bean block.
	 * **Snapshot-only** — never the live bean (reading live-bean content
	 * would retroactively rewrite history). The core builder maps it onto
	 * `bean_brand` / `bean_type` / `roast_date` / `roast_level` /
	 * `bean_notes` / `grinder_setting`, skipping blanks so an empty bean
	 * doesn't blow away server-side values the user filled in via
	 * Visualizer's web UI.
	 */
	bean?: ShotBean | null;
	grinderModel?: string;
	/** Who made the shot → Visualizer `barista` (a free-account field). */
	barista?: string;
	/**
	 * The effective per-shot grind (issue #16): the shot's own recorded /
	 * edited setting, else the bean snapshot's reference — the same
	 * precedence History displays (`effectiveGrindSetting`). When set it
	 * wins over `bean.grinderSetting` for the wire's `grinder_setting`;
	 * absent, the snapshot value still rides (the post-upload PATCH path).
	 */
	grinderSetting?: string;
	/**
	 * Per-shot visibility. Sent as the same `privacy` vocabulary the upload
	 * document uses; the PATCH schema's `shot` object is
	 * `additionalProperties: true`, so the key is accepted — server-side
	 * application is best-effort (the formal schema doesn't model it).
	 */
	privacy?: 'public' | 'unlisted' | 'private';
}

/**
 * Progress callback fired between pages when `pullAllShotsSince` walks
 * pagination. UIs can wire this to a progress bar or counter.
 */
export interface PullProgress {
	/** Number of WireShots materialised so far across all pages. */
	fetched: number;
	/** 1-based page index just completed. */
	page: number;
	/** Total pages reported by the server (best-effort; may be revised). */
	totalPages: number;
	/** Whether more pages remain. */
	hasMore: boolean;
}

export interface PullOptions {
	/** Pages walked at most, a safety cap: past it the run ends `PageCap`. */
	maxPages?: number;
	onProgress?: (p: PullProgress) => void;
	/** The run is sitting out a rate limit for this long. */
	onWaiting?: (waitMs: number) => void;
	/**
	 * Run between pages, by the tab holding the run: the retry queue's drain.
	 * Answers how many remote deletes it sent, which shift the list.
	 */
	betweenPages?: () => Promise<number>;
	/** Walk the whole account, and forget the rows marked as failing. */
	fromStart?: boolean;
}

/** What a pull did. */
export interface PullApplied {
	/**
	 * How the run ended (core `RunOutcome`), or `Busy`: another tab is
	 * pulling, or a restore is running (`restore`: it started mid-walk, and
	 * the rows applied before it stay).
	 */
	outcome: RunOutcome | { kind: 'Busy'; restore?: true };
	/** Rows the run added or changed. */
	pulled: number;
	/** New rows a full history had no room for: they wait, holding the cursor. */
	refused: number;
	/** The first run after this update: it walks the whole account once. */
	repairing: boolean;
}

/** A pull that didn't run: another tab holds it, or is restoring. */
const BUSY: PullApplied = { outcome: { kind: 'Busy' }, pulled: 0, refused: 0, repairing: false };

/** A restore is applying, in this tab or another: sync waits for it. */
const restoring = (): boolean => restoreFrozen() || restoringInThisTab();

/** Rows a list page asks for: the most Visualizer gives. */
const RUN_PAGE_ITEMS = 100;
/** A run's safety cap on pages, so a server that never ends can't pin the tab. */
const MAX_RUN_PAGES = 500;

/** One run request's answer: its value, or the failure and what the core says to do. */
type RunReply<A> =
	| { readonly ok: true; readonly value: A }
	| { readonly ok: false; readonly failure: RowFailure; readonly action: RowAction };

/** A detail's own `updated_at` (unix s), in either format, or `null`. */
function detailUpdatedAt(detail: unknown): number | null {
	const d = detail as { updated_at?: unknown; meta?: { visualizer?: { updated_at?: unknown } } } | null;
	const v = d?.updated_at ?? d?.meta?.visualizer?.updated_at;
	return typeof v === 'number' && v > 0 ? v : null;
}

/** Whether the first pull after this update has yet to walk the whole account. */
export function pullRepairPending(): boolean {
	return readSyncConfig().pullCursorEpoch == null;
}

/** The run's one line in the sync log: a large run would push out every other line. */
function runLogLine(
	outcome: RunOutcome,
	pulled: number,
	refused: number,
	capacity: number
): Parameters<typeof appendSyncLog>[0] {
	const at = Date.now();
	const shots = `${pulled} shot${pulled === 1 ? '' : 's'}`;
	if (outcome.kind === 'Complete') {
		const skipped = outcome.details.skipped_permanent;
		return {
			direction: 'pull',
			entity: 'shot',
			id: '',
			name: pulled === 0 ? 'No new shots from Visualizer' : `Pulled ${shots}`,
			at,
			...(skipped > 0 ? { error: `${skipped} couldn't be read and were skipped.` } : {})
		};
	}
	const why =
		outcome.kind === 'Partial'
			? refused > 0 && outcome.details.reason === PartialReason.HistoryFull
				? historyFullNotice(refused, capacity)
				: PARTIAL_WHY[outcome.details.reason]
			: outcome.kind === 'Failed'
				? `Visualizer couldn't be reached (${outcome.details.failure.kind}).`
				: 'The pull was blocked.';
	return { direction: 'pull', entity: 'shot', id: '', name: `Pulled ${shots}, then stopped`, at, error: why };
}

const PARTIAL_WHY: Record<PartialReason, string> = {
	[PartialReason.RateLimited]: 'Visualizer is limiting requests. The rest come with the next sync.',
	[PartialReason.Offline]: 'The connection dropped. The rest come with the next sync.',
	[PartialReason.ServerTrouble]: "Visualizer isn't answering properly. The rest come with the next sync.",
	[PartialReason.PageCap]: 'The pull reached its page limit. Run Sync again to continue.',
	[PartialReason.RowsFailed]: "Some shots couldn't be read. They're tried again with the next sync.",
	[PartialReason.ListShifted]: 'Shots changed on Visualizer while pulling. The rest come with the next sync.',
	[PartialReason.HistoryFull]: 'The history is full.'
};

/** What {@link ShotSync.applyShotReconciliation} left undone. */
export interface ReconcileApplied {
	/** New rows a full history refused. */
	refused: number;
	/** Rows edited mid-pull that still need their curve. */
	held: number;
}


/**
 * What {@link ShotSync.pushPendingEdit} did: `none` (nothing owed, or the row
 * isn't on Visualizer), `waiting` (only tag marks, on a tier that can't take
 * them), `sent`, or `tier` (sent, but Visualizer didn't keep the tags).
 */
export type PendingPush = 'none' | 'waiting' | 'sent' | 'tier';

/** Internal iteration state for the `pullAllShotsSince` `iterate` walk. */
interface PullState {
	page: number;
	totalPages: number;
	reachedOlder: boolean;
	keepGoing: boolean;
}

/**
 * Effect 3's `Effect.iterate` (gone in Effect 4) on `Effect.whileLoop`: from
 * `initial`, run `body` while `while` holds, feeding each result into the next
 * step; succeeds with the final state.
 */
function iterate<Z, E, R>(
	initial: Z,
	options: { readonly while: (z: Z) => boolean; readonly body: (z: Z) => Effect.Effect<Z, E, R> }
): Effect.Effect<Z, E, R> {
	return Effect.suspend(() => {
		let state = initial;
		return Effect.whileLoop({
			while: () => options.while(state),
			body: () => options.body(state),
			step: (z) => {
				state = z;
			}
		}).pipe(Effect.map(() => state));
	});
}

export class ShotSync extends Context.Service<
	ShotSync,
	{
		readonly uploadShot: (
			shot: StoredShot
		) => Effect.Effect<{ visualizerId: string }, VisualizerCallError | ResponseDecodeError>;
		readonly deleteShot: (
			visualizerId: string
		) => Effect.Effect<void, Exclude<VisualizerCallError, VisualizerNotFoundError>>;
		/** PATCH a remote shot; the reply (the updated shot), or `undefined` when nothing was sent. */
		readonly patchShot: (visualizerId: string, patch: ShotPatch) => Effect.Effect<unknown, VisualizerCallError>;
		/**
		 * Send a History edit to the shot's Visualizer copy: whatever is
		 * pending for it (see `#lib/visualizer/pending-edits.js`, which the
		 * edit marks first). No-op when the shot isn't uploaded.
		 */
		readonly patchEditedShot: (history: HistoryStore, id: string) => Effect.Effect<void, VisualizerCallError>;
		/** {@link patchEditedShot}, saying what happened: the drain's retry. */
		readonly pushPendingEdit: (
			history: HistoryStore,
			id: string
		) => Effect.Effect<PendingPush, VisualizerCallError>;
		readonly pullShots: (
			page?: number,
			itemsPerPage?: number
		) => Effect.Effect<{ summaries: ShotSummary[]; paging: Paging }, VisualizerCallError>;
		readonly fetchShotDetail: (id: string, patient?: boolean) => Effect.Effect<ShotDetail, VisualizerCallError>;
		readonly applyShotReconciliation: (
			history: HistoryStore,
			remote: WireShot[],
			samplesById?: ReadonlyMap<string, TimedSample[]>,
			profilesById?: ReadonlyMap<string, unknown>,
			before?: ReadonlyMap<string, ShotMergeInput>,
			seriesLoaded?: boolean
		) => Effect.Effect<ReconcileApplied>;
		readonly pullAndReconcileShots: (
			history: HistoryStore,
			opts?: PullOptions
		) => Effect.Effect<PullApplied, NotAuthenticatedError>;
		readonly uploadUnsyncedShots: (history: HistoryStore) => Effect.Effect<UnsyncedUploadResult>;
	}
>()('crema/ShotSync') {}

export const ShotSyncLive = Layer.effect(
	ShotSync,
	Effect.gen(function* () {
		const http = yield* HttpClient;
		const vault = yield* TokenVault;

		/** Early bail mirroring the old `if (!isConnected())` guard. */
		const requireConnected: Effect.Effect<void, NotAuthenticatedError> = vault.getTokens.pipe(
			Effect.flatMap((t) => (t === null ? Effect.fail(new NotAuthenticatedError()) : Effect.void))
		);

		/**
		 * `ShotSync`'s authenticated entry point: the shared {@link visualizerCall}
		 * with this layer's captured `http` / `vault` provided, so every method
		 * built on it stays `R = never` (which is what lets `UploadQueue` call
		 * `ShotSync` without itself holding `HttpClient` / `TokenVault`).
		 */
		const call = (
			path: string,
			opts: VisualizerCallOptions = {}
		): Effect.Effect<unknown, VisualizerCallError> =>
			visualizerCall(path, opts).pipe(
				Effect.provideService(HttpClient, http),
				Effect.provideService(TokenVault, vault)
			);

		/**
		 * Upload a single shot, then fire a soft follow-up PATCH (bag link +
		 * auto-tags + inline-bean snapshot + grinder model). PATCH failure is
		 * soft — logged, not rethrown — so a flaky link wire doesn't lose the
		 * uploaded shot.
		 */
		const uploadShot = Effect.fn('ShotSync.uploadShot')(function* (shot: StoredShot) {
			yield* requireConnected;
			if (refusedNoSamples(shot)) return yield* new ShotWithoutSeriesError({ shotId: shot.id });
			const body = buildShotPayload(shot);
			// Stored first: a kill mid-POST must leave a delete able to see it.
			markUploadAttempted(shot.id);
			const raw = yield* call('/shots/upload', { method: 'POST', body }).pipe(
				Effect.tapError((e) => Effect.sync(() => void (refusedUpload(e) && clearUploadAttempted(shot.id)))),
				Effect.ensuring(Effect.sync(() => uploadReturned(shot.id)))
			);
			const result = decodeResponse(ShotUploadResultSchema, raw, 'POST /shots/upload');
			if (!result || !result.id) {
				return yield* new ResponseDecodeError({
					url: `${API_BASE}/shots/upload`,
					cause: 'Visualizer accepted the shot but returned no id.'
				});
			}

			// Post-upload PATCH: build each block independently so empty sources
			// skip cleanly (no risk of overwriting a Visualizer-side edit —
			// the core builder additionally drops blank inline-bean fields).
			const coffeeBagId = resolveCoffeeBagId(shot);
			// Tags go absolute only onto a new row. A bound shot's tags already
			// went up, or wait in marks: a re-upload sends none and marks none,
			// or it would bring back tags removed on the website.
			const tagList = shot.visualizerId != null ? null : resolveTagList(shot);
			const grinderModel = resolveGrinderModel(shot, getSettingsStore().current.grinderModel);
			if (
				coffeeBagId != null ||
				(tagList != null && tagList.length > 0) ||
				shot.bean != null ||
				grinderModel != null
			) {
				yield* patchShot(result.id, {
					...(coffeeBagId != null ? { coffeeBagId } : {}),
					...(tagList != null && tagList.length > 0 ? { tagList } : {}),
					...(shot.bean != null ? { bean: shot.bean } : {}),
					...(grinderModel != null ? { grinderModel } : {})
				}).pipe(
					// The drain sends them again once the row is bound: the
					// journal fields as they stand then, and the tags as an add
					// onto whatever the server holds.
					Effect.catch((e) =>
						Effect.sync(() => {
							console.warn(
								'[Crema] post-upload PATCH (coffee_bag_id / tag_list / inline bean / grinder_model) failed:',
								describeVisualizerError(e)
							);
							markShotEdit(shot.id, 'annotations');
							if (tagList != null && tagList.length > 0) {
								markShotEdit(shot.id, { tags: { before: [], after: tagList } });
							}
						})
					)
				);
			}

			return { visualizerId: result.id };
		});

		/**
		 * Delete a remote shot by id. `DELETE /shots/{id}` returns 200 +
		 * `{ success: true }` per the spec. A 404 (→ `VisualizerNotFoundError`)
		 * is treated as success.
		 */
		const deleteShot = Effect.fn('ShotSync.deleteShot')(function* (visualizerId: string) {
			yield* requireConnected;
			yield* call(`/shots/${visualizerId}`, { method: 'DELETE' }).pipe(
				Effect.catchTag('VisualizerNotFoundError', () => Effect.void)
			);
		});

		/**
		 * Patch a remote shot's editable annotations. The spec requires a
		 * `{ shot: {...} }` envelope; only keys explicitly present on `patch` ride
		 * out. `rating` (0..5) → `flavor` (0..15) via `flavor = rating * 3`; a
		 * `null` rating omits `flavor`. Inline-bean fields ride via the spec's
		 * `additionalProperties: true` index signature.
		 */
		const patchShot = Effect.fn('ShotSync.patchShot')(function* (
			visualizerId: string,
			patch: ShotPatch
		) {
			yield* requireConnected;
			// The body assembly lives in the core (one builder for both
			// shells — review #42): rating→flavor with unrated/cleared
			// OMITTED (never clobber a server-side cupping score), the
			// inline-bean block with blank-skipping and the lossy
			// roast-level banding, and the wire key names.
			const inputs: ShotPatchInputs = {
				rating: patch.rating ?? undefined,
				notes: patch.notes,
				privacy: patch.privacy,
				grinderModel: patch.grinderModel,
				barista: patch.barista,
				coffeeBagId: patch.coffeeBagId ?? undefined,
				// An absolute list, and `[]` clears the remote tags: only a tag
				// push, built from the server's own list, sends one that way.
				tagList: patch.tagList,
				beanBrand: patch.bean?.roasterName ?? undefined,
				beanType: patch.bean?.name,
				roastDate: patch.bean?.roastedOn ?? undefined,
				roastLevel: patch.bean?.roastLevel ?? undefined,
				beanNotes: patch.bean?.notes,
				// The shot's effective grind wins over the snapshot reference
				// (issue #16: a grind edit must reach the uploaded copy) —
				// Android's patchEditedShot sends the same precedence.
				grinderSetting: patch.grinderSetting ?? patch.bean?.grinderSetting ?? undefined
			};
			// Fitted to the account tier in core, off the bean-sync premium flag
			// (refreshed on sign-in, daily at start, on Test, and by every
			// bag/roaster write): a free account — or one whose tier isn't
			// known yet (`premium: null`) — silently loses the
			// premium-only fields (private_notes, the tasting scores incl.
			// `flavor`, tag_list, coffee_bag_id …), and a body left with nothing
			// the server applies comes back `undefined`: no request, no error.
			const patchJson = wasmVisualizerShotPatchJson(
				JSON.stringify(inputs),
				readSyncSettings().premium ?? undefined
			);
			if (patchJson === undefined) return undefined;
			const shotBody = JSON.parse(patchJson) as ShotUpdateRequest['shot'];
			const envelope: ShotUpdateRequest = { shot: shotBody };
			// The reply is the updated shot, `tags` included.
			return yield* call(`/shots/${visualizerId}`, {
				method: 'PATCH',
				body: envelope
			});
		});

		/**
		 * Fetch one page of remote shot SUMMARIES (`{ id, clock, updated_at }`).
		 * The default `sort=updated_at` lets `pullAllShotsSince` stop walking as
		 * soon as it crosses the local cursor.
		 */
		const pullShots = Effect.fn('ShotSync.pullShots')(function* (page = 1, itemsPerPage = 50) {
			yield* requireConnected;
			const items = Math.min(100, Math.max(1, Math.floor(itemsPerPage)));
			const raw = yield* call(`/shots?page=${page}&items=${items}&sort=updated_at`);
			const body = decodeResponse(ShotListResponseSchema, raw, 'GET /shots');
			return {
				// Spread to a mutable array — `Schema.Array` decodes to `readonly`.
				summaries: body ? [...body.data] : [],
				paging: body?.paging ?? { count: 0, page, limit: items, pages: 1 }
			};
		});

		/**
		 * Fetch the full record for one shot (`GET /shots/{id}`). The body is fed
		 * verbatim to the wasm reconcile path, so it is passed through as-is rather
		 * than validated — `ShotDetail` is a two-variant (Default/Beanconqueror)
		 * shape and a stripping schema would drop fields wasm needs (handoff §T-12).
		 */
		const fetchShotDetail = Effect.fn('ShotSync.fetchShotDetail')(function* (id: string, patient = false) {
			yield* requireConnected;
			const raw = yield* call(`/shots/${id}`, { patient });
			return raw as ShotDetail;
		});

		/**
		 * Fetch a shot's recipe from `/shots/{id}/profile` (the shot detail has no
		 * step list) and convert it to the core `Profile` JSON. Used at pull time so
		 * a pulled shot is self-contained (#12). Throws on a profile-less stub (the
		 * v2 parser rejects an empty step list) — the caller skips it.
		 */
		const fetchShotProfile = Effect.fn('ShotSync.fetchShotProfile')(function* (id: string, patient = false) {
			yield* requireConnected;
			const v2 = yield* call(`/shots/${id}/profile?format=json`, { patient });
			// `parseV2Profile` throws on a profile-less stub / invalid v2; swallow it
			// (the shot stays name-only) so a sync defect can't escape the pull walk.
			try {
				return JSON.parse(wasmParseV2Profile(JSON.stringify(v2))) as unknown;
			} catch {
				return null;
			}
		});

		/**
		 * Apply a {@link reconcileShots} plan against `history`. Logs each action
		 * via {@link appendSyncLog}; does not throw. Pure planner output translated
		 * to store mutations — wrapped in `Effect.sync` (calls wasm + the rune
		 * store synchronously, same as the old module). Answers how many new rows
		 * a full history refused (the rest of the plan still applies), and how
		 * many rows still need their curve: edited mid-pull, or not loaded yet
		 * (`seriesLoaded` false: the load timed out). `before` is each row as
		 * the pull began, for the edit check.
		 */
		const applyShotReconciliation = (
			history: HistoryStore,
			remote: WireShot[],
			samplesById?: ReadonlyMap<string, TimedSample[]>,
			profilesById?: ReadonlyMap<string, unknown>,
			before?: ReadonlyMap<string, ShotMergeInput>,
			seriesLoaded = true
		): Effect.Effect<ReconcileApplied> =>
			Effect.sync(() => {
				let refused = 0;
				// Tombstones included: a shot deleted here whose remote DELETE
				// hasn't landed yet is still on Visualizer, and without its
				// tombstone the planner saw it as new and pulled it back. A bound
				// one's `update` is skipped below; an unbound one binds by upload key.
				const parked = parkedTombstones();
				const local = history.rawAll.filter((s) => !isBrewLog(s) && !parked.has(s.id));
				const keptOut = remoteKeptOut();
				const actions = reconcileShots(
					local,
					remote.filter((r) => !keptOut.has(r.id))
				);
				const tombstoned = (localId: string): boolean =>
					history.get(localId)?.deletedAt != null;
				// Read once for the whole pull, not per shot (C3 #10): nothing
				// edits a row inside this synchronous pass.
				const edits = pendingEdits();
				let held = 0;
				/**
				 * Merge the pulled copy onto a bound row, through the core: tags
				 * only add, a pending edit wins, a curve or recipe fills only a
				 * row without one. A row edited since the pull began is newer
				 * here: it is left alone, and holds the cursor only if it still
				 * needs its curve.
				 */
				const merge = (localId: string, wire: WireShot): void => {
					const row = history.get(localId);
					if (!row) return;
					const input = mergeInputOf(row, edits.get(localId));
					// Its curve may still be loading: a backfill now would take
					// Visualizer's copy over the row's own. It waits, and holds.
					if (!seriesLoaded && !input.hasSeries && !input.isBrew) {
						held += 1;
						return;
					}
					const was = before?.get(localId);
					if (was && editedDuringFetch(was, input)) {
						if (!input.hasSeries && !input.isBrew) held += 1;
						return;
					}
					const profile = profilesById?.get(wire.id);
					const patch = JSON.parse(
						wasmMergeRemoteShot(
							JSON.stringify({ local: input, remote: wire, profileOffered: profile != null })
						)
					) as RemoteShotPatch;
					history.applyRemote(localId, patch, {
						samples: samplesById?.get(wire.id),
						durationMs: wire.duration_ms,
						profile
					});
				};
				for (const action of actions) {
					if (action.kind === 'add') {
						const stored = storedShotFromWire(action.remote, samplesById?.get(action.remote.id));
						if (stored) {
							// Attach the recipe fetched at pull time so the shot is
							// self-contained (#12); absent for profile-less remotes.
							const profile = profilesById?.get(action.remote.id);
							// Its version too, or every run would fetch it again.
							const version =
								action.remote.updated_at_ms != null ? Math.floor(action.remote.updated_at_ms / 1000) : null;
							const row = { ...stored, visualizerUpdatedAt: version, ...(profile != null ? { profile } : {}) };
							if (!history.insertPulled(row)) {
								refused += 1;
								continue;
							}
						}
						appendSyncLog({
							direction: 'pull',
							entity: 'shot',
							id: stored?.id ?? action.remote.id ?? '',
							name: stored?.profileName ?? 'Shot',
							at: Date.now()
						});
					} else if (action.kind === 'bind') {
						// A row deleted during its upload keeps the id as a tombstone,
						// so the remote copy stays out of the history.
						history.bindVisualizerId(action.localId, action.visualizerId);
						if (tombstoned(action.localId)) continue;
						merge(action.localId, action.remote);
						appendSyncLog({
							direction: 'pull',
							entity: 'shot',
							id: action.localId,
							name: 'Shot (bound)',
							at: Date.now()
						});
					} else if (action.kind === 'update') {
						// Deleted here; the pending DELETE removes the remote too.
						if (tombstoned(action.localId)) continue;
						merge(action.localId, action.remote);
					}
				}
				return { refused, held };
			});

		/**
		 * One request of a pull run, paced (patient), with the core's rule for
		 * a failure: a 5xx retries once in place, a 429 sits its wait out and
		 * tries the same request again (three times), and anything else comes
		 * back with what to do about it.
		 */
		const runRequest = <A>(
			request: Effect.Effect<A, VisualizerCallError | ResponseDecodeError>,
			onWaiting?: (waitMs: number) => void
		): Effect.Effect<RunReply<A>> =>
			Effect.gen(function* () {
				for (let attempt = 0; ; attempt += 1) {
					const result = yield* Effect.result(request);
					if (result._tag === 'Success') return { ok: true as const, value: result.success };
					const failure = rowFailureOf(result.failure);
					const action = pullRules.rowFailureAction(failure, attempt);
					if (action.kind === 'RetryNow') continue;
					if (action.kind === 'Wait') {
						noteRateLimited(action.details.wait_ms);
						onWaiting?.(action.details.wait_ms);
						yield* waitFor(action.details.wait_ms);
						continue;
					}
					return { ok: false as const, failure, action };
				}
			});

		/** A list page, for the run: a body that doesn't decode is a portal's page. */
		const listPage = (page: number, sinceMs: number) =>
			Effect.gen(function* () {
				const after = sinceMs > 0 ? `&updated_after=${Math.floor(sinceMs / 1000) - 1}` : '';
				const raw = yield* call(`/shots?page=${page}&items=${RUN_PAGE_ITEMS}&sort=updated_at${after}`, {
					patient: true
				});
				const body = decodeResponse(ShotListResponseSchema, raw, 'GET /shots');
				if (!body) {
					return yield* new NetworkError({ cause: new SyntaxError('not a list'), url: `${API_BASE}/shots` });
				}
				return body;
			});

		/** What the plan knows of the rows this device holds, by remote id. */
		const knownRowsOf = (history: HistoryStore, seriesLoaded: boolean): Map<string, KnownRow> => {
			const keptOut = remoteKeptOut();
			const out = new Map<string, KnownRow>();
			for (const shot of history.rawAll) {
				if (!shot.visualizerId) continue;
				out.set(shot.visualizerId, {
					remoteId: shot.visualizerId,
					remoteVersion: shot.visualizerUpdatedAt ?? undefined,
					// Not loaded yet: fetched, then held until the curve is there.
					hasSeries: seriesLoaded && shot.record.samples.length > 0,
					isBrew: isBrewLog(shot),
					tombstoned: shot.deletedAt != null
				});
			}
			for (const id of keptOut) out.set(id, { remoteId: id, tombstoned: true });
			return out;
		};

		/** Unbound rows and unbound tombstones a remote row may be the upload of. */
		const keyCandidatesOf = (history: HistoryStore): KeyCandidate[] =>
			history.rawAll
				.filter((s) => !s.visualizerId && !isBrewLog(s))
				.map((s) => ({
					localId: s.id,
					completedAtMs: s.completedAt,
					durationMs: s.record.duration,
					tombstone: s.deletedAt != null
				}));

		/**
		 * Fetch one row's detail and apply it, as the run does for each row: the
		 * recipe only when offered and the row has none, the row re-read just
		 * before the request so an edit made meanwhile wins.
		 */
		const pullRow = (
			history: HistoryStore,
			row: ListRow,
			seriesLoaded: boolean,
			onWaiting?: (waitMs: number) => void
		): Effect.Effect<RunReply<RowOutcome>> =>
			Effect.gen(function* () {
				const bound = history.rawAll.find((s) => s.visualizerId === row.id);
				const before = bound
					? new Map([[bound.id, mergeInputOf(bound, pendingEdit(bound.id))]])
					: undefined;
				const detail = yield* runRequest(fetchShotDetail(row.id, true), onWaiting);
				if (!detail.ok) return detail;
				let wire: WireShot;
				let samples: TimedSample[];
				// A poison retry has no list row: its version comes from the detail.
				const updatedAt = row.updated_at > 0 ? row.updated_at : detailUpdatedAt(detail.value);
				try {
					wire = wireShotFromDetail({ id: row.id, clock: row.clock, updated_at: updatedAt ?? 0 }, detail.value);
					if (updatedAt === null) wire = { ...wire, updated_at_ms: null };
					samples = samplesFromVisualizerDetail(detail.value);
				} catch {
					const failure: RowFailure = { kind: 'Unmappable' };
					return { ok: false as const, failure, action: pullRules.rowFailureAction(failure, 0) };
				}
				const offered = typeof (detail.value as { profile_url?: unknown }).profile_url === 'string';
				let profile: unknown = null;
				if (offered && (bound?.profile ?? null) == null) {
					// A recipe that won't come leaves the row name-only.
					const got = yield* runRequest(fetchShotProfile(row.id, true), onWaiting);
					profile = got.ok ? got.value : null;
				}
				const { refused, held } = yield* applyShotReconciliation(
					history,
					[wire],
					new Map([[row.id, samples]]),
					profile != null ? new Map([[row.id, profile]]) : undefined,
					before,
					seriesLoaded
				);
				const outcome: RowOutcome =
					refused > 0
						? { kind: 'HistoryFull' }
						: held > 0
							? { kind: 'EditedDuringFetch', details: { has_series: false } }
							: { kind: 'Applied' };
				return { ok: true as const, value: outcome };
			});

		/**
		 * Pull from Visualizer as the core's run rules say (`#lib/visualizer/
		 * pull-run-core.js`): one run at a time across tabs, paced, walking
		 * `GET /shots` newest first from the cursor (`updated_after`). Each row
		 * applies as it goes, its version written last, so a run that stops keeps
		 * what it did and the next one skips it. The cursor moves only on a
		 * complete run, on the server's clock. A row that keeps failing becomes
		 * poison after three counted days and is retried alone once a day.
		 *
		 * The first run after this changed (`pullCursorEpoch` unset) walks the
		 * whole account once: an old cursor passed rows no pull applied (#328).
		 */
		const pullAndReconcileShots = Effect.fn('ShotSync.pullAndReconcileShots')(function* (
			history: HistoryStore,
			opts: PullOptions = {}
		) {
			// Not from an account this device can't name.
			if (readSyncConfig().accountUnconfirmed) return yield* new NotAuthenticatedError();
			yield* requireConnected;
			// A restore, here or in another tab: its rows aren't settled yet.
			if (restoring()) return { ...BUSY, outcome: { kind: 'Busy', restore: true } };
			const release = yield* Effect.promise(acquireRunLock);
			if (!release) return BUSY;
			return yield* walk(history, opts).pipe(
				Effect.ensuring(
					Effect.sync(() => {
						storePace();
						release();
					})
				)
			);
		});

		const walk = (history: HistoryStore, opts: PullOptions) =>
			Effect.gen(function* () {
				const config = readSyncConfig();
				const repairing = config.pullCursorEpoch == null;
				const legacyCursor =
					repairing && config.shotPullCursor ? Math.floor(config.shotPullCursor / 1000) : undefined;
				const sinceMs = opts.fromStart || repairing ? 0 : (config.shotPullCursor ?? 0);
				// A row read before its curve loaded would look like it has none:
				// if the load timed out, a row with no curve may yet have one.
				const seriesLoaded = yield* Effect.promise(() => history.seriesReady());
				const pullRun = beginPullRun();
				let poison = opts.fromStart ? [] : readPoison();
				const resolved: string[] = [];
				let tally: PullTally = { sinceMs };
				let pulled = 0;
				let refused = 0;
				const waiting = opts.onWaiting;

				// Poison rows due their daily try, alone, before the walk.
				for (const id of pullRules.poisonDue(poison, Date.now())) {
					const entry = poison.find((e) => e.id === id);
					const row: ListRow = { id, clock: entry?.clock ?? 0, updated_at: 0 };
					const got = yield* pullRow(history, row, seriesLoaded, waiting);
					const gone = !got.ok && got.failure.kind === 'Call' && got.failure.details.failure.kind === 'NotFound';
					if (got.ok || gone) {
						resolved.push(id);
						if (got.ok && got.value.kind === 'Applied') pulled += 1;
					} else {
						poison = pullRules.notePoisonRetry(poison, id, Date.now());
					}
				}

				let page = 1;
				let ownDeletes = 0;
				walk: for (;;) {
					if (restoring()) {
						// What it applied stays; the cursor, the poison list and the
						// rest wait for the next run, after the restore.
						appendSyncLog({
							direction: 'pull',
							entity: 'shot',
							id: '',
							name: `Pulled ${pulled} shot${pulled === 1 ? '' : 's'}, then paused`,
							at: Date.now(),
							error: 'A restore started, so the pull stopped. It goes on with the next sync.'
						});
						return { outcome: { kind: 'Busy', restore: true }, pulled, refused, repairing } satisfies PullApplied;
					}
					if (page > (opts.maxPages ?? MAX_RUN_PAGES)) {
						tally = { ...tally, ended: { kind: 'Partial', details: { reason: PartialReason.PageCap } } };
						break;
					}
					const list = yield* runRequest(listPage(page, sinceMs), waiting);
					if (!list.ok) {
						const f = list.failure;
						tally = pullRules.endRun(
							tally,
							f.kind === 'Call' ? f.details.failure : { kind: 'Unavailable', details: { status: 200 } }
						);
						break;
					}
					const { data, paging } = list.value;
					const shift = pullRules.afterListPage(tally, paging.count, ownDeletes);
					tally = shift.tally;
					ownDeletes = 0;
					if (shift.step === ListStep.Restart) {
						page = 1;
						continue;
					}
					if (shift.step === ListStep.Stop) break;
					const rows: ListRow[] = data.map((r) => ({ id: r.id, clock: r.clock, updated_at: r.updated_at }));
					const known = knownRowsOf(history, seriesLoaded);
					const plan = pullRules.planPage({
						rows,
						known: rows.map((r) => known.get(r.id)).filter((k): k is KnownRow => k !== undefined),
						candidates: keyCandidatesOf(history),
						freeSlots: history.freeSlots,
						legacyCursor
					});
					for (const [i, planned] of plan.rows.entries()) {
						const row = rows[i];
						if (planned.kind === 'Skip') {
							const full = planned.details.why === SkipReason.HistoryFull;
							if (full) refused += 1;
							tally = pullRules.recordRow(tally, row, full ? { kind: 'HistoryFull' } : { kind: 'Unchanged' });
							continue;
						}
						const got = yield* pullRow(history, row, seriesLoaded, waiting);
						if (got.ok) {
							if (got.value.kind === 'Applied') {
								pulled += 1;
								resolved.push(row.id);
							}
							if (got.value.kind === 'HistoryFull') refused += 1;
							tally = pullRules.recordRow(tally, row, got.value);
						} else if (got.action.kind === 'Skip') {
							tally = pullRules.recordRow(tally, row, { kind: 'Failed', details: got.action.details });
						} else {
							const f = got.failure;
							tally = pullRules.endRun(
								tally,
								f.kind === 'Call' ? f.details.failure : { kind: 'Unavailable', details: { status: 200 } }
							);
						}
						if (tally.ended) break walk;
					}
					opts.onProgress?.({ fetched: pulled, page, totalPages: paging.pages, hasMore: page < paging.pages });
					// The drain runs here, between pages, from the tab holding the run.
					if (opts.betweenPages) ownDeletes += yield* Effect.promise(opts.betweenPages);
					if (page >= paging.pages || data.length === 0) break;
					page += 1;
				}

				// The only failing row may be the only one changed: one known-good
				// detail says whether the server works, for the poison count.
				if (pullRules.healthProbeDue(tally)) {
					const good = history.rawAll.find(
						(s) => s.visualizerId && s.deletedAt == null && s.visualizerUpdatedAt != null
					);
					if (good?.visualizerId) {
						const probe = yield* Effect.result(fetchShotDetail(good.visualizerId, true));
						tally = pullRules.recordHealthProbe(tally, probe._tag === 'Success');
					}
				}

				poison = pullRules.settlePoison(poison, tally, resolved, Date.now());
				writePoison(poison);
				const outcome = pullRules.finishRun(tally, poison);
				if (outcome.kind === 'Complete') {
					updateSyncConfig({ shotPullCursor: outcome.details.next_cursor_ms, pullCursorEpoch: 1 });
					for (const id of expiredTombstones(pullRun, sinceMs)) history.purgeTombstone(id);
				}
				appendSyncLog(runLogLine(outcome, pulled, refused, history.capacity));
				return { outcome, pulled, refused, repairing } satisfies PullApplied;
			});

		/**
		 * Upload every local shot that lacks a `visualizerId`. Recoverable
		 * failures route to the persistent retry queue; auth / premium failures,
		 * the free-plan daily quota (422) and the rate limit (429) abort the loop
		 * (continuing would just keep hammering the same wall). A quota stop
		 * queues nothing: the rest stay unsynced, so the next pass picks them up.
		 */
		const uploadUnsyncedShots = Effect.fn('ShotSync.uploadUnsyncedShots')(function* (
			history: HistoryStore
		) {
			const config = readSyncConfig();
			if (uploadsHeld(config)) return { uploaded: 0, stopped: { reason: 'held' as const } };
			yield* Effect.promise(() => history.seriesReady());
			// Brew Log rows (`isBrewLog`) never sync: Visualizer is a
			// DE1-telemetry service and brews carry none (issue #10 — the same
			// local-only posture as `nextPlan`).
			const backlog = history.all.filter(
				(s) => !s.visualizerId && !isBrewLog(s) && !excludedFromUpload(s, config)
			);
			const list = backlog.filter((s) => !refusedNoSamples(s));
			if (list.length < backlog.length) {
				// One line for the pass: these would otherwise fill the log on every sync.
				const n = backlog.length - list.length;
				appendSyncLog({
					direction: 'skip',
					entity: 'shot',
					id: '',
					name: 'upload',
					at: Date.now(),
					error: `${n} shot${n === 1 ? ' has' : 's have'} no telemetry to upload.`
				});
			}

			/**
			 * Record a non-success outcome: enqueue a recoverable error for a timed
			 * retry through `UploadQueue`, and append a `skip` line to the sync log.
			 */
			const skipOrQueue = (shot: StoredShot, e: VisualizerCallError | ResponseDecodeError): void => {
				if (isRecoverable(e)) {
					enqueueSyncOp({
						entity: 'shot',
						id: shot.id,
						op: 'create',
						error: describeVisualizerError(e),
						// A rate-limited (429) shot waits out the limit window
						// rather than being retried by the drain right after.
						...(isRateLimited(e) ? { delayMs: retryDelayMs(e, 0) } : {})
					});
				}
				appendSyncLog({
					direction: 'skip',
					entity: 'shot',
					id: shot.id,
					name: shot.profileName ?? 'Shot',
					at: Date.now(),
					error: describeVisualizerError(e)
				});
			};

			let uploaded = 0;
			let stopped: UnsyncedStop | null = null;

			/** Upload one shot; returns `true` when the loop should abort. */
			const uploadOne = (shot: StoredShot): Effect.Effect<boolean> =>
				uploadShot(shot).pipe(
					Effect.flatMap(({ visualizerId }) =>
						Effect.sync(() => {
							uploaded += 1;
							history.bindVisualizerId(shot.id, visualizerId);
							appendSyncLog({
								direction: 'push',
								entity: 'shot',
								id: shot.id,
								name: shot.profileName ?? 'Shot',
								at: Date.now()
							});
							return false;
						})
					),
					// A failure that may have landed anyway goes back on the queue and
					// re-POSTs: Visualizer's `sha` hands the same row back.
					Effect.catch((e) =>
						Effect.sync(() => {
							skipOrQueue(shot, e);
							const abort = shouldAbortLoop(e);
							if (abort) stopped = stopFor(e);
							return abort;
						})
					),
					// A throw while exporting one shot is a defect, which `Effect.catch`
					// lets through. Skip that shot, or every pass ends at it and the
					// shots after it never upload. Not queued: it would only throw again.
					Effect.catchDefect((cause) =>
						Effect.sync(() => {
							console.warn('[shot sync] skipping a shot that could not be exported', shot.id, cause);
							appendSyncLog({
								direction: 'skip',
								entity: 'shot',
								id: shot.id,
								name: shot.profileName ?? 'Shot',
								at: Date.now(),
								error: 'This shot could not be read for upload.'
							});
							return false;
						})
					)
				);

			// `iterate` over the index; on abort, jump the index past the end
			// to terminate (mirrors the old loop's early `return`).
			yield* iterate(0, {
				while: (i) => i < list.length,
				body: (i) => uploadOne(list[i]).pipe(Effect.map((aborted) => (aborted ? list.length : i + 1)))
			});
			return { uploaded, stopped } satisfies UnsyncedUploadResult;
		});

		/**
		 * The journal fields of a local shot as a [ShotPatch]: what an
		 * `annotations` mark sends. The same resolvers the upload follow-up
		 * uses, plus the fields the History panel edits. Notes respect
		 * `visualizerIncludeNotes` (a user who opted out of uploading notes
		 * must not have an edit leak them). Privacy sends the EFFECTIVE value
		 * (per-shot override, else the settings default) so the server always
		 * matches what the user sees. No tags and no barista: those go only
		 * with their own marks.
		 */
		const annotationsPatch = (shot: StoredShot): ShotPatch => {
			const settings = getSettingsStore().current;
			const cfg = readSyncConfig();
			const coffeeBagId = resolveCoffeeBagId(shot);
			const grinderModel = resolveGrinderModel(shot, settings.grinderModel);
			const grinderSetting = effectiveGrindSetting(shot);
			return {
				rating: (shot.metadata.rating ?? 0) > 0 ? (shot.metadata.rating ?? null) : null,
				...(cfg.includeNotes ? { notes: shot.metadata.notes ?? '' } : {}),
				privacy: shot.privacy ?? cfg.privacy,
				...(coffeeBagId != null ? { coffeeBagId } : {}),
				...(shot.bean != null ? { bean: shot.bean } : {}),
				...(grinderModel != null ? { grinderModel } : {}),
				...(grinderSetting != null ? { grinderSetting } : {})
			};
		};

		/** The tags of a PATCH reply or a detail: `[]` when it carries none. */
		const tagsOf = (body: unknown): string[] => {
			const tags = (body as { tags?: unknown } | null | undefined)?.tags;
			return Array.isArray(tags) ? tags.filter((t): t is string => typeof t === 'string') : [];
		};

		/**
		 * Send what is pending for `id`, built from the row as it stands. Each
		 * mark rides only its own field: the journal body, the barista, and the
		 * tags, which on Premium start from the server's list (`GET` first) so a
		 * change made on the website isn't undone. On a Free or unknown tier the
		 * tag marks wait. A mark clears once what was sent is still the row's;
		 * one made since stays. A failure keeps them for the drain, unless it is
		 * for good (the row is gone or refused), which clears and reports.
		 */
		const pushPendingEdit = Effect.fn('ShotSync.pushPendingEdit')(function* (
			history: HistoryStore,
			id: string
		) {
			const edit = pendingEdit(id);
			if (!edit) return 'none' as const;
			const shot = history.all.find((s) => s.id === id);
			// Gone here: nothing is owed.
			if (!shot) {
				settlePendingEdit(id, undefined);
				return 'none' as const;
			}
			const vid = shot.visualizerId;
			if (!vid || readSyncConfig().accountUnconfirmed) return 'none' as const;
			const sendTags =
				hasTagMarks(edit.tags) && readSyncSettings().premium === true && edit.tierSignal !== true;
			const journal = edit.annotations ? annotationsPatch(shot) : undefined;
			const barista = edit.barista ? baristaOf(shot) : undefined;
			if (!journal && barista === undefined && !sendTags) return 'waiting' as const;
			const sentTags: TagMarks | undefined = sendTags ? edit.tags : undefined;
			let pushed: string[] | undefined;
			const result = yield* Effect.result(
				Effect.gen(function* () {
					if (sentTags) {
						const remote = yield* fetchShotDetail(vid);
						pushed = JSON.parse(
							wasmTagPushList(JSON.stringify(tagsOf(remote)), JSON.stringify(sentTags))
						) as string[];
					}
					return yield* patchShot(vid, {
						...journal,
						...(barista !== undefined ? { barista } : {}),
						...(pushed !== undefined ? { tagList: pushed } : {})
					});
				})
			);
			const current = pendingEdit(id) ?? {};
			if (result._tag === 'Failure') {
				const e = result.failure;
				const kind = pendingEditFailure(e, sentTags !== undefined && !journal && barista === undefined);
				if (kind === 'terminal') {
					settlePendingEdit(id, undefined);
					appendSyncLog({
						direction: 'skip',
						entity: 'shot',
						id,
						name: shot.profileName ?? 'Shot',
						at: Date.now(),
						error: `This edit can't reach Visualizer: ${describeVisualizerError(e)}`
					});
				} else if (kind === 'tier') {
					// Backed off too: the journal fields would only meet the same refusal.
					const attempts = (current.attempts ?? 0) + 1;
					settlePendingEdit(id, {
						...current,
						tierSignal: true,
						...(e._tag === 'VisualizerPremiumGatedError' ? { premiumRefused: true as const } : {}),
						attempts,
						nextAttemptAt: Date.now() + retryDelayMs(e, attempts - 1)
					});
				} else {
					const attempts = (current.attempts ?? 0) + 1;
					settlePendingEdit(id, {
						...current,
						attempts,
						nextAttemptAt: Date.now() + retryDelayMs(e, attempts - 1)
					});
				}
				return yield* Effect.fail(e);
			}
			const now = history.all.find((s) => s.id === id);
			let next: PendingEdit = { ...current, attempts: undefined, nextAttemptAt: undefined };
			// Sent and still the row's: done. Changed since: the drain sends it again.
			if (journal && (!now || JSON.stringify(annotationsPatch(now)) === JSON.stringify(journal))) {
				next = { ...next, annotations: undefined };
			}
			if (barista !== undefined && (!now || baristaOf(now) === barista)) {
				next = { ...next, barista: undefined };
			}
			let outcome: 'sent' | 'tier' = 'sent';
			if (sentTags && pushed !== undefined) {
				const settled = JSON.parse(
					wasmSettleTagPush(
						JSON.stringify(current.tags ?? {}),
						JSON.stringify(sentTags),
						JSON.stringify(pushed),
						JSON.stringify(tagsOf(result.success))
					)
				) as TagMarks | null;
				if (settled === null) {
					// Visualizer didn't keep them: a tier signal, checked by the drain.
					next = { ...next, tierSignal: true };
					outcome = 'tier';
				} else {
					next = { ...next, tags: settled, tierSignal: undefined };
					if (now) repairTags(history, now, tagsOf(result.success), settled);
				}
			}
			settlePendingEdit(id, next);
			return outcome;
		});

		/**
		 * After a tag push landed, the row takes what the confirmed reply holds
		 * besides its own, so a tag added on the website arrives too. A tag
		 * removed here since the push stays removed.
		 */
		const repairTags = (
			history: HistoryStore,
			shot: StoredShot,
			confirmed: readonly string[],
			marks: TagMarks
		): void => {
			const norm = (list: readonly string[]): string[] =>
				JSON.parse(wasmNormalizeTags(JSON.stringify(list))) as string[];
			const have = new Set(norm([...(shot.tags ?? []), ...(marks.remove ?? [])]));
			const extra = confirmed.filter((t) => {
				const [key] = norm([t]);
				return key !== undefined && !have.has(key) && !!have.add(key);
			});
			if (extra.length > 0) history.setTags(shot.id, [...(shot.tags ?? []), ...extra]);
		};

		/** Send `id`'s pending edits now, as a History edit does. */
		const patchEditedShot = Effect.fn('ShotSync.patchEditedShot')(function* (
			history: HistoryStore,
			id: string
		) {
			yield* pushPendingEdit(history, id);
		});

		return ShotSync.of({
			uploadShot,
			deleteShot,
			patchShot,
			patchEditedShot,
			pushPendingEdit,
			pullShots,
			fetchShotDetail,
			applyShotReconciliation,
			pullAndReconcileShots,
			uploadUnsyncedShots
		});
	})
);
