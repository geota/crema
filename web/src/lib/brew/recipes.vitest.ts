import { describe, expect, it } from "vitest";
import { RecipeStore, defaultRecipeFor, nominalRecipeMs, plannedPourTotalG } from "./recipes.svelte";

/*
 * The templates and recipe math are the core's (`default_recipe`,
 * `nominal_duration_ms`, `planned_pour_total_g` — pinned by de1-domain
 * tests); these check the wasm delegation and the copy the web keeps.
 */
describe("core-backed recipes", () => {
  it("defaultRecipeFor takes the core plan and names it with the UI label", () => {
    const r = defaultRecipeFor("pourover");
    expect(r.name).toBe("V60 classic");
    expect(r.id).toMatch(/^recipe:/);
    expect([r.doseG, r.waterG, r.tempC]).toEqual([15, 250, 96]);
    expect((r.steps ?? []).map((s) => s.kind)).toEqual(["bloom", "pour", "wait", "pour", "drawdown"]);
    expect(defaultRecipeFor("french_press").name).toBe("French press classic");
  });

  it("nominalRecipeMs counts pour-only steps a notional 30 s", () => {
    // bloom 45 + pour 30 + wait 30 + pour 30 + open drawdown 0.
    expect(nominalRecipeMs(defaultRecipeFor("pourover"))).toBe(135_000);
  });

  it("plannedPourTotalG is the largest step target, null without one", () => {
    const r = defaultRecipeFor("aeropress");
    expect(plannedPourTotalG(r)).toBe(220);
    expect(plannedPourTotalG({ ...r, steps: [] })).toBeNull();
  });
});

describe("RecipeStore.saveEdit (drift bug 11)", () => {
  it("makes a method's first saved recipe its default, never moves an existing one", () => {
    localStorage.clear();
    const store = new RecipeStore();
    const first = defaultRecipeFor("pourover");
    store.saveEdit(first);
    expect(store.lastUsedFor("pourover")?.id).toBe(first.id);
    const second = { ...defaultRecipeFor("pourover"), name: "Two-pour" };
    const saved = store.saveEdit(second);
    expect(saved.name).toBe("Two-pour");
    expect(store.get(second.id)).toBeDefined();
    // Editing the second recipe left the method's default alone.
    expect(store.lastUsedFor("pourover")?.id).toBe(first.id);
  });
});
