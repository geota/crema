/**
 * `$lib/ble/reconnect-timeline.vitest` — episode bookkeeping, phase
 * durations and the compact line. The expected lines match the Android
 * shell's `ReconnectTimelineTest`, so a report reads the same from either.
 */

import { describe, expect, it } from 'vitest';
import {
	DEFAULT_TIMELINE_CAPACITY,
	ReconnectTimelineRecorder,
	compactLine,
	phaseDurations,
	type ReconnectTimeline
} from './reconnect-timeline.ts';

function rig() {
	let now = 0;
	const finished: ReconnectTimeline[] = [];
	const rec = new ReconnectTimelineRecorder(
		() => now,
		() => 0
	);
	rec.onFinished = (t) => finished.push(t);
	return { rec, finished, at: (ms: number) => (now = ms), now: () => now };
}

describe('ReconnectTimelineRecorder', () => {
	it('records each phase with its duration and the time to READY', () => {
		const { rec, finished, at } = rig();
		rec.begin('DE1', 'foreground');
		rec.attemptStarted('DE1');
		at(100);
		rec.mark('DE1', 'scan');
		at(900);
		rec.mark('DE1', 'gatt');
		at(2_000);
		rec.mark('DE1', 'discover');
		at(2_300);
		rec.mark('DE1', 'subscribe');
		at(2_500);
		rec.ready('DE1', true);
		expect(finished).toHaveLength(0); // waits for the post-connect sync
		at(3_100);
		rec.postConnectDone('DE1');

		const t = rec.timelines[0];
		expect(phaseDurations(t)).toEqual([
			['scan', 800],
			['gatt', 1_100],
			['discover', 300],
			['subscribe', 200],
			['post-connect', 600]
		]);
		expect(compactLine(finished[0])).toBe(
			'reconnect DE1 · foreground → READY in 2.5s · 1 attempt · scan 0.8s · gatt 1.1s · ' +
				'discover 0.3s · subscribe 0.2s · post-connect 0.6s'
		);
	});

	it('accumulates backoff waits and failures across attempts', () => {
		const { rec, finished, at, now } = rig();
		rec.begin('Scale', 'drop');
		for (let i = 0; i < 2; i++) {
			rec.mark('Scale', 'backoff');
			at(now() + 500);
			rec.attemptStarted('Scale');
			rec.mark('Scale', 'gatt');
			at(now() + 1_000);
			rec.attemptFailed('Scale', 'GATT 133');
		}
		rec.end('Scale', 'abandoned');
		expect(compactLine(finished[0])).toBe(
			'reconnect Scale · drop → abandoned after 3.0s · 2 attempts (2 failed) · backoff 1.0s · gatt 2.0s · last error: GATT 133'
		);
	});

	it('a new trigger supersedes the open episode', () => {
		const { rec, finished, at } = rig();
		rec.begin('DE1', 'drop');
		at(40_000);
		rec.begin('DE1', 'user-retry');
		const [retry, drop] = rec.timelines;
		expect(retry.outcome).toBe('in progress');
		expect(drop.outcome).toBe('superseded');
		expect(drop.endedAtMs).toBe(40_000);
		expect(finished.map((t) => t.id)).toEqual([drop.id]);
	});

	it('ignores marks with no open episode and keeps devices apart', () => {
		const { rec, finished } = rig();
		rec.mark('DE1', 'gatt');
		rec.ready('DE1');
		expect(rec.timelines).toHaveLength(0);
		rec.begin('DE1', 'foreground');
		rec.begin('Scale', 'foreground');
		rec.ready('Scale');
		expect(rec.isOpen('DE1')).toBe(true);
		expect(finished.map((t) => t.device)).toEqual(['Scale']);
	});

	it('keeps only the newest episodes and bounds a long lurk', () => {
		const { rec, at, now } = rig();
		for (let i = 0; i < DEFAULT_TIMELINE_CAPACITY + 5; i++) {
			at(i);
			rec.begin('DE1', 'drop');
			rec.ready('DE1');
		}
		expect(rec.timelines).toHaveLength(DEFAULT_TIMELINE_CAPACITY);
		expect(rec.timelines[0].startedAtMs).toBe(DEFAULT_TIMELINE_CAPACITY + 4);

		rec.begin('Scale', 'drop');
		for (let i = 0; i < 1_000; i++) {
			at(now() + 60_000);
			rec.mark('Scale', 'backoff');
		}
		expect(rec.timelines[0].marks).toHaveLength(64);
	});

	it('notifies subscribers', () => {
		const { rec } = rig();
		const seen: number[] = [];
		const off = rec.subscribe((list) => seen.push(list.length));
		rec.begin('DE1', 'launch');
		off();
		rec.begin('Scale', 'launch');
		expect(seen).toEqual([0, 1]);
	});
});
