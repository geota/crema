/**
 * #310 R1w: `backup/cross-shell.vitest.ts`'s cases, with the history in
 * `history.db` behind the seam. That file stays as it is and runs on the P3
 * store; this one runs its fixtures through the SQLite source: a backup from
 * either shell restores, and the web fixture comes out of the walk byte for
 * byte.
 */
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getBeanStore, blankBean, blankRoaster } from '#lib/bean/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { blankProfile } from '#lib/profiles/model.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { BrewMethodStyle } from '#lib/core/crema-core.js';
import { HistoryDb } from '#lib/history/history-db.js';
import { freshStorage } from '#lib/history/idb-test-kit.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { StoredShot } from '#lib/history/model.js';
import { summarize } from '#lib/history/summary.js';
import { buildBackupJsonl, restoreBackup, type RestoreSummary } from '#lib/backup/index.js';
import { setHistorySource } from './source';
import { SqliteSource } from './sqlite';
import { idbDetails } from './details';
import { directPort, freshFile, historyWasmBuilt, makeTruth, openTruth, rowsOf } from './sqlite-kit';
import { sqliteOfP3 } from './cross-kit';

const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/backup');
const fixture = (name: string): string => readFileSync(resolve(FIXTURES, name), 'utf8');

const realIdb = (globalThis as { indexedDB?: unknown }).indexedDB;
beforeAll(async () => {
	await initTestWasm();
});
afterAll(() => {
	(globalThis as { indexedDB?: unknown }).indexedDB = realIdb;
});

function resetAll(): void {
	getBeanStore().clearAll();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	getRecipeStore().applyBackup([], null, true);
	getCustomMethodStore().applyBackup([], true);
	localStorage.clear();
}

/** An empty `history.db` behind the seam, its details in a `crema-history` the P3 store made. */
async function emptySqlite() {
	await getHistoryStore().whenReady();
	// Stage 2 finds `crema-history` there: its `details` stay where P3 put them.
	const made = new HistoryDb({ expectExisting: () => false });
	await made.open();
	made.close();
	const file = freshFile();
	await makeTruth(file);
	const port = directPort(await openTruth(file));
	const details = idbDetails(new HistoryDb({ expectExisting: () => true }));
	const source = new SqliteSource({ db: port, details, summarize });
	setHistorySource(source);
	return { port, details };
}

function counts({ outcome }: RestoreSummary) {
	if (outcome.kind !== 'done') throw new Error(`restore ${outcome.kind} ${JSON.stringify(outcome).slice(0, 300)}`);
	const p = outcome.plan;
	return {
		beans: p.beans.add.length,
		roasters: p.roasters.add.length,
		shots: p.shots.add.length,
		profiles: p.profiles.add.length,
		recipes: p.recipes.add.length,
		customMethods: p.customMethods.add.length,
		settingsApplied: outcome.settingsApplied
	};
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

/** `cross-shell.vitest.ts`'s seed, behind `web-backup.crema.jsonl`. */
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
		},
		bagDebit: 12.5
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

describe.runIf(historyWasmBuilt)('cross-shell backup restore on history.db (F11a, #310 R1w)', () => {
	beforeEach(() => {
		freshStorage();
		resetAll();
	});
	afterEach(() => {
		setHistorySource(null);
		vi.useRealTimers();
	});

	it('writes the web fixture’s backup byte for byte from the walk', async () => {
		vi.useFakeTimers({ toFake: ['Date'] });
		vi.setSystemTime(1_790_000_000_000);
		seed();
		await getHistoryStore().flushed();
		const p3 = (await buildBackupJsonl('fixture'))!.jsonl;
		const { source } = await sqliteOfP3();
		setHistorySource(source);
		const walked = (await buildBackupJsonl('fixture'))!.jsonl;
		expect(walked).toBe(p3);
		// The shot line is the fixture's: the seed's profiles and recipes take new ids each run.
		const shotLine = (jsonl: string) => jsonl.split('\n').find((l) => l.includes('"kind":"shot"'));
		expect(shotLine(walked)).toBe(shotLine(fixture('web-backup.crema.jsonl')));
	});

	it('restores an Android backup', async () => {
		const { port, details } = await emptySqlite();
		const summary = counts(await restoreBackup(fixture('android-backup.crema.jsonl'), 'wipe'));
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

		const row = (await rowsOf(port)).get('shot:android-1') as StoredShot & { summary: { sampleCount: number } };
		expect(row.metadata.dose).toBe(18);
		expect(row.metadata.yieldOut).toBe(36);
		expect(row.metadata.notes).toBe('from Android');
		expect(row.bean?.name).toBe('Halo Hambela');
		expect(row.bean?.beanId).toBe('bean:android-1');
		expect(row.brewTempTarget).toBe(93);
		expect(row.bagDebit).toBe(10.5);
		expect(row.summary.sampleCount).toBe(2);
		const d = (await details.get(['shot:android-1'])).get('shot:android-1')!;
		expect(d.samples).toHaveLength(2);
		expect(d.samples[1].sample.groupPressure).toBe(9);
		// Nothing went to the P3 store's rows.
		expect(getHistoryStore().get('shot:android-1')).toBeUndefined();

		expect(getProfileStore().get('profile:android-fixture')?.name).toBe('Android Fixture Profile');
		expect(getCustomMethodStore().get('custom:android-orb')?.label).toBe('ORB');
		expect(getRecipeStore().get('recipe:android-copy')?.name).toBe('Android V60');
		expect(getSettingsStore().current.tempUnit).toBe('F');
		expect(getSettingsStore().current.maxShotDurationS).toBe(90);
	});

	it('restores its own web backup fixture', async () => {
		const { port } = await emptySqlite();
		const summary = counts(await restoreBackup(fixture('web-backup.crema.jsonl'), 'wipe'));
		expect(summary).toMatchObject({ beans: 1, roasters: 1, shots: 1, profiles: 1, recipes: 1 });
		const row = (await rowsOf(port)).get('shot:web-1') as StoredShot;
		expect(row.bean?.name).toBe('Gesha Village');
		expect(row.bagDebit).toBe(12.5);
		expect(getSettingsStore().current.tempUnit).toBe('F');
	});
});
