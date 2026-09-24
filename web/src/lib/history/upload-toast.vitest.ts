import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const shown: { kind: string; message: string }[] = [];
vi.mock('$lib/components/shared/toast.svelte', () => ({
	toast: {
		success: (m: string) => shown.push({ kind: 'success', message: m }),
		error: (m: string) => shown.push({ kind: 'error', message: m }),
		info: (m: string) => shown.push({ kind: 'info', message: m })
	}
}));
const { BATCH_TIMEOUT_MS, registerUploadBatch, reportUploadOutcome, resetUploadBatches, summarizeUploadOutcomes } =
	await import('./upload-toast');

beforeEach(() => {
	shown.length = 0;
	resetUploadBatches();
});
afterEach(() => {
	vi.useRealTimers();
});

describe('upload toast batching', () => {
	it('waits for every registered destination, then toasts once', () => {
		registerUploadBatch('s1', ['Visualizer', 'Decent']);
		reportUploadOutcome('s1', 'Visualizer', { kind: 'uploaded' });
		expect(shown).toEqual([]);
		reportUploadOutcome('s1', 'Decent', { kind: 'uploaded' });
		expect(shown).toEqual([{ kind: 'success', message: 'Uploaded to Visualizer + Decent' }]);
	});
	it('names the failure next to the success', () => {
		registerUploadBatch('s2', ['Visualizer', 'Decent']);
		reportUploadOutcome('s2', 'Decent', { kind: 'failed', message: 'serial not on account' });
		reportUploadOutcome('s2', 'Visualizer', { kind: 'uploaded' });
		expect(shown).toEqual([{ kind: 'error', message: 'Uploaded to Visualizer · Decent failed: serial not on account' }]);
	});
	it('is silent when everything was an expected skip, and toasts alone without a batch', () => {
		registerUploadBatch('s3', ['Decent']);
		reportUploadOutcome('s3', 'Decent', { kind: 'skipped', message: 'Shorter than 5 s' });
		expect(shown).toEqual([]);
		reportUploadOutcome('s4', 'Visualizer', { kind: 'queued' });
		expect(shown).toEqual([{ kind: 'info', message: 'Visualizer queued to retry' }]);
	});
	it('summarises queued alongside uploaded as a success', () => {
		const r = new Map([
			['Visualizer', { kind: 'queued' as const }],
			['Decent', { kind: 'uploaded' as const }]
		]);
		expect(summarizeUploadOutcomes(r)).toEqual({ kind: 'success', message: 'Uploaded to Decent · Visualizer queued to retry' });
	});
	it('explains an all-skip MANUAL batch instead of staying silent', () => {
		registerUploadBatch('m1', ['Decent'], { manual: true });
		reportUploadOutcome('m1', 'Decent', { kind: 'skipped', message: 'No DE1 serial number known — connect the machine first' });
		expect(shown).toEqual([
			{ kind: 'info', message: 'Not uploaded — Decent: No DE1 serial number known — connect the machine first' }
		]);
	});
	it('does not close early when an unregistered destination answers first', () => {
		registerUploadBatch('u1', ['Visualizer']);
		reportUploadOutcome('u1', 'Decent', { kind: 'failed', message: 'offline' });
		expect(shown).toEqual([]);
		reportUploadOutcome('u1', 'Visualizer', { kind: 'uploaded' });
		expect(shown).toEqual([{ kind: 'error', message: 'Uploaded to Visualizer · Decent failed: offline' }]);
	});
	it('toasts what it has once the timeout passes, and only once', () => {
		vi.useFakeTimers();
		registerUploadBatch('t1', ['Visualizer', 'Decent']);
		reportUploadOutcome('t1', 'Visualizer', { kind: 'uploaded' });
		vi.advanceTimersByTime(BATCH_TIMEOUT_MS - 1);
		expect(shown).toEqual([]);
		vi.advanceTimersByTime(1);
		expect(shown).toEqual([{ kind: 'success', message: 'Uploaded to Visualizer' }]);
		// A straggler after the timeout is a fresh one-off report, not a second summary of t1.
		reportUploadOutcome('t1', 'Decent', { kind: 'skipped', message: 'late' });
		expect(shown).toHaveLength(1);
	});
});
