/**
 * `#lib/visualizer/pull-run-core.js` — the core's pull rules
 * (`de1_domain::pull_run`), typed. Each call crosses the bridge as JSON
 * through one entry point, `pullRunCall(op, args)`; the loop that drives them
 * is `ShotSync.pullAndReconcileShots`.
 */

import type {
	KeyCandidate,
	ListRow,
	ListStep,
	PaceBucket,
	PageInput,
	PagePlan,
	PoisonEntry,
	PullTally,
	RowAction,
	RowFailure,
	RowOutcome,
	RunOutcome,
	SyncFailure
} from '#lib/core/crema-core.js';
import { pullRunCall } from '#lib/wasm/de1_wasm.js';

function call<T>(op: string, args: unknown): T {
	return JSON.parse(pullRunCall(op, JSON.stringify(args))) as T;
}

export const pullRules = {
	planPage: (input: PageInput): PagePlan => call('planPage', input),
	chooseBind: (candidates: KeyCandidate[], durationMs: number): KeyCandidate | null =>
		call('chooseBind', { candidates, durationMs }),
	rowFailureAction: (failure: RowFailure, attempt: number, jitter: number = Math.random()): RowAction =>
		call('rowFailureAction', { failure, attempt, jitter }),
	recordRow: (tally: PullTally, row: ListRow, outcome: RowOutcome): PullTally =>
		call('recordRow', { tally, row, outcome }),
	endRun: (tally: PullTally, failure: SyncFailure): PullTally => call('endRun', { tally, failure }),
	afterListPage: (tally: PullTally, count: number, ownDeletes: number): { tally: PullTally; step: ListStep } =>
		call('afterListPage', { tally, count, ownDeletes }),
	settlePoison: (entries: PoisonEntry[], tally: PullTally, resolved: string[], nowMs: number): PoisonEntry[] =>
		call('settlePoison', { entries, tally, resolved, nowMs }),
	notePoisonRetry: (entries: PoisonEntry[], id: string, nowMs: number): PoisonEntry[] =>
		call('notePoisonRetry', { entries, id, nowMs }),
	poisonDue: (entries: PoisonEntry[], nowMs: number): string[] => call('poisonDue', { entries, nowMs }),
	healthProbeDue: (tally: PullTally): boolean => call('healthProbeDue', { tally }),
	recordHealthProbe: (tally: PullTally, succeeded: boolean): PullTally =>
		call('recordHealthProbe', { tally, succeeded }),
	finishRun: (tally: PullTally, poison: PoisonEntry[]): RunOutcome => call('finishRun', { tally, poison }),
	pace: (bucket: PaceBucket, nowMs: number): { bucket: PaceBucket; waitMs: number } =>
		call('pace', { bucket, nowMs }),
	noteRateLimit: (bucket: PaceBucket, nowMs: number, waitMs: number): PaceBucket =>
		call('noteRateLimit', { bucket, nowMs, waitMs })
};
