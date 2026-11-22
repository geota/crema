/**
 * The phone Quick Controls sheet's Save is the same Save as the tablet's
 * (save-boundaries review F1): labelled "Save", off when nothing changed,
 * and "Save as new…" beside it when Save would update a custom profile.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import { BrewParamState } from './brew-params.svelte';
import PhoneQuickSheet from './PhoneQuickSheet.svelte';

describe('the phone sheet’s Save', () => {
	let component: ReturnType<typeof mount> | null = null;
	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(extra: Record<string, unknown>): HTMLElement {
		const target = document.body.appendChild(document.createElement('div'));
		const noop = (): void => {};
		component = mount(PhoneQuickSheet, {
			target,
			props: {
				params: new BrewParamState(() => ({ dose: 18, yield: 36, brewTemp: 93, preinf: 8 })),
				open: true,
				onClose: noop,
				yieldTargetOn: true,
				onToggleYieldTarget: noop,
				onToggleStopOnWeight: noop,
				onToggleAutoTare: noop,
				onToggleSteamEco: noop,
				...extra
			}
		});
		flushSync();
		return document.body;
	}
	const labels = (root: HTMLElement): string[] =>
		[...root.querySelectorAll('button')].map((b) => b.textContent?.trim() ?? '');

	it('reads Save, and is off when there is nothing to save', () => {
		const onSave = vi.fn();
		const root = render({ onSave, saveEnabled: false });
		expect(labels(root)).toContain('Save');
		expect(labels(root)).not.toContain('Save profile');
		const save = [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save')!;
		expect(save.disabled).toBe(true);
	});

	it('offers Save as new beside Save', () => {
		const onSaveAsNew = vi.fn();
		const root = render({ onSave: vi.fn(), saveEnabled: true, onSaveAsNew });
		const asNew = [...root.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Save as new…')!;
		asNew.click();
		expect(onSaveAsNew).toHaveBeenCalled();
	});
});
