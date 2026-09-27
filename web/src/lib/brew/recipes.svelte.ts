/**
 * `$lib/brew/recipes` — the guided-brew recipe library (issue #10).
 *
 * A recipe is a named multi-stage plan for a brew method (core
 * `BrewRecipe`, typeshared). Two kinds live side by side:
 *
 * - **Built-ins** — the core's credited catalogue (`builtin_brew_recipes`:
 *   Hoffmann, Kasuya, AeroPress, Stumptown, Hario …), bundled with the app
 *   and read-only, like the built-in DE1 profiles. Never persisted here and
 *   never written to a backup; "Duplicate to edit" makes an editable copy
 *   credited "Adapted from …". They can be hidden, never deleted.
 * - **User recipes** — shell-persisted like beans (`localStorage` via the
 *   shared storage helpers), soft-deleted with tombstones so a future sync
 *   can reconcile.
 *
 * The per-method default is a pointer (`lastUsed`, kept under its original
 * key): the recipe the Scale page's Brew setup opens on. Unset, a method
 * opens on its default built-in (`default_builtin_recipe_id`); methods
 * without one (espresso, drip, free text) open with no recipe.
 */

import type { BrewRecipe, RecipeLibraryMigration } from '$lib/core/crema-core';
import { readJson, writeJsonChecked } from '$lib/utils/storage';
import {
	blankRecipeJson,
	builtinBrewRecipesJson,
	defaultBuiltinRecipeId,
	duplicateRecipeJson,
	isBuiltinRecipe as wasmIsBuiltinRecipe,
	migrateRecipeLibraryJson,
	recipeNominalDurationMsJson,
	recipePlannedPourTotalGJson
} from '$lib/wasm/de1_wasm';
import { methodLabel } from './methods';

const RECIPES_KEY = 'crema.brewRecipes.v1';
const LAST_USED_KEY = 'crema.brewRecipes.lastUsed.v1';
const HIDDEN_KEY = 'crema.brewRecipes.hiddenBuiltins.v1';

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

let builtinCache: readonly BrewRecipe[] | null = null;

/** The core's credited built-in catalogue, parsed once. */
export function builtinRecipes(): readonly BrewRecipe[] {
	builtinCache ??= JSON.parse(builtinBrewRecipesJson()) as BrewRecipe[];
	return builtinCache;
}

/** Whether `id` names a built-in (read-only) recipe — core catalogue membership. */
export function isBuiltinRecipe(id: string): boolean {
	return wasmIsBuiltinRecipe(id);
}

/**
 * A brand-new recipe for the editor's "+ New recipe" door — the core's
 * `blank_recipe` (preset numbers, one pour, no credit) named with the UI
 * label ("Chemex recipe"). NOT persisted until saved.
 */
export function newRecipeFor(method: string): BrewRecipe {
	const recipe = JSON.parse(blankRecipeJson(method, recipeId(), Date.now())) as BrewRecipe;
	// "V60 / pourover" reads clumsy as a recipe name — take the first word.
	return { ...recipe, name: `${methodLabel(recipe.method).split(' / ')[0]} recipe` };
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
 * A session clock: "m:ss", or "h:mm:ss" from an hour up (the cold brew's
 * 12-hour steep reads "12:00:00", not "720:00").
 */
export function brewClock(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000));
	const h = Math.floor(total / 3600);
	const m = Math.floor((total % 3600) / 60);
	const s = String(total % 60).padStart(2, '0');
	return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

/** The `recipeMeta` backup line: default pointers + hidden built-ins. */
export interface RecipeBackupMeta {
	defaults: Record<string, string>;
	hiddenBuiltins: string[];
}

/**
 * The recipe library — a Svelte 5 `$state` class; obtain the singleton
 * with {@link getRecipeStore}.
 */
export class RecipeStore {
	private recipes = $state<BrewRecipe[]>([]);
	private lastUsed = $state<Record<string, string>>({});
	private hidden = $state<string[]>([]);

	constructor() {
		this.recipes = readJson<BrewRecipe[]>(RECIPES_KEY, []);
		this.lastUsed = readJson<Record<string, string>>(LAST_USED_KEY, {});
		this.hidden = readJson<string[]>(HIDDEN_KEY, []);
		this.migrate();
	}

