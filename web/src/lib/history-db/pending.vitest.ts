/**
 * #310 W1, M5: the pending journal. Two appenders and two overlapping drains
 * (an owner change mid-drain), interleaved at every step: no entry is lost,
 * none is applied over a newer edit, and the journal ends empty. Then the
 * journal through real tabs: a frozen owner, a read-only tab's capture, and
 * the drains a hello, a lock and a `storage` event start.
 */
import { afterEach, describe, expect, test, vi } from 'vitest';
import { appendPending, drainPending, NOTHING_DRAINED, pendingKey } from './pending';
import { Env, FakeStorage, openTab, STAGE2, row } from './test-kit';
import type { Drained, JournalEntry } from './protocol';

// Fake timers drive minutes of protocol time; under a full parallel run that takes real seconds.
vi.setConfig({ testTimeout: 60_000 });

const flush = async () => {
	for (let i = 0; i < 5; i++) await Promise.resolve();
};

/** Every interleaving of `threads`, each a list of step names kept in order. */
function* interleavings(threads: string[][]): Generator<string[]> {
	const idx = threads.map(() => 0);
	const out: string[] = [];
	function* go(): Generator<string[]> {
		if (out.length === threads.reduce((n, t) => n + t.length, 0)) {
			yield [...out];
			return;
		}
		for (let t = 0; t < threads.length; t++) {
			if (idx[t] >= threads[t].length) continue;
			out.push(threads[t][idx[t]++]);
			yield* go();
			idx[t]--;
			out.pop();
		}
	}
	yield* go();
}

describe('every interleaving of two appenders, two drains and an edit', () => {
	test('no entry lost, none applied over a newer edit, the journal ends empty', async () => {
		const threads = [
			['d1.start', 'd1.commit'],
			['d2.start', 'd2.commit'],
			['append.a', 'append.b'],
			['edit.a']
		];
		let runs = 0;
		for (const order of interleavings(threads)) {
			runs++;
			const storage = new FakeStorage();
			const store = new Map<string, string>();
			// The owner's drain, held until its commit step: insert each absent id.
			const commits: Record<string, () => void> = {};
			const insert = (d: string) => (entries: JournalEntry[]) =>
				new Promise<Drained>((resolve) => {
					commits[d] = () => {
						const out: Drained = { ...NOTHING_DRAINED, inserted: [], present: [] };
						for (const { row: r } of entries) {
							const id = JSON.parse(r).id;
							if (store.has(id)) out.present.push(id);
							else {
								store.set(id, r);
								out.inserted.push(id);
							}
						}
						resolve(out);
					};
				});
			const drains: Promise<unknown>[] = [];
			let edited = false;
			for (const step of order) {
				if (step === 'd1.start' || step === 'd2.start') drains.push(drainPending(storage, insert(step.slice(0, 2))));
				else if (step.endsWith('.commit')) commits[step.slice(0, 2)]?.() ?? undefined;
				else if (step === 'append.a') appendPending(storage, row('a', 1));
				else if (step === 'append.b') appendPending(storage, row('b', 1));
				else if (step === 'edit.a' && store.has('a')) {
					store.set('a', row('a', 5));
					edited = true;
				}
				await flush();
			}
			// A drain that never got its commit (no entries): nothing to wait for.
			for (const d of ['d1', 'd2']) commits[d]?.();
			await Promise.all(drains);
			// The next owner's drain takes what's left.
			await drainPending(storage, async (entries) => {
				const out: Drained = { ...NOTHING_DRAINED, inserted: [], present: [] };
				for (const { row: r } of entries) {
					const id = JSON.parse(r).id;
					if (store.has(id)) out.present.push(id);
					else {
						store.set(id, r);
						out.inserted.push(id);
					}
				}
				return out;
			});
			const ctx = order.join(' ');
			expect(store.has('a'), ctx).toBe(true);
			expect(store.has('b'), ctx).toBe(true);
			if (edited) expect(JSON.parse(store.get('a')!).metadata.rating, ctx).toBe(5);
			expect(storage.keys(), ctx).toEqual([]);
		}
		expect(runs).toBe(630);
	});

	test('an entry rewritten while a drain has it stays for the next drain', async () => {
		const storage = new FakeStorage();
		appendPending(storage, row('a', 1));
		let release: (d: Drained) => void = () => {};
		const d = drainPending(storage, () => new Promise((r) => (release = r)));
		storage.setItem(pendingKey('a'), row('a', 2));
		release({ ...NOTHING_DRAINED, inserted: ['a'] });
		await d;
		expect(storage.getItem(pendingKey('a'))).toBe(row('a', 2));
	});

	test('an entry the owner gave no answer for stays', async () => {
		const storage = new FakeStorage();
		appendPending(storage, row('a', 1));
		appendPending(storage, row('b', 1));
		await drainPending(storage, async () => ({ ...NOTHING_DRAINED, inserted: ['a'] }));
		expect(storage.keys()).toEqual([pendingKey('b')]);
	});
});

