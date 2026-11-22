/**
 * What a shot records comes from the core's frozen selections
 * (REVIEW-CORE H3): a replay hands the capture's selections to its shadow
 * core, so the replayed shot records the capture's bag, grinder and grind,
 * not Brew's; Brew's live selections reach the live core again when the
 * replay ends. The capture's META carries what the shot recorded, so a
 * shot replays as it was recorded. Drives the real `CremaApp` and wasm core.
 */
import { flushSync } from 'svelte';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CoreOutput, CremaCore, NotificationSource } from '#lib/core/index.js';
import { p3RowsOf } from '#lib/history/p3-rows.js';
import { parseCapture } from '#lib/replay/index.js';
import { blankBean } from '#lib/bean/index.js';
import type { StoredShot } from './model';

/** The core's own test `ShotSample`; byte 17 is the frame number. */
function sampleIn(frame: number): Uint8Array {
	const s = new Uint8Array([
		0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5c, 0x80, 0x00, 0x58, 0x00, 0x5a, 0x00, 0x5d, 0x00,
		0x60, 0x40, 0x03, 0x96
	]);
	s[17] = frame;
	return s;
}

const hex = (b: Uint8Array) => [...b].map((x) => x.toString(16).padStart(2, '0')).join('');

/** A capture of a 12 s pour, with `meta` as its at-shot-start META line. */
function captureText(meta: Record<string, unknown>): string {
	const lines: object[] = [{ t: 0, dir: 'in', src: 'META', hex: '', meta }];
	lines.push({ t: 1_000, dir: 'in', src: 'DE1_STATE', hex: '0405' });
	for (let i = 1; i <= 48; i++) {
		lines.push({ t: 1_000 + 250 * i, dir: 'in', src: 'DE1_SHOT_SAMPLE', hex: hex(sampleIn([0, 2, 2, 3][Math.min(i - 1, 3)])) });
	}
	lines.push({ t: 1_000 + 250 * 49, dir: 'in', src: 'DE1_STATE', hex: '0200' });
	return lines.map((l) => JSON.stringify(l)).join('\n');
}

let core: CremaCore;

beforeAll(async () => {
	await initTestWasm();
	core = await (await import('#lib/core/index.js')).loadCore();
});

beforeEach(async () => {
	localStorage.clear();
	await core.reset();
	const { getBeanStore } = await import('#lib/bean/index.js');
	getBeanStore().clearAll();
	const { getHistoryStore } = await import('./store.svelte');
	getHistoryStore().clearAllShots();
});

afterEach(async () => {
	const { getToasts, dismissToast } = await import('#lib/components/shared/toast.svelte.js');
	for (const t of getToasts()) dismissToast(t.id);
	const { getBrewGrind } = await import('#lib/brew/grind-session.svelte.js');
	getBrewGrind().set(null);
});

async function makeApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { getHistoryStore } = await import('./store.svelte');
	const { getBeanStore } = await import('#lib/bean/index.js');
	const { getBrewGrind } = await import('#lib/brew/grind-session.svelte.js');
	const app = new CremaApp(core, null);
	const internals = app as unknown as { applyCoreOutput(out: CoreOutput): void };
	const feed = async (source: NotificationSource, bytes: Uint8Array, atMs: number) =>
		internals.applyCoreOutput(await core.onNotification(source, bytes, atMs));
	/** A group-head pour, `count` samples 250 ms apart; `mid` runs after the first sample. */
	const pour = async (start: number, count: number, mid?: () => void) => {
		await feed('De1State', new Uint8Array([4, 5]), start);
		for (let i = 1; i <= count; i++) {
			await feed('De1ShotSample', sampleIn([0, 2, 2, 3][Math.min(i - 1, 3)]), start + 250 * i);
			if (i === 1) mid?.();
		}
		await feed('De1State', new Uint8Array([2, 0]), start + 250 * (count + 1));
	};
	const history = getHistoryStore();
	const newest = async (): Promise<StoredShot> => (await history.loadShot(p3RowsOf(history).live[0].id))!;
	return { app, feed, pour, history, newest, library: getBeanStore(), brewGrind: getBrewGrind() };
}

/** Brew: bag "House" (250 g, full) on the EK43 at grind 14. */
function brewSelects(t: Awaited<ReturnType<typeof makeApp>>) {
	const ek43 = t.library.addGrinder('EK43')!;
	t.library.setActiveGrinder(ek43.id);
	t.library.upsertBean({ ...blankBean('bean:house'), name: 'House', bagSize: 250, remaining: 250 });
	t.library.setActiveBean('bean:house');
	t.brewGrind.set('14');
	flushSync();
	return ek43;
}

