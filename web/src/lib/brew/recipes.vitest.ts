import { beforeEach, describe, expect, it } from "vitest";
import type { BrewRecipe } from "$lib/core/crema-core";
import {
  RecipeStore,
  brewClock,
  builtinRecipes,
  isBuiltinRecipe,
  newRecipeFor,
  nominalRecipeMs,
  plannedPourTotalG,
} from "./recipes.svelte";

const RECIPES_KEY = "crema.brewRecipes.v1";
const LAST_USED_KEY = "crema.brewRecipes.lastUsed.v1";

/*
 * The catalogue, the copy rule, the migration and the recipe math are
 * the core's (`builtin_brew_recipes`, `duplicate_recipe`,
 * `migrate_recipe_library`, … — pinned by de1-domain tests); these check
 * the wasm delegation and the store rules the web keeps (shared with
 * Android: see the parity notes on each).
 */
describe("core-backed recipes", () => {
  it("ships the credited built-in catalogue", () => {
    const all = builtinRecipes();
    expect(all).toHaveLength(13);
    const v60 = all.find((r) => r.id === "builtin:hoffmann-1-cup-v60")!;
    expect(v60.credit).toBe("James Hoffmann — A Better 1 Cup V60 Technique (2022)");
    expect(v60.sourceUrl).toBe("https://www.youtube.com/watch?v=1oB1oDrDkHM");
    expect(all.every((r) => isBuiltinRecipe(r.id))).toBe(true);
    expect(isBuiltinRecipe("recipe:0191")).toBe(false);
    expect(new Set(all.map((r) => r.method))).toEqual(
      new Set(["pourover", "aeropress", "french_press", "clever", "chemex", "kalita_wave", "cold_brew", "moka", "siphon"]),
    );
  });

  it("newRecipeFor is the blank template, named with the UI label", () => {
    const r = newRecipeFor("pourover");
    expect(r.name).toBe("V60 recipe");
    expect(r.id).toMatch(/^recipe:/);
    expect([r.doseG, r.waterG, r.tempC]).toEqual([15, 250, 96]);
    expect((r.steps ?? []).map((s) => s.kind)).toEqual(["pour"]);
    expect(r.credit).toBeUndefined();
    expect(newRecipeFor("chemex").name).toBe("Chemex recipe");
    expect(newRecipeFor("kalita_wave").name).toBe("Kalita Wave recipe");
  });

  it("nominalRecipeMs and plannedPourTotalG delegate to the core", () => {
    const v60 = builtinRecipes().find((r) => r.id === "builtin:hoffmann-1-cup-v60")!;
    expect(nominalRecipeMs(v60)).toBe(125_000);
    expect(plannedPourTotalG(v60)).toBe(250);
    expect(plannedPourTotalG({ ...v60, steps: [] })).toBeNull();
  });

  it("brewClock formats hours for the 12-hour cold brew steep", () => {
    expect(brewClock(0)).toBe("0:00");
    expect(brewClock(185_000)).toBe("3:05");
    expect(brewClock(3_599_000)).toBe("59:59");
    expect(brewClock(3_600_000)).toBe("1:00:00");
    expect(brewClock(43_200_000)).toBe("12:00:00");
    expect(brewClock(43_199_000)).toBe("11:59:59");
    expect(brewClock(-5)).toBe("0:00");
  });
});