describe('the journal through tabs', () => {
	afterEach(() => vi.useRealTimers());
	const settle = (ms = 200) => vi.advanceTimersByTimeAsync(ms);

	test('a frozen owner: the other tab goes read-only and journals; thawed, the owner drains it once', async () => {
		vi.useFakeTimers();
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		expect(b.client.status.health).toBe('ok');
		a.freeze();
		const r = b.client.read('count', {});
		r.catch(() => {});
		await settle(6_000);
		expect(b.client.status).toMatchObject({ health: 'unavailable', reason: 'noAnswer' });
		await expect(b.client.write({ kind: 'delete', ids: ['x'] })).rejects.toMatchObject({ code: 'readOnly' });
		expect(await b.client.capture(row('shot:1', 0))).toBe('journaled');
		expect(env.storage.keys()).toEqual([pendingKey('shot:1')]);
		a.thaw();
		await settle(10_000);
		expect(f.rows.has('shot:1')).toBe(true);
		expect(env.storage.keys()).toEqual([]);
		expect(f.log.filter((l) => l.write.kind === 'upsert')).toHaveLength(1);
		expect(b.client.status.health).toBe('ok');
		await expect(r).resolves.toBe(0);
	});

	test('a journaled shot whose id is already there is dropped from the journal, the row left as it is', async () => {
		vi.useFakeTimers();
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		f.rows.set('shot:1', row('shot:1', 4));
		env.storage.setItem(pendingKey('shot:1'), row('shot:1', 0));
		openTab(env, STAGE2);
		await settle(1_000);
		expect(f.rows.get('shot:1')).toBe(row('shot:1', 4));
		expect(env.storage.keys()).toEqual([]);
	});

	test('the owner drains on a storage event for the prefix', async () => {
		vi.useFakeTimers();
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		openTab(env, STAGE2);
		await settle(1_000);
		// Another tab (an older build, or one whose owner was gone) journals a shot.
		env.storage.as('elsewhere', () => env.storage.setItem(pendingKey('shot:9'), row('shot:9', 0)));
		await settle(1_000);
		expect(f.rows.has('shot:9')).toBe(true);
		expect(env.storage.keys()).toEqual([]);
	});

	test('a capture while the owner answers is written, and its journal entry goes after the commit', async () => {
		vi.useFakeTimers();
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		const c = b.client.capture(row('shot:2', 0));
		// Journaled first: a kill now loses nothing.
		expect(env.storage.keys()).toEqual([pendingKey('shot:2')]);
		await settle();
		expect(await c).toBe('written');
		expect(f.rows.has('shot:2')).toBe(true);
		expect(env.storage.keys()).toEqual([]);
	});

	test('a tab killed right after its capture: the entry survives, and the owner drains it', async () => {
		vi.useFakeTimers();
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		env.bus.route = (_m, from) => (from === b.ctx ? 'drop' : undefined);
		void b.client.capture(row('shot:3', 0)).catch(() => {});
		b.close();
		env.bus.route = () => undefined;
		expect(env.storage.keys()).toEqual([pendingKey('shot:3')]);
		// A hello from any tab makes the owner drain.
		openTab(env, STAGE2);
		await settle(1_000);
		expect(f.rows.has('shot:3')).toBe(true);
		expect(env.storage.keys()).toEqual([]);
	});
});
