/**
 * Every web store writes its whole blob, so a tab with an old copy used to put
 * it back over another tab's change (#333). Each store now takes what another
 * tab wrote: on the `storage` event, and when it comes back into view. Two
 * store instances over one `localStorage` stand in for two tabs; the event is
 * dispatched by hand, as a browser only sends it to the other tabs.
 */
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import type { TimedSample } from '#lib/core/index.js';
import { initTestWasm } from '#lib/testing/test-init.js';
import { coerceBean } from '#lib/bean/model.js';
import { BeanLibraryStore } from '#lib/bean/store.svelte.js';
import { CustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { RecipeStore } from '#lib/brew/recipes.svelte.js';
import { MaintenanceStore } from '#lib/maintenance/store.svelte.js';
import { ProfileStore } from '#lib/profiles/store.svelte.js';
import { SettingsStore } from '#lib/settings/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import { clearAllSeries, getAllSeries, putSeries } from '#lib/history/series-store.js';
import { HistoryStore } from '#lib/history/store.svelte.js';
import { readStored, resetStorageStateForTest } from './storage';
import { resetTabSafetyForTest, shotFinished, shotStarted } from '#lib/restore/tab-safety.js';
import type { CremaProfile } from '#lib/profiles/model.js';

const BEANS = 'crema.beans.v1';
const HISTORY = 'crema.history.v2';

/** What the browser tells every other tab after a write to `key`. */
function announce(key: string): void {
	window.dispatchEvent(new StorageEvent('storage', { key, newValue: localStorage.getItem(key) }));
}

/** Another tab stores `value` at `key`, and this tab hears of it. */
function otherTabWrites(key: string, value: unknown): void {
	localStorage.setItem(key, JSON.stringify(value));
	announce(key);
}

/** This tab comes back into view. */
function comeBack(): void {
	Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
	document.dispatchEvent(new Event('visibilitychange'));
}

const storedBean = (id: string) =>
	(JSON.parse(localStorage.getItem(BEANS)!) as { beans: { id: string; remaining: number }[] }).beans.find(
		(b) => b.id === id
	);

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	localStorage.clear();
	resetStorageStateForTest();
});

