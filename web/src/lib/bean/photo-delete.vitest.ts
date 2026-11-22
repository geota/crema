/**
 * Deleting a bag deletes its photo blob (save-boundaries review F9, as
 * Android does): before, the blob stayed in IndexedDB for good.
 */

import 'fake-indexeddb/auto';
import { beforeEach, describe, expect, it } from 'vitest';
import { blankBean, blankRoaster, getBeanImageStore, getBeanStore, beanImageRefFor, type Bean } from './index';

const library = getBeanStore();
const images = getBeanImageStore();

async function bagWithPhoto(id: string, ref = beanImageRefFor(id), roasterId: string | null = null): Promise<Bean> {
	await images.put(ref, new Blob(['jpeg']));
	const bean: Bean = { ...blankBean(id), name: id, imageRef: ref, roasterId };
	library.upsertBean(bean);
	return bean;
}

/** The store's delete is fire-and-forget: let it land. */
async function settled(): Promise<void> {
	for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 5));
}

beforeEach(async () => {
	localStorage.clear();
	library.clearAll();
	for (const k of await images.keys()) await images.delete(k);
});

describe('deleting a bag deletes its photo', () => {
	it('the blob goes with the bag', async () => {
		const bean = await bagWithPhoto('bean:a');
		library.deleteBean(bean.id);
		await settled();
		expect(await images.get(bean.imageRef!)).toBeNull();
	});

	it('a photo another bag still shows stays', async () => {
		const ref = beanImageRefFor('bean:a');
		await bagWithPhoto('bean:a', ref);
		await bagWithPhoto('bean:b', ref);
		library.deleteBean('bean:a');
		await settled();
		expect(await images.get(ref)).not.toBeNull();
	});

	it('deleting a roaster with its bags deletes their photos', async () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		const bean = await bagWithPhoto('bean:a', beanImageRefFor('bean:a'), 'roaster:x');
		const other = await bagWithPhoto('bean:b');
		library.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		await settled();
		expect(await images.get(bean.imageRef!)).toBeNull();
		expect(await images.get(other.imageRef!)).not.toBeNull();
	});

	// REVIEW-497 P5.
	it('a bag whose photo is already gone deletes without an error', async () => {
		const bean = await bagWithPhoto('bean:a');
		await images.delete(bean.imageRef!);
		expect(() => library.deleteBean(bean.id)).not.toThrow();
		await settled();
		expect(library.getBean(bean.id)).toBeNull();
	});

	it("deleting a roaster's bags keeps a photo a remaining bag shares", async () => {
		library.upsertRoaster({ ...blankRoaster('X'), id: 'roaster:x' });
		const ref = beanImageRefFor('bean:a');
		await bagWithPhoto('bean:a', ref, 'roaster:x');
		await bagWithPhoto('bean:b', ref);
		library.deleteRoasterAndBeans('roaster:x', { localOnly: true });
		await settled();
		expect(library.getBean('bean:a')).toBeNull();
		expect(await images.get(ref)).not.toBeNull();
	});
});
