/**
 * Load outcomes on the real stores: a value that can't be read is never taken
 * for an empty store, and a row a loader can't coerce survives the next save.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BeanLibraryStore } from '#lib/bean/store.svelte.js';
import { CustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { RecipeStore } from '#lib/brew/recipes.svelte.js';
import { HistoryStore } from '#lib/history/store.svelte.js';
import { MaintenanceStore } from '#lib/maintenance/store.svelte.js';
import { ProfileStore } from '#lib/profiles/store.svelte.js';
import { SettingsStore } from '#lib/settings/store.svelte.js';
import { keptAsideBundle, refusedMessage } from '#lib/shell/storage-notices.js';
import {
	corruptKeyOf,
	keepSkippedRows,
	onRefusedStoresChange,
	readJson,
	readStore,
	readStored,
	refusedStores,
	resetStorageStateForTest,
	skippedKeyOf,
	storageNoticeMessage,
	storeHealth,
	takeStorageNotices,
	writeJson,
	writeStore
} from './storage';

const KEY = 'crema.test.v1';
const WHAT = 'test log';

/** Make reading `key` throw, as a storage failure does. */
function unreadable(...keys: string[]): void {
	const real = localStorage.getItem.bind(localStorage);
	vi.spyOn(localStorage, 'getItem').mockImplementation((k: string) => {
		if (keys.includes(k)) throw new DOMException('read failed', 'UnknownError');
		return real(k);
	});
}

function fillUp(): void {
	vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
		throw new DOMException('full', 'QuotaExceededError');
	});
}

beforeEach(() => {
	localStorage.clear();
	resetStorageStateForTest();
	vi.spyOn(console, 'warn').mockImplementation(() => {});
});
afterEach(() => vi.restoreAllMocks());

describe('readStored', () => {
	it('a throw from storage is unavailable, not absent', () => {
		localStorage.setItem(KEY, '[1]');
		unreadable(KEY);
		expect(readStored(KEY).kind).toBe('unavailable');
	});

	it('only a parse error is corrupt: running out of memory says nothing of the value', () => {
		localStorage.setItem(KEY, '[1]');
		vi.spyOn(JSON, 'parse').mockImplementationOnce(() => {
			throw new RangeError('Invalid string length');
		});
		expect(readStored(KEY)).toEqual({ kind: 'unavailable', error: 'Invalid string length' });
	});
});

describe('readStore', () => {
	it('reports each outcome and the health that goes with it', () => {
		expect(storeHealth(KEY)).toBe('loading');
		expect(readStore(KEY, { what: WHAT })).toEqual({ kind: 'absent' });
		expect(storeHealth(KEY)).toBe('absent');

		localStorage.setItem(KEY, '[1]');
		expect(readStore(KEY, { what: WHAT })).toEqual({ kind: 'loaded', value: [1] });
		expect(storeHealth(KEY)).toBe('ok');

		localStorage.setItem(KEY, '[1');
		expect(readStore(KEY, { what: WHAT })).toEqual({ kind: 'corrupt', kept: true });
		expect(storeHealth(KEY)).toBe('damaged');
		expect(localStorage.getItem(corruptKeyOf(KEY))).toBe('[1');
	});

	it('a value of the wrong shape is corrupt', () => {
		localStorage.setItem(KEY, '{"a":1}');
		expect(readStore(KEY, { what: WHAT, valid: Array.isArray })).toEqual({
			kind: 'corrupt',
			kept: true
		});
		expect(storeHealth(KEY)).toBe('damaged');
	});

	it('an unreadable value stays in place, unquarantined, and saves are refused', () => {
		localStorage.setItem(KEY, '[1]');
		unreadable(KEY);
		expect(readStore(KEY, { what: WHAT })).toMatchObject({ kind: 'unavailable' });
		vi.restoreAllMocks();

		expect(storeHealth(KEY)).toBe('unavailable');
		expect(writeStore(KEY, [])).toBe('blocked');
		writeJson(KEY, []);
		expect(localStorage.getItem(KEY)).toBe('[1]');
		expect(localStorage.getItem(corruptKeyOf(KEY))).toBeNull();
		expect(refusedStores()).toEqual([{ key: KEY, what: WHAT }]);
	});

	it('a read that works lifts the refusal, which is what Try again relies on', () => {
		localStorage.setItem(KEY, '[1]');
		unreadable(KEY);
		readStore(KEY, { what: WHAT });
		vi.restoreAllMocks();
		const heard = vi.fn();
		onRefusedStoresChange(heard);

		expect(readStore(KEY, { what: WHAT })).toEqual({ kind: 'loaded', value: [1] });
		expect(refusedStores()).toEqual([]);
		expect(heard).toHaveBeenCalledOnce();
		expect(writeStore(KEY, [])).toBe('ok');
	});

	it('a damaged value that could not be kept aside is refused too', () => {
		localStorage.setItem(KEY, '{broken');
		fillUp();
		expect(readStore(KEY, { what: WHAT })).toEqual({ kind: 'corrupt', kept: false });
		expect(refusedStores()).toEqual([{ key: KEY, what: WHAT }]);
	});
});