describe('a shot records the selections the core froze at its start', () => {
	it('a group-head shot records Brew\'s bag, grinder and grind, and a change mid-shot does not reach it', async () => {
		const t = await makeApp();
		const ek43 = brewSelects(t);
		// A profile with a dose, so the shot takes it from its bag.
		const { getProfileStore } = await import('#lib/profiles/index.js');
		const profiles = getProfileStore();
		await profiles.ensureLoaded();
		profiles.setActive(profiles.all.find((p) => (p.dose ?? 0) > 0)!.id);
		flushSync();
		await t.pour(1_000, 48, () => {
			t.library.setActiveGrinder(null);
			t.brewGrind.set('20');
			flushSync();
		});
		const shot = await t.newest();
		expect(shot.metadata.grinderSetting).toBe('14');
		expect(shot.grinderId).toBe(ek43.id);
		expect(shot.grinderModel).toBe('EK43');
		expect(shot.bean?.beanId).toBe('bean:house');
		expect(t.library.getBean('bean:house')?.remaining, 'the recorded bag is debited').toBeLessThan(250);
	});

	it('a replay records the capture\'s bag, grinder and grind, not Brew\'s, and debits nothing', async () => {
		const t = await makeApp();
		brewSelects(t);
		const file = new File(
			[captureText({ profileName: 'Blooming', grinderModel: 'Niche Zero', bean: { name: 'Guji', roaster: 'Sey', grinderSetting: '2.25' } })],
			'cap.jsonl'
		);
		await t.app.replayCapture(file, { speed: 1_000 });
		const shot = await t.newest();
		expect(shot.metadata.grinderSetting).toBe('2.25');
		expect(shot.grinderModel).toBe('Niche Zero');
		expect(shot.grinderId ?? null).toBeNull();
		expect(shot.bean?.name).toBe('Guji');
		expect(shot.bean?.beanId ?? null).toBeNull();
		expect(t.library.getBean('bean:house')?.remaining).toBe(250);
	});

	it('Brew\'s changes during a replay reach the live core when it ends', async () => {
		const t = await makeApp();
		brewSelects(t);
		const file = new File([captureText({ grinderModel: 'Niche Zero' })], 'cap.jsonl');
		const replay = t.app.replayCapture(file, { speed: 20 });
		// Wait until the replay runs, then change Brew's grind under it.
		await expect.poll(() => t.app.state.current.replay?.phase).toBe('running');
		t.brewGrind.set('16');
		flushSync();
		await replay;
		const replayed = await t.newest();
		expect(replayed.metadata.grinderSetting ?? null, 'the change did not reach the replayed shot').toBeNull();
		// Then a live group-head shot records Brew's grind as it is now.
		await t.pour(100_000, 48);
		expect((await t.newest()).metadata.grinderSetting).toBe('16');
	});

	it('the capture\'s META carries what the shot recorded, so it replays the same', async () => {
		const { buildAtShotStartMeta } = await import('./shot-persistence');
		const { replayMetaToSelections } = await import('#lib/services/replay.js');
		const recorded = {
			id: 'shot:1',
			metadata: { grinderSetting: '2.25' },
			grinderModel: 'Niche Zero',
			grinderId: 'grinder:niche',
			bean: { beanId: 'bean:a', name: 'Guji', roasterName: 'Sey', notes: 'jammy' }
		} as unknown as StoredShot;
		const entry = buildAtShotStartMeta(5_000, recorded, { profileName: 'Blooming' })!;
		expect(entry.meta).toMatchObject({ profileName: 'Blooming', grinderModel: 'Niche Zero', bean: { name: 'Guji', grinderSetting: '2.25' } });
		const parsed = parseCapture(JSON.stringify(entry));
		expect(replayMetaToSelections(parsed.meta)).toMatchObject({
			bean: { name: 'Guji', roasterName: 'Sey', notes: 'jammy' },
			profileName: 'Blooming',
			grinderName: 'Niche Zero',
			grind: '2.25'
		});
	});

	it('a shot with no bag carries its grind in the META too (PLAN follow-up 36)', async () => {
		const { buildAtShotStartMeta } = await import('./shot-persistence');
		const { replayMetaToSelections } = await import('#lib/services/replay.js');
		const recorded = {
			id: 'shot:2',
			metadata: { grinderSetting: '4⅓' },
			grinderModel: 'Fellow Ode',
			bean: null
		} as unknown as StoredShot;
		const entry = buildAtShotStartMeta(5_000, recorded, {})!;
		expect(entry.meta).toMatchObject({ grinderModel: 'Fellow Ode', grind: '4⅓' });
		expect(entry.meta).not.toHaveProperty('bean');
		const selections = replayMetaToSelections(parseCapture(JSON.stringify(entry)).meta);
		expect(selections.bean).toBeUndefined();
		expect(selections.grind).toBe('4⅓');
	});
});
