import { describe, expect, it } from "vitest";
import {
  mergeHealthDays, sumMeasured, averageMeasured, measuredCount,
} from "../src/lib/healthProviders";
import type { MeasuredDailyMetric } from "../src/lib/domain";

/**
 * H-14: null is not zero.
 *
 * The previous merge defaulted every absent field to 0, which turned "this source reported no
 * heart rate" into "the user's resting heart rate is 0" - a fabricated measurement that would then
 * reach analytics, the progress charts and the AI Coach. These tests pin the distinction in both
 * directions: a missing value stays missing, and a real measured zero stays zero.
 */

const day = (over: Partial<MeasuredDailyMetric> = {}): MeasuredDailyMetric => ({
  id: "d1",
  metric_date: "2026-09-10",
  steps: null,
  sleep_hours: null,
  calories_burned: null,
  water_oz: null,
  resting_heart_rate: null,
  readiness: null,
  hrv: null,
  active_minutes: null,
  stress_level: null,
  ...over,
});

describe("mergeHealthDays preserves null", () => {
  it("keeps a null field null instead of inventing a zero", () => {
    const merged = mergeHealthDays([day()], [
      { metric_date: "2026-09-10", steps: 5000, sleep_hours: null, resting_heart_rate: null,
        hrv: null, calories_burned: null, active_minutes: null, source: "fitbit" },
    ]);
    expect(merged[0].sleep_hours).toBeNull();
    expect(merged[0].hrv).toBeNull();
    expect(merged[0].resting_heart_rate).toBeNull();
  });

  it("applies a reported value over a null", () => {
    const merged = mergeHealthDays([day()], [
      { metric_date: "2026-09-10", steps: 5000, sleep_hours: 7.5, resting_heart_rate: null,
        hrv: null, calories_burned: null, active_minutes: null, source: "fitbit" },
    ]);
    expect(merged[0].steps).toBe(5000);
    expect(merged[0].sleep_hours).toBe(7.5);
  });

  it("distinguishes a reported zero from a missing value", () => {
    const merged = mergeHealthDays([day({ steps: 4000 })], [
      { metric_date: "2026-09-10", steps: 0, sleep_hours: null, resting_heart_rate: null,
        hrv: null, calories_burned: null, active_minutes: null, source: "fitbit" },
    ]);
    // Zero is a measurement and must overwrite; null is absence and must not.
    expect(merged[0].steps).toBe(0);
  });

  it("leaves an existing value alone when the source reports nothing", () => {
    const merged = mergeHealthDays([day({ steps: 4000, sleep_hours: 8 })], [
      { metric_date: "2026-09-10", steps: null, sleep_hours: null, resting_heart_rate: null,
        hrv: null, calories_burned: null, active_minutes: null, source: "fitbit" },
    ]);
    expect(merged[0].steps).toBe(4000);
    expect(merged[0].sleep_hours).toBe(8);
  });

  it("appends a new day without filling absent fields with zero", () => {
    const merged = mergeHealthDays([], [
      { metric_date: "2026-09-11", steps: 3000, sleep_hours: null, resting_heart_rate: null,
        hrv: null, calories_burned: null, active_minutes: null, source: "health-connect" },
    ]);
    expect(merged).toHaveLength(1);
    expect(merged[0].steps).toBe(3000);
    expect(merged[0].sleep_hours).toBeNull();
    expect(merged[0].water_oz).toBeNull();
  });
});

describe("aggregation does not turn missing data into zero", () => {
  it("sums only measured values", () => {
    expect(sumMeasured([1000, 2000, 3000])).toBe(6000);
    expect(sumMeasured([1000, null, 3000])).toBe(4000);
  });

  it("returns null rather than zero when nothing was measured", () => {
    // The distinction that matters: a total of 0 and an absent total are different answers.
    expect(sumMeasured([null, null, undefined])).toBeNull();
    expect(sumMeasured([])).toBeNull();
  });

  it("returns 0 only when a real zero was measured", () => {
    expect(sumMeasured([0, 0])).toBe(0);
  });

  it("averages only measured days", () => {
    expect(averageMeasured([10, 20, null])).toBe(15);
    expect(averageMeasured([null, null])).toBeNull();
  });

  it("reports how many days were actually measured", () => {
    expect(measuredCount([1, null, 3, undefined, 0])).toBe(3);
    expect(measuredCount([null, null])).toBe(0);
  });

  it("does not count a missing day toward an average", () => {
    // Averages over 7 days where 3 have sleep would otherwise divide a real total by 7.
    const week: (number | null)[] = [8, null, 7, null, 9, null, 8];
    expect(averageMeasured(week)).toBe(8);
    expect(measuredCount(week)).toBe(4);
  });
});
