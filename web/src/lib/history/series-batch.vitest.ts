/** A restore writes its series in batches of 25 on one connection (restore-safety §2). */
import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import type { TimedSample } from '#lib/core/index.js';
import { getAllSeries, putManySeries } from './series-store';

const realIdb = (globalThis as { indexedDB?: IDBFactory }).indexedDB;
beforeAll(() => {
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = new IDBFactory();
});
afterAll(() => {
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = realIdb;
});

it('writes 60 series in 3 transactions, all of them stored', async () => {
	const rows = Array.from({ length: 60 }, (_, i) => ({
		id: `s${i}`,
		samples: [{ elapsed: i, sample: {} }] as unknown as TimedSample[]
	}));
	// Opened once, so its transactions can be counted.
	await putManySeries([]);
	const open = indexedDB.open.bind(indexedDB);
	let transactions = 0;
	const spy = vi.spyOn(indexedDB, 'open').mockImplementation((...args: Parameters<IDBFactory['open']>) => {
		const req = open(...args);
		req.addEventListener('success', () => {
			const db = req.result;
			const tx = db.transaction.bind(db);
			db.transaction = ((...a: Parameters<IDBDatabase['transaction']>) => {
				transactions++;
				return tx(...a);
			}) as IDBDatabase['transaction'];
		});
		return req;
	});
	try {
		await putManySeries(rows);
	} finally {
		spy.mockRestore();
	}
	expect(transactions).toBe(3);
	expect((await getAllSeries()).size).toBe(60);
});
