

export type Profile = {
  id: string;
  display_name: string;
  goal: string;
  activity_target: number;
  weekly_minutes: number;
  fitness_level: string;
  equipment: string;
  limitations: string;
  sleep_target_hours: number;
  step_target: number;
  calorie_target: number;
  protein_target_g: number;
  water_target_oz: number;
  target_weight_lb: number;
};

export type DailyMetric = {
  id: string;
  metric_date: string;
  /*
   * NOTE: the API returns null for a field that was never measured, and 0 for a measured zero.
   * These are declared as plain numbers because the app's existing charts, stat tiles and insights
   * all read them as numbers, and changing that contract means deciding how every view renders
   * missing data. Until that work is done deliberately, a null arriving here is a real gap in the
   * view layer rather than a type error. See MeasuredDailyMetric below and the null-safe
   * aggregation helpers in healthProviders.ts for code that must distinguish the two.
   */
  steps: number;
  sleep_hours: number;
  calories_burned: number;
  water_oz: number;
  resting_heart_rate: number;
  readiness: number;
  hrv: number;
  active_minutes: number;
  stress_level: number;
};

/**
 * A daily metric that distinguishes "not measured" from "measured zero".
 *
 * This is the shape provider data actually has, and the one any health merge or aggregate must use.
 * Merging into MeasuredDailyMetric keeps a missing value missing instead of inventing a zero.
 */
export type MeasuredDailyMetric = {
  id: string;
  metric_date: string;
  steps: number | null;
  sleep_hours: number | null;
  calories_burned: number | null;
  water_oz: number | null;
  resting_heart_rate: number | null;
  readiness: number | null;
  hrv: number | null;
  active_minutes: number | null;
  stress_level: number | null;
};

export type Workout = {
  id: string;
  title: string;
  workout_type: string;
  duration_minutes: number;
  calories_burned: number;
  intensity: string;
  workout_date: string;
  completed: boolean;
  notes: string | null;
  perceived_effort: number;
  distance_miles: number | null;
  created_at: string;
};

export type Meal = {
  id: string;
  meal_date: string;
  meal_type: string;
  name: string;
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
  fiber_g: number;
  source: string;
  notes: string | null;
  created_at: string;
};

export type BodyMetric = {
  id: string;
  metric_date: string;
  weight_lb: number;
  body_fat_pct: number;
  waist_in: number;
  chest_in: number;
  arm_in: number;
  thigh_in: number;
};

export type PersonalRecord = {
  id: string;
  exercise: string;
  record_value: number;
  unit: string;
  achieved_date: string;
  previous_value: number | null;
};

export type PlanSession = {
  id: string;
  day_index: number;
  title: string;
  workout_type: string;
  intensity: string;
  duration_minutes: number;
  completed: boolean;
};

export type HealthDevice = {
  id: string;
  provider: string | null;
  external_device_id: string | null;
  device_name: string;
  device_type: string;
  status: string;
  sync_status: string;
  last_error: string | null;
  last_sync_at: string | null;
  awaiting_first_sync: boolean;

  /**
   * Phase 11: the permission state an Android Health Connect bridge reported about itself.
   *
   * This is a DEVICE-REPORTED claim, not a server-verified fact. Health Connect permissions are
   * granted on the handset, so the backend has no way to observe them and only records what the
   * bridge says. It never gates access to any data.
   */
  permission_status: string | null;
};

export type CoachNotification = {
  id: string;
  title: string;
  message: string;
  kind: string;
  is_read: boolean;
  created_at: string;
};

export const WORKOUT_TYPES = [
  "Strength",
  "Cardio",
  "HIIT",
  "Yoga",
  "Mobility",
  "Cycling",
  "Running",
  "Swimming",
  "Walking",
] as const;

export const INTENSITY_LEVELS = ["Easy", "Moderate", "Hard", "Max"] as const;

export const MEAL_TYPES = ["Breakfast", "Lunch", "Dinner", "Snack"] as const;

export const FITNESS_LEVELS = ["Beginner", "Intermediate", "Advanced"] as const;

export const GOALS = [
  "Lose fat",
  "Build strength",
  "Improve endurance",
  "Stay active",
  "Improve mobility",
  "General health",
] as const;

export const EQUIPMENT_OPTIONS = [
  "Full gym",
  "Home gym",
  "Dumbbells only",
  "Bodyweight only",
  "Outdoor only",
] as const;

export const DAY_LABELS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;



