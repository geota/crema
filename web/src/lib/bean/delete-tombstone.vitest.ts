/**
 * A bag deleted on this device stays deleted (#488 review S5): a bag that is
 * on Visualizer leaves a hidden tombstone, so the next pull doesn't add it
 * back without its Crema-only data. Any other bag goes outright.
 */

import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { blankBean, blankRoaster, getBeanStore, type Bean } from './index';

const library = getBeanStore();

function bag(id: string, patch: Partial<Bean> = {}): Bean {
	const bean: Bean = { ...blankBean(id), name: id, ...patch };
	library.upsertBean(bean);
	return bean;
}

beforeEach(() => {
	localStorage.clear();
	library.clearAll();
});

describe('deleting a bag on this device', () => {
	it('keeps a hidden tombstone for a bag on Visualizer', () => {
		bag('bean:a', { visualizerId: 'b1', imageRef: 'bean-image:bean:a', tags: ['washed'] });
		library.setActiveBean('bean:a');
		library.deleteBean('bean:a');
		expect(library.beans.map((b) => b.id)).toEqual([]);
		expect(library.getBean('bean:a')).toBeNull();
		expect(library.activeBean).toBeNull();
		const [tomb] = library.allBeans;
		expect(tomb).toMatchObject({ id: 'bean:a', name: 'bean:a', visualizerId: 'b1', imageRef: null, tags: [] });
		expect(tomb.deletedAt).not.toBeNull();
	});

	it('keeps only what matching needs', () => {
		bag('bean:a', {
			visualizerId: 'b1',
			roasterId: 'roaster:x',
			roastedOn: '2026-11-01',
			notes: 'lovely',
			tastingNotes: 'jasmine',
			cost: 18,
			favourite: true,
			linkedProfileId: 'custom-a',
			remaining: 120
		});
		library.deleteBean('bean:a');
		const [tomb] = library.allBeans;
		expect(tomb).toMatchObject({ id: 'bean:a', visualizerId: 'b1', roasterId: 'roaster:x', roastedOn: '2026-11-01' });
		expect(tomb).toMatchObject({ notes: '', tastingNotes: '', cost: null, favourite: false, linkedProfileId: null, remaining: 0 });
	});

	it('removes a bag that never reached Visualizer', () => {
		bag('bean:a');
		library.deleteBean('bean:a');
		expect(library.allBeans).toEqual([]);
	});

	it('leaves a tombstone alone when asked to delete it again', () => {
		bag('bean:a', { visualizerId: 'b1' });
		library.deleteBean('bean:a');
		const first = library.allBeans[0].deletedAt;
		library.deleteBean('bean:a');
		expect(library.allBeans).toHaveLength(1);
		expect(library.allBeans[0].deletedAt).toBe(first);
	});
});

describe('deleting a roaster with its bags', () => {
	it('keeps a tombstone for each bag on Visualizer', () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		bag('bean:a', { roasterId: 'roaster:x', visualizerId: 'b1' });
		bag('bean:b', { roasterId: 'roaster:x' });
		const plan = library.deleteRoasterAndBeans('roaster:x');
		expect(plan?.remoteBeanIds).toEqual(['b1']);
		expect(library.beans).toEqual([]);
		expect(library.allBeans.map((b) => [b.id, b.deletedAt != null])).toEqual([['bean:a', true]]);
	});

	it('leaves a bag deleted earlier as its tombstone', () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		bag('bean:old', { roasterId: 'roaster:x', visualizerId: 'b0' });
		library.deleteBean('bean:old');
		const before = library.allBeans[0];
		const plan = library.deleteRoasterAndBeans('roaster:x');
		expect(plan?.remoteBeanIds).toEqual([]);
		expect(library.allBeans).toEqual([before]);
	});
});
