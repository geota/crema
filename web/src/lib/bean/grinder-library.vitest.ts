/**
 * Grinder records and bag grinds through every web library path (#488, the
 * #494 data-loss review): save → reload → backup → restore, the library
 * export and import, an older backup, and the migration's guards. Each test
 * here fails with its fix removed.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { blankBean, getBeanStore, type Bean } from '#lib/bean/index.js';
import { BeanLibraryStore, runGrinderMigration } from './store.svelte';
import { getHistoryStore } from '#lib/history/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { buildBackupJsonl, webSingletons } from '#lib/backup/index.js';
import { planNow, prepareBackup, readRecords, runRestore, type RestoreDeps, type WebMode } from '#lib/restore/engine.js';
import { resetTabSafetyForTest } from '#lib/restore/tab-safety.js';
import { mergeBeanImport } from './import-merge';
import { exportCremaJsonl, importCremaJsonl } from '#lib/wasm/de1_wasm.js';
import type { Grinder } from './grinder';

const LIBRARY_KEY = 'crema.beans.v1';
const library = getBeanStore();

function deps(): RestoreDeps {
	return { singletons: webSingletons().sink, locks: undefined, machineWaitMs: 200, progressMs: 5 };
}

/** Restore `jsonl` as the user confirming the plan they were shown. */
async function restore(jsonl: string, mode: WebMode) {
	const prepared = prepareBackup(jsonl, 'b');
	const expected = await planNow(prepared, mode, false, undefined);
	return runRestore(prepared, mode, { expected }, deps());
}

function resetAll(): void {
	library.clearAll();
	getHistoryStore().clearAllShots();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	getRecipeStore().applyBackup([], null, true);
	getCustomMethodStore().applyBackup([], true);
	localStorage.clear();
}

/** An owned Niche Zero with an edited scale, and a bag with a starred grind for it. */
function seed(): { grinder: Grinder; bag: Bean } {
	const added = library.addGrinder('', 'niche-zero') ?? library.addGrinder('Niche Zero')!;
	const grinder = library.saveGrinderEdit(added, {
		...added,
		scale: { min: 0, max: 60, step: 0.5, unit: 'clicks' }
	})!;
	library.setActiveGrinder(grinder.id);
	library.upsertBean({
		...blankBean('bean:guji'),
		name: 'Guji',
		grinds: [{ grinderId: grinder.id, grinderName: grinder.name, grind: '14.5', starred: true }]
	});
	return { grinder, bag: library.getBean('bean:guji')! };
}

/** The library as a fresh tab reads it from storage. */
const reloaded = () => new BeanLibraryStore();

beforeEach(() => {
	resetAll();
	resetTabSafetyForTest();
});

describe('grinders and bag grinds survive the library paths', () => {
	it('save → reload keeps the grinder, its scale, the pointer and the starred grind', () => {
		const { grinder } = seed();
		const again = reloaded();
		expect(again.getGrinder(grinder.id)).toEqual(grinder);
		expect(again.activeGrinder?.id).toBe(grinder.id);
		expect(again.getBean('bean:guji')!.grinds).toEqual([
			{ grinderId: grinder.id, grinderName: grinder.name, grind: '14.5', starred: true }
		]);
	});

	it('backup → Replace restore on an emptied device brings them back', async () => {
		const { grinder, bag } = seed();
		const built = (await buildBackupJsonl())!;
		expect(built.jsonl).toContain('"kind":"grinder"');
		resetAll();
		const out = await restore(built.jsonl, 'wipe');
		expect(out.kind).toBe('done');
		expect(library.getGrinder(grinder.id)).toEqual(grinder);
		expect(library.getBean(bag.id)!.grinds).toEqual(bag.grinds);
	});

	it('backup → Merge restore onto a used device adds the grinder by id', async () => {
		const { grinder, bag } = seed();
		const built = (await buildBackupJsonl())!;
		resetAll();
		// A used device: its own grinder and bag.
		const mine = library.addGrinder('', 'df64') ?? library.addGrinder('DF64')!;
		library.upsertBean({ ...blankBean('bean:mine'), name: 'Mine' });
		const out = await restore(built.jsonl, 'merge');
		expect(out.kind).toBe('done');
		expect(library.getGrinder(grinder.id)?.scale).toEqual(grinder.scale);
		expect(library.getGrinder(mine.id)).not.toBeNull();
		expect(library.getBean(bag.id)!.grinds).toEqual(bag.grinds);
	});

	it('the restore reads a grinder line (it threw on an unknown record before)', async () => {
		seed();
		const built = (await buildBackupJsonl())!;
		const records = await readRecords(prepareBackup(built.jsonl, 'b'));
		expect(records.grinders.map((g) => g.name)).toHaveLength(1);
	});

	it('an archived grinder stays archived through reload and restore, with no re-migration', async () => {
		const { grinder } = seed();
		getSettingsStore().set('grinderModel', 'Niche Zero');
		library.archiveGrinder(grinder.id);
		expect(library.activeGrinder, 'archiving the active grinder clears it').toBeNull();
		const again = reloaded();
		runGrinderMigration(again);
		expect(again.grinders).toEqual([]);
		expect(again.allGrinders).toHaveLength(1);
		expect(again.getGrinder(grinder.id)?.archivedAt).not.toBeNull();
		const built = (await buildBackupJsonl())!;
		resetAll();
		getSettingsStore().set('grinderModel', 'Niche Zero');
		expect((await restore(built.jsonl, 'wipe')).kind).toBe('done');
		expect(library.grinders, 'no grinder re-created from the settings name').toEqual([]);
		expect(library.getGrinder(grinder.id)?.archivedAt).not.toBeNull();
	});

	it('a Replace from an older app (no grinds on its bag row) keeps the device\'s grinds', async () => {
		const { bag } = seed();
		// An older app's backup of the same bag: no `grinds`, newer stamp.
		const old = { ...bag, updatedAt: bag.updatedAt + 1000 } as Partial<Bean>;
		delete old.grinds;
		const jsonl = exportCremaJsonl(JSON.stringify({ beans: [old], roasters: [], shots: [] }), Date.now(), '0.0.4');
		const built = (await buildBackupJsonl())!;
		const header = built.jsonl.split('\n')[0];
		const oldBackup = [header.replace(/"grinderCount":\d+,?/, '').replace(/"beanCount":\d+/, '"beanCount":1'), ...jsonl.split('\n').slice(1)]
			.filter(Boolean)
			.join('\n');
		const out = await restore(oldBackup, 'wipe');
		expect(out.kind, JSON.stringify(out)).toBe('done');
		expect(library.getBean(bag.id)!.grinds).toEqual(bag.grinds);
	});

	it('the bean-library export → import carries grinders and the starred grind', () => {
		const { grinder, bag } = seed();
		const jsonl = exportCremaJsonl(
			JSON.stringify({ beans: library.beans, roasters: [], grinders: library.allGrinders, shots: [] }),
			Date.now(),
			'test'
		);
		const plan = JSON.parse(importCremaJsonl(jsonl)) as { beans: Bean[]; grinders: Grinder[] };
		resetAll();
		const merge = mergeBeanImport(plan.beans, [], library.beans, library.roasters, plan.grinders, library.allGrinders);
		library.putImportedGrinders([...merge.newGrinders, ...merge.updatedGrinders] as Grinder[]);
		library.bulkAdd(merge.newBeans as Bean[], []);
		expect(library.getGrinder(grinder.id)?.scale).toEqual(grinder.scale);
		expect(library.getBean(bag.id)!.grinds).toEqual(bag.grinds);
	});
});

