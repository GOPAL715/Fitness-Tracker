import { describe, it, expect } from "vitest";
import { calculateNutrition, sumNutrition, matchFood, isValidImageFile } from "../src/lib/nutrition";
import { parseDetections, confidenceLabel, needsReview } from "../src/lib/foodScan";
import {
  computeSessionMetrics, estimateOneRepMax, bestOneRepMax, muscleDistribution,
  underTrainedMuscles, habitStreak, weeklyVolumeSeries, type SessionWithDetail,
} from "../src/lib/workoutMetrics";
import { computeReadiness, computeTrainingLoad, computeStreak, macroTotals, computeConsistency } from "../src/lib/insights";
import { computeGoalProgress } from "../src/views/GoalsView";
import { parseDetections as reparse } from "../src/lib/foodScan";
import type { Food, Goal, Habit, HabitLog, Meal } from "../src/lib/types";
import type { DailyMetric, Workout } from "../src/lib/supabase";
import { dateOffset, todayISO, round } from "../src/lib/utils";

/* ---------- Test fixtures ---------- */

const rice: Food = {
  id: "rice", name: "Cooked white rice", category: "Grains", serving_size: 100, serving_unit: "g",
  calories: 130, protein_g: 2.7, carbs_g: 28, fat_g: 0.3, fiber_g: 0.4, sugar_g: 0.1, sodium_mg: 1, source: "fittrack",
};

const chicken: Food = {
  id: "chicken", name: "Chicken curry", category: "Poultry", serving_size: 100, serving_unit: "g",
  calories: 150, protein_g: 14, carbs_g: 6, fat_g: 8, fiber_g: 1, sugar_g: 2, sodium_mg: 380, source: "fittrack",
};

function metric(overrides: Partial<DailyMetric>): DailyMetric {
  return {
    id: "m", metric_date: todayISO(), steps: 8000, sleep_hours: 8, calories_burned: 500,
    water_oz: 64, resting_heart_rate: 55, readiness: 80, hrv: 60, active_minutes: 40, stress_level: 3,
    ...overrides,
  };
}

function workout(overrides: Partial<Workout>): Workout {
  return {
    id: "w", title: "Session", workout_type: "Strength", duration_minutes: 45, calories_burned: 300,
    intensity: "Moderate", workout_date: todayISO(), completed: true, notes: null,
    perceived_effort: 7, distance_miles: null, created_at: new Date().toISOString(),
    ...overrides,
  };
}

function session(volumeSets: { reps: number; weight: number }[], muscle = "Chest"): SessionWithDetail {
  return {
    id: "s", user_id: "u", title: "Push", workout_type: "Strength",
    started_at: new Date().toISOString(), completed_at: new Date().toISOString(),
    duration_minutes: 45, notes: null, perceived_effort: 7, completed: true,
    created_at: new Date().toISOString(),
    exercises: [{
      id: "we", workout_session_id: "s", exercise_id: "e", order_index: 0, notes: null,
      exercise: {
        id: "e", name: "Bench", description: "", muscle_group: muscle, secondary_muscles: [],
        equipment: "Barbell", difficulty: "Intermediate", instructions: "", is_compound: true,
      },
      sets: volumeSets.map((s, i) => ({
        id: `set${i}`, workout_exercise_id: "we", set_number: i + 1, reps: s.reps, weight: s.weight,
        weight_unit: "lbs", duration_seconds: null, distance: null, distance_unit: null, rpe: null, completed: true,
      })),
    }],
  };
}

/* ---------- Nutrition calculations ---------- */

describe("calculateNutrition", () => {
  it("scales per-100g values by portion weight", () => {
    const n = calculateNutrition(rice, 200);
    expect(n.calories).toBe(260);
    expect(n.protein_g).toBe(5.4);
    expect(n.carbs_g).toBe(56);
    expect(n.fat_g).toBe(0.6);
    expect(n.fiber_g).toBe(0.8);
  });

  it("handles a 100g portion as identity", () => {
    const n = calculateNutrition(rice, 100);
    expect(n.calories).toBe(130);
    expect(n.protein_g).toBe(2.7);
  });

  it("returns zeros for zero, negative and non-finite portions", () => {
    for (const grams of [0, -50, NaN, Infinity]) {
      const n = calculateNutrition(rice, grams);
      expect(n.calories).toBe(0);
      expect(n.protein_g).toBe(0);
    }
  });

  it("scales fractional portions correctly", () => {
    const n = calculateNutrition(rice, 50);
    expect(n.calories).toBe(65);
    expect(n.protein_g).toBe(1.4);
  });
});

