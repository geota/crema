/**
 * #310 W1, M1: owners and clients of different builds or stages never write
 * through each other, and a client shows the right state for its stage.
 */
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { Env, openTab, STAGE1, STAGE2, row } from './test-kit';
import { OTHER_BUILD_MESSAGE, PROTOCOL_VERSION, type OwnerInfo, type Reply, type Request } from './protocol';
import { RPC_CHANNEL } from './names';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const settle = (ms = 200) => vi.advanceTimersByTimeAsync(ms);

/** An owner of another build on the bus: it answers hellos and requests as `info`, and records what it got. */
function foreignOwner(env: Env, info: Partial<OwnerInfo>) {
	const owner: OwnerInfo = {
		v: PROTOCOL_VERSION,
		tab: 'old-tab',
		build: 'old',
		stage: 1,
		role: 'index',
		file: 'history-index.db',
		generation: 0,
		health: 'ok',
		reason: 'open',
		...info
	};
	const ch = env.bus.channel(RPC_CHANNEL, 'old-tab');
	const got: Request[] = [];
	ch.onmessage = (e) => {
		const m = e.data as { t: string };
		if (m.t === 'hello') ch.postMessage({ t: 'ready', owner });
		if (m.t === 'req') {
			const req = m as Request;
			got.push(req);
			ch.postMessage({ t: 'reply', to: req.tab, id: req.id, owner, ok: true, value: { outcome: 'done', written: { change: { seq: 1, upserted: [], deleted: [], kinds: {} } } } } satisfies Reply);
		}
	};
	return { owner, got };
}

describe('a newer client and an older-build owner', () => {
	test('a stage-2 client refuses a stage-1 owner: read-only, the message, no write sent through', async () => {
		const env = new Env();
		const old = foreignOwner(env, {});
		// The lock is the old owner's: this tab's Worker only queues.
		const holder = env.locks.forCtx('old-tab');
		void holder.request('crema-history-db', {}, () => new Promise(() => {}));
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		expect(b.client.status).toMatchObject({ health: 'unavailable', reason: 'otherBuild', message: OTHER_BUILD_MESSAGE });
		await expect(b.client.write({ kind: 'upsert', rows: [{ rowJson: row('s1', 1) }] })).rejects.toMatchObject({ code: 'readOnly' });
		expect(old.got.filter((r) => r.op === 'write')).toEqual([]);
	});

	test('a client refuses an owner of another protocol version, whatever it answers', async () => {
		const env = new Env();
		foreignOwner(env, { v: PROTOCOL_VERSION + 1, stage: 1 });
		void env.locks.forCtx('old-tab').request('crema-history-db', {}, () => new Promise(() => {}));
		await settle();
		const b = openTab(env, STAGE1);
		await settle();
		const read = b.client.read('count', {});
		read.catch(() => {});
		await settle(1_000);
		expect(b.client.status).toMatchObject({ health: 'index_fallback', reason: 'otherBuild' });
		// Its answer is never taken: the read waits, then rejects rather than answer as if empty.
		await vi.advanceTimersByTimeAsync(31_000);
		await expect(read).rejects.toMatchObject({ code: 'unavailable' });
	});

	test('a stage-2 client refuses a stage-2 owner of another file or generation', async () => {
		const env = new Env();
		foreignOwner(env, { stage: 2, role: 'authority', file: 'history.db', generation: 3 });
		void env.locks.forCtx('old-tab').request('crema-history-db', {}, () => new Promise(() => {}));
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		expect(b.client.status).toMatchObject({ health: 'unavailable', reason: 'otherBuild' });
	});
});

describe('an owner and a client of another stage', () => {
	test('a stage-2 owner answers a stage-1 client nothing it may use, and refuses its writes', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		expect(a.client.status.ownerHere).toBe(true);
		const s1 = openTab(env, STAGE1);
		await settle();
		// The stage-1 tab uses its in-memory path, as an older build does (SPEC §11).
		expect(s1.client.status).toMatchObject({ health: 'index_fallback', reason: 'otherBuild' });
		// A write sent anyway (an older stage-1 build's) is refused by the owner.
		const ch = env.bus.channel(RPC_CHANNEL, 'raw');
		const replies: Reply[] = [];
		ch.onmessage = (e) => {
			if ((e.data as Reply).t === 'reply') replies.push(e.data as Reply);
		};
		ch.postMessage({
			t: 'req', v: PROTOCOL_VERSION, build: 'old', stage: 1, tab: 'raw', seq: 1, id: 'raw:1', expect: STAGE1.expect,
			op: 'write', wseq: 1, atMs: 0, write: { kind: 'upsert', rows: [{ rowJson: row('x', 1) }] }
		} satisfies Request);
		await settle();
		expect(replies).toHaveLength(1);
		expect(replies[0]).toMatchObject({ ok: false, error: { code: 'otherStage' } });
		expect(f.rows.size).toBe(0);
		expect(f.log).toEqual([]);
	});

	test('an owner refuses a request of another protocol version', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		openTab(env, STAGE2);
		await settle();
		const ch = env.bus.channel(RPC_CHANNEL, 'raw');
		const replies: Reply[] = [];
		ch.onmessage = (e) => {
			if ((e.data as Reply).t === 'reply') replies.push(e.data as Reply);
		};
		ch.postMessage({
			t: 'req', v: PROTOCOL_VERSION + 1, build: 'new', stage: 2, tab: 'raw', seq: 1, id: 'raw:1', expect: STAGE2.expect,
			op: 'write', wseq: 1, atMs: 0, write: { kind: 'delete', ids: ['a'] }
		} satisfies Request);
		await settle();
		expect(replies[0]).toMatchObject({ ok: false, error: { code: 'protocol' } });
		expect(f.log).toEqual([]);
	});

	test('an owner refuses a write meant for another file or generation', async () => {
		const env = new Env();
		const f = env.disk.plant('db', 0, 'history.db', 'authority');
		openTab(env, STAGE2);
		await settle();
		const ch = env.bus.channel(RPC_CHANNEL, 'raw');
		const replies: Reply[] = [];
		ch.onmessage = (e) => {
			if ((e.data as Reply).t === 'reply') replies.push(e.data as Reply);
		};
		for (const expect of [
			{ role: 'authority' as const, file: 'history.db', generation: 1 },
			{ role: 'authority' as const, file: 'history-2.db', generation: 0 }
		]) {
			ch.postMessage({
				t: 'req', v: PROTOCOL_VERSION, build: 'test', stage: 2, tab: 'raw', seq: replies.length + 1, id: `raw:${replies.length + 1}`, expect,
				op: 'write', wseq: 1, atMs: 0, write: { kind: 'delete', ids: ['a'] }
			} satisfies Request);
			await settle();
		}
		expect(replies.map((r) => !r.ok && r.error.code)).toEqual(['otherFile', 'otherFile']);
		expect(f.log).toEqual([]);
	});

	test('every reply names the owner: tab, build, stage, role, file', async () => {
		const env = new Env();
		env.disk.plant('db', 0, 'history.db', 'authority');
		const a = openTab(env, STAGE2);
		await settle();
		const b = openTab(env, STAGE2);
		await settle();
		expect(b.client.status.owner).toMatchObject({
			v: PROTOCOL_VERSION, tab: a.ctx, build: 'test', stage: 2, role: 'authority', file: 'history.db', health: 'ok'
		});
	});
});