describe('readJson', () => {
	it('a named store it cannot read is refused too', () => {
		localStorage.setItem(KEY, '[1]');
		unreadable(KEY);
		expect(readJson(KEY, [], { what: 'upload queue' })).toEqual([]);
		vi.restoreAllMocks();
		expect(writeStore(KEY, [])).toBe('blocked');
		expect(localStorage.getItem(KEY)).toBe('[1]');
	});

	it('a cache it cannot read is not', () => {
		localStorage.setItem(KEY, '[1]');
		unreadable(KEY);
		expect(readJson(KEY, [])).toEqual([]);
		vi.restoreAllMocks();
		expect(writeStore(KEY, [])).toBe('ok');
	});
});

describe('writeStore', () => {
	it('tells quota from any other failure', () => {
		const spy = vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('full', 'QuotaExceededError');
		});
		expect(writeStore(KEY, 1, false)).toBe('quota');
		spy.mockImplementation(() => {
			throw new DOMException('denied', 'SecurityError');
		});
		expect(writeStore(KEY, 1, false)).toBe('failed');
	});
});

describe('keepSkippedRows', () => {
	it('appends, keeps what is already there, and announces only new rows', () => {
		readStore(KEY, { what: WHAT }); // health exists
		localStorage.setItem(KEY, '[]');
		readStore(KEY, { what: WHAT });

		expect(keepSkippedRows(KEY, [{ n: 1 }], WHAT)).toBe(true);
		expect(keepSkippedRows(KEY, [{ n: 1 }, { n: 2 }], WHAT)).toBe(true);
		expect(keepSkippedRows(KEY, [{ n: 1 }, { n: 2 }], WHAT)).toBe(true);

		const kept = JSON.parse(localStorage.getItem(skippedKeyOf(KEY))!) as { row: unknown }[];
		expect(kept.map((k) => k.row)).toEqual([{ n: 1 }, { n: 2 }]);
		const notices = takeStorageNotices();
		expect(notices.map((n) => n.kind === 'skipped' && n.count)).toEqual([1, 1]);
		expect(storageNoticeMessage(notices[0])).toBe(
			'One item in your test log couldn’t be read and was kept aside.'
		);
		expect(storeHealth(KEY)).toBe('damaged');
	});

	it('does not lose a kept list it cannot parse', () => {
		localStorage.setItem(skippedKeyOf(KEY), '[{oops');
		keepSkippedRows(KEY, [{ n: 1 }], WHAT);
		const kept = JSON.parse(localStorage.getItem(skippedKeyOf(KEY))!) as { row: unknown }[];
		expect(kept.map((k) => k.row)).toEqual(['[{oops', { n: 1 }]);
	});

	it('blocks the key when the rows cannot be kept, so no save drops them', () => {
		localStorage.setItem(KEY, '[{"bad":true}]');
		fillUp();
		expect(keepSkippedRows(KEY, [{ bad: true }], WHAT)).toBe(false);
		vi.restoreAllMocks();
		expect(writeStore(KEY, [])).toBe('blocked');
		expect(localStorage.getItem(KEY)).toBe('[{"bad":true}]');
		expect(refusedStores()).toEqual([{ key: KEY, what: WHAT }]);
	});

	it('goes in the file the user can save', () => {
		keepSkippedRows(KEY, [{ n: 1 }], WHAT);
		const bundle = JSON.parse(keptAsideBundle([KEY])) as Record<string, string | null>;
		expect(JSON.parse(bundle[skippedKeyOf(KEY)]!)).toMatchObject([{ row: { n: 1 } }]);
	});
});

