/**
 * QuickStepper's click-to-type with a `parse` (the grind, #488): the box
 * takes text, starts from the formatted value ("4⅓"), and a typed "4 1/3"
 * reads through the core as a number; without `parse` it stays a number
 * input.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { flushSync, mount, tick, unmount, type ComponentProps } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import { DEFAULT_GRIND_SCALE, grindInputMode, grindNumber, grindText, type GrindScale } from '#lib/bean/grinder.js';
import QuickStepper from './QuickStepper.svelte';

beforeAll(async () => {
	await initTestWasm();
});

const ODE: GrindScale = { min: 1, max: 11, step: Math.fround(1 / 3), unit: '' };

let component: ReturnType<typeof mount> | null = null;

afterEach(() => {
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
});

function render(props: ComponentProps<typeof QuickStepper>): HTMLElement {
	const target = document.body.appendChild(document.createElement('div'));
	component = mount(QuickStepper, { target, props });
	flushSync();
	return target;
}

async function edit(root: HTMLElement): Promise<HTMLInputElement> {
	root.querySelector<HTMLButtonElement>('.qcs-num-btn')!.click();
	flushSync();
	await tick();
	return root.querySelector<HTMLInputElement>('.qcs-num-input')!;
}

describe('QuickStepper with a grind parse', () => {
	const grindProps = (onChange: (n: number) => void) => ({
		name: 'Grind',
		value: grindNumber('4⅓'),
		min: ODE.min,
		max: ODE.max,
		step: ODE.step,
		precision: 6,
		clampTyped: false,
		onChange,
		fmt: (v: number) => grindText(v, ODE),
		parse: grindNumber,
		inputmode: grindInputMode(ODE)
	});

	it('shows thirds and steps onto the next one', () => {
		const onChange = vi.fn();
		const root = render(grindProps(onChange));
		expect(root.querySelector('.qcs-num-btn')!.textContent?.trim()).toBe('4⅓');
		root.querySelector<HTMLButtonElement>('[aria-label="Increase Grind"]')!.click();
		expect(grindText(onChange.mock.calls[0][0] as number, ODE)).toBe('4⅔');
	});

	it('starts typing from "4⅓" and reads a typed "4 1/3" or "5⅔" as a number', async () => {
		const onChange = vi.fn();
		const root = render(grindProps(onChange));
		let input = await edit(root);
		expect(input.type).toBe('text');
		expect(input.value).toBe('4⅓');
		input.value = '5⅔';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
		flushSync();
		expect(grindText(onChange.mock.calls.at(-1)![0] as number, ODE)).toBe('5⅔');

		input = await edit(root);
		input.value = '6 1/3';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
		flushSync();
		expect(grindText(onChange.mock.calls.at(-1)![0] as number, ODE)).toBe('6⅓');
	});

	it('ignores typed text that is not a number', async () => {
		const onChange = vi.fn();
		const root = render(grindProps(onChange));
		const input = await edit(root);
		input.value = '6 + a tooth';
		input.dispatchEvent(new Event('input', { bubbles: true }));
		input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
		flushSync();
		expect(onChange).not.toHaveBeenCalled();
	});

	it('opens the full keyboard on a thirds scale and the number pad on any other', async () => {
		expect(grindInputMode(ODE)).toBe('text');
		expect(grindInputMode(DEFAULT_GRIND_SCALE)).toBe('decimal');
		expect(grindInputMode({ min: 0, max: 999, step: 1, unit: 'μm' })).toBe('decimal');
		expect(grindInputMode(null)).toBe('decimal');

		let root = render(grindProps(vi.fn()));
		expect((await edit(root)).inputMode, 'Fellow Ode').toBe('text');
		unmount(component!);
		document.body.innerHTML = '';
		root = render({
			...grindProps(vi.fn()),
			value: 4.2,
			step: 0.1,
			fmt: (v: number) => grindText(v, DEFAULT_GRIND_SCALE),
			inputmode: grindInputMode(DEFAULT_GRIND_SCALE)
		});
		const input = await edit(root);
		expect(input.inputMode, 'a decimal scale').toBe('decimal');
		expect(input.value).toBe('4.2');
	});

	it('stays a number input without a parse', async () => {
		const root = render({ name: 'Dose', value: 18, min: 0, max: 30, step: 0.1, onChange: vi.fn() });
		expect((await edit(root)).type).toBe('number');
	});
});
