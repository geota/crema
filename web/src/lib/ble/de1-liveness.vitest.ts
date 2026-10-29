/**
 * The web DE1 liveness check mirrors Android's `livenessLoop`: nothing while
 * notifications flow; after 30 s of silence a passive probe every 15 s; three
 * failures in a row drop the link; a success (or fresh traffic) resets the
 * count; paused during a firmware flash.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	De1Liveness,
	LIVENESS_CHECK_INTERVAL_MS,
	LIVENESS_MAX_FAILURES,
	LIVENESS_PROBE_TIMEOUT_MS,
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
	it('never probes while notifications keep arriving', async () => {
		const t = setup(async () => undefined);
		for (let i = 0; i < 10; i++) {
			t.heard();
			await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS);
		}
		expect(t.probe).not.toHaveBeenCalled();
		t.l.stop();
	});

	it('drops a link that stays silent through three failed probes', async () => {
		const t = setup(() => Promise.reject(new Error('dead')));
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS);
		expect(t.probe).toHaveBeenCalledTimes(1);
		expect(t.onDead).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS * (LIVENESS_MAX_FAILURES - 1));
		expect(t.probe).toHaveBeenCalledTimes(LIVENESS_MAX_FAILURES);
		expect(t.onDead).toHaveBeenCalledOnce();
		// Stopped: no further probes.
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS * 4);
		expect(t.probe).toHaveBeenCalledTimes(LIVENESS_MAX_FAILURES);
	});

	it('a hung probe counts as a failure after its time limit', async () => {
		const t = setup(() => new Promise(() => undefined));
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS);
		for (let i = 1; i < LIVENESS_MAX_FAILURES; i++) {
			await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS);
		}
		await vi.advanceTimersByTimeAsync(LIVENESS_PROBE_TIMEOUT_MS);
		expect(t.onDead).toHaveBeenCalledOnce();
	});

	it('a successful probe (a quiet but alive DE1) resets the count', async () => {
		let ok = false;
		const t = setup(() => (ok ? Promise.resolve() : Promise.reject(new Error('x'))));
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS + LIVENESS_CHECK_INTERVAL_MS);
		expect(t.probe).toHaveBeenCalledTimes(2);
		ok = true;
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS);
		ok = false;
		// The success counts as traffic: 30 s quiet again before the next probe,
		// then two failures — not yet three.
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS + LIVENESS_CHECK_INTERVAL_MS);
		expect(t.onDead).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(LIVENESS_CHECK_INTERVAL_MS);
		expect(t.onDead).toHaveBeenCalledOnce();
	});

	it('does nothing during a firmware flash', async () => {
		const t = setup(() => Promise.reject(new Error('x')));
		t.pause(true);
		await vi.advanceTimersByTimeAsync(LIVENESS_QUIET_MS * 4);
		expect(t.probe).not.toHaveBeenCalled();
		t.l.stop();
	});
});
