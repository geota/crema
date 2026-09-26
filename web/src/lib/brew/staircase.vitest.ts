import { describe, expect, it } from "vitest";
import type { StageMark } from "$lib/core/crema-core";
import { maxPlannedTarget, plannedStaircase, staircasePath } from "./staircase";

/** V60: bloom 45 → pour 150 → wait → pour 250 → drawdown. */
const v60: StageMark[] = [
  { elapsedMs: 0, stepIndex: 0, targetWaterG: 45 },
  { elapsedMs: 45_000, stepIndex: 1, targetWaterG: 150 },
  { elapsedMs: 75_000, stepIndex: 2 },
  { elapsedMs: 100_000, stepIndex: 3, targetWaterG: 250 },
  { elapsedMs: 130_000, stepIndex: 4 },
];

describe("plannedStaircase", () => {
  it("builds a V60 staircase, carrying targets through timed steps", () => {
    expect(plannedStaircase(v60, 180_000)).toEqual([
      { t0Ms: 0, t1Ms: 45_000, targetG: 45 },
      { t0Ms: 45_000, t1Ms: 75_000, targetG: 150 },
      { t0Ms: 75_000, t1Ms: 100_000, targetG: 150 },
      { t0Ms: 100_000, t1Ms: 130_000, targetG: 250 },
      { t0Ms: 130_000, t1Ms: 180_000, targetG: 250 },
    ]);
    expect(maxPlannedTarget(v60)).toBe(250);
  });

  it("follows a skip to the skipped-to step’s target, in any input order", () => {
    // Bloom skipped at 20 s straight into the 250 g pour.
    const marks: StageMark[] = [
      { elapsedMs: 20_000, stepIndex: 1, targetWaterG: 250 },
      { elapsedMs: 0, stepIndex: 0, targetWaterG: 45 },
      { elapsedMs: 60_000, stepIndex: 3 },
    ];
    expect(plannedStaircase(marks, 90_000)).toEqual([
      { t0Ms: 0, t1Ms: 20_000, targetG: 45 },
      { t0Ms: 20_000, t1Ms: 60_000, targetG: 250 },
      { t0Ms: 60_000, t1Ms: 90_000, targetG: 250 },
    ]);
  });

  it("is empty with no targets (manual logs, old records)", () => {
    const old: StageMark[] = [
      { elapsedMs: 0, stepIndex: 0 },
      { elapsedMs: 30_000, stepIndex: 1 },
    ];
    expect(plannedStaircase(old, 60_000)).toEqual([]);
    expect(plannedStaircase([], 60_000)).toEqual([]);
    expect(maxPlannedTarget(old)).toBe(0);
  });

  it("starts at the first target, not before it", () => {
    const marks: StageMark[] = [
      { elapsedMs: 0, stepIndex: 0 }, // a timed pre-wet with no target
      { elapsedMs: 10_000, stepIndex: 1, targetWaterG: 60 },
    ];
    expect(plannedStaircase(marks, 30_000)).toEqual([
      { t0Ms: 10_000, t1Ms: 30_000, targetG: 60 },
    ]);
  });

  it("ends a live series at the session clock, mid-step", () => {
    const live = v60.slice(0, 2); // reached the 150 g pour, 52 s in
    expect(plannedStaircase(live, 52_000)).toEqual([
      { t0Ms: 0, t1Ms: 45_000, targetG: 45 },
      { t0Ms: 45_000, t1Ms: 52_000, targetG: 150 },
    ]);
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