describe("sumNutrition", () => {
  it("sums multiple items", () => {
    const total = sumNutrition([calculateNutrition(rice, 200), calculateNutrition(chicken, 150)]);
    expect(total.calories).toBe(485);
    expect(total.protein_g).toBe(26.4);
  });

  it("returns zeros for an empty list", () => {
    expect(sumNutrition([]).calories).toBe(0);
  });

  it("is deterministic across repeated calls", () => {
    const a = sumNutrition([calculateNutrition(rice, 137)]);
    const b = sumNutrition([calculateNutrition(rice, 137)]);
    expect(a).toEqual(b);
  });
});

describe("macroTotals", () => {
  it("totals meals for the day", () => {
    const meals: Meal[] = [
      { id: "a", meal_date: todayISO(), meal_type: "Lunch", name: "L", calories: 500, protein_g: 30, carbs_g: 50, fat_g: 15, fiber_g: 5, source: "manual", notes: null, created_at: "" },
      { id: "b", meal_date: todayISO(), meal_type: "Dinner", name: "D", calories: 600, protein_g: 40, carbs_g: 60, fat_g: 20, fiber_g: 8, source: "scanner", notes: null, created_at: "" },
    ];
    const t = macroTotals(meals);
    expect(t.calories).toBe(1100);
    expect(t.protein).toBe(70);
  });

  it("handles no meals", () => {
    expect(macroTotals([]).protein).toBe(0);
  });
});

/* ---------- Food matching ---------- */

describe("matchFood", () => {
  const foods = [rice, chicken];

  it("matches exactly", () => {
    expect(matchFood("Cooked white rice", foods)?.id).toBe("rice");
  });

  it("matches case-insensitively", () => {
    expect(matchFood("CHICKEN CURRY", foods)?.id).toBe("chicken");
  });

  it("matches a partial phrase", () => {
    expect(matchFood("white rice", foods)?.id).toBe("rice");
  });

  it("returns null for unrelated text", () => {
    expect(matchFood("chocolate cake", foods)).toBeNull();
  });

  it("returns null for empty input", () => {
    expect(matchFood("", foods)).toBeNull();
  });
});

/* ---------- AI response validation ---------- */

describe("parseDetections", () => {
  it("accepts a well formed response", () => {
    const r = parseDetections({ items: [{ name: "Cooked white rice", estimated_grams: 200, confidence: 0.93 }] });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.items[0].estimated_grams).toBe(200);
  });

  it("accepts a JSON string", () => {
    const r = parseDetections('{"items":[{"name":"Dal","estimated_grams":150,"confidence":0.8}]}');
    expect(r.ok).toBe(true);
  });

  it("rejects malformed JSON", () => {
    const r = parseDetections("{not json");
    expect(r.ok).toBe(false);
  });

  it("rejects a missing items array", () => {
    expect(parseDetections({ food: "rice" }).ok).toBe(false);
  });

  it("rejects empty item lists", () => {
    expect(parseDetections({ items: [] }).ok).toBe(false);
  });

  it("rejects null and undefined", () => {
    expect(parseDetections(null).ok).toBe(false);
    expect(parseDetections(undefined).ok).toBe(false);
  });

  it("drops entries with invalid weights", () => {
    const r = parseDetections({ items: [
      { name: "Rice", estimated_grams: 200, confidence: 0.9 },
      { name: "Dal", estimated_grams: -5, confidence: 0.9 },
      { name: "Curd", estimated_grams: 99999, confidence: 0.9 },
    ] });
    expect(r.ok).toBe(true);
    if (r.ok) {
      expect(r.items).toHaveLength(1);
      expect(r.items[0].name).toBe("Rice");
    }
  });

  it("clamps out of range confidence", () => {
    const r = parseDetections({ items: [{ name: "Rice", estimated_grams: 200, confidence: 4.2 }] });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.items[0].confidence).toBe(1);
  });

  it("defaults missing confidence rather than failing", () => {
    const r = parseDetections({ items: [{ name: "Rice", estimated_grams: 200 }] });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.items[0].confidence).toBe(0.5);
  });

  it("rejects too many items", () => {
    const items = Array.from({ length: 20 }, (_, i) => ({ name: `Food ${i}`, estimated_grams: 100, confidence: 0.9 }));
    expect(parseDetections({ items }).ok).toBe(false);
  });

  it("rejects when every entry is unusable", () => {
    expect(parseDetections({ items: [{ name: "x", estimated_grams: 0 }] }).ok).toBe(false);
  });
});

