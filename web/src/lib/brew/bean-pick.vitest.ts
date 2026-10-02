import { describe, expect, it } from 'vitest';
import { blankBean, type Bean } from '$lib/bean';
import type { BrewRecipe, BrewSessionSummary } from '$lib/core/crema-core';
import {
	beanOverdraws,
	guidedLogPrefill,
	openingBeanId,
	resolveBrewBean,
	type BeanLookup
} from './bean-pick';
import { GuidedBrewStore } from './session.svelte';

const bag = (id: string, over: Partial<Bean> = {}): Bean => ({
	...blankBean(),
	id,
	name: id,
	remaining: 250,
	...over
});

const lookup = (beans: Bean[], activeBeanId: string | null): BeanLookup => ({
	activeBeanId,
	getBean: (id) => beans.find((b) => b.id === id) ?? null
});

const recipe = {
	id: 'r',
	name: 'My V60',
	method: 'pourover',
	doseG: 15,
	waterG: 250,
	tempC: 94,
	steps: [],
	favourite: false,
	createdAt: 1,
	updatedAt: 1
} as unknown as BrewRecipe;

const summary = {
	method: 'pourover',
	recipeName: 'My V60',
	durationMs: 180_000,
	finalWeightG: null,
	series: { samples: [], stageMarks: [] }
} as unknown as BrewSessionSummary;

describe('resolveBrewBean', () => {
	const beans = [bag('active'), bag('other'), bag('old', { archivedAt: 5 })];

	it('defaults to the active bag', () => {
		expect(resolveBrewBean(undefined, lookup(beans, 'active'))).toBe('active');
	});

	it('keeps a chosen non-active bag', () => {
		expect(resolveBrewBean('other', lookup(beans, 'active'))).toBe('other');
	});

	it('keeps "No bean"', () => {
		expect(resolveBrewBean(null, lookup(beans, 'active'))).toBeNull();
	});

	it('falls back to the active bag when the chosen one is archived or deleted', () => {
		expect(resolveBrewBean('old', lookup(beans, 'active'))).toBe('active');
		expect(resolveBrewBean('gone', lookup(beans, 'active'))).toBe('active');
		const tomb = [...beans, bag('tomb', { deletedAt: 9 })];
		expect(resolveBrewBean('tomb', lookup(tomb, 'active'))).toBe('active');
	});

	it('falls back to No bean when there is no usable active bag', () => {
		expect(resolveBrewBean('old', lookup(beans, null))).toBeNull();
		expect(resolveBrewBean('old', lookup(beans, 'old'))).toBeNull();
	});
});

describe('beanOverdraws', () => {
	it('warns only when the dose exceeds what is left', () => {
		expect(beanOverdraws(bag('b', { remaining: 12 }), 15)).toBe(true);
		expect(beanOverdraws(bag('b', { remaining: 12 }), 12)).toBe(false);
		expect(beanOverdraws(bag('b', { remaining: 250 }), 15)).toBe(false);
	});

	it('stays quiet without a bag, a dose, or a recorded remaining weight', () => {
		expect(beanOverdraws(null, 15)).toBe(false);
		expect(beanOverdraws(bag('b', { remaining: 12 }), 0)).toBe(false);
		expect(beanOverdraws(bag('b', { remaining: 0 }), 15)).toBe(false);
	});
});

describe('guided prefill carries the chosen bean', () => {
	it('prefills the setup bag, not the active one', () => {
		const p = guidedLogPrefill(summary, recipe, 'other');
		expect(p.beanId).toBe('other');
		expect(p.dose).toBe(15);
		expect(openingBeanId(p, 'active')).toBe('other');
	});

	it("never merges the recipe's notes into the brew's own notes", () => {
		const p = guidedLogPrefill(summary, { ...recipe, notes: 'Cafec Abaca, TWW' }, 'other');
		expect(JSON.stringify(p)).not.toContain('Cafec Abaca');
	});

	it('keeps No bean through to the form', () => {
		const p = guidedLogPrefill(summary, recipe, null);
		expect('beanId' in p).toBe(true);
		expect(openingBeanId(p, 'active')).toBeNull();
	});

	it('opens other forms on the active bag when the prefill names none', () => {
		expect(openingBeanId(undefined, 'active')).toBe('active');
		expect(openingBeanId({ method: 'v60' }, 'active')).toBe('active');
	});
});

describe('GuidedBrewStore bean pick', () => {
	it('survives a session reset and never touches anything else', () => {
		const s = new GuidedBrewStore();
		expect(s.beanPick).toBeUndefined();
		s.pickBean('other');
		s.armed(recipe, false);
		s.reset();
		expect(s.beanPick).toBe('other');
		s.pickBean(null);
		expect(s.beanPick).toBeNull();
	});
});
