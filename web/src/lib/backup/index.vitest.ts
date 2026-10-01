import { describe, it, expect, beforeEach } from 'vitest';
import { getBeanStore, blankBean, blankRoaster } from '$lib/bean';
import { getHistoryStore, type StoredShot } from '$lib/history';
import { getProfileStore } from '$lib/profiles';
import { blankProfile } from '$lib/profiles/model';
import { getSettingsStore } from '$lib/settings';
import { getRecipeStore } from '$lib/brew/recipes.svelte';
import { getCustomMethodStore } from '$lib/brew/custom-methods.svelte';
import { methodLabel } from '$lib/brew/methods';
import { BrewMethodStyle } from '$lib/core/crema-core';
import { buildBackupJsonl, restoreBackup } from './index';

/**
 * Round-trip tests for the whole-app backup/restore (review #07). The runes
 * store singletons + the wasm `exportBackupJsonl` both work under jsdom, so this
 * drives the real `buildBackupJsonl` → `restoreBackup` path — no mocks. Backup is
 * data-loss-adjacent; a line-parser or merge/wipe dedup regression would silently
 * drop or duplicate records, which these pin.
 */

function makeShot(id: string): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		profileName: 'Test Profile',
		profile: null,
		stopReason: null,
		metadata: {
			dose: 18,
			yieldOut: 36,
			beans: null,
			grinderSetting: null,
			notes: 'tasty',
			rating: 4,
			tds: null,
			extractionYield: null
		},
		record: { duration: 30_000, samples: [] },
		// A frozen bean snapshot — the shell-side enrichment that routing restore
		// through the core's library importer (ImportedShot) would have dropped.
		// Asserting it survives the round-trip guards F1 (review #06).
		bean: {
			beanId: 'bean-1',
			name: 'Ethiopia',
			roasterName: 'Test Roaster',
			roastedOn: '2026-01-01',
			roastLevel: 4,
			tags: ['fruity'],
			grinderSetting: '2.5'
		}
	};
}

function resetAll(): void {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	getRecipeStore().applyBackup([], null, true);
	getCustomMethodStore().applyBackup([], true);
	localStorage.clear();
}

/** Seed one of each data type; returns the ids so a restore can be asserted. */
function seed(): { beanId: string; roasterId: string; shotId: string; profileId: string } {
	const bean = { ...blankBean('bean-1'), name: 'Ethiopia', roasterId: 'roaster-1' };
	const roaster = blankRoaster('Test Roaster', 'roaster-1');
	getBeanStore().bulkAdd([bean], [roaster]);
	const shot = makeShot('shot:round-trip-1');
	getHistoryStore().insertPulled(shot);
	const profile = { ...blankProfile(), name: 'My Custom' };
	getProfileStore().save(profile);
	return { beanId: bean.id, roasterId: roaster.id, shotId: shot.id, profileId: profile.id };
}

