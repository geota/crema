/**
 * The stolen-serial fetch + cache wrapper. The list rules are pinned in core
 * (`de1_domain::stolen_serials`); these cover the shell's half: the daily
 * cadence, caching only real lists, and staying silent on failure.
 */
import { describe, expect, it } from 'vitest';
import { isSerialStolen, type StolenSerialsDeps } from './stolen-serials';

const LIST = '{"updated":"2026-09-29","stolen_sns":["76","317","11358"]}';
const DAY = 24 * 60 * 60 * 1000;

function harness(response: () => Promise<{ ok: boolean; text(): Promise<string> }>) {
	let cache: { fetchedAt: number; body: string } | null = null;
	let now = 1_000_000;
	let fetches = 0;
	const deps: StolenSerialsDeps = {
		fetch: () => {
			fetches += 1;
			return response();
		},
		now: () => now,
		read: () => cache,
		write: (c) => {
			cache = c;
		}
	};
	return {
		deps,
		get fetches() {
			return fetches;
		},
		advance(ms: number) {
			now += ms;
		},
		get cache() {
			return cache;
		}
	};
}

const ok = (body: string) => async () => ({ ok: true, text: async () => body });

describe('isSerialStolen', () => {
	it('flags a listed serial and caches the list', async () => {
		const h = harness(ok(LIST));
		expect(await isSerialStolen(317, h.deps)).toBe(true);
		expect(await isSerialStolen(6262, h.deps)).toBe(false);
		expect(h.fetches).toBe(1);
		expect(h.cache?.body).toBe(LIST);
	});

	it('refetches at most once a day', async () => {
		const h = harness(ok(LIST));
		await isSerialStolen(76, h.deps);
		h.advance(DAY - 1);
		await isSerialStolen(76, h.deps);
		expect(h.fetches).toBe(1);
		h.advance(1);
		await isSerialStolen(76, h.deps);
		expect(h.fetches).toBe(2);
	});

	it('ignores failures silently and keeps the cached list', async () => {
		let fail = false;
		const h = harness(async () => {
			if (fail) throw new Error('offline');
			return { ok: true, text: async () => LIST };
		});
		expect(await isSerialStolen(76, h.deps)).toBe(true);
		fail = true;
		h.advance(DAY);
		expect(await isSerialStolen(76, h.deps)).toBe(true);
	});

	it('never caches an error page', async () => {
		const h = harness(ok('404: Not Found'));
		expect(await isSerialStolen(76, h.deps)).toBe(false);
		expect(h.cache).toBeNull();
		const h2 = harness(async () => ({ ok: false, text: async () => LIST }));
		expect(await isSerialStolen(76, h2.deps)).toBe(false);
		expect(h2.cache).toBeNull();
	});
});
