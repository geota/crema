/**
 * A bag deleted on this device stays deleted (#488 review S5): a bag that is
 * on Visualizer leaves a hidden tombstone, so the next pull doesn't add it
 * back without its Crema-only data. Any other bag goes outright.
 */

import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { blankBean, blankRoaster, getBeanStore, type Bean } from './index';
import { BeanLibraryStore } from './store.svelte';
import { bagRoasterRemoteId } from './visualizer-sync';

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
		const plan = library.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		expect(plan?.remoteBeanIds).toEqual(['b1']);
		expect(library.beans).toEqual([]);
		expect(library.allBeans.map((b) => [b.id, b.deletedAt != null])).toEqual([['bean:a', true]]);
	});

	it('keeps a tombstone for a bag whose first upload is running (save-boundaries follow-up 57)', () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		bag('bean:a', { roasterId: 'roaster:x' });
		bag('bean:b', { roasterId: 'roaster:x' });
		library.beginFirstUpload('bean:a');
		library.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		expect(library.beans).toEqual([]);
		expect(library.allBeans.map((b) => [b.id, b.deletedAt != null])).toEqual([['bean:a', true]]);
		// The POST lands: the tombstone binds, so the next pull matches it by id.
		library.bindFirstUpload('bean:a', 'vb-1');
		library.endFirstUpload('bean:a');
		expect(library.allBeans[0]).toMatchObject({ id: 'bean:a', visualizerId: 'vb-1' });
	});

	it('leaves a bag deleted earlier as its tombstone', () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		bag('bean:old', { roasterId: 'roaster:x', visualizerId: 'b0' });
		library.deleteBean('bean:old');
		const before = library.allBeans[0];
		const plan = library.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		expect(plan?.remoteBeanIds).toEqual([]);
		expect(library.allBeans).toEqual([before]);
	});
});

describe('deleting a roaster on this device (#488 review SF1)', () => {
	function roaster(visualizerId: string | null): void {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x', visualizerId, website: 'x.coffee' });
	}

	it('keeps a hidden tombstone for a roaster on Visualizer, and its bags stay filed under it', () => {
		roaster('r1');
		const before = bag('bean:a', { roasterId: 'roaster:x' });
		const stamp = library.getBean('bean:a')!.updatedAt;
		const plan = library.deleteRoaster('roaster:x', { localOnly: true });
		expect(plan?.tombstoneRoaster).toBe(true);
		expect(library.roasters).toEqual([]);
		expect(library.getRoaster('roaster:x')).toBeNull();
		expect(library.findRoasterByName('X')).toBeNull();
		const [tomb] = library.allRoasters;
		expect(tomb).toMatchObject({ id: 'roaster:x', name: 'X', visualizerId: 'r1' });
		expect(tomb.deletedAt).not.toBeNull();
		expect(tomb.website).toBeFalsy();
		// Not detached: the push never sends the bag with no roaster.
		expect(library.getBean('bean:a')).toMatchObject({ roasterId: before.roasterId, updatedAt: stamp });
		// The bag's push still names the roaster's Visualizer id (core `bag_roaster_remote_id`).
		expect(bagRoasterRemoteId(library.getBean('bean:a')!, library.allRoasters)).toBe('r1');
		expect(bagRoasterRemoteId(library.getBean('bean:a')!, library.roasters), 'live rows only would unfile it').toBeNull();
	});

	it('with "also on Visualizer" keeps the tombstone and detaches the bags', () => {
		roaster('r1');
		bag('bean:a', { roasterId: 'roaster:x' });
		const plan = library.deleteRoaster('roaster:x', { localOnly: false });
		expect(plan?.remoteRoasterId).toBe('r1');
		expect(library.allRoasters.map((r) => [r.id, r.deletedAt != null])).toEqual([['roaster:x', true]]);
		expect(library.getBean('bean:a')?.roasterId).toBeNull();
	});

	it('removes a roaster never on Visualizer, detaching its bags', () => {
		roaster(null);
		bag('bean:a', { roasterId: 'roaster:x' });
		library.deleteRoaster('roaster:x', { localOnly: true });
		expect(library.allRoasters).toEqual([]);
		expect(library.getBean('bean:a')?.roasterId).toBeNull();
	});

	it('a cascade keeps the tombstone too', () => {
		roaster('r1');
		bag('bean:a', { roasterId: 'roaster:x' });
		library.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		expect(library.beans).toEqual([]);
		expect(library.allRoasters.map((r) => [r.id, r.deletedAt != null])).toEqual([['roaster:x', true]]);
	});

	it('a tombstone can\'t be deleted again', () => {
		roaster('r1');
		library.deleteRoaster('roaster:x', { localOnly: true });
		const first = library.allRoasters[0];
		expect(library.deleteRoaster('roaster:x', { localOnly: true })).toBeNull();
		expect(library.deleteRoasterAndBeans('roaster:x', { localOnly: true })).toBeNull();
		expect(library.allRoasters).toEqual([first]);
	});
});

describe('a delete in another tab during a first upload (save-boundaries follow-up 58)', () => {
	/** Another tab: its own store over the same localStorage, with no mark of its own. */
	function otherTab(): BeanLibraryStore {
		return new BeanLibraryStore();
	}

	it('keeps a tombstone when another tab is uploading the bag', () => {
		bag('bean:a');
		library.beginFirstUpload('bean:a');
		const other = otherTab();
		other.deleteBean('bean:a');
		expect(other.allBeans.map((b) => [b.id, b.deletedAt != null])).toEqual([['bean:a', true]]);
		library.endFirstUpload('bean:a');
		expect(localStorage.getItem('crema.beans.firstUploads.v1')).toBeNull();
	});

	it('the roaster cascade in another tab keeps one too', () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		bag('bean:a', { roasterId: 'roaster:x' });
		library.beginFirstUpload('bean:a');
		const other = otherTab();
		other.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		expect(other.allBeans.map((b) => [b.id, b.deletedAt != null])).toEqual([['bean:a', true]]);
		library.endFirstUpload('bean:a');
	});

	it('removes the bag once the upload ended, or when the mark is stale (a tab closed mid-POST)', () => {
		bag('bean:a');
		bag('bean:b');
		library.beginFirstUpload('bean:a');
		library.endFirstUpload('bean:a');
		otherTab().deleteBean('bean:a');
		expect(otherTab().allBeans.map((b) => b.id)).toEqual(['bean:b']);
		vi.useFakeTimers({ now: Date.now() });
		try {
			library.beginFirstUpload('bean:b');
			vi.setSystemTime(Date.now() + 11 * 60_000);
			const other = otherTab();
			other.deleteBean('bean:b');
			expect(other.allBeans).toEqual([]);
		} finally {
			library.endFirstUpload('bean:b');
			vi.useRealTimers();
		}
	});
});
