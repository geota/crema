/**
 * The pour's averages (#299) show in shot detail next to the peaks, from the
 * core, and only when the shot's samples hold a pour: an old or imported shot
 * without telemetry shows nothing, not zeros.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { pourAveragesOf, type StoredShot } from '#lib/history/index.js';
import ShotDetail from './ShotDetail.svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: true } }));
Element.prototype.animate = vi.fn(
	() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation
);

/** A telemetry sample at `ms`: `pressure` bar, `flow` ml/s, 92 °C at the head. */
const sample = (ms: number, pressure: number, flow: number) => ({
	elapsed: ms,
	sample: {
		sampleTime: 0,
		groupPressure: pressure,
		groupFlow: flow,
		headTemp: 92,
		mixTemp: 92,
		setMixTemp: 0,
		setHeadTemp: 0,
		setGroupPressure: 0,
		setGroupFlow: 0,
		frameNumber: 0,
		steamTemp: 0
	}
});

/** A shot whose samples are `samples`: a 10 s preheat at no flow, then the pour. */
const shotWith = (id: string, samples: ReturnType<typeof sample>[]): StoredShot =>
	({
		formatVersion: 3,
		id,
		completedAt: 1_790_000_000_000,
		profileName: 'Profile',
		metadata: { notes: '', rating: 0 },
		record: { duration: 40_000, samples },
		bean: null,
		tags: []
	}) as unknown as StoredShot;

const preheat = Array.from({ length: 40 }, (_, i) => sample(i * 250, 0, 0));
const pour = Array.from({ length: 41 }, (_, i) => sample(10_000 + i * 250, 9, 2));

describe('pour averages in shot detail', () => {
	let host: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (host) unmount(host);
		host = null;
		document.body.innerHTML = '';
	});

	function show(shot: StoredShot): string | null {
		host = mount(WithAppContext, {
			target: document.body,
			props: { app: null, component: ShotDetail, props: { shot } }
		});
		flushSync();
		return document.querySelector('[data-testid="pour-averages"]')?.textContent?.trim() ?? null;
	}

	it('the core averages the pour only, leaving the preheat out', () => {
		const a = pourAveragesOf(shotWith('shot:avg', [...preheat, ...pour]));
		expect(a).toEqual({ pressure: 9, flow: 2, headTemp: 92 });
	});

	it('shows the averages beside the peaks', () => {
		expect(show(shotWith('shot:shown', [...preheat, ...pour]))).toBe(
			'Pour average 9.0 bar · 2.0 ml/s · 92.0°C'
		);
	});

	it('shows nothing for a shot without telemetry', () => {
		expect(pourAveragesOf(shotWith('shot:none', []))).toBeNull();
		expect(show(shotWith('shot:none-shown', []))).toBeNull();
	});
});
