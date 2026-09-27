/**
 * `$lib/brew/bean-pick` — which bag a brew debits (issue #10 feedback).
 *
 * The guided Brew setup offers a bean picker next to the recipe picker;
 * the pick rides through the session into the log form's prefill, where
 * it is still changeable. Picking a bag here never changes the app's
 * active bag (the log form doesn't either — "Set active" stays a
 * deliberate action on the Beans page).
 *
 * Pure: the store slot lives on `GuidedBrewStore.beanPick`.
 */

import type { Bean } from '$lib/bean';
import type { BrewRecipe, BrewSessionSummary } from '$lib/core/crema-core';
import { guidedPrefillWeights, type LogBrewPrefill } from './methods';

/**
 * The setup's bean selection: `undefined` = follow the active bag (the
 * default), `null` = "No bean" (inventory untouched), else a bag id.
 */
export type BeanPick = string | null | undefined;

/** The slice of the bean store the resolver reads. */
export interface BeanLookup {
	readonly activeBeanId: string | null;
	getBean(id: string): Bean | null;
}

/** A bag a brew can use: it exists, and is neither archived nor deleted. */
export function isPickableBean(b: Bean | null | undefined): b is Bean {
	return b != null && b.archivedAt == null && b.deletedAt == null;
}

/**
 * The bag a brew will debit for `pick`: the chosen bag while it's still
 * pickable; if it was archived or deleted meanwhile (or nothing was
 * chosen), the active bag; else no bean. "No bean" stays no bean.
 */
export function resolveBrewBean(pick: BeanPick, lookup: BeanLookup): string | null {
	if (pick === null) return null;
	if (pick !== undefined && isPickableBean(lookup.getBean(pick))) return pick;
	const active = lookup.activeBeanId;
	return active != null && isPickableBean(lookup.getBean(active)) ? active : null;
}

/**
 * The dose would take more than the bag has left — the log form's quiet
 * "more than the N g left in this bag" note. A bag with no remaining
 * weight recorded (0) never warns.
 */
export function beanOverdraws(bean: Bean | null | undefined, doseG: number | null | undefined): boolean {
	return (
		bean != null &&
		doseG != null &&
		doseG > 0 &&
		bean.remaining > 0 &&
		doseG > bean.remaining + 0.05
	);
}

/** The finished guided session, pre-shaped for the log form. */
export function guidedLogPrefill(
	summary: BrewSessionSummary,
	recipe: BrewRecipe,
	beanId: string | null
): LogBrewPrefill {
	return {
		method: summary.method,
		recipeName: summary.recipeName,
		// Explicit, even when null: "No bean" must not fall back to the
		// active bag in the form.
		beanId,
		dose: recipe.doseG > 0 ? recipe.doseG : null,
		...guidedPrefillWeights(summary.method, summary.finalWeightG ?? null, recipe.waterG),
		tempC: recipe.tempC ?? null,
		durationMs: summary.durationMs,
		// A scale-less run records no weight — it saves as a plain
		// logged brew, without a series (spec §8).
		brewSeries: summary.series.samples.length > 0 ? summary.series : undefined
	};
}

/** The log form's opening bag: the prefill's, when it names one (even
 *  `null` = No bean), else the active bag. */
export function openingBeanId(prefill: LogBrewPrefill | undefined, activeBeanId: string | null): string | null {
	return prefill !== undefined && prefill.beanId !== undefined ? prefill.beanId : activeBeanId;
}
