/**
 * #310 P3w, SPEC §2's `ready` table: code that acts on the rows waits for
 * them, and when they don't arrive it skips, so "not loaded" is never read as
 * "empty". Each reader runs against the real history store held mid-load
 * (the gate gives up after 30 ms here, 30 s in the app).
 */
import 'fake-indexeddb/auto';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { initTestWasm } from '#lib/testing/test-init.js';
import { realSamples, realShot } from '#lib/testing/real-shots.js';
import { getHistoryStore, setHistoryStoreDefaultsForTest } from './store.svelte';
import { getCaptureStore } from '#lib/capture/store.js';
import { capturesOverLimit, pruneCaptures } from '#lib/capture/prune.js';
import { backupContents, buildBackupJsonl, buildBackupFile } from '#lib/backup/index.js';
import { buildLocalState } from '#lib/restore/engine.js';
import { checkRestoreMarker } from '#lib/restore/boot.js';
import { beginMarker, finishMarker, readMarker } from '#lib/restore/marker.js';

// The rows never arrive: the boot is held for good.
setHistoryStoreDefaultsForTest({
	migrationFault: () => new Promise(() => {}),
	readyTimeoutMs: 30,
	locks: null,
	channel: null,
	visibility: false
});

beforeAll(async () => {
	await initTestWasm();
});
beforeEach(() => {
	localStorage.clear();
});

describe('readers wait for the rows, and skip when they don’t come', () => {
	it('the history is still loading', () => {
		const history = getHistoryStore();
		expect(history.loaded).toBe(false);
		expect(history.health).toBe('loading');
	});

	it('capture prune: deletes no capture', async () => {
		await getCaptureStore().put('a-capture', []);
		expect(await capturesOverLimit(getHistoryStore(), 'off')).toEqual([]);
		expect(await pruneCaptures(getHistoryStore(), 'off')).toEqual([]);
		expect(await getCaptureStore().keys()).toEqual(['a-capture']);
	});

	it('backups: refuse, rather than write an empty one, and count no shots', async () => {
		await expect(buildBackupJsonl('test')).rejects.toThrow(/still loading/);
		await expect(buildBackupFile('test')).rejects.toThrow(/still loading/);
		expect(backupContents().shots).toBe(0);
	});

	it('restore: plans nothing over rows it hasn’t got', async () => {
		await expect(buildLocalState(undefined)).rejects.toMatchObject({ name: 'HistoryNotReady' });
	});

	it('the restore marker’s deletes: wait, and the marker stays', async () => {
		beginMarker('replace', 'b.crema.zip', { shots: ['gone'], photos: [] }, 0);
		finishMarker(0);
		expect((await checkRestoreMarker()).kind).toBe('waiting');
		expect(readMarker()).not.toBeNull();
	});

	it('a file of shots: imports nothing, and says why', async () => {
		const core = await (await import('#lib/core/index.js')).loadCore();
		const { CremaApp } = await import('#lib/state/app.svelte.js');
		const app = new CremaApp(core, null);
		const text = readFileSync(resolve(process.cwd(), '../core/de1-domain/tests/fixtures/shots/web-shots.crema.jsonl'), 'utf8');
		const out = await app.importShotFile(new File([text], 'crema-history.crema.jsonl'));
		expect(out).toMatchObject({ imported: 0, record: null, error: expect.stringMatching(/still loading/) });
	});

	it('a new shot made meanwhile is held, not written over the store', () => {
		const history = getHistoryStore();
		const made = history.recordCaptured(realShot(1, realSamples(4)) as unknown as Record<string, unknown>);
		expect(made).not.toBeNull();
		expect(history.loaded).toBe(false);
	});
});

describe('the Beanconqueror and Crema bean import', () => {
	let component: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	it('shows "still loading" instead of a preview whose duplicate check saw no shots', async () => {
		const BeanImportDialog = (await import('#lib/components/beans/BeanImportDialog.svelte')).default;
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(BeanImportDialog, { target, props: { onClose: () => {} } });
		flushSync();
		const input = target.querySelector<HTMLInputElement>('input[type="file"]')!;
		const file = new File(
			[readFileSync(resolve(process.cwd(), '../core/de1-domain/tests/fixtures/beans/android-beans.crema.jsonl'), 'utf8')],
			'crema-beans.crema.jsonl'
		);
		Object.defineProperty(input, 'files', { value: [file], configurable: true });
		input.dispatchEvent(new Event('change', { bubbles: true }));
		for (let i = 0; i < 50 && !/still loading/.test(target.textContent ?? ''); i++) {
			await new Promise((r) => setTimeout(r, 10));
			await tick();
		}
		expect(target.textContent).toMatch(/Shot history is still loading/);
	});
});
