/**
 * A web restore through the core's plan (restore-safety PR7w): the tab rules
 * a restoring tab must keep, the plan written per store with nothing cleared,
 * and the marker that owns its deletes.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { getSettingsStore } from '#lib/settings/index.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { readQueue, enqueueEntry } from '#lib/services/queue-store.js';
import { buildBackupJsonl, webSingletons } from '#lib/backup/index.js';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import { PlanCode, RestoreMode, Verdict } from '#lib/core/crema-core.js';
import {
	MACHINE_BUSY_LOCK,
	RESTORE_LOCK,
	TAB_ID,
	resetTabSafetyForTest,
	type RestoreMessage
} from './tab-safety';
import { planNow, prepareBackup, runRestore, type RestoreDeps } from './engine';
import { DELETE_AFTER_MS, MARKER_KEY, beginMarker, checkMarkerAtBoot, readMarker } from './marker';
import { confirmText, outcomeMessage, refusalMessage } from './report';

const samples = [
	{
		elapsed: 100,
		sample: {
			sampleTime: 10,
			groupPressure: 9,
			groupFlow: 2,
			headTemp: 92,
			mixTemp: 93,
			setMixTemp: 93,
			setHeadTemp: 92,
			setGroupPressure: 9,
			setGroupFlow: 0,
			frameNumber: 1,
			steamTemp: 140
		}
	}
] as StoredShot['record']['samples'];

const metadata = {
	dose: 18,
	yieldOut: 36,
	beans: null,
	grinderSetting: null,
	notes: '',
	rating: 4,
	tds: null,
	extractionYield: null
};

function shot(id: string, extra: Partial<StoredShot> = {}): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000 + Number(id.replace(/\D/g, '') || 0),
		profileName: 'Test',
		profile: null,
		stopReason: null,
		metadata,
		record: { duration: 30_000, samples },
		...extra
	} as StoredShot;
}

function resetAll(): void {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	getRecipeStore().applyBackup([], null, true);
	getCustomMethodStore().applyBackup([], true);
	localStorage.clear();
}

/** A backup of beans `beanIds` and shots `shotIds`, then the device emptied. */
async function backupOf(beanIds: string[], shotIds: string[], bean: (id: string) => string = () => 'b1') {
	resetAll();
	getBeanStore().bulkAdd(
		beanIds.map((id) => ({ ...blankBean(id), name: `Bean ${id}` })),
		[]
	);
	for (const id of shotIds) {
		getHistoryStore().insertPulled(shot(id, { bean: { beanId: bean(id), name: 'x', roasterName: null, roastedOn: null, roastLevel: null, tags: [], grinderSetting: null } as never, bagDebit: 18 }));
	}
	const built = (await buildBackupJsonl())!;
	resetAll();
	return prepareBackup(built.jsonl, 'test backup');
}

/** Every message this tab posts, with what the locks held at that moment. */
function captureMessages(locks: FakeLocks, onPost?: (m: RestoreMessage) => void) {
	const posts: { message: RestoreMessage; restoreHeld: boolean; marker: string | null }[] = [];
	vi.stubGlobal(
		'BroadcastChannel',
		class {
			postMessage(m: RestoreMessage) {
				posts.push({
					message: m,
					restoreHeld: locks.held.some((l) => l.name === RESTORE_LOCK),
					marker: readMarker()?.phase ?? null
				});
				onPost?.(m);
			}
			close() {}
		}
	);
	return posts;
}

function deps(locks: FakeLocks | undefined, more: Partial<RestoreDeps> = {}): RestoreDeps {
	return { singletons: webSingletons().sink, locks, machineWaitMs: 200, progressMs: 5, ...more };
}

const settle = (ms = 0) => new Promise((r) => setTimeout(r, ms));

beforeEach(() => {
	resetAll();
	resetTabSafetyForTest();
});
afterEach(() => {
	vi.unstubAllGlobals();
	vi.useRealTimers();
});