describe('the banner', () => {
	it('names every store', () => {
		expect(refusedMessage([{ key: 'a', what: 'shot history' }])).toBe(
			'Couldn’t read your shot history, so changes aren’t being saved.'
		);
		expect(
			refusedMessage([
				{ key: 'a', what: 'shot history' },
				{ key: 'b', what: 'settings' },
				{ key: 'c', what: 'bean library' }
			])
		).toBe('Couldn’t read your shot history, settings and bean library, so changes aren’t being saved.');
	});
});

/** Each of the 11 keys that hold user data: load it unreadable, then save. */
const STORES: readonly {
	key: string;
	what: string;
	raw: string;
	loadAndSave: () => void;
}[] = [
	{
		key: 'crema.settings.v1',
		what: 'settings',
		raw: '{"common":{}}',
		loadAndSave: () => new SettingsStore().migrateHotWater()
	},
	{
		key: 'crema.beans.v1',
		what: 'bean library',
		raw: '{"schemaVersion":1,"beans":[],"roasters":[]}',
		loadAndSave: () => new BeanLibraryStore().clearAll()
	},
	{
		key: 'crema.history.v2',
		what: 'shot history',
		raw: '[]',
		loadAndSave: () => new HistoryStore().clearAllShots()
	},
	{
		key: 'crema.profiles.custom.v1',
		what: 'custom profiles',
		raw: '[]',
		loadAndSave: () => new ProfileStore().clearAllCustom()
	},
	{
		key: 'crema.profiles.builtinOverrides.v1',
		what: 'profile settings',
		raw: '{}',
		loadAndSave: () => writeJson('crema.profiles.builtinOverrides.v1', { after: 'load' })
	},
	{
		key: 'crema.profiles.hiddenBuiltins.v1',
		what: 'hidden profiles list',
		raw: '[]',
		loadAndSave: () => writeJson('crema.profiles.hiddenBuiltins.v1', ['after-load'])
	},
	{
		key: 'crema.brewRecipes.v1',
		what: 'brew recipes',
		raw: '[]',
		loadAndSave: () => writeJson('crema.brewRecipes.v1', [{ id: 'after-load' }])
	},
	{
		key: 'crema.brewRecipes.lastUsed.v1',
		what: 'recipe defaults',
		raw: '{}',
		loadAndSave: () => writeJson('crema.brewRecipes.lastUsed.v1', { after: 'load' })
	},
	{
		key: 'crema.brewRecipes.hiddenBuiltins.v1',
		what: 'hidden recipes list',
		raw: '[]',
		loadAndSave: () => writeJson('crema.brewRecipes.hiddenBuiltins.v1', ['after-load'])
	},
	{
		key: 'crema.brewMethods.custom.v1',
		what: 'custom brew methods',
		raw: '[]',
		loadAndSave: () => new CustomMethodStore().applyBackup([], true)
	},
	{
		key: 'crema.maintenance.v2',
		what: 'maintenance log',
		raw: '{"totalLitres":12}',
		loadAndSave: () => new MaintenanceStore().addCountedMl(5000)
	}
];

describe('every store', () => {
	it.each(STORES)('$what: unreadable means refused, never overwritten', ({ key, what, raw, loadAndSave }) => {
		localStorage.setItem(key, raw);
		unreadable(key);
		// Every store sharing the key's class loads here; only `key` is unreadable.
		new ProfileStore();
		new RecipeStore();
		loadAndSave();
		vi.restoreAllMocks();

		expect(localStorage.getItem(key)).toBe(raw);
		expect(localStorage.getItem(corruptKeyOf(key))).toBeNull();
		expect(storeHealth(key)).toBe('unavailable');
		expect(refusedStores()).toEqual([{ key, what }]);
	});
});

