import { describe, expect, it } from "vitest";
import type { StageMark } from "$lib/core/crema-core";
import { maxPlannedTarget, plannedStaircase, staircasePath } from "./staircase";

/*
 * The staircase geometry is the core's (`planned_staircase` — its cases are
 * pinned by the de1-domain tests); these check the wasm delegation and the
 * SVG path this module still owns.
 */

/** V60: bloom 45 → pour 150 → wait → pour 250 → drawdown. */
const v60: StageMark[] = [
  { elapsedMs: 0, stepIndex: 0, targetWaterG: 45 },
  { elapsedMs: 45_000, stepIndex: 1, targetWaterG: 150 },
  { elapsedMs: 75_000, stepIndex: 2 },
  { elapsedMs: 100_000, stepIndex: 3, targetWaterG: 250 },
  { elapsedMs: 130_000, stepIndex: 4 },
];

describe("plannedStaircase (core-backed)", () => {
  it("delegates to the core, carrying targets through timed steps", () => {
    expect(plannedStaircase(v60, 180_000)).toEqual([
      { t0Ms: 0, t1Ms: 45_000, targetG: 45 },
      { t0Ms: 45_000, t1Ms: 75_000, targetG: 150 },
      { t0Ms: 75_000, t1Ms: 100_000, targetG: 150 },
      { t0Ms: 100_000, t1Ms: 130_000, targetG: 250 },
      { t0Ms: 130_000, t1Ms: 180_000, targetG: 250 },
    ]);
    expect(maxPlannedTarget(v60)).toBe(250);
  });

  it("is empty with no targets", () => {
    expect(plannedStaircase([], 60_000)).toEqual([]);
    expect(plannedStaircase([{ elapsedMs: 0, stepIndex: 0 }], 60_000)).toEqual([]);
    expect(maxPlannedTarget([])).toBe(0);
  });
});

describe("staircasePath", () => {
  it("joins runs with vertical risers", () => {
    const segs = plannedStaircase(v60.slice(0, 2), 60_000);
    const d = staircasePath(
      segs,
      (t) => t / 1000,
      (g) => 300 - g,
    );
    expect(d).toBe("M 0.0 255.0 L 45.0 255.0 L 45.0 150.0 L 60.0 150.0");
  });

  it("is empty for no segments", () => {
    expect(
      staircasePath(
        [],
        (t) => t,
        (g) => g,
      ),
    ).toBe("");
  });
});
