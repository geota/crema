import { afterEach, describe, expect, it } from 'vitest';
import { flushSync } from 'svelte';
import { runInEffect } from '#lib/testing/effect-root.svelte.js';
import { clearanceAbove, getToastBottomInset, keepToastsAbove } from './toast-inset.svelte.js';

describe('clearanceAbove', () => {
	it('is the distance from the band top to the viewport bottom', () => {
		// Brew foot at 1280x800: top edge at y 700 -> the stack starts 100 px up.
		expect(clearanceAbove(700, 800)).toBe(100);
		// Phone width: a 202 px sticky foot.
		expect(clearanceAbove(658, 860)).toBe(202);
	});

	it('needs no clearance for a band scrolled out of view', () => {
		expect(clearanceAbove(800, 800)).toBe(0);
		expect(clearanceAbove(950, 800)).toBe(0);
	});

	it('caps at the viewport height and ignores non-finite input', () => {
		expect(clearanceAbove(-40, 800)).toBe(800);
		expect(clearanceAbove(Number.NaN, 800)).toBe(0);
	});

	it('rounds to whole pixels', () => {
		expect(clearanceAbove(699.6, 800)).toBe(100);
	});
});

describe('keepToastsAbove', () => {
	const cleanups: Array<() => void> = [];
	afterEach(() => {
		while (cleanups.length) cleanups.pop()?.();
		document.body.innerHTML = '';
	});

	function band(top: number): HTMLElement {
		const el = document.createElement('div');
		el.getBoundingClientRect = () => ({ top }) as DOMRect;
		document.body.appendChild(el);
		return el;
	}

	it('publishes the band clearance while attached and clears it on detach', () => {
		expect(getToastBottomInset()).toBe(0);
		const stop = keepToastsAbove(band(window.innerHeight - 96));
		expect(getToastBottomInset()).toBe(96);
		stop();
		expect(getToastBottomInset()).toBe(0);
	});

	it('uses the tallest clearance when several bands are attached', () => {
		cleanups.push(keepToastsAbove(band(window.innerHeight - 60)));
		cleanups.push(keepToastsAbove(band(window.innerHeight - 140)));
		expect(getToastBottomInset()).toBe(140);
	});

	it('re-measures on window resize', () => {
		let top = window.innerHeight - 100;
		const el = document.createElement('div');
		el.getBoundingClientRect = () => ({ top }) as DOMRect;
		cleanups.push(keepToastsAbove(el));
		expect(getToastBottomInset()).toBe(100);
		top = window.innerHeight - 200;
		window.dispatchEvent(new Event('resize'));
		expect(getToastBottomInset()).toBe(200);
	});

	it('does not loop when attached inside an effect (as {@attach} does)', () => {
		// Regression: the first version read the inset state it wrote, and
		// the Brew route died with effect_update_depth_exceeded.
		const el = band(window.innerHeight - 120);
		const stop = runInEffect(() => keepToastsAbove(el));
		expect(getToastBottomInset()).toBe(120);
		window.dispatchEvent(new Event('resize'));
		flushSync();
		expect(getToastBottomInset()).toBe(120);
		stop();
		expect(getToastBottomInset()).toBe(0);
	});
});
