/**
 * F10 PR 2: the web shell records shots through the core's assembly
 * (`CremaCore.takeStoredShot`) — the core's record (frames, overlays), its
 * rules (grinder precedence, the as-run values, the stop switches) — and the
 * v2 export is the core's, `metadata.crema` included. Drives the real
 * `CremaApp` and the real wasm core.
 */
import { flushSync } from 'svelte';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CoreOutput, CremaCore, NotificationSource } from '#lib/core/index.js';
import { p3RowsOf } from '#lib/history/p3-rows.js';

/** The core's own test `ShotSample`; byte 17 is the frame number. */
function sampleIn(frame: number): Uint8Array {
	const s = new Uint8Array([
		0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5c, 0x80, 0x00, 0x58, 0x00, 0x5a, 0x00, 0x5d, 0x00,
		0x60, 0x40, 0x03, 0x96
	]);
	s[17] = frame;
	return s;
}

let core: CremaCore;

beforeAll(async () => {
	await initTestWasm();
	core = await (await import('#lib/core/index.js')).loadCore();
});

beforeEach(async () => {
	localStorage.clear();
	await core.reset();
});

afterEach(async () => {
	const { getToasts, dismissToast } = await import('#lib/components/shared/toast.svelte.js');
	for (const t of getToasts()) dismissToast(t.id);
});

async function makeApp() {
	const { CremaApp } = await import('#lib/state/app.svelte.js');
	const { getHistoryStore } = await import('./store.svelte');
	const { getSettingsStore } = await import('#lib/settings/index.js');
	const app = new CremaApp(core, null);
	const internals = app as unknown as { applyCoreOutput(out: CoreOutput): void };
	const feed = async (source: NotificationSource, bytes: Uint8Array, atMs: number) =>
		internals.applyCoreOutput(await core.onNotification(source, bytes, atMs));
	/** A pour through frames 0 → 2 → 3, `count` samples 250 ms apart, then Idle. */
	const pour = async (start: number, count: number) => {
		await feed('De1State', new Uint8Array([4, 5]), start);
		for (let i = 1; i <= count; i++) {
			await feed('De1ShotSample', sampleIn([0, 2, 2, 3][Math.min(i - 1, 3)]), start + 250 * i);
		}
		await feed('De1State', new Uint8Array([2, 0]), start + 250 * (count + 1));
	};
	return { app, feed, pour, history: getHistoryStore(), settings: getSettingsStore() };
}

describe('the web records shots through the core', () => {
	it('AC-F10.9 a shot is recorded through the core assembly', async () => {
		const t = await makeApp();
		// The settings grinder is not the shot's: the active grinder is.
		t.settings.set('grinderModel', 'Old setting');
		const { getBeanStore } = await import('#lib/bean/index.js');
		const ek43 = getBeanStore().addGrinder('EK43')!;
		getBeanStore().setActiveGrinder(ek43.id);
		// The app hands the selections to the core from an effect.
		flushSync();
		t.settings.set('baristaName', 'Sam');
		// A real pour: 48 samples × 250 ms = 12 s, so it isn't an aborted pull.
		await t.pour(1_000, 48);
		const shot = (await t.history.loadShot(p3RowsOf(t.history).live[0].id))!;
		expect(shot, 'a recorded shot').toBeDefined();
		// The core's record: its frames and the overlays it filled live.
		expect(shot.record.samples.slice(0, 4).map((s) => s.sample.frameNumber)).toEqual([0, 2, 2, 3]);
		expect(shot.record.samples.every((s) => s.dispensedVolume != null)).toBe(true);
		// The core's rules: the grinder frozen at shot start (the active one,
		// handed in with the selections); its stop switches.
		expect(shot.grinderModel).toBe('EK43');
		expect(shot.metadata.barista).toBe('Sam');
		expect(shot.stopOnWeight).toBeTypeOf('boolean');
		expect(shot.autoTare).toBeTypeOf('boolean');
	});

	it('a group-head start records Brew\'s grind, frozen at shot start (follow-up 26, REVIEW-CORE H3)', async () => {
		const t = await makeApp();
		const { getBrewGrind } = await import('#lib/brew/grind-session.svelte.js');
		getBrewGrind().set('14');
		flushSync();
		// `pour` reports Espresso with no app start: the group head's.
		await t.pour(1_000, 48);
		getBrewGrind().set(null);
		flushSync();
		const shot = (await t.history.loadShot(p3RowsOf(t.history).live[0].id))!;
		expect(shot.metadata.grinderSetting).toBe('14');
	});

	it('AC-F10.10 an undone discard records the core’s shot', async () => {
		const t = await makeApp();
		const { getToasts } = await import('#lib/components/shared/toast.svelte.js');
		// A Bookoo reading ~1 g through a 2 s pour: short AND light, so the
		// core classifies an aborted pull and the commit holds it for undo.
		core.setAutoTare(false);
		await core.connectScale('BOOKOO_SC', []);
		const bookoo = (centigrams: number) => {
			const p = new Uint8Array(20);
			p.set([0x03, 0x0b, 0, 0, 0, 0, 0x2b, 0, (centigrams >> 8) & 0xff, centigrams & 0xff]);
			p[19] = p.slice(0, 19).reduce((a, b) => a ^ b, 0);
			return p;
		};
		const before = p3RowsOf(t.history).live.length; // the store is an app-wide singleton
		await t.feed('ScaleWeight', bookoo(100), 900);
		await t.feed('De1State', new Uint8Array([4, 5]), 1_000);
		for (let i = 1; i <= 8; i++) {
			await t.feed('ScaleWeight', bookoo(100 + i), 1_000 + 250 * i - 10);
			await t.feed('De1ShotSample', sampleIn([0, 2, 2, 3][Math.min(i - 1, 3)]), 1_000 + 250 * i);
		}
		await t.feed('De1State', new Uint8Array([2, 0]), 3_500);
		// The drip settle ends at its backstop.
		const internals = t.app as unknown as { applyCoreOutput(out: CoreOutput): void };
		internals.applyCoreOutput(await core.onTick(7_000));
		expect(p3RowsOf(t.history).live).toHaveLength(before);
		const undo = getToasts().find((x) => x.action?.label === 'Undo');
		expect(undo, 'the undo toast').toBeDefined();
		undo!.action!.run();
		expect(p3RowsOf(t.history).live).toHaveLength(before + 1);
		expect((await t.history.loadShot(p3RowsOf(t.history).live[0].id))!.record.samples.map((s) => s.sample.frameNumber)).toEqual([
			0, 2, 2, 3, 3, 3, 3, 3
		]);
		await core.disconnectScale();
	});

	it('AC-F10.11 the v2 export is the core’s, crema block included', async () => {
		const { exportStoredShotAsV2Json } = await import('./v2-export');
		const t = await makeApp();
		await t.pour(1_000, 48);
		const shot = {
			...(await t.history.loadShot(p3RowsOf(t.history).live[0].id))!,
			tags: ['dial-in'],
			yieldTarget: 36,
			brewTempTarget: 93.5,
			preinfuseTarget: 8,
			bean: { name: 'Gesha', roastedOn: '2026-10-01', roastLevel: 3, notes: 'jasmine' }
		};
		const doc = JSON.parse(exportStoredShotAsV2Json(shot));
		expect(doc.metadata.crema).toMatchObject({
			tags: ['dial-in'],
			yield_target: 36,
			brew_temp_target: 93.5,
			preinfuse_target: 8,
			bean_roasted_on: '2026-10-01',
			bean_roast_level: 3,
			bean_notes: 'jasmine'
		});
	});
});
