import type { Exercise, ExerciseSet, WorkoutExercise, WorkoutSession, WorkoutTemplate, TemplateExercise, Habit, HabitLog } from "./types";
import { round, dateOffset } from "./utils";

export type SessionWithDetail = WorkoutSession & {
  exercises: (WorkoutExercise & { exercise: Exercise | null; sets: ExerciseSet[] })[];
};

export type SessionMetrics = {
  totalSets: number;
  totalReps: number;
  totalVolume: number;
  heaviestLift: { exercise: string; weight: number; reps: number } | null;
};

/** Volume is weight times reps, summed across every completed set. */
export function computeSessionMetrics(session: SessionWithDetail): SessionMetrics {
  let totalSets = 0;
  let totalReps = 0;
  let totalVolume = 0;
  let heaviest: SessionMetrics["heaviestLift"] = null;

  for (const we of session.exercises) {
    for (const s of we.sets) {
      if (!s.completed) continue;
      totalSets += 1;
      const reps = s.reps ?? 0;
      const weight = s.weight ?? 0;
      totalReps += reps;
      totalVolume += weight * reps;
      if (weight > 0 && (!heaviest || weight > heaviest.weight)) {
        heaviest = { exercise: we.exercise?.name ?? "Exercise", weight, reps };
      }
    }
  }

  return { totalSets, totalReps, totalVolume: round(totalVolume, 1), heaviestLift: heaviest };
}

/**
 * Epley estimated one-rep max. Bodyweight sets return null rather than a
 * misleading number, and very high rep sets are excluded as unreliable.
 */
export function estimateOneRepMax(weight: number | null, reps: number | null): number | null {
  if (!weight || !reps || weight <= 0 || reps <= 0) return null;
  if (reps === 1) return round(weight, 1);
  if (reps > 15) return null;
  return round(weight * (1 + reps / 30), 1);
}

export function bestOneRepMax(sets: ExerciseSet[]): number | null {
  let best: number | null = null;
  for (const s of sets) {
    if (!s.completed) continue;
    const orm = estimateOneRepMax(s.weight, s.reps);
    if (orm !== null && (best === null || orm > best)) best = orm;
  }
  return best;
}

export function weeklyVolumeSeries(sessions: SessionWithDetail[], weeks = 8): { label: string; value: number }[] {
  const out: { label: string; value: number }[] = [];
  for (let i = weeks - 1; i >= 0; i--) {
    const start = dateOffset(i * 7 + 6);
    const end = dateOffset(i * 7);
    const value = sessions
      .filter((s) => s.started_at.slice(0, 10) >= start && s.started_at.slice(0, 10) <= end)
      .reduce((sum, s) => sum + computeSessionMetrics(s).totalVolume, 0);
    out.push({ label: i === 0 ? "Now" : `-${i}w`, value: Math.round(value) });
  }
  return out;
}

export function muscleDistribution(sessions: SessionWithDetail[]): { muscle: string; sets: number; share: number }[] {
  const counts = new Map<string, number>();
  for (const s of sessions) {
    for (const we of s.exercises) {
      const group = we.exercise?.muscle_group ?? "Full Body";
      const sets = we.sets.filter((x) => x.completed).length;
      counts.set(group, (counts.get(group) ?? 0) + sets);
    }
  }
  const total = Array.from(counts.values()).reduce((a, b) => a + b, 0);
  if (!total) return [];
  return Array.from(counts.entries())
    .map(([muscle, sets]) => ({ muscle, sets, share: Math.round((sets / total) * 100) }))
    .sort((a, b) => b.sets - a.sets);
}

export function underTrainedMuscles(distribution: { muscle: string; sets: number }[]): string[] {
  const core = ["Chest", "Back", "Shoulders", "Arms", "Legs", "Core"];
  const counts = new Map(distribution.map((d) => [d.muscle, d.sets]));
  return core.filter((m) => (counts.get(m) ?? 0) < 4);
}

export function exerciseProgressSeries(exerciseName: string, sessions: SessionWithDetail[]): { label: string; value: number }[] {
  const points: { date: string; value: number }[] = [];
  for (const s of sessions) {
    for (const we of s.exercises) {
      if (we.exercise?.name !== exerciseName) continue;
      const orm = bestOneRepMax(we.sets);
      if (orm !== null) points.push({ date: s.started_at.slice(0, 10), value: orm });
    }
  }
  points.sort((a, b) => a.date.localeCompare(b.date));
  return points.slice(-10).map((p) => ({ label: p.date.slice(5), value: p.value }));
}

export function rollingAverage(values: number[], window = 7): number[] {
  return values.map((_, i) => {
    const slice = values.slice(Math.max(0, i - window + 1), i + 1);
    return round(slice.reduce((a, b) => a + b, 0) / slice.length, 1);
  });
}

export function templateSummary(
  template: WorkoutTemplate,
  items: (TemplateExercise & { exercise: Exercise | null })[]
): string {
  const groups = new Set(items.map((i) => i.exercise?.muscle_group).filter(Boolean));
  const n = items.length;
  return `${n} exercise${n === 1 ? "" : "s"} · ${groups.size} muscle group${groups.size === 1 ? "" : "s"} · about ${template.estimated_minutes} min`;
}

export function habitStreak(habitId: string, logs: HabitLog[]): number {
  const days = new Set(logs.filter((l) => l.habit_id === habitId && l.completed).map((l) => l.log_date));
  let streak = 0;
  for (let i = 0; i < 180; i++) {
    const day = dateOffset(i);
    if (days.has(day)) streak++;
    else if (i === 0) continue;
    else break;
  }
  return streak;
}

export function habitWeeklyRate(habit: Habit, logs: HabitLog[]): number {
  const week = logs.filter((l) => l.habit_id === habit.id && l.completed && l.log_date >= dateOffset(6));
  return Math.min(100, Math.round((new Set(week.map((l) => l.log_date)).size / Math.max(1, habit.target_per_week)) * 100));
}