describe("RecipeStore — built-ins and defaults", () => {
  beforeEach(() => localStorage.clear());

  it("opens each method on its built-in default; espresso / drip / free text get none", () => {
    const store = new RecipeStore();
    expect(store.defaultFor("pourover")?.id).toBe("builtin:hoffmann-1-cup-v60");
    expect(store.defaultFor("aeropress")?.id).toBe("builtin:aeropress-official");
    expect(store.defaultFor("chemex")?.id).toBe("builtin:stumptown-chemex");
    expect(store.defaultFor("cold_brew")?.id).toBe("builtin:hoffmann-cold-brew");
    expect(store.defaultFor("espresso")).toBeUndefined();
    expect(store.defaultFor("drip")).toBeUndefined();
    expect(store.defaultFor("karlsbad_kanne")).toBeUndefined();
  });

  it("built-ins are read-only: never stored, deleted, or upserted", () => {
    const store = new RecipeStore();
    const b = store.get("builtin:kasuya-4-6")!;
    store.upsert({ ...b, name: "Hacked" });
    store.remove(b.id);
    expect(store.get(b.id)?.name).toBe("4:6 Method");
    expect(localStorage.getItem(RECIPES_KEY)).toBeNull();
    expect(store.all).toHaveLength(0);
  });

  it("duplicating a built-in makes a credited, editable copy", () => {
    const store = new RecipeStore();
    const copy = store.duplicate("builtin:hoffmann-ultimate-french-press")!;
    expect(copy.id).toMatch(/^recipe:/);
    expect(isBuiltinRecipe(copy.id)).toBe(false);
    expect(copy.name).toBe("Ultimate French Press (copy)");
    expect(copy.credit).toBe("Adapted from James Hoffmann — The Ultimate French Press Technique (2016)");
    expect(copy.sourceUrl).toBe("https://www.youtube.com/watch?v=st571DYYTR8");
    expect(store.all.map((r) => r.id)).toEqual([copy.id]);
    // The editor never writes over a built-in: a built-in handed to
    // saveEdit is saved as a copy.
    const saved = store.saveEdit({ ...store.get("builtin:hario-syphon")!, name: "Mine" });
    expect(isBuiltinRecipe(saved.id)).toBe(false);
    expect(store.get("builtin:hario-syphon")?.name).toBe("Syphon");
  });

  it("parity 1: saving never moves a default off a built-in; it defaults a method with none", () => {
    const store = new RecipeStore();
    const copy = store.duplicate("builtin:hoffmann-1-cup-v60")!;
    store.saveEdit({ ...copy, doseG: 16 });
    expect(store.defaultFor("pourover")?.id).toBe("builtin:hoffmann-1-cup-v60");
    // Espresso has no built-in and no pick: its first saved recipe becomes the default…
    const esp = store.saveEdit({ ...newRecipeFor("espresso"), name: "Turbo" });
    expect(store.defaultFor("espresso")?.id).toBe(esp.id);
    // …and a second one doesn't take over.
    store.saveEdit({ ...newRecipeFor("espresso"), name: "Slow" });
    expect(store.defaultFor("espresso")?.id).toBe(esp.id);
    // Only "Make default" (touch) moves it.
    store.touch(copy);
    expect(store.defaultFor("pourover")?.id).toBe(copy.id);
  });

  it("parity 2: running a built-in moves the pointer but never persists it", () => {
    const store = new RecipeStore();
    store.touch(store.get("builtin:kasuya-4-6")!);
    expect(store.defaultFor("pourover")?.id).toBe("builtin:kasuya-4-6");
    expect(JSON.parse(localStorage.getItem(LAST_USED_KEY)!)).toEqual({ pourover: "builtin:kasuya-4-6" });
    expect(localStorage.getItem(RECIPES_KEY)).toBeNull();
    expect(new RecipeStore().defaultFor("pourover")?.id).toBe("builtin:kasuya-4-6");
  });

  it("parity 3: a hidden built-in leaves the picker, except while selected (or the default)", () => {
    const store = new RecipeStore();
    store.hide("builtin:hoffmann-ultimate-v60");
    const ids = (sel?: string) => store.forMethod("pourover", sel).map((r) => r.id);
    expect(ids()).not.toContain("builtin:hoffmann-ultimate-v60");
    expect(ids("builtin:hoffmann-ultimate-v60")).toContain("builtin:hoffmann-ultimate-v60");
    // The default is always offered, hidden or not.
    store.hide("builtin:hoffmann-1-cup-v60");
    expect(ids()).toContain("builtin:hoffmann-1-cup-v60");
    expect(store.visibleBuiltins.map((r) => r.id)).not.toContain("builtin:hoffmann-1-cup-v60");
    store.unhide("builtin:hoffmann-ultimate-v60");
    expect(ids()).toContain("builtin:hoffmann-ultimate-v60");
    // Only built-ins hide.
    store.hide("recipe:x");
    expect(store.hiddenBuiltinIds).not.toContain("recipe:x");
  });
});

describe("RecipeStore — migration off the generic 'classic' starters", () => {
  beforeEach(() => localStorage.clear());

  /** What the removed `default_recipe` produced for pourover, as the web named and stored it. */
  function legacyV60(id: string): BrewRecipe {
    return {
      id,
      name: "V60 classic",
      method: "pourover",
      doseG: 15,
      waterG: 250,
      tempC: 96,
      steps: [
        { kind: "bloom", targetWaterG: 45, durationS: 45, advance: "auto" },
        { kind: "pour", targetWaterG: 150, advance: "auto" },
        { kind: "wait", durationS: 30, advance: "auto" },
        { kind: "pour", targetWaterG: 250, advance: "auto" },
        { kind: "drawdown", advance: "manual" },
      ] as BrewRecipe["steps"],
      favourite: false,
      createdAt: 1_700_000_000_000,
      updatedAt: 1_700_000_004_000,
    } as BrewRecipe;
  }

  it("drops an untouched seeded starter and repoints its default at the built-in", () => {
    localStorage.setItem(RECIPES_KEY, JSON.stringify([legacyV60("recipe:old")]));
    localStorage.setItem(LAST_USED_KEY, JSON.stringify({ pourover: "recipe:old" }));
    const store = new RecipeStore();
    expect(store.all).toHaveLength(0);
    expect(store.defaultFor("pourover")?.id).toBe("builtin:hoffmann-1-cup-v60");
    // Persisted, so the next load doesn't redo it.
    expect(JSON.parse(localStorage.getItem(RECIPES_KEY)!)).toEqual([]);
    expect(JSON.parse(localStorage.getItem(LAST_USED_KEY)!)).toEqual({ pourover: "builtin:hoffmann-1-cup-v60" });
  });

  it("keeps an edited starter — it's user data — and its default", () => {
    const edited = { ...legacyV60("recipe:mine"), doseG: 16 };
    const renamed = { ...legacyV60("recipe:renamed"), name: "Morning V60" };
    localStorage.setItem(RECIPES_KEY, JSON.stringify([edited, renamed]));
    localStorage.setItem(LAST_USED_KEY, JSON.stringify({ pourover: "recipe:mine" }));
    const store = new RecipeStore();
    expect(store.all.map((r) => r.id).sort()).toEqual(["recipe:mine", "recipe:renamed"]);
    expect(store.defaultFor("pourover")?.id).toBe("recipe:mine");
  });
});
