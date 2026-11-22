/**
 * A bag deleted on this device stays deleted (#488 review S5): a bag that is
 * on Visualizer leaves a hidden tombstone, so the next pull doesn't add it
 * back without its Crema-only data. Any other bag goes outright.
 */

import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { blankBean, getBeanStore, type Bean } from './index';

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
		expect(tomb).toMatchObject({ id: 'bean:a', visualizerId: 'b1', imageRef: null });
		expect(tomb.deletedAt).not.toBeNull();
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
