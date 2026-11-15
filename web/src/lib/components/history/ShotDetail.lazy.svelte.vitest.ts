/**
 * #310 P4w: the detail is given a row with no samples and loads the whole
 * shot itself. Until it lands the chart says it's loading, and nothing reads
 * the row as a shot without telemetry; a load for an earlier pick, or an
 * earlier version of the row, never lands on the page.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { ShotRow, StoredShot } from '#lib/history/index.js';
import { summarize } from '#lib/history/summary.js';
import ShotDetail from './ShotDetail.svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: true } }));
Element.prototype.animate = vi.fn(() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation);

beforeAll(async () => {
	await initTestWasm();
});

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

/** A whole shot whose pour runs at `pressure` bar. */
const wholeShot = (id: string, pressure: number): StoredShot =>
	({
		formatVersion: 3,
		id,
		completedAt: 1_790_000_000_000,
		profileName: 'Profile',
		metadata: { notes: '', rating: 0 },
		record: { duration: 20_000, samples: Array.from({ length: 41 }, (_, i) => sample(i * 250, pressure, 2)) },
		bean: null,
		tags: []
	}) as unknown as StoredShot;

/** `shot` as the history hands it out: its summary, no samples. */
function rowOf(shot: StoredShot): ShotRow {
	const { record, profile: _p, brewSeries: _b, ...rest } = shot;
	return { ...rest, record: { duration: record.duration }, summary: summarize(shot) };
}

/** Loads that end when the test says, by id, in any order. */
function loads() {
	const waiting: { id: string; resolve: (s: StoredShot | undefined) => void }[] = [];
	const loadShot = (id: string) => new Promise<StoredShot | undefined>((resolve) => waiting.push({ id, resolve }));
	return { loadShot, waiting };
}

const settle = async () => {
	for (let i = 0; i < 5; i++) await Promise.resolve();
	flushSync();
};
const pourLine = () => document.querySelector('[data-testid="pour-averages"]')?.textContent?.trim() ?? null;
const loading = () => document.querySelector('[data-testid="curve-loading"]')?.textContent?.trim() ?? null;

describe('the detail loads its shot', () => {
	let host: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (host) unmount(host);
		host = null;
		document.body.innerHTML = '';
	});

	function show(shot: ShotRow, loadShot: (id: string) => Promise<StoredShot | undefined>) {
		host = mount(WithAppContext, {
			target: document.body,
			props: { app: null, component: ShotDetail, props: { shot, loadShot } }
		});
		flushSync();
	}

	it('says it’s loading the curve, then draws from the whole shot', async () => {
		const { loadShot, waiting } = loads();
		const whole = wholeShot('shot:a', 9);
		show(rowOf(whole), loadShot);
		expect(loading()).toBe('Loading the curve…');
		expect(pourLine()).toBeNull();
		expect(waiting.map((w) => w.id)).toEqual(['shot:a']);
		waiting[0].resolve(whole);
		await settle();
		expect(loading()).toBeNull();
		expect(pourLine()).toBe('Pour average 9.0 bar · 2.0 ml/s · 92.0°C');
	});

	it('rapid selection: B’s page shows B, though A’s slow load ends after B’s', async () => {
		const { loadShot, waiting } = loads();
		const a = wholeShot('shot:a', 6);
		const b = wholeShot('shot:b', 9);
		// The page keys the detail on the shot: picking B unmounts A's.
		show(rowOf(a), loadShot);
		unmount(host!);
		document.body.innerHTML = '';
		show(rowOf(b), loadShot);
		waiting.find((w) => w.id === 'shot:b')!.resolve(b);
		await settle();
		waiting.find((w) => w.id === 'shot:a')!.resolve(a);
		await settle();
		expect(pourLine()).toBe('Pour average 9.0 bar · 2.0 ml/s · 92.0°C');
	});

	it('a load says which shot it is: a wrong one never lands, and the chart keeps waiting', async () => {
		const { loadShot, waiting } = loads();
		show(rowOf(wholeShot('shot:a', 9)), loadShot);
		waiting[0].resolve(wholeShot('shot:other', 3));
		await settle();
		expect(pourLine()).toBeNull();
	});

	it('a failed load says so, and shows no empty chart', async () => {
		show(rowOf(wholeShot('shot:a', 9)), () => Promise.reject(new Error('unreadable')));
		await settle();
		expect(loading()).toBe('This shot’s curve couldn’t be read.');
		expect(pourLine()).toBeNull();
	});
});
