import type { Meal, DailyMetric, Workout } from "./domain";
import type { Habit, HabitLog } from "./types";
import { todayISO } from "./utils";

export type DailyNutrition = { calories: number; protein: number; carbs: number; fat: number };

/** Nutrition totals for a single day, used by the dashboard. */
export function todayNutrition(meals: Meal[], date = todayISO()): DailyNutrition {
  return meals
    .filter((m) => m.meal_date === date)
    .reduce(
      (acc, m) => ({
        calories: acc.calories + Number(m.calories ?? 0),
        protein: acc.protein + Number(m.protein_g ?? 0),
        carbs: acc.carbs + Number(m.carbs_g ?? 0),
        fat: acc.fat + Number(m.fat_g ?? 0),
      }),
      { calories: 0, protein: 0, carbs: 0, fat: 0 }
    );
}

export function habitsCompletedToday(habits: Habit[], logs: HabitLog[], date = todayISO()): number {
  const done = new Set(logs.filter((l) => l.log_date === date && l.completed).map((l) => l.habit_id));
  return habits.filter((h) => done.has(h.id)).length;
}

export function sessionsToday(workouts: Workout[], date = todayISO()): number {
  return workouts.filter((w) => w.workout_date === date && w.completed).length;
}

export function sleepTrendLabel(metric: DailyMetric | null, target: number): string {
  if (!metric) return "No sleep data yet";
  const diff = metric.sleep_hours - target;
  if (diff >= 0.5) return `${diff.toFixed(1)} hrs above your target`;
  if (diff <= -1) return `${Math.abs(diff).toFixed(1)} hrs below your target`;
  return "Close to your target";
}