describe('backup round-trip (review #07)', () => {
	beforeEach(resetAll);

	it('round-trips beans, roasters, shots + profiles through a wipe restore', () => {
		const ids = seed();
		const built = buildBackupJsonl();
		expect(built).not.toBeNull();

		resetAll(); // simulate restoring onto a fresh device
		const summary = restoreBackup(built!.jsonl, 'wipe');

		expect(summary).toMatchObject({ beans: 1, roasters: 1, shots: 1, profiles: 1 });
		expect(getBeanStore().beans.map((b) => b.id)).toContain(ids.beanId);
		expect(getBeanStore().roasters.map((r) => r.id)).toContain(ids.roasterId);
		expect(getHistoryStore().get(ids.shotId)?.id).toBe(ids.shotId);
		expect(getProfileStore().get(ids.profileId)?.id).toBe(ids.profileId);

		// The shot's frozen bean snapshot must survive restore (F1, review #06) —
		// it would be lost if restore routed through the library importer's
		// ImportedShot wrapper instead of the full StoredShot.
		const restoredBean = getHistoryStore().get(ids.shotId)?.bean;
		expect(restoredBean?.name).toBe('Ethiopia');
		expect(restoredBean?.beanId).toBe('bean-1');
		expect(restoredBean?.roasterName).toBe('Test Roaster');
		expect(restoredBean?.roastLevel).toBe(4);
	});

	it('merge adds new records but preserves existing ones', () => {
		seed();
		const built = buildBackupJsonl()!;

		resetAll();
		getBeanStore().bulkAdd([{ ...blankBean('bean-2'), name: 'Kenya' }], []); // a different bag
		const summary = restoreBackup(built.jsonl, 'merge');

		expect(summary.beans).toBe(1); // bean-1 added; bean-2 untouched
		expect(getBeanStore().beans.map((b) => b.id).sort()).toEqual(['bean-1', 'bean-2']);
	});

	it('merge skips records whose ids are already present (idempotent re-restore)', () => {
		seed();
		const built = buildBackupJsonl()!;

		// Restore the same bundle without clearing — every id already exists.
		const summary = restoreBackup(built.jsonl, 'merge');

		expect(summary).toMatchObject({ beans: 0, roasters: 0, shots: 0, profiles: 0 });
		expect(getBeanStore().beans.length).toBe(1);
		expect(getHistoryStore().all.length).toBe(1);
	});

	it('skips a malformed JSONL line without dropping the rest', () => {
		seed();
		const built = buildBackupJsonl()!;

		resetAll();
		const corrupted = built.jsonl.replace('\n', '\n{ this is not valid json \n');
		expect(() => restoreBackup(corrupted, 'wipe')).not.toThrow();
		expect(getBeanStore().beans.length).toBe(1);
		expect(getHistoryStore().all.length).toBe(1);
	});

	it('throws on a file without the crema-backup header', () => {
		expect(() => restoreBackup('{"kind":"something-else"}\n', 'merge')).toThrow(
			/not a crema backup/i
		);
	});

	it('backs up user recipe copies with their credit, never the built-ins', () => {
		const recipes = getRecipeStore();
		const dup = recipes.duplicate('builtin:hoffmann-ultimate-v60')!;
		const copy = recipes.saveEdit({ ...dup, notes: 'Cafec Abaca filter\nTWW light roast' });
		recipes.hide('builtin:kasuya-4-6');
		recipes.touch(copy);
		const built = buildBackupJsonl()!;
		expect(built.jsonl).toContain('"kind":"recipe"');
		expect(built.jsonl).toContain('"kind":"recipeMeta"');
		// Only the copy rides — the catalogue ships with the app.
		expect(built.jsonl.match(/"kind":"recipe"/g)).toHaveLength(1);
		expect(built.jsonl).not.toMatch(/"kind":"recipe","id":"builtin:/);

		resetAll();
		expect(getRecipeStore().backupRecipes()).toHaveLength(0);
		const summary = restoreBackup(built.jsonl, 'wipe');
		expect(summary.recipes).toBe(1);
		const restored = getRecipeStore().get(copy.id)!;
		expect(restored.name).toBe('Ultimate V60 (copy)');
		expect(restored.credit).toBe(
			'Adapted from James Hoffmann — The Ultimate V60 Technique (2019)'
		);
		expect(restored.sourceUrl).toBe('https://www.youtube.com/watch?v=AI4ynXzkSQo');
		expect(restored.notes).toBe('Cafec Abaca filter\nTWW light roast');
		// recipeMeta: the default pointer + the hidden built-in come back too.
		expect(getRecipeStore().defaultFor('pourover')?.id).toBe(copy.id);
		expect(getRecipeStore().isHidden('builtin:kasuya-4-6')).toBe(true);
	});

	it('round-trips custom methods (tombstones too) and the brew label snapshot', () => {
		const methods = getCustomMethodStore();
		const draft = {
			style: BrewMethodStyle.Percolation,
			icon: null,
			seedDoseG: null,
			seedWaterG: null,
			seedTempC: null
		};
		const orb = methods.create({ ...draft, label: 'ORB' })!;
		const gone = methods.create({ ...draft, label: 'Old brewer' })!;
		methods.remove(gone.id);
		const history = getHistoryStore();
		const shot = history.addManualBrew({
			method: gone.id,
			methodLabel: 'Old brewer',
			completedAt: 1_700_000_000_000,
			bean: null,
			dose: 15,
			waterG: 250,
			yieldOut: null,
			grinderSetting: null,
			brewTempC: null,
			durationMs: null,
			rating: null,
			notes: null,
			nextPlan: null
		});
		const recipe = getRecipeStore().saveEdit(
			getCustomMethodStore().blankRecipe(orb.id, 'recipe:orb')!
		);
		const built = buildBackupJsonl()!;
		expect(built.jsonl.match(/"kind":"brewMethod"/g)).toHaveLength(2);
		expect(built.jsonl).toContain('"brewMethodLabel":"Old brewer"');

		resetAll();
		expect(methods.backupMethods()).toHaveLength(0);
		// With the method list gone, the row falls back to its snapshot.
		const summary = restoreBackup(built.jsonl, 'wipe');
		expect(summary.customMethods).toBe(2);
		expect(getCustomMethodStore().live.map((m) => m.label)).toEqual(['ORB']);
		expect(getCustomMethodStore().get(gone.id)?.deletedAt).toBeTypeOf('number');
		expect(getRecipeStore().get(recipe.id)?.method).toBe(orb.id);
		const restored = getHistoryStore().get(shot.id)!;
		expect(restored.brewMethodLabel).toBe('Old brewer');
		expect(methodLabel(restored.brewMethod, restored.brewMethodLabel)).toBe('Old brewer');
		// A merge re-restore adds nothing.
		expect(restoreBackup(built.jsonl, 'merge').customMethods).toBe(0);
	});
});