describe('the restoring tab and the others (PR7w rules)', () => {
	it('holds crema-restore from before started until after done, and posts done after the last write', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		const posts = captureMessages(locks);
		const out = await runRestore(prepared, 'merge', {}, deps(locks));
		expect(out.kind).toBe('done');
		const types = posts.map((p) => p.message.type);
		expect(types[0]).toBe('started');
		expect(types.at(-1)).toBe('done');
		expect(posts.every((p) => p.restoreHeld)).toBe(true);
		// The last write, the marker's finishing phase, comes before `done`.
		expect(posts.at(-1)?.marker).toBe('finishing');
		expect(locks.held).toEqual([]);
	});

	it('tags every message with this tab', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		const posts = captureMessages(locks);
		await runRestore(prepared, 'merge', {}, deps(locks));
		expect(posts.length).toBeGreaterThan(1);
		expect(posts.every((p) => p.message.from === TAB_ID)).toBe(true);
	});

	it('posts done from a finally when a step fails', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		const posts = captureMessages(locks);
		const out = await runRestore(prepared, 'merge', {}, deps(locks, { failAt: 'beans' }));
		expect(out).toMatchObject({ kind: 'stopped', at: 'beans', landed: ['marker', 'tombstones', 'photos', 'recipes'] });
		expect(posts.at(-1)?.message.type).toBe('done');
		expect(readMarker()?.phase).toBe('applying');
	});

	it('sends progress inside a long step: step 8 waiting on a shot', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		let shotOver: (() => void) | null = null;
		const posts = captureMessages(locks, (m) => {
			// A shot starts in another tab just as the restore begins.
			if (m.type === 'started') void locks.hold(MACHINE_BUSY_LOCK, 'shared').then((r) => (shotOver = r));
		});
		const running = runRestore(prepared, 'merge', {}, deps(locks, { machineWaitMs: 5_000 }));
		await settle(60);
		const during = posts.filter((p) => p.message.type === 'progress').length;
		expect(during).toBeGreaterThan(2);
		expect(posts.some((p) => p.message.type === 'done')).toBe(false);
		shotOver!();
		await running;
		expect(posts.at(-1)?.message.type).toBe('done');
	});

	it('another tab restoring: nothing runs', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		const release = await locks.hold(RESTORE_LOCK);
		const posts = captureMessages(locks);
		expect((await runRestore(prepared, 'merge', {}, deps(locks))).kind).toBe('busy');
		expect(posts).toEqual([]);
		expect(getBeanStore().beans).toEqual([]);
		release();
	});

	it('a shot or flash in any tab refuses at step 0, before started', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		const release = await locks.hold(MACHINE_BUSY_LOCK, 'shared');
		const posts = captureMessages(locks);
		const out = await runRestore(prepared, 'merge', {}, deps(locks));
		expect(out.kind).toBe('refused');
		if (out.kind === 'refused') expect(out.plan.refusals[0].code).toBe(PlanCode.MachineBusy);
		expect(posts).toEqual([]);
		expect(getBeanStore().beans).toEqual([]);
		expect(readMarker()).toBeNull();
		release();
	});

	it('the busy lock itself refuses at step 0, even where a query missed the shot', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		// A shot that took the lock after the plan's look at it.
		locks.query = async () => ({ held: [], pending: [] });
		const release = await locks.hold(MACHINE_BUSY_LOCK, 'shared');
		const posts = captureMessages(locks);
		const out = await runRestore(prepared, 'merge', {}, deps(locks));
		expect(out.kind).toBe('refused');
		expect(posts).toEqual([]);
		expect(getBeanStore().beans).toEqual([]);
		release();
	});

	it('a shot that outlasts step 8 skips settings and says so', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		const locks = new FakeLocks();
		captureMessages(locks, (m) => {
			if (m.type === 'started') void locks.hold(MACHINE_BUSY_LOCK, 'shared');
		});
		const out = await runRestore(prepared, 'wipe', {}, deps(locks, { machineWaitMs: 30 }));
		expect(out).toMatchObject({ kind: 'done', singletonsSkipped: true, settingsApplied: false });
		expect(outcomeMessage(out)).toContain("settings and maintenance weren't changed");
		expect(getBeanStore().beans.map((b) => b.id)).toEqual(['b1']);
	});

	it('without Web Locks a restore still runs, one at a time', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		captureMessages(new FakeLocks());
		const first = runRestore(prepared, 'merge', {}, deps(undefined));
		const second = await runRestore(prepared, 'merge', {}, deps(undefined));
		expect(second.kind).toBe('busy');
		expect((await first).kind).toBe('done');
	});
});

