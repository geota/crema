/**
 * A shot records the active grinder at shot start (#488, review F4/F9): its
 * id and a snapshot of its name, not the bag's old grinder or the settings
 * grinder. After a kept shot, auto-save (when on) saves its grind to the bag.
 */
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import { getActiveShotStore } from '#lib/state/active-shot.svelte.js';
import type { ShotCaptureContext } from '#lib/core/crema-core.js';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import type { StoredShot } from './model';

vi.mock('#lib/bean/bag-empty-prompt.js', () => ({ promptBagEmpty: () => {} }));

const { autoSaveGrind, commitShotCompletion } = await import('./shot-persistence');
type Event = Parameters<typeof commitShotCompletion>[0];

beforeAll(async () => {
	await initTestWasm();
});

beforeEach(() => {
	getBeanStore().clearAll();
	getSettingsStore().reset();
	localStorage.clear();
});

describe('the shot records its grinder', () => {
	it('the live shot start freezes the active grinder, not the bag or settings grinder', async () => {
		const { loadCore } = await import('#lib/core/index.js');
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const core = await loadCore();
		core.reset();
		const app = new CremaApp(core, null);
		const library = getBeanStore();
		getSettingsStore().set('grinderModel', 'EK43');
		const motto = library.addGrinder('', 'bookoo-motto-80')!;
		library.setActiveGrinder(motto.id);
		library.upsertBean({ ...blankBean('bean:a'), name: 'A', grinder: 'Niche' });
		library.setActiveBean('bean:a');
		const frozen = (app as unknown as { buildLiveActiveShot(): { grinderId?: string | null; grinderModel: string | null } }).buildLiveActiveShot();
		expect(frozen.grinderId).toBe(motto.id);
		expect(frozen.grinderModel).toBe(motto.name);
		library.setActiveGrinder(null);
		const none = (app as unknown as { buildLiveActiveShot(): { grinderId?: string | null; grinderModel: string | null } }).buildLiveActiveShot();
		expect(none.grinderId ?? null).toBeNull();
		expect(none.grinderModel).toBeNull();
	});

	it('the completion passes the frozen id and name to the core', () => {
		getSettingsStore().set('grinderModel', 'EK43');
		getActiveShotStore().set({
			bean: null,
			profileName: null,
			dose: 18,
			brewParams: null,
			grinderModel: 'Niche Zero',
			grinderId: 'grinder:niche',
			source: 'live'
		});
		const takeStoredShot = vi.fn((_ctx: ShotCaptureContext) => null);
		commitShotCompletion({ content: { duration: 28_000, disposition: 'Record' } } as unknown as Event, {
			snapshot: { activeProfileName: null },
			shotStartedAtMs: null,
			lastNotificationAtMs: null,
			sliceJsonl: async () => '',
			fireWebhook: () => {},
			takeStoredShot,
			runtime: null
		} as unknown as Parameters<typeof commitShotCompletion>[1]);
		expect(takeStoredShot.mock.lastCall![0]).toMatchObject({ settingsGrinder: 'Niche Zero', grinderId: 'grinder:niche' });
	});
});

describe('auto-save after a kept shot', () => {
	function shotOn(grinderId: string, grinderModel: string, grind: string): StoredShot {
		return {
			id: 'shot:1',
			completedAt: 1,
			bean: { beanId: 'bean:a', name: 'A' },
			grinderId,
			grinderModel,
			metadata: { grinderSetting: grind }
		} as unknown as StoredShot;
	}

	it('off by default: nothing is written', () => {
		const library = getBeanStore();
		const g = library.addGrinder('Niche Zero')!;
		library.upsertBean({ ...blankBean('bean:a'), name: 'A' });
		autoSaveGrind(shotOn(g.id, g.name, '14'));
		expect(library.getBean('bean:a')!.grinds).toEqual([]);
	});

	it('on: the grind is saved for the shot\'s bag and grinder, the star untouched', () => {
		const library = getBeanStore();
		getSettingsStore().set('grindAutoSave', true);
		const g = library.addGrinder('Niche Zero')!;
		library.upsertBean({
			...blankBean('bean:a'),
			name: 'A',
			grinds: [{ grinderId: 'grinder:other', grinderName: 'Other', grind: '3', starred: true }]
		});
		autoSaveGrind(shotOn(g.id, g.name, '14'));
		expect(library.getBean('bean:a')!.grinds).toEqual([
			{ grinderId: 'grinder:other', grinderName: 'Other', grind: '3', starred: true },
			{ grinderId: g.id, grinderName: g.name, grind: '14', starred: false }
		]);
	});
});

describe('editing a shot\'s grinder in History (#499 review M2)', () => {
	it('re-points grinderId to the owned grinder of that name, or clears it', async () => {
		const { getHistoryStore } = await import('./store.svelte');
		const history = getHistoryStore();
		const library = getBeanStore();
		const niche = library.addGrinder('Niche Zero')!;
		const df = library.addGrinder('DF64')!;
		history.insertPulled({
			formatVersion: 3,
			id: 'shot:m2',
			completedAt: 1_700_000_000_000,
			profileName: 'x',
			profile: null,
			stopReason: null,
			metadata: { dose: 18, yieldOut: 36, grinderSetting: '14' },
			record: { duration: 30_000, samples: [] },
			grinderModel: niche.name,
			grinderId: niche.id
		} as unknown as StoredShot);
		history.setGrinderModel('shot:m2', ' df64 ');
		expect(history.get('shot:m2')?.grinderId).toBe(df.id);
		history.setGrinderModel('shot:m2', 'Some other grinder');
		expect(history.get('shot:m2')?.grinderId ?? null, 'no match: no id').toBeNull();
		expect(history.get('shot:m2')?.grinderModel).toBe('Some other grinder');
	});
});