describe("confidenceLabel / needsReview", () => {
  it("labels confidence tiers", () => {
    expect(confidenceLabel(0.95).tone).toBe("high");
    expect(confidenceLabel(0.6).tone).toBe("medium");
    expect(confidenceLabel(0.2).tone).toBe("low");
  });

  it("treats null confidence as low and needing review", () => {
    expect(confidenceLabel(null).tone).toBe("low");
    expect(needsReview([{ confidence: null }])).toBe(true);
  });

  it("flags low confidence lists", () => {
    expect(needsReview([{ confidence: 0.9 }, { confidence: 0.3 }])).toBe(true);
    expect(needsReview([{ confidence: 0.9 }, { confidence: 0.8 }])).toBe(false);
  });
});

/* ---------- Image validation ---------- */

describe("isValidImageFile", () => {
  function file(type: string, size: number): File {
    return new File([new Uint8Array(size)], "photo", { type });
  }

  it("accepts a small jpeg", () => {
    expect(isValidImageFile(file("image/jpeg", 1024)).ok).toBe(true);
  });

  it("rejects a non-image type", () => {
    expect(isValidImageFile(file("application/pdf", 1024)).ok).toBe(false);
  });

  it("rejects an oversized image", () => {
    const r = isValidImageFile(file("image/png", 9 * 1024 * 1024));
    expect(r.ok).toBe(false);
    expect(r.reason).toBeTruthy();
  });
});

/* ---------- Workout metrics ---------- */

describe("estimateOneRepMax", () => {
  it("returns the weight itself for a single rep", () => {
    expect(estimateOneRepMax(200, 1)).toBe(200);
  });

  it("applies Epley for multiple reps", () => {
    expect(estimateOneRepMax(100, 10)).toBe(133.3);
  });

  it("returns null for bodyweight or invalid input", () => {
    expect(estimateOneRepMax(null, 10)).toBeNull();
    expect(estimateOneRepMax(100, null)).toBeNull();
    expect(estimateOneRepMax(0, 10)).toBeNull();
    expect(estimateOneRepMax(100, 0)).toBeNull();
  });

  it("returns null above the reliable rep range", () => {
    expect(estimateOneRepMax(100, 20)).toBeNull();
  });
});

describe("computeSessionMetrics", () => {
  it("computes sets, reps and volume", () => {
    const s = session([{ reps: 10, weight: 100 }, { reps: 8, weight: 100 }]);
    const m = computeSessionMetrics(s);
    expect(m.totalSets).toBe(2);
    expect(m.totalReps).toBe(18);
    expect(m.totalVolume).toBe(1800);
  });

  it("tracks the heaviest lift", () => {
    const s = session([{ reps: 5, weight: 225 }, { reps: 8, weight: 135 }]);
    expect(computeSessionMetrics(s).heaviestLift?.weight).toBe(225);
  });

  it("handles an empty session", () => {
    const m = computeSessionMetrics(session([]));
    expect(m.totalVolume).toBe(0);
    expect(m.heaviestLift).toBeNull();
  });

  it("ignores incomplete sets", () => {
    const s = session([{ reps: 10, weight: 100 }]);
    s.exercises[0].sets[0].completed = false;
    expect(computeSessionMetrics(s).totalVolume).toBe(0);
  });

  it("counts bodyweight reps without adding volume", () => {
    const s = session([{ reps: 20, weight: 0 }]);
    const m = computeSessionMetrics(s);
    expect(m.totalReps).toBe(20);
    expect(m.totalVolume).toBe(0);
  });
});

describe("bestOneRepMax", () => {
  it("picks the best estimated max across sets", () => {
    const s = session([{ reps: 5, weight: 200 }, { reps: 10, weight: 150 }]);
    const best = bestOneRepMax(s.exercises[0].sets);
    expect(best).toBeGreaterThan(200);
  });

  it("returns null when nothing is loaded", () => {
    const s = session([{ reps: 10, weight: 0 }]);
    expect(bestOneRepMax(s.exercises[0].sets)).toBeNull();
  });
});

