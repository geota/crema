import { describe, expect, it } from "vitest";
import type { BrewRecipe } from "$lib/core/crema-core";
import { GuidedBrewStore } from "./session.svelte";

const recipe = (steps: BrewRecipe["steps"]): BrewRecipe =>
  ({
    id: "r",
    name: "V60",
    method: "pourover",
    doseG: 15,
    waterG: 250,
    steps,
  }) as BrewRecipe;

describe("GuidedBrewStore live marks", () => {
  it("snapshot each step’s planned target like the core", () => {
    const s = new GuidedBrewStore();
    s.armed(
      recipe([
        { kind: "bloom", targetWaterG: 45, durationS: 45 },
        { kind: "wait", durationS: 30 },
        { kind: "pour", targetWaterG: 250 },
      ] as BrewRecipe["steps"]),
      false,
    );
    s.started(0);
    s.stepChanged(1, 45_000);
    s.stepChanged(2, 75_000);
    expect(s.liveMarks.map((m) => m.targetWaterG)).toEqual([
      45,
      undefined,
      250,
    ]);
    expect("targetWaterG" in s.liveMarks[1]).toBe(false);
  });

  it("gives a step-less recipe its water target on the implicit pour", () => {
    const s = new GuidedBrewStore();
    s.armed(recipe([]), false);
    s.started(0);
    expect(s.liveMarks).toEqual([
      { elapsedMs: 0, stepIndex: 0, targetWaterG: 250 },
    ]);
  });
});