describe('beans', () => {
	/** Two tabs open on the same library, with a 250 g bag and another. */
	const twoTabs = () => {
		const seed = new BeanLibraryStore();
		seed.upsertBean({ ...coerceBean({ id: 'gesha', name: 'Gesha' })!, bagSize: 250, remaining: 250 });
		seed.upsertBean({ ...coerceBean({ id: 'kenya', name: 'Kenya' })! });
		resetStorageStateForTest();
		return { a: new BeanLibraryStore(), b: new BeanLibraryStore() };
	};

	it("a stale tab's edit keeps the other tab's debit (the issue's repro)", () => {
		const { a, b } = twoTabs();
		a.debitBean('gesha', 36);
		announce(BEANS);
		b.updateBean('kenya', { rating: 4 });
		expect(storedBean('gesha')?.remaining).toBe(214);
		expect(b.getBean('gesha')?.remaining).toBe(214);
	});

	it('a write missed while hidden is taken on coming back', () => {
		const { b } = twoTabs();
		// The other tab's debit, with no event: this tab was frozen.
		const env = JSON.parse(localStorage.getItem(BEANS)!) as { beans: { id: string; remaining: number }[] };
		env.beans.find((x) => x.id === 'gesha')!.remaining = 214;
		localStorage.setItem(BEANS, JSON.stringify(env));
		comeBack();
		b.updateBean('kenya', { rating: 4 });
		expect(storedBean('gesha')?.remaining).toBe(214);
	});

	it('the active bag follows the other tab', () => {
		const { a, b } = twoTabs();
		a.setActiveBean('kenya');
		announce('crema.beans.activeBeanId.v1');
		expect(b.activeBeanId).toBe('kenya');
	});

	it("a value this tab can't read is left alone, and this tab's copy stays", () => {
		const { b } = twoTabs();
		otherTabWrites(BEANS, { schemaVersion: 99, shelves: [] });
		expect(b.getBean('gesha')).not.toBeNull();
		expect(localStorage.getItem(BEANS)).toBe(JSON.stringify({ schemaVersion: 99, shelves: [] }));
		expect(localStorage.getItem(`${BEANS}.corrupt`)).toBeNull();
		// And it isn't followed again: a later write doesn't replace this copy.
		otherTabWrites(BEANS, { schemaVersion: 1, beans: [], roasters: [] });
		expect(b.getBean('gesha')).not.toBeNull();
	});

	it("a value that doesn't parse stops the following too, so a later write isn't taken", () => {
		const { b } = twoTabs();
		localStorage.setItem(BEANS, '{"schemaVersion":');
		announce(BEANS);
		otherTabWrites(BEANS, { schemaVersion: 1, beans: [], roasters: [] });
		expect(b.getBean('gesha')).not.toBeNull();
	});

	it('a tab that quarantined and started empty never leads this one (#380 review M1)', () => {
		const { a } = twoTabs();
		// The stored value goes bad: this tab ignores it.
		localStorage.setItem(BEANS, '{"schemaVersion":');
		announce(BEANS);
		// Another tab boots on it, keeps it aside, removes the key, starts empty.
		const c = new BeanLibraryStore();
		announce(BEANS);
		expect(localStorage.getItem(BEANS)).toBeNull();
		// Its first write must not cost this tab the good copy.
		c.upsertBean({ ...coerceBean({ id: 'new', name: 'New' })! });
		announce(BEANS);
		comeBack();
		expect(a.getBean('gesha'), 'this tab kept the good copy').not.toBeNull();
		a.updateBean('kenya', { rating: 5 });
		expect(storedBean('gesha'), 'and its next write puts it back').toBeDefined();
	});

	it('a removed key leaves this tab its copy, which may be the good one', () => {
		const { b } = twoTabs();
		b.setActiveBean('gesha');
		localStorage.removeItem(BEANS);
		announce(BEANS);
		localStorage.removeItem('crema.beans.activeBeanId.v1');
		announce('crema.beans.activeBeanId.v1');
		expect(b.getBean('gesha')).not.toBeNull();
		expect(b.activeBeanId, 'null is a valid id, but a removal is not a choice').toBe('gesha');
		// A later write, from a tab that may have started empty, isn't taken.
		otherTabWrites(BEANS, { schemaVersion: 1, beans: [], roasters: [] });
		expect(b.getBean('gesha')).not.toBeNull();
	});

	it("another reader of a key doesn't hide a missed write from the recheck (#380 review S3)", () => {
		const { b } = twoTabs();
		const env = JSON.parse(localStorage.getItem(BEANS)!) as { beans: { id: string; remaining: number }[] };
		env.beans.find((x) => x.id === 'gesha')!.remaining = 214;
		localStorage.setItem(BEANS, JSON.stringify(env));
		// A backup or a sync read of the key, before the tab is shown again.
		readStored(BEANS);
		comeBack();
		expect(b.getBean('gesha')?.remaining).toBe(214);
	});

	it("a key this tab couldn't read at load isn't taken either", () => {
		twoTabs();
		const realGet = localStorage.getItem.bind(localStorage);
		const spy = vi.spyOn(localStorage, 'getItem').mockImplementation((k: string) => {
			if (k === BEANS) throw new DOMException('denied', 'SecurityError');
			return realGet(k);
		});
		const blocked = new BeanLibraryStore();
		spy.mockRestore();
		// Its banner's Try again reads the store; until then it shows nothing.
		otherTabWrites(BEANS, JSON.parse(localStorage.getItem(BEANS)!));
		expect(blocked.beans).toEqual([]);
	});
});

