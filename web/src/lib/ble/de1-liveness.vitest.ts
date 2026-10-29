/**
 * The web DE1 liveness check mirrors Android's `livenessLoop`: nothing while
 * notifications flow; after 5 s of silence a passive probe each second; two
 * failures in a row with no traffic in between drop the link; a success (or
 * fresh traffic) resets the count; paused during a firmware flash. Final
 * review 02 N8: a dead link is dropped within ~10 s (reaprime 10 s, Decenza
 * 5 s after an abandoned write), not 60–75 s.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	De1Liveness,
	LIVENESS_CHECK_INTERVAL_MS,
	LIVENESS_DEAD_WITHIN_MS,
	LIVENESS_MAX_FAILURES,
	LIVENESS_QUIET_MS
} from './de1-liveness.ts';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

function setup(probe: () => Promise<unknown>) {
	let lastInbound = Date.now();
	let paused = false;
	const onDead = vi.fn();
	const probeFn = vi.fn(probe);
	const l = new De1Liveness({
		now: () => Date.now(),
		lastInboundAtMs: () => lastInbound,
		probe: probeFn,
		paused: () => paused,
		onDead
	});
	l.start();
	return {
		l,
		onDead,
		probe: probeFn,
		heard: () => (lastInbound = Date.now()),
		pause: (p: boolean) => (paused = p)
	};
}

describe('De1Liveness', () => {
	it('drops a dead link within about 10 s of its last notification', () => {
		// The bound the upstream apps use (reaprime 10 s, Decenza 5 s + a write).
		expect(LIVENESS_DEAD_WITHIN_MS).toBeLessThanOrEqual(11_000);
	});

	it('never probes while notifications keep arriving', async () => {
		const t = setup(async () => undefined);
		for (let i = 0; i < 40; i++) {
			t.heard();
			await vi.advanceTimersByTimeAsync(500); // water levels ~2/s
		}
		expect(t.probe).not.toHaveBeenCalled();
		t.l.stop();
	});

	it('drops a link that stays silent through failed probes', async () => {
		const t = setup(() => Promise.reject(new Error('dead')));
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS);
		expect(t.probe).toHaveBeenCalledTimes(1);
		expect(t.onDead).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS * (LIVENESS_MAX_FAILURES - 1));
		expect(t.probe).toHaveBeenCalledTimes(LIVENESS_MAX_FAILURES);
		expect(t.onDead).toHaveBeenCalledOnce();
		// Stopped: no further probes.
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS * 10);
		expect(t.probe).toHaveBeenCalledTimes(LIVENESS_MAX_FAILURES);
	});

	it('hung probes (a dead link that never errors) still drop it within the bound', async () => {
		const t = setup(() => new Promise(() => undefined));
		await vi.advanceTimersByTimeAsync(LIVENESS_DEAD_WITHIN_MS);
		expect(t.probe).toHaveBeenCalledTimes(LIVENESS_MAX_FAILURES);
		expect(t.onDead).toHaveBeenCalledOnce();
	});

	it('a quiet but alive DE1 (asleep, answering probes) is never dropped', async () => {
		const t = setup(async () => undefined);
		await vi.advanceTimersByTimeAsync(120_000);
		expect(t.onDead).not.toHaveBeenCalled();
		// A success counts as traffic: at most one probe per quiet window.
		expect(t.probe.mock.calls.length).toBeLessThanOrEqual(120_000 / LIVENESS_QUIET_MS);
		t.l.stop();
	});

	it('one failed probe followed by a success does not drop the link', async () => {
		let ok = false;
		const t = setup(() => (ok ? Promise.resolve() : Promise.reject(new Error('busy'))));
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS);
		expect(t.probe).toHaveBeenCalledTimes(1);
		ok = true;
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS);
		expect(t.probe).toHaveBeenCalledTimes(2);
		ok = false;
		// The success reset the count and counts as traffic: another quiet
		// window, then one failure — still alive.
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS);
		expect(t.probe).toHaveBeenCalledTimes(3);
		expect(t.onDead).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS);
		expect(t.onDead).toHaveBeenCalledOnce();
	});

	it('a failed probe while notifications arrived meanwhile does not count', async () => {
		let heard: () => void = () => {};
		const t = setup(
			() =>
				new Promise((_, reject) => {
					// The read is refused (busy GATT queue) but a notification lands
					// while it was out.
					setTimeout(() => {
						heard();
						reject(new Error('GATT operation already in progress'));
					}, 100);
				})
		);
		heard = t.heard;
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS + LIVENESS_CHECK_INTERVAL_MS * 20);
		expect(t.probe.mock.calls.length).toBeGreaterThan(1);
		expect(t.onDead).not.toHaveBeenCalled();
		t.l.stop();
	});

	it('does nothing during a firmware flash', async () => {
		const t = setup(() => Promise.reject(new Error('x')));
		t.pause(true);
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS * 4);
		expect(t.probe).not.toHaveBeenCalled();
		t.l.stop();
	});
});