describe('the plan, written per store', () => {
	it('Merge adds what is missing and keeps every local row as it is', async () => {
		const prepared = await backupOf(['b1', 'b2'], ['s1']);
		getBeanStore().bulkAdd([{ ...blankBean('b1'), name: 'Mine' }, { ...blankBean('b9'), name: 'Only here' }], []);
		captureMessages(new FakeLocks());
		const out = await runRestore(prepared, 'merge', {}, deps(new FakeLocks()));
		expect(out.kind).toBe('done');
		const beans = getBeanStore();
		expect(beans.getBean('b1')?.name).toBe('Mine');
		expect(beans.getBean('b2')?.name).toBe('Bean b2');
		expect(beans.getBean('b9')).not.toBeNull();
	});

	it('Replace removes by name and leaves the deletes to the marker', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		getHistoryStore().insertPulled(shot('s7'));
		captureMessages(new FakeLocks());
		const plan = await planNow(prepared, 'wipe', false, new FakeLocks());
		expect(plan.verdict).toBe(Verdict.Confirm);
		expect(plan.shots.remove).toEqual(['s7']);
		const out = await runRestore(prepared, 'wipe', { expected: plan }, deps(new FakeLocks()));
		expect(out.kind).toBe('done');
		expect(getHistoryStore().rawAll.map((s) => s.id)).toEqual(['s1']);
		expect(readMarker()).toMatchObject({ phase: 'finishing', mode: 'replace', deletes: { shots: ['s7'] } });
	});

	it('a shot from elsewhere on a bean already here pays nothing from this bag', async () => {
		const prepared = await backupOf(['b1', 'b2'], ['s1', 's2'], (id) => (id === 's1' ? 'b1' : 'b2'));
		getBeanStore().bulkAdd([{ ...blankBean('b1'), name: 'Mine' }], []);
		captureMessages(new FakeLocks());
		await runRestore(prepared, 'merge', {}, deps(new FakeLocks()));
		expect(getHistoryStore().get('s1')?.bagDebit).toBe(0);
		expect(getHistoryStore().get('s2')?.bagDebit).toBe(18);
	});

	it('Merge fills a curve this device lost, and nothing else of the row', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		getHistoryStore().insertPulled(shot('s1', { record: { duration: 0, samples: [] }, metadata: { ...metadata, dose: 20 } }));
		captureMessages(new FakeLocks());
		const plan = await planNow(prepared, 'merge', false, new FakeLocks());
		expect(plan.shots.fill).toEqual(['s1']);
		await runRestore(prepared, 'merge', { expected: plan }, deps(new FakeLocks()));
		const s1 = getHistoryStore().get('s1')!;
		expect(s1.record.samples).toHaveLength(1);
		expect(s1.metadata.dose).toBe(20);
	});

	it('a shot deleted here stays deleted, unless the user brings it back', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		getHistoryStore().insertPulled(shot('s1', { visualizerId: 'v1' } as never));
		getHistoryStore().delete('s1', { deleteRemote: true });
		enqueueEntry({ entity: 'shot', id: 's1', op: 'delete', visualizerId: 'v1' });
		captureMessages(new FakeLocks());
		const kept = await planNow(prepared, 'merge', false, new FakeLocks());
		expect(kept.shots.deletedHere).toEqual(['s1']);
		expect(confirmText(kept).message).toContain('deleted here and stays deleted');
		await runRestore(prepared, 'merge', { expected: kept }, deps(new FakeLocks()));
		expect(getHistoryStore().get('s1')?.deletedAt).toBeTypeOf('number');

		const back = await planNow(prepared, 'merge', true, new FakeLocks());
		await runRestore(prepared, 'merge', { reviveDeleted: true, expected: back }, deps(new FakeLocks()));
		expect(getHistoryStore().get('s1')?.deletedAt ?? null).toBeNull();
		expect(readQueue().entries.some((e) => e.id === 's1' && e.op === 'delete')).toBe(false);
	});

	it('a device that changed while the report was open runs nothing and shows the new numbers', async () => {
		const prepared = await backupOf(['b1'], ['s1']);
		captureMessages(new FakeLocks());
		const seen = await planNow(prepared, 'merge', false, new FakeLocks());
		getBeanStore().bulkAdd([{ ...blankBean('b1'), name: 'Added meanwhile' }], []);
		const out = await runRestore(prepared, 'merge', { expected: seen }, deps(new FakeLocks()));
		expect(out.kind).toBe('changed');
		expect(getBeanStore().getBean('b1')?.name).toBe('Added meanwhile');
		expect(getHistoryStore().all).toEqual([]);
		expect(readMarker()).toBeNull();
	});

	it('Replace refuses a backup with nothing in it on a device that has rows', async () => {
		resetAll();
		const prepared = prepareBackup('{"kind":"crema-backup/v1","createdAt":1,"beanCount":0}\n', 'empty');
		getBeanStore().bulkAdd([{ ...blankBean('b1'), name: 'Mine' }], []);
		const plan = await planNow(prepared, 'wipe', false, new FakeLocks());
		expect(plan.verdict).toBe(Verdict.Refused);
		expect(refusalMessage(plan)).toContain('Replace would empty this device');
		expect(plan.mode).toBe(RestoreMode.Replace);
	});
});