describe("muscleDistribution / underTrainedMuscles", () => {
  it("computes share of total sets", () => {
    const d = muscleDistribution([session([{ reps: 10, weight: 100 }, { reps: 10, weight: 100 }], "Chest")]);
    expect(d).toHaveLength(1);
    expect(d[0].muscle).toBe("Chest");
    expect(d[0].share).toBe(100);
  });

  it("returns nothing for empty sessions", () => {
    expect(muscleDistribution([])).toEqual([]);
  });

  it("flags core groups with too few sets", () => {
    const lagging = underTrainedMuscles([{ muscle: "Chest", sets: 10 }]);
    expect(lagging).toContain("Back");
    expect(lagging).not.toContain("Chest");
  });
});

describe("weeklyVolumeSeries", () => {
  it("returns the requested number of buckets", () => {
    expect(weeklyVolumeSeries([], 8)).toHaveLength(8);
  });

  it("places this session in the current bucket", () => {
    const series = weeklyVolumeSeries([session([{ reps: 10, weight: 100 }])], 4);
    expect(series[series.length - 1].value).toBe(1000);
  });

  it("produces zeros with no sessions", () => {
    expect(weeklyVolumeSeries([], 3).every((b) => b.value === 0)).toBe(true);
  });
});

/* ---------- Insights ---------- */

describe("computeReadiness", () => {
  it("returns 0 without a metric", () => {
    expect(computeReadiness(null, [])).toBe(0);
  });

  it("scores a well recovered day highly", () => {
    const r = computeReadiness(metric({ sleep_hours: 8, hrv: 65, resting_heart_rate: 52, stress_level: 2 }), [
      metric({ hrv: 60, resting_heart_rate: 55 }),
    ]);
    expect(r).toBeGreaterThan(70);
  });

  it("scores a poor day lower than a good one", () => {
    const poor = computeReadiness(metric({ sleep_hours: 4, hrv: 30, resting_heart_rate: 75, stress_level: 9 }), [
      metric({ hrv: 60, resting_heart_rate: 55 }),
    ]);
    const good = computeReadiness(metric({ sleep_hours: 8, hrv: 65, resting_heart_rate: 52, stress_level: 2 }), [
      metric({ hrv: 60, resting_heart_rate: 55 }),
    ]);
    expect(poor).toBeLessThan(good);
    expect(poor).toBeLessThan(60);
  });

  it("stays within 0 to 100", () => {
    const r = computeReadiness(metric({ sleep_hours: 14, hrv: 300, resting_heart_rate: 40, stress_level: 0 }), []);
    expect(r).toBeLessThanOrEqual(100);
    expect(r).toBeGreaterThanOrEqual(0);
  });
});

describe("computeTrainingLoad", () => {
  it("labels a light week", () => {
    expect(computeTrainingLoad([workout({ duration_minutes: 20, perceived_effort: 4 })]).label).toBe("Light");
  });

  it("labels a very high load week", () => {
    const heavy = Array.from({ length: 12 }, () => workout({ duration_minutes: 90, perceived_effort: 9 }));
    expect(computeTrainingLoad(heavy).label).toBe("Very high");
  });

  it("labels a moderate load week", () => {
    const moderate = Array.from({ length: 5 }, () => workout({ duration_minutes: 90, perceived_effort: 9 }));
    expect(computeTrainingLoad(moderate).label).toBe("Moderate");
  });

  it("returns zero load for no workouts", () => {
    expect(computeTrainingLoad([]).load).toBe(0);
  });
});

describe("computeStreak / computeConsistency", () => {
  it("counts consecutive days from today", () => {
    const workouts = [workout({ workout_date: todayISO() }), workout({ workout_date: dateOffset(1) })];
    expect(computeStreak(workouts)).toBe(2);
  });

  it("is zero with no workouts", () => {
    expect(computeStreak([])).toBe(0);
  });

  it("ignores uncompleted workouts", () => {
    expect(computeStreak([workout({ completed: false })])).toBe(0);
  });

  it("computes consistency as a percentage", () => {
    const workouts = Array.from({ length: 7 }, (_, i) => workout({ workout_date: dateOffset(i) }));
    expect(computeConsistency(workouts, 28)).toBe(25);
  });
});

/* ---------- Goals ---------- */

