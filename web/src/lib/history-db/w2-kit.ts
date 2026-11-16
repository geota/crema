/**
 * `#lib/history-db/w2-kit.js` — the stage-1 index under Vitest (#310 W2): the
 * test kit's tabs and Workers over the real store (`node-engine.ts`), each
 * owner keeping its index in step with `crema-history` in the fake IndexedDB,
 * which the P3 store writes. Tests only.
 */
import { vi } from 'vitest';
import { HistoryDb, META_MIGRATION, type MigrationRecord } from '#lib/history/history-db.js';
import { summarize } from '#lib/history/summary.js';
import type { StoredShot } from '#lib/history/model.js';
import { writeDoneMarker } from '#lib/history/store-marker.js';
import { Env, openTab, STAGE1, type Tab } from './test-kit.js';
import { NodeEngine } from './node-engine.js';
import { IndexSync, type SyncDeps } from './index-sync.js';
import { p3Source } from './p3-source.js';
import { P3_CHANNEL } from './names.js';
import type { HistoryDbClient } from './client.js';

export interface W2Env {
	env: Env;
	/** Each Worker's engine, by its context. */
	engines: Map<string, NodeEngine>;
	/** Each Worker's index sync, by its context. */
	syncs: Map<string, IndexSync>;
	/** The P3 store's channel, as a tab posts on it. */
	p3Channel(ctx: string): ReturnType<Env['bus']['channel']>;
	/** Every line the syncs logged. */
	log: string[];
}

/** An env whose Workers run the real store and the index's upkeep. */
export function w2Env(extra: Partial<Pick<SyncDeps, 'source'>> = {}): W2Env {
	const engines = new Map<string, NodeEngine>();
	const syncs = new Map<string, IndexSync>();
	const log: string[] = [];
	const env: Env = new Env({
		engine: (ctx) => {
			const e = new NodeEngine();
			engines.set(ctx, e);
			return e;
		},
		indexSync: (ctx, d) => {
			const s = new IndexSync({
				...d,
				source: extra.source ?? p3Source(),
				channel: env.bus.channel(P3_CHANNEL, ctx),
				log: (l) => log.push(`${ctx}: ${l}`)
			});
			syncs.set(ctx, s);
			return s;
		}
	});
	return { env, engines, syncs, log, p3Channel: (ctx) => env.bus.channel(P3_CHANNEL, ctx) };
}

/** A stage-1 tab. */
export function stage1Tab(w: W2Env): Tab {
	return openTab(w.env, STAGE1);
}

/** Until `client` can read the index (its owner's diff is done). */
export async function indexReady(client: HistoryDbClient, timeout = 20_000): Promise<void> {
	await vi.waitFor(
		() => {
			if (client.status.health !== 'ok') throw new Error(`health ${client.status.health} (${client.status.reason})`);
		},
		{ timeout, interval: 20 }
	);
}

/** A device already on `crema-history` with `shots`, as a migration leaves it. */
export async function seedP3(shots: readonly StoredShot[]): Promise<void> {
	const db = new HistoryDb({ expectExisting: () => false });
	for (let i = 0; i < shots.length; i += 1_000) {
		await db.putShots(
			shots.slice(i, i + 1_000).map((shot) => ({ shot, details: true })),
			summarize
		);
	}
	await db.setMeta(META_MIGRATION, {
		state: 'done',
		sourceHash: '0:0',
		sourceIds: shots.map((s) => s.id),
		doneAt: 1
	} satisfies MigrationRecord);
	db.close();
	writeDoneMarker(1, shots.length);
}

/** A connection of the test's own to `crema-history`, never creating it. */
export function p3Db(): HistoryDb {
	return new HistoryDb({ expectExisting: () => true });
}
