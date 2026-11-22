/**
 * Log brew records what a shot records (REVIEW-FINAL S2, S4): the active
 * grinder's id and name, the grind on its scale (0 is a grind), and a bag
 * snapshot without the bag's legacy grind and grinder.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { blankBean, getBeanStore } from '#lib/bean/index.js';
import { getHistoryStore } from '#lib/history/store.svelte.js';
import type { AnyShot } from '#lib/history/model.js';
import LogBrewDialog from './LogBrewDialog.svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: false } }));
vi.mock('#lib/bean/bag-empty-prompt.js', () => ({ promptBagEmpty: () => {} }));
Element.prototype.animate = vi.fn(() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation);

beforeAll(async () => {
	await initTestWasm();
});

let host: ReturnType<typeof mount> | null = null;

beforeEach(() => {
	localStorage.clear();
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
});

afterEach(() => {
	if (host) unmount(host);
	host = null;
	document.body.innerHTML = '';
});

/** Open the dialog with `prefill`, press Save, and return the logged row. */
function logBrew(prefill: Record<string, unknown>): AnyShot {
	const saved = vi.fn<(shot: AnyShot) => void>();
	host = mount(WithAppContext, {
		target: document.body,
		props: { app: null, component: LogBrewDialog, props: { onClose: () => {}, onSaved: saved, prefill } }
	});
	flushSync();
	const save = [...document.querySelectorAll<HTMLButtonElement>('button.bl-btn-primary')].at(-1)!;
	save.click();
	flushSync();
	expect(saved).toHaveBeenCalledOnce();
	return saved.mock.calls[0][0];
}

describe('Log brew', () => {
	it('stamps the active grinder, as a shot does (REVIEW-FINAL S4)', () => {
		const library = getBeanStore();
		const ek43 = library.addGrinder('EK43')!;
		library.setActiveGrinder(ek43.id);
		const row = logBrew({ method: 'pourover', dose: 15, grinderSetting: '22', beanId: null });
		expect(row.grinderId).toBe(ek43.id);
		expect(row.grinderModel).toBe('EK43');
		expect(row.metadata.grinderSetting).toBe('22');
	});

	it('keeps a grind of 0', () => {
		const library = getBeanStore();
		const ek43 = library.addGrinder('EK43')!;
		library.setActiveGrinder(ek43.id);
		const row = logBrew({ method: 'pourover', dose: 15, grinderSetting: '0', beanId: null });
		expect(row.metadata.grinderSetting).toBe('0');
	});

	it('with no grinder stamps none, and an unset grind records none', () => {
		const row = logBrew({ method: 'pourover', dose: 15, beanId: null });
		expect(row.grinderId ?? null).toBeNull();
		expect(row.grinderModel ?? null).toBeNull();
		expect(row.metadata.grinderSetting ?? null).toBeNull();
	});

	it('snapshots the bag without its legacy grind and grinder (REVIEW-FINAL S2)', () => {
		const library = getBeanStore();
		library.upsertBean({ ...blankBean('bean:a'), name: 'A', grinderSetting: '14', grinder: 'Niche', notes: 'jammy' });
		const row = logBrew({ method: 'pourover', dose: 15, beanId: 'bean:a' });
		expect(row.bean?.beanId).toBe('bean:a');
		expect(row.bean?.notes).toBe('jammy');
		expect(row.bean).not.toHaveProperty('grinderSetting');
		expect(row.bean).not.toHaveProperty('grinder');
		expect(row.metadata.grinderSetting ?? null, 'the legacy field does not seed the grind').toBeNull();
	});
});
