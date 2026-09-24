/**
 * `$lib/history/upload-toast` — one completion toast per shot across every
 * cloud destination.
 *
 * Each destination's push (Visualizer, Decent) used to toast on its own, so a
 * shot going to both produced two toasts a second apart. Now the caller
 * registers a batch naming the destinations it is about to fire, each push
 * reports its outcome here, and the batch collapses into a single line once
 * every registered destination has answered (or after
 * {@link BATCH_TIMEOUT_MS}): "Uploaded to Visualizer + Decent", or
 * "Uploaded to Visualizer · Decent failed: …". A destination that reports
 * without being registered joins the batch's summary but is not waited for.
 * A report with no batch at all toasts on its own.
 *
 * Skips (too short, no serial, auto-upload off) are silent on the automatic
 * path — an expected non-event should not nag. On a MANUAL batch (the user
 * tapped Upload) a batch that is all skips shows an info notice with the
 * reasons, so the tap never looks ignored.
 */

import { toast } from '$lib/components/shared/toast.svelte';

export type UploadOutcomeKind = 'uploaded' | 'queued' | 'failed' | 'skipped';
export interface UploadOutcome {
	kind: UploadOutcomeKind;
	/** Failure / skip reason, or the queued note. */
	message?: string;
}

interface Batch {
	expected: Set<string>;
	manual: boolean;
	results: Map<string, UploadOutcome>;
	timer: ReturnType<typeof setTimeout> | null;
}

const batches = new Map<string, Batch>();
/** A destination that never answers must not hold the toast forever. */
export const BATCH_TIMEOUT_MS = 90_000;

/**
 * Declare the destinations a shot is about to be pushed to. No-op for none.
 * `manual` — a user-initiated upload: an all-skip batch is explained (info)
 * rather than silent.
 */
export function registerUploadBatch(
	shotId: string,
	destinations: readonly string[],
	opts: { manual?: boolean } = {}
): void {
	if (destinations.length === 0) return;
	const existing = batches.get(shotId);
	if (existing) {
		for (const d of destinations) existing.expected.add(d);
		if (opts.manual) existing.manual = true;
		return;
	}
	const batch: Batch = {
		expected: new Set(destinations),
		manual: !!opts.manual,
		results: new Map(),
		timer: null
	};
	batch.timer = setTimeout(() => finish(shotId), BATCH_TIMEOUT_MS);
	batches.set(shotId, batch);
}

/** A destination's answer; toasts once the batch is complete. */
export function reportUploadOutcome(shotId: string, destination: string, outcome: UploadOutcome): void {
	let batch = batches.get(shotId);
	if (!batch) {
		batch = { expected: new Set([destination]), manual: false, results: new Map(), timer: null };
		batches.set(shotId, batch);
	}
	batch.results.set(destination, outcome);
	// Complete only when every REGISTERED destination has answered — an
	// unregistered one reporting first must not close the batch early.
	const results = batch.results;
	if ([...batch.expected].every((d) => results.has(d))) finish(shotId);
}

/** Compose the one-line summary (exported for tests). */
export function summarizeUploadOutcomes(
	results: ReadonlyMap<string, UploadOutcome>,
	opts: { manual?: boolean } = {}
): {
	kind: 'success' | 'error' | 'info' | 'none';
	message: string;
} {
	const uploaded = [...results].filter(([, r]) => r.kind === 'uploaded').map(([d]) => d);
	const queued = [...results].filter(([, r]) => r.kind === 'queued').map(([d]) => d);
	const failed = [...results].filter(([, r]) => r.kind === 'failed');
	const parts: string[] = [];
	if (uploaded.length > 0) parts.push(`Uploaded to ${uploaded.join(' + ')}`);
	if (queued.length > 0) parts.push(`${queued.join(' + ')} queued to retry`);
	for (const [d, r] of failed) parts.push(`${d} failed${r.message ? `: ${r.message}` : ''}`);
	if (parts.length === 0) {
		const skipped = [...results].filter(([, r]) => r.kind === 'skipped');
		if (opts.manual && skipped.length > 0) {
			const reasons = skipped.map(([d, r]) => (r.message ? `${d}: ${r.message}` : `${d} skipped`));
			return { kind: 'info', message: `Not uploaded — ${reasons.join(' · ')}` };
		}
		return { kind: 'none', message: '' };
	}
	const kind = failed.length > 0 ? 'error' : queued.length > 0 && uploaded.length === 0 ? 'info' : 'success';
	return { kind, message: parts.join(' · ') };
}

function finish(shotId: string): void {
	const batch = batches.get(shotId);
	if (!batch) return;
	batches.delete(shotId);
	if (batch.timer) clearTimeout(batch.timer);
	const summary = summarizeUploadOutcomes(batch.results, { manual: batch.manual });
	if (summary.kind === 'success') toast.success(summary.message);
	else if (summary.kind === 'error') toast.error(summary.message);
	else if (summary.kind === 'info') toast.info(summary.message);
}

/** Test hook — drop every pending batch. */
export function resetUploadBatches(): void {
	for (const b of batches.values()) if (b.timer) clearTimeout(b.timer);
	batches.clear();
}
