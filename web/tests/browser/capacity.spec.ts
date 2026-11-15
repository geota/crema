/**
 * W0 capacity (#310 PLAN W0, SPEC §8 "Capacity", S5): `crema-db` installs
 * with 16 slots. Files and their journals fill them; the write that finds no
 * free slot makes the store call `add_capacity` and retry once, and goes
 * through. Capacity never shrinks.
 */
import { expect, test } from './fixtures.ts';

test('filling crema-db adds capacity and every write goes through', async ({ tab, gen }) => {
	const t = await tab();
	const g = gen();
	const start = await t.call<{ capacity: number }>('install', { pool: 'db', generation: g });
	expect(start.capacity).toBe(16);
	// Each file takes a slot, and its journal another at its first write.
	const files: string[] = [];
	for (let i = 1; i <= 10; i++) {
		const h = `f${i}`;
		await t.call('recover', { h, opts: { place: 'db', file: `history-${i}.db`, generation: i, journal: 'truncate' } });
		await t.call('writeVersion', { h, v: i, k: 5 });
		files.push(h);
	}
	const full = await t.call<{ capacity: number; files: string[] }>('poolInfo', { pool: 'db' });
	expect(full.capacity, JSON.stringify(full)).toBeGreaterThan(16);
	expect(full.files.length).toBeGreaterThan(16);
	for (const [i, h] of files.entries()) {
		expect(await t.call('versions', { h, k: 5 })).toEqual({ byVersion: { [i + 1]: 5 }, missing: 0 });
	}
	await t.call('closeAll');
	// Closing gives no slot back.
	expect((await t.call<{ capacity: number }>('poolInfo', { pool: 'db' })).capacity).toBe(full.capacity);
	console.log(`W0 capacity 16 -> ${full.capacity} for ${full.files.length} files`);
});