	/**
	 * One-time cleanup (core `migrate_recipe_library`): drop the untouched
	 * generic "… classic" starters the Brew Log auto-saved before the
	 * built-in catalogue, and any stored built-in, repointing a default at
	 * the method's built-in. Edited starters are user data and stay.
	 */
	private migrate(): void {
		if (this.recipes.length === 0) return;
		let out: RecipeLibraryMigration;
		try {
			out = JSON.parse(
				migrateRecipeLibraryJson(
					JSON.stringify({ recipes: this.recipes, defaultByMethod: this.lastUsed })
				)
			) as RecipeLibraryMigration;
		} catch {
			return; // A malformed store stays as-is rather than being lost.
		}
		if (out.droppedIds.length === 0) return;
		this.recipes = out.recipes;
		this.lastUsed = out.defaultByMethod;
		this.persist();
		writeJsonChecked(LAST_USED_KEY, this.lastUsed);
	}

	/** Live (non-tombstoned) USER recipes, favourites first, then most recent. */
	get all(): BrewRecipe[] {
		return this.recipes
			.filter((r) => r.deletedAt == null)
			.sort(
				(a, b) =>
					Number(b.favourite ?? false) - Number(a.favourite ?? false) ||
					b.updatedAt - a.updatedAt
			);
	}

	/** Every built-in, hidden ones included (catalogue order). */
	get builtins(): readonly BrewRecipe[] {
		return builtinRecipes();
	}

	/** The built-ins not hidden by the user. */
	get visibleBuiltins(): BrewRecipe[] {
		return builtinRecipes().filter((r) => !this.hidden.includes(r.id));
	}

	/** The hidden built-in ids. */
	get hiddenBuiltinIds(): readonly string[] {
		return this.hidden;
	}

	isHidden(id: string): boolean {
		return this.hidden.includes(id);
	}

	/**
	 * What the recipe picker offers for `method`: the user's recipes, then
	 * the visible built-ins. The method's default and the current
	 * selection (`selectedId`) are always included, even when hidden.
	 */
	forMethod(method: string, selectedId?: string): BrewRecipe[] {
		const list = [
			...this.all.filter((r) => r.method === method),
			...this.visibleBuiltins.filter((r) => r.method === method)
		];
		for (const extra of [this.defaultFor(method), selectedId ? this.get(selectedId) : undefined]) {
			if (extra && extra.method === method && !list.some((r) => r.id === extra.id)) {
				list.push(extra);
			}
		}
		return list;
	}

	/** A live user recipe or a built-in, by id. */
	get(id: string): BrewRecipe | undefined {
		return (
			this.recipes.find((r) => r.id === id && r.deletedAt == null) ??
			builtinRecipes().find((r) => r.id === id)
		);
	}

	/**
	 * The recipe the Brew setup opens on for `method`: the user's chosen
	 * default when it still resolves, else the method's default built-in,
	 * else nothing (espresso, drip, free text have no built-in).
	 */
	defaultFor(method: string): BrewRecipe | undefined {
		const id = this.lastUsed[method];
		const chosen = id ? this.get(id) : undefined;
		if (chosen) return chosen;
		const builtin = defaultBuiltinRecipeId(method);
		return builtin ? this.get(builtin) : undefined;
	}

	/** Whether `recipe` is its method's default. */
	isDefault(recipe: BrewRecipe): boolean {
		return this.defaultFor(recipe.method)?.id === recipe.id;
	}

	/** Create-or-replace a USER recipe by id; bumps `updatedAt` and persists. */
	upsert(recipe: BrewRecipe): void {
		if (isBuiltinRecipe(recipe.id)) return; // Built-ins are bundled, never stored.
		const next = { ...recipe, updatedAt: Date.now() };
		const idx = this.recipes.findIndex((r) => r.id === recipe.id);
		this.recipes =
			idx >= 0
				? this.recipes.map((r, i) => (i === idx ? next : r))
				: [next, ...this.recipes];
		this.persist();
	}

	/**
	 * Clone `id` into an editable user recipe (core `duplicate_recipe`:
	 * fresh id, "<name> (copy)", credit "Adapted from …", same source) and
	 * persist it — the Profiles "Duplicate" / "Duplicate to edit" door.
	 */
	duplicate(id: string): BrewRecipe | undefined {
		const base = this.get(id);
		if (!base) return undefined;
		const copy = JSON.parse(
			duplicateRecipeJson(JSON.stringify(base), recipeId(), Date.now())
		) as BrewRecipe;
		this.upsert(copy);
		return this.get(copy.id) ?? copy;
	}

