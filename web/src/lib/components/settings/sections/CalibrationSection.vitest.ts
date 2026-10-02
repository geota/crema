import { afterEach, describe, expect, it } from 'vitest';
import { flushSync, mount, unmount } from 'svelte';
import CalibrationSection, {
	SHOW_CALIBRATION_FACTORY_RESET
} from './CalibrationSection.svelte';

/**
 * The temperature / pressure "Reset to factory" buttons send DE1
 * calibration command 2, which no reference app sends and Decent has not
 * documented — they stay hidden behind `SHOW_CALIBRATION_FACTORY_RESET`
 * until the command is verified. Read + Apply flows stay rendered.
 */
describe('CalibrationSection factory reset', () => {
	let component: ReturnType<typeof mount> | null = null;

	afterEach(() => {
		if (component) unmount(component);
		component = null;
		document.body.innerHTML = '';
	});

	function render(): HTMLElement {
		const target = document.createElement('div');
		document.body.appendChild(target);
		component = mount(CalibrationSection, { target, props: { app: null } });
		flushSync();
		return target;
	}

	function buttonLabels(root: HTMLElement): string[] {
		return [...root.querySelectorAll('button')].map((b) => b.textContent?.trim() ?? '');
	}

	it('keeps the flag off', () => {
		expect(SHOW_CALIBRATION_FACTORY_RESET).toBe(false);
	});

	it('does not render the command-2 Reset to factory controls', () => {
		const root = render();
		const labels = buttonLabels(root);
		// Temperature, Pressure, Flow each keep Apply.
		expect(labels.filter((l) => l === 'Apply')).toHaveLength(3);
		// Only the Flow row's reset (an MMR write of 1.000, not cmd 2) remains.
		const resets = [...root.querySelectorAll('button')].filter(
			(b) => b.textContent?.trim() === 'Reset to factory'
		);
		expect(resets).toHaveLength(1);
		expect(resets[0].closest('.cal-control')?.querySelector('input[aria-label="Flow calibration multiplier"]')).not.toBeNull();
		expect(labels).toContain('Refresh');
		expect(root.textContent).not.toContain('Reset to factory restores the original');
	});
});
