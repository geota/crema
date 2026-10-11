import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { CoreOutput } from '$lib/core';
import { IdleActivity, type IdleSleepInput } from '$lib/core/crema-core';
import {
	applyCommonToSettings,
	DEFAULT_SETTINGS,
	settingsToCommon
} from '$lib/settings/store.svelte';
import {
	DEFAULT_IDLE_SLEEP_MIN,
	IDLE_ACTIVITY_THROTTLE_MS,
	IDLE_SLEEP_FALLBACK_CHOICES,
	IDLE_SLEEP_TICK_MS,
	IdleSleepRunner,
	idleSleepLabel,
	type IdleSleepDom
} from './idle-sleep';

const SLEEP: CoreOutput = {
	events: [{ type: 'IdleSleepRequested', content: { attempt: 1 } }],
	commands: []
};

/** The idle-sleep timer, shell side (Android `IdleSleepTest` parity). The timer
 *  itself is core-tested; this pins the feed and the settings mapping. */
function harness(minutes = 60) {
	const fed: [IdleSleepInput, number][] = [];
	const applied: CoreOutput[] = [];
	let now = 1_000_000;
	let current = minutes;
	const core = {
		idleSleep: vi.fn(async (input: IdleSleepInput, nowMs: number) => {
			fed.push([input, nowMs]);
			return input.type === 'Tick' ? SLEEP : { events: [], commands: [] };
		})
	};
	const runner = new IdleSleepRunner({
		core,
		apply: (out) => applied.push(out),
		now: () => now,
		minutes: () => current
	});
	return {
		runner,
		fed,
		applied,
		inputs: () => fed.map(([i]) => i),
		advance: (ms: number) => (now += ms),
		setMinutes: (m: number) => (current = m),
		now: () => now
	};
}

