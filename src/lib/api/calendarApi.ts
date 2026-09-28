import { apiClient } from "./apiClient";

/**
 * The calendar's single source of truth.
 *
 * <p>Replaces the day-by-day endpoints that preceded it. Those issued five queries per day, so a
 * long window cost thousands of statements, and they grouped sessions by the UTC date rather than the
 * user's own calendar day. This asks for one bounded window and gets back everything the calendar
 * grid, day panel and history timeline render.
 */

/** One row per date that has any recorded activity. The grid derives its own empty cells. */
export type CalendarDay = {
  date: string;
  steps: number | null;
  water_oz: number | null;
  calories_burned: number | null;
  active_minutes: number | null;
  sleep_hours: number | null;
  readiness: number | null;
};

export type CalendarSession = {
  id: string;
  date: string;
  title: string;
  workout_type: string;
  duration_minutes: number | null;
  perceived_effort: number | null;
};

export type CalendarMeal = {
  id: string;
  date: string;
  meal_type: string;
  name: string;
  calories: number | null;
};

export type CalendarHabit = { id: string; date: string; name: string };

export type CalendarBody = {
  id: string;
  date: string;
  weight_lb: number | null;
  body_fat_pct: number | null;
  waist_in: number | null;
};

export type CalendarRecord = {
  id: string;
  date: string;
  exercise: string;
  record_value: number | null;
  unit: string;
};

export type CalendarSummary = {
  from: string;
  to: string;
  days: CalendarDay[];
  sessions: CalendarSession[];
  meals: CalendarMeal[];
  habitLogs: CalendarHabit[];
  bodyMetrics: CalendarBody[];
  personalRecords: CalendarRecord[];
};

/**
 * Reads one bounded window of calendar activity.
 *
 * <p>The range is the caller's responsibility: the server rejects a reversed or over-long window
 * with a 400 rather than serving an unbounded query.
 */
export const getCalendarSummary = (from: string, to: string) =>
  apiClient<CalendarSummary>(`/calendar/summary?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