describe('the marker', () => {
	const bootDeps = (over: Partial<Parameters<typeof checkMarkerAtBoot>[0]> = {}) => {
		const deleted: string[] = [];
		return {
			deleted,
			deps: {
				restoreRunning: async () => false,
				listedShots: () => new Set(['kept']),
				listedBeans: () => new Set<string>(),
				shotsHealth: () => 'ok',
				beansHealth: () => 'ok',
				deleteSeries: async (id: string) => void deleted.push(`series:${id}`),
				deleteCapture: async (id: string) => void deleted.push(`capture:${id}`),
				deletePhoto: async (id: string) => void deleted.push(`photo:${id}`),
				...over
			}
		};
	};

	function finishing(at: number, shots: string[], photos: string[] = []): void {
		beginMarker('replace', 'b', { shots, photos }, at);
		const m = readMarker()!;
		localStorage.setItem(MARKER_KEY, JSON.stringify({ ...m, phase: 'finishing', finishingAt: at }));
	}

	it('waits an hour after finishing, then deletes only what the rows no longer list', async () => {
		finishing(1_000, ['gone', 'kept'], ['bean-gone']);
		const early = bootDeps({ now: 1_000 + DELETE_AFTER_MS - 1 });
		expect((await checkMarkerAtBoot(early.deps)).kind).toBe('waiting');
		expect(early.deleted).toEqual([]);

		const late = bootDeps({ now: 1_000 + DELETE_AFTER_MS });
		expect(await checkMarkerAtBoot(late.deps)).toEqual({ kind: 'deleted', shots: 1, photos: 1 });
		expect(late.deleted).toEqual(['series:gone', 'capture:gone', 'photo:bean-gone']);
		expect(readMarker()).toBeNull();
	});

	it('runs no deletes while the rows did not load ok, or a restore runs', async () => {
		finishing(1_000, ['gone']);
		const corrupt = bootDeps({ now: 1_000 + DELETE_AFTER_MS, shotsHealth: () => 'damaged' });
		await checkMarkerAtBoot(corrupt.deps);
		expect(corrupt.deleted).toEqual([]);
		expect(readMarker()).not.toBeNull();
		const running = bootDeps({ now: 1_000 + DELETE_AFTER_MS, restoreRunning: async () => true });
		expect((await checkMarkerAtBoot(running.deps)).kind).toBe('waiting');
		expect(running.deleted).toEqual([]);
	});

	it('an interrupted restore says so; a re-run keeps the deletes the first one owed', async () => {
		beginMarker('replace', 'Pixel backup', { shots: ['a'], photos: [] }, 1);
		expect(await checkMarkerAtBoot(bootDeps().deps)).toEqual({ kind: 'interrupted', backupName: 'Pixel backup' });
		beginMarker('replace', 'Pixel backup', { shots: ['b'], photos: [] }, 2);
		expect(readMarker()?.deletes.shots).toEqual(['a', 'b']);
	});
});

describe('nothing to back up', () => {
	it('a fresh install has nothing to back up, though its first boot filled the hot-water fields', async () => {
		resetAll();
		expect(await buildBackupJsonl()).toBeNull();
		getSettingsStore().set('weightUnit', 'oz' as never);
		expect(await buildBackupJsonl()).not.toBeNull();
	});
});
