/**
 * R1w in real browsers (#310 PLAN R1w, M3): a restore into `history.db`
 * across an owner change. Two tabs; the restore runs in the one that doesn't
 * own the database; the owner tab is killed between the restore's batches;
 * the restore then finishes through the next owner. No row of the backup is
 * deleted, every row the plan removes is gone, and a row the plan doesn't name
 * (one captured meanwhile) stays.
 */
import type { BrowserContext, Page } from '@playwright/test';
import { BASE, dbDir, expect, listFiles, newTruth, test, type Tab } from './fixtures.ts';

/** `history.db` in `crema-db` generation `g`, empty, made through W0's harness, then let go. */
async function makeTruth(t: Tab, g: number) {
	await newTruth(t, g, { k: 0 });
	await t.call('close', { h: 'db' });
	await t.call('uninstall', { pool: 'db' });
	await t.call('writeText', { path: `${dbDir(g)}/.repair-done`, text: JSON.stringify({ copied: ['/history.db'] }) });
}

async function r1w(ctx: BrowserContext, g: number): Promise<Page> {
	const page = await ctx.newPage();
	await page.goto(`${BASE}/tests/browser/r1w/index.html?g=${g}`);
	await page.waitForFunction(() => document.body.dataset.ready === '1');
	return page;
}

type Status = { health: string; ownerHere: boolean };
interface R1w {
	status(): Status;
	until(want: Partial<Status>, ms?: number): Promise<Status>;
	seed(shots: unknown[]): Promise<unknown>;
	restore(input: unknown, pauseAfter?: number): Promise<{ ok: boolean; value?: { stored: boolean }; message?: string }>;
	paused(): boolean;
	go(): void;
	rows(): Promise<Record<string, number>>;
	withDetails(ids: string[]): Promise<string[]>;
	stop(): Promise<void>;
}
const on = <T>(p: Page, f: (r: R1w, a: unknown) => T | Promise<T>, arg?: unknown): Promise<T> =>
	p.evaluate(
		([src, a]) => (0, eval)(`(${src})`)((window as unknown as { r1w: unknown }).r1w, a),
		[f.toString(), arg] as const
	) as Promise<T>;

const shot = (id: string, rating: number) => ({
	formatVersion: 3,
	id,
	completedAt: 1_790_000_000_000,
	profileName: 'R1w',
	profile: null,
	stopReason: null,
	metadata: { dose: 18, yieldOut: 36, beans: null, grinderSetting: null, notes: '', rating, tds: null, extractionYield: null },
	record: { duration: 30_000, samples: [{ elapsed: 0, sample: { sampleTime: 0, groupPressure: 9 } }] }
});

const plan = (p: Record<string, unknown>) => ({
	add: [],
	overwrite: [],
	keep: 0,
	remove: [],
	fill: [],
	zeroDebit: [],
	deletedHere: [],
	revive: [],
	notAdded: 0,
	notRevived: [],
	pinnedRemoved: 0,
	...p
});

test('a restore across an owner change deletes no row of the backup (M3)', async ({ profile, tab, gen }) => {
	const g = gen();
	const h = await tab();
	await makeTruth(h, g);
	await h.page.close();

	const a = await r1w(profile, g);
	await on(a, (r) => r.until({ health: 'ok', ownerHere: true }));
	const b = await r1w(profile, g);
	await on(b, (r) => r.until({ health: 'ok', ownerHere: false }));

	// Here: l1–l4 and s1, s2. The backup: s1, s2 (rated 5) and b1–b6. Replace removes l1–l4.
	await on(a, (r, s) => r.seed(s as unknown[]), ['l1', 'l2', 'l3', 'l4', 's1', 's2'].map((id) => shot(id, 1)));
	const backup = [shot('s1', 5), shot('s2', 5), ...['b1', 'b2', 'b3', 'b4', 'b5', 'b6'].map((id) => shot(id, 5))];
	const input = {
		plan: plan({ add: ['b1', 'b2', 'b3', 'b4', 'b5', 'b6'], overwrite: ['s1', 's2'], remove: ['l1', 'l2', 'l3', 'l4'] }),
		backup,
		backupIds: backup.map((s) => s.id),
		replace: true
	};

	// B restores; it stops after its second batch of rows has landed.
	const running = on(b, (r, i) => r.restore(i, 2), input);
	await b.waitForFunction(() => (window as unknown as { r1w: { paused(): boolean } }).r1w.paused());
	// The owner tab is killed between batches, and a shot is captured in a third tab meanwhile.
	await a.close({ runBeforeUnload: false });
	await on(b, (r) => r.until({ health: 'ok', ownerHere: true }, 40_000));
	const c = await r1w(profile, g);
	await on(c, (r) => r.until({ health: 'ok', ownerHere: false }));
	await on(c, (r, s) => r.seed(s as unknown[]), [shot('new1', 3)]);
	await on(b, (r) => r.go());
	const done = await running;
	expect(done).toMatchObject({ ok: true, value: { stored: true } });

	const rows = await on(c, (r) => r.rows());
	expect(Object.keys(rows).sort()).toEqual(['b1', 'b2', 'b3', 'b4', 'b5', 'b6', 'new1', 's1', 's2']);
	for (const s of backup) expect(rows[s.id], s.id).toBe(5);
	expect(await on(b, (r, ids) => r.withDetails(ids as string[]), Object.keys(rows))).toEqual(Object.keys(rows).sort());
	expect(await on(b, (r) => r.withDetails(['l1', 'l2', 'l3', 'l4']))).toEqual([]);

	await on(b, (r) => r.stop());
	await on(c, (r) => r.stop());
	const h2 = await tab();
	expect((await listFiles(h2, dbDir(g))).some((f) => f.path.endsWith('/history.db'))).toBe(true);
});