describe('the library migration runs only over a loaded library', () => {
	function legacyLibrary(): void {
		const bag = { ...blankBean('bean:old'), name: 'Old', grinder: 'Niche', grinderSetting: '12', grinds: undefined };
		localStorage.setItem(LIBRARY_KEY, JSON.stringify({ schemaVersion: 1, beans: [bag], roasters: [] }));
	}

	it('migrates a loaded library: a grinder per name, the old grind as a starred saved grind', () => {
		legacyLibrary();
		const lib = reloaded();
		expect(lib.migrateGrinders('Niche Zero')).toBe(true);
		expect(lib.grinders).toHaveLength(1);
		expect(lib.activeGrinder?.id).toBe(lib.grinders[0].id);
		const g = lib.getBean('bean:old')!;
		expect(g.grinds).toEqual([{ grinderId: lib.grinders[0].id, grinderName: lib.grinders[0].name, grind: '12', starred: true }]);
		expect(g.grinderSetting, 'the old field stays').toBe('12');
		expect(lib.migrateGrinders('Niche Zero'), 'idempotent').toBe(false);
	});

	it('never migrates a library that could not be read', () => {
		legacyLibrary();
		const lib = reloaded();
		// As readStore reports a store that threw on read.
		Object.defineProperty(lib, 'libraryRead', { value: 'unavailable' });
		expect(lib.migrateGrinders('Niche Zero')).toBe(false);
		expect(lib.allGrinders).toEqual([]);
	});

	it('waits while the settings could not be read', () => {
		legacyLibrary();
		const lib = reloaded();
		expect(lib.migrateGrinders('', false)).toBe(false);
		expect(lib.allGrinders).toEqual([]);
		expect(lib.getBean('bean:old')!.grinds ?? null).toBeNull();
	});

	it('with absent settings, migrates only bags that name their own grinder (PLAN follow-up 40)', () => {
		const own = { ...blankBean('bean:own'), name: 'Own', grinder: 'Niche', grinderSetting: '12', grinds: undefined };
		const none = { ...blankBean('bean:none'), name: 'None', grinder: '', grinderSetting: '14', grinds: undefined };
		localStorage.setItem(LIBRARY_KEY, JSON.stringify({ schemaVersion: 1, beans: [own, none], roasters: [] }));
		const lib = reloaded();
		expect(lib.migrateGrinders('', true, true)).toBe(true);
		expect(lib.getBean('bean:own')!.grinds).toHaveLength(1);
		expect(lib.getBean('bean:none')!.grinds ?? null, 'waits for the settings, not filed under "no grinder"').toBeNull();
		expect(lib.activeGrinder).toBeNull();
	});
});

describe('adding a grinder with none active (#499 review)', () => {
	it('a created, catalogue or restored grinder becomes the active one', () => {
		expect(library.activeGrinder).toBeNull();
		const g = library.takeGrinderChoice({ kind: 'create', name: "Dad's Zassenhaus" })!;
		expect(library.activeGrinder?.id).toBe(g.id);
		// With one active, another addition leaves it as it is.
		const ek = library.takeGrinderChoice({ kind: 'catalogue', id: 'mahlkonig-ek43', name: 'Mahlkönig EK43' });
		expect(ek).not.toBeNull();
		expect(library.activeGrinder?.id).toBe(g.id);
		// Archiving the active clears it; restoring one makes it active again.
		library.archiveGrinder(g.id);
		expect(library.activeGrinder).toBeNull();
		library.takeGrinderChoice({ kind: 'archived', id: g.id, name: g.name });
		expect(library.activeGrinder?.id).toBe(g.id);
	});

	it('picking an owned grinder in the bag editor does not switch Brew', () => {
		const g = library.addGrinder('Niche Zero')!;
		library.takeGrinderChoice({ kind: 'owned', id: g.id, name: g.name });
		expect(library.activeGrinder).toBeNull();
	});
});
