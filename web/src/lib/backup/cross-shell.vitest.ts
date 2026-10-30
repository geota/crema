import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getBeanStore, blankBean, blankRoaster } from '#lib/bean/index.js';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { blankProfile } from '#lib/profiles/model.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { BrewMethodStyle } from '#lib/core/crema-core.js';
import { buildBackupJsonl, restoreBackup } from './index';

/**
 * F11a: a backup from either shell restores on web. The fixtures live in
 * `core/de1-domain/tests/fixtures/backup/`: `android-backup.crema.jsonl` was
 * written by Android's `backupBundleJsonl` (`BackupCrossShellTest.kt`) and
 * `web-backup.crema.jsonl` by `buildBackupJsonl` from {@link seed} below.
 * They are committed artifacts, so an old backup keeps restoring as both
 * shells change. Regenerate the web one with
 * `CREMA_REGEN_BACKUP_FIXTURES=1 pnpm vitest run src/lib/backup/cross-shell.vitest.ts`.
 */

const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const fixture = (name: string): string => readFileSync(resolve(FIXTURES, name), 'utf8');

function resetAll(): void {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	getRecipeStore().applyBackup([], null, true);
	getCustomMethodStore().applyBackup([], true);
	localStorage.clear();
}

function sample(elapsed: number, groupPressure: number): StoredShot['record']['samples'][number] {
	return {
		elapsed,
		sample: {
			sampleTime: 0,
			groupPressure,
			groupFlow: 2,
			headTemp: 92,
			mixTemp: 90,
			setMixTemp: 93,
			setHeadTemp: 93,
			setGroupPressure: 9,
			setGroupFlow: 0,
			frameNumber: 1,
			steamTemp: 0
		},
		scaleWeight: 10
	} as StoredShot['record']['samples'][number];
}

/** The web seed behind `web-backup.crema.jsonl`. */
function seed(): void {
	const roaster = blankRoaster('Sey', 'roaster:web-1');
	const bean = {
		...blankBean('bean:web-1'),
		name: 'Gesha Village',
		roasterId: roaster.id,
		roastedOn: '2026-09-01',
		frozenOn: '2026-09-05',
		defrostedOn: '2026-09-20',
		tags: ['comp'],
		bagSize: 250,
		remaining: 180
	};
	getBeanStore().bulkAdd([bean], [roaster]);
	getHistoryStore().insertPulled({
		formatVersion: 3,
		id: 'shot:web-1',
		completedAt: 1_790_000_000_000,
		profileName: 'My Web Profile',
		profile: null,
		stopReason: null,
		metadata: {
			dose: 18,
			yieldOut: 36,
			beans: null,
			grinderSetting: '2.5',
			notes: 'from web',
			rating: 4,
			tds: null,
			extractionYield: null
		},
		record: { duration: 28_000, samples: [sample(0, 2), sample(1_000, 9)] },
		bean: {
			beanId: bean.id,
			name: bean.name,
			roasterName: 'Sey',
			roastedOn: '2026-09-01',
			roastLevel: 4,
			tags: ['comp'],
			grinderSetting: '2.5'
		}
	});
	getProfileStore().save({ ...blankProfile(), name: 'My Web Profile' });
	getSettingsStore().set('tempUnit', 'F');
	getSettingsStore().set('maxShotDurationS', 90);
	getCustomMethodStore().create({
		label: 'ORB',
		style: BrewMethodStyle.Percolation,
		icon: null,
		seedDoseG: null,
		seedWaterG: null,
		seedTempC: null
	});
	const recipes = getRecipeStore();
	recipes.saveEdit(recipes.duplicate('builtin:hoffmann-ultimate-v60')!);
}

describe('cross-shell backup restore (F11a)', () => {
	beforeEach(async () => {
		await initTestWasm();
		resetAll();
	});
	afterEach(() => {
		vi.useRealTimers();
	});

	it.runIf(process.env.CREMA_REGEN_BACKUP_FIXTURES === '1')('regenerates the web fixture', () => {
		vi.useFakeTimers({ toFake: ['Date'] });
		vi.setSystemTime(1_790_000_000_000);
		seed();
		writeFileSync(resolve(FIXTURES, 'web-backup.crema.jsonl'), buildBackupJsonl('fixture')!.jsonl);
	});

	it('restores an Android backup', () => {
		const summary = restoreBackup(fixture('android-backup.crema.jsonl'), 'wipe');
		expect(summary).toMatchObject({
			beans: 1,
			roasters: 1,
			shots: 1,
			profiles: 1,
			recipes: 1,
			customMethods: 1,
			settingsApplied: true
		});

		const bean = getBeanStore().getBean('bean:android-1')!;
		expect(bean.name).toBe('Halo Hambela');
		expect(bean.roasterId).toBe('roaster:android-1');
		expect([bean.frozenOn, bean.defrostedOn]).toEqual(['2026-09-05', '2026-09-20']);
		expect(bean.tags).toEqual(['comp']);
		expect(getBeanStore().getRoaster('roaster:android-1')?.name).toBe('Onyx');

		const shot = getHistoryStore().get('shot:android-1')!;
		expect(shot.metadata.dose).toBe(18);
		expect(shot.metadata.yieldOut).toBe(36);
		expect(shot.metadata.notes).toBe('from Android');
		expect(shot.bean?.name).toBe('Halo Hambela');
		expect(shot.bean?.beanId).toBe('bean:android-1');
		expect(shot.record.samples).toHaveLength(2);
		expect(shot.record.samples[1].sample.groupPressure).toBe(9);
		expect(shot.brewTempTarget).toBe(93);

		expect(getProfileStore().get('profile:android-fixture')?.name).toBe('Android Fixture Profile');
		expect(getCustomMethodStore().get('custom:android-orb')?.label).toBe('ORB');
		expect(getRecipeStore().get('recipe:android-copy')?.name).toBe('Android V60');
		expect(getSettingsStore().current.tempUnit).toBe('F');
		expect(getSettingsStore().current.maxShotDurationS).toBe(90);
	});

	it('restores its own web backup fixture', () => {
		const summary = restoreBackup(fixture('web-backup.crema.jsonl'), 'wipe');
		expect(summary).toMatchObject({ beans: 1, roasters: 1, shots: 1, profiles: 1, recipes: 1 });
		expect(getHistoryStore().get('shot:web-1')?.bean?.name).toBe('Gesha Village');
		expect(getSettingsStore().current.tempUnit).toBe('F');
	});
});