describe('the other stores', () => {
	it('maintenance counters add up across tabs', () => {
		const a = new MaintenanceStore();
		const b = new MaintenanceStore();
		a.addCountedMl(500);
		announce('crema.maintenance.v2');
		b.addCountedMl(100);
		expect(b.current.totalLitres).toBeCloseTo(0.6);
		expect(
			(JSON.parse(localStorage.getItem('crema.maintenance.v2')!) as { totalLitres: number }).totalLitres
		).toBeCloseTo(0.6);
	});

	it("settings are taken, and pushed as a whole bundle, so the core enforces what's shown (#380 review S2)", () => {
		const a = new SettingsStore();
		const b = new SettingsStore();
		const push = vi.fn();
		b.onReplaced = push;
		a.set('theme', 'light');
		announce('crema.settings.v1');
		expect(b.current.theme).toBe('light');
		expect(push).toHaveBeenCalledOnce();
	});

	it('a settings adopt mid-shot is pushed once the shot ends, as the profile push is', () => {
		resetTabSafetyForTest();
		const a = new SettingsStore();
		const b = new SettingsStore();
		let pushes = 0;
		b.onReplaced = () => (pushes += 1);
		shotStarted();
		a.set('stopOnWeight', !a.current.stopOnWeight);
		announce('crema.settings.v1');
		a.set('theme', 'light');
		announce('crema.settings.v1');
		expect(pushes, 'no push to the core while a shot runs').toBe(0);
		expect(b.current.theme, 'memory follows at once').toBe('light');
		shotFinished();
		expect(pushes, 'once, after the shot').toBe(1);
		resetTabSafetyForTest();
	});

	it('custom methods and recipes, with their defaults and hidden list', () => {
		const methods = new CustomMethodStore();
		const recipes = new RecipeStore();
		otherTabWrites('crema.brewMethods.custom.v1', [
			{ id: 'm1', label: 'Siphon', createdAt: 1, deletedAt: null }
		]);
		otherTabWrites('crema.brewRecipes.v1', [{ id: 'r1', method: 'pourover', name: 'Mine', deletedAt: null }]);
		otherTabWrites('crema.brewRecipes.lastUsed.v1', { pourover: 'r1' });
		otherTabWrites('crema.brewRecipes.hiddenBuiltins.v1', ['b1']);
		expect(methods.get('m1')).toBeDefined();
		expect(recipes.get('r1')).toBeDefined();
		expect(recipes.defaultFor('pourover')?.id).toBe('r1');
		expect(recipes.isHidden('b1')).toBe(true);
	});

	it('profiles, pins and the hidden list are taken; the active profile stays this tab’s', () => {
		const b = new ProfileStore();
		otherTabWrites('crema.profiles.custom.v1', [{ id: 'c1', name: 'Mine' }]);
		otherTabWrites('crema.profiles.builtinOverrides.v1', { x: { pinned: true, lastUsed: null } });
		otherTabWrites('crema.profiles.hiddenBuiltins.v1', ['b1']);
		otherTabWrites('crema.profiles.activeId.v1', 'c1');
		expect(b.get('c1')?.name).toBe('Mine');
		expect((b as unknown as { overrides: Record<string, unknown> }).overrides).toHaveProperty('x');
		expect(b.isHidden('b1')).toBe(true);
		expect(b.activeId, "the machine this tab drives runs this tab's choice").toBeNull();
	});
});

describe('the active profile, changed in another tab (#380 review M2)', () => {
	const CUSTOM = 'crema.profiles.custom.v1';
	/** This tab, driving a machine on custom profile c1 at 36 g. */
	const thisTab = () => {
		localStorage.setItem(CUSTOM, JSON.stringify([{ id: 'c1', name: 'Mine', yieldOut: 36 }]));
		localStorage.setItem('crema.profiles.activeId.v1', JSON.stringify('c1'));
		resetStorageStateForTest();
		resetTabSafetyForTest();
		const store = new ProfileStore();
		store.loaded = true;
		const push = vi
			.spyOn(store as unknown as { pushActiveProfileToCore: () => Promise<void> }, 'pushActiveProfileToCore')
			.mockResolvedValue(undefined);
		const uploads: (CremaProfile | null)[] = [];
		store.onActivate = (p) => uploads.push(p);
		return { store, push, uploads };
	};

	it('an edit goes to the core and the machine, as a local save does', () => {
		const { store, push, uploads } = thisTab();
		otherTabWrites(CUSTOM, [{ id: 'c1', name: 'Mine', yieldOut: 18 }]);
		expect(store.get('c1')?.yieldOut).toBe(18);
		expect(push).toHaveBeenCalledOnce();
		expect(uploads.map((p) => p?.yieldOut)).toEqual([18]);
		expect(store.activeId, 'the active id stays this tab’s').toBe('c1');
	});

	it('a pin or a last-used stamp changes nothing on the machine', () => {
		const { push, uploads } = thisTab();
		otherTabWrites(CUSTOM, [
			{ id: 'c1', name: 'Mine', yieldOut: 36, pinned: true, lastUsed: '2026-11-08T10:00:00Z' }
		]);
		expect(push).not.toHaveBeenCalled();
		expect(uploads).toEqual([]);
	});

	it('an edit to another profile changes nothing on the machine', () => {
		const { push, uploads } = thisTab();
		otherTabWrites(CUSTOM, [
			{ id: 'c1', name: 'Mine', yieldOut: 36 },
			{ id: 'c2', name: 'Theirs', yieldOut: 40 }
		]);
		expect(push).not.toHaveBeenCalled();
		expect(uploads).toEqual([]);
	});

	it('mid-shot, it waits for the shot to end', () => {
		const { push, uploads } = thisTab();
		shotStarted();
		otherTabWrites(CUSTOM, [{ id: 'c1', name: 'Mine', yieldOut: 18 }]);
		otherTabWrites(CUSTOM, [{ id: 'c1', name: 'Mine', yieldOut: 20 }]);
		expect(push).not.toHaveBeenCalled();
		expect(uploads).toEqual([]);
		shotFinished();
		expect(push).toHaveBeenCalledOnce();
		expect(uploads.map((p) => p?.yieldOut), 'once, with the latest').toEqual([20]);
	});

	it('deleted there: no profile here, as a local delete of the active one', () => {
		const { store, uploads } = thisTab();
		otherTabWrites(CUSTOM, []);
		expect(store.activeId).toBeNull();
		expect(uploads).toEqual([null]);
	});
});