describe('rows a loader cannot coerce', () => {
	const HISTORY = 'crema.history.v2';
	const LIBRARY = 'crema.beans.v1';
	const good = {
		formatVersion: 3,
		id: 'shot-1',
		completedAt: 1_700_000_000_000,
		profileName: 'Test',
		profile: null,
		stopReason: null,
		metadata: {},
		record: { duration: 1000, samples: [] }
	};
	const badRow = { formatVersion: 3, noId: true };

	it('a shot survives the save that drops it from the rows', () => {
		localStorage.setItem(HISTORY, JSON.stringify([good, badRow]));
		const store = new HistoryStore();
		expect(store.all.map((s) => s.id)).toEqual(['shot-1']);

		store.delete('shot-1');
		const kept = JSON.parse(localStorage.getItem(skippedKeyOf(HISTORY))!) as { row: unknown }[];
		expect(kept.map((k) => k.row)).toEqual([badRow]);
		expect(takeStorageNotices().map((n) => n.kind)).toEqual(['skipped']);
		expect(storeHealth(HISTORY)).toBe('damaged');
	});

	it('a shot that cannot be kept stops the save instead', () => {
		const raw = JSON.stringify([good, badRow]);
		localStorage.setItem(HISTORY, raw);
		const real = localStorage.setItem.bind(localStorage);
		vi.spyOn(localStorage, 'setItem').mockImplementation((k: string, v: string) => {
			if (k === skippedKeyOf(HISTORY)) throw new DOMException('full', 'QuotaExceededError');
			real(k, v);
		});
		new HistoryStore().delete('shot-1');
		expect(localStorage.getItem(HISTORY)).toBe(raw);
	});

	it('a bean and a roaster survive the save that drops them', () => {
		const bean = { id: 'bean:1', name: 'Kept' };
		const badBean = { name: 'no id' };
		const badRoaster = { name: 'no id either' };
		localStorage.setItem(
			LIBRARY,
			JSON.stringify({ schemaVersion: 1, beans: [bean, badBean], roasters: [badRoaster] })
		);
		const store = new BeanLibraryStore();
		expect(store.beans.map((b) => b.id)).toEqual(['bean:1']);

		store.clearAll();
		const kept = JSON.parse(localStorage.getItem(skippedKeyOf(LIBRARY))!) as { row: unknown }[];
		expect(kept.map((k) => k.row)).toEqual([badBean, badRoaster]);
	});

	it('a duplicate is not damage', () => {
		const bean = { id: 'bean:1', name: 'Kept' };
		localStorage.setItem(LIBRARY, JSON.stringify({ schemaVersion: 1, beans: [bean, bean], roasters: [] }));
		expect(new BeanLibraryStore().beans).toHaveLength(1);
		expect(localStorage.getItem(skippedKeyOf(LIBRARY))).toBeNull();
	});
});

describe('history at the quota', () => {
	const HISTORY = 'crema.history.v2';
	const rows = (n: number) =>
		Array.from({ length: n }, (_, i) => ({
			formatVersion: 3,
			id: `shot-${i}`,
			completedAt: 1_700_000_000_000 - i,
			profileName: 'Test',
			profile: null,
			stopReason: null,
			metadata: {},
			record: { duration: 1000, samples: [] }
		}));

	it('a refused key never evicts: memory keeps every row and nothing is claimed lost', () => {
		localStorage.setItem(HISTORY, JSON.stringify(rows(20)));
		const raw = localStorage.getItem(HISTORY);
		const store = new HistoryStore();
		// The key turns unreadable on a second load in this tab, as Try again might.
		unreadable(HISTORY);
		readStore(HISTORY, { what: 'shot history' });
		vi.restoreAllMocks();

		store.setRating(store.all[0].id, 3);
		expect(store.all).toHaveLength(20);
		expect(localStorage.getItem(HISTORY)).toBe(raw);
	});

	it('a failure that is not quota never evicts either', () => {
		localStorage.setItem(HISTORY, JSON.stringify(rows(20)));
		const store = new HistoryStore();
		vi.spyOn(localStorage, 'setItem').mockImplementation(() => {
			throw new DOMException('denied', 'SecurityError');
		});
		store.setRating(store.all[0].id, 3);
		expect(store.all).toHaveLength(20);
	});

	it('quota still evicts, to keep saving', () => {
		localStorage.setItem(HISTORY, JSON.stringify(rows(20)));
		const store = new HistoryStore();
		const real = localStorage.setItem.bind(localStorage);
		let failures = 1;
		vi.spyOn(localStorage, 'setItem').mockImplementation((k: string, v: string) => {
			if (failures-- > 0) throw new DOMException('full', 'QuotaExceededError');
			real(k, v);
		});
		store.setRating(store.all[0].id, 3);
		expect(store.all).toHaveLength(18);
	});
});
