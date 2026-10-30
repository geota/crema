/**
 * A page reload must hand back every field a shot was saved with. The loader
 * (`coerceStoredShot`) rebuilds each row field by field, so a field it forgets
 * is silently dropped on the next load: `privacy`, `decentId`, `machine` and
 * the bean snapshot's `grinder` were (core/shell review 2026-10-30, §2.3-1).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ShotBean, ShotMetadata, StoredShot } from './model';
import type { ShotMachine as CoreShotMachine } from '#lib/core/crema-core.js';

vi.mock('./series-store', () => ({
	putSeries: async () => {},
	getAllSeries: async () => new Map(),
	deleteSeries: async () => {},
	pruneSeriesTo: async () => {},
	clearAllSeries: async () => {}
}));

import { HistoryStore } from './store.svelte';

function makeShot(id: string, extra: Partial<StoredShot> = {}): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		profileName: 'Test Profile',
		metadata: {},
		record: { duration: 30_000, samples: [] },
		...extra
	};
}

/** What the next page load sees: a fresh store over the same localStorage. */
function reload(): HistoryStore {
	return new HistoryStore();
}

describe('HistoryStore reload', () => {
	beforeEach(() => {
		localStorage.clear();
	});

	it('AC-F1.1 a reloaded private shot stays private', () => {
		const store = new HistoryStore();
		store.insertPulled(makeShot('shot:private'));
		store.setPrivacy('shot:private', 'private');
		expect(reload().get('shot:private')?.privacy).toBe('private');
	});

	it('AC-F1.2 decentId, machine and bean grinder survive a reload', () => {
		const store = new HistoryStore();
		store.insertPulled(
			makeShot('shot:decent', {
				bean: { name: 'Gesha', grinder: 'Niche Zero' },
				machine: { serialNumber: '12345', firmwareVersion: '1352', model: 'DE1PRO' }
			})
		);
		store.bindDecentId('shot:decent', 'dec-789');
		const shot = reload().get('shot:decent');
		expect(shot?.decentId).toBe('dec-789');
		expect(shot?.machine).toStrictEqual({
			serialNumber: '12345',
			firmwareVersion: '1352',
			model: 'DE1PRO'
		});
		expect(shot?.bean?.grinder).toBe('Niche Zero');
	});

	it('AC-F1.3 a fully populated shot round-trips through persist and reload unchanged', () => {
		// `Required<…>` makes this fixture fail to compile when a field is added
		// to the stored shape, so a new field can't skip this test (or the loader).
		const metadata: Required<ShotMetadata> = {
			dose: 18,
			yieldOut: 36.4,
			beans: 'Roaster · Gesha',
			grinderSetting: '2.5',
			notes: 'bright',
			nextPlan: 'go finer',
			rating: 4,
			tds: 9.8,
			extractionYield: 20.1,
			waterG: 250,
			barista: 'Sam',
			drinker: 'Alex'
		};
		const bean: Required<ShotBean> = {
			beanId: 'bean:1',
			name: 'Gesha',
			roasterName: 'Roaster',
			roastedOn: '2026-10-01',
			roastLevel: 3,
			tags: ['washed'],
			notes: 'jasmine',
			grinderSetting: '2.5',
			grinder: 'Niche Zero'
		};
		const machine: Required<CoreShotMachine> = {
			serialNumber: '12345',
			firmwareVersion: '1352',
			model: 'DE1PRO'
		};
		const full: Required<StoredShot> = {
			formatVersion: 3,
			id: 'shot:full',
			completedAt: 1_700_000_000_000,
			profileName: 'Test Profile',
			profile: { title: 'Test Profile', steps: [] },
			stopReason: { kind: 'weight' },
			metadata,
			// Persisted rows are stripped of samples (they live in IndexedDB).
			record: { duration: 30_000, samples: [] },
			bean,
			grinderModel: 'Niche Zero',
			tags: ['dial-in'],
			yieldTarget: 36,
			brewTempTarget: 93.5,
			preinfuseTarget: 8,
			stopOnWeight: true,
			autoTare: false,
			privacy: 'unlisted',
			visualizerId: 'vis-1',
			decentId: 'dec-1',
			machine,
			deletedAt: 1_700_000_100_000,
			brewMethod: 'custom:abc',
			brewMethodLabel: 'My method',
			recipeName: 'Hoffmann V60',
			brewSeries: {
				samples: [{ elapsedMs: 1000, weightG: 12.5, flowGS: 2 }],
				stageMarks: [{ elapsedMs: 0, stepIndex: 0 }]
			}
		};
		new HistoryStore().insertPulled(full);
		expect(reload().get('shot:full')).toStrictEqual(full);
	});

	it('normalises legacy and malformed values on reload', () => {
		localStorage.setItem(
			'crema.history.v2',
			JSON.stringify([
				makeShot('shot:legacy', {
					privacy: 'secret' as never,
					decentId: '',
					machine: { serialNumber: '12345', firmwareVersion: null, model: null }
				}),
				makeShot('shot:noserial', { machine: { model: 'DE1' } as never })
			])
		);
		const store = reload();
		const legacy = store.get('shot:legacy');
		expect(legacy?.privacy).toBeUndefined();
		expect(legacy?.decentId).toBeUndefined();
		expect(legacy?.machine).toStrictEqual({ serialNumber: '12345' });
		expect(store.get('shot:noserial')?.machine).toBeUndefined();
	});
});