/** A minimal event-target DOM with a controllable visibility state. */
function fakeDom() {
	const listeners = new Map<string, Set<() => void>>();
	const dom: IdleSleepDom & { visibilityState: DocumentVisibilityState } = {
		visibilityState: 'visible',
		addEventListener: (type, l) => {
			if (!listeners.has(type)) listeners.set(type, new Set());
			listeners.get(type)!.add(l);
		},
		removeEventListener: (type, l) => listeners.get(type)?.delete(l)
	};
	const fire = (type: string) => listeners.get(type)?.forEach((l) => l());
	return { dom, fire, count: () => [...listeners.values()].reduce((n, s) => n + s.size, 0) };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('IdleSleepRunner', () => {
	beforeEach(() => vi.useRealTimers());
	afterEach(() => vi.useRealTimers());

	it('sends the setting, then a tick re-asserts it before checking', async () => {
		const h = harness(60);
		h.runner.configure(60);
		h.runner.tick();
		await flush();
		expect(h.inputs()).toEqual([
			{ type: 'Configure', content: { minutes: 60 } },
			{ type: 'Configure', content: { minutes: 60 } },
			{ type: 'Tick' }
		]);
		// The core's output (the Sleep write + event) is applied.
		expect(h.applied).toContainEqual(SLEEP);
		// A setting changed elsewhere (a restore) reaches the core on the next tick.
		h.setMinutes(15);
		h.runner.tick();
		await flush();
		expect(h.inputs().slice(-2)).toEqual([
			{ type: 'Configure', content: { minutes: 15 } },
			{ type: 'Tick' }
		]);
	});

	it('stamps every input with the wall clock', async () => {
		const h = harness();
		h.runner.tick();
		h.advance(5_000);
		h.runner.activity(IdleActivity.Touch);
		await flush();
		expect(h.fed.map(([, t]) => t)).toEqual([1_000_000, 1_000_000, 1_005_000]);
	});

	it('forwards only link edges', async () => {
		const h = harness();
		h.runner.onDe1State('connecting');
		h.runner.onDe1State('ready');
		h.runner.onDe1State('ready');
		h.runner.onDe1State('reconnecting');
		h.runner.onDe1State('disconnected');
		await flush();
		expect(h.inputs()).toEqual([
			{ type: 'Link', content: { ready: true } },
			{ type: 'Link', content: { ready: false } }
		]);
	});

	it('throttles pointer / key activity but not foreground or navigation', async () => {
		const h = harness();
		h.runner.activity(IdleActivity.Touch);
		h.advance(100);
		h.runner.activity(IdleActivity.Touch);
		h.runner.activity(IdleActivity.Key);
		h.runner.activity(IdleActivity.Foreground);
		h.runner.activity(IdleActivity.Navigation);
		h.advance(IDLE_ACTIVITY_THROTTLE_MS);
		h.runner.activity(IdleActivity.Key);
		await flush();
		expect(h.inputs()).toEqual([
			{ type: 'Activity', content: { kind: 'Touch' } },
			{ type: 'Activity', content: { kind: 'Foreground' } },
			{ type: 'Activity', content: { kind: 'Navigation' } },
			{ type: 'Activity', content: { kind: 'Key' } }
		]);
	});

	it('listens for pointer, keys, visibility and back/forward; a visible tab also checks at once', async () => {
		const h = harness();
		const { dom, fire, count } = fakeDom();
		h.runner.attach(dom);
		fire('pointerdown');
		h.advance(IDLE_ACTIVITY_THROTTLE_MS);
		fire('keydown');
		dom.visibilityState = 'hidden';
		fire('visibilitychange');
		dom.visibilityState = 'visible';
		fire('visibilitychange');
		fire('popstate');
		await flush();
		expect(h.inputs().map((i) => (i.type === 'Activity' ? i.content.kind : i.type))).toEqual([
			'Touch',
			'Key',
			// Hidden: a check only (a hidden tab is not activity).
			'Configure',
			'Tick',
			// Visible again: activity plus an immediate check — throttled
			// background ticks may be minutes behind.
			'Foreground',
			'Configure',
			'Tick',
			'Navigation'
		]);
		h.runner.stop();
		expect(count()).toBe(0);
	});

	it('ticks on the cadence, never on a single long timeout', async () => {
		vi.useFakeTimers();
		const h = harness();
		h.runner.start();
		h.runner.start(); // idempotent
		vi.advanceTimersByTime(IDLE_SLEEP_TICK_MS * 3);
		expect(h.inputs().filter((i) => i.type === 'Tick')).toHaveLength(3);
		h.runner.stop();
		vi.advanceTimersByTime(IDLE_SLEEP_TICK_MS * 5);
		expect(h.inputs().filter((i) => i.type === 'Tick')).toHaveLength(3);
	});

	it('clamps a bad setting to Never rather than a negative timeout', async () => {
		const h = harness();
		h.runner.configure(-3);
		h.runner.configure(29.6);
		await flush();
		expect(h.inputs()).toEqual([
			{ type: 'Configure', content: { minutes: 0 } },
			{ type: 'Configure', content: { minutes: 30 } }
		]);
	});
});

describe('the "Cool down after" setting', () => {
	it('defaults to 60 min, as de1app and Decenza', () => {
		expect(DEFAULT_SETTINGS.idleSleepMin).toBe(60);
		expect(DEFAULT_IDLE_SLEEP_MIN).toBe(60);
		expect(IDLE_SLEEP_FALLBACK_CHOICES).toEqual([0, 15, 30, 60, 90, 120]);
		expect(idleSleepLabel(0)).toBe('Never');
		expect(idleSleepLabel(90)).toBe('90 min');
	});

	it('travels in CommonSettings; the retired keep-awake flag is still written for older readers', () => {
		const c = settingsToCommon({ ...DEFAULT_SETTINGS, idleSleepMin: 15 });
		expect(c.idleSleepMin).toBe(15);
		expect(c.suppressDe1Sleep).toBe(true);
		expect(applyCommonToSettings(c, DEFAULT_SETTINGS).idleSleepMin).toBe(15);
	});

	it("a backup from before the timer keeps this device's value", () => {
		const { idleSleepMin: _omit, ...old } = settingsToCommon(DEFAULT_SETTINGS);
		void _omit;
		const restored = applyCommonToSettings(old, { ...DEFAULT_SETTINGS, idleSleepMin: 120 });
		expect(restored.idleSleepMin).toBe(120);
		// …and the retired flag in it is ignored, whatever it says.
		const legacy = applyCommonToSettings(
			{ ...old, suppressDe1Sleep: false },
			{ ...DEFAULT_SETTINGS, idleSleepMin: 30 }
		);
		expect(legacy.idleSleepMin).toBe(30);
		expect('suppressDe1Sleep' in legacy).toBe(false);
	});
});
