/**
 * Application data access.
 *
 * All reads for the signed-in user live here so the app shell and the views stay
 * free of query logic. The shape returned by `fetchAppData` is the single source
 * of truth for what the application has loaded.
 */

import {
  type Workout, type DailyMetric, type Profile, type Meal, type BodyMetric,
  type PersonalRecord, type PlanSession, type HealthDevice, type CoachNotification,
} from "../../lib/domain";
import type {
  Exercise, ExerciseSet, WorkoutExercise, WorkoutSession, WorkoutTemplate,
  TemplateExercise, Goal, Food, MealItem, Habit, HabitLog, Reminder,
} from "../../lib/types";
import type { SessionWithDetail } from "../../lib/workoutMetrics";
import { dateOffset } from "../../lib/utils";

export type TemplateWithItems = WorkoutTemplate & {
  items: (TemplateExercise & { exercise: Exercise | null })[];
};

export type AppData = {
  profile: Profile | null;
  metrics: DailyMetric[];
  workouts: Workout[];
  meals: Meal[];
  mealItems: MealItem[];
  records: PersonalRecord[];
  body: BodyMetric[];
  plan: PlanSession[];
  devices: HealthDevice[];
  notifications: CoachNotification[];
  exercises: Exercise[];
  sessions: SessionWithDetail[];
  templates: TemplateWithItems[];
  goals: Goal[];
  foods: Food[];
  habits: Habit[];
  habitLogs: HabitLog[];
  reminders: Reminder[];
};

export const EMPTY_APP_DATA: AppData = {
  profile: null,
  metrics: [],
  workouts: [],
  meals: [],
  mealItems: [],
  records: [],
  body: [],
  plan: [],
  devices: [],
  notifications: [],
  exercises: [],
  sessions: [],
  templates: [],
  goals: [],
  foods: [],
  habits: [],
  habitLogs: [],
  reminders: [],
};

/** The seeded profile name means the account has not completed setup yet. */
export function needsOnboarding(profile: Profile | null): boolean {
  return Boolean(profile && profile.display_name === "Alex Morgan");
}

/**
 * Joins sessions to their exercises and sets in memory.
 *
 * Kept pure and separate from the querying so the join can be tested without a
 * database, and so a missing exercise or set can never throw mid-render.
 */
export function hydrateSessions(
  rawSessions: WorkoutSession[],
  weRows: WorkoutExercise[],
  setRows: ExerciseSet[],
  exercisesById: Map<string, Exercise>
): SessionWithDetail[] {
  const setsByWorkoutExercise = new Map<string, ExerciseSet[]>();
  for (const s of setRows) {
    const list = setsByWorkoutExercise.get(s.workout_exercise_id) ?? [];
    list.push(s);
    setsByWorkoutExercise.set(s.workout_exercise_id, list);
  }

  const weBySession = new Map<string, WorkoutExercise[]>();
  for (const w of weRows) {
    const list = weBySession.get(w.workout_session_id) ?? [];
    list.push(w);
    weBySession.set(w.workout_session_id, list);
  }

  return rawSessions.map((s) => ({
    ...s,
    exercises: (weBySession.get(s.id) ?? []).map((w) => ({
      ...w,
      exercise: exercisesById.get(w.exercise_id) ?? null,
      sets: setsByWorkoutExercise.get(w.id) ?? [],
    })),
  }));
}

/** Joins templates to their exercises in memory. */
export function hydrateTemplates(
  rawTemplates: WorkoutTemplate[],
  teRows: TemplateExercise[],
  exercisesById: Map<string, Exercise>
): TemplateWithItems[] {
  const itemsByTemplate = new Map<string, TemplateExercise[]>();
  for (const i of teRows) {
    const list = itemsByTemplate.get(i.template_id) ?? [];
    list.push(i);
    itemsByTemplate.set(i.template_id, list);
  }

  return rawTemplates.map((t) => ({
    ...t,
    items: (itemsByTemplate.get(t.id) ?? []).map((i) => ({
      ...i,
      exercise: exercisesById.get(i.exercise_id) ?? null,
    })),
  }));
}

/**
 * Loads everything the application renders.
 *
 * The first pass is a single parallel batch; the nested session and template
 * children are then fetched in two follow-up queries keyed on the ids just
 * returned. That is a fixed number of round trips rather than a per-row N+1.
 */
export async function fetchAppData(_userId: string): Promise<AppData> {
  const { getAppData, addWater: logWater } = await import("../../lib/api/data");
  return getAppData() as Promise<AppData>;
}
export async function addWater(_userId: string, metric: DailyMetric | null, oz: number): Promise<void> {
  const { addWater: logWater } = await import("../../lib/api/data"); await logWater(metric, oz);
}


