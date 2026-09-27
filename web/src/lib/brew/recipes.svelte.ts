/**
 * `$lib/brew/recipes` — the guided-brew recipe library (issue #10).
 *
 * A recipe is a named multi-stage plan for a brew method (core
 * `BrewRecipe`, typeshared). Shell-persisted like beans: `localStorage`
 * via the shared storage helpers, soft-deleted with tombstones so a
 * future sync can reconcile. The "last used per method" pointer honors
 * the issue's "don't think it needs to store anything but last used"
 * while still letting people who name recipes keep several.
 */

import type { BrewRecipe } from '$lib/core/crema-core';
import { readJson, writeJsonChecked } from '$lib/utils/storage';
import {
	defaultRecipeJson,
	recipeNominalDurationMsJson,
	recipePlannedPourTotalGJson
} from '$lib/wasm/de1_wasm';
import { methodLabel } from './methods';

const RECIPES_KEY = 'crema.brewRecipes.v1';
const LAST_USED_KEY = 'crema.brewRecipes.lastUsed.v1';

/** Mint a `recipe:<uuid-v7>` id via the core, with a boot fallback. */
export function recipeId(): string {
	if (typeof window !== 'undefined') {
		const cached = (window as { __cremaWasmCore?: { newRecipeId?: () => string } })
			.__cremaWasmCore;
		if (cached?.newRecipeId) {
			try {
				return cached.newRecipeId();
			} catch {
				// Fall through.
			}
		}
	}
	const rnd =
		typeof crypto !== 'undefined' && 'randomUUID' in crypto
			? crypto.randomUUID()
			: Math.random().toString(36).slice(2) + Date.now().toString(36);
	return `recipe:${rnd}`;
}

/**
 * Build the sensible starter recipe for a method — what the Brew
 * segment offers before the user has saved anything. NOT persisted
 * until the user edits + saves it; running it untouched is fine. The
 * template (preset numbers + the per-method classic steps) is the
 * core's `default_recipe`; the name is the UI's copy.
 */
export function defaultRecipeFor(method: string): BrewRecipe {
	const recipe = JSON.parse(defaultRecipeJson(method, recipeId(), Date.now())) as BrewRecipe;
	return { ...recipe, name: defaultRecipeName(method) };
}

function defaultRecipeName(method: string): string {
	// "V60 / pourover" reads clumsy as a recipe name — take the first word.
	return `${methodLabel(method).split(' / ')[0]} classic`;
}

/**
 * A recipe's nominal run time, ms — the core's
 * `BrewRecipe::nominal_duration_ms` (step durations, pour-only steps a
 * notional 30 s each). Drives the library card's "~m:ss" and the guided
 * panel's "of about m:ss" line.
 */
export function nominalRecipeMs(recipe: BrewRecipe): number {
	return recipeNominalDurationMsJson(JSON.stringify(recipe));
}

/**
 * The recipe's planned cumulative pour total, grams — the core's
 * `BrewRecipe::planned_pour_total_g` (largest finite step target), or
 * `null` when no step has one. The editor's "250 g planned" check.
 */
export function plannedPourTotalG(recipe: BrewRecipe): number | null {
	return recipePlannedPourTotalGJson(JSON.stringify(recipe)) ?? null;
}

/**
 * The recipe library — a Svelte 5 `$state` class; obtain the singleton
 * with {@link getRecipeStore}.
 */
export class RecipeStore {
	private recipes = $state<BrewRecipe[]>([]);
	private lastUsed = $state<Record<string, string>>({});

	constructor() {
		this.recipes = readJson<BrewRecipe[]>(RECIPES_KEY, []);
		this.lastUsed = readJson<Record<string, string>>(LAST_USED_KEY, {});
	}

	/** Live (non-tombstoned) recipes, favourites first, then most recent. */
	get all(): BrewRecipe[] {
		return this.recipes
			.filter((r) => r.deletedAt == null)
			.sort(
				(a, b) =>
					Number(b.favourite ?? false) - Number(a.favourite ?? false) ||
					b.updatedAt - a.updatedAt
			);
	}

	forMethod(method: string): BrewRecipe[] {
		return this.all.filter((r) => r.method === method);
	}

	get(id: string): BrewRecipe | undefined {
		return this.recipes.find((r) => r.id === id && r.deletedAt == null);
	}

	/** The recipe the Brew segment opens on for `method`. */
	lastUsedFor(method: string): BrewRecipe | undefined {
		const id = this.lastUsed[method];
		return id ? this.get(id) : undefined;
	}

	/** Create-or-replace by id; bumps `updatedAt` and persists. */
	upsert(recipe: BrewRecipe): void {
		const next = { ...recipe, updatedAt: Date.now() };
		const idx = this.recipes.findIndex((r) => r.id === recipe.id);
		this.recipes =
			idx >= 0
				? this.recipes.map((r, i) => (i === idx ? next : r))
				: [next, ...this.recipes];
		this.persist();
	}

	/**
	 * Clone `id` into a sibling recipe ("<name> copy", fresh id) and
	 * persist it — the Profiles page's "Duplicate" door, which is how a
	 * method grows a second recipe (e.g. a 1-pour and a 3-pour V60).
	 */
	duplicate(id: string): BrewRecipe | undefined {
		const base = this.get(id);
		if (!base) return undefined;
		const copy: BrewRecipe = {
			...base,
			id: recipeId(),
			name: `${base.name} copy`,
			steps: (base.steps ?? []).map((s) => ({ ...s })),
			favourite: false,
			createdAt: Date.now(),
			updatedAt: Date.now()
		};
		this.upsert(copy);
		return copy;
	}

	/** Soft-delete (tombstone) and persist. */
	remove(id: string): void {
		this.recipes = this.recipes.map((r) =>
			r.id === id ? { ...r, deletedAt: Date.now() } : r
		);
		// Drop any last-used pointer at the tombstone so the method falls
		// back to its remaining recipes (or the built-in default).
		const cleaned = Object.fromEntries(
			Object.entries(this.lastUsed).filter(([, rid]) => rid !== id)
		);
		if (Object.keys(cleaned).length !== Object.keys(this.lastUsed).length) {
			this.lastUsed = cleaned;
			writeJsonChecked(LAST_USED_KEY, this.lastUsed);
		}
		this.persist();
	}

	/** Remember `recipe` as the method's last-used (persisting it if new). */
	touch(recipe: BrewRecipe): void {
		if (!this.recipes.some((r) => r.id === recipe.id)) {
			// A default recipe run untouched still becomes the method's
			// remembered one — "stores the last used" per the issue.
			this.upsert(recipe);
		}
		this.lastUsed = { ...this.lastUsed, [recipe.method]: recipe.id };
		writeJsonChecked(LAST_USED_KEY, this.lastUsed);
	}

	private persist(): void {
		writeJsonChecked(RECIPES_KEY, this.recipes);
	}
}

let store: RecipeStore | null = null;
export function getRecipeStore(): RecipeStore {
	store ??= new RecipeStore();
	return store;
}
