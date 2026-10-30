/**
 * F10: a shot history moves between the shells as the core's Crema
 * `crema/v1` JSONL. The fixtures live in `core/de1-domain/tests/fixtures/shots/`:
 * `web-shots.crema.jsonl` was written by {@link shotHistoryJsonl} (the
 * Settings → Sharing export) from a shot this test records through the real
 * `CremaApp` and wasm core, and `android-shots.crema.jsonl` by Android's
 * `shotHistoryJsonl` from a shot recorded the same way
 * (`CoreShotAssemblyTest.kt`). Both import through `CremaApp.importShotFile`,
 * the History page's import. Regenerate with `CREMA_REGEN_SHOT_FIXTURES=1 pnpm
 * vitest run src/lib/history/shot-cross-shell.vitest.ts`.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { CoreOutput, CremaCore, NotificationSource } from '#lib/core/index.js';
import { isCremaJsonl, shotHistoryJsonl, shotsFromCremaJsonl } from './crema-jsonl';

const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures/shots');
const fixture = (name: string) => readFileSync(resolve(FIXTURES, name), 'utf8');

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
	const { getHistoryStore } = await import('./store.svelte');
	getHistoryStore().clearAllShots();
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
	return { app, pour, history: getHistoryStore(), settings: getSettingsStore() };
}

/** A shot recorded through the core, with the edits a user makes afterwards. */
async function recordedShot() {
	const t = await makeApp();
	t.settings.set('grinderModel', 'EK43');
	t.settings.set('baristaName', 'Sam');
	await t.pour(1_000, 48);
	const shot = {
		...t.history.all[0],
		tags: ['dial-in'],
		bean: { name: 'Gesha', roasterName: 'Onyx', roastedOn: '2026-10-01', roastLevel: 3 }
	};
	return { t, shot };
}

const fileOf = (text: string, name: string) => new File([text], name);

describe('shot history between the shells (F10)', () => {
	it.runIf(process.env.CREMA_REGEN_SHOT_FIXTURES === '1')('regenerates the web fixture', async () => {
		const { shot } = await recordedShot();
		writeFileSync(
			resolve(FIXTURES, 'web-shots.crema.jsonl'),
			shotHistoryJsonl([shot], 1_790_000_000_000, 'fixture')
		);
	});

	it('the history export is crema/v1 JSONL and re-imports whole', async () => {
		const { t, shot } = await recordedShot();
		const text = shotHistoryJsonl([shot], 1_790_000_000_000, 'test');
		expect(isCremaJsonl(text)).toBe(true);
		const [back] = shotsFromCremaJsonl(text);
		expect(back.id).toBe(shot.id);
		expect(back.record.samples).toEqual(shot.record.samples);
		expect(back.tags).toEqual(['dial-in']);
		expect(back.bean?.name).toBe('Gesha');
		// Through the History import: already there, so nothing is added.
		t.history.insertPulled(shot);
		const again = await t.app.importShotFile(fileOf(text, 'crema-history.crema.jsonl'));
		expect(again.imported).toBe(0);
	});

	it('an Android shot export imports on web', async () => {
		const t = await makeApp();
		const result = await t.app.importShotFile(
			fileOf(fixture('android-shots.crema.jsonl'), 'android-shots.crema.jsonl')
		);
		expect(result.error).toBeNull();
		expect(result.imported).toBe(1);
		const shot = t.history.all[0];
		expect(shot.record.samples).toHaveLength(48);
		// DE1 frames count from 0: the first frame survives the trip.
		expect(shot.record.samples.slice(0, 4).map((s) => s.sample.frameNumber)).toEqual([0, 2, 2, 3]);
		expect(shot.grinderModel).toBe('EK43');
		expect(shot.metadata.barista).toBe('Sam');
		expect(shot.tags).toEqual(['dial-in']);
		expect(shot.bean?.name).toBe('Gesha');
		expect(shot.bean?.roasterName).toBe('Onyx');
		// Re-importing the same file adds nothing.
		const again = await t.app.importShotFile(
			fileOf(fixture('android-shots.crema.jsonl'), 'android-shots.crema.jsonl')
		);
		expect(again.imported).toBe(0);
		expect(t.history.all).toHaveLength(1);
	});

	it('the committed web shot export imports on web', async () => {
		const t = await makeApp();
		const result = await t.app.importShotFile(
			fileOf(fixture('web-shots.crema.jsonl'), 'web-shots.crema.jsonl')
		);
		expect(result.imported).toBe(1);
		const shot = t.history.all[0];
		expect(shot.record.samples).toHaveLength(48);
		expect(shot.record.samples.slice(0, 4).map((s) => s.sample.frameNumber)).toEqual([0, 2, 2, 3]);
		expect(shot.grinderModel).toBe('EK43');
		expect(shot.tags).toEqual(['dial-in']);
	});
});
