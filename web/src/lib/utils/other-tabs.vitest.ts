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
import { resetStorageStateForTest } from './storage';

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

	it('settings are taken into memory, and nothing is pushed from it', () => {
		const a = new SettingsStore();
		const b = new SettingsStore();
		const push = vi.fn();
		b.onReplaced = push;
		a.set('theme', 'light');
		announce('crema.settings.v1');
		expect(b.current.theme).toBe('light');
		expect(push).not.toHaveBeenCalled();
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

	it('nothing is taken while a restore here owns the rows', async () => {
		const b = await thisTab('mine');
		const resume = b.pauseDeletes();
		otherTabWrites(HISTORY, [bare(row('theirs', 1_800_000_000_000))]);
		expect(b.get('mine')).toBeDefined();
		expect(b.get('theirs')).toBeUndefined();
		resume();
	});
});
