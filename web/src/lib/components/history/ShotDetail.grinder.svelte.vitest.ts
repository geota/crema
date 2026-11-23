/**
 * The connected grinder in a shot's detail (spec grinder-ble §4.2, §7): what
 * the grinder reported, "Edited" when the grind differs, and the RPM field
 * for a shot with a speed, whose edit goes to the page.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import type { StoredShot } from '#lib/history/index.js';
import ShotDetail from './ShotDetail.svelte';
import WithAppContext from '#lib/testing/WithAppContext.svelte';

vi.mock('#lib/shell/phone.svelte.js', () => ({ phoneLayout: { current: true } }));
Element.prototype.animate = vi.fn(() => ({ cancel: vi.fn(), finish: vi.fn() }) as unknown as Animation);

beforeAll(async () => {
	await initTestWasm();
});

const shotOf = (over: Partial<StoredShot>): StoredShot =>
	({
		id: 'shot:a',
		profileName: 'Profile',
		completedAt: 1_790_000_000_000,
		metadata: { notes: '', rating: 0, grinderSetting: '470' },
		record: { duration: 30_000, samples: [] },
		bean: null,
		tags: [],
		grinderModel: 'Motto 80',
		...over
	}) as unknown as StoredShot;

let host: ReturnType<typeof mount> | null = null;

afterEach(() => {
	if (host) unmount(host);
	host = null;
	document.body.innerHTML = '';
});

function show(shot: StoredShot) {
	const onGrindRpmChange = vi.fn();
	const noop = vi.fn();
	host = mount(WithAppContext, {
		target: document.body,
		props: {
			app: null,
			component: ShotDetail,
			props: {
				shot,
				onNotesChange: noop,
				onNextPlanChange: noop,
				onRatingChange: noop,
				onPrivacyChange: noop,
				onGrinderModelChange: noop,
				onPersonChange: noop,
				onTagsChange: noop,
				onGrindChange: noop,
				onBeanChange: noop,
				onGrindRpmChange
			}
		}
	});
	flushSync();
	return { onGrindRpmChange };
}

describe('the connected grinder in the shot detail', () => {
	it("shows what the grinder said, Edited when the grind differs, and the shot's speed", async () => {
		const t = show(
			shotOf({
				grindRpm: 700,
				grinderObserved: { source: 'LastGrind', setting: '480', grindRpm: 700, grindMs: 9_800 }
			})
		);
		const line = document.querySelector('[data-testid="grinder-observed"]')!.textContent;
		expect(line).toContain('480 · 700 rpm · 9.8 s grind · from Motto 80');
		expect(line).toContain('Edited (grinder said 480)');
		const rpm = document.querySelector<HTMLInputElement>('.hi-rpm-input')!;
		expect(rpm.value).toBe('700');
		rpm.value = '800';
		rpm.dispatchEvent(new Event('change'));
		await Promise.resolve();
		expect(t.onGrindRpmChange).toHaveBeenCalledWith('shot:a', 800);
	});

	it('a shot with no grinder speed and no observation shows neither', () => {
		show(shotOf({}));
		expect(document.querySelector('[data-testid="grinder-observed"]')).toBeNull();
		expect(document.querySelector('.hi-rpm-input')).toBeNull();
	});
});
