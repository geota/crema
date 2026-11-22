/**
 * Brew's grind field takes a typed text grind (#28, the core's text-grind
 * rule): "6 + a tooth" is not a number, so it becomes Brew's grind as typed;
 * it shows as typed with no chips, editing it starts from it, the first + or
 * − goes to the scale's start from blank, and a typed number replaces it.
 */
import '#lib/testing/dom-stubs.js';
import { afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { flushSync, mount, tick, unmount } from 'svelte';
import { initTestWasm } from '#lib/testing/test-init.js';
import WithAppContext from '#lib/testing/WithAppContext.svelte';
import { grindText, type Grinder } from '#lib/bean/grinder.js';
import { grindView } from '#lib/brew/grind-view.js';
import { getBrewGrind, resetBrewGrindForTest } from '#lib/brew/grind-session.svelte.js';
import DoseGrindStepper from './DoseGrindStepper.svelte';
import { BrewParamState } from './brew-params.svelte';

beforeAll(async () => {
	await initTestWasm();
});

const grinder: Grinder = {
	id: 'grinder:garage',
	name: 'Garage Grinder',
	scale: { min: 0, max: 20, step: 0.5, unit: '' },
	updatedAt: 1
};

let component: ReturnType<typeof mount> | null = null;

beforeEach(() => {
	localStorage.clear();
	resetBrewGrindForTest();
});

afterEach(() => {
	if (component) unmount(component);
	component = null;
	document.body.innerHTML = '';
});

/** The Dose | Grind card on its Grind side, showing Brew's grind as it is now. */
function render(): HTMLElement {
	if (component) unmount(component);
	document.body.innerHTML = '';
	const params = new BrewParamState(() => ({ dose: 18, yield: 36, brewTemp: 93, preinf: 8 }));
	params.set('doseGrindMode', 'grind');
	const grind = grindView({ bag: null, grinder, brewGrind: getBrewGrind().value });
	const target = document.body.appendChild(document.createElement('div'));
	component = mount(WithAppContext, { target, props: { app: null, component: DoseGrindStepper, props: { params, grind } } });
	flushSync();
	return target;
}

/** Type `text` into the grind box and press Enter; answers what the box started with. */
async function typeGrind(root: HTMLElement, text: string): Promise<string> {
	root.querySelector<HTMLButtonElement>('.qcs-num-btn')!.click();
	flushSync();
	await tick();
	const input = root.querySelector<HTMLInputElement>('.qcs-num-input')!;
	const started = input.value;
	input.value = text;
	input.dispatchEvent(new Event('input', { bubbles: true }));
	input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
	flushSync();
	return started;
}

describe("Brew's grind field takes a text grind (#28)", () => {
	it('a typed "6 + a tooth" becomes Brew\'s grind as typed: no dial, no chips', async () => {
		getBrewGrind().set('6');
		let root = render();
		expect(root.querySelectorAll('.qchip').length, 'a number has chips').toBeGreaterThan(0);

		await typeGrind(root, '  6 + a tooth ');
		expect(getBrewGrind().value).toBe('6 + a tooth');

		root = render();
		expect(root.querySelector('.qcs-num-btn')!.textContent?.trim()).toBe('6 + a tooth');
		expect(root.querySelectorAll('.qchip').length, 'a text grind has no chips').toBe(0);
	});

	it('editing a text grind starts from it, and a typed number replaces it', async () => {
		getBrewGrind().set('6 + a tooth');
		const root = render();
		expect(await typeGrind(root, '7.5'), 'the edit started from the text').toBe('6 + a tooth');
		expect(getBrewGrind().value).toBe('7.5');
	});

	it("the first + from a text grind goes to the scale's start from blank", () => {
		getBrewGrind().set('6 + a tooth');
		const root = render();
		const start = grindView({ bag: null, grinder, brewGrind: '6 + a tooth' }).startFromBlank;
		root.querySelector<HTMLButtonElement>('[aria-label="Increase Grind"]')!.click();
		flushSync();
		expect(getBrewGrind().value).toBe(grindText(start, grinder.scale));
	});

	it('an emptied field is no edit', async () => {
		getBrewGrind().set('6 + a tooth');
		const root = render();
		await typeGrind(root, '   ');
		expect(getBrewGrind().value).toBe('6 + a tooth');
	});
});