describe('history', () => {
	const curve: TimedSample[] = [
		{
			elapsed: 100,
			sample: {
				sampleTime: 10,
				groupPressure: 9,
				groupFlow: 2,
				headTemp: 92,
				mixTemp: 93,
				setMixTemp: 93,
				setHeadTemp: 92,
				setGroupPressure: 9,
				setGroupFlow: 0,
				frameNumber: 1,
				steamTemp: 140
			}
		}
	];
	const row = (id: string, at: number, samples: TimedSample[] = curve): StoredShot => ({
		formatVersion: 3,
		id,
		completedAt: at,
		profileName: 'P',
		profile: null,
		stopReason: null,
		metadata: {},
		record: { duration: 30_000, samples }
	});
	const bare = (s: StoredShot) => ({ ...s, record: { ...s.record, samples: [] } });
	const settle = (ms = 30) => new Promise((resolve) => setTimeout(resolve, ms));

	const realIdb = (globalThis as { indexedDB?: IDBFactory }).indexedDB;
	beforeAll(() => {
		(globalThis as { indexedDB?: IDBFactory }).indexedDB = new IDBFactory();
	});
	afterAll(() => {
		(globalThis as { indexedDB?: IDBFactory }).indexedDB = realIdb;
	});
	beforeEach(async () => {
		await clearAllSeries();
	});

	/** This tab, holding `ids` with their curves. */
	async function thisTab(...ids: string[]): Promise<HistoryStore> {
		const seed = new HistoryStore();
		ids.forEach((id, i) => seed.insertPulled(row(id, 1_700_000_000_000 + i * 1000)));
		await settle();
		resetStorageStateForTest();
		const store = new HistoryStore();
		await settle();
		return store;
	}

	it('a shot another tab recorded shows here with its curve', async () => {
		const b = await thisTab('old');
		await putSeries('new', curve);
		otherTabWrites(HISTORY, [bare(row('new', 1_800_000_000_000)), bare(row('old', 1_700_000_000_000))]);
		expect(b.get('old')?.record.samples, 'a row shown here keeps its curve at once').toHaveLength(1);
		await settle();
		expect(b.get('new')?.record.samples).toHaveLength(1);
		b.setRating('old', 4);
		expect((JSON.parse(localStorage.getItem(HISTORY)!) as StoredShot[]).map((s) => s.id)).toEqual([
			'new',
			'old'
		]);
	});

	it("a curve the other tab writes after its row is found on a second look", async () => {
		const b = await thisTab('old');
		otherTabWrites(HISTORY, [bare(row('new', 1_800_000_000_000)), bare(row('old', 1_700_000_000_000))]);
		await settle();
		await putSeries('new', curve);
		await settle(1_100);
		expect(b.get('new')?.record.samples).toHaveLength(1);
	});

	it("a stale tab's rows cost this tab no curve", async () => {
		const b = await thisTab('x', 'y');
		// The other tab's blob lacks x: it hasn't heard of it, or removed it.
		otherTabWrites(HISTORY, [bare(row('y', 1_700_000_001_000))]);
		b.setRating('y', 3);
		await settle();
		expect([...(await getAllSeries()).keys()].sort()).toEqual(['x', 'y']);
	});

	it('a pin made in another tab shows here, and this tab\u2019s next edit keeps it (#376)', async () => {
		const b = await thisTab('x', 'y');
		otherTabWrites(HISTORY, [
			{ ...bare(row('y', 1_700_000_001_000)), pinned: true },
			bare(row('x', 1_700_000_000_000))
		]);
		expect(b.get('y')?.pinned).toBe(true);
		b.setRating('x', 4);
		const stored = JSON.parse(localStorage.getItem(HISTORY)!) as StoredShot[];
		expect(stored.find((s) => s.id === 'y')?.pinned).toBe(true);
	});

	it('a tab that quarantined and started empty never leads this one (#380 review M1)', async () => {
		const a = await thisTab('x', 'y');
		localStorage.setItem(HISTORY, '[{"id":');
		announce(HISTORY);
		const c = new HistoryStore();
		await settle();
		announce(HISTORY);
		c.insertPulled(row('z', 1_800_000_000_000));
		announce(HISTORY);
		expect(a.all.map((s) => s.id), 'this tab keeps its rows').toEqual(['y', 'x']);
		a.setRating('x', 3);
		const stored = (JSON.parse(localStorage.getItem(HISTORY)!) as StoredShot[]).map((s) => s.id);
		expect(stored, 'and its next write puts them back').toEqual(['y', 'x']);
	});

	it('a failed look for a curve is tried again (#380 review S1)', async () => {
		const b = await thisTab('old');
		await putSeries('new', curve);
		const idb = (globalThis as { indexedDB: IDBFactory }).indexedDB;
		const open = idb.open.bind(idb);
		idb.open = () => {
			throw new DOMException('busy', 'UnknownError');
		};
		otherTabWrites(HISTORY, [bare(row('new', 1_800_000_000_000))]);
		await settle();
		expect(b.seriesPending('new'), 'still being looked for').toBe(true);
		idb.open = open;
		await settle(1_100);
		expect(b.get('new')?.record.samples).toHaveLength(1);
		expect(b.seriesPending('new')).toBe(false);
	});

	it("a pulled curve never replaces the one another tab stored (#380 review S1)", async () => {
		const b = await thisTab('old');
		otherTabWrites(HISTORY, [bare(row('new', 1_800_000_000_000))]);
		await settle();
		// The other tab's curve lands after this tab's first look.
		const own = curve.map((t) => ({ ...t, sample: { ...t.sample, groupPressure: 9 } }));
		await putSeries('new', own);
		const pulled = curve.map((t) => ({ ...t, sample: { ...t.sample, groupPressure: 1 } }));
		b.applyRemote('new', { backfillSamples: true } as never, { samples: pulled, durationMs: 30_000 });
		await settle();
		expect((await getAllSeries()).get('new')?.[0].sample.groupPressure, 'the device curve').toBe(9);
		expect(b.get('new')?.record.samples[0].sample.groupPressure, 'here too').toBe(9);
		b.backfillTelemetry('new', pulled, 30_000);
		expect(b.get('new')?.record.samples[0].sample.groupPressure).toBe(9);
	});

	it('a backfill waits while an adopted row may still get its curve', async () => {
		const b = await thisTab('old');
		otherTabWrites(HISTORY, [bare(row('new', 1_800_000_000_000))]);
		await settle();
		expect(b.seriesPending('new')).toBe(true);
		b.backfillTelemetry('new', curve, 30_000);
		expect(b.get('new')?.record.samples, 'not taken while pending').toHaveLength(0);
	});

	it('nothing is taken while a restore here owns the rows', async () => {
		const b = await thisTab('mine');
		const resume = b.pauseDeletes();
		otherTabWrites(HISTORY, [bare(row('theirs', 1_800_000_000_000))]);
		expect(b.get('mine')).toBeDefined();
		expect(b.get('theirs')).toBeUndefined();
		resume();
	});
});