describe("computeGoalProgress", () => {
  function goal(overrides: Partial<Goal>): Goal {
    return {
      id: "g", user_id: "u", goal_type: "Build Strength", title: "Bench", description: "",
      start_value: 100, target_value: 200, current_value: 100, unit: "lbs",
      start_date: dateOffset(10), target_date: null, status: "active", created_at: "",
      ...overrides,
    };
  }

  it("computes percentage complete", () => {
    expect(computeGoalProgress(goal({ current_value: 150 })).pct).toBe(50);
  });

  it("reports remaining amount", () => {
    expect(computeGoalProgress(goal({ current_value: 150 })).remaining).toBe(50);
  });

  it("caps at 100 when exceeded", () => {
    expect(computeGoalProgress(goal({ current_value: 250 })).pct).toBe(100);
  });

  it("handles a zero span without dividing by zero", () => {
    const p = computeGoalProgress(goal({ start_value: 100, target_value: 100, current_value: 100 }));
    expect(Number.isFinite(p.pct)).toBe(true);
    expect(p.pct).toBe(100);
  });

  it("handles a decreasing goal", () => {
    const p = computeGoalProgress(goal({ start_value: 200, target_value: 150, current_value: 175 }));
    expect(p.direction).toBe("down");
    expect(p.pct).toBe(50);
  });

  it("does not project when there is no progress", () => {
    expect(computeGoalProgress(goal({ current_value: 100 })).estimatedDate).toBeNull();
  });

  it("projects a date only when progress exists", () => {
    const p = computeGoalProgress(goal({ current_value: 150 }));
    expect(p.estimatedDate).toBeTruthy();
  });
});

/* ---------- Habits ---------- */

describe("habitStreak", () => {
  const habit: Habit = {
    id: "h", user_id: "u", name: "Water", description: "", icon: "droplet",
    target_per_week: 7, color: "#38bdf8", active: true,
  };

  function log(date: string): HabitLog {
    return { id: `l-${date}`, user_id: "u", habit_id: "h", log_date: date, completed: true };
  }

  it("counts consecutive completed days", () => {
    expect(habitStreak(habit.id, [log(todayISO()), log(dateOffset(1)), log(dateOffset(2))])).toBe(3);
  });

  it("is zero with no logs", () => {
    expect(habitStreak(habit.id, [])).toBe(0);
  });

  it("ignores other habits", () => {
    const other: HabitLog = { id: "x", user_id: "u", habit_id: "other", log_date: todayISO(), completed: true };
    expect(habitStreak(habit.id, [other])).toBe(0);
  });

  it("stops at the first gap", () => {
    expect(habitStreak(habit.id, [log(todayISO()), log(dateOffset(2))])).toBe(1);
  });
});

/* ---------- Utilities ---------- */

describe("utility edge cases", () => {
  it("rounds to the requested precision", () => {
    expect(round(1.2345, 2)).toBe(1.23);
    expect(round(2.5, 0)).toBe(3);
  });

  it("produces an ISO date string", () => {
    expect(todayISO()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("offsets dates backwards", () => {
    expect(dateOffset(0)).toBe(todayISO());
    expect(dateOffset(1) < todayISO()).toBe(true);
  });
});

/* ---------- Round trip: scan to meal ---------- */

describe("scan to meal data flow", () => {
  it("converts validated detections into meal-ready nutrition", () => {
    const foods = [rice, chicken];
    const raw = {
      items: [
        { name: "Cooked white rice", estimated_grams: 200, confidence: 0.93 },
        { name: "Chicken curry", estimated_grams: 150, confidence: 0.84 },
      ],
    };

    const parsed = reparse(raw);
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;

    const items = parsed.items.map((d) => {
      const food = matchFood(d.name, foods);
      return { name: d.name, foodId: food?.id ?? null, grams: d.estimated_grams, nutrition: food ? calculateNutrition(food, d.estimated_grams) : null };
    });

    expect(items).toHaveLength(2);
    expect(items[0].foodId).toBe("rice");
    expect(items[1].foodId).toBe("chicken");

    const total = sumNutrition(items.map((i) => i.nutrition!).filter(Boolean));
    // 200g rice (260 kcal) + 150g chicken curry (225 kcal)
    expect(total.calories).toBe(485);
    expect(total.protein_g).toBe(26.4);
  });

  it("still produces a usable total when one item is unmatched", () => {
    const foods = [rice];
    const parsed = parseDetections({
      items: [
        { name: "Cooked white rice", estimated_grams: 100, confidence: 0.9 },
        { name: "Mystery sauce", estimated_grams: 50, confidence: 0.4 },
      ],
    });
    expect(parsed.ok).toBe(true);
    if (!parsed.ok) return;

    const matched = parsed.items.map((d) => matchFood(d.name, foods));
    expect(matched[0]).not.toBeNull();
    expect(matched[1]).toBeNull();
    expect(needsReview(parsed.items)).toBe(true);
  });
});
