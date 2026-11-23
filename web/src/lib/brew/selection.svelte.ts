/**
 * `#lib/brew/selection` — Brew's selection and the mode it decides (brew
 * modes spec §2, §4).
 *
 * Brew selects either a DE1 profile (**Espresso**, the DE1 dashboard) or a
 * brew recipe (**Manual**, the guided brew). The rules are the core's
 * (`brew_selection`); this store keeps what the shell stores:
 *
 * - the profile key it always had, `crema.profiles.activeId.v1`, owned by
 *   the profile store and still "the profile Espresso uses";
 * - the recipe key beside it, {@link RECIPE_KEY}, set while a recipe is
 *   selected.
 *
 * A pick in Brew's picker goes through {@link BrewSelectionStore.pickProfile}
 * or {@link BrewSelectionStore.pickRecipe} (the core's
 * `pick_brew_selection`): a profile clears the recipe, a recipe keeps the
 * profile. Other ways the profile key changes (a bag's linked profile, a
 * saved edit) leave the recipe alone, as the core's remote rule does.
 *
 * Like the profile key, the recipe key is not followed across tabs: it is
 * what this tab's Brew shows.
 */

import type {
	BrewPickLock,
	BrewPickLockInput,
	BrewRecipe,
	BrewSelection,
	BrewSelectionView,
	RecipeSummary,
	StoredBrewSelection
} from '#lib/core/crema-core.js';
import { BrewMode, BrewSelectionKind } from '#lib/core/crema-core.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { isIdOrNull, readJson, writeJson } from '#lib/utils/storage.js';
import {
	brewPickLockJson,
	brewSelectionViewJson,
	pickBrewSelectionJson,
	recipeSummaryJson
} from '#lib/wasm/de1_wasm.js';
import { getRecipeStore } from './recipes.svelte';
import type { GuidedBrewPhase } from './session.svelte';

/** The recipe Brew has selected, beside the profile key. */
export const RECIPE_KEY = 'crema.profiles.activeRecipeId.v1';

/** The view of nothing selected (and the fallback if the core can't read). */
const ESPRESSO: BrewSelectionView = { mode: BrewMode.Espresso };

export class BrewSelectionStore {
	/** The stored recipe id; `null` = none. */
	recipeId = $state<string | null>(readJson<string | null>(RECIPE_KEY, null, { valid: isIdOrNull }));

	private readonly profiles = getProfileStore();
	private readonly recipes = getRecipeStore();

	/** The selection and its mode (the core's `brew_selection`). */
	readonly view: BrewSelectionView = $derived.by(() => {
		const id = this.recipeId;
		try {
			return JSON.parse(
				brewSelectionViewJson(
					JSON.stringify({
						activeProfileId: this.profiles.activeId ?? undefined,
						activeRecipeId: id ?? undefined,
						recipeLive: id != null && this.recipes.get(id) != null
					})
				)
			) as BrewSelectionView;
		} catch {
			return ESPRESSO;
		}
	});

	/** Brew's mode. */
	get mode(): BrewMode {
		return this.view.mode;
	}

	/** True in Manual. */
	get manual(): boolean {
		return this.view.mode === BrewMode.Manual;
	}

	/** The selected recipe while in Manual, else `null`. */
	readonly recipe: BrewRecipe | null = $derived.by(() => {
		const sel = this.view.selection;
		return sel?.kind === BrewSelectionKind.Recipe ? (this.recipes.get(sel.id) ?? null) : null;
	});

	/** The selected recipe's header summary. */
	readonly summary: RecipeSummary | null = $derived(this.recipe ? recipeSummary(this.recipe) : null);

	/** Pick a DE1 profile: Espresso, the recipe cleared. */
	pickProfile(id: string): void {
		this.store(this.pick({ kind: BrewSelectionKind.Profile, id }));
		if (this.profiles.activeId !== id) this.profiles.setActive(id);
	}

	/** Pick a recipe: Manual, the profile kept for Espresso. */
	pickRecipe(id: string): void {
		this.store(this.pick({ kind: BrewSelectionKind.Recipe, id }));
	}

	private pick(pick: BrewSelection): StoredBrewSelection {
		const stored: StoredBrewSelection = {
			activeProfileId: this.profiles.activeId ?? undefined,
			activeRecipeId: this.recipeId ?? undefined
		};
		return JSON.parse(pickBrewSelectionJson(JSON.stringify({ stored, pick }))) as StoredBrewSelection;
	}

	private store(next: StoredBrewSelection): void {
		const id = next.activeRecipeId ?? null;
		if (id === this.recipeId) return;
		this.recipeId = id;
		writeJson(RECIPE_KEY, id);
	}
}

/** A recipe's header summary (the core's `recipe_summary`). */
export function recipeSummary(recipe: BrewRecipe): RecipeSummary | null {
	try {
		return JSON.parse(recipeSummaryJson(JSON.stringify(recipe))) as RecipeSummary;
	} catch {
		return null;
	}
}

/** A guided brew runs on this device: armed, running or paused (not done). */
export function guidedBrewRunning(phase: GuidedBrewPhase): boolean {
	return phase === 'armed' || phase === 'running' || phase === 'paused';
}

/**
 * The picker lock (§4, the core's `brew_pick_lock`): a shot disables the
 * profile rows, a guided brew every row. `null` = the rows are enabled.
 */
export function brewPickLock(input: BrewPickLockInput): BrewPickLock | null {
	try {
		const out = brewPickLockJson(JSON.stringify(input));
		return out == null ? null : (JSON.parse(out) as BrewPickLock);
	} catch {
		return null;
	}
}

let store: BrewSelectionStore | null = null;

/** The app's selection store, created on first use (after the core loads). */
export function getBrewSelectionStore(): BrewSelectionStore {
	store ??= new BrewSelectionStore();
	return store;
}
