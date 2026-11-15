/**
 * W0 connections (#310 PLAN W0, SPEC §8 "One connection per file", S4, M7,
 * SPEC §1 "The pre-3.54 locking bug"), in one Worker on real OPFS:
 * - a second connection to a file the store has open is refused;
 * - connections to two files work, in one pool and across the two pools,
 *   and so does the online backup copy between them: the snapshot
 *   (`crema-db` to `crema-db`) and the flip (`crema-index` to `crema-db`);
 * - a copy into `crema-index` is refused (the index never holds the truth);
 * - SQLite is 3.54 or newer, and the same as the native build's.
 */
import fs from 'node:fs';
import { expect, test } from './fixtures.ts';

const truth = (file: string) => ({ place: 'db', file, role: 'authority', journal: 'truncate' });

test('one connection per file; two files and copies between them work, in one pool and across two', async ({
	tab,
	gen
}) => {
	const t = await tab();
	const g = gen();
	await t.call('install', { pool: 'db', generation: g });
	await t.call('install', { pool: 'index', generation: g });

	await t.call('recover', { h: 'a', opts: { place: 'db', file: 'history.db', generation: 1, journal: 'truncate' } });
	await t.call('writeVersion', { h: 'a', v: 1, k: 30 });
	// A second connection to the same file: refused, by open and by create.
	const again = await t.try('open', { h: 'a2', opts: truth('history.db') });
	expect(again.error?.code).toBe('alreadyOpen');
	const recreate = await t.try('recover', { h: 'a3', opts: { place: 'db', file: 'history.db', generation: 1, journal: 'truncate' } });
	expect(recreate.ok).toBe(false);

	// A second file in the same pool, open beside the first.
	await t.call('recover', { h: 'b', opts: { place: 'db', file: 'history-2.db', generation: 2, journal: 'truncate' } });
	await t.call('writeVersion', { h: 'b', v: 2, k: 10 });
	expect(await t.call('call', { h: 'a', m: 'count', a: {} })).toBe(30);
	expect(await t.call('call', { h: 'b', m: 'count', a: {} })).toBe(10);

	// A file in the other pool, open beside both.
	await t.call('open', {
		h: 'i',
		opts: { place: 'index', file: 'history-index.db', role: 'index', create: true, generation: 1, journal: 'truncate' }
	});
	await t.call('writeVersion', { h: 'i', v: 3, k: 25 });

	// The snapshot: a copy within crema-db, with both ends open in this Worker.
	await t.call('call', { h: 'a', m: 'copyTo', a: { place: 'db', file: 'history-snap.db', journal: 'truncate' } });
	await t.call('open', { h: 's', opts: truth('history-snap.db') });
	expect(await t.call('versions', { h: 's', k: 30 })).toEqual({ byVersion: { 1: 30 }, missing: 0 });
	// The flip: a copy from crema-index into crema-db. The copy is the
	// index's, `role = 'index'`, until the flip's step 5 sets `authority`, so
	// a truth open reads it and refuses it on its role: it is a whole SQLite
	// file, apart from the index.
	await t.call('call', { h: 'i', m: 'copyTo', a: { place: 'db', file: 'history-flip.db', journal: 'truncate' } });
	const flipped = await t.try('open', { h: 'f', opts: truth('history-flip.db') });
	expect(flipped.error?.code).toBe('wrongRole');
	const slots = await t.call<{ name: string; size: number }[]>('slots', { dir: `.crema-history-db-${g}` });
	const indexSlots = await t.call<{ name: string; size: number }[]>('slots', { dir: `.crema-history-index-${g}` });
	const size = (xs: { name: string; size: number }[], n: string) => xs.find((x) => x.name === n)?.size;
	expect(size(slots, '/history-flip.db')).toBe(size(indexSlots, '/history-index.db'));
	const poolFiles = (await t.call<{ files: string[] }>('poolInfo', { pool: 'db' })).files;
	expect(poolFiles).toEqual(expect.arrayContaining(['/history.db', '/history-2.db', '/history-snap.db', '/history-flip.db']));

	// Never into the index's pool (M7).
	const intoIndex = await t.try('call', { h: 'a', m: 'copyTo', a: { place: 'index', file: 'stray.db', journal: 'truncate' } });
	expect(intoIndex.error?.code).toBe('refused');

	// Every file still answers.
	expect(await t.call('call', { h: 'a', m: 'count', a: {} })).toBe(30);
	expect(await t.call('call', { h: 'b', m: 'count', a: {} })).toBe(10);
	expect(await t.call('call', { h: 'i', m: 'count', a: {} })).toBe(25);
	await t.call('closeAll');
});

test('SQLite is 3.54 or newer, and the native build pins the same version', async ({ tab }) => {
	const t = await tab();
	const [major, minor] = t.info.sqlite.split('.').map(Number);
	expect(major * 1000 + minor, `SQLite ${t.info.sqlite} is older than 3.54`).toBeGreaterThanOrEqual(3054);
	// The native constant the C4 test checks the wasm build against.
	const native = fs.readFileSync(new URL('../../../core/de1-history/src/store/mod.rs', import.meta.url), 'utf8');
	const pinned = /SQLITE_VERSION: &str = "([\d.]+)"/.exec(native)?.[1];
	expect(pinned, 'the native pin').toBeDefined();
	expect(t.info.sqlite).toBe(pinned);
});
