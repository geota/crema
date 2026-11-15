/**
 * W0 failed starts (#310 PLAN W0, SPEC §8 "A failed start never deletes
 * storage" and its table, S5): each way a stage-2 boot can fail, with the
 * marker `done`, ends in the table's health state, and every OPFS file the
 * history keeps is still there afterwards, byte for byte (listed, with a
 * SHA-256 of each, before and after).
 *
 * | What failed                                  | Health        |
 * | -------------------------------------------- | ------------- |
 * | the pool's handles held by another tab       | `unavailable` |
 * | the pool directory missing                   | `lost`        |
 * | `history.db` corrupt                         | `lost`        |
 * | `history.db` foreign (`application_id`)      | `lost`        |
 * | a slot with an invalid header                | `unavailable` |
 *
 * `bootStage2` (fixtures.ts) is the smallest model of W1's boot. Fallback C
 * isn't chosen, so its `preserveOnInitFailure` variant isn't here.
 */
import { bootStage2, dbDir, expect, expectKept, listFiles, newTruth, test, type Tab } from './fixtures.ts';

/** `history.db` with rows in generation `g`, closed and its pool uninstalled. */
async function closedTruth(t: Tab, g: number) {
	await newTruth(t, g);
	await t.call('close', { h: 'db' });
	await t.call('uninstall', { pool: 'db' });
}

test('handles held by another tab: unavailable after the retries, nothing changed', async ({ tab, gen }) => {
	const g = gen();
	const holder = await tab();
	const booting = await tab();
	await newTruth(holder, g);
	const before = await listFiles(booting, dbDir(g));
	const health = await bootStage2(booting, g, { budgetMs: 3_000 });
	expect(health.health).toBe('unavailable');
	expect(health.attempts).toBeGreaterThan(1);
	expectKept(before, await listFiles(booting, dbDir(g)), 'held handles');
	console.log(`W0 failed-start held ${JSON.stringify(health)}`);
});

test('the pool directory missing: lost, and every other file kept', async ({ tab, gen }) => {
	const t = await tab();
	// Another generation with a file, to show a boot elsewhere leaves it alone.
	const other = gen();
	await closedTruth(t, other);
	const g = gen();
	const before = await listFiles(t, dbDir(other), dbDir(g));
	expect(before.some((e) => e.path.startsWith(`${dbDir(g)}/`))).toBe(false);
	const health = await bootStage2(t, g);
	expect(health).toMatchObject({ health: 'lost', reason: 'absent' });
	expectKept(before, await listFiles(t, dbDir(other), dbDir(g)), 'missing directory');
	// The boot created no database: the new pool holds no file.
	expect((await t.call<{ files: string[] }>('poolInfo', { pool: 'db' })).files).toEqual([]);
});

test('a corrupt history.db: lost, and the file left exactly as it was', async ({ tab, gen }) => {
	const t = await tab();
	const g = gen();
	await closedTruth(t, g);
	// The database's own header, after the 4 KiB slot header.
	await t.call('corruptSlot', { dir: dbDir(g), name: '/history.db', offset: 4096, length: 100 });
	const before = await listFiles(t, dbDir(g));
	const health = await bootStage2(t, g);
	expect(health).toMatchObject({ health: 'lost', reason: 'corrupt' });
	await t.call('uninstall', { pool: 'db' });
	expect(await listFiles(t, dbDir(g)), 'corrupt file').toEqual(before);
});

test('a foreign history.db: lost, and the file left exactly as it was', async ({ tab, gen }) => {
	const t = await tab();
	const g = gen();
	await closedTruth(t, g);
	// `application_id` is at byte 68 of the database header.
	await t.call('corruptSlot', { dir: dbDir(g), name: '/history.db', offset: 4096 + 68, length: 4, fill: 0x11 });
	const before = await listFiles(t, dbDir(g));
	const health = await bootStage2(t, g);
	expect(health).toMatchObject({ health: 'lost', reason: 'foreign' });
	await t.call('uninstall', { pool: 'db' });
	expect(await listFiles(t, dbDir(g)), 'foreign file').toEqual(before);
});

test('a slot with an invalid header: unavailable at once, every file byte-identical', async ({ tab, gen }) => {
	const t = await tab();
	const g = gen();
	await closedTruth(t, g);
	// A slot whose name field isn't NUL-terminated (S5): sahpool refuses the pool.
	await t.call('plant', { path: `${dbDir(g)}/.opaque/PLANTEDBADSLOT`, size: 8192, fill: 0x41 });
	const before = await listFiles(t, dbDir(g));
	const health = await bootStage2(t, g);
	expect(health).toMatchObject({ health: 'unavailable', reason: 'pool.invalidHeader', attempts: 1 });
	expect(await listFiles(t, dbDir(g)), 'invalid header').toEqual(before);
});