	/** Soft-delete (tombstone) a user recipe and persist. Built-ins can't be deleted. */
	remove(id: string): void {
		if (isBuiltinRecipe(id)) return;
		this.recipes = this.recipes.map((r) =>
			r.id === id ? { ...r, deletedAt: Date.now() } : r
		);
		// Drop any default pointer at the tombstone so the method falls back
		// to its built-in default (or no recipe).
		const cleaned = Object.fromEntries(
			Object.entries(this.lastUsed).filter(([, rid]) => rid !== id)
		);
		if (Object.keys(cleaned).length !== Object.keys(this.lastUsed).length) {
			this.lastUsed = cleaned;
			writeJsonChecked(LAST_USED_KEY, this.lastUsed);
		}
		this.persist();
	}

	/** Hide a built-in from the library and pickers (reversible). */
	hide(id: string): void {
		if (!isBuiltinRecipe(id) || this.hidden.includes(id)) return;
		this.hidden = [...this.hidden, id];
		writeJsonChecked(HIDDEN_KEY, this.hidden);
	}

	/** Bring a hidden built-in back. */
	unhide(id: string): void {
		if (!this.hidden.includes(id)) return;
		this.hidden = this.hidden.filter((h) => h !== id);
		writeJsonChecked(HIDDEN_KEY, this.hidden);
	}

	/**
	 * Save a recipe from the editor. It becomes its method's default only
	 * when the method has neither a chosen default nor a built-in one (so
	 * saving a copy of a built-in never silently moves the default off the
	 * built-in); changing an existing default is the Profiles "Make
	 * default" door's job. A built-in handed in is saved as a copy instead
	 * (the editor never writes over the catalogue). Android's rule too.
	 * Returns the stored copy.
	 */
	saveEdit(recipe: BrewRecipe): BrewRecipe {
		const toSave = isBuiltinRecipe(recipe.id)
			? (JSON.parse(
					duplicateRecipeJson(JSON.stringify(recipe), recipeId(), Date.now())
				) as BrewRecipe)
			: recipe;
		this.upsert(toSave);
		if (!this.lastUsed[toSave.method] && !defaultBuiltinRecipeId(toSave.method)) {
			this.touch(toSave);
		}
		return this.get(toSave.id) ?? toSave;
	}

	/**
	 * Remember `recipe` as the method's default — run from the Brew setup,
	 * or "Make default" in Profiles. A user recipe not yet stored is
	 * persisted; a built-in only moves the pointer.
	 */
	touch(recipe: BrewRecipe): void {
		if (!isBuiltinRecipe(recipe.id) && !this.recipes.some((r) => r.id === recipe.id)) {
			this.upsert(recipe);
		}
		this.lastUsed = { ...this.lastUsed, [recipe.method]: recipe.id };
		writeJsonChecked(LAST_USED_KEY, this.lastUsed);
	}

	// ── Backup ───────────────────────────────────────────────────

	/** Every stored user recipe (tombstones included) — never a built-in. */
	backupRecipes(): BrewRecipe[] {
		return this.recipes.filter((r) => !isBuiltinRecipe(r.id));
	}

	/** The `recipeMeta` line: default pointers + hidden built-ins. */
	backupMeta(): RecipeBackupMeta {
		return { defaults: { ...this.lastUsed }, hiddenBuiltins: [...this.hidden] };
	}

	/**
	 * Apply a backup's recipes + `recipeMeta`. `wipe` replaces the library;
	 * a merge adds recipes whose id isn't known and unions the hidden set
	 * (existing default pointers win). Returns how many recipes were added.
	 */
	applyBackup(
		recipes: BrewRecipe[],
		meta: Partial<RecipeBackupMeta> | null,
		wipe: boolean
	): number {
		const base = wipe ? [] : this.recipes;
		const known = new Set(base.map((r) => r.id));
		const added = recipes.filter((r) => r.id && !known.has(r.id) && !isBuiltinRecipe(r.id));
		this.recipes = [...added, ...base];
		const defaults = meta?.defaults ?? {};
		this.lastUsed = wipe ? { ...defaults } : { ...defaults, ...this.lastUsed };
		const hidden = (meta?.hiddenBuiltins ?? []).filter((id) => isBuiltinRecipe(id));
		this.hidden = wipe ? hidden : [...new Set([...this.hidden, ...hidden])];
		this.persist();
		writeJsonChecked(LAST_USED_KEY, this.lastUsed);
		writeJsonChecked(HIDDEN_KEY, this.hidden);
		return added.length;
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
