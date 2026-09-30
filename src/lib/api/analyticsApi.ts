import { apiClient } from "./apiClient";

/**
 * Phase 21: the typed client for the server-side analytics layer.
 *
 * <p>This file previously existed with five untyped exports and no importers. The backend contracts
 * those calls return are now stable and versioned, so each one is declared here as a type rather
 * than left as `unknown` at the call site. The client is the only place that knows the wire shape;
 * views work with these types and never re-derive a figure the server already owns.
 *
 * <h2>Null is not zero</h2>
 * Several measures are deliberately nullable. A `null` means the server has no recorded value;
 * `0` means a real measurement of zero. The types preserve that distinction so a view cannot turn
 * "nothing recorded" into "the user did nothing" by accident.
 */

/** A date range plus the timezone the server resolved it in. */
export type AnalyticsRange = {
  from: string;
  to: string;
};

/** How a trend series is grouped. */
export type TrendBucket = "day" | "week" | "month";

export type ActivitySummary = {
  steps: number;
  active_minutes: number;
  sleep_hours: number | null;
  calories_burned: number;
  water_oz: number;
  readiness: number;
};

export type NutritionSummary = {
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  /** Phase 21: aggregated from meals.fiber_g on the same rows as the other macros. */
  fiber_g: number;
};

/**
 * Training activity for one day.
 *
 * <p>`calories` is nullable: `workout_sessions` has no calorie column, so a window containing a
 * logged session has no known calorie total. Reporting 0 there would assert the session burned no
 * calories, which is a claim about a value nobody recorded.
 */
export type WorkoutSummary = {
  sessions: number;
  minutes: number;
  calories: number | null;
  quick_logged: number;
  logged_sessions: number;
};

export type BodySummary = {
  weight_lb: number;
  body_fat_pct: number;
};

export type TargetSummary = {
  step_target: number | null;
  calorie_target: number | null;
  protein_target_g: number | null;
  water_target_oz: number | null;
  target_weight_lb: number;
};

/**
 * The caller's day, as the server computed it.
 *
 * <p>`timezone` is the zone "today" was resolved in, and `timezone_resolved` says whether that is
 * the user's own stored zone or the UTC fallback applied because they have not set one. A view must
 * not describe a UTC fallback as the user's own calendar.
 */
export type DashboardAnalytics = AnalyticsRange & {
  date: string;
  timezone: string;
  timezone_resolved: boolean;
  activity: ActivitySummary;
  nutrition: NutritionSummary;
  workout: WorkoutSummary;
  body: BodySummary;
  targets: TargetSummary;
};

/** One day, week or month. Every measure is null when nothing was recorded in that bucket. */
export type TrendPoint = {
  start: string;
  end: string;
  /** How many days of this bucket the requested range covered; less than a full bucket is partial. */
  days: number;
  steps: number | null;
  active_minutes: number | null;
  /** The device's whole-day estimate. Never summed with workout_calories: they are different facts. */
  activity_calories_burned: number | null;
  sleep_hours_avg: number | null;
  nutrition_calories: number | null;
  protein_g: number | null;
  carbs_g: number | null;
  fat_g: number | null;
  fiber_g: number | null;
  workouts: number | null;
  workout_minutes: number | null;
  workout_calories: number | null;
  quick_logged: number | null;
  logged_sessions: number | null;
};

export type TrendsAnalytics = AnalyticsRange & {
  bucket: TrendBucket;
  timezone: string;
  timezone_resolved: boolean;
  /** Only buckets that hold data. A gap in the series is a real gap, not a zero. */
  buckets: TrendPoint[];
};

const range = (params?: Record<string, string | number>) => {
  const query = new URLSearchParams(params as Record<string, string>).toString();
  return query ? `?${query}` : "";
};

export const getDashboardAnalytics = () =>
  apiClient<DashboardAnalytics>("/analytics/dashboard");

export const getWorkoutAnalytics = (params?: Record<string, string | number>) =>
  apiClient(`/analytics/workouts${range(params)}`);

export const getNutritionAnalytics = (params?: Record<string, string | number>) =>
  apiClient(`/analytics/nutrition${range(params)}`);

export const getProgressAnalytics = (params?: Record<string, string | number>) =>
  apiClient(`/analytics/progress${range(params)}`);

export const getWeeklyAnalytics = (params?: Record<string, string | number>) =>
  apiClient(`/analytics/weekly${range(params)}`);

/**
 * Day, week or month rollups.
 *
 * <p>The server bounds the range at 366 inclusive days and rejects anything longer, so a caller that
 * wants a wider window must ask for several. `bucket` is validated server-side; an unknown value is a
 * structured 400 rather than a silently different aggregation.
 */
export const getTrendsAnalytics = (
  params?: Record<string, string | number>
): Promise<TrendsAnalytics> =>
  apiClient<TrendsAnalytics>(`/analytics/trends${range(params)}`);

export const getAnalytics = getDashboardAnalytics;
